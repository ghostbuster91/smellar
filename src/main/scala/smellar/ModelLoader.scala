package smellar

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.loader.ModelAssembler
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.validation.{Severity, ValidationEvent}

import java.net.{URL, URLClassLoader}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** An assembled model plus the knowledge of which shapes came out of the queried artifacts.
  *
  * `label` describes where the model came from (a coordinate, or a project's config path) and is
  * used to say so in error messages.
  */
final class LoadedModel(val model: Model, val label: String, rootJarNames: Set[String]):

  /** True when `shape` was declared by one of the JARs the user asked about, rather than pulled in
    * from a transitive dependency.
    *
    * Shapes loaded from a JAR carry a source location like
    * `jar:file:/.../acme-model-1.0.jar!/META-INF/smithy/acme.smithy`, so the owning JAR's file name
    * is a substring of the location. A shape with no usable location (the prelude, synthetics)
    * counts as not-root, which is what callers want: those are never the answer to "what does this
    * artifact define?".
    */
  def definedInRoot(shape: Shape): Boolean =
    val file = shape.getSourceLocation.getFilename
    file != null && rootJarNames.exists(file.contains)

object ModelLoader:

  /** Loads every Smithy model reachable from `jars`.
    *
    * The whole closure is loaded even when only the root artifact is being listed: a model that
    * references a trait or shape from a dependency does not assemble without it.
    */
  def load(jars: ResolvedJars, source: String)(using log: Log): Resource[IO, LoadedModel] =
    classLoader(jars.all).evalMap { loader =>
      assemble(loader, source).map(LoadedModel(_, source, jars.roots.map(_.getFileName.toString).toSet))
    }

  private def classLoader(jars: Seq[Path]): Resource[IO, URLClassLoader] =
    Resource.fromAutoCloseable(
      IO.blocking(URLClassLoader(jars.map(_.toUri.toURL).toArray[URL], getClass.getClassLoader))
    )

  private def assemble(loader: URLClassLoader, source: String)(using log: Log): IO[Model] =
    for
      result <- IO.blocking {
                  Model
                    .assembler(loader)
                    // A model JAR can legitimately apply traits whose definitions were not
                    // published alongside it. Refusing to load at all would be worse than
                    // reporting the shapes without them.
                    .putProperty(ModelAssembler.ALLOW_UNKNOWN_TRAITS, true)
                    .discoverModels(loader)
                    .assemble()
                }
      events = result.getValidationEvents.asScala.toList
      errors = events.filter(_.getSeverity == Severity.ERROR)
      _     <- events.traverse_(e => log.debug(render(e)))
      model <- result.getResult.toScala match
                 case None        => IO.raiseError(SmellarError.ModelBroken(source, errors.map(render)))
                 case Some(model) => IO.pure(model)
      _     <- IO.raiseUnless(definesAnything(model))(SmellarError.NoModelsFound(source))
      // A model with errors still answers most lookups, so serve it — but never silently.
      _     <- log.warn(s"$source has ${errors.size} validation error(s); re-run with --debug to see them")
                 .whenA(errors.nonEmpty)
    yield model

  private def definesAnything(model: Model): Boolean =
    model.getShapeIds.asScala.exists(id => !Prelude.isPrelude(id.getNamespace))

  private def render(event: ValidationEvent): String =
    val where = Option(event.getSourceLocation).map(_.toString).filter(_.nonEmpty)
    val head  = s"[${event.getSeverity}] ${event.getId}"
    where.fold(s"$head: ${event.getMessage}")(w => s"$head $w: ${event.getMessage}")
