package smellar

import coursier.MavenRepository

class RepositoriesTest extends munit.FunSuite:

  test("accepts http, https and file URLs") {
    assert(Repositories.parse("https://artifactory.example.com/maven").isRight)
    assert(Repositories.parse("http://localhost:8081/repo").isRight)
    assert(Repositories.parse("file:///home/me/.m2/repository").isRight)
  }

  test("rejects a relative path, which would silently resolve nothing") {
    assert(Repositories.parse("maven/releases").isLeft)
  }

  test("rejects an unsupported scheme") {
    assert(Repositories.parse("ftp://example.com/maven").isLeft)
  }

  test("rejects an empty value, so a stray comma in the env var is reported") {
    assert(Repositories.parse("   ").isLeft)
  }

  test("env var splits on commas and whitespace, ignoring blanks") {
    val env = Map("SMELLAR_REPOSITORIES" -> "https://a.example/maven, https://b.example/maven,,")
    assertEquals(Repositories.fromEnv(env), List("https://a.example/maven", "https://b.example/maven"))
  }

  test("no env var means no repositories") {
    assertEquals(Repositories.fromEnv(Map.empty), Nil)
  }

  test("flag repositories are appended after the configured ones") {
    val configured = List(MavenRepository("https://a.example/maven"))
    val flags      = List(MavenRepository("https://b.example/maven"))
    assertEquals(
      Repositories.effective(configured, flags).map(_.repr),
      List(MavenRepository("https://a.example/maven"), MavenRepository("https://b.example/maven")).map(_.repr)
    )
  }

  test("the same repository given twice collapses, ignoring a trailing slash") {
    val configured = List(MavenRepository("https://a.example/maven/"))
    val flags      = List(MavenRepository("https://a.example/maven"))
    assertEquals(Repositories.effective(configured, flags).size, 1)
  }
