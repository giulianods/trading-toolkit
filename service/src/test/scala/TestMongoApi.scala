import io.greensheep.trading.dataservice.MongoDbDataRepo
import zio.test.*
import zio.test.Assertion.*
import zio.*

object TestMongoApi extends ZIOSpecDefault {


  def spec: Spec[Any, Throwable] = suite("MongoDbDataRepoSpec")(
    test("getTickerDetails should retrieve all stocks from MongoDB") {
      for {
        // Instantiate the repository.
        repo <- ZIO.succeed(MongoDbDataRepo.build())
        // Call the method under test.
        stocks <- repo.getTickerDetails
        _ = println(s"Got ${stocks.size} stocks")
      } yield assert(stocks)(isNonEmpty)
    }
  )
}