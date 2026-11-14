package io.greensheep.trading.service.services

import io.circe.{Json, parser}
import zio.*
import zio.http.*

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.io.Source

/** Stateless Streamable HTTP. Tools execute the REST app in process, never a loopback HTTP call. */
object McpEndpoints {
  val protocolVersion = "2025-11-25"
  private val versions = Set("2025-03-26", "2025-06-18", protocolVersion)
  private val verbs = Set("get", "post", "put", "patch", "delete", "head", "options", "trace")
  private def str(value: String): Json = Json.fromString(value)
  private def field(value: Json, key: String): Json = value.hcursor.downField(key).focus.getOrElse(Json.Null)
  private def string(value: Json, key: String): Option[String] = field(value, key).asString
  private def array(value: Json): Vector[Json] = value.asArray.getOrElse(Vector.empty)

  final case class Parameter(name: String, location: String, required: Boolean, schema: Json)
  final case class Operation(name: String, method: String, path: String, description: String, parameters: Vector[Parameter]) {
    def tool: Json = Json.obj(
      "name" -> str(name),
      "description" -> str(s"$description ($method $path)"),
      "inputSchema" -> Json.obj(
        "type" -> str("object"),
        "properties" -> Json.obj(parameters.map(p => p.name -> p.schema)*),
        "required" -> Json.arr(parameters.filter(_.required).map(p => str(p.name))*),
        "additionalProperties" -> Json.False
      ),
      "annotations" -> Json.obj("readOnlyHint" -> Json.True, "openWorldHint" -> Json.True)
    )

    def request(arguments: Json): Either[String, Request] = {
      val args = arguments.asObject.toRight("arguments must be an object")
      args.flatMap { values =>
        val unknown = values.keys.toSet -- parameters.map(_.name)
        val invalid = parameters.collectFirst {
          case p if p.required && !values.contains(p.name) => s"Missing required argument: ${p.name}"
          case p if values(p.name).exists(v => !valid(v, p.schema)) => s"Invalid argument: ${p.name}"
          case p if p.location == "path" && values(p.name).exists(v =>
            v.asString.exists(s => s.isEmpty || s == "." || s == ".." || s.exists(c => c == '/' || c == '\\' || c == '%'))
          ) => s"Invalid path argument: ${p.name}"
        }
        if (unknown.nonEmpty) Left(s"Unknown arguments: ${unknown.toSeq.sorted.mkString(", ")}")
        else if (invalid.nonEmpty) Left(invalid.get)
        else {
          def value(p: Parameter): Option[String] = values(p.name).map(v => v.asString.getOrElse(v.noSpaces))
          val rendered = parameters.filter(_.location == "path").foldLeft(path) { (url, p) =>
            url.replace(s"{${p.name}}", encode(value(p).get))
          }
          val query = parameters.filter(_.location == "query").flatMap(p => value(p).map(v => s"${encode(p.name)}=${encode(v)}"))
          val url = rendered + (if (query.isEmpty) "" else query.mkString("?", "&", ""))
          val headers = parameters.filter(_.location == "header").flatMap(p => value(p).map(v => Header.Custom(p.name, v)))
          Right(Request.get(url).copy(headers = Headers(headers*)))
        }
      }
    }
  }

  private def encode(value: String): String = URLEncoder.encode(value, UTF_8).replace("+", "%20")

  private def valid(value: Json, schema: Json): Boolean = {
    val correctType = string(schema, "type") match {
      case Some("string") => value.isString
      case Some("integer") => value.asNumber.exists(_.toBigDecimal.exists(_.isWhole))
      case Some("number") => value.isNumber
      case Some("boolean") => value.isBoolean
      case _ => false
    }
    correctType &&
      (field(schema, "enum").isNull || array(field(schema, "enum")).contains(value)) &&
      field(schema, "minimum").asNumber.forall(min => value.asNumber.exists(_.toDouble >= min.toDouble)) &&
      field(schema, "maximum").asNumber.forall(max => value.asNumber.exists(_.toDouble <= max.toDouble))
  }

