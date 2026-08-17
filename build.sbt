import sbt.Keys.testFrameworks

import scala.collection.Seq

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.7.1"
lazy val root = (project in file("."))
  .settings(
    name := "trading-toolkit",
    idePackagePrefix := Some("io.greensheep.trading")
  ).aggregate(dataservice, core, service, restApi, polygonClientZio, finnhubClientZio)


scalacOptions += "-Yretain-trees"

val zioVersion = "2.1.21"
val zioLoggingVersion = "2.5.0"
val zioHttpVersion = "3.0.0-RC4"
val zioJsonVersion = "0.6.2"
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
  "io.github.zeal18" %% "zio-mongodb-bson"    % "0.11.3",
  "io.github.zeal18" %% "zio-mongodb-driver"  % "0.11.3",
  "io.github.zeal18" %% "zio-mongodb-testkit" % "0.11.3" % Test
)

val logbackLib = Seq(
  "ch.qos.logback" % "logback-classic" % "1.0.13"
)

val zioTestFramework = new TestFramework("zio.test.sbt.ZTestFramework")

lazy val polygonClientZio = (project in file("polygon-client-zio"))
  .settings(
    name := "polygon-client-zio",
    libraryDependencies ++= zioLibraries ++ Seq(
      "dev.zio" %% "zio-http" % zioHttpVersion,
      "dev.zio" %% "zio-json" % zioJsonVersion
    ),
    testFrameworks := Seq(zioTestFramework)
  )

lazy val finnhubClientZio = (project in file("finnhub-client-zio"))
  .settings(
    name := "finnhub-client-zio",
    scalacOptions ++= Seq("-Xmax-inlines", "64"),
    libraryDependencies ++= zioLibraries ++ Seq(
      "dev.zio" %% "zio-http" % zioHttpVersion,
      "dev.zio" %% "zio-json" % zioJsonVersion
    ),
    testFrameworks := Seq(zioTestFramework)
  )

lazy val core = (project in file("core"))
  .settings(
    libraryDependencies := zioLibraries ++ logbackLib,
    testFrameworks := Seq(zioTestFramework)
  )

lazy val dataservice = (project in file("dataservice")).dependsOn(core, polygonClientZio, finnhubClientZio)
  .settings(

    libraryDependencies := Seq("dev.zio" %% "zio-json" % zioJsonVersion) ++ zioLibraries ++ mongoZioLibraries ++ logbackLib,
    testFrameworks := Seq(zioTestFramework)
  )

lazy val restApi = (project in file("rest-api"))
  .dependsOn(core)
  .dependsOn(dataservice)
  .settings(
    name := "rest-api",
  )

val cronScheduling = "com.github.tharwaninitin" %% "cron4zio" % "1.0.0"

lazy val service = (project in file("service")).dependsOn(core, dataservice, restApi, polygonClientZio, finnhubClientZio)
  .settings(
    libraryDependencies := zioLibraries ++ logbackLib :+ cronScheduling,
    testFrameworks := Seq(zioTestFramework)
  )




libraryDependencies ++= (zioLibraries ++ logbackLib)
testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

ThisBuild / Test / fork := true
ThisBuild / Test / envVars := sys.env
ThisBuild / Test / javaOptions ++= Seq(
  "-Djava.net.preferIPv4Stack=true",
  "-Djava.net.preferIPv6Addresses=false"
)
