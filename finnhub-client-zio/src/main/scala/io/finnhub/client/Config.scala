package io.finnhub.client

import zio.*

object Config:
  def getApiKey: ZIO[Any, String, String] = 
    System.env("FINNHUB_API_KEY")
      .mapError(_ => "Failed to access environment variables")
      .someOrFail("FINNHUB_API_KEY environment variable is required")
  
  def getApiKeyOrEmpty: ZIO[Any, Nothing, String] =
    System.env("FINNHUB_API_KEY")
      .map(_.getOrElse(""))
      .catchAll(_ => ZIO.succeed(""))
