package smellar

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import coursier.Repository
import software.amazon.smithy.model.shapes.Shape

/** Where a command gets its model from. */
enum Source:
  /** An explicit `group:artifact:version`, used by the `-external` commands. */
  case Coordinate(coord: MavenCoordinate)

  /** The `maven.dependencies` of the nearest `smithy-build.json`. */
  case Project

object Commands:

  def get(
      source: Source,
      query: String,
      asJson: Boolean,
      withClosure: Boolean,
      scope: Scope,
      repositories: List[Repository]
  )(using log: Log): IO[Unit] =
    withModel(source, repositories) { loaded =>
      // A bare name is resolved against the whole model, not just the root artifact: asking for a
      // shape by name should find it wherever it lives, even if it came from a dependency.
      val lookupScope = scope.copy(includeDependencies = true)
      IO.fromEither(ShapeQuery.resolve(loaded, query, lookupScope).leftMap(identity[Throwable])).flatMap { shape =>
        val selected = if withClosure then ShapeQuery.closure(loaded, shape, lookupScope) else List(shape)
        emit(if asJson then Render.json(loaded.model, selected) else Render.idl(loaded.model, selected))
      }
    }

  def list(
      source: Source,
      namespace: String,
      limit: Int,
      asJson: Boolean,
      scope: Scope,
      repositories: List[Repository]
  )(using log: Log): IO[Unit] =
    withModel(source, repositories) { loaded =>
      IO.fromEither(ShapeQuery.inNamespace(loaded, namespace, scope).leftMap(identity[Throwable])).flatMap { shapes =>
        bounded(shapes, limit).flatMap { shown =>
          emit(if asJson then Render.json(loaded.model, shown) else Render.shapeLines(shown))
        }
      }
    }

  def search(
      source: Source,
      query: String,
      limit: Int,
      asJson: Boolean,
      scope: Scope,
      repositories: List[Repository]
  )(using log: Log): IO[Unit] =
    withModel(source, repositories) { loaded =>
      val matches = ShapeQuery.search(loaded, query, scope)
      if matches.isEmpty then
        emit(s"No shape name matches '$query' in ${loaded.label}.") *>
          log.info("searching only shapes the queried artifact defines; add --include-dependencies to widen")
            .whenA(!scope.includeDependencies)
      else
        bounded(matches, limit).flatMap { shown =>
          emit(if asJson then Render.json(loaded.model, shown) else Render.shapeLines(shown))
        }
    }

  def namespaces(source: Source, scope: Scope, repositories: List[Repository])(using log: Log): IO[Unit] =
    withModel(source, repositories) { loaded =>
      emit(Render.namespaceOverview(ShapeQuery.namespaces(loaded, scope)))
    }

  def deps(coord: MavenCoordinate, repositories: List[Repository])(using log: Log): IO[Unit] =
    CoursierClient.resolveDeps(coord, repositories).flatMap(r => emit(Render.depsList(r)))

  /** Resolves `source` to a set of JARs, assembles the model, and hands it to `use`. */
  private def withModel[A](source: Source, repositories: List[Repository])(
      use: LoadedModel => IO[A]
  )(using log: Log): IO[A] =
    modelResource(source, repositories).use(use)

  private def modelResource(source: Source, repositories: List[Repository])(using
      log: Log
  ): Resource[IO, LoadedModel] =
    source match
      case Source.Coordinate(raw) =>
        for
          coord  <- Resource.eval(raw.resolveLatest(repositories))
          jars   <- Resource.eval(CoursierClient.fetchCoordinate(coord, repositories))
          loaded <- ModelLoader.load(jars, coord.render)
        yield loaded

      case Source.Project =>
        for
          project <- Resource.eval(SmithyProject.discover(SmithyProject.cwd))
          // The project's own repositories are consulted too — a model published to a private
          // Artifactory is unreachable without them, and the project already names them.
          projectRepos <- Resource.eval(IO.fromEither(resolveProjectRepos(project)))
          allRepos = (repositories ++ projectRepos).distinct
          coords <- Resource.eval(project.dependencies.traverse(_.resolveLatest(allRepos)))
          jars   <- Resource.eval(CoursierClient.fetch(coords, allRepos))
          loaded <- ModelLoader.load(jars, project.configPath.toString)
        yield loaded

  private def resolveProjectRepos(project: SmithyProject): Either[Throwable, List[Repository]] =
    project.repositoryUrls
      .traverse(url => Repositories.parse(url).leftMap(why => s"maven.repositories: $why"))
      .leftMap(why => SmellarError.MalformedProjectConfig(project.configPath.toString, RuntimeException(why)))

  /** Truncates to `limit`, telling the user on stderr when something was cut.
    *
    * The note is unconditional, not a verbose-only log line: silently dropping results would make
    * an incomplete listing look complete.
    */
  private def bounded(shapes: List[Shape], limit: Int): IO[List[Shape]] =
    if shapes.length <= limit then IO.pure(shapes)
    else
      IO.blocking(
        System.err.println(
          s"[smellar] showing $limit of ${shapes.length} results; use --limit to raise the cap"
        )
      ).as(shapes.take(limit))

  private def emit(text: String): IO[Unit] = IO.blocking(System.out.println(text))
