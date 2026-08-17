package io.greensheep.trading.core.utils

import java.io.FileInputStream
import java.nio.file.{Files, Path, Paths}
import java.util.Properties
import scala.annotation.tailrec

object LocalConfig {

  private val defaultFileName = "local.properties"

  private lazy val properties: Properties = {
    val props = new Properties()
    findConfigFile().foreach { file =>
      val in = new FileInputStream(file.toFile)
      try props.load(in)
      finally in.close()
    }
    props
  }

  private def environmentName(key: String): String =
    key.toUpperCase.replace('.', '_').replace('-', '_')

  def get(key: String): Option[String] =
    sys.env
      .get(environmentName(key))
      .orElse(Option(properties.getProperty(key)))
      .map(_.trim)
      .filter(_.nonEmpty)

  def require(key: String): String =
    get(key).getOrElse {
      throw new IllegalStateException(
        s"Missing required config key '$key' (environment: ${environmentName(key)}, file: $defaultFileName)"
      )
    }

  def configFileDirectory: Option[Path] =
    findConfigFile().map(_.getParent)

  private def findConfigFile(): Option[Path] = {
    val configuredPath = sys.props.get("ttk.config.file").map(path => Paths.get(path).toAbsolutePath.normalize())
    configuredPath.filter(Files.exists(_)).orElse {
      val start = Paths.get("").toAbsolutePath.normalize()
      findInParents(start, _.resolve(defaultFileName))
    }
  }

  @tailrec
  private def findInParents(current: Path, pathForDir: Path => Path): Option[Path] = {
    val candidate = pathForDir(current)
    if (Files.exists(candidate)) Some(candidate)
    else {
      val parent = current.getParent
      if (parent == null) None else findInParents(parent, pathForDir)
    }
  }
}
