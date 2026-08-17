package io.finnhub.client.types
import zio.json.*

object QuarterlyFinancialData {
    implicit val decoder: JsonDecoder[QuarterlyFinancialData] = DeriveJsonDecoder.gen[QuarterlyFinancialData]
    implicit val encoder: JsonEncoder[QuarterlyFinancialData] = DeriveJsonEncoder.gen[QuarterlyFinancialData]
}

final case class QuarterlyFinancialData(
    assetTurnoverTTM: List[FinancialDataPoint],
    bookValue: List[FinancialDataPoint],
    cashRatio: List[FinancialDataPoint],
    currentRatio: List[FinancialDataPoint],
    ebitPerShare: List[FinancialDataPoint],
    eps: List[FinancialDataPoint],
    ev: List[FinancialDataPoint],
    evEbitdaTTM: List[FinancialDataPoint],
    evRevenueTTM: List[FinancialDataPoint],
    fcfMargin: List[FinancialDataPoint],
    grossMargin: List[FinancialDataPoint],
    inventoryTurnoverTTM: List[FinancialDataPoint],
    longtermDebtTotalAsset: List[FinancialDataPoint],
    longtermDebtTotalCapital: List[FinancialDataPoint],
    longtermDebtTotalEquity: List[FinancialDataPoint],
    netDebtToTotalCapital: List[FinancialDataPoint],
    netDebtToTotalEquity: List[FinancialDataPoint],
    netMargin: List[FinancialDataPoint],
    operatingMargin: List[FinancialDataPoint],
    payoutRatioTTM: List[FinancialDataPoint],
    pb: List[FinancialDataPoint],
    peTTM: List[FinancialDataPoint],
    pfcfTTM: List[FinancialDataPoint],
    pretaxMargin: List[FinancialDataPoint],
    psTTM: List[FinancialDataPoint],
    ptbv: List[FinancialDataPoint],
    quickRatio: List[FinancialDataPoint],
    receivablesTurnoverTTM: List[FinancialDataPoint],
    roaTTM: List[FinancialDataPoint],
    roeTTM: List[FinancialDataPoint],
    roicTTM: List[FinancialDataPoint],
    rotcTTM: List[FinancialDataPoint],
    salesPerShare: List[FinancialDataPoint],
    sgaToSale: List[FinancialDataPoint],
    tangibleBookValue: List[FinancialDataPoint],
    totalDebtToEquity: List[FinancialDataPoint],
    totalDebtToTotalAsset: List[FinancialDataPoint],
    totalDebtToTotalCapital: List[FinancialDataPoint],
    totalRatio: List[FinancialDataPoint],
)
