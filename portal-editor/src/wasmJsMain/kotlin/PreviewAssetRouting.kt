/*
 * K5: canvas images from the app's own hosts go through the relay.
 *
 * The canvas loads AsyncImage URLs with the browser's fetch(); an image host
 * that sends no CORS headers cannot be read from the editor's page. The relay
 * serves such images at /preview-asset for the hosts keliver.portal.json lists
 * in `previewAssetHosts` (GET /preview-assets says which). A URL on one of
 * those hosts, on its scheme's default port, is rewritten to the relay; every
 * other URL loads as before.
 *
 * At most [RELAY_IMAGE_FETCHES] of the rewritten fetches run at once. The
 * browser opens about six HTTP/1.1 connections to one origin and the editor
 * already holds one for its event stream; images waiting on a slow host must
 * leave the rest for /ops, /doc and write-back. Direct URLs are not limited.
 */
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object PreviewAssetRouting : PreviewAssetHostGate() {
  fun route(url: String): String = route(url, SERVER)
}

/** Relay image fetches the editor runs at once (see the file comment). */
internal const val RELAY_IMAGE_FETCHES = 2

/** The relay's host list, the one wait for it, and the cap on relay fetches. */
internal open class PreviewAssetHostGate(
  private val waitMillis: Long = 3_000,
  relayFetches: Int = RELAY_IMAGE_FETCHES,
) {
  private var hosts: Set<String> = emptySet()
  private val loaded = CompletableDeferred<Unit>()
  private val relayPermits = Semaphore(relayFetches)

  /** The relay's list (or none: an older relay, playground mode, an error). */
  fun loaded(hosts: Set<String>) {
    this.hosts = hosts
    loaded.complete(Unit)
  }

  /**
   * Images requested before the list arrives wait for it, briefly — once. If
   * nothing delivers it within [waitMillis] (neither the workspace load nor
   * playground mode ran), the wait ends for good with no hosts, so later
   * images do not each wait again. A list that arrives afterwards still
   * routes the images requested after it.
   */
  suspend fun awaitLoaded() {
    if (withTimeoutOrNull(waitMillis) { loaded.await() } == null) loaded.complete(Unit)
  }

  fun route(url: String, server: String): String = previewAssetUrl(url, hosts, server) ?: url

  /**
   * Load [url] with [fetch], given the URL to request: the relay's when its
   * host is listed, under the cap on concurrent relay fetches (held until
   * [fetch] returns, body read included), else [url] itself, uncapped.
   */
  suspend fun <T> load(url: String, server: String, fetch: suspend (target: String) -> T): T {
    awaitLoaded()
    val target = route(url, server)
    return if (target == url) fetch(url) else relayPermits.withPermit { fetch(target) }
  }
}

/** The host list from `GET /preview-assets`; empty when absent or malformed. */
internal fun parsePreviewAssetHosts(json: String): Set<String> = runCatching {
  Json.parseToJsonElement(json).jsonObject["hosts"]!!.jsonArray
    .map { it.jsonPrimitive.content.lowercase() }.toSet()
}.getOrDefault(emptySet())

/**
 * The relay URL serving [url], or null when [url] loads directly: not
 * http(s), carries user-info, names a port other than its scheme's default
 * (the relay refuses those), or its host is not exactly one of [hosts].
 */
internal fun previewAssetUrl(url: String, hosts: Set<String>, server: String): String? {
  if (hosts.isEmpty()) return null
  val m = Regex("^((?i:https?))://([^/?#]*)").find(url) ?: return null
  val scheme = m.groupValues[1].lowercase()
  val authority = m.groupValues[2]
  if ('@' in authority || authority.startsWith("[")) return null
  val host = authority.substringBefore(':').lowercase()
  if (host !in hosts) return null
  val port = authority.substringAfter(':', "")
  if (port.isNotEmpty() && port != if (scheme == "https") "443" else "80") return null
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