  /** Fail fast for unsupported contracts instead of silently dropping REST capabilities. */
  def operationsFromYaml(yaml: String): Vector[Operation] = {
    val doc = io.circe.yaml.parser.parse(yaml).fold(throw _, identity)
    def resolve(value: Json): Json = string(value, "$ref") match {
      case None => value
      case Some(ref) =>
        require(ref.startsWith("#/"), s"Only local OpenAPI references are supported: $ref")
        resolve(ref.drop(2).split("/").foldLeft(doc)((json, key) => field(json, key.replace("~1", "/").replace("~0", "~"))))
    }
    val operations = field(doc, "paths").asObject.get.toVector.flatMap { case (path, item) =>
      if (path == "/mcp") Vector.empty
      else item.asObject.get.toVector.filter(kv => verbs(kv._1)).map { case (method, operation) =>
        require(method == "get", s"Extend MCP request mapping for $method $path")
        require(field(operation, "requestBody").isNull, s"Extend MCP body mapping for $method $path")
        val parameters = (array(field(item, "parameters")) ++ array(field(operation, "parameters"))).map(resolve).map { p =>
          val schema = resolve(field(p, "schema"))
          val location = string(p, "in").get
          require(Set("path", "query", "header")(location), s"Unsupported MCP parameter location: $location")
          require(Set("string", "integer", "number", "boolean")(string(schema, "type").getOrElse("")), s"Unsupported MCP schema: $schema")
          Parameter(string(p, "name").get, location, field(p, "required").asBoolean.getOrElse(false),
            schema.deepMerge(Json.obj("description" -> str(string(p, "description").getOrElse(string(p, "name").get)))))
        }
        require(parameters.map(_.name).distinct.size == parameters.size, s"Duplicate MCP parameter names for $path")
        val name = string(operation, "operationId").getOrElse(s"${method}_${path.drop(1).replaceAll("[{}]", "").replaceAll("[^a-zA-Z0-9_]", "_")}")
        Operation(name, method.toUpperCase, path,
          Seq(string(operation, "summary"), string(operation, "description")).flatten.mkString(". "), parameters)
      }
    }
    require(operations.map(_.name).distinct.size == operations.size, "Duplicate MCP tool names")
    operations
  }

  lazy val operations: Vector[Operation] = {
    val source = Source.fromResource("openapi.yaml")
    try operationsFromYaml(source.mkString) finally source.close()
  }

  private def error(id: Json, code: Int, message: String, status: Status = Status.Ok): Response =
    Response.json(Json.obj("jsonrpc" -> str("2.0"), "id" -> id,
      "error" -> Json.obj("code" -> Json.fromInt(code), "message" -> str(message))).noSpaces).status(status)

  private def result(id: Json, value: Json): Response =
    Response.json(Json.obj("jsonrpc" -> str("2.0"), "id" -> id, "result" -> value).noSpaces)

  // Explicit allowlist rather than trusting Host (which could itself be rebound).
  private val localOrigins = Set("http://localhost:7777", "http://127.0.0.1:7777", "http://[::1]:7777")

