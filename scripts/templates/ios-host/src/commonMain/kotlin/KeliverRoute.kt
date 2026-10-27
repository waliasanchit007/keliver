package @@PACKAGE@@

/**
 * What a Keliver screen shows (adoption item 3, K2; D8: navigation across
 * features goes through the app's own deep links). A path, and only the query
 * keys the app allows: a deep link's other parameters never reach the guest.
 * The same text as the Android host's KeliverRoute ([encode]:
 * `orders/42?tab=items`), which the guest receives in `launchRoute`. Pure
 * Kotlin: its tests run on the JVM (`./gradlew -p host-ios jvmTest`).
 */
public class KeliverRoute(path: String, query: Map<String, String>) {
  /** Its segments joined by `/`, with no empty ones (so no leading or trailing `/`). */
  public val path: String = path.split('/').filter { it.isNotEmpty() }.joinToString("/")
  public val query: Map<String, String> = query.entries.sortedBy { it.key }.associate { it.key to it.value }

  /** `path?key=value&…`, keys sorted; each segment, key and value form-encoded (UTF-8). */
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
     * The route a deep link [uri] names (its host and path, as `example://orders/42`, or
     * `https://example.com/orders/42`), keeping only [allowedQueryKeys] of its query. Null when
     * [uri] is null or not a URI (no scheme, or a space or control character in it). A key that
     * appears twice keeps its first value.
     */
    public fun from(uri: String?, allowedQueryKeys: Set<String>): KeliverRoute? {
      if (uri == null || uri.any { it <= ' ' || it == '\u007f' }) return null
      val scheme = uri.substringBefore(':', "")
      if (scheme.isEmpty() || !scheme.first().isLetter() || scheme.any { !it.isLetterOrDigit() && it !in "+-." }) return null
      var rest = uri.substringAfter(':').substringBefore('#')
      val rawQuery = if ('?' in rest) rest.substringAfter('?') else null
      rest = rest.substringBefore('?')
      // A custom scheme's first segment is its "host" (example://orders/42); a web link's host is not a route.
      val head: String
      if (rest.startsWith("//")) {
        val authority = rest.removePrefix("//").substringBefore('/')
        rest = rest.removePrefix("//").removePrefix(authority)
        head = if (scheme.lowercase() in setOf("http", "https")) "" else authority.substringAfter('@').substringBefore(':')
      } else {
        head = ""
      }
      val path = (head + "/" + rest).split('/').filter { it.isNotEmpty() }.joinToString("/") { percentDecode(it, plusIsSpace = false) }
      val query = linkedMapOf<String, String>()
      rawQuery?.split('&')?.forEach { pair ->
        if (pair.isEmpty()) return@forEach
        val key = percentDecode(pair.substringBefore('='), plusIsSpace = true)
        val value = if ('=' in pair) percentDecode(pair.substringAfter('='), plusIsSpace = true) else ""
        if (key in allowedQueryKeys && key !in query) query[key] = value
      }
      return KeliverRoute(path, query)
    }

    /** Reads [encode]'s text back (the guest does the same with its own copy). */
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
        val c = (b.toInt() and 0xff)
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
        when {
          hi >= 0 && lo >= 0 -> { out.add(((hi shl 4) or lo).toByte()); i += 3 }
          c == '+' && plusIsSpace -> { out.add(' '.code.toByte()); i++ }
          else -> { c.toString().encodeToByteArray().forEach { out.add(it) }; i++ }
        }
      }
      return out.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"
  }
}
