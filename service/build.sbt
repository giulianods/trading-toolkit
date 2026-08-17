
libraryDependencies ++= Seq(
  "dev.zio" %% "zio-interop-cats" % "23.0.0.2",

)

import scala.sys.process.Process

val ttkMainClass = "io.greensheep.trading.service.Main"
libraryDependencySchemes += "io.circe" %% "circe-yaml" % VersionScheme.Always
dependencyOverrides ++= Seq (
  "io.circe" %% "circe-yaml" % "0.15.1"
)

val frontendSrc = settingKey[File]("Directory of the greensheep-stocks React app")
val npmBuild = taskKey[File]("Install and build greensheep-stocks")
val packageService = taskKey[File]("Build the React UI and assemble the service fat jar")

frontendSrc := {
  sys.env.get("TTK_WEB_SRC").map(file)
    .orElse(sys.props.get("ttk.web.src").map(file))
    .getOrElse((ThisBuild / baseDirectory).value.getParentFile / "greensheep-stocks")
}

npmBuild := {
  val dir = frontendSrc.value
  val log = streams.value.log
  if (!dir.exists) {
    sys.error(
      s"Frontend project not found at $dir. Place greensheep-stocks next to trading-toolkit or set TTK_WEB_SRC."
    )
  }
  if (!(dir / "node_modules").exists) {
    log.info(s"Installing frontend dependencies in $dir")
    val install = Process(Seq("npm", "install"), dir).!
    if (install != 0) sys.error("npm install failed")
  }
  log.info(s"Building frontend in $dir")
  val code = Process(Seq("npm", "run", "build"), dir).!
  if (code != 0) sys.error("npm run build failed")
  val buildDir = dir / "build"
  if (!buildDir.exists) sys.error(s"Expected $buildDir after npm run build")
  buildDir
}

Compile / resourceGenerators += Def.task {
  val src = frontendSrc.value / "build"
  val dest = (Compile / resourceManaged).value / "web"
  val log = streams.value.log
  if (src.isDirectory) {
    IO.delete(dest)
    IO.copyDirectory(src, dest)
    val files = (dest ** "*").get.filter(_.isFile)
    log.info(s"Copied ${files.size} frontend files from $src")
    files
  } else {
    log.warn(s"No frontend build at $src; packaged jar will not include the web app")
    Seq.empty
  }
}.taskValue

packageService := {
  npmBuild.value
  assembly.value
}

assembly / mainClass := Some(ttkMainClass)
assembly / assemblyMergeStrategy := {
  case "logback.xml" => MergeStrategy.first
  case PathList("META-INF", _*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}