ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.1"

lazy val root = (project in file("."))
  .settings(
    name := "trading-toolkit",
    idePackagePrefix := Some("org.greensheep.trading")
  ).aggregate(dataservice, core, service)

resolvers += "jitpack" at "https://jitpack.io"
resolvers += Resolver.mavenLocal

val zioVersion = "2.0.19"

val zioLibraries = Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-test" % zioVersion,
  "dev.zio" %% "zio-test-sbt" % zioVersion,
  "dev.zio" %% "zio-test-magnolia" % zioVersion % Test
)

val zioTestFramework = new TestFramework("zio.test.sbt.ZTestFramework")

lazy val core = (project in file("core"))
  .settings(
    idePackagePrefix := Some("org.greensheep.trading.core"),
    libraryDependencies := zioLibraries,
    testFrameworks := Seq(zioTestFramework)
  )

lazy val dataservice = (project in file("dataservice")).dependsOn(core)
  .settings(
    idePackagePrefix := Some("org.greensheep.trading.dataservice"),
    libraryDependencies := Seq("dev.zio" %% "zio-json" % "0.4.2") ++ zioLibraries,
    testFrameworks := Seq(zioTestFramework)
  )

lazy val service = (project in file("service")).dependsOn(core, dataservice)
  .settings(
    idePackagePrefix := Some("org.greensheep.trading.service"),
    libraryDependencies := zioLibraries,
    testFrameworks := Seq(zioTestFramework)
  )

libraryDependencies ++= zioLibraries
testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

Test / fork := true
Test / envVars := sys.env
