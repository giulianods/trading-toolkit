package io.greensheep.providers.polygon.requests

import io.greensheep.providers.polygon.datatypes.{ApiResponse, SplitData}
import zio.ZIO
import zio.http.{Client, Method, QueryParams, Request}
import zio.json.*


case class SplitsRequest(
                        dateFrom: String,
                        dateTo: String,
                        maxResults: Int,
                        reverse: Boolean = false,
                        ) extends ApiRequest[Seq[SplitData]] {

  override def perform(): ZIO[Client, Throwable, ApiResponse[Seq[SplitData]]] = {
    for {
      client <- ZIO.service[Client]
      url = (client.url / "v3" / "reference" / "splits")
        .addQueryParams(QueryParams(
          "execution_date.gte" -> dateFrom,
          "execution_date.lte" -> dateTo,
          "limit" -> maxResults.toString,
          "reverse_split" -> reverse.toString
        ))
      resultString <- PolygonHttp.get(url)
      decoded = resultString.fromJson[ApiResponse[Seq[SplitData]]]
      apiResponse <- ZIO.fromEither(decoded.left.map(RuntimeException(_)))
      _ <- ZIO.fail(new RuntimeException(apiResponse.errorMessage)).unless(apiResponse.isValid)
      resultEither = apiResponse.results.toRight(new RuntimeException(s"Missing result from json: $decoded"))
      _ <- ZIO.fromEither(resultEither)
    } yield apiResponse

  }
}
