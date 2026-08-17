package io.finnhub.client.types

import zio.json.*
import zio.json.ast.Json

object AnnualFinancialData {
  implicit val decoder: JsonDecoder[AnnualFinancialData] =
    JsonDecoder[Json.Obj].map { obj =>
      def field(name: String): List[FinancialDataPoint] =
        obj.get(name).flatMap(_.as[List[FinancialDataPoint]].toOption).getOrElse(Nil)

      AnnualFinancialData(
        bookValue = field("bookValue"),
        cashRatio = field("cashRatio"),
        currentRatio = field("currentRatio"),
        ebitPerShare = field("ebitPerShare"),
        eps = field("eps"),
        ev = field("ev"),
        evEbitda = field("evEbitda"),
        evRevenue = field("evRevenue"),
        fcfMargin = field("fcfMargin"),
        grossMargin = field("grossMargin"),
        inventoryTurnover = field("inventoryTurnover"),
        longtermDebtTotalAsset = field("longtermDebtTotalAsset"),
        longtermDebtTotalCapital = field("longtermDebtTotalCapital"),
        longtermDebtTotalEquity = field("longtermDebtTotalEquity"),
        netDebtToTotalCapital = field("netDebtToTotalCapital"),
        netDebtToTotalEquity = field("netDebtToTotalEquity"),
        netMargin = field("netMargin"),
        operatingMargin = field("operatingMargin"),
        payoutRatio = field("payoutRatio"),
        pb = field("pb"),
        pe = field("pe"),
        pfcf = field("pfcf"),
        pretaxMargin = field("pretaxMargin"),
        ps = field("ps"),
        ptbv = field("ptbv"),
        quickRatio = field("quickRatio"),
        receivablesTurnover = field("receivablesTurnover"),
        roa = field("roa"),
        roe = field("roe"),
        roic = field("roic"),
        rotc = field("rotc"),
        salesPerShare = field("salesPerShare"),
        sgaToSale = field("sgaToSale"),
        tangibleBookValue = field("tangibleBookValue"),
        totalDebtToEquity = field("totalDebtToEquity"),
        totalDebtToTotalAsset = field("totalDebtToTotalAsset"),
        totalDebtToTotalCapital = field("totalDebtToTotalCapital"),
        totalRatio = field("totalRatio")
      )
    }

  implicit val encoder: JsonEncoder[AnnualFinancialData] = DeriveJsonEncoder.gen[AnnualFinancialData]
}

final case class AnnualFinancialData(
    bookValue: List[FinancialDataPoint] = Nil,
    cashRatio: List[FinancialDataPoint] = Nil,
    currentRatio: List[FinancialDataPoint] = Nil,
    ebitPerShare: List[FinancialDataPoint] = Nil,
    eps: List[FinancialDataPoint] = Nil,
    ev: List[FinancialDataPoint] = Nil,
    evEbitda: List[FinancialDataPoint] = Nil,
    evRevenue: List[FinancialDataPoint] = Nil,
    fcfMargin: List[FinancialDataPoint] = Nil,
    grossMargin: List[FinancialDataPoint] = Nil,
    inventoryTurnover: List[FinancialDataPoint] = Nil,
    longtermDebtTotalAsset: List[FinancialDataPoint] = Nil,
    longtermDebtTotalCapital: List[FinancialDataPoint] = Nil,
    longtermDebtTotalEquity: List[FinancialDataPoint] = Nil,
    netDebtToTotalCapital: List[FinancialDataPoint] = Nil,
    netDebtToTotalEquity: List[FinancialDataPoint] = Nil,
    netMargin: List[FinancialDataPoint] = Nil,
    operatingMargin: List[FinancialDataPoint] = Nil,
    payoutRatio: List[FinancialDataPoint] = Nil,
    pb: List[FinancialDataPoint] = Nil,
    pe: List[FinancialDataPoint] = Nil,
    pfcf: List[FinancialDataPoint] = Nil,
    pretaxMargin: List[FinancialDataPoint] = Nil,
    ps: List[FinancialDataPoint] = Nil,
    ptbv: List[FinancialDataPoint] = Nil,
    quickRatio: List[FinancialDataPoint] = Nil,
    receivablesTurnover: List[FinancialDataPoint] = Nil,
    roa: List[FinancialDataPoint] = Nil,
    roe: List[FinancialDataPoint] = Nil,
    roic: List[FinancialDataPoint] = Nil,
    rotc: List[FinancialDataPoint] = Nil,
    salesPerShare: List[FinancialDataPoint] = Nil,
    sgaToSale: List[FinancialDataPoint] = Nil,
    tangibleBookValue: List[FinancialDataPoint] = Nil,
    totalDebtToEquity: List[FinancialDataPoint] = Nil,
    totalDebtToTotalAsset: List[FinancialDataPoint] = Nil,
    totalDebtToTotalCapital: List[FinancialDataPoint] = Nil,
    totalRatio: List[FinancialDataPoint] = Nil
)
