package io.greensheep.providers.massive
import io.greensheep.providers.massive.datatypes.{Aggregate, ApiResponse, ApiResponseSeq}
import io.greensheep.providers.massive.requests.{AggregatesRequest, GroupedDailyRequest}
import zio.ZIO
import zio.http.*
import zio.json.*

import scala.util.{Failure, Success}

/** Blocking/legacy Massive client. The 24/7 service uses [[MassiveAsyncApi]] instead. */
class MarketClient(settings: MassiveSettings) {

  private val authHeaders: Headers = settings.headers

    def getAggregates(request: AggregatesRequest): ZIO[Any, Throwable, Seq[Aggregate]]  = {
      val url = request.buildRequest(settings.baseUrl)
      val retriever = ZIO.scoped {
        for {
          res <- Client.request(Request(
            headers = authHeaders,
            url = settings.authenticate(URL.decode(url).toOption.get),
            method = Method.GET,
          ))
          resultString <- res.body.asString
          _ <- ZIO
            .fail(new RuntimeException(s"Massive aggregates request failed with HTTP ${res.status.code}: $resultString"))
            .when(res.status.isError)
          apiResponse <- ZIO.fromTry(resultString.fromJson[ApiResponse[Seq[Aggregate]]]
            .fold(l => {
              Failure(Exception(l))
            }, Success(_)))
          _ <- ZIO.fail(new RuntimeException(apiResponse.errorMessage)).unless(apiResponse.isValid)
        } yield apiResponse.results.getOrElse(Seq.empty)
      }
      retriever.provideLayer(Client.default)
    }

  def getAggregatesResponse(request: AggregatesRequest): ZIO[Any, Throwable, ApiResponse[Seq[Aggregate]]] = {
    val url = request.buildRequest(settings.baseUrl)
    val retriever = ZIO.scoped {
      for {
        res <- Client.request(Request(
          headers = authHeaders,
          url = settings.authenticate(URL.decode(url).toOption.get),
          method = Method.GET,
        ))
        resultString <- res.body.asString
        _ <- ZIO
          .fail(new RuntimeException(s"Massive aggregates request failed with HTTP ${res.status.code}: $resultString"))
          .when(res.status.isError)
        apiResponse <- ZIO.fromTry(resultString.fromJson[ApiResponse[Seq[Aggregate]]]
          .fold(l => Failure(Exception(l)), Success(_)))
        _ <- ZIO.fail(new RuntimeException(apiResponse.errorMessage)).unless(apiResponse.isValid)
      } yield apiResponse
    }
    retriever.provideLayer(Client.default)
  }

  def getGroupedDaily(request: GroupedDailyRequest): ZIO[Any, Throwable, Map[String, Aggregate]] = {
    val url = request.buildQuery(settings.baseUrl)
    val r = ZIO.scoped {
      for {
        res <- Client.request(Request(
          headers = settings.headers,
          url = settings.authenticate(URL.decode(url).toOption.get),
          method = Method.GET,
        ))
        resultString <- res.body.asString
        apiResponse <- ZIO.fromTry(resultString.fromJson[ApiResponseSeq[Aggregate]]
          .fold(l => Failure(Exception(l)), Success(_)))
        _ <- ZIO.fail(new RuntimeException(apiResponse.getErrorMessage))
          .unless(apiResponse.isValid)
      } yield apiResponse.getResults.filter(_.T.isDefined).map(a => a.T.get -> a).toMap
    }
    r.provideLayer(Client.default)
  }

  def getGroupedDailyResponse(request: GroupedDailyRequest): ZIO[Any, Throwable, ApiResponse[Map[String, Aggregate]]] = {
    val url = request.buildQuery(settings.baseUrl)
    val r = ZIO.scoped {
      for {
        res <- Client.request(Request(
          headers = settings.headers,
          url = settings.authenticate(URL.decode(url).toOption.get),
          method = Method.GET,
        ))
        resultString <- res.body.asString
        apiResponse <- ZIO.fromTry(resultString.fromJson[ApiResponseSeq[Aggregate]]
          .fold(l => Failure(Exception(l)), Success(_)))
        _ <- ZIO.fail(new RuntimeException(apiResponse.getErrorMessage))
          .unless(apiResponse.isValid)
        response = ApiResponse(None, Some(apiResponse.getResults.filter(_.T.isDefined).map(a => a.T.get -> a).toMap),
          apiResponse.status, apiResponse.error)
      } yield response
    }
    r.provideLayer(Client.default)
  }
}
