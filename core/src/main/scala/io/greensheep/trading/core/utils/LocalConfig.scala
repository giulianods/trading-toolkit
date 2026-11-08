package io.greensheep.trading.core.utils

import com.typesafe.config.{Config, ConfigFactory, ConfigValueType}

import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

object LocalConfig {

  private val defaultFileName = "local.conf"
  private val legacyFileName = "local.properties"

  private lazy val configFile = findConfigFile()
  private lazy val config: Config =
    configFile.fold(ConfigFactory.empty())(path => ConfigFactory.parseFile(path.toFile).resolve())

  private def environmentName(key: String): String =
    key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_')

  def get(key: String): Option[String] =
    sys.env
      .get(environmentName(key))
      .orElse(Option.when(config.hasPath(key))(config.getString(key)))
      .map(_.trim)
      .filter(_.nonEmpty)

  def getList(key: String): Option[Seq[String]] =
    sys.env.get(environmentName(key)) match {
      case Some(value) => commaSeparated(value)
      case None if !config.hasPath(key) => None
      case None if config.getValue(key).valueType() == ConfigValueType.LIST =>
        Some(config.getStringList(key).asScala.toVector.map(_.trim).filter(_.nonEmpty))
      case None => commaSeparated(config.getString(key))
    }

  private def commaSeparated(value: String): Option[Seq[String]] =
    Option(value.trim).filter(_.nonEmpty).map(_.split(",").toVector.map(_.trim).filter(_.nonEmpty))

  def require(key: String): String =
    get(key).getOrElse {
      throw new IllegalStateException(
        s"Missing required config key '$key' (environment: ${environmentName(key)}, file: $defaultFileName)"
      )
    }

  def configFileDirectory: Option[Path] =
    configFile.map(_.getParent)

  private def findConfigFile(): Option[Path] = {
    val configuredPath = sys.props.get("ttk.config.file").map(path => Paths.get(path).toAbsolutePath.normalize())
    configuredPath.filter(Files.exists(_)).orElse {
      val start = Paths.get("").toAbsolutePath.normalize()
      findInParents(start, defaultFileName).orElse(findInParents(start, legacyFileName))
    }
  }

  @tailrec
  private def findInParents(current: Path, fileName: String): Option[Path] = {
    val candidate = current.resolve(fileName)
    if (Files.exists(candidate)) Some(candidate)
    else {
      val parent = current.getParent
      if (parent == null) None else findInParents(parent, fileName)
    }
  }
}
