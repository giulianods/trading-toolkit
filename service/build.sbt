
libraryDependencies += "dev.zio" %% "zio" % "2.0.2"
libraryDependencies ++= Seq(
  "dev.zio" %% "zio-test" % "2.0.9" % Test,
  "dev.zio" %% "zio-test-sbt" % "2.0.9" % Test,
  "dev.zio" %% "zio-test-magnolia" % "2.0.9" % Test
)

libraryDependencies += "dev.zio" %% "zio-interop-cats" % "23.0.0.2"