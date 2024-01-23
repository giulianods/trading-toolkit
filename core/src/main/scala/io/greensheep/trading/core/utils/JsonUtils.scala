package io.greensheep.trading.core.utils
import zio.json.*
import zio.json.ast.Json

import scala.collection.mutable
import scala.language.implicitConversions
object JsonUtils {

  implicit def toMap(json: Json): Map[String, Object] = {
    json.asObject match {
      case Some(obj) =>
        val map = mutable.Map[String, Object]()
        obj.fields.foreach { case (key, value) =>
          val objValue: Object = value match {
            case Json.Null => null
            case Json.Bool(b) => b: java.lang.Boolean
            case Json.Str(s) => s
            case Json.Num(n) => n: java.math.BigDecimal
            case Json.Arr(a) => a.map(toMap).toList
            case Json.Obj(o) => toMap(Json.Obj(o))
          }
          map(key) = objValue
        }
        map.toMap
      case None => Map.empty
    }
  }
}
