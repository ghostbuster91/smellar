package smiffer

import cats.effect.IO
import cats.syntax.all.*
import coursier.complete.Complete
import coursier.{Dependency, Fetch, Repository, Resolve, Versions}

import java.nio.file.Path

/** A resolved artifact set: every JAR on the classpath, plus the subset belonging to the
  * coordinates the user actually asked about.
  *
  * Both matter. Trait definitions and referenced shapes live in the transitive JARs, so the whole
  * closure has to be loaded for a model to assemble at all — but when listing or searching, shapes
  * pulled in from dependencies are noise next to the ones the queried artifact defines itself.
  */
final case class ResolvedJars(all: Seq[Path], roots: Seq[Path])

final case class ResolvedDeps(root: String, deps: Seq[Dependency])

object CoursierClient:

  def fetchCoordinate(coord: MavenCoordinate, repositories: Seq[Repository])(using log: Log): IO[ResolvedJars] =
    fetch(List(coord), repositories)

  def fetch(coords: List[MavenCoordinate], repositories: Seq[Repository])(using log: Log): IO[ResolvedJars] =
    val label = coords.map(_.render).mkString(", ")
    val where = if repositories.isEmpty then "default repositories" else repositories.map(_.repr).mkString(", ")
    log.debug(s"resolving $label from $where") *>
      (jarsOf(coords, repositories, transitive = true), jarsOf(coords, repositories, transitive = false))
        .mapN(ResolvedJars.apply)
        .flatTap(r => log.debug(s"resolved ${r.all.size} jars (${r.roots.size} root)"))

  private def jarsOf(
      coords: List[MavenCoordinate],
      repositories: Seq[Repository],
      transitive: Boolean
  ): IO[Seq[Path]] =
    IO.blocking {
      Fetch()
        .addDependencies(coords.map(c => c.toDependency.copy(transitive = transitive))*)
        .addRepositories(repositories*)
        .run()
        .map(_.toPath)
        .filter(_.getFileName.toString.endsWith(".jar"))
    }.handleErrorWith { case e: coursier.error.CoursierError =>
      // Which coordinate failed is in coursier's own message; attach suggestions for the first one
      // so a typo'd artifact name gets a "did you mean" rather than just a wall of tried URLs.
      coords.headOption match
        case Some(head) =>
          suggest(head, repositories).flatMap(s => IO.raiseError(SmifferError.CoordinateNotFound(head, e, s)))
        case None => IO.raiseError(e)
    }

  def resolveDeps(coord: MavenCoordinate, repositories: Seq[Repository])(using log: Log): IO[ResolvedDeps] =
    log.debug(s"resolving dependency tree for ${coord.render}") *>
      IO.blocking {
        val resolution = Resolve()
          .addDependencies(coord.toDependency)
          .addRepositories(repositories*)
          .run()
        val deps = resolution.orderedDependencies
          .distinctBy(_.moduleVersionConstraint)
          .sortBy(d => s"${d.module.organization.value}:${d.module.name.value}:${d.versionConstraint.asString}")
        ResolvedDeps(coord.render, deps)
      }.handleErrorWith { case e: coursier.error.CoursierError =>
        suggest(coord, repositories).flatMap(s => IO.raiseError(SmifferError.CoordinateNotFound(coord, e, s)))
      }

  /** Nearby artifact names for a coordinate that did not resolve. Best-effort: a failure here must
    * never replace the resolution error the user is actually waiting on.
    */
  private def suggest(coord: MavenCoordinate, repositories: Seq[Repository]): IO[List[String]] =
    IO.blocking {
      def release(artifact: String): Option[String] =
        Some(
          Versions()
            .withModule(coord.copy(artifact = artifact).toModule)
            .addRepositories(repositories*)
            .run()
        ).map(_.release0.asString).filter(_.nonEmpty)

      val complete     = Complete().addRepositories(repositories*).copy(input = s"${coord.group}:${coord.artifact}")
      val completions  = complete.complete().unsafeRun()(using complete.cache.ec)._2.toList
      val exactArtifact = completions.contains(coord.artifact)

      if exactArtifact then release(coord.artifact).map(r => s"Artifact exists. Latest version: $r").toList
      else
        completions.take(5).map { name =>
          release(name).fold(s"${coord.group}:$name")(r => s"${coord.group}:$name:$r")
        }
    }.handleError(_ => Nil)
