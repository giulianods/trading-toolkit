package org.greensheep.tradingtk.core
package services

import org.greensheep.tradingtk.core.types.Quote
import zio.{IO, RIO, Task, ZIO}

class FunctionTypes {

  type SymbolLister = Task[Seq[String]]

  type QuoteLister = RIO[String, Seq[Quote]]

}
