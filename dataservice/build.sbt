libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.14" % Test


libraryDependencies += "org.typelevel" %% "cats-effect" % "3.4.1"

val http4sVersion = "1.0.0-M37"


libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.15" % "test"
libraryDependencies += "org.mongodb" % "mongodb-driver-sync" % "4.11.1"
libraryDependencies += "org.apache.commons" % "commons-csv" % "1.10.0"
libraryDependencies += "com.softwaremill.magnolia1_3" %% "magnolia" % "1.3.4"

Test / fork := true
Test / envVars := sys.env
