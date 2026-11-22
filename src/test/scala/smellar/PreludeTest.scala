package smellar

class PreludeTest extends munit.FunSuite:

  test("smithy.api is the prelude") {
    assert(Prelude.isPrelude("smithy.api"))
  }

  // These namespaces are published as their own artifacts. Counting them as prelude made
  // `smellar namespaces-external software.amazon.smithy:smithy-waiters:latest` report that the
  // artifact contained no models at all.
  test("separately-published smithy.* trait packages are not the prelude") {
    assert(!Prelude.isPrelude("smithy.waiters"))
    assert(!Prelude.isPrelude("smithy.test"))
    assert(!Prelude.isPrelude("smithy.rules"))
  }

  test("a user namespace is never the prelude") {
    assert(!Prelude.isPrelude("com.acme"))
    assert(!Prelude.isPrelude("aws.api"))
  }
