/*
 * The bundle server's index, bundles/index.json (W3 in Keliver's
 * docs/DELIVERY_PLAN.md). Written by keliver-publish for a static server or CDN,
 * and served by the Keliver relay. The SAME file is in the Android and the iOS
 * host; keep them identical.
 *
 * The index is not signed and doesn't need to be for integrity: what runs is
 * decided by the manifest, which Zipline verifies against this host's key.
 * `manifestSha256` ties an entry to the exact manifest its publisher meant, and
 * is checked on the bytes Zipline loads (ManifestPinningHttpClient).
 *
 * Rollback protection (W4): every manifest keliver-publish or the relay
 * publishes carries its sequence in its SIGNED metadata ("keliver.sequence").
 * The host remembers the highest sequence it has run (its floor) and refuses a
 * manifest below it, or one without a sequence once the floor is above 0, on
 * the network (ManifestPinningHttpClient) and on a cache start. The floor rises
 * only after a load succeeded, so after Zipline verified that sequence's
 * signature. Not protected: a reinstall or cleared app data resets the floor.
 */
package @@PACKAGE@@

import app.cash.zipline.loader.ZiplineHttpClient
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.IOException

/** The widget protocol version this host renders. */
internal const val HOST_WIDGET_VERSION = 1

/**
 * The base channel. An entry without a channel is on it, and every host takes it:
 * a host built for another channel (HostConfig / BuildConfig, W4.4) takes that
 * channel's entries AND stable's, so it is never behind stable.
 */
internal const val DEFAULT_CHANNEL = "stable"

/**
 * Constraint keys this host understands (W4.5). An entry carrying any other is
 * skipped, so a constraint added later never reaches a host that can't honour it.
 */
private val KNOWN_CONSTRAINTS = setOf("rollout", "minHostVersion", "maxHostVersion")

/**
 * What an entry's constraints are checked against (W4.5): this install's random
 * id (generated once, kept on the device, never sent anywhere), this host build's
 * version (Android versionCode, iOS CFBundleVersion; null when it is not an
 * integer), and the rollback floor.
 */
internal data class HostFacts(val installId: String, val hostVersion: Long?, val floor: Long)

/**
 * The install's bucket for a staged rollout of [sequence], 0..99: the first four
 * bytes of sha256("<installId>:<sequence>") as an unsigned number, mod 100. An
 * entry at rollout R reaches the installs whose bucket is below R; each sequence
 * draws its own buckets.
 */
internal fun rolloutBucket(installId: String, sequence: Long): Int {
  val h = "$installId:$sequence".encodeUtf8().sha256()
  val first = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or
    ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
  return (first % 100).toInt()
}

private val SEGMENT = Regex("[A-Za-z0-9._-]+")
private val SHA256 = Regex("[0-9a-f]{64}")

/** The manifest metadata key that carries the publish sequence, inside the signed part of the manifest. */
internal const val SEQUENCE_METADATA_KEY = "keliver.sequence"
private val SEQUENCE_TEXT = Regex("[1-9][0-9]{0,17}")

/** The signed sequence in a (verified) manifest's metadata; null when absent or malformed. */
internal fun manifestSequence(metadata: Map<String, String>): Long? =
  metadata[SEQUENCE_METADATA_KEY]?.takeIf { SEQUENCE_TEXT.matches(it) }?.toLong()

/** The same, read from a manifest's JSON before Zipline has verified it: used only to refuse. */
internal fun manifestSequence(manifestJson: String): Long? = runCatching {
  val metadata = (Json.parseToJsonElement(manifestJson) as JsonObject)["metadata"] as? JsonObject
  (metadata?.get(SEQUENCE_METADATA_KEY) as? JsonPrimitive)?.takeIf { it.isString }?.content
}.getOrNull()?.takeIf { SEQUENCE_TEXT.matches(it) }?.toLong()

/** Why a manifest at [sequence] must not run on a host whose floor is [floor], or null when it may. */
internal fun rollbackProblem(sequence: Long?, floor: Long): String? = when {
  floor <= 0 -> null
  sequence == null -> "rollback refused: the manifest carries no signed sequence, and this host has run sequence $floor"
  sequence < floor -> "rollback refused: sequence $sequence is below $floor, the highest this host has run"
  else -> null
}

/** The newest usable entry: its channel, manifest path (relative to `<server>/bundles/`) and that manifest's sha256. */
internal data class IndexPick(val sequence: Long, val manifestPath: String, val manifestSha256: String, val channel: String = DEFAULT_CHANNEL)

/**
 * The entry this host should load from [indexJson], or a reason there is none.
 *
 * An entry is usable when its channel is [channel] or [DEFAULT_CHANNEL], it carries no constraint
 * this host doesn't know, its constraints admit [facts], its widget version is
 * at most [widgetVersion], every capability it requires is in [capabilities],
 * and its manifest path and sha256 are well formed. Of those, the highest
 * `sequence` wins.
 *
 * Constraints (W4.5):
 * - `minHostVersion` / `maxHostVersion`: this host's version must be within
 *   them; a host whose version is unknown skips any entry that has them.
 * - `rollout` (0..100): only installs whose [rolloutBucket] is below it. It
 *   gates only sequences above the host's floor: a host that has already run a
 *   sequence keeps it when its rollout is lowered or halted.
 */
