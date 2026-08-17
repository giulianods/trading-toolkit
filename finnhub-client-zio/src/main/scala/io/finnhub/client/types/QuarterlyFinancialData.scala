package io.finnhub.client.types

import zio.json.*
import zio.json.ast.Json

object QuarterlyFinancialData {
  implicit val decoder: JsonDecoder[QuarterlyFinancialData] =
    JsonDecoder[Json.Obj].map { obj =>
      def field(name: String): List[FinancialDataPoint] =
        obj.get(name).flatMap(_.as[List[FinancialDataPoint]].toOption).getOrElse(Nil)

      QuarterlyFinancialData(
        assetTurnoverTTM = field("assetTurnoverTTM"),
        bookValue = field("bookValue"),
        cashRatio = field("cashRatio"),
        currentRatio = field("currentRatio"),
        ebitPerShare = field("ebitPerShare"),
        eps = field("eps"),
        ev = field("ev"),
        evEbitdaTTM = field("evEbitdaTTM"),
        evRevenueTTM = field("evRevenueTTM"),
        fcfMargin = field("fcfMargin"),
        grossMargin = field("grossMargin"),
        inventoryTurnoverTTM = field("inventoryTurnoverTTM"),
        longtermDebtTotalAsset = field("longtermDebtTotalAsset"),
        longtermDebtTotalCapital = field("longtermDebtTotalCapital"),
        longtermDebtTotalEquity = field("longtermDebtTotalEquity"),
        netDebtToTotalCapital = field("netDebtToTotalCapital"),
        netDebtToTotalEquity = field("netDebtToTotalEquity"),
        netMargin = field("netMargin"),
        operatingMargin = field("operatingMargin"),
        payoutRatioTTM = field("payoutRatioTTM"),
        pb = field("pb"),
        peTTM = field("peTTM"),
        pfcfTTM = field("pfcfTTM"),
        pretaxMargin = field("pretaxMargin"),
        psTTM = field("psTTM"),
        ptbv = field("ptbv"),
        quickRatio = field("quickRatio"),
        receivablesTurnoverTTM = field("receivablesTurnoverTTM"),
        roaTTM = field("roaTTM"),
        roeTTM = field("roeTTM"),
        roicTTM = field("roicTTM"),
        rotcTTM = field("rotcTTM"),
        salesPerShare = field("salesPerShare"),
        sgaToSale = field("sgaToSale"),
        tangibleBookValue = field("tangibleBookValue"),
        totalDebtToEquity = field("totalDebtToEquity"),
        totalDebtToTotalAsset = field("totalDebtToTotalAsset"),
        totalDebtToTotalCapital = field("totalDebtToTotalCapital"),
        totalRatio = field("totalRatio")
      )
    }

  implicit val encoder: JsonEncoder[QuarterlyFinancialData] = DeriveJsonEncoder.gen[QuarterlyFinancialData]
}

final case class QuarterlyFinancialData(
    assetTurnoverTTM: List[FinancialDataPoint] = Nil,
    bookValue: List[FinancialDataPoint] = Nil,
    cashRatio: List[FinancialDataPoint] = Nil,
    currentRatio: List[FinancialDataPoint] = Nil,
    ebitPerShare: List[FinancialDataPoint] = Nil,
    eps: List[FinancialDataPoint] = Nil,
    ev: List[FinancialDataPoint] = Nil,
    evEbitdaTTM: List[FinancialDataPoint] = Nil,
    evRevenueTTM: List[FinancialDataPoint] = Nil,
    fcfMargin: List[FinancialDataPoint] = Nil,
    grossMargin: List[FinancialDataPoint] = Nil,
    inventoryTurnoverTTM: List[FinancialDataPoint] = Nil,
    longtermDebtTotalAsset: List[FinancialDataPoint] = Nil,
    longtermDebtTotalCapital: List[FinancialDataPoint] = Nil,
    longtermDebtTotalEquity: List[FinancialDataPoint] = Nil,
    netDebtToTotalCapital: List[FinancialDataPoint] = Nil,
    netDebtToTotalEquity: List[FinancialDataPoint] = Nil,
    netMargin: List[FinancialDataPoint] = Nil,
    operatingMargin: List[FinancialDataPoint] = Nil,
    payoutRatioTTM: List[FinancialDataPoint] = Nil,
    pb: List[FinancialDataPoint] = Nil,
    peTTM: List[FinancialDataPoint] = Nil,
    pfcfTTM: List[FinancialDataPoint] = Nil,
    pretaxMargin: List[FinancialDataPoint] = Nil,
    psTTM: List[FinancialDataPoint] = Nil,
    ptbv: List[FinancialDataPoint] = Nil,
    quickRatio: List[FinancialDataPoint] = Nil,
    receivablesTurnoverTTM: List[FinancialDataPoint] = Nil,
    roaTTM: List[FinancialDataPoint] = Nil,
    roeTTM: List[FinancialDataPoint] = Nil,
    roicTTM: List[FinancialDataPoint] = Nil,
    rotcTTM: List[FinancialDataPoint] = Nil,
    salesPerShare: List[FinancialDataPoint] = Nil,
    sgaToSale: List[FinancialDataPoint] = Nil,
    tangibleBookValue: List[FinancialDataPoint] = Nil,
    totalDebtToEquity: List[FinancialDataPoint] = Nil,
    totalDebtToTotalAsset: List[FinancialDataPoint] = Nil,
    totalDebtToTotalCapital: List[FinancialDataPoint] = Nil,
    totalRatio: List[FinancialDataPoint] = Nil
)