  def routes[R](rest: HttpApp[R], allowedOrigins: Set[String] = localOrigins): Routes[R, Response] = {
    val catalog = operations // Validate the bundled contract at startup.
    def handle(req: Request): ZIO[R, Nothing, Response] = {
      if (!req.headers.get("Origin").forall(allowedOrigins.contains)) ZIO.succeed(Response.status(Status.Forbidden))
      else if (req.headers.get("MCP-Protocol-Version").exists(v => !versions(v)))
        ZIO.succeed(error(Json.Null, -32600, "Unsupported MCP protocol version", Status.BadRequest))
      else if (req.method != Method.POST) ZIO.succeed(Response.status(Status.MethodNotAllowed))
      else if (!req.headers.get("Content-Type").exists(_.takeWhile(_ != ';').trim.equalsIgnoreCase("application/json")))
        ZIO.succeed(Response.status(Status.UnsupportedMediaType))
      else if (!req.headers.get("Accept").exists(a => a.contains("application/json") && a.contains("text/event-stream")))
        ZIO.succeed(Response.status(Status.NotAcceptable))
      else req.body.asString.foldZIO(
        _ => ZIO.succeed(error(Json.Null, -32700, "Unable to read JSON", Status.BadRequest)),
        body => parser.parse(body) match {
          case Left(_) => ZIO.succeed(error(Json.Null, -32700, "Parse error", Status.BadRequest))
          case Right(message) =>
            val id = field(message, "id")
            val method = string(message, "method")
            val params = field(message, "params")
            val hasId = message.asObject.exists(_.contains("id"))
            val validId = id.isString || id.asNumber.exists(_.toBigDecimal.exists(_.isWhole))
            if (string(message, "jsonrpc") != Some("2.0") || method.isEmpty ||
                (hasId && !validId) || (!params.isNull && !params.isObject))
              ZIO.succeed(error(Json.Null, -32600, "Invalid Request", Status.BadRequest))
            else if (!hasId) ZIO.succeed(Response.status(Status.Accepted))
            else method.get match {
              case "initialize" =>
                if (string(params, "protocolVersion").isEmpty || !field(params, "capabilities").isObject ||
                    string(field(params, "clientInfo"), "name").isEmpty || string(field(params, "clientInfo"), "version").isEmpty)
                  ZIO.succeed(error(id, -32602, "Invalid initialize parameters"))
                else ZIO.succeed(result(id, Json.obj(
                  "protocolVersion" -> str(string(params, "protocolVersion").filter(versions).getOrElse(protocolVersion)),
                  "capabilities" -> Json.obj("tools" -> Json.obj("listChanged" -> Json.False)),
                  "serverInfo" -> Json.obj("name" -> str("trading-toolkit"), "version" -> str("1.0.0")),
                  "instructions" -> str("Tools mirror the REST API. Prefer market-qualified tools; unprefixed tools use US Massive. Dates are ISO dates; tickers lists are comma-separated. Data and watchlist dates can differ.")
                )))
              case "ping" => ZIO.succeed(result(id, Json.obj()))
              case "tools/list" =>
                if (!field(params, "cursor").isNull) ZIO.succeed(error(id, -32602, "This server returns all tools in one page"))
                else ZIO.succeed(result(id, Json.obj("tools" -> Json.arr(catalog.map(_.tool)*))))
              case "tools/call" =>
                catalog.find(op => string(params, "name").contains(op.name)) match {
                  case None => ZIO.succeed(error(id, -32602, "Unknown tool"))
                  case Some(op) =>
                    val args = if (params.asObject.exists(_.contains("arguments"))) field(params, "arguments") else Json.obj()
                    op.request(args) match {
                      case Left(message) => ZIO.succeed(error(id, -32602, message))
                      case Right(request) =>
                        (for {
                          response <- rest.runZIO(request)
                          body <- response.body.asString
                          content = Json.obj("status" -> Json.fromInt(response.status.code), "body" -> parser.parse(body).getOrElse(str(body)))
                        } yield result(id, Json.obj(
                          "content" -> Json.arr(Json.obj("type" -> str("text"), "text" -> str(content.noSpaces))),
                          "structuredContent" -> content,
                          "isError" -> Json.fromBoolean(response.status.code >= 400)
                        ))).catchAll(_ => ZIO.succeed(result(id, Json.obj(
                          "content" -> Json.arr(Json.obj("type" -> str("text"), "text" -> str("Internal server error"))),
                          "isError" -> Json.True
                        ))))
                    }
                }
              case _ => ZIO.succeed(error(id, -32601, "Method not found"))
            }
        }
      )
    }
    Routes(
      (Method.POST / "mcp") -> Handler.fromFunctionZIO[Request](handle),
      (Method.GET / "mcp") -> Handler.fromFunctionZIO[Request](handle),
      (Method.DELETE / "mcp") -> Handler.fromFunctionZIO[Request](handle)
    )
  }
}
