package io.greensheep.providers.bvb

import zio.*
import zio.http.{Client, DnsResolver, ZClient}
import zio.http.netty.NettyConfig
import zio.test.*

object BvbAeroLiveSpec extends ZIOSpecDefault {
  private val http =
    (ZLayer.succeed(ZClient.Config.default.noIdleTimeout) ++
      ZLayer.succeed(NettyConfig.default) ++
      DnsResolver.default) >>> Client.live

  override def spec = suite("BVB AeRO live")(
    test("listMainShares includes HUNT from AeRO") {
      for {
        client <- ZIO.service[BvbClient]
        shares <- client.listMainShares
      } yield assertTrue(
        shares.size > 100,
        shares.exists(_.ticker == "HUNT"),
        shares.exists(_.ticker == "TLV")
      )
    }
  ).provide(http, BvbClient.live) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds) @@ TestAspect.ignore
}
