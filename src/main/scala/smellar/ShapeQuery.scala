package smellar

import software.amazon.smithy.model.neighbor.Walker
import software.amazon.smithy.model.shapes.{Shape, ShapeId, ShapeType}

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Which shapes a command is allowed to see. */
final case class Scope(includeDependencies: Boolean, includePrelude: Boolean):
  def allows(loaded: LoadedModel)(shape: Shape): Boolean =
    val preludeOk = includePrelude || !Prelude.isPrelude(shape.getId.getNamespace)
    val sourceOk  = includeDependencies || loaded.definedInRoot(shape)
    preludeOk && sourceOk

object Scope:
  /** Shapes the queried artifact defines itself, excluding Smithy's own prelude. */
  val rootOnly: Scope = Scope(includeDependencies = false, includePrelude = false)

final case class NamespaceSummary(namespace: String, shapeCount: Int, services: List[String])

object ShapeQuery:

  /** Every non-member shape in scope. Member shapes are excluded: they are listed as part of the
    * structure that declares them, and on their own they just triple the size of every listing.
    */
  def visibleShapes(loaded: LoadedModel, scope: Scope): List[Shape] =
    loaded.model.toSet.asScala.toList
      .filterNot(_.isMemberShape)
      .filter(scope.allows(loaded))

  def namespaces(loaded: LoadedModel, scope: Scope): List[NamespaceSummary] =
    visibleShapes(loaded, scope)
      .groupBy(_.getId.getNamespace)
      .toList
      .map { (namespace, shapes) =>
        val services = shapes.filter(_.getType == ShapeType.SERVICE).map(_.getId.getName).sorted
        NamespaceSummary(namespace, shapes.size, services)
      }
      .sortBy(_.namespace)

  /** Shapes declared in `namespace`, sorted with services first — a service is the entry point of a
    * model, so it is what you want to see at the top of a listing.
    */
  def inNamespace(loaded: LoadedModel, namespace: String, scope: Scope): Either[SmellarError, List[Shape]] =
    val all   = visibleShapes(loaded, scope)
    val found = all.filter(_.getId.getNamespace == namespace)
    if found.nonEmpty then Right(found.sortBy(s => (typeRank(s.getType), s.getId.getName)))
    else
      val near = all.map(_.getId.getNamespace).distinct.filter(ns => isNear(namespace, ns)).sorted.take(5)
      Left(SmellarError.NamespaceNotFound(namespace, loaded.label, near))

  /** Case-insensitive substring search over shape names, or over full shape IDs when the query
    * looks like one (contains `#` or a dot).
    */
  def search(loaded: LoadedModel, query: String, scope: Scope): List[Shape] =
    val lowered = query.toLowerCase
    val byId    = lowered.contains('#') || lowered.contains('.')
    def target(shape: Shape): String =
      if byId then shape.getId.toString else shape.getId.getName
    visibleShapes(loaded, scope)
      .filter(s => target(s).toLowerCase.contains(lowered))
      .sortBy(s => rankKey(lowered, target(s)))

  /** Resolves a user-supplied shape reference.
    *
    * `namespace#Name` is looked up exactly. A bare `Name` is matched case-insensitively across
    * every namespace in scope, which is what makes the tool usable without knowing the namespace
    * up front — but an ambiguous bare name is an error rather than a silent pick.
    */
  def resolve(loaded: LoadedModel, query: String, scope: Scope): Either[SmellarError, Shape] =
    val candidates = visibleShapes(loaded, scope)
    if query.contains('#') then
      for
        id <- parseShapeId(query.trim)
        shape <- loaded.model.getShape(id).toScala.toRight(notFound(loaded, query, candidates))
        // A member is not a standalone shape in the IDL — it is printed as part of its container,
        // so selecting one on its own serializes to nothing at all. Answer with the container,
        // where the member the user asked about is visible.
        resolved <- containerIfMember(loaded, shape)
      yield resolved
    else
      val bare = query.trim
      candidates.filter(_.getId.getName.equalsIgnoreCase(bare)) match
        case single :: Nil => Right(single)
        case Nil           => Left(notFound(loaded, bare, candidates))
        case many          => Left(SmellarError.AmbiguousShape(bare, many.map(_.getId.toString).sorted))

  /** The shape plus everything it transitively references — members' targets, a service's
    * operations, those operations' inputs and outputs, and so on.
    */
  def closure(loaded: LoadedModel, shape: Shape, scope: Scope): List[Shape] =
    Walker(loaded.model)
      .walkShapes(shape)
      .asScala
      .toList
      .filterNot(_.isMemberShape)
      // The closure deliberately ignores the root-only part of the scope: a structure whose members
      // point into a shared dependency is exactly the case `--closure` exists to answer. The
      // prelude stays out, since `String` and `Integer` need no introduction.
      .filter(s => scope.includePrelude || !Prelude.isPrelude(s.getId.getNamespace))
      .sortBy(s => (s.getId.getNamespace, typeRank(s.getType), s.getId.getName))

  /** Smithy throws on a malformed ID; turn that into a message that explains the format. */
  private def parseShapeId(raw: String): Either[SmellarError, ShapeId] =
    try Right(ShapeId.from(raw))
    catch
      case e: IllegalArgumentException =>
        Left(SmellarError.InvalidShapeId(raw, Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))

  private def containerIfMember(loaded: LoadedModel, shape: Shape): Either[SmellarError, Shape] =
    shape.asMemberShape.toScala match
      case None => Right(shape)
      case Some(member) =>
        loaded.model
          .getShape(member.getContainer)
          .toScala
          .toRight(SmellarError.ShapeNotFound(member.getContainer.toString, loaded.label, Nil))

  private def notFound(loaded: LoadedModel, query: String, candidates: List[Shape]): SmellarError =
    val bare = query.split("#").lastOption.getOrElse(query)
    val near = candidates
      .map(_.getId)
      .filter(id => isNear(bare, id.getName))
      .map(_.toString)
      .sorted
      .take(5)
    SmellarError.ShapeNotFound(query, loaded.label, near)

  /** Cheap "did you mean": a shared prefix, a substring either way, or one typo apart. */
  private def isNear(query: String, candidate: String): Boolean =
    val q = query.toLowerCase
    val c = candidate.toLowerCase
    q == c || c.contains(q) || q.contains(c) ||
      (math.abs(q.length - c.length) <= 2 && editDistanceAtMost(q, c, 2))

  private def editDistanceAtMost(a: String, b: String, max: Int): Boolean =
    if math.abs(a.length - b.length) > max then false
    else
      var prev = Array.tabulate(b.length + 1)(identity)
      for i <- 1 to a.length do
        val cur = Array.fill(b.length + 1)(0)
        cur(0) = i
        for j <- 1 to b.length do
          val cost = if a(i - 1) == b(j - 1) then 0 else 1
          cur(j) = math.min(math.min(cur(j - 1) + 1, prev(j) + 1), prev(j - 1) + cost)
        prev = cur
      prev(b.length) <= max

  /** Exact match first, then prefix match, then shorter names — so `User` beats `UserSummary`. */
  private[smellar] def rankKey(loweredQuery: String, name: String): (Int, Int, Int, String) =
    val lowered = name.toLowerCase
    val exact   = if lowered == loweredQuery then 0 else 1
    val prefix  = if lowered.startsWith(loweredQuery) then 0 else 1
    (exact, prefix, name.length, name)

  /** Services, then resources, then operations, then everything else: the order you read a model in. */
  private def typeRank(shapeType: ShapeType): Int = shapeType match
    case ShapeType.SERVICE   => 0
    case ShapeType.RESOURCE  => 1
    case ShapeType.OPERATION => 2
    case _                   => 3
