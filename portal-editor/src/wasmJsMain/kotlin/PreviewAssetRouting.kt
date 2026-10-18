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
 * Rewritten fetches are limited twice: at most [RELAY_FETCHES_PER_HOST] per
 * image host, and at most [RELAY_IMAGE_FETCHES] in all. The browser opens
 * about six HTTP/1.1 connections to one origin and the editor already holds
 * one for its event stream; images waiting on a slow host must leave at
 * least two for /ops, /doc and write-back. The per-host limit is taken first,
 * so the images queued behind one hanging host wait without holding any of
 * the shared permits: they cannot starve the images of the other hosts.
 * Direct URLs are not limited.
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

/** Relay image fetches the editor runs at once, all hosts together (see the file comment). */
internal const val RELAY_IMAGE_FETCHES = 3

/** Relay image fetches the editor runs at once for any one image host. */
internal const val RELAY_FETCHES_PER_HOST = 1

/** The relay's host list, the one wait for it, and the caps on relay fetches. */
internal open class PreviewAssetHostGate(
  private val waitMillis: Long = 3_000,
  relayFetches: Int = RELAY_IMAGE_FETCHES,
  private val perHostFetches: Int = RELAY_FETCHES_PER_HOST,
) {
  private var hosts: Set<String> = emptySet()
  private val loaded = CompletableDeferred<Unit>()
  private val relayPermits = Semaphore(relayFetches)

  /** One host's permits and how many loads hold or wait for them (wasm: one thread, no lock). */
  private class HostSlot(permits: Int) {
    val permits = Semaphore(permits)
    var users = 0
  }

  // Only hosts with a load running or waiting have an entry: the last load out removes it.
  private val hostSlots = HashMap<String, HostSlot>()

  /** Image hosts with a relay load running or waiting (tests). */
  internal fun hostsInUse(): Int = hostSlots.size

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
   * host is listed, else [url] itself, uncapped. A relay fetch takes its
   * host's permit first and a shared one second, and holds both until
   * [fetch] returns (body read included); the loads queued behind a busy
   * host hold no shared permit while they wait.
   */
  suspend fun <T> load(url: String, server: String, fetch: suspend (target: String) -> T): T {
    awaitLoaded()
    val target = route(url, server)
    if (target == url) return fetch(url)
    val host = previewAssetHostKey(url) ?: return relayPermits.withPermit { fetch(target) }
    val slot = hostSlots.getOrPut(host) { HostSlot(perHostFetches) }
    slot.users++
    try {
      return slot.permits.withPermit { relayPermits.withPermit { fetch(target) } }
    } finally {
      if (--slot.users == 0) hostSlots.remove(host)
    }
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
  val (_, host) = defaultPortOrigin(url) ?: return null
  if (host !in hosts) return null
  return "$server/preview-asset?url=${encodeUriComponent(url)}"
}

/**
 * The key the per-host cap counts [url] under: `scheme://host`, lowercase.
 * Only default ports are routed, so this is the relay's host:port.
 */
internal fun previewAssetHostKey(url: String): String? =
  defaultPortOrigin(url)?.let { (scheme, host) -> "$scheme://$host" }

/** (scheme, host), lowercase, for an http(s) URL without user-info on its scheme's default port. */
private fun defaultPortOrigin(url: String): Pair<String, String>? {
  val m = Regex("^((?i:https?))://([^/?#]*)").find(url) ?: return null
  val scheme = m.groupValues[1].lowercase()
  val authority = m.groupValues[2]
  if ('@' in authority || authority.startsWith("[")) return null
  val host = authority.substringBefore(':').lowercase()
  val port = authority.substringAfter(':', "")
  if (port.isNotEmpty() && port != if (scheme == "https") "443" else "80") return null
  return scheme to host
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
