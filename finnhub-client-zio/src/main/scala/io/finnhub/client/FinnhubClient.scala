
package io.finnhub.client

import zio.*
import zio.http.*
import zio.json.*
import io.finnhub.client.types.*
import zio.json.ast.Json
import io.greensheep.trading.core.utils.{HttpStatusException, TransientRetry}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.jdk.OptionConverters.*

final class FinnhubClient private (private val apiKey: String, limiter: FinnhubRateLimiter) {

    private[client] def endpoint(path: String, params: (String, String)*): IO[Throwable, URL] =
        ZIO
            .fromOption(URL.decode(s"https://finnhub.io/api/v1/$path").toOption)
            .orElseFail(new IllegalArgumentException(s"Invalid Finnhub endpoint: $path"))
            .map { url =>
                val queryParams = params :+ ("token" -> apiKey)
                if (path == "stock/financials-reported") {
                    // Keep statement requests deterministic for diagnostics and tests.
                    url.copy(queryParams = QueryParams(scala.collection.immutable.ListMap.from(
                      queryParams.map { case (key, value) => key -> Chunk(value) }
                    )))
                } else url.addQueryParams(QueryParams(queryParams.head, queryParams.tail*))
            }

    private[client] def getAndDecode[A: JsonDecoder](url: URL, context: String): ZIO[Client & Scope, Throwable, A] =
        val once = ZIO.scoped {
          for
            response <- requestFollowingRedirects(url, remaining = 3)
            body = response.body()
            _ <- ZIO.when(response.statusCode() >= 400) {
              ZIO.fail(HttpStatusException(response.statusCode(), s"Finnhub $context request failed"))
            }
            result <- ZIO
              .fromEither(body.fromJson[A])
              .mapError(message => new RuntimeException(s"Failed to decode Finnhub $context response: $message"))
          yield result
        }
        once.tapError {
            case error if TransientRetry.isTransient(error) =>
                ZIO.logWarning(s"Retryable Finnhub request failure: $context (${TransientRetry.statusCode(error).map(_.toString).getOrElse("transport timeout or connection error")})")
            case _ => ZIO.unit
        }.retry(TransientRetry.http)

    private def requestFollowingRedirects(url: URL, remaining: Int): ZIO[Client & Scope, Throwable, HttpResponse[String]] =
        ZIO.logInfo(s"Finnhub GET ${url.copy(queryParams = url.queryParams.filter { case (key, _) =>
          Set("symbol", "freq", "metric", "exchange").contains(key)
        }).encode}") *>
        limiter.run(ZIO.fromCompletionStage {
          val request = HttpRequest.newBuilder(url.toJavaURI)
            .timeout(java.time.Duration.ofSeconds(30))
            .header("User-Agent", "TradingToolkit/0.1")
            .header("Accept", "application/json")
            .GET().build()
          FinnhubClient.httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        }
          .timeoutFail(new RuntimeException("Finnhub request timed out"))(30.seconds).flatMap { response =>
            if (response.statusCode() >= 400) ZIO.fail(HttpStatusException(response.statusCode(), "Finnhub request failed"))
            else ZIO.succeed(response)
        }).flatMap { response =>
            val redirect = Set(301, 302, 303, 307, 308).contains(response.statusCode())
            if redirect && remaining > 0 then
                for
                    location <- ZIO
                        .fromOption(response.headers().firstValue("Location").toScala)
                        .orElseFail(new RuntimeException("Finnhub redirect response did not include a Location header"))
                    redirectUrl <- ZIO
                        .fromOption(URL.decode(url.toJavaURI.resolve(location).toString).toOption)
                        .orElseFail(new RuntimeException("Finnhub returned an invalid redirect URL"))
                    redirected <- requestFollowingRedirects(redirectUrl, remaining - 1)
                yield redirected
            else if redirect then
                ZIO.fail(new RuntimeException("Finnhub exceeded the redirect limit"))
            else ZIO.succeed(response)
        }

    def basicFinancials(symbol: String, metric: String = "all"): ZIO[Client & Scope, Throwable, BasicFinancials] =
        endpoint("stock/metric", "symbol" -> symbol, "metric" -> metric)
            .flatMap(getAndDecode[BasicFinancials](_, s"basic financials for '$symbol'"))

    def stockSymbols(exchange: String = "US"): ZIO[Client & Scope, Throwable, List[StockSymbol]] =
        endpoint("stock/symbol", "exchange" -> exchange)
            .flatMap(getAndDecode[List[StockSymbol]](_, s"stock symbols for '$exchange'"))

    def financialsReported(symbol: String, frequency: String): ZIO[Client & Scope, Throwable, Json.Obj] =
        endpoint("stock/financials-reported", "symbol" -> symbol, "freq" -> frequency)
            .flatMap(getAndDecode[Json.Obj](_, s"$frequency reported financials for '$symbol'"))
            .flatMap { response =>
                response.get("data") match {
                    case Some(_: Json.Arr) => ZIO.succeed(response)
                    case _ => ZIO.fail(new IllegalStateException(s"Invalid Finnhub reported financials for '$symbol'"))
                }
            }
}

object FinnhubClient {
    // Reuse a process-wide connection pool. The JDK transport also bounds full-body
    // reads, unlike the Netty response-header timeout used by the shared REST client.
    private lazy val httpClient: HttpClient = HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(20))
      .version(HttpClient.Version.HTTP_1_1)
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()

    def make(apiKey: String): UIO[FinnhubClient] = FinnhubRateLimiter.make.map(new FinnhubClient(apiKey, _))
}
