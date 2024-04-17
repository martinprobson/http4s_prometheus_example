name := "Http4s Prometheus monitoring example"
ThisBuild / scalaVersion := "3.3.1"
ThisBuild / version := "0.0.1-SNAPSHOT"
ThisBuild / organization := "net.martinprobson"

val Http4sVersion = "0.23.26"
val CirceVersion = "0.14.6"
val fs2Version = "3.10.2"
val LogbackVersion = "1.2.11"

val commonDependencies = Seq(
  "ch.qos.logback" % "logback-classic" % LogbackVersion,
  "ch.qos.logback" % "logback-core" % LogbackVersion,
  "org.http4s" %% "http4s-circe" % Http4sVersion,
  "org.http4s" %% "http4s-dsl" % Http4sVersion,
  "io.circe" %% "circe-generic" % CirceVersion,
  "io.circe" %% "circe-literal" % CirceVersion,
  "co.fs2" %% "fs2-core" % fs2Version,
  "com.disneystreaming" %% "weaver-cats" % "0.8.4" % Test
)

lazy val root = project
  .in(file("."))
  .aggregate(common, client, server)
  .disablePlugins(AssemblyPlugin)
  .settings(Test / fork := true, run / fork := true)
  .settings(testFrameworks += new TestFramework("weaver.framework.CatsEffect"))
  .settings(commonSettings)

lazy val common = project
  .in(file("common"))
  .settings(commonSettings)
  .settings(libraryDependencies ++= commonDependencies)
  .disablePlugins(AssemblyPlugin)
  .settings(Test / fork := true, run / fork := true)
  .settings(testFrameworks += new TestFramework("weaver.framework.CatsEffect"))

lazy val client = project
  .in(file("client"))
  .dependsOn(common)
  .settings(commonSettings)
  .settings(libraryDependencies ++=
    commonDependencies ++
    Seq("org.http4s" %% "http4s-ember-client" % Http4sVersion,
        "org.http4s" %% "http4s-circe" % Http4sVersion,
        "org.http4s" %% "http4s-dsl" % Http4sVersion)
  )
  .settings(Test / fork := true, run / fork := true)
  .settings(assembly / mainClass := Some("net.martinprobson.example.client.UserClient"))
  .settings(testFrameworks += new TestFramework("weaver.framework.CatsEffect"))
  .settings(assemblySettings)

lazy val server = project
  .in(file("server"))
  .dependsOn(common)
  .settings(commonSettings)
  .settings(assembly / mainClass := Some("net.martinprobson.example.server.UserServer"))
  .settings(libraryDependencies ++=
    commonDependencies ++
    Seq("org.http4s" %% "http4s-ember-server" % Http4sVersion,
        "org.http4s" %% "http4s-prometheus-metrics" % "0.23.12",
        "org.http4s" %% "http4s-circe" % Http4sVersion,
        "org.http4s" %% "http4s-dsl" % Http4sVersion)
    )
  .settings(Test / fork := true, run / fork := true)
  .settings(testFrameworks += new TestFramework("weaver.framework.CatsEffect"))
  .enablePlugins(JavaAppPackaging)
  .settings(assemblySettings)

lazy val compilerOptions = Seq(
  "-deprecation",         // Emit warning and location for usages of deprecated APIs.
  "-Wunused:all"
)

lazy val commonSettings = Seq(
  scalacOptions ++= compilerOptions
)

lazy val assemblySettings = Seq(
  assembly / assemblyJarName := name.value + ".jar",
  assembly / assemblyMergeStrategy := {
    case PathList("META-INF", xs @ _*) => MergeStrategy.discard
    case "application.conf"            => MergeStrategy.concat
    case "reference.conf"              => MergeStrategy.concat
    case "module-info.class"           => MergeStrategy.discard
    case x =>
      val oldStrategy = (assembly / assemblyMergeStrategy).value
      oldStrategy(x)
  }
)

testFrameworks += new TestFramework("weaver.framework.CatsEffect")
