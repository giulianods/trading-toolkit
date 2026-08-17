package io.greensheep.providers.polygon.requests

import io.greensheep.providers.polygon.datatypes
import io.greensheep.providers.polygon.datatypes.{Aggregate, ApiResponse}
import zio.*
import zio.http.{Client, Method, QueryParams, Request}
import zio.json.*

case class AggregatesRequest(
                              stocksTicker: String,
                              multiplier: Int = 1,
                              timespan: String = "day",
                              from: String,
                              to: String,
                              adjusted: Boolean = true,
                              sort: String = "asc",
                              limit: Int = 5000
                            )  extends ApiRequest [Seq[Aggregate]]{


  def buildRequest(baseUrl: String): String = {
    val pathUrl = s"${baseUrl.stripSuffix("/")}/v2/aggs/ticker/$stocksTicker/range/$multiplier/$timespan/$from/$to"
    s"$pathUrl?adjusted=$adjusted&sort=$sort&limit=$limit"
  }

  def perform(): ZIO[Client, Throwable, ApiResponse[Seq[Aggregate]]] = {
    for{
      client <- ZIO.service[Client]
      url = (client.url / "v2" / "aggs" / "ticker" / stocksTicker / "range" / multiplier.toString / timespan / from / to)
        .addQueryParams(QueryParams(
          "adjusted" -> adjusted.toString,
          "sort" -> sort,
          "limit" -> limit.toString
        ))
      resultString <- ZIO.scoped {
        for{
          res <- client.request(Request(url = url, method = Method.GET))
          resultString <- res.body.asString
          _ <- if(res.status.isError)
            ZIO.fail(new RuntimeException(s"Error: ${res.status.code} - ${res.status.text} : $resultString"))
          else ZIO.unit

        } yield resultString
      }
      decoded = resultString.fromJson[ApiResponse[Seq[Aggregate]]]
      apiResponse <- ZIO.fromEither(decoded.left.map(RuntimeException(_)))
      _ <- ZIO.fail(new RuntimeException(apiResponse.errorMessage)).unless(apiResponse.isValid)
      resultSeq = apiResponse.results.getOrElse(Seq.empty)
    } yield apiResponse.copy(results = Some(resultSeq))

  }

}
