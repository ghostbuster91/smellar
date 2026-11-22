package smellar

import coursier.{MavenRepository, Repository}

object Repositories:
  /** Repository URLs from `SMELLAR_REPOSITORIES` (comma or whitespace separated). */
  def fromEnv(env: Map[String, String]): List[String] =
    env.get("SMELLAR_REPOSITORIES").toList.flatMap(_.split("[,\\s]+").toList).map(_.trim).filter(_.nonEmpty)

  def parse(raw: String): Either[String, MavenRepository] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left("repository URL is empty")
    else
      try
        val uri = java.net.URI(trimmed)
        if !uri.isAbsolute then Left(s"'$trimmed' is not an absolute URL")
        else if uri.getScheme == null || !Set("http", "https", "file").contains(uri.getScheme.toLowerCase) then
          Left(s"'$trimmed' must use http, https or file")
        else Right(MavenRepository(trimmed))
      catch case e: IllegalArgumentException => Left(s"'$trimmed' is not a valid URL: ${e.getMessage}")

  /** Environment first, then command-line `-r`; duplicates collapse ignoring a trailing slash. */
  def effective(fromEnv: List[MavenRepository], fromFlags: List[MavenRepository]): List[Repository] =
    (fromEnv ++ fromFlags).distinctBy(_.root.stripSuffix("/"))
