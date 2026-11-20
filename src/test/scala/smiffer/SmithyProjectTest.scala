package smiffer

import cats.effect.unsafe.implicits.global

import java.nio.file.{Files, Path}

class SmithyProjectTest extends munit.FunSuite:

  private given Log = Log.off

  private val tempRoot = FunFixture[Path](
    setup = _ => Files.createTempDirectory("smiffer-project-test"),
    teardown = dir =>
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder[Path])
        .forEach(p => Files.deleteIfExists(p): Unit)
  )

  private def write(dir: Path, contents: String): Path =
    Files.writeString(dir.resolve("smithy-build.json"), contents)

  tempRoot.test("finds smithy-build.json by walking up from a nested directory") { root =>
    write(root, """{"version": "1.0"}""")
    val nested = Files.createDirectories(root.resolve("model/src/deep"))
    assertEquals(
      SmithyProject.find(nested).unsafeRunSync().toRealPath(),
      root.resolve("smithy-build.json").toRealPath()
    )
  }

  tempRoot.test("reports a missing config rather than silently inspecting nothing") { root =>
    val error = intercept[SmifferError.NoProject](SmithyProject.find(root).unsafeRunSync())
    assert(error.getMessage.contains("smithy-build.json"), error.getMessage)
  }

  tempRoot.test("reads maven.dependencies and maven.repositories") { root =>
    val config = write(
      root,
      """{
        |  "version": "1.0",
        |  "maven": {
        |    "dependencies": ["com.acme:acme-model:1.2.0", "com.acme:other-model:2.0.0"],
        |    "repositories": [{"url": "https://artifactory.example.com/maven"}]
        |  }
        |}""".stripMargin
    )
    val project = SmithyProject.load(config).unsafeRunSync()
    assertEquals(
      project.dependencies,
      List(MavenCoordinate("com.acme", "acme-model", "1.2.0"), MavenCoordinate("com.acme", "other-model", "2.0.0"))
    )
    assertEquals(project.repositoryUrls, List("https://artifactory.example.com/maven"))
  }

  tempRoot.test("a config with no maven block has nothing to inspect") { root =>
    val config = write(root, """{"version": "1.0"}""")
    val error  = intercept[SmifferError.ProjectHasNoDependencies](SmithyProject.load(config).unsafeRunSync())
    assert(error.getMessage.contains("maven.dependencies"), error.getMessage)
  }

  tempRoot.test("a malformed dependency entry is reported with the offending entry") { root =>
    val config = write(
      root,
      """{"version": "1.0", "maven": {"dependencies": ["com.acme:acme-model"]}}"""
    )
    val error = intercept[SmifferError.MalformedProjectConfig](SmithyProject.load(config).unsafeRunSync())
    assert(error.getMessage.contains("com.acme:acme-model"), error.getMessage)
  }

  tempRoot.test("unparseable JSON names the file instead of leaking a parser exception") { root =>
    val config = write(root, """{"version": "1.0",""")
    val error  = intercept[SmifferError.MalformedProjectConfig](SmithyProject.load(config).unsafeRunSync())
    assert(error.getMessage.contains("smithy-build.json"), error.getMessage)
  }
