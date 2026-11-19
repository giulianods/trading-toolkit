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

  val UsAlpaca: MarketDefinition = UsMassive.copy(
    id = MarketId.UsAlpaca,
    dataSource = "alpaca",
    cron = UsMassive.cron.copy(quotes = "0 30 20 * * ?", clean = None)
  )

  val UsFinnhub: MarketDefinition = UsAlpaca.copy(id = MarketId.UsFinnhub, dataSource = "finnhub")

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

  val CaEodhd: MarketDefinition = MarketDefinition(
    id = MarketId.CaEodhd,
    timeZone = "America/Toronto",
    dataSource = "eodhd",
    cron = MarketCron(
      quotes = "0 30 0 ? * TUE-SAT",
      splits = "0 0 2 * * ?",
      clean = None,
      screens = "0 0 3 * * ?",
      financials = None
    ),
    hasFinancials = false
  )

  val catalog: Map[MarketId, MarketDefinition] = Map(
    UsMassive.id -> UsMassive,
    UsAlpaca.id -> UsAlpaca,
    UsFinnhub.id -> UsFinnhub,
    RoBvb.id -> RoBvb,
    DeXetra.id -> DeXetra,
    JpJquants.id -> JpJquants,
    CaEodhd.id -> CaEodhd
  )

  def parseEnabled(raw: String): Either[String, Seq[MarketDefinition]] =
    parseEnabled(raw.split(",").map(_.trim).filter(_.nonEmpty).toSeq)

  private def parseEnabled(tokens: Seq[String]): Either[String, Seq[MarketDefinition]] = {
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
    configured(LocalConfig.getList("ttk.markets").getOrElse(Seq(UsMassive.id.id)), LocalConfig.get, LocalConfig.getList)

  def configured(tokens: Seq[String], read: String => Option[String], readList: String => Option[Seq[String]]): Either[String, Seq[MarketDefinition]] = {
    val parsed = tokens.map { token =>
      for {
        id <- MarketId.parse(token)
        _ <- Either.cond("[a-z]{2}-[a-z][a-z0-9-]*".r.matches(id.id), (), s"Invalid market profile ID '$token'")
        prefix = s"ttk.profiles.${id.id}"
        requestedPrice = read(s"$prefix.prices")
        base <- catalog.get(id).orElse(catalog.values.find(m => m.id.country == id.country && requestedPrice.contains(m.id.provider)))
          .toRight(s"Unsupported market profile '$token'; configure its price provider")
        defaults = base.sources
        prices = read(s"$prefix.prices").getOrElse(defaults.prices)
        sources = MarketSources(prices, read(s"$prefix.financials").getOrElse(defaults.financials),
          read(s"$prefix.metadata").getOrElse(defaults.metadata), read(s"$prefix.listings").getOrElse(if (prices == "alpaca") "finnhub" else prices))
        _ <- MarketSources.validate(id.country, sources)
        schedule = catalog.values.find(m => m.id.country == id.country && m.id.provider == prices).map(_.cron).getOrElse(base.cron)
        aliases <- readList(s"$prefix.aliases").getOrElse(Seq.empty).foldLeft[Either[String, Seq[MarketId]]](Right(Seq.empty)) {
          case (acc, value) => for {
            ids <- acc
            alias <- MarketId.parse(value)
            _ <- Either.cond(alias.country == id.country && "[a-z]{2}-[a-z][a-z0-9-]*".r.matches(alias.id), (), "Aliases must be valid IDs in the same country")
          } yield ids :+ alias
        }
      } yield base.copy(id = id, dataSource = sources.prices, cron = schedule, providers = Some(sources),
        hasFinancials = sources.financials != "none", displayLabel = read(s"$prefix.label"), aliases = aliases)
    }
    parsed.collectFirst { case Left(error) => error } match {
      case Some(error) => Left(error)
      case None =>
        val profiles = parsed.collect { case Right(value) => value }.distinct
        val names = profiles.flatMap(p => p.id +: p.aliases)
        if (profiles.isEmpty) Left("ttk.markets must list at least one market profile")
        else if (names.distinct.size != names.size) Left("Market profile IDs and aliases must be unique")
        else Right(profiles)
    }
  }
}
