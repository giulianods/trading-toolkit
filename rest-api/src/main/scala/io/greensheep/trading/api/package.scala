package io.greensheep.trading

import zio.http.Response

package object api {

  def throwableToResponse(th: Throwable) = {
    th.printStackTrace()
    Response.internalServerError(th.getMessage)
  }

}
