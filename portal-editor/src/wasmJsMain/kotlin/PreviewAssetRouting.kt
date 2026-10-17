/*
 * K5: canvas images from the app's own hosts go through the relay.
 *
 * The canvas loads AsyncImage URLs with the browser's fetch(); an image host
 * that sends no CORS headers cannot be read from the editor's page. The relay
 * serves such images at /preview-asset for the hosts keliver.portal.json lists
 * in `previewAssetHosts` (GET /preview-assets says which). A URL on one of
 * those hosts is rewritten to the relay; every other URL loads as before.
 */
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object PreviewAssetRouting {
  private var hosts: Set<String> = emptySet()
  private val loaded = CompletableDeferred<Unit>()

  /** The relay's list (or none: an older relay, playground mode, an error). */
  fun loaded(hosts: Set<String>) {
    this.hosts = hosts
    loaded.complete(Unit)
  }

  /** Images requested before the list arrives wait for it, briefly. */
  suspend fun awaitLoaded() {
    withTimeoutOrNull(3_000) { loaded.await() }
  }

  fun route(url: String): String = previewAssetUrl(url, hosts, SERVER) ?: url
}

/** The host list from `GET /preview-assets`; empty when absent or malformed. */
internal fun parsePreviewAssetHosts(json: String): Set<String> = runCatching {
  Json.parseToJsonElement(json).jsonObject["hosts"]!!.jsonArray
    .map { it.jsonPrimitive.content.lowercase() }.toSet()
}.getOrDefault(emptySet())

/**
 * The relay URL serving [url], or null when [url] loads directly: not
 * http(s), carries user-info, or its host is not exactly one of [hosts].
 */
internal fun previewAssetUrl(url: String, hosts: Set<String>, server: String): String? {
  if (hosts.isEmpty()) return null
  val m = Regex("^(?i:https?)://([^/?#]*)").find(url) ?: return null
  val authority = m.groupValues[1]
  if ('@' in authority || authority.startsWith("[")) return null
  val host = authority.substringBefore(':').lowercase()
  if (host !in hosts) return null
  return "$server/preview-asset?url=${encodeUriComponent(url)}"
}

/** encodeURIComponent: everything but A–Z a–z 0–9 - _ . ! ~ * ' ( ) as UTF-8 %XX. */
internal fun encodeUriComponent(s: String): String {
  val sb = StringBuilder()
  for (b in s.encodeToByteArray()) {
    val c = (b.toInt() and 0xff)
    val ch = c.toChar()
    if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-_.!~*'()")) sb.append(ch)
    else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
  }
  return sb.toString()
}
