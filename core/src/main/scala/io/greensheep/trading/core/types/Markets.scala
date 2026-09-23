package io.greensheep.trading.core.types

import io.greensheep.trading.core.utils.LocalConfig

object Markets {

  /** Only leftover first-party name for the pre-rename Mongo database. */
  val LegacyUsDatabaseName: String = "polygon"

  val UsMassive: MarketDefinition = MarketDefinition(
    id = MarketId.UsMassive,
    timeZone = "America/New_York",
    dataSource = "massive",
    cron = MarketCron(
      quotes = "0 30 0 * * ?",
      splits = "0 0 2 * * ?",
      clean = Some("0 30 2 * * ?"),
      screens = "0 0 3 * * ?",
      financials = Some("0 15 0 ? * SAT")
    ),
    hasFinancials = true
  )

  val RoBvb: MarketDefinition = MarketDefinition(
    id = MarketId.RoBvb,
    timeZone = "Europe/Bucharest",
    dataSource = "bvb",
    cron = MarketCron(
      quotes = "0 30 0 * * ?",
      splits = "0 0 2 * * ?",
      clean = None,
      screens = "0 0 3 * * ?",
      financials = None
    ),
    hasFinancials = false
  )

  val DeXetra: MarketDefinition = MarketDefinition(
    id = MarketId.DeXetra,
    timeZone = "Europe/Berlin",
    dataSource = "xetra",
    cron = MarketCron(
      quotes = "0 30 0 * * ?",
      splits = "0 0 2 * * ?",
      clean = None,
      screens = "0 0 3 * * ?",
      financials = None
    ),
    hasFinancials = false
  )

  val JpJquants: MarketDefinition = MarketDefinition(
    id = MarketId.JpJquants,
    timeZone = "Asia/Tokyo",
    dataSource = "jquants",
    cron = MarketCron(
      quotes = "0 30 0 * * ?",
      splits = "0 0 2 * * ?",
      clean = None,
      screens = "0 0 3 * * ?",
      financials = None
    ),
    hasFinancials = false
  )

  val catalog: Map[MarketId, MarketDefinition] = Map(
    UsMassive.id -> UsMassive,
    RoBvb.id -> RoBvb,
    DeXetra.id -> DeXetra,
    JpJquants.id -> JpJquants
  )

  def parseEnabled(raw: String): Either[String, Seq[MarketDefinition]] = {
    val tokens = raw.split(",").map(_.trim).filter(_.nonEmpty).toSeq
    if (tokens.isEmpty) Left("ttk.markets must list at least one market")
    else {
      val parsed = tokens.map { token =>
        MarketId.parse(token).flatMap { id =>
          catalog.get(id).toRight(s"Unsupported market '$token'")
        }
      }
      parsed.find(_.isLeft) match {
        case Some(Left(error)) => Left(error)
        case _ => Right(parsed.collect { case Right(definition) => definition }.distinct)
      }
    }
  }

  def enabledFromConfig: Either[String, Seq[MarketDefinition]] =
    parseEnabled(LocalConfig.get("ttk.markets").getOrElse(UsMassive.id.id))
}
