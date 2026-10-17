package dev.keliver.portal

import kotlin.math.abs
import kotlin.math.floor

/**
 * A Double as a Kotlin literal that re-reads exactly and never uses exponent
 * notation (`1.0E-4` would not re-ingest as `1.0E-4.dp`): `16.0`, `0.0001`,
 * `16.5`. Platform-independent (JS prints `16` for 16.0 and `1e-7` for 1e-7).
 * Non-finite values print as `0.0`.
 */
public fun kotlinDoubleLiteral(v: Double): String {
  if (!v.isFinite()) return "0.0"
  if (v == floor(v) && abs(v) < 1e15) return "${v.toLong()}.0"
  val s = v.toString()
  val m = Regex("^(-?)(\\d+)(?:\\.(\\d+))?[eE]([+-]?\\d+)$").find(s) ?: return if ('.' in s) s else "$s.0"
  val (sign, intPart, fracPart, expText) = m.destructured
  val digits = (intPart + fracPart).trimStart('0').ifEmpty { "0" }
  // Position of the decimal point within `intPart + fracPart`, then shifted.
  val leadingZeros = (intPart + fracPart).length - (intPart + fracPart).trimStart('0').length
  val point = intPart.length + expText.toInt() - leadingZeros
  val body = when {
    point <= 0 -> "0." + "0".repeat(-point) + digits
    point >= digits.length -> digits + "0".repeat(point - digits.length) + ".0"
    else -> digits.substring(0, point) + "." + digits.substring(point)
  }
  return sign + body.trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }
}

/** A Dp value as `N.dp` (whole) or `N.M.dp` — the `.dp` spelling; negatives clamp to 0. */
public fun kotlinDpLiteral(v: Double): String {
  val c = if (v.isFinite()) v.coerceAtLeast(0.0) else 0.0
  return if (c == floor(c) && c < 1e15) "${c.toLong()}.dp" else "${kotlinDoubleLiteral(c)}.dp"
}
