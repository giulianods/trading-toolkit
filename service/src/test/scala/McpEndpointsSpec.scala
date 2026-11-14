package io.greensheep.trading.service.services

import io.circe.{Json, parser}
import io.github.zeal18.zio.mongodb.driver.MongoClient
import io.greensheep.trading.dataservice.MarketStore
import io.greensheep.trading.service.app.AppHealth
import zio.*
import zio.http.*
import zio.test.*

import java.nio.file.{Files, Paths}

object McpEndpointsSpec extends ZIOSpecDefault {
  private def json(text: String): Json = parser.parse(text).fold(throw _, identity)
  private def rpc(method: String, params: Json = Json.obj(), id: Json = Json.fromInt(1)): String =
    Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> id,
      "method" -> Json.fromString(method), "params" -> params).noSpaces
  private def post(body: String): Request = Request.post("/mcp", Body.fromString(body)).copy(headers = Headers(
    Header.Custom("Content-Type", "application/json"),
    Header.Custom("Accept", "application/json, text/event-stream")
  ))
  private def call(name: String, arguments: Json): String = rpc("tools/call", Json.obj(
    "name" -> Json.fromString(name), "arguments" -> arguments))
  private val rest = Handler.fromFunction[Request] { req => Response.json(Json.obj(
    "path" -> Json.fromString(req.url.path.encode),
    "query" -> Json.fromString(req.url.queryParams.encode),
    "accept" -> Json.fromString(req.headers.get("Accept").getOrElse("")),
    "tickers" -> Json.arr(Json.fromString("ZZZ"), Json.fromString("AAA"))
  ).noSpaces) }.toHttpApp
  private val app = McpEndpoints.routes(rest).toHttpApp
  private def send(body: String): Task[Json] = app.runZIO(post(body)).flatMap(_.body.asString).map(json)
  private def body(result: Json): Json = result.hcursor.downField("result").downField("structuredContent").downField("body").focus.get

  override def spec = suite("MCP REST parity and Streamable HTTP")(
    test("every implemented REST route has exactly one generated tool") {
      val root = Paths.get(java.lang.System.getProperty("ttk.repo.root"))
      val source = Files.readString(root.resolve("service/src/main/scala/io/greensheep/trading/service/services/ApiEndpoints.scala"))
      val route = """\(Method\.([A-Z]+)((?:\s*/\s*(?:"[^"]+"|string\("[^"]+"\)))+)\)""".r
      val segment = """string\("([^"]+)"\)|"([^"]+)"""".r
      val implemented = route.findAllMatchIn(source).map { m =>
        val path = segment.findAllMatchIn(m.group(2)).map { s =>
          Option(s.group(1)).map(v => s"{$v}").getOrElse(s.group(2))
        }.mkString("/", "/", "")
        m.group(1) -> path
      }.toSet
      val catalog = McpEndpoints.operations
      val resource = scala.io.Source.fromResource("openapi.yaml")
      val bundled = try resource.mkString finally resource.close()
      val contract = Files.readString(root.resolve("openapi.yaml"))
      assertTrue(implemented.nonEmpty, implemented == catalog.map(op => op.method -> op.path).toSet,
        catalog.map(_.name).distinct.size == catalog.size,
        contract == bundled)
    },
    test("negotiates initialization and advertises only implemented capabilities") {
      for {
        result <- send(rpc("initialize", json("""{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""")))
        fallback <- send(rpc("initialize", json("""{"protocolVersion":"unknown","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""")))
        invalid <- send(rpc("initialize"))
      } yield assertTrue(
        result.hcursor.downField("result").get[String]("protocolVersion") == Right("2025-06-18"),
        result.hcursor.downField("result").downField("capabilities").focus.contains(json("""{"tools":{"listChanged":false}}""")),
        fallback.hcursor.downField("result").get[String]("protocolVersion") == Right(McpEndpoints.protocolVersion),
        invalid.hcursor.downField("error").get[Int]("code") == Right(-32602)
      )
    },
    test("contract additions become tools and unsupported operations fail explicitly") {
      val yaml = """openapi: 3.0.3
paths:
  /new-endpoint:
    get:
      summary: New endpoint
      parameters:
        - name: limit
          in: query
          schema:
            type: integer
"""
      val added = McpEndpoints.operationsFromYaml(yaml)
      val unsupported = scala.util.Try(McpEndpoints.operationsFromYaml(yaml.replace("    get:", "    post:")))
      assertTrue(added.map(_.name) == Vector("get_new_endpoint"), added.head.parameters.head.name == "limit", unsupported.isFailure)
    },
    test("serves MCP JSON responses over an HTTP connection") {
      ZIO.scoped {
        for {
          port <- Server.install(app)
          client <- ZIO.service[Client]
          request = post(rpc("tools/list")).copy(url = URL.decode(s"http://127.0.0.1:$port/mcp").toOption.get)
          response <- client.request(request)
          text <- response.body.asString
          listed = json(text).hcursor.downField("result").get[Vector[Json]]("tools")
        } yield assertTrue(response.status == Status.Ok,
          response.headers.get("Content-Type").exists(_.startsWith("application/json")),
          listed == Right(McpEndpoints.operations.map(_.tool)))
      }.provide(Server.defaultWithPort(0), Client.default)
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds),
    test("lists schemas, preserves string request IDs and handles ping") {
      for {
        listed <- send(rpc("tools/list", id = Json.fromString("agent-1")))
        ping <- send(rpc("ping"))
      } yield assertTrue(
        listed.hcursor.get[String]("id") == Right("agent-1"),
        listed.hcursor.downField("result").get[Vector[Json]]("tools") == Right(McpEndpoints.operations.map(_.tool)),
        ping.hcursor.downField("result").focus.contains(Json.obj())
      )
    },
    test("all tools dispatch their documented REST operation with query and header arguments") {
      ZIO.foreach(McpEndpoints.operations) { op =>
        val args = Json.obj(op.parameters.map { p => p.name -> (if (p.name == "Accept") Json.fromString("text/csv")
          else if (p.name == "days") Json.fromInt(5) else Json.fromString(p.name + "+& value")) }*)
        for {
          expected <- ZIO.fromEither(op.request(args))
          result <- send(call(op.name, args))
        } yield assertTrue(
          body(result).hcursor.get[String]("path") == Right(expected.url.path.encode),
          body(result).hcursor.get[String]("query") == Right(expected.url.queryParams.encode),
          body(result).hcursor.get[String]("accept") == Right(expected.headers.get("Accept").getOrElse("")),
          body(result).hcursor.get[Vector[String]]("tickers") == Right(Vector("ZZZ", "AAA")),
          result.hcursor.downField("result").get[Boolean]("isError") == Right(false)
        )
      }.map(results => results.reduce(_ && _))
    },
    test("CSV and REST errors are returned as tool content with original HTTP status") {
      val csvApp = McpEndpoints.routes(Handler.succeed(Response.text("date,close\n2026-11-13,42\n")).toHttpApp).toHttpApp
      val missingApp = McpEndpoints.routes(Handler.succeed(Response.text("Unknown market").status(Status.NotFound)).toHttpApp).toHttpApp
      val request = post(call("get_quotes_ticker", json("""{"ticker":"AAPL","Accept":"text/csv"}""")))
      for {
        csv <- csvApp.runZIO(request).flatMap(_.body.asString).map(json)
        missing <- missingApp.runZIO(request).flatMap(_.body.asString).map(json)
      } yield assertTrue(
        body(csv).asString.contains("date,close\n2026-11-13,42\n"),
        missing.hcursor.downField("result").get[Boolean]("isError") == Right(true),
        missing.hcursor.downField("result").downField("structuredContent").get[Int]("status") == Right(404)
      )
    },
    test("real REST handlers share screen ordering and reject disabled markets through MCP") {
      ZIO.scoped {
        for {
          mongo <- ZIO.service[MongoClient]
          http <- ZIO.service[Client]
          api = ApiEndpoints.routes(new MarketStore(mongo, Seq.empty), http).toHttpApp
          mcp = McpEndpoints.routes(api).toHttpApp
          direct <- api.runZIO(Request.get("/markets/screens")).flatMap(_.body.asString).map(json)
          screens <- mcp.runZIO(post(call("get_markets_screens", Json.obj()))).flatMap(_.body.asString).map(json)
          disabled <- mcp.runZIO(post(call("get_markets_country_provider_quotes_ticker",
            json("""{"country":"us","provider":"alpaca","ticker":"AAPL"}""")))).flatMap(_.body.asString).map(json)
        } yield assertTrue(body(screens) == direct,
          disabled.hcursor.downField("result").get[Boolean]("isError") == Right(true),
          disabled.hcursor.downField("result").downField("structuredContent").get[Int]("status") == Right(404))
      }.provide(MongoClient.live("mongodb://127.0.0.1:1"), Client.default, AppHealth.layer)
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds),
    test("invalid arguments never reach REST handlers") {
      for {
        calls <- Ref.make(0)
        guarded = McpEndpoints.routes(Handler.fromFunctionZIO[Request](_ => calls.update(_ + 1).as(Response.ok)).toHttpApp).toHttpApp
        invalid = Vector(
          call("unknown", Json.obj()),
          call("get_quotes_ticker", Json.obj()),
          call("get_quotes_ticker", json("""{"ticker":"AAPL","unexpected":true}""")),
          call("get_quotes_ticker", json("""{"ticker":"../status"}""")),
          call("get_quotes_ticker", json("""{"ticker":"%2fstatus"}""")),
          call("get_quotes_ticker", json("""{"ticker":42}""")),
          call("get_quotes_ticker", json("""{"ticker":"AAPL","Accept":"text/html"}""")),
          call("get_breadth_indicators", json("""{"days":0}""")),
          call("get_breadth_indicators", json("""{"days":1.5}""")),
          call("get_status", Json.Null)
        )
        results <- ZIO.foreach(invalid)(payload => guarded.runZIO(post(payload)).flatMap(_.body.asString).map(json))
        count <- calls.get
      } yield assertTrue(count == 0, results.forall(_.hcursor.downField("error").get[Int]("code") == Right(-32602)))
    },
    test("accepts notifications without a response body and rejects malformed JSON-RPC") {
      for {
        notification <- app.runZIO(post("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        empty <- notification.body.asString
        malformed <- app.runZIO(post("{"))
        batch <- app.runZIO(post("[]"))
        unknown <- send(rpc("unknown"))
      } yield assertTrue(notification.status == Status.Accepted, empty.isEmpty,
        malformed.status == Status.BadRequest, batch.status == Status.BadRequest,
        unknown.hcursor.downField("error").get[Int]("code") == Right(-32601))
    },
    test("checks origins, media types and protocol headers; declines SSE and session deletion") {
      val request = post(rpc("ping"))
      for {
        origin <- app.runZIO(request.copy(headers = request.headers ++ Headers(Header.Custom("Origin", "https://evil.example"), Header.Custom("Host", "evil.example"))))
        local <- app.runZIO(request.copy(headers = request.headers ++ Headers(Header.Custom("Origin", "http://localhost:7777"))))
        version <- app.runZIO(request.copy(headers = request.headers ++ Headers(Header.Custom("MCP-Protocol-Version", "unknown"))))
        media <- app.runZIO(request.copy(headers = Headers(Header.Custom("Content-Type", "text/plain"))))
        accept <- app.runZIO(request.copy(headers = Headers(Header.Custom("Content-Type", "application/json"))))
        get <- app.runZIO(Request.get("/mcp"))
        delete <- app.runZIO(Request.get("/mcp").copy(method = Method.DELETE))
      } yield assertTrue(origin.status == Status.Forbidden, local.status == Status.Ok,
        version.status == Status.BadRequest, media.status == Status.UnsupportedMediaType,
        accept.status == Status.NotAcceptable, get.status == Status.MethodNotAllowed, delete.status == Status.MethodNotAllowed)
    }
  )
}
