
libraryDependencies ++= Seq(
  "dev.zio" %% "zio-interop-cats" % "23.0.0.2",

)

import scala.sys.process.Process

val ttkMainClass = "io.greensheep.trading.service.Main"
libraryDependencySchemes += "io.circe" %% "circe-yaml" % VersionScheme.Always
dependencyOverrides ++= Seq (
  "io.circe" %% "circe-yaml" % "0.15.1"
)

val frontendSrc = settingKey[File]("Directory of the React web app")
val npmBuild = taskKey[File]("Install and build the React web app")
val packageService = taskKey[File]("Build the React UI and assemble the service fat jar")

frontendSrc := {
  sys.env.get("TTK_WEB_SRC").map(file)
    .orElse(sys.props.get("ttk.web.src").map(file))
    .getOrElse((ThisBuild / baseDirectory).value / "web")
}

npmBuild := {
  val dir = frontendSrc.value
  val log = streams.value.log
  if (!dir.exists) {
    sys.error(s"Frontend project not found at $dir")
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

val deploy = taskKey[File]("Build the fat jar and deploy it with docker compose (local or SSH)")

deploy := {
  val log = streams.value.log
  val jar = packageService.value
  val composeDir = (baseDirectory).value / "docker" / "mongo-ttk"
  val appDir = composeDir / "app"
  IO.createDirectory(appDir / "logs")
  val destJar = appDir / "ttk.jar"
  IO.copyFile(jar, destJar)
  log.info(s"Copied $jar -> $destJar")

  val propsFile = (ThisBuild / baseDirectory).value / "local.properties"
  if (!propsFile.exists) {
    sys.error("Missing local.properties at the repo root (API keys). Copy local.properties.example first.")
  }
  IO.copyFile(propsFile, appDir / "local.properties")
  log.info(s"Copied workspace ${propsFile.getName} -> ${appDir / "local.properties"}")

  val deployProps = {
    val loaded = new java.util.Properties()
    val in = new java.io.FileInputStream(propsFile)
    try loaded.load(in)
    finally in.close()
    loaded
  }
  def prop(key: String, default: String): String =
    Option(deployProps.getProperty(key)).map(_.trim).filter(_.nonEmpty).getOrElse(default)

  val host = prop("ttk.deploy.host", "192.168.0.156")
  val user = prop("ttk.deploy.user", sys.props.getOrElse("user.name", "giuli"))
  val remoteDir = prop("ttk.deploy.dir", "ttk")
  val localHosts = Set("localhost", "127.0.0.1", "::1")

  if (localHosts.contains(host)) {
    log.info(s"Starting docker compose in $composeDir")
    val code = Process(Seq("docker", "compose", "up", "-d", "--force-recreate"), composeDir).!
    if (code != 0) sys.error("docker compose up failed")
    log.info("App: http://127.0.0.1:7777   Mongo Express: http://127.0.0.1:8081")
  } else {
    val sshTarget = s"$user@$host"
    val ssh = Seq("ssh", "-o", "BatchMode=yes")
    val rsync = Seq("rsync", "-az", "-e", "ssh -o BatchMode=yes")
    def run(cmd: Seq[String]): Unit = {
      log.info(cmd.mkString(" "))
      val code = Process(cmd).!
      if (code != 0) sys.error(s"Command failed ($code): ${cmd.mkString(" ")}")
    }
    log.info(s"Deploying to $sshTarget:$remoteDir (including workspace local.properties)")
    val remoteBase = if (remoteDir.startsWith("/")) remoteDir else remoteDir
    val mkdirRemote = if (remoteDir.startsWith("/")) s"$remoteDir/app/logs" else s"$remoteDir/app/logs"
    run(ssh ++ Seq(sshTarget, s"mkdir -p $mkdirRemote"))
    run(
      rsync ++ Seq(
        (composeDir / "docker-compose.yml").getAbsolutePath,
        (composeDir / "up.sh").getAbsolutePath,
        s"$sshTarget:$remoteBase/"
      )
    )
    run(
      rsync ++ Seq(
        destJar.getAbsolutePath,
        (appDir / "local.properties").getAbsolutePath,
        s"$sshTarget:$remoteBase/app/"
      )
    )
    val start = if (remoteDir.startsWith("/")) s"bash $remoteDir/up.sh" else s"bash $$HOME/$remoteDir/up.sh"
    run(ssh ++ Seq(sshTarget, start))
    log.info(s"App: http://$host:7777   Mongo Express: http://$host:8081")
  }
  destJar
}

assembly / mainClass := Some(ttkMainClass)
assembly / assemblyMergeStrategy := {
  case "logback.xml" => MergeStrategy.first
  case PathList("META-INF", _*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}