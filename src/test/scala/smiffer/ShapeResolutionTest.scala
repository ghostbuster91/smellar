package smiffer

import software.amazon.smithy.model.Model

/** Resolution against a model assembled from IDL source, so the shape-lookup rules are covered
  * without reaching the network.
  */
class ShapeResolutionTest extends munit.FunSuite:

  private val source =
    """$version: "2"
      |
      |metadata suppressions = [
      |    {id: "UnreferencedShape", namespace: "com.acme.orders", reason: "library namespace"}
      |]
      |
      |namespace com.acme.orders
      |
      |/// A customer order.
      |structure Order {
      |    @required
      |    id: String
      |    note: String
      |}
      |
      |structure Shared {}
      |""".stripMargin

  private val other =
    """$version: "2"
      |namespace com.acme.other
      |
      |structure Shared {}
      |""".stripMargin

  private val loaded: LoadedModel =
    val model = Model
      .assembler()
      .addUnparsedModel("orders.smithy", source)
      .addUnparsedModel("other.smithy", other)
      .assemble()
      .unwrap()
    // Every shape here is "root": the test model has no JAR behind it, so widen the scope instead.
    LoadedModel(model, "test-model", Set.empty)

  private val everything = Scope(includeDependencies = true, includePrelude = false)

  test("a fully qualified shape ID resolves") {
    assertEquals(
      ShapeQuery.resolve(loaded, "com.acme.orders#Order", everything).map(_.getId.toString),
      Right("com.acme.orders#Order")
    )
  }

  test("a bare name resolves when it is unique") {
    assertEquals(
      ShapeQuery.resolve(loaded, "Order", everything).map(_.getId.toString),
      Right("com.acme.orders#Order")
    )
  }

  test("a bare name is matched case-insensitively") {
    assertEquals(
      ShapeQuery.resolve(loaded, "order", everything).map(_.getId.toString),
      Right("com.acme.orders#Order")
    )
  }

  test("a bare name present in two namespaces lists the candidates instead of guessing") {
    ShapeQuery.resolve(loaded, "Shared", everything) match
      case Left(SmifferError.AmbiguousShape(_, candidates)) =>
        assertEquals(candidates, List("com.acme.other#Shared", "com.acme.orders#Shared").sorted)
      case other => fail(s"expected an ambiguity error, got $other")
  }

  // A member is serialized as part of its container, so selecting one on its own used to render an
  // empty document and exit successfully.
  test("a member ID resolves to its container, so the output is never empty") {
    assertEquals(
      ShapeQuery.resolve(loaded, "com.acme.orders#Order$id", everything).map(_.getId.toString),
      Right("com.acme.orders#Order")
    )
  }

  test("a member ID still renders IDL containing that member") {
    val shape  = ShapeQuery.resolve(loaded, "com.acme.orders#Order$note", everything).toOption.get
    val output = Render.idl(loaded.model, List(shape))
    assert(output.contains("structure Order"), output)
    assert(output.contains("note"), output)
  }

  // Smithy throws IllegalArgumentException here; it used to reach the user as a raw stack-trace
  // class name rather than an explanation of the format.
  test("a malformed shape ID is reported as a shape ID problem") {
    ShapeQuery.resolve(loaded, "bad ns#!!", everything) match
      case Left(e: SmifferError.InvalidShapeId) =>
        assert(e.getMessage.contains("namespace#Name"), e.getMessage)
      case other => fail(s"expected InvalidShapeId, got $other")
  }

  test("an unknown shape suggests a near match") {
    ShapeQuery.resolve(loaded, "com.acme.orders#Ordr", everything) match
      case Left(SmifferError.ShapeNotFound(_, _, near)) =>
        assertEquals(near, List("com.acme.orders#Order"))
      case other => fail(s"expected ShapeNotFound with a suggestion, got $other")
  }

  test("rendering a structure includes its documentation and traits") {
    val shape  = ShapeQuery.resolve(loaded, "Order", everything).toOption.get
    val output = Render.idl(loaded.model, List(shape))
    assert(output.contains("/// A customer order."), output)
    assert(output.contains("@required"), output)
  }

  test("JSON rendering emits the shape under the AST's shapes map") {
    val shape  = ShapeQuery.resolve(loaded, "Order", everything).toOption.get
    val output = Render.json(loaded.model, List(shape))
    assert(output.contains("\"com.acme.orders#Order\""), output)
    assert(output.contains("\"type\": \"structure\""), output)
  }

  test("listing a namespace excludes member shapes") {
    val shapes = ShapeQuery.inNamespace(loaded, "com.acme.orders", everything).toOption.get
    assertEquals(shapes.map(_.getId.toString).sorted, List("com.acme.orders#Order", "com.acme.orders#Shared"))
  }

  test("an unknown namespace suggests a near match") {
    ShapeQuery.inNamespace(loaded, "com.acme.order", everything) match
      case Left(SmifferError.NamespaceNotFound(_, _, near)) => assert(near.contains("com.acme.orders"), near)
      case other                                            => fail(s"expected NamespaceNotFound, got $other")
  }

  test("the closure of a structure pulls in the shapes it references, minus the prelude") {
    val shape = ShapeQuery.resolve(loaded, "Order", everything).toOption.get
    val ids   = ShapeQuery.closure(loaded, shape, everything).map(_.getId.toString)
    assert(ids.contains("com.acme.orders#Order"), ids)
    assert(!ids.exists(_.startsWith("smithy.api#")), ids)
  }

  // The IDL serializer puts model metadata in a file of its own; concatenating every file appended
  // an unrelated `metadata suppressions` block and a second `$version` header to every answer.
  test("rendered IDL carries no model metadata") {
    val shape  = ShapeQuery.resolve(loaded, "Order", everything).toOption.get
    val output = Render.idl(loaded.model, List(shape))
    assert(!output.contains("metadata"), output)
    assert(!output.contains("suppressions"), output)
    assertEquals(output.linesIterator.count(_.startsWith("$version")), 1, output)
  }

  test("rendered JSON carries no model metadata") {
    val shape  = ShapeQuery.resolve(loaded, "Order", everything).toOption.get
    val output = Render.json(loaded.model, List(shape))
    assert(!output.contains("suppressions"), output)
  }
