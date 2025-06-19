

libraryDependencies += "dev.zio" %% "zio-interop-cats" % "23.0.0.2"

val ttkMainClass = "io.greensheep.trading.service.Main"
libraryDependencySchemes += "io.circe" %% "circe-yaml" % VersionScheme.Always
dependencyOverrides ++= Seq (
  "io.circe" %% "circe-yaml" % "0.15.1"
)
assembly / mainClass := Some(ttkMainClass)
assembly / assemblyMergeStrategy := {
  case "logback.xml" => MergeStrategy.first
  case PathList("META-INF", _*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}