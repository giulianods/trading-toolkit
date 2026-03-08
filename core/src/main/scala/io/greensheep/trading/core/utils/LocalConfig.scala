package io.greensheep.trading.core.utils

import java.io.FileInputStream
import java.nio.file.{Files, Path, Paths}
import java.util.Properties
import scala.annotation.tailrec

object LocalConfig {

  private val defaultFileName = "local.properties"

  private lazy val properties: Properties = {
    val file = findConfigFile().getOrElse {
      throw new IllegalStateException(
        s"Could not find $defaultFileName in the current directory or any parent directory"
      )
    }

    val props = new Properties()
    val in = new FileInputStream(file.toFile)
    try props.load(in)
    finally in.close()
    props
  }

  def get(key: String): Option[String] =
    Option(properties.getProperty(key)).map(_.trim).filter(_.nonEmpty)

  def require(key: String): String =
    get(key).getOrElse {
      throw new IllegalStateException(s"Missing required config key '$key' in $defaultFileName")
    }

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
