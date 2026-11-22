package smellar

/** Errors that are the user's to fix — reported as a plain message, never a stack trace. */
sealed trait SmellarError extends Throwable

object SmellarError:
  final case class CoordinateNotFound(
      coord: MavenCoordinate,
      cause: Throwable,
      suggestions: List[String] = Nil
  ) extends SmellarError:
    override def getMessage: String =
      val base =
        if suggestions.isEmpty then
          s"Could not resolve '${coord.render}'. Check the group ID, artifact ID and version."
        else if suggestions.head.startsWith("Artifact exists.") then
          s"Could not resolve '${coord.render}'.\n\n${suggestions.head}"
        else
          s"Could not resolve '${coord.render}'.\n\nDid you mean?\n" +
            suggestions.map(s => s"  $s").mkString("\n")
      // Coursier names every repository it consulted. "Is it even looking at my Artifactory?" is
      // the first question on any resolution failure, so it belongs in the error, not behind a flag.
      triedLocations.fold(base)(tried => s"$base\n\nTried:\n$tried")

    private def triedLocations: Option[String] =
      Option(cause.getMessage)
        .map(_.linesIterator.drop(1).map(_.trim).filter(_.nonEmpty).map(l => s"  $l").toList)
        .filter(_.nonEmpty)
        .map(_.mkString("\n"))

    override def getCause: Throwable = cause

  final case class NoModelsFound(source: String) extends SmellarError:
    override def getMessage: String =
      s"No Smithy models found in $source. A Smithy model JAR carries its models under " +
        "'META-INF/smithy/' with a 'manifest' file listing them."

  final case class ModelBroken(source: String, events: List[String]) extends SmellarError:
    override def getMessage: String =
      s"The Smithy model in $source could not be assembled:\n" + events.map(e => s"  $e").mkString("\n")

  final case class ShapeNotFound(query: String, source: String, nearMatches: List[String])
      extends SmellarError:
    override def getMessage: String =
      val base = s"Shape '$query' not found in $source."
      if nearMatches.isEmpty then
        s"$base\nUse 'smellar namespaces' to see what the model defines, or 'smellar search' to look by name."
      else s"$base\n\nDid you mean?\n" + nearMatches.map(m => s"  $m").mkString("\n")

  final case class InvalidShapeId(raw: String, reason: String) extends SmellarError:
    override def getMessage: String =
      s"'$raw' is not a valid shape ID: $reason\n" +
        "A shape ID looks like 'namespace#Name' (for example 'com.acme.orders#Order'); " +
        "a bare 'Name' works too."

  final case class AmbiguousShape(query: String, candidates: List[String]) extends SmellarError:
    override def getMessage: String =
      s"'$query' is ambiguous — it matches several namespaces. Qualify it as 'namespace#Name':\n" +
        candidates.map(c => s"  $c").mkString("\n")

  final case class NamespaceNotFound(namespace: String, source: String, nearMatches: List[String])
      extends SmellarError:
    override def getMessage: String =
      val base = s"No shapes found in namespace '$namespace' in $source."
      if nearMatches.isEmpty then s"$base\nUse 'smellar namespaces' to list the namespaces that exist."
      else s"$base\n\nDid you mean?\n" + nearMatches.map(m => s"  $m").mkString("\n")

  final case class NoProject(searchedFrom: String) extends SmellarError:
    override def getMessage: String =
      s"No 'smithy-build.json' found in '$searchedFrom' or any parent directory.\n" +
        "Run the project-aware commands from a Smithy project, or name a coordinate explicitly " +
        "with the '-external' variants (e.g. 'smellar get-external group:artifact:version com.acme#User')."

  final case class ProjectHasNoDependencies(configPath: String) extends SmellarError:
    override def getMessage: String =
      s"'$configPath' declares no 'maven.dependencies', so there are no model JARs to inspect."

  final case class MalformedProjectConfig(configPath: String, cause: Throwable) extends SmellarError:
    override def getMessage: String =
      s"Could not read '$configPath': ${Option(cause.getMessage).getOrElse(cause.getClass.getName)}"
    override def getCause: Throwable = cause
