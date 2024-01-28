
val zioVersion = "2.0.19"
val zioHttpVersion = "3.0.0-RC4"

val zioHttp     = "dev.zio" %% "zio-http"     % zioHttpVersion

val zioTest     = "dev.zio" %% "zio-test"     % zioVersion % Test
val zioTestSBT = "dev.zio" %% "zio-test-sbt" % zioVersion % Test
val zioTestMagnolia = "dev.zio" %% "zio-test-magnolia" % zioVersion

def settingsApp = Seq(
  name := "zio-http-start",
  Compile / run / mainClass := Option("io.greensheep.trading.api.Main"),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  libraryDependencies ++= Seq(
    "com.softwaremill.magnolia1_3" %% "magnolia" % "1.3.4",
    zioHttp,
    zioTest,
    zioTestSBT,
    zioTestMagnolia
  ),
)