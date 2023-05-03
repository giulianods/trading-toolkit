package org.greensheep.trading.dataservice

import jdk.javadoc.doclet.DocletEnvironment.ModuleMode
import org.greensheep.trading.core.services.DataSource
import org.greensheep.trading.core.types.{Quote, Stock}
import org.greensheep.trading.dataservice.FinnhubDataSource.{API_KEY, BASE_URL, DEFAULT_ENV, FinnhubSymbol, FinnhubSymbolList, MICS, logger}
import zhttp.http.{Headers, Response}
import zhttp.service.{ChannelFactory, Client, EventLoopGroup}
import zio.{IO, Task, ZIO}

import java.nio.file.{Files, Paths}
import scala.util.{Failure, Success, Try}
import zio.json._

import java.nio.charset.StandardCharsets
import java.util.logging.Logger
import scala.collection.mutable




object FinnhubDataSource {

  val logger: Logger = Logger.getLogger(classOf[FinnhubDataSource].getName)

  case class ServiceConfig(key: String)

  implicit val decoder: JsonDecoder[ServiceConfig] =
    DeriveJsonDecoder.gen[ServiceConfig]

  val BASE_URL = "https://finnhub.io/api/v1"

  val MICS = Seq("ARCX", "BATS", "XASE", "XNCM", "XNGS", "XNMS", "XNYS", "XNAS")

  val API_KEY: String = {
    val path = Paths.get(java.lang.System.getProperty("user.home")).resolve(".finnhub").resolve("config.json")
    val key = for{
      configStr <- Try(Files.readString(path))
      config <- JsonDecoder[ServiceConfig].decodeJson(configStr)
        .fold(failure => Failure(new Exception(failure)), s => Success(s))
    } yield config
    key match {
      case Success(k) => k.key
      case Failure(e) =>
        logger.severe(e.getMessage)
        ""
    }
  }

  val DEFAULT_ENV = ChannelFactory.auto ++ EventLoopGroup.auto()

  case class FinnhubSymbolList(
                                symbols: Seq[FinnhubSymbol]
                              )

  case class FinnhubSymbol(
                            symbol: String,
                            description: String,
                            `type`: String,
                            mic: String
                          )

  object FinnhubSymbol {
    implicit val symbDecoder: JsonDecoder[FinnhubSymbol] = DeriveJsonDecoder.gen[FinnhubSymbol]
  }

  object FinnhubSymbolList {
    implicit val symbListDecoder: JsonDecoder[FinnhubSymbolList] = DeriveJsonDecoder.gen[FinnhubSymbolList]
  }
}

class FinnhubDataSource()  {




  val authHeader = Headers.apply("X-Finnhub-Token", API_KEY)

  def getQuotesZ(symbol: String) =
    Client.request(s"$BASE_URL/stock/candle?$symbol")


  def getSymbolsZIO: ZIO[Any, Throwable, Response] =
    Client.request(s"$BASE_URL/stock/symbol?exchange=US&securityType=Common%20Stock", headers = authHeader)
      .provideLayer(DEFAULT_ENV)

  def getCompanyProfileZIO(symbol: String): ZIO[Any, Throwable, Response] =
    Client.request(s"$BASE_URL/stock/profile?symbol=$symbol", headers = authHeader)
      .provideLayer(DEFAULT_ENV)



  def getStocks(): Map[String, Stock] = ???

  def getSymbols: Task[Seq[String]] = {
    for{
      request <- getSymbolsZIO
      resp <- request.body.asString
      _ <- ZIO.when(!request.status.isSuccess)(ZIO.fail(new RuntimeException(s"Call failed with ${request.status}\n$resp")))
      stocks <- ZIO.fromEither(resp.fromJson[Seq[FinnhubSymbol]]).mapError(s => new RuntimeException(s))
    } yield(stocks.filter(s => MICS.contains(s.mic)).map(s => s.symbol + " " + s.`type`))
  }

  def getStock(symbol: String): IO[Throwable, Stock] = {
    for {
      request <- getCompanyProfileZIO(symbol)
      resp <- request.body.asString
      _ <- ZIO.when(!request.status.isSuccess)(ZIO.fail(new RuntimeException(s"Call failed with ${request.status}\n$resp")))
      profile <- ZIO.fromEither(resp.fromJson[CompanyProfile]).mapError(s => new RuntimeException(s))
      stock = Stock(profile.ticker, profile.name, profile.ipo)
    } yield stock
  }





  def getQuotes(symbol: String): Iterable[Quote] = {
    val env     = ChannelFactory.auto ++ EventLoopGroup.auto()
    val program = for {
      res <- Client.request(s"$BASE_URL/symbol?exchange=US", headers = authHeader).provideLayer(env)
      data <- res.body.asString(StandardCharsets.UTF_8)
      _ = logger.info("got data:+")
      _ = logger.info(data)
    } yield ()
    Seq.empty
  }


}
