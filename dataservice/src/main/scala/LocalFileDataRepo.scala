package org.greensheep.trading.dataservice

import org.apache.commons.csv.{CSVFormat, CSVPrinter}
import org.greensheep.trading.core.services.{DataRepo, DataSource}
import org.greensheep.trading.core.types.{Quote, Stock}
import zio.{IO, Task, ULayer, ZIO, ZLayer}

import java.io.FileWriter
import java.nio.file.{Path, Paths}
import scala.util.{Try, Using}

object LocalFileDataRepo{
  val layer: ULayer[DataRepo] = ZLayer.succeed(build())

  def build(): LocalFileDataRepo = {
    new LocalFileDataRepo(Paths.get("C:\\data"))
  }
}

class LocalFileDataRepo(directory: Path) extends DataRepo {

  override def saveQuotes(symbol: String, quotes: Seq[Quote]): Task[Unit] = {
    def quoteSaverCsv(): Try[Unit] = {
      val file = directory.resolve(symbol + ".csv")
      val csvFormat = CSVFormat.DEFAULT.builder()
        .setHeader("day", "open", "high", "low", "close", "volume")
        .build()
      Using.Manager { use =>
        val writer = use(new FileWriter(file.toFile))
        val csvPrinter = use(new CSVPrinter(writer, csvFormat))
        quotes.foreach(q => csvPrinter.printRecord(q.day, q.o, q.h, q.l, q.c, q.v))
      }
    }
    ZIO.fromTry(quoteSaverCsv())
  }

  override def getSymbols: Task[Seq[String]] = ???

  override def getStock(symbol: String): IO[Throwable, Stock] = ???

  override def getStocks(): Map[String, Stock] = ???

  override def getQuotes(symbol: String): Task[Seq[Quote]] = ???
}
