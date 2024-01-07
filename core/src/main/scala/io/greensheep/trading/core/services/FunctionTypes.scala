
package io.greensheep.trading.core.services

import io.greensheep.trading.core.types.Quote
import zio.{IO, RIO, Task, ZIO}

class FunctionTypes {

  type SymbolLister = Task[Seq[String]]

  type QuoteLister = RIO[String, Seq[Quote]]

}
