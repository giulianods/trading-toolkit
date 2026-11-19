package io.greensheep.trading.service.services

import io.greensheep.trading.core.types.StockDescription
import zio.test.*
import java.time.LocalDate

object CompanyDescriptionSpec extends ZIOSpecDefault {
  private val base = StockDescription("AAPL", "Apple from Alpaca", LocalDate.of(1980, 12, 12), primaryExchange = "NASDAQ")
  private val reference = base.copy(companyName = "Other source name", primaryExchange = "XNAS",
    description = " Apple makes consumer electronics. ", sicCode = "3571", sicDescription = "Electronic Computers")

  override def spec = suite("Company metadata enrichment")(
    test("reuses narrative and industry metadata while preserving the target identity") {
      val result = CompanyDescriptionService.enrich(base, Some(reference))
      assertTrue(result.description == reference.description.trim, result.sicDescription == reference.sicDescription,
        result.symbol == base.symbol, result.companyName == base.companyName,
        result.primaryExchange == base.primaryExchange, result.ipoDate == base.ipoDate)
    },
    test("uses the selected source instead of an old enrichment and never substitutes a different ticker") {
      val existing = base.copy(description = "Existing narrative", sicCode = "123", sicDescription = "Existing industry")
      assertTrue(CompanyDescriptionService.enrich(existing, Some(reference)).description == reference.description.trim,
        CompanyDescriptionService.enrich(base, Some(reference.copy(symbol = "MSFT"))) == base,
        CompanyDescriptionService.enrich(base, None) == base)
    },
    test("Alpaca quote refreshes update listing fields without erasing enriched metadata") {
      val existing = reference.copy(totalEmployees = 100, marketCap = 123456)
      val updated = AlpacaQuotesServices.assetDescription(AlpacaClient.Asset("AAPL", "New listing name", "NASDAQ"), Some(existing))
      assertTrue(updated == existing.copy(companyName = "New listing name", primaryExchange = "NASDAQ"))
    }
  )
}
