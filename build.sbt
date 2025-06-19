import sbt.Keys.testFrameworks

import scala.collection.Seq

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.7.0"

lazy val root = (project in file("."))
  .settings(
    name := "trading-toolkit",
    idePackagePrefix := Some("io.greensheep.trading")
  ).aggregate(dataservice, core, service, restApi)

credentials += Credentials(Path.userHome / ".sbt" / ".credentials")

val zioVersion = "2.0.19"
val zioLoggingVersion = "2.5.0"
val zioLibraries = Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-test" % zioVersion,
  "dev.zio" %% "zio-test-sbt" % zioVersion,
  "dev.zio" %% "zio-logging" % zioLoggingVersion,
  "dev.zio" %% "zio-logging-slf4j" % zioLoggingVersion,
  "dev.zio" %% "zio-test-magnolia" % zioVersion % Test
)
libraryDependencySchemes += "io.circe" %% "circe-yaml" % VersionScheme.Always


val mongoZioLibraries = Seq(
  "io.github.zeal18" %% "zio-mongodb-bson"    % "0.11.1",
  "io.github.zeal18" %% "zio-mongodb-driver"  % "0.11.1",
  "io.github.zeal18" %% "zio-mongodb-testkit" % "0.11.1" % Test
)

val logbackLib = Seq(
  "ch.qos.logback" % "logback-classic" % "1.0.13"
)

val dataSourceLibraries = Seq(
  "io.greensheep" %% "polygon-client-zio" % "0.1.0-SNAPSHOT"
)

val zioTestFramework = new TestFramework("zio.test.sbt.ZTestFramework")

lazy val core = (project in file("core"))
  .settings(
    libraryDependencies := zioLibraries ++ logbackLib,
    testFrameworks := Seq(zioTestFramework)
  )

lazy val dataservice = (project in file("dataservice")).dependsOn(core)
  .settings(

    libraryDependencies := Seq("dev.zio" %% "zio-json" % "0.4.2") ++ zioLibraries ++ mongoZioLibraries ++ dataSourceLibraries ++ logbackLib,
    testFrameworks := Seq(zioTestFramework)
  )

lazy val restApi = (project in file("rest-api"))
  .dependsOn(core)
  .dependsOn(dataservice)
  .settings(
    name := "rest-api",
  )

val cronScheduling = "com.github.tharwaninitin" %% "cron4zio" % "1.0.0"

lazy val service = (project in file("service")).dependsOn(core, dataservice, restApi)
  .settings(
    libraryDependencies := zioLibraries ++ logbackLib :+ cronScheduling,
    testFrameworks := Seq(zioTestFramework)
  )

libraryDependencies ++= (zioLibraries ++ logbackLib)
testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

Test / fork := true
Test / envVars := sys.env
