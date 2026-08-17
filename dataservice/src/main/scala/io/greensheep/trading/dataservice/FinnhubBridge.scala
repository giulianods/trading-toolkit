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
        val epsMap = data.series.annual.eps.flatMap(eps => eps.v.map(eps.annualKey -> _)).toMap
        val revenueMap = data.series.annual.salesPerShare.flatMap(revenue => revenue.v.map(revenue.annualKey -> _)).toMap
        val operatingMarginMap = data.series.annual.operatingMargin.flatMap(value => value.v.map(value.annualKey -> _)).toMap
        val netIncomeMarginMap = data.series.annual.netMargin.flatMap(value => value.v.map(value.annualKey -> _)).toMap
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
        val epsMap = data.series.quarterly.eps.flatMap(eps => eps.v.map(eps.quarterlyKey -> _)).toMap
        val revenueMap = data.series.quarterly.salesPerShare.flatMap(revenue => revenue.v.map(revenue.quarterlyKey -> _)).toMap
        val operatingMarginMap = data.series.quarterly.operatingMargin.flatMap(value => value.v.map(value.quarterlyKey -> _)).toMap
        val netIncomeMarginMap = data.series.quarterly.netMargin.flatMap(value => value.v.map(value.quarterlyKey -> _)).toMap
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
