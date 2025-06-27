package io.greensheep.trading.api

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.core.types.DailyQuoteData
import io.greensheep.trading.dataservice.MongoDataRepo
import sttp.apispec.openapi.OpenAPI
import sttp.apispec.openapi.circe.yaml.RichOpenAPI
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.*
import zio.{Task, ZLayer}

class PublicEndpoints(mongoLayer: ZLayer[Any, Throwable, MongoDatabase]) {


  val quotesEndpoint: PublicEndpoint[String, Unit, DailyQuoteData, Any] =
    endpoint.get
      .in("quotes" / path[String]("ticker"))
      .out(jsonBody[DailyQuoteData])

  val quotesServerEndpoint: ServerEndpoint[Any, Task] = {
    quotesEndpoint.serverLogic { name =>
      MongoDataRepo.getPriceData("AAPL").provideLayer(mongoLayer).map(Right(_))
    }
  }

  val openApiDocs: OpenAPI = OpenAPIDocsInterpreter().toOpenAPI(List(quotesEndpoint), "Hello API", "1.0")

  openApiDocs.toYaml


}
