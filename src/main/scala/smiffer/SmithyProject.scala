package smiffer

import cats.effect.IO
import software.amazon.smithy.build.model.SmithyBuildConfig

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** A `smithy-build.json` and the model JARs it declares. */
final case class SmithyProject(
    configPath: Path,
    dependencies: List[MavenCoordinate],
    repositoryUrls: List[String]
)

object SmithyProject:
  private val ConfigName = "smithy-build.json"

  /** Walks up from `start` looking for `smithy-build.json`, the way a build tool would. */
  def find(start: Path): IO[Path] =
    IO.blocking {
      val from = start.toAbsolutePath.normalize
      Iterator
        .iterate(from)(_.getParent)
        .takeWhile(_ != null)
        .map(_.resolve(ConfigName))
        .find(Files.isRegularFile(_))
    }.flatMap {
      case Some(found) => IO.pure(found)
      case None        => IO.raiseError(SmifferError.NoProject(start.toAbsolutePath.normalize.toString))
    }

  def load(configPath: Path)(using log: Log): IO[SmithyProject] =
    IO.blocking(SmithyBuildConfig.load(configPath))
      .adaptError { case e if !e.isInstanceOf[SmifferError] =>
        SmifferError.MalformedProjectConfig(configPath.toString, e)
      }
      .flatMap { config =>
        val maven = config.getMaven.toScala
        val rawDeps = maven.toList.flatMap(_.getDependencies.asScala.toList)
        val repos   = maven.toList.flatMap(_.getRepositories.asScala.toList.map(_.getUrl))

        // smithy-build.json dependency strings are plain Maven coordinates; a malformed one is the
        // user's typo in their own build file, so name the file and the entry rather than failing
        // the whole lookup silently.
        parseAll(rawDeps) match
          case Left((entry, why)) =>
            IO.raiseError(
              SmifferError.MalformedProjectConfig(
                configPath.toString,
                RuntimeException(s"maven.dependencies entry '$entry': $why")
              )
            )
          case Right(Nil) => IO.raiseError(SmifferError.ProjectHasNoDependencies(configPath.toString))
          case Right(deps) =>
            log.debug(
              s"$configPath declares ${deps.size} dependencies" +
                (if repos.isEmpty then "" else s" and ${repos.size} repositories")
            ).as(SmithyProject(configPath, deps, repos))
      }

  def discover(cwd: Path)(using log: Log): IO[SmithyProject] =
    find(cwd).flatMap(load)

  def cwd: Path = Paths.get(sys.props.getOrElse("user.dir", "."))

  /** Parses every dependency entry, reporting which one failed alongside the reason. */
  private def parseAll(entries: List[String]): Either[(String, String), List[MavenCoordinate]] =
    entries.foldLeft[Either[(String, String), List[MavenCoordinate]]](Right(Nil)) { (acc, entry) =>
      acc.flatMap(done => MavenCoordinate.parse(entry).left.map(entry -> _).map(done :+ _))
    }
