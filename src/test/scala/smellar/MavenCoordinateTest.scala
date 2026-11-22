package smellar

class MavenCoordinateTest extends munit.FunSuite:

  test("parses a three-segment coordinate") {
    assertEquals(
      MavenCoordinate.parse("com.acme:acme-model:1.2.0"),
      Right(MavenCoordinate("com.acme", "acme-model", "1.2.0"))
    )
  }

  test("trims surrounding whitespace") {
    assertEquals(
      MavenCoordinate.parse(" com.acme : acme-model : 1.2.0 "),
      Right(MavenCoordinate("com.acme", "acme-model", "1.2.0"))
    )
  }

  test("keeps 'latest' as the version, to be resolved against the repositories") {
    assertEquals(MavenCoordinate.parse("com.acme:acme-model:latest").map(_.version), Right("latest"))
  }

  test("rejects a coordinate with too few segments") {
    assert(MavenCoordinate.parse("com.acme:acme-model").isLeft)
  }

  test("rejects a coordinate with too many segments") {
    assert(MavenCoordinate.parse("com.acme:acme-model:1.0:extra").isLeft)
  }

  test("rejects empty segments rather than resolving a blank version") {
    assert(MavenCoordinate.parse("com.acme::1.0").isLeft)
    assert(MavenCoordinate.parse("com.acme:acme-model:").isLeft)
  }

  test("the error names the expected format, since this is what a user sees on a typo") {
    val message = MavenCoordinate.parse("acme-model").left.getOrElse("")
    assert(message.contains("group:artifact:version"), message)
  }

  test("renders back to the coordinate it was parsed from") {
    assertEquals(MavenCoordinate("com.acme", "acme-model", "1.2.0").render, "com.acme:acme-model:1.2.0")
  }
