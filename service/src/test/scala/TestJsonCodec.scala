import io.greensheep.trading.dataservice.{MongoDbDataRepo, StockUniverse}
import zio.test.*
import zio.test.Assertion.*
import zio.*
import zio.json.*


object TestJsonCodec extends ZIOSpecDefault{

  def spec: Spec[Any, Throwable] = suite("json deserialization spec")(
    test("stock universe with default values") {
      val stockUniverseJson: String =
        """
        {
          "_id": "universe-1",
          "name": "S&P 500",
          "dataSource": "YahooFinance",
          "lastUpdateQuotes": "2024-06-01",
          "timeZone": "America/New_York",
          "symbols": ["AAPL", "MSFT", "GOOGL"]
        }
        """
      stockUniverseJson.fromJson[StockUniverse] match {
        case Left(error) =>
          assertTrue(s"Deserialization failed: $error".isEmpty)
        case Right(stockUniverse) =>
          assertTrue(
            stockUniverse._id == "universe-1" &&
              stockUniverse.name == "S&P 500" &&
              stockUniverse.dataSource == "YahooFinance" &&
              stockUniverse.lastUpdateQuotes.toString == "2024-06-01" &&
              stockUniverse.timeZone == "America/New_York" &&
              stockUniverse.symbols == Seq("AAPL", "MSFT", "GOOGL") &&
              stockUniverse.lastUpdateSplits.toString == "1970-01-01"
          )
      }
    }
  )

}
