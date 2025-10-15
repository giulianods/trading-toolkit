
val zioVersion = "2.0.19"
val zioHttpVersion = "3.0.0-RC4"

val zioHttp     = "dev.zio" %% "zio-http"     % zioHttpVersion

val zioTest     = "dev.zio" %% "zio-test"     % zioVersion % Test
val zioTestSBT = "dev.zio" %% "zio-test-sbt" % zioVersion % Test
val zioTestMagnolia = "dev.zio" %% "zio-test-magnolia" % zioVersion

val tapirDependencies = Seq(
  "com.softwaremill.sttp.tapir" %% "tapir-core" % "1.9.6",
  "com.softwaremill.sttp.tapir" %% "tapir-zio" % "1.9.6",
  "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server" % "1.9.6",
  "com.softwaremill.sttp.tapir" %% "tapir-json-zio" % "1.9.6",         // ZIO JSON
  "dev.zio" %% "zio-json" % "0.6.2",                                   // zio-json itself
  "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % "1.9.6",
  "com.softwaremill.sttp.tapir" %% "tapir-openapi-circe-yaml" % "1.0.0-M9"
)



libraryDependencies ++= tapirDependencies
libraryDependencySchemes += "io.circe" %% "circe-yaml" % VersionScheme.Always
dependencyOverrides ++= Seq (
  "io.circe" %% "circe-yaml" % "0.15.1"
)

libraryDependencies ++= Seq(
  "org.jfree" % "jfreechart" % "1.5.4",
  "org.jfree" % "org.jfree.svg"   % "5.0.7",
  "dev.zio"  %% "zio-cache"       % "0.2.3"
)


def settingsApp = Seq(
  name := "zio-http-start",
  Compile / run / mainClass := Option("io.greensheep.trading.api.Main"),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  libraryDependencies ++= {
    tapirDependencies ++
    Seq(
      "com.softwaremill.magnolia1_3" %% "magnolia" % "1.3.4",
      zioHttp,
      zioTest,
      zioTestSBT,
      zioTestMagnolia
    )
  },
)