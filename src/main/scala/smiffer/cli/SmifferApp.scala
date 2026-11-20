package smiffer.cli

import cats.data.Validated
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import com.monovore.decline.{Command, Opts}
import coursier.Repository
import smiffer.*

object SmifferApp extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    val command = Command("smiffer", "Inspect Smithy models published as Maven JARs")(
      subcommands <+> versionFlag
    )
    command.parse(args, sys.env) match
      case Left(help) if help.errors.nonEmpty =>
        IO.blocking(System.err.println(help)).as(ExitCode.Error)
      case Left(help) =>
        IO.blocking(System.out.println(help)).as(ExitCode.Success)
      case Right(action) =>
        action
  end run

  private def versionFlag: Opts[IO[ExitCode]] =
    Opts
      .flag("version", "Print the version and exit", short = "V")
      .map(_ => IO.println(BuildInfo.version).as(ExitCode.Success))

  private def subcommands: Opts[IO[ExitCode]] =
    getCmd orElse getExternalCmd orElse
      listCmd orElse listExternalCmd orElse
      searchCmd orElse searchExternalCmd orElse
      namespacesCmd orElse namespacesExternalCmd orElse
      depsCmd

  // ---------------------------------------------------------------- arguments

  private def shapeArg: Opts[String]     = Opts.argument[String]("shape-id")
  private def namespaceArg: Opts[String] = Opts.argument[String]("namespace")
  private def queryArg: Opts[String]     = Opts.argument[String]("query")
  private def coordArg: Opts[String]     = Opts.argument[String]("coordinate")

  private def asJson: Opts[Boolean] =
    Opts.flag("json", "Emit the Smithy JSON AST instead of IDL").orFalse

  private def withClosure: Opts[Boolean] =
    Opts
      .flag("closure", "Also render every shape this one transitively references", short = "c")
      .orFalse

  private def limit: Opts[Int] =
    Opts.option[Int]("limit", "Maximum number of results", short = "l", metavar = "N").withDefault(100)

  private def scope: Opts[Scope] =
    (
      Opts
        .flag(
          "include-dependencies",
          "Include shapes defined by dependency JARs, not just the queried artifact",
          short = "d"
        )
        .orFalse,
      Opts.flag("include-prelude", "Include Smithy's own prelude shapes").orFalse
    ).mapN(Scope.apply)

  /** `-r` values on top of `SMIFFER_REPOSITORIES`, so a private Artifactory can be configured once. */
  private def repositories: Opts[List[Repository]] =
    Opts
      .options[String](
        "repository",
        "Extra Maven repository URL (repeatable), appended to $SMIFFER_REPOSITORIES",
        short = "r",
        metavar = "url"
      )
      .orEmpty
      .mapValidated { flags =>
        def validate(where: String)(raw: String) =
          Repositories.parse(raw).leftMap(why => s"Invalid $where: $why").toValidatedNel
        (
          Repositories.fromEnv(sys.env).traverse(validate("SMIFFER_REPOSITORIES entry")),
          flags.traverse(validate("--repository"))
        ).mapN(Repositories.effective)
      }

  private def log: Opts[Log] =
    (
      Opts.flag("verbose", "Log progress to stderr", short = "v").orFalse,
      Opts.flag("debug", "Log detailed diagnostics and stack traces to stderr").orFalse
    ).mapN { (verbose, debug) =>
      val level =
        if debug then LogLevel.Debug
        else if verbose then LogLevel.Verbose
        else LogLevel.fromEnv(sys.env)
      Log(level)
    }

  private def coordinate: Opts[MavenCoordinate] =
    coordArg.mapValidated(raw => Validated.fromEither(MavenCoordinate.parse(raw)).toValidatedNel)

  // ---------------------------------------------------------------- project-aware commands

  private def getCmd: Opts[IO[ExitCode]] =
    Opts.subcommand("get", "Render a shape from the current project's model dependencies") {
      (shapeArg, asJson, withClosure, scope, repositories, log).mapN { (shape, json, closure, sc, repos, logger) =>
        runWith(logger)(Commands.get(Source.Project, shape, json, closure, sc, repos)(using logger))
      }
    }

  private def listCmd: Opts[IO[ExitCode]] =
    Opts.subcommand("list", "List the shapes in a namespace of the current project's dependencies") {
      (namespaceArg, limit, asJson, scope, repositories, log).mapN { (ns, lim, json, sc, repos, logger) =>
        runWith(logger)(Commands.list(Source.Project, ns, lim, json, sc, repos)(using logger))
      }
    }

  private def searchCmd: Opts[IO[ExitCode]] =
    Opts.subcommand("search", "Find shapes by name in the current project's dependencies") {
      (queryArg, limit, asJson, scope, repositories, log).mapN { (q, lim, json, sc, repos, logger) =>
        runWith(logger)(Commands.search(Source.Project, q, lim, json, sc, repos)(using logger))
      }
    }

  private def namespacesCmd: Opts[IO[ExitCode]] =
    Opts.subcommand("namespaces", "Summarise the namespaces in the current project's dependencies") {
      (scope, repositories, log).mapN { (sc, repos, logger) =>
        runWith(logger)(Commands.namespaces(Source.Project, sc, repos)(using logger))
      }
    }

  // ---------------------------------------------------------------- coordinate commands

  private def getExternalCmd: Opts[IO[ExitCode]] =
    Opts.subcommand("get-external", "Render a shape from a published model JAR") {
      (coordinate, shapeArg, asJson, withClosure, scope, repositories, log).mapN {
        (coord, shape, json, closure, sc, repos, logger) =>
          runWith(logger)(Commands.get(Source.Coordinate(coord), shape, json, closure, sc, repos)(using logger))
      }
    }

  private def listExternalCmd: Opts[IO[ExitCode]] =
    Opts.subcommand("list-external", "List the shapes in a namespace of a published model JAR") {
      (coordinate, namespaceArg, limit, asJson, scope, repositories, log).mapN {
        (coord, ns, lim, json, sc, repos, logger) =>
          runWith(logger)(Commands.list(Source.Coordinate(coord), ns, lim, json, sc, repos)(using logger))
      }
    }

  private def searchExternalCmd: Opts[IO[ExitCode]] =
    Opts.subcommand("search-external", "Find shapes by name in a published model JAR") {
      (coordinate, queryArg, limit, asJson, scope, repositories, log).mapN {
        (coord, q, lim, json, sc, repos, logger) =>
          runWith(logger)(Commands.search(Source.Coordinate(coord), q, lim, json, sc, repos)(using logger))
      }
    }

  private def namespacesExternalCmd: Opts[IO[ExitCode]] =
    Opts.subcommand("namespaces-external", "Summarise the namespaces in a published model JAR") {
      (coordinate, scope, repositories, log).mapN { (coord, sc, repos, logger) =>
        runWith(logger)(Commands.namespaces(Source.Coordinate(coord), sc, repos)(using logger))
      }
    }

  private def depsCmd: Opts[IO[ExitCode]] =
    Opts.subcommand("deps", "Print the transitive dependencies of a model JAR") {
      (coordinate, repositories, log).mapN { (coord, repos, logger) =>
        runWith(logger)(Commands.deps(coord, repos)(using logger))
      }
    }

  // ---------------------------------------------------------------- error reporting

  /** User errors print their message and nothing else; anything unexpected keeps its type so a bug
    * is distinguishable from a typo. Stack traces are `--debug` only.
    */
  private def runWith(logger: Log)(body: IO[Unit]): IO[ExitCode] =
    body.as(ExitCode.Success).handleErrorWith {
      case e: SmifferError =>
        IO.blocking(System.err.println(e.getMessage)).as(ExitCode.Error)
      case e =>
        val detail = Option(e.getMessage).getOrElse("(no message)")
        IO.blocking(System.err.println(s"smiffer: ${e.getClass.getSimpleName}: $detail")) *>
          logger.debugTrace(e).as(ExitCode.Error)
    }
