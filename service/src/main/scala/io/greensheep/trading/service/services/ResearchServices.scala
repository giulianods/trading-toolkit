package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.core.types.{Portfolio, PortfolioTrade, Quote}
import io.greensheep.trading.dataservice.{MongoDataRepo, ResearchRepo}
import zio.*
import zio.http.*
import zio.json.*
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID

object ResearchServices {
  final case class ReportInput(title: String, markdown: String, asOf: LocalDate)
  object ReportInput { implicit val codec: JsonCodec[ReportInput] = DeriveJsonCodec.gen[ReportInput] }
  final case class ReportView(id: String, title: String, markdown: String, asOf: String, createdAt: String)
  object ReportView { implicit val codec: JsonCodec[ReportView] = DeriveJsonCodec.gen[ReportView] }
  final case class Position(ticker: String, quantity: BigDecimal, averageEntryPrice: BigDecimal, costBasis: BigDecimal,
      currentPrice: BigDecimal, quoteDate: Option[String], priceSource: String, marketValue: BigDecimal, unrealizedPnl: BigDecimal)
  object Position { implicit val codec: JsonCodec[Position] = DeriveJsonCodec.gen[Position] }
  final case class EquityPoint(date: String, equity: BigDecimal)
  object EquityPoint { implicit val codec: JsonCodec[EquityPoint] = DeriveJsonCodec.gen[EquityPoint] }
  final case class PortfolioView(currency: String, initialCapital: BigDecimal, cash: BigDecimal, equity: BigDecimal,
      marketValue: BigDecimal, realizedPnl: BigDecimal, unrealizedPnl: BigDecimal, totalPnl: BigDecimal,
      returnPercent: BigDecimal, positions: Seq[Position], trades: Seq[PortfolioTrade], history: Seq[EquityPoint])
  object PortfolioView { implicit val codec: JsonCodec[PortfolioView] = DeriveJsonCodec.gen[PortfolioView] }
  final case class Conflict(message: String) extends Exception(message)

  private def reportView(r: ResearchRepo.Report) = ReportView(r.id, r.title, r.markdown, r.asOf, r.createdAt)

  private def readJson[A: JsonDecoder](req: Request): Task[A] = for {
    _ <- ZIO.fail(new IllegalArgumentException("Content-Type must be application/json"))
      .unless(req.headers.get("Content-Type").exists(_.takeWhile(_ != ';').trim.equalsIgnoreCase("application/json")))
    text <- req.body.asString
    _ <- ZIO.fail(new IllegalArgumentException("Request body must be at most 1 MB")).when(text.length > 1000000)
    value <- ZIO.fromEither(text.fromJson[A]).mapError(e => new IllegalArgumentException(s"Invalid JSON: $e"))
  } yield value

  def reports(req: Request): ZIO[MongoDatabase, Throwable, Response] = for {
    limit <- ZIO.attempt(req.url.queryParams.get("limit").fold(50)(_.toInt))
      .mapError(_ => new IllegalArgumentException("limit must be an integer"))
    _ <- ZIO.attempt(require(limit >= 1 && limit <= 200, "limit must be between 1 and 200"))
    offset <- ZIO.attempt(req.url.queryParams.get("offset").fold(0)(_.toInt))
      .mapError(_ => new IllegalArgumentException("offset must be an integer"))
    _ <- ZIO.attempt(require(offset >= 0 && offset <= 1000000, "offset must be between 0 and 1000000"))
    rows <- ResearchRepo.reports(limit, offset)
  } yield Response.json(rows.map(reportView).toJson)

  def report(id: String): ZIO[MongoDatabase, Throwable, Response] =
    ResearchRepo.report(id).someOrFail(new NoSuchElementException("Report not found"))
      .map(r => Response.json(reportView(r).toJson))

  def createReport(req: Request): ZIO[MongoDatabase, Throwable, Response] = for {
    input <- readJson[ReportInput](req)
    _ <- ZIO.attempt {
      require(input.title.trim.nonEmpty && input.title.length <= 300, "title must contain 1-300 characters")
      require(input.markdown.trim.nonEmpty && input.markdown.length <= 900000, "markdown must contain 1-900000 characters")
    }
    now <- Clock.instant
    row = ResearchRepo.Report(UUID.randomUUID.toString, input.title.trim, input.markdown, input.asOf.toString,
      new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter.format(now))
    _ <- ResearchRepo.insertReport(row)
  } yield Response.json(reportView(row).toJson).status(Status.Created)

  private def decodeLedger(row: ResearchRepo.Ledger): Task[Vector[PortfolioTrade]] =
    ZIO.fromEither(row.json.fromJson[Vector[PortfolioTrade]]).mapError(e => new IllegalStateException(e))

