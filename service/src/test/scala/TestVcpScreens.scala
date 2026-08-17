

import io.github.zeal18.zio.mongodb.driver.{MongoClient, MongoDatabase}
import io.greensheep.trading.core.screens.TrendScreens
import io.greensheep.trading.core.screens.utils.VCPParams
import io.greensheep.trading.core.types.StockMarket
import io.greensheep.trading.service.services.ScreenerServices
import zio.Console.printLine
import zio.test.*
import zio.{ZIO, ZLayer}

import java.time.LocalDate

object TestVcpScreens extends ZIOSpecDefault {


  val vcpLenientParams: VCPParams = VCPParams(
    minBaseDays = 10,        // allow shorter bases
    maxBaseDays = 90,        // allow longer consolidations
    minContractions = 1,     // detect 2+ contractions
    maxContractions = 5,
    zigzagReversalPct = 0.04,  // more sensitive to smaller swings
    decreasingDepthTolerancePct = 0.03, // allow some depth variance
    maxFirstDepthPct = 0.40,   // up to 40% initial drop
    minLastDepthPct = 0.02,    // as tight as 2% for final contraction
    atrShort = 14,             // faster ATR (short-term volatility)
    atrLong = 40,              // medium-term ATR for trend contrast
    volSma = 40,               // 40-day average for volume trend
    dryUpLastN = 8,            // look at last 8 days for volume dry-up
    dryUpLookbackDays = 150,   // compare against last ~7 months
    priorUptrendMa = 50,       // uptrend check using 50-day MA
    minUptrendSlopeBps = 0.0,  // allow flat MA, not necessarily rising
    breakoutVolumeBoost = 1.1  // 1.4× volume on breakout confirmation
  )

  val firstTest: Spec[StockMarket, Throwable] = test("some test")(
    for {
      screen <- TrendScreens.debugVCP(vcpLenientParams)
      _ <- ZIO.logInfo(s"Found ${screen.filter(_.hit.isDefined).size} stocks matching VCP pattern")
      _ <- ZIO.foreach(screen) { result =>
        printLine(s"${result.symbol} | ${result.hit.isDefined} | ${result.reasons.map(_.reason).mkString(", ")}")
      }

    } yield assertTrue(true)
  )


  override def spec: Spec[TestEnvironment, Any] = {
    val mongoLayer = MongoClient.live("mongodb://127.0.0.1:27017") >>> ZLayer.fromZIO(ZIO.serviceWith[MongoClient](_.getDatabase("polygon")))
    val market = ZLayer.fromZIO(ScreenerServices.LoadQuotes.provideLayer(mongoLayer))
    suite("VCP tests")(
      firstTest
    ).provideLayer(market) @@ TestAspect.ignore
  }
}
