package io.greensheep.providers.polygon.requests
import io.greensheep.providers.polygon.PolygonSettings
import io.greensheep.providers.polygon.datatypes.ApiResponse
import io.greensheep.providers.polygon.requests.ApiRequest.PolygonClient
import zio.ZIO
import zio.http.*
import zio.json.*
case class PageRequest[T](url: URL)
                         (implicit val decoder: JsonDecoder[T]) extends ApiRequest [T]{
  def perform(): ZIO[Client, Throwable, ApiResponse[T]] = {
      for {
        client <- ZIO.service[Client]
        resultString <- ZIO.scoped {
          for{
            res <- client.request(Request(url = url, method = Method.GET))
            resultString <- res.body.asString
            _ <- if(res.status.isError)
              ZIO.fail(new RuntimeException(s"Error: ${res.status.code} - ${res.status.text} : $resultString"))
            else ZIO.unit

          } yield resultString
        }
        decoded = resultString.fromJson[ApiResponse[T]]
        apiResponse <- ZIO.fromEither(decoded.left.map(RuntimeException(_)))
        _ <- ZIO.fail(new RuntimeException(apiResponse.errorMessage)).unless(apiResponse.isValid)
        resultEither = apiResponse.results.toRight(new RuntimeException(s"Missing result from json: $decoded"))
        _ <- ZIO.fromEither(resultEither)
      } yield apiResponse
  }
}
