/*
 * The bundle server's index, bundles/index.json (W3 in Keliver's
 * docs/DELIVERY_PLAN.md). Written by keliver-publish for a static server or CDN,
 * and served by the Keliver relay. The SAME file is in the Android and the iOS
 * host; keep them identical.
 *
 * The index is not signed and doesn't need to be for integrity: what runs is
 * decided by the manifest, which Zipline verifies against this host's key.
 * `manifestSha256` ties an entry to the exact manifest its publisher meant, and
 * is checked on the bytes Zipline loads (ManifestPinningHttpClient). Not
 * protected: an older index or an older signed bundle being served again
 * (rollback); that needs a signed sequence the host remembers.
 */
package @@PACKAGE@@

import app.cash.zipline.loader.ZiplineHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okio.ByteString
import okio.IOException

/** The widget protocol version this host renders. */
internal const val HOST_WIDGET_VERSION = 1

/** The only channel this host takes. An entry without a channel is on it. */
internal const val HOST_CHANNEL = "stable"

/** Constraint keys this host understands. An entry carrying any other is skipped. */
private val KNOWN_CONSTRAINTS = emptySet<String>()

private val SEGMENT = Regex("[A-Za-z0-9._-]+")
private val SHA256 = Regex("[0-9a-f]{64}")

/** The newest usable entry: its manifest path (relative to `<server>/bundles/`) and that manifest's sha256. */
internal data class IndexPick(val sequence: Long, val manifestPath: String, val manifestSha256: String)

/**
 * The entry this host should load from [indexJson], or a reason there is none.
 *
 * An entry is usable when its channel is [channel], it carries no constraint
 * this host doesn't know, its widget version is at most [widgetVersion], every
 * capability it requires is in [capabilities], and its manifest path and sha256
 * are well formed. Of those, the highest `sequence` wins.
 */
internal fun pickFromIndex(
  indexJson: String,
  capabilities: Collection<String>,
  widgetVersion: Int = HOST_WIDGET_VERSION,
  channel: String = HOST_CHANNEL,
): Result<IndexPick> = runCatching {
  val root = Json.parseToJsonElement(indexJson) as? JsonObject ?: throw IOException("the index is not a JSON object")
  val format = (root["format"] as? JsonPrimitive)?.intOrNull
  if (format != 1) throw IOException("index format $format is not 1")
  val entries = root["entries"] as? JsonArray ?: throw IOException("the index has no entries")
  entries.mapNotNull { usable(it as? JsonObject ?: return@mapNotNull null, capabilities, widgetVersion, channel) }
    .maxByOrNull { it.sequence }
    ?: throw IOException("no compatible entry among ${entries.size} (channel $channel, widget version $widgetVersion, capabilities $capabilities)")
}

private fun usable(e: JsonObject, capabilities: Collection<String>, widgetVersion: Int, channel: String): IndexPick? {
  val sequence = (e["sequence"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
  val entryChannel = when (val c = e["channel"]) {
    null -> HOST_CHANNEL
    else -> (c as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
  }
  if (entryChannel != channel) return null
  when (val c = e["constraints"]) {
    null -> Unit
    is JsonObject -> if (!KNOWN_CONSTRAINTS.containsAll(c.keys)) return null
    else -> return null
  }
  val wv = (e["widgetVersion"] as? JsonPrimitive)?.intOrNull ?: return null
  if (wv > widgetVersion) return null
  val required = (e["capabilities"] as? JsonArray ?: return null).map {
    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null
  }
  if (!capabilities.containsAll(required)) return null
  val manifest = (e["manifest"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
  if (!manifestPathOk(manifest)) return null
  val sha = (e["manifestSha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase() ?: return null
  if (!SHA256.matches(sha)) return null
  return IndexPick(sequence, manifest, sha)
}

/** A relative path under bundles/: no scheme, no leading '/', no '.' or '..' segment. */
internal fun manifestPathOk(path: String): Boolean =
  path.split('/').all { SEGMENT.matches(it) && it != "." && it != ".." }

/**
 * Zipline's HTTP client, with the manifest at [manifestUrl] held to [sha256]:
 * the check runs on the very bytes Zipline then verifies and loads, so there is
 * no second fetch to differ from the first. Every other download is unchanged.
 */
internal class ManifestPinningHttpClient(
  private val delegate: ZiplineHttpClient,
  private val manifestUrl: String,
  private val sha256: String,
) : ZiplineHttpClient() {
  override suspend fun download(url: String, requestHeaders: List<Pair<String, String>>): ByteString {
    val body = delegate.download(url, requestHeaders)
    if (url == manifestUrl) {
      val actual = body.sha256().hex()
      if (actual != sha256) throw IOException("manifest sha256 mismatch: $url is $actual, the index says $sha256")
    }
    return body
  }

  override suspend fun openDevelopmentServerWebSocket(
    url: String,
    requestHeaders: List<Pair<String, String>>,
  ): Flow<String> = delegate.openDevelopmentServerWebSocket(url, requestHeaders)
}
