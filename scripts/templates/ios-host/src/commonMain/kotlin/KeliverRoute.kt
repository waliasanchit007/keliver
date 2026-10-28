package @@PACKAGE@@

/**
 * What a Keliver screen shows (adoption item 3, host kit; D8: navigation across
 * features goes through the app's own deep links). A path, and only the query
 * keys the app allows: a deep link's other parameters never reach the guest.
 * The guest receives [encode]'s text (`orders/42?tab=items`) in `launchRoute`.
 *
 * Pure Kotlin, and the same file in the Android and iOS hosts, so both send the
 * same text for the same link; KeliverRouteTest (also shared) pins it.
 */
public class KeliverRoute(path: String, query: Map<String, String> = emptyMap()) {
  /** Its segments joined by `/`, with no empty ones (so no leading or trailing `/`). */
  public val path: String = path.split('/').filter { it.isNotEmpty() }.joinToString("/")
  public val query: Map<String, String> = query.entries.sortedBy { it.key }.associate { it.key to it.value }

  /** `path?key=value&…`, keys sorted; each segment, key and value form-encoded (UTF-8, as java.net.URLEncoder). */
  public fun encode(): String {
    val p = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { formEncode(it) }
    if (query.isEmpty()) return p
    return p + "?" + query.entries.joinToString("&") { (k, v) -> "${formEncode(k)}=${formEncode(v)}" }
  }

  override fun equals(other: Any?): Boolean = other is KeliverRoute && other.path == path && other.query == query
  override fun hashCode(): Int = 31 * path.hashCode() + query.hashCode()
  override fun toString(): String = encode()

  public companion object {
    /**
     * The route a deep link [uri] names, keeping only [allowedQueryKeys] of its query:
     * - `example://orders/42`: a custom scheme's authority is the first segment (`orders/42`);
     * - `https://example.com/orders/42`: a web link's host is not part of it (`orders/42`);
     * - `example:orders/42`: no authority, the path as it is.
     * Null when [uri] is null, has no scheme, or holds a space or a control character. Segments
     * are percent-decoded (an encoded `/` becomes a separator: a route is a path, not a list); a
     * query key that appears twice keeps its first value.
     */
    public fun from(uri: String?, allowedQueryKeys: Set<String>): KeliverRoute? {
      if (uri == null || uri.any { it <= ' ' || it == '\u007f' }) return null
      val scheme = uri.substringBefore(':', "").lowercase()
      if (scheme.isEmpty() || !scheme.first().isLetter() || scheme.any { !it.isLetterOrDigit() && it !in "+-." }) return null
      var rest = uri.substringAfter(':').substringBefore('#')
      val rawQuery = if ('?' in rest) rest.substringAfter('?') else null
      rest = rest.substringBefore('?')
      var head = ""
      if (rest.startsWith("//")) {
        val authority = rest.removePrefix("//").substringBefore('/')
        rest = rest.removePrefix("//").removePrefix(authority)
        if (scheme != "http" && scheme != "https") head = authority.substringAfter('@').substringBefore(':')
      }
      val path = (listOf(head) + rest.split('/')).filter { it.isNotEmpty() }.joinToString("/") { percentDecode(it, plusIsSpace = false) }
      val query = linkedMapOf<String, String>()
      rawQuery?.split('&')?.forEach { pair ->
        if (pair.isEmpty()) return@forEach
        val key = percentDecode(pair.substringBefore('='), plusIsSpace = true)
        val value = if ('=' in pair) percentDecode(pair.substringAfter('='), plusIsSpace = true) else ""
        if (key in allowedQueryKeys && key !in query) query[key] = value
      }
      return KeliverRoute(path, query)
    }

    /** Reads [encode]'s text back (a guest does the same with its own copy). */
    public fun decode(text: String): KeliverRoute {
      val query = linkedMapOf<String, String>()
      if ('?' in text) {
        text.substringAfter('?').split('&').filter { it.isNotEmpty() }.forEach { pair ->
          val key = percentDecode(pair.substringBefore('='), plusIsSpace = true)
          if (key !in query) query[key] = percentDecode(pair.substringAfter('=', ""), plusIsSpace = true)
        }
      }
      return KeliverRoute(text.substringBefore('?').split('/').joinToString("/") { percentDecode(it, plusIsSpace = true) }, query)
    }

    /** As java.net.URLEncoder (UTF-8): letters, digits and `.-*_` kept, a space as `+`, the rest `%XX`. */
    internal fun formEncode(s: String): String = buildString {
      for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xff
        when {
          c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c.toChar() in ".-*_" -> append(c.toChar())
          c == ' '.code -> append('+')
          else -> append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
        }
      }
    }

    /** `%XX` as UTF-8 bytes (and `+` as a space when [plusIsSpace]); a malformed escape stays as it is. */
    internal fun percentDecode(s: String, plusIsSpace: Boolean): String {
      val out = ArrayList<Byte>(s.length)
      var i = 0
      while (i < s.length) {
        val c = s[i]
        val hi = if (c == '%' && i + 2 < s.length) HEX.indexOf(s[i + 1].uppercaseChar()) else -1
        val lo = if (hi >= 0) HEX.indexOf(s[i + 2].uppercaseChar()) else -1
        if (hi >= 0 && lo >= 0) {
          out.add(((hi shl 4) or lo).toByte()); i += 3
        } else if (c == '+' && plusIsSpace) {
          out.add(' '.code.toByte()); i++
        } else {
          // A run of literal characters, encoded together so a surrogate pair stays one character.
          var end = i + 1
          while (end < s.length && s[end] != '%' && !(plusIsSpace && s[end] == '+')) end++
          s.substring(i, end).encodeToByteArray().forEach { out.add(it) }
          i = end
        }
      }
      return out.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"
  }
}
