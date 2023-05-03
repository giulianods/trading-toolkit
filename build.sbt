ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.2.2"

lazy val root = (project in file("."))
  .settings(
    name := "trading-toolkit",
    idePackagePrefix := Some("org.greensheep.trading")
  ).aggregate(dataservice, core, service)

resolvers += "jitpack" at "https://jitpack.io"
resolvers += Resolver.mavenLocal

val zioLibraries = Seq(
  "dev.zio" %% "zio" % "2.0.10",
  "dev.zio" %% "zio-test" % "2.0.10",
  "dev.zio" %% "zio-test-sbt" % "2.0.10",
  "dev.zio" %% "zio-test-magnolia" % "2.0.10" % Test
)


lazy val core = (project in file("core"))
  .settings(
    idePackagePrefix := Some("org.greensheep.trading.core"),
    libraryDependencies := zioLibraries
  )

lazy val dataservice = (project in file("dataservice")).dependsOn(core)
  .settings(
    idePackagePrefix := Some("org.greensheep.trading.dataservice"),
    libraryDependencies := Seq("dev.zio" %% "zio-json" % "0.4.2") ++ zioLibraries
  )

lazy val service = (project in file("service")).dependsOn(core, dataservice)
  .settings(
    idePackagePrefix := Some("org.greensheep.trading.service"),
    libraryDependencies := zioLibraries
  )

libraryDependencies ++= zioLibraries
testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

