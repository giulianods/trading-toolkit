package io.greensheep.trading.core.screens.utils

import io.greensheep.trading.core.types.DailyAggregate

object VCPDebug {

  final case class Failure(reason: String)
  final case class DebugResult(
                                symbol: String,
                                hit: Option[VCPHit],
                                reasons: List[Failure],
                                candidateContractions: List[Contraction] = Nil
                              )

  def detectWithReasons(
      symbol: String,
      bars: Seq[DailyAggregate],
      p: VCPParams,
      lookRight: Int = 10,
      uptrendConfirmed: Boolean = false
  ): DebugResult = {
    VCPDetector.evaluate(bars, p, lookRight, uptrendConfirmed) match {
      case Right(hit) => DebugResult(symbol, Some(hit), Nil, hit.contractions)
      case Left((reason, contractions)) => DebugResult(symbol, None, List(Failure(reason)), contractions)
    }
  }
}
