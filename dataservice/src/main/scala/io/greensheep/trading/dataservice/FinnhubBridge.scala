package io.greensheep.trading.dataservice

import io.greensheep.trading.core.types.AnnualFinancialData
import zio.Chunk
import io.greensheep.trading.core.types.QuarterlyFinancialData
import java.time.LocalDate

object FinnhubBridge {

    extension (financialDataPoint: io.finnhub.client.types.FinancialDataPoint) {
        def annualKey: Int = {
            financialDataPoint.period.take(4).toInt
        }
    }

    extension (financialDataPoint: io.finnhub.client.types.FinancialDataPoint) {
        def quarterlyKey: (Int, Int) = {
            val date = LocalDate.parse(financialDataPoint.period)
            val year = date.getYear
            val month = date.getMonthValue
            val quarter = (month - 1) / 3 + 1
            (year, quarter)
        }
    }

    def translateAnnualFinancials(data: io.finnhub.client.types.BasicFinancials): Seq[AnnualFinancialData] = {
        val epsMap = data.series.annual.eps.map(eps => eps.annualKey -> eps.v).toMap
        val revenueMap = data.series.annual.salesPerShare.map(revenue => revenue.annualKey -> revenue.v).toMap
        val operatingMarginMap = data.series.annual.operatingMargin.map(operatingMargin => operatingMargin.annualKey -> operatingMargin.v).toMap
        val netIncomeMarginMap = data.series.annual.netMargin.map(netIncomeMargin => netIncomeMargin.annualKey -> netIncomeMargin.v).toMap
        val years = epsMap.keys.toSet ++ revenueMap.keys.toSet
        years.map(year => {
            val eps = epsMap.get(year).map(_.toDouble).getOrElse(0.0)
            val revenue = revenueMap.get(year).map(_.toDouble).getOrElse(0.0)
            val netIncomeMargin = netIncomeMarginMap.get(year).map(_.toDouble).getOrElse(0.0)
            val operatingMargin = operatingMarginMap.get(year).map(_.toDouble).getOrElse(0.0)
            AnnualFinancialData(year, revenue, eps, operatingMargin, netIncomeMargin)
        }).toSeq.sortBy(_.year)
    }

    def translateQuarterlyFinancials(data: io.finnhub.client.types.BasicFinancials): Seq[QuarterlyFinancialData] = {
        val epsMap = data.series.quarterly.eps.map(eps => eps.quarterlyKey -> eps.v).toMap
        val revenueMap = data.series.quarterly.salesPerShare.map(revenue => revenue.quarterlyKey -> revenue.v).toMap
        val operatingMarginMap = data.series.quarterly.operatingMargin.map(operatingMargin => operatingMargin.quarterlyKey -> operatingMargin.v).toMap
        val netIncomeMarginMap = data.series.quarterly.netMargin.map(netIncomeMargin => netIncomeMargin.quarterlyKey -> netIncomeMargin.v).toMap
        val quarters = epsMap.keys.toSet ++ revenueMap.keys.toSet
        quarters.map((year,quarter) => {
            val eps = epsMap.get((year,quarter)).map(_.toDouble).getOrElse(0.0)
            val revenue = revenueMap.get((year,quarter)).map(_.toDouble).getOrElse(0.0)
            val netIncomeMargin = netIncomeMarginMap.get((year,quarter)).map(_.toDouble).getOrElse(0.0)
            val operatingMargin = operatingMarginMap.get((year,quarter)).map(_.toDouble).getOrElse(0.0)
            QuarterlyFinancialData(quarter, year, revenue, eps, operatingMargin, netIncomeMargin)
        }).toSeq.sortBy(x => x.year * 10 + x.quarter)
    }


    
}
