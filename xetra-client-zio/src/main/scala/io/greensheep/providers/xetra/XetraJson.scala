package io.greensheep.providers.xetra

object XetraJson {

  /** Börse Frankfurt occasionally emits non-standard JSON numbers. */
  def sanitize(body: String): String = {
    val out = new StringBuilder(body.length)
    var i = 0
    var inString = false
    var lastNonWs = '\u0000'
    while (i < body.length) {
      val c = body.charAt(i)
      if (inString) {
        out.append(c)
        if (c == '\\' && i + 1 < body.length) {
          out.append(body.charAt(i + 1))
          i += 2
        } else {
          if (c == '"') inString = false
          i += 1
        }
      } else if (c == '"') {
        inString = true
        lastNonWs = c
        out.append(c)
        i += 1
      } else if (c == 'N' && body.startsWith("NaN", i)) {
        out.append("null")
        lastNonWs = 'l'
        i += 3
      } else if (c == 'I' && body.startsWith("Infinity", i)) {
        out.append("null")
        lastNonWs = 'l'
        i += 8
      } else if (c == '-' && i + 1 < body.length && body.startsWith("Infinity", i + 1)) {
        out.append("null")
        lastNonWs = 'l'
        i += 9
      } else if (c == '.' && isNumberBoundary(lastNonWs)) {
        if (i + 1 < body.length && body.charAt(i + 1).isDigit) out.append("0.")
        else out.append("null")
        lastNonWs = '0'
        i += 1
      } else {
        out.append(c)
        if (!c.isWhitespace) lastNonWs = c
        i += 1
      }
    }
    out.toString()
  }

  private def isNumberBoundary(previous: Char): Boolean =
    previous == ':' || previous == '[' || previous == ',' || previous == '\u0000'
}
