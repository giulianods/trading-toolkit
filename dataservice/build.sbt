resolvers += "jitpack" at "https://jitpack.io"
resolvers += Resolver.mavenLocal

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.14" % Test

libraryDependencies += "io.d11" %% "zhttp" % "2.0.0-RC11"
libraryDependencies += "org.typelevel" %% "cats-effect" % "3.4.1"

val http4sVersion = "1.0.0-M37"

libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-ember-server" % http4sVersion,
  "org.http4s" %% "http4s-ember-client" % http4sVersion
)

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.15" % "test"

libraryDependencies += "org.apache.commons" % "commons-csv" % "1.10.0"
libraryDependencies += "com.github.polygon-io" % "client-jvm" % "5.1.0"

Test / fork := true
Test / envVars := sys.env