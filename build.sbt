import sbtassembly.MergeStrategy

ThisBuild / organization := "io.github.ghostbuster91"
ThisBuild / version      := sys.env.getOrElse("SMIFFER_VERSION", "0.1.0-SNAPSHOT").stripPrefix("v")
ThisBuild / scalaVersion := "3.9.0"

lazy val smiffer = (project in file("."))
  .settings(
    name := "smiffer",
    javacOptions ++= Seq("--release", "21"),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Werror",
      "-Wunused:all",
      "-Wvalue-discard",
      "-Wimplausible-patterns"
    ),
    libraryDependencies ++= Seq(
      // The Smithy model loader, IDL/AST serializers and shape graph walker.
      "software.amazon.smithy"  % "smithy-model"   % "1.75.0",
      // SmithyBuildConfig: reads `maven.dependencies` / `maven.repositories` out of smithy-build.json.
      "software.amazon.smithy"  % "smithy-build"   % "1.75.0",
      "io.get-coursier"        %% "coursier"       % "2.1.26",
      "com.monovore"           %% "decline"        % "2.6.2",
      "com.monovore"           %% "decline-effect" % "2.6.2",
      "org.typelevel"          %% "cats-effect"    % "3.7.1",
      // Coursier logs through slf4j; without a binding it prints a warning onto our stdout payload.
      "org.slf4j"               % "slf4j-nop"      % "2.0.20",
      "org.scalameta"          %% "munit"          % "1.3.6" % Test
    ),
    Compile / mainClass := Some("smiffer.cli.SmifferApp"),
    Compile / sourceGenerators += Def.task {
      val out = (Compile / sourceManaged).value / "smiffer" / "cli" / "BuildInfo.scala"
      val safe = version.value.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\r\n]", "")
      java.nio.file.Files.createDirectories(out.toPath.getParent)
      java.nio.file.Files.writeString(
        out.toPath,
        s"""package smiffer.cli
           |private[cli] object BuildInfo:
           |  val version = "$safe"
           |""".stripMargin
      )
      Seq(out)
    }.taskValue,
    // `TraitService` and friends are ServiceLoader-registered across several smithy jars; losing
    // any one of them silently drops trait definitions from every model we load.
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*)             => MergeStrategy.concat
      case PathList("META-INF", "MANIFEST.MF")              => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if isSignature(xs) => MergeStrategy.discard
      // JPMS descriptors, including the multi-release copies under META-INF/versions/N/.
      case xs if xs.split('/').lastOption.contains("module-info.class") => MergeStrategy.discard
      case other                                            =>
        val base = (assembly / assemblyMergeStrategy).value
        base(other)
    },
    assembly / assemblyJarName := "smiffer.jar",
    assembly / mainClass       := Some("smiffer.cli.SmifferApp")
  )

def isSignature(parts: Seq[String]): Boolean =
  parts.lastOption.exists(n => n.endsWith(".SF") || n.endsWith(".DSA") || n.endsWith(".RSA"))
