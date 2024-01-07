ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.1"

lazy val root = (project in file("."))
  .settings(
    name := "trading-toolkit",
    idePackagePrefix := Some("io.greensheep.trading")
  ).aggregate(dataservice, core, service)

credentials += Credentials(Path.userHome / ".sbt" / ".credentials")



val zioVersion = "2.0.19"

val zioLibraries = Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-test" % zioVersion,
  "dev.zio" %% "zio-test-sbt" % zioVersion,
  "dev.zio" %% "zio-test-magnolia" % zioVersion % Test
)

val dataSourceLibraries = Seq(
  "io.greensheep" %% "polygon-client-zio" % "0.1.0-SNAPSHOT"
)

val zioTestFramework = new TestFramework("zio.test.sbt.ZTestFramework")

lazy val core = (project in file("core"))
  .settings(
    libraryDependencies := zioLibraries,
    testFrameworks := Seq(zioTestFramework)
  )

lazy val dataservice = (project in file("dataservice")).dependsOn(core)
  .settings(
    libraryDependencies := Seq("dev.zio" %% "zio-json" % "0.4.2") ++ zioLibraries ++ dataSourceLibraries,
    testFrameworks := Seq(zioTestFramework)
  )

lazy val service = (project in file("service")).dependsOn(core, dataservice)
  .settings(
    libraryDependencies := zioLibraries,
    testFrameworks := Seq(zioTestFramework)
  )

libraryDependencies ++= zioLibraries
testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

Test / fork := true
Test / envVars := sys.env
