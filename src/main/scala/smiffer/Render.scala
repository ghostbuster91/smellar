package smiffer

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.shapes.{ModelSerializer, Shape, ShapeId, SmithyIdlModelSerializer}
import software.amazon.smithy.model.traits.DocumentationTrait

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

object Render:

  /** The selected shapes as Smithy IDL source.
    *
    * Smithy's own IDL serializer is used rather than a hand-rolled printer, so what comes out is
    * real, re-parseable IDL — traits, defaults and documentation included — instead of an
    * approximation that drifts as the spec grows.
    */
  def idl(model: Model, shapes: List[Shape]): String =
    val wanted = shapes.map(_.getId).toSet
    val serializer = SmithyIdlModelSerializer
      .builder()
      // Members are serialized as part of their container, but the predicate still sees them;
      // keeping a member whose container is selected avoids dropping it from the output.
      .shapeFilter(shape => wanted.contains(shape.getId) || containerOf(shape.getId).exists(wanted.contains))
      // The model's own metadata (validator suppressions and the like) is serialized into a file of
      // its own. It has nothing to do with the shape that was asked for, and it would be appended
      // to every single answer with its own `$version` header.
      .metadataFilter(_ => false)
      .build()
    val byFile = serializer.serialize(model).asScala.toList.sortBy((path, _) => path.toString)
    // One file per namespace; a multi-namespace selection is concatenated in namespace order.
    // Filtering the metadata out above leaves its file behind as a bare `$version` header, so
    // blocks that declare nothing are dropped rather than trailing the answer.
    byFile.map((_, text) => text.trim).filter(declaresSomething).mkString("\n\n")

  /** False for a block that is only a `$version` header and blank lines. */
  private def declaresSomething(block: String): Boolean =
    block.linesIterator.map(_.trim).exists(line => line.nonEmpty && !line.startsWith("$version"))

  /** The selected shapes as the Smithy JSON AST — the machine-readable form. */
  def json(model: Model, shapes: List[Shape]): String =
    val wanted = shapes.map(_.getId).toSet
    val serializer = ModelSerializer
      .builder()
      .shapeFilter(shape => wanted.contains(shape.getId) || containerOf(shape.getId).exists(wanted.contains))
      .metadataFilter(_ => false)
      .build()
    Node.prettyPrintJson(serializer.serialize(model))

  def namespaceOverview(summaries: List[NamespaceSummary]): String =
    if summaries.isEmpty then "No shapes found."
    else
      val width = summaries.map(_.namespace.length).max
      val lines = summaries.map { s =>
        val shapes   = if s.shapeCount == 1 then "1 shape" else s"${s.shapeCount} shapes"
        val services = if s.services.isEmpty then "" else s"  services: ${s.services.mkString(", ")}"
        s"  ${s.namespace.padTo(width, ' ')}  $shapes$services"
      }
      val total = summaries.map(_.shapeCount).sum
      s"${summaries.size} namespace(s), $total shapes\n\n${lines.mkString("\n")}"

  /** A one-line-per-shape listing: `type  namespace#Name  — first line of docs`. */
  def shapeLines(shapes: List[Shape]): String =
    if shapes.isEmpty then "No shapes found."
    else
      val width = shapes.map(_.getType.toString.length).max
      shapes
        .map { shape =>
          val kind = shape.getType.toString.padTo(width, ' ')
          val doc  = summaryDoc(shape).fold("")(d => s"  — $d")
          s"$kind  ${shape.getId}$doc"
        }
        .mkString("\n")

  def depsList(resolved: ResolvedDeps): String =
    val header = s"${resolved.root}\n  (${resolved.deps.size} transitive dependencies)"
    val deps = resolved.deps
      .map(d => s"  ${d.module.organization.value}:${d.module.name.value}:${d.versionConstraint.asString}")
      .mkString("\n")
    if deps.isEmpty then header else s"$header\n\n$deps"

  /** The first sentence of a shape's `@documentation`, flattened to one line. */
  private def summaryDoc(shape: Shape): Option[String] =
    shape
      .getTrait(classOf[DocumentationTrait])
      .toScala
      .map(_.getValue.linesIterator.map(_.trim).filter(_.nonEmpty).mkString(" "))
      .filter(_.nonEmpty)
      .map(text => if text.length > 100 then text.take(97) + "..." else text)

  /** For a member ID (`ns#Struct$member`), the ID of the structure that declares it. */
  private def containerOf(id: ShapeId): Option[ShapeId] =
    id.getMember.toScala.map(_ => id.withoutMember)
