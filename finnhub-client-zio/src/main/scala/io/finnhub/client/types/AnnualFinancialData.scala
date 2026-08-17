package io.finnhub.client.types

import zio.json.*

object AnnualFinancialData {
    implicit val decoder: JsonDecoder[AnnualFinancialData] = DeriveJsonDecoder.gen[AnnualFinancialData]
    implicit val encoder: JsonEncoder[AnnualFinancialData] = DeriveJsonEncoder.gen[AnnualFinancialData]
}

final case class AnnualFinancialData(
    bookValue: List[FinancialDataPoint],
    cashRatio: List[FinancialDataPoint],
    currentRatio: List[FinancialDataPoint],
    ebitPerShare: List[FinancialDataPoint],
    eps: List[FinancialDataPoint],
    ev: List[FinancialDataPoint],
    evEbitda: List[FinancialDataPoint],
    evRevenue: List[FinancialDataPoint],
    fcfMargin: List[FinancialDataPoint],
    grossMargin: List[FinancialDataPoint],
    inventoryTurnover: List[FinancialDataPoint],
    longtermDebtTotalAsset: List[FinancialDataPoint],
    longtermDebtTotalCapital: List[FinancialDataPoint],
    longtermDebtTotalEquity: List[FinancialDataPoint],
    netDebtToTotalCapital: List[FinancialDataPoint],
    netDebtToTotalEquity: List[FinancialDataPoint],
    netMargin: List[FinancialDataPoint],
    operatingMargin: List[FinancialDataPoint],
    payoutRatio: List[FinancialDataPoint],
    pb: List[FinancialDataPoint],
    pe: List[FinancialDataPoint],
    pfcf: List[FinancialDataPoint],
    pretaxMargin: List[FinancialDataPoint],
    ps: List[FinancialDataPoint],
    ptbv: List[FinancialDataPoint],
    quickRatio: List[FinancialDataPoint],
    receivablesTurnover: List[FinancialDataPoint],
    roa: List[FinancialDataPoint],
    roe: List[FinancialDataPoint],
    roic: List[FinancialDataPoint],
    rotc: List[FinancialDataPoint],
    salesPerShare: List[FinancialDataPoint],
    sgaToSale: List[FinancialDataPoint],
    tangibleBookValue: List[FinancialDataPoint],
    totalDebtToEquity: List[FinancialDataPoint],
    totalDebtToTotalAsset: List[FinancialDataPoint],
    totalDebtToTotalCapital: List[FinancialDataPoint],
    totalRatio: List[FinancialDataPoint],  
)