internal fun pickFromIndex(
  indexJson: String,
  capabilities: Collection<String>,
  widgetVersion: Int = HOST_WIDGET_VERSION,
  channel: String = DEFAULT_CHANNEL,
  facts: HostFacts = HostFacts(installId = "", hostVersion = null, floor = 0),
): Result<IndexPick> = runCatching {
  val root = Json.parseToJsonElement(indexJson) as? JsonObject ?: throw IOException("the index is not a JSON object")
  val format = (root["format"] as? JsonPrimitive)?.intOrNull
  if (format != 1) throw IOException("index format $format is not 1")
  val entries = root["entries"] as? JsonArray ?: throw IOException("the index has no entries")
  entries.mapNotNull { usable(it as? JsonObject ?: return@mapNotNull null, capabilities, widgetVersion, channel, facts) }
    .maxByOrNull { it.sequence }
    ?: throw IOException("no compatible entry among ${entries.size} (channel $channel, widget version $widgetVersion, capabilities $capabilities)")
}

private fun usable(e: JsonObject, capabilities: Collection<String>, widgetVersion: Int, channel: String, facts: HostFacts): IndexPick? {
  val sequence = (e["sequence"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
  val entryChannel = when (val c = e["channel"]) {
    null -> DEFAULT_CHANNEL
    else -> (c as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
  }
  if (entryChannel != channel && entryChannel != DEFAULT_CHANNEL) return null
  when (val c = e["constraints"]) {
    null -> Unit
    is JsonObject -> if (!KNOWN_CONSTRAINTS.containsAll(c.keys) || !admits(c, sequence, facts)) return null
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
  return IndexPick(sequence, manifest, sha, entryChannel)
}

/** Whether [constraints] (only known keys) admit this host for [sequence]. A malformed value admits nobody. */
private fun admits(constraints: JsonObject, sequence: Long, facts: HostFacts): Boolean {
  fun number(key: String): Long? = (constraints[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
  if ("minHostVersion" in constraints) {
    val min = number("minHostVersion") ?: return false
    if (facts.hostVersion == null || facts.hostVersion < min) return false
  }
  if ("maxHostVersion" in constraints) {
    val max = number("maxHostVersion") ?: return false
    if (facts.hostVersion == null || facts.hostVersion > max) return false
  }
  if ("rollout" in constraints) {
    val rollout = number("rollout")?.takeIf { it in 0..100 } ?: return false
    if (sequence > facts.floor && rolloutBucket(facts.installId, sequence) >= rollout) return false
  }
  return true
}

/** A relative path under bundles/: no scheme, no leading '/', no '.' or '..' segment. */
internal fun manifestPathOk(path: String): Boolean =
  path.split('/').all { SEGMENT.matches(it) && it != "." && it != ".." }

/**
 * Zipline's HTTP client, with the manifest at [manifestUrl] held to [sha256]
 * (when the index gave one) and to the rollback floor, read from [floor] at the
 * moment the manifest arrives (a restart after a crash sees a floor raised since
 * the host started). Both checks run on the
 * very bytes Zipline then verifies and loads, so there is no second fetch to
 * differ from the first. They only ever refuse: a sequence read here is not yet
 * verified, so it never raises the floor. Every other download is unchanged.
 *
 * [pin] adds another manifest to check (W5: an update, or the fall-back to the
 * last good bundle) before the host hands Zipline its URL. Earlier pins stay:
 * a download still in flight for one of them is checked as before, and a pin
 * without a hash never erases one a URL already has. The host's one Treehouse
 * app keeps this one client for its whole life. Called from one thread (the
 * host's main thread).
 */
internal class ManifestPinningHttpClient(
  private val delegate: ZiplineHttpClient,
  manifestUrl: String,
  sha256: String?,
  private val floor: () -> Long = { 0 },
) : ZiplineHttpClient() {
  /** Manifest URL -> the sha256 the index holds it to (null: the floor only). Copied on write. */
  @Volatile private var pinned: Map<String, String?> = mapOf(manifestUrl to sha256)

  /**
   * Hold [manifestUrl] to [sha256] and to the floor. A null [sha256] (no index
   * hash: the last good bundle's URL) keeps a hash the URL is already held to.
   */
  fun pin(manifestUrl: String, sha256: String?) {
    pinned = pinned + (manifestUrl to (sha256 ?: pinned[manifestUrl]))
  }

  override suspend fun download(url: String, requestHeaders: List<Pair<String, String>>): ByteString {
    val body = delegate.download(url, requestHeaders)
    val pinned = pinned
    if (url in pinned) {
      val sha256 = pinned[url]
      if (sha256 != null) {
        val actual = body.sha256().hex()
        if (actual != sha256) throw IOException("manifest sha256 mismatch: $url is $actual, the index says $sha256")
      }
      rollbackProblem(manifestSequence(body.utf8()), floor())?.let { throw IOException(it) }
    }
    return body
  }

  override suspend fun openDevelopmentServerWebSocket(
    url: String,
    requestHeaders: List<Pair<String, String>>,
  ): Flow<String> = delegate.openDevelopmentServerWebSocket(url, requestHeaders)
}
