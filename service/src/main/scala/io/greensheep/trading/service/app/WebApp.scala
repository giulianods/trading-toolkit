package io.greensheep.trading.service.app

import io.greensheep.trading.core.utils.LocalConfig
import zio.*
import zio.http.*

import java.nio.file.{Files, Path, Paths}

object WebApp {

  private val classpathPrefix = "/web"
  private val defaultRelativeRoot = "web/build"

  private val assetExtensions: Set[String] = Set(
    "js", "css", "map", "json", "png", "jpg", "jpeg", "gif", "svg", "ico",
    "woff", "woff2", "ttf", "eot", "txt", "webp", "html"
  )

  def resolveRoot: Path = {
    val configured = LocalConfig.get("ttk.web.root").getOrElse(defaultRelativeRoot)
    val path = Paths.get(configured)
    if (path.isAbsolute) path.normalize()
    else LocalConfig.configFileDirectory
      .getOrElse(Paths.get("").toAbsolutePath.normalize())
      .resolve(path)
      .normalize()
  }

  def describeSource: String = {
    val disk = resolveRoot
    if (Files.isDirectory(disk)) disk.toString
    else if (classpathIndexAvailable) s"classpath:$classpathPrefix"
    else "missing"
  }

  def app: HttpApp[Any] = {
    val disk = resolveRoot
    if (Files.isDirectory(disk)) fromDirectory(disk)
    else fromClasspath()
  }

  def fromDirectory(webRoot: Path): HttpApp[Any] =
    Handler
      .fromFunctionZIO[Request](req => serveDisk(webRoot, req))
      .toHttpApp

  def fromClasspath(): HttpApp[Any] =
    Handler
      .fromFunctionZIO[Request](serveClasspath)
      .toHttpApp

  private def classpathIndexAvailable: Boolean =
    Option(getClass.getResource(s"$classpathPrefix/index.html")).isDefined

  private def serveDisk(webRoot: Path, req: Request): UIO[Response] =
    serve(req) { relative =>
      resolveUnderRoot(webRoot, relative)
        .filter(Files.isRegularFile(_))
        .map(file => Right(file))
    }

  private def serveClasspath(req: Request): UIO[Response] =
    serve(req) { relative =>
      classpathResource(relative).map(bytes => Left(bytes))
    }

  private def serve(req: Request)(resolve: String => Option[Either[Array[Byte], Path]]): UIO[Response] = {
    if (req.method != Method.GET && req.method != Method.HEAD) {
      ZIO.succeed(Response.status(Status.MethodNotAllowed))
    } else {
      val relative = requestedRelativePath(req)
      resolve(relative) match {
        case Some(payload) =>
          payloadResponse(relative, payload, req.method)
        case None if fileExtension(relative).exists(assetExtensions.contains) =>
          ZIO.succeed(Response.status(Status.NotFound))
        case None if isApiPath(relative) =>
          ZIO.succeed(Response.status(Status.NotFound))
        case None =>
          resolve("index.html") match {
            case Some(payload) => payloadResponse("index.html", payload, req.method)
            case None          => ZIO.succeed(Response.status(Status.NotFound))
          }
      }
    }
  }

  private def isApiPath(relative: String): Boolean = {
    val first = relative.split('/').headOption.getOrElse("")
    Set("markets", "watchlists", "quotes", "financials", "description", "breadth", "status").contains(first)
  }

  private def payloadResponse(name: String, payload: Either[Array[Byte], Path], method: Method): UIO[Response] =
    payload match {
      case Right(file)  => fileResponse(file, method)
      case Left(bytes)  => bytesResponse(name, bytes, method)
    }

  private def requestedRelativePath(req: Request): String = {
    val raw = req.url.path.toString match {
      case "" | "/" => "index.html"
      case other    => other.stripPrefix("/")
    }
    java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8)
  }

  private def resolveUnderRoot(webRoot: Path, relative: String): Option[Path] = {
    if (relative.isBlank) None
    else {
      val resolved = webRoot.resolve(relative).normalize()
      if (resolved.startsWith(webRoot.normalize())) Some(resolved) else None
    }
  }

  private def classpathResource(relative: String): Option[Array[Byte]] = {
    val parts = relative.split('/').toSeq.filter(_.nonEmpty)
    if (parts.isEmpty || parts.contains("..")) None
    else {
      val path = s"$classpathPrefix/${parts.mkString("/")}"
      Option(getClass.getResourceAsStream(path)).map { stream =>
        try stream.readAllBytes()
        finally stream.close()
      }
    }
  }

  private def fileExtension(path: String): Option[String] = {
    val name = Paths.get(path).getFileName.toString
    val dot = name.lastIndexOf('.')
    if (dot > 0 && dot < name.length - 1) Some(name.substring(dot + 1).toLowerCase) else None
  }

  private def fileResponse(file: Path, method: Method): UIO[Response] = {
    val headers = Headers(Header.Custom("Content-Type", contentType(file.getFileName.toString)))
    val body = if (method == Method.HEAD) Body.empty else Body.fromFile(file.toFile)
    ZIO.succeed(Response(status = Status.Ok, headers = headers, body = body))
  }

  private def bytesResponse(name: String, bytes: Array[Byte], method: Method): UIO[Response] = {
    val headers = Headers(Header.Custom("Content-Type", contentType(name)))
    val body = if (method == Method.HEAD) Body.empty else Body.fromChunk(Chunk.fromArray(bytes))
    ZIO.succeed(Response(status = Status.Ok, headers = headers, body = body))
  }

  private def contentType(fileName: String): String =
    fileExtension(fileName).getOrElse("") match {
      case "html" => "text/html; charset=utf-8"
      case "js" => "application/javascript; charset=utf-8"
      case "css" => "text/css; charset=utf-8"
      case "json" => "application/json; charset=utf-8"
      case "svg" => "image/svg+xml"
      case "png" => "image/png"
      case "jpg" | "jpeg" => "image/jpeg"
      case "gif" => "image/gif"
      case "ico" => "image/x-icon"
      case "webp" => "image/webp"
      case "woff" => "font/woff"
      case "woff2" => "font/woff2"
      case "ttf" => "font/ttf"
      case "eot" => "application/vnd.ms-fontobject"
      case "map" => "application/json"
      case "txt" => "text/plain; charset=utf-8"
      case _ => "application/octet-stream"
    }
}
