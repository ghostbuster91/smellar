package smellar

import cats.effect.IO
import coursier.{Dependency, Module, ModuleName, Organization, Repository, VersionConstraint, Versions}

/** A `group:artifact:version` triple naming a published Smithy model JAR. */
final case class MavenCoordinate(group: String, artifact: String, version: String):
  def render: String = s"$group:$artifact:$version"

  def toModule: Module = Module(Organization(group), ModuleName(artifact))

  def toDependency: Dependency = Dependency(toModule, VersionConstraint(version))

  /** Turns the sentinel version `latest` into the repositories' current release. */
  def resolveLatest(extraRepositories: Seq[Repository] = Seq.empty): IO[MavenCoordinate] =
    if version != "latest" then IO.pure(this)
    else
      IO.blocking {
        val release = Versions().withModule(toModule).addRepositories(extraRepositories*).run().release0.asString
        if release.nonEmpty then copy(version = release)
        else
          throw SmellarError.CoordinateNotFound(
            this,
            RuntimeException(s"No versions found for '$group:$artifact'")
          )
      }

object MavenCoordinate:
  def parse(raw: String): Either[String, MavenCoordinate] =
    raw.split(":", -1).toList match
      case g :: a :: v :: Nil if g.trim.nonEmpty && a.trim.nonEmpty && v.trim.nonEmpty =>
        Right(MavenCoordinate(g.trim, a.trim, v.trim))
      case _ =>
        Left(
          s"Invalid coordinate '$raw'. Expected group:artifact:version " +
            "(exactly three non-empty segments separated by ':'). Use 'latest' as the version " +
            "to resolve the newest release."
        )
