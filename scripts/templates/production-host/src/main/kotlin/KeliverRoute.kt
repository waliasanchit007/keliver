package @@PACKAGE@@

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a Keliver screen shows (adoption item 3, K1; D8: navigation across
 * features goes through the app's own deep links). A path, and only the query
 * keys the app allows: a deep link's other parameters never reach the guest.
 *
 * The guest receives it as text ([encode]: `orders/42?tab=items`) in
 * `launchRoute`, and decides what to show; a guest scaffolded before K1 is
 * launched without one.
 */
class KeliverRoute(path: String, query: Map<String, String> = emptyMap()) {
  /** Its segments joined by `/`, with no empty ones (so no leading or trailing `/`). */
  val path: String = path.split('/').filter { it.isNotEmpty() }.joinToString("/")
  val query: Map<String, String> = query.toSortedMap()

  /** `path?key=value&…`, keys sorted; each segment, key and value form-encoded (UTF-8). */
  fun encode(): String {
    val p = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { enc(it) }
    if (query.isEmpty()) return p
    return p + "?" + query.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
  }

  override fun equals(other: Any?) = other is KeliverRoute && other.path == path && other.query == query
  override fun hashCode() = 31 * path.hashCode() + query.hashCode()
  override fun toString() = encode()

  companion object {
    /**
     * The route a deep link [uri] names (its host and path, as `example://orders/42` or
     * `https://example.com/orders/42`), keeping only [allowedQueryKeys] of its query. Null when
     * [uri] is null or not a URI. A key that appears twice keeps its first value.
     */
    fun from(uri: String?, allowedQueryKeys: Set<String>): KeliverRoute? {
      val u = runCatching { URI(uri ?: return null) }.getOrNull() ?: return null
      // A custom scheme's first segment is its "host" (example://orders/42); a web link's host is not a route.
      val head = if (u.scheme != null && u.scheme !in setOf("http", "https")) u.host.orEmpty() else ""
      // getPath() is already percent-decoded ('+' stays itself in a path).
      val path = (head + "/" + u.path.orEmpty()).split('/').filter { it.isNotEmpty() }.joinToString("/")
      val query = linkedMapOf<String, String>()
      u.rawQuery?.split('&')?.forEach { pair ->
        if (pair.isEmpty()) return@forEach
        val key = dec(pair.substringBefore('='))
        val value = if ('=' in pair) dec(pair.substringAfter('=')) else ""
        if (key in allowedQueryKeys && key !in query) query[key] = value
      }
      return KeliverRoute(path, query)
    }

    /** Reads [encode]'s text back (the guest does the same with its own copy). */
    fun decode(text: String): KeliverRoute {
      val path = text.substringBefore('?')
      val query = linkedMapOf<String, String>()
      if ('?' in text) {
        text.substringAfter('?').split('&').filter { it.isNotEmpty() }.forEach { pair ->
          val key = dec(pair.substringBefore('='))
          if (key !in query) query[key] = dec(pair.substringAfter('=', ""))
        }
      }
      return KeliverRoute(path.split('/').joinToString("/") { dec(it) }, query)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
  }
}
