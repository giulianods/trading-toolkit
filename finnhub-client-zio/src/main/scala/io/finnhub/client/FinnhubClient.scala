
package io.finnhub.client

import zio.*
import zio.http.*
import zio.json.*
import io.finnhub.client.types.*

final class FinnhubClient private (private val apiKey: String) {

    private def endpoint(path: String, params: (String, String)*): IO[Throwable, URL] =
        ZIO
            .fromOption(URL.decode(s"https://finnhub.io/api/v1/$path").toOption)
            .orElseFail(new IllegalArgumentException(s"Invalid Finnhub endpoint: $path"))
            .map { url =>
                val queryParams = params :+ ("token" -> apiKey)
                url.addQueryParams(QueryParams(queryParams.head, queryParams.tail*))
            }

    private def getAndDecode[A: JsonDecoder](url: URL, context: String): ZIO[Client & Scope, Throwable, A] =
        for
            response <- requestFollowingRedirects(url, remaining = 3)
            body <- response.body.asString
            _ <- ZIO
                .fail(new RuntimeException(s"Finnhub $context failed with HTTP ${response.status.code}: $body"))
                .when(response.status.isError)
            result <- ZIO
                .fromEither(body.fromJson[A])
                .mapError(message => new RuntimeException(s"Failed to decode Finnhub $context response: $message"))
        yield result

    private def requestFollowingRedirects(url: URL, remaining: Int): ZIO[Client & Scope, Throwable, Response] =
        Client.request(Request.get(url)).flatMap { response =>
            if response.status.isRedirection && remaining > 0 then
                for
                    location <- ZIO
                        .fromOption(response.headers.get("Location"))
                        .orElseFail(new RuntimeException("Finnhub redirect response did not include a Location header"))
                    redirectUrl <- ZIO
                        .fromOption(URL.decode(location).toOption)
                        .orElseFail(new RuntimeException("Finnhub returned an invalid redirect URL"))
                    redirected <- requestFollowingRedirects(redirectUrl, remaining - 1)
                yield redirected
            else if response.status.isRedirection then
                ZIO.fail(new RuntimeException("Finnhub exceeded the redirect limit"))
            else ZIO.succeed(response)
        }

    def basicFinancials(symbol: String, metric: String = "all"): ZIO[Client & Scope, Throwable, BasicFinancials] =
        endpoint("stock/metric", "symbol" -> symbol, "metric" -> metric)
            .flatMap(getAndDecode[BasicFinancials](_, s"basic financials for '$symbol'"))

    def stockSymbols(exchange: String = "US"): ZIO[Client & Scope, Throwable, List[StockSymbol]] =
        endpoint("stock/symbol", "exchange" -> exchange)
            .flatMap(getAndDecode[List[StockSymbol]](_, s"stock symbols for '$exchange'"))
}

object FinnhubClient {
    def make(apiKey: String): FinnhubClient = new FinnhubClient(apiKey)
}