  def addTrade(req: Request): ZIO[MongoDatabase, Throwable, Response] = for {
    input <- readJson[PortfolioTrade](req)
    now <- Clock.instant
    _ <- ZIO.attempt(Portfolio.validate(input, now))
    created <- appendTrade(input, 10)
  } yield Response.json(input.toJson).status(if (created) Status.Created else Status.Ok)

  private def appendTrade(input: PortfolioTrade, attempts: Int): ZIO[MongoDatabase, Throwable, Boolean] = for {
    row <- ResearchRepo.ledger
    trades <- decodeLedger(row)
    result <- trades.find(_.id == input.id) match {
      case Some(existing) if existing == input => ZIO.succeed(false)
      case Some(_) => ZIO.fail(Conflict("Trade id already exists with different details"))
      case None => for {
        _ <- ZIO.attempt {
          require(trades.size < 20000, "Portfolio trade limit reached")
          require(trades.lastOption.forall(t => !input.executedAt.isBefore(t.executedAt)),
            "Trades must be submitted in chronological order")
          Portfolio.replay(trades :+ input)
        }
        saved <- ResearchRepo.saveLedger(row, (trades :+ input).toJson)
        result <- if (saved) ZIO.succeed(true)
          else if (attempts > 0) appendTrade(input, attempts - 1)
          else ZIO.fail(Conflict("Portfolio changed concurrently; retry with the same trade id"))
      } yield result
    }
  } yield result

  def portfolio(country: String): ZIO[MongoDatabase, Throwable, Response] = for {
    row <- ResearchRepo.ledger
    trades <- decodeLedger(row)
    today <- Clock.instant.map(_.atZone(ZoneOffset.UTC).toLocalDate)
    quotes <- ZIO.foreach(trades.map(_.ticker).distinct) { ticker =>
      MongoDataRepo.getPriceData(ticker).map(d => ticker -> d.quotes.filter(q => !q.day.isAfter(today)).sortBy(_.day.toEpochDay))
        .catchSome { case _: NoSuchElementException => ZIO.succeed(ticker -> Seq.empty[Quote]) }
    }.map(_.toMap)
    view <- ZIO.attempt(valuePortfolio(country, trades, quotes, today))
  } yield Response.json(view.toJson)

  private[services] def valuePortfolio(country: String, trades: Vector[PortfolioTrade], quotes: Map[String, Seq[Quote]], today: LocalDate): PortfolioView = {
    def positionsAt(included: Seq[PortfolioTrade], day: LocalDate): Seq[Position] = {
      val ledger = Portfolio.replay(included)
      ledger.holdings.map { h =>
        val lastTrade = included.reverseIterator.find(_.ticker == h.ticker).get
        val tradeDay = lastTrade.executedAt.atZone(ZoneOffset.UTC).toLocalDate
        val quote = quotes.getOrElse(h.ticker, Seq.empty).filter(q => !q.day.isAfter(day) && !q.day.isBefore(tradeDay) && q.c > 0 && q.c.isFinite).lastOption
        val price = quote.map(q => BigDecimal(q.c)).getOrElse(lastTrade.price)
        val value = price * h.quantity
        Position(h.ticker, h.quantity, h.costBasis / h.quantity, h.costBasis, price, quote.map(_.day.toString),
          if (quote.isDefined) "close" else "last_fill", value, value - h.costBasis)
      }
    }
    val ledger = Portfolio.replay(trades)
    val positions = positionsAt(trades, today)
    val marketValue = positions.map(_.marketValue).sum
    val equity = ledger.cash + marketValue
    val firstDay = trades.headOption.map(_.executedAt.atZone(ZoneOffset.UTC).toLocalDate)
    val days = firstDay.toSeq.flatMap { first =>
      (quotes.values.flatten.map(_.day).filter(d => !d.isBefore(first)) ++
        trades.map(_.executedAt.atZone(ZoneOffset.UTC).toLocalDate) ++ Seq(today)).toSeq.distinct.sortBy(_.toEpochDay)
    }
    val history = firstDay.toSeq.map(d => EquityPoint(d.minusDays(1).toString, Portfolio.InitialCapital)) ++ days.map { day =>
      val included = trades.filter(t => !t.executedAt.atZone(ZoneOffset.UTC).toLocalDate.isAfter(day))
      EquityPoint(day.toString, Portfolio.replay(included).cash + positionsAt(included, day).map(_.marketValue).sum)
    }
    PortfolioView(if (country == "ro") "RON" else if (country == "ca") "CAD" else "USD", Portfolio.InitialCapital,
      ledger.cash, equity, marketValue, ledger.realizedPnl, positions.map(_.unrealizedPnl).sum,
      equity - Portfolio.InitialCapital, (equity / Portfolio.InitialCapital - 1) * 100, positions, trades.reverse, history)
  }
}
