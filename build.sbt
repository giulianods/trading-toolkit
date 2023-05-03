ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.10"

lazy val root = (project in file("."))
  .settings(
    name := "trading-toolkit",
    idePackagePrefix := Some("org.greensheep.tradingtk")
  )

resolvers += "jitpack" at "https://jitpack.io"

val zioDeps = Seq(
  "dev.zio" %% "zio" % "2.0.2",
  "dev.zio" %% "zio-test" % "2.0.2",
  "dev.zio" %% "zio-test-sbt" % "2.0.2"
)


lazy val core = (project in file("core"))
  .settings(
    idePackagePrefix := Some("org.greensheep.tradingtk.core"),
    libraryDependencies := zioDeps
  )

lazy val dataservice = (project in file("dataservice")).dependsOn(core)
  .settings(
    idePackagePrefix := Some("org.greensheep.tradingtk.dataservice"),
    libraryDependencies := Seq("dev.zio" %% "zio-json" % "0.3.0") ++ zioDeps
  )

lazy val service = (project in file("service")).dependsOn(core, dataservice)
  .settings(
    idePackagePrefix := Some("org.greensheep.tradingtk.service"),
    libraryDependencies := zioDeps
  )

libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % "2.0.2",
  "dev.zio" %% "zio-test" % "2.0.2",
  "dev.zio" %% "zio-test-sbt" % "2.0.2"
)


