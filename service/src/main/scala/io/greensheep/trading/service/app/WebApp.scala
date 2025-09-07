package io.greensheep.trading.service.app

import zio._
import zio.http._
import zio.Chunk

object WebApp {

  private def resourceBytes(path: String): Task[Chunk[Byte]] =
    ZIO
      .fromOption(Option(getClass.getResourceAsStream(path)))
      .orElseFail(new Exception(s"resource $path not found"))
      .flatMap { is =>
        ZIO.attempt {
          try Chunk.fromArray(is.readAllBytes())
          finally is.close()
        }
      }

  private def responseFor(path: String): Task[Response] =
    resourceBytes(path).map(bytes => Response(body = Body.fromChunk(bytes)))

  private val notFound = new Exception("not found")

  val app: HttpApp[Any] =
    Handler
      .fromFunctionZIO[Request] { req =>
        val requested = req.url.path.toString match {
          case "" | "/" => "/dist/index.html"
          case other      => s"/dist$other"
        }
        responseFor(requested).catchAll { _ =>
          if (requested.contains(".")) ZIO.fail(notFound)
          else responseFor("/dist/index.html")
        }
      }
      .mapError {
        case `notFound` => Response.status(Status.NotFound)
        case _          => Response.status(Status.InternalServerError)
      }
      .toHttpApp
}
