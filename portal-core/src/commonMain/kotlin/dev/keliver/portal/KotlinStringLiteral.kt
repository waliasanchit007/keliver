package dev.keliver.portal

// Kotlin string literals in the portal's .kt round-trip. A document holds a
// string's VALUE; the .kt file holds a LITERAL. Ingest decodes the escapes and
// export encodes them, so `"a\nb"` stays a newline through an edit, and a `$`
// typed in the editor can never become a string template. Before this, ingest
// decoded only `\"` and `\\`: the canvas showed a literal `\n`, and editing that
// text wrote `\\n` back — the device then showed a backslash and an `n`.

/** [value] as a Kotlin string literal, quotes included. */
public fun kotlinStringLiteral(value: String): String = buildString(value.length + 2) {
  append('"')
  for ((i, c) in value.withIndex()) {
    when (c) {
      '\\' -> append("\\\\")
      '"' -> append("\\\"")
      '$' -> append("\\$")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      '\b' -> append("\\b")
      else -> if (needsUnicodeEscape(value, i)) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
    }
  }
  append('"')
}

/**
 * Control characters (C0, DEL, C1), the invisible line/paragraph separators and BOM,
 * and a lone surrogate (which UTF-8 cannot encode) are written as `\uXXXX`.
 */
private fun needsUnicodeEscape(s: String, i: Int): Boolean {
  val c = s[i]
  return when {
    c < ' ' || c in '\u007F'..'\u009F' || c == '\u2028' || c == '\u2029' || c == '\uFEFF' -> true
    c.isHighSurrogate() -> i + 1 >= s.length || !s[i + 1].isLowSurrogate()
    c.isLowSurrogate() -> i == 0 || !s[i - 1].isHighSurrogate()
    else -> false
  }
}

/**
 * The value of a single plain Kotlin string literal, or null when [text] is not
 * one: a template (`$name`, `${…}`), a raw string (`"""…"""`), a concatenation
 * (`"a" + "b"`), or a malformed escape. Null keeps the expression out of the
 * grammar, so it is preserved as code instead of being misread as text.
 */
public fun parseKotlinStringLiteral(text: String): String? {
  if (text.length < 2 || text[0] != '"' || text[text.length - 1] != '"') return null
  if (text.startsWith("\"\"\"")) return null
  val end = text.length - 1
  val out = StringBuilder(end)
  var i = 1
  while (i < end) {
    val c = text[i]
    when {
      c == '\\' -> {
        if (i + 1 >= end) return null
        val decoded = when (text[i + 1]) {
          'n' -> '\n'
          't' -> '\t'
          'r' -> '\r'
          'b' -> '\b'
          '\\' -> '\\'
          '"' -> '"'
          '\'' -> '\''
          '$' -> '$'
          'u' -> {
            if (i + 6 > end) return null
            val hex = text.substring(i + 2, i + 6)
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            out.append(hex.toInt(16).toChar())
            i += 6
            continue
          }
          else -> return null
        }
        out.append(decoded)
        i += 2
      }
      c == '$' -> {
        // `${'$'}` is the idiomatic constant dollar: a literal `$`, not a template.
        if (text.startsWith("\${'$'}", i) && i + 6 <= end) {
          out.append('$')
          i += 6
          continue
        }
        val next = if (i + 1 < end) text[i + 1] else null
        // A surrogate pair may start an identifier outside the BMP: refuse, conservatively.
        if (next != null && (next == '{' || next == '_' || next == '`' || next.isLetter() || next.isHighSurrogate())) return null
        out.append(c)
        i++
      }
      c == '"' || c == '\n' || c == '\r' -> return null
      else -> {
        out.append(c)
        i++
      }
    }
  }
  return out.toString()
}
