package io.greensheep.providers.bvb

import scala.util.matching.Regex

/** Helpers for BVB's ASP.NET WebForms market-segment tabs (Main / AeRO / SMT Intl). */
object BvbSharesForm {

  val RegulatedButton: String = "ctl00$ctl00$body$rightColumnPlaceHolder$MSCtrl$ms1"
  val AeroButton: String = "ctl00$ctl00$body$rightColumnPlaceHolder$MSCtrl$ms3"
  val ScriptManager: String = "ctl00$ctl00$MasterScriptManager"
  val AeroUpdatePanel: String = "ctl00$ctl00$body$rightColumnPlaceHolder$MSCtrl$updsgm"

  private val HiddenInput: Regex =
    """(?is)<input[^>]*type=["']hidden["'][^>]*>""".r
  private val NameAttr: Regex = """(?is)name=["']([^"']+)["']""".r
  private val ValueAttr: Regex = """(?is)value=["']([^"']*)["']""".r

  def hiddenFields(html: String): Map[String, String] =
    HiddenInput
      .findAllIn(html)
      .flatMap { tag =>
        for {
          name <- NameAttr.findFirstMatchIn(tag).map(_.group(1))
          value = ValueAttr.findFirstMatchIn(tag).map(_.group(1)).getOrElse("")
        } yield name -> value
      }
      .toMap

  /** ASP.NET AJAX postback body that switches the Shares page to the AeRO segment. */
  def aeroPostFields(pageHtml: String): Map[String, String] = {
    val fields = hiddenFields(pageHtml)
    fields ++ Map(
      ScriptManager -> s"$AeroUpdatePanel|$AeroButton",
      "__EVENTTARGET" -> AeroButton,
      "__EVENTARGUMENT" -> "",
      "__ASYNCPOST" -> "true"
    )
  }
}
