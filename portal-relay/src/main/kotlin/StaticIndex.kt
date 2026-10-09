import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * W3: the static bundle layout (docs/DELIVERY_PLAN.md, "W3 design").
 *
 *   <out>/bundles/index.json          the only file that changes
 *   <out>/bundles/v<N>/…              a compile task's output, copied unchanged, never rewritten
 *
 * The index is NOT signed. What runs is decided by the manifest, whose Ed25519
 * signature (and through it every module's sha256) the host verifies against
 * the key built into it. `manifestSha256` ties an entry's selection fields to
 * the manifest the publisher meant; it is a consistency check, not a security
 * boundary. Freshness and rollback protection are W4.
 */

internal const val INDEX_FORMAT = 1
internal const val INDEX_FILE = "index.json"
internal const val DEFAULT_CHANNEL = "stable"

/** The widget protocol version every Keliver bundle is published at today (as the relay's meta.json). */
internal const val PUBLISHED_WIDGET_VERSION = 1

private val CHANNEL_RE = Regex("[a-z0-9][a-z0-9-]{0,31}")
private val VERSION_DIR_RE = Regex("v([1-9][0-9]{0,8})")
private val prettyJson = Json { prettyPrint = true }

/**
 * A publish that was refused before anything was published. [aboutSigning] is
 * true when the bundle itself was the problem (unsigned, another key,
 * incomplete), so the CLI adds how signing works; a lock or index problem is
 * not about signing.
 */
internal class PublishRefused(message: String, val aboutSigning: Boolean = false) : Exception(message)

/**
 * W4: the manifest `metadata` key that carries the bundle's sequence. `metadata`
 * is in the signed part of a Zipline manifest, so the sequence is covered by the
 * Ed25519 signature; hosts built from the W4 templates refuse a sequence below
 * the highest they have run.
 * The signing block writes it from `-Pkeliver.sequence` before it signs.
 */
internal const val SEQUENCE_METADATA_KEY = "keliver.sequence"

/**
 * The sequence a manifest's signed metadata carries, exactly as written (a
 * decimal string), or null when it carries none.
 */
internal fun signedSequenceText(manifestJson: String): String? {
  val root = runCatching { Json.parseToJsonElement(manifestJson) as? JsonObject }.getOrNull() ?: return null
  val v = (root["metadata"] as? JsonObject)?.get(SEQUENCE_METADATA_KEY) as? JsonPrimitive ?: return null
  return if (v.isString) v.content else null
}

/** Why [manifestJson] may not be published at [sequence], or null. */
internal fun sequenceProblem(manifestJson: String, sequence: Long): String? {
  val signed = signedSequenceText(manifestJson)
    ?: return "the manifest carries no signed $SEQUENCE_METADATA_KEY, so a host could not refuse an older " +
      "bundle in its place. Its signing block predates W4: run keliver-new-publish-target.sh to replace it"
  return if (signed == sequence.toString()) {
    null
  } else {
    "the manifest is signed for sequence $signed, but this publish is sequence $sequence. keliver-publish passes " +
      "the next sequence to the build (-Pkeliver.sequence); with --skip-build, or when another publish ran " +
      "since this build, publish again with a build"
  }
}

/**
 * The sequence the next entry of [index] gets: past every entry, and past every
 * signed sequence in the `v<N>/` manifests already in [bundlesDir]. A directory
 * downloaded without its index (an `--init` over existing bundles) would
 * otherwise restart at 1 below sequences hosts have already run.
 */
internal fun nextSequence(index: JsonObject, bundlesDir: File): Long {
  val fromIndex = (index["entries"] as JsonArray).maxOfOrNull { ((it as JsonObject)["sequence"] as JsonPrimitive).longOrNull!! } ?: 0L
  val fromManifests = bundlesDir.listFiles { f -> f.isDirectory && VERSION_DIR_RE.matches(f.name) }.orEmpty()
    .mapNotNull { dir ->
      File(dir, "manifest.zipline.json").takeIf { it.isFile }?.let { signedSequenceText(it.readText()) }
        ?.takeIf { SIGNED_SEQUENCE_RE.matches(it) }?.toLong()
    }
    .maxOrNull() ?: 0L
  return maxOf(fromIndex, fromManifests) + 1
}

private val SIGNED_SEQUENCE_RE = Regex("[1-9][0-9]{0,17}")

/** The highest bundle number a `v<N>` directory can carry (VERSION_DIR_RE). */
private const val MAX_VERSION = 999_999_999

internal fun sha256Hex(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Why the compile output in [output] must not be published, or null.
 *
 * 1. The manifest carries a portal-ed25519 signature that verifies against
 *    [publicKeyHex] (the relay's own check, [publishedSignatureProblem]).
 * 2. Every module the manifest names is a file INSIDE [output] whose sha256 is
 *    the one the signed manifest records. Zipline checks this again on the
 *    device; checking it here stops an incomplete layout reaching a server.
 */
internal fun staticOutputProblem(output: File, publicKeyHex: String): String? {
  val manifestFile = File(output, "manifest.zipline.json")
  if (!manifestFile.isFile) return "no manifest.zipline.json in $output"
  val manifestJson = manifestFile.readText()
  publishedSignatureProblem(manifestJson, publicKeyHex)?.let { return it }
  val modules = runCatching { Json.parseToJsonElement(manifestJson) as JsonObject }.getOrNull()
    ?.get("modules") as? JsonObject ?: return "the manifest has no modules"
  val root = output.canonicalFile
  for ((id, m) in modules) {
    val module = m as? JsonObject ?: return "module $id is not an object"
    val url = (module["url"] as? JsonPrimitive)?.content ?: return "module $id has no url"
    val expected = (module["sha256"] as? JsonPrimitive)?.content?.lowercase() ?: return "module $id has no sha256"
    if (url.startsWith("/") || "://" in url || url.split('/').any { it == ".." }) {
      return "module $id has a url outside the bundle directory: $url"
    }
    val f = File(root, url).canonicalFile
    if (!f.toPath().startsWith(root.toPath()) || !f.isFile) return "module $id: $url is missing from $output"
    val actual = sha256Hex(f.readBytes())
    if (actual != expected) return "module $id: $url has sha256 $actual, but the signed manifest says $expected"
  }
  return null
}

/** The index in [bundlesDir], or an empty format-1 index when there is none. Refuses one it can't read. */
internal fun readIndex(bundlesDir: File): JsonObject {
  val f = File(bundlesDir, INDEX_FILE)
  if (!f.exists()) return JsonObject(mapOf("format" to JsonPrimitive(INDEX_FORMAT), "entries" to JsonArray(emptyList())))
  val root = runCatching { Json.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull()
    ?: throw PublishRefused("$f is not a JSON object; it was left as it is")
  val format = (root["format"] as? JsonPrimitive)?.intOrNull
  if (format != INDEX_FORMAT) throw PublishRefused("$f has format $format; this tool writes format $INDEX_FORMAT only")
  val entries = root["entries"] as? JsonArray ?: throw PublishRefused("$f has no entries array; it was left as it is")
  // W4.4: a promoted bundle has one entry per channel, so a sequence may appear
  // more than once, but once per channel, and always for the same v<N>.
  val onChannel = HashSet<Pair<Long, String>>()
  val versionOf = HashMap<Long, Int>()
  val sequenceOf = HashMap<Int, Long>()
  val manifestOf = HashMap<Long, Pair<JsonElement?, JsonElement?>>()
  entries.forEachIndexed { i, e ->
    val o = e as? JsonObject ?: throw PublishRefused("$f entry $i is not an object")
    val sequence = (o["sequence"] as? JsonPrimitive)?.longOrNull
    val version = (o["version"] as? JsonPrimitive)?.intOrNull
    if (sequence == null || version == null) {
      throw PublishRefused("$f entry $i has no integer sequence and version; it was left as it is")
    }
    if (sequence < 1 || version < 1 || version > MAX_VERSION) {
      throw PublishRefused("$f entry $i has sequence $sequence and version $version, outside 1..$MAX_VERSION; it was left as it is")
    }
    o["channel"]?.let { c ->
      if (!(c is JsonPrimitive && c.isString && CHANNEL_RE.matches(c.content))) {
        throw PublishRefused("$f entry $i has a malformed channel ($c), which hosts skip; it was left as it is")
      }
    }
    val channel = entryChannel(o)
    if (!onChannel.add(sequence to channel)) {
      throw PublishRefused("$f has two entries with sequence $sequence on channel $channel; it was left as it is")
    }
    versionOf.put(sequence, version)?.takeIf { it != version }?.let {
      throw PublishRefused("$f has sequence $sequence for both v$it and v$version; it was left as it is")
    }
    sequenceOf.put(version, sequence)?.takeIf { it != sequence }?.let {
      throw PublishRefused("$f has two entries for v$version, at sequences $it and $sequence; it was left as it is")
    }
    val manifest = o["manifest"] to o["manifestSha256"]
    manifestOf.put(sequence, manifest)?.takeIf { it != manifest }?.let {
      throw PublishRefused("$f has entries at sequence $sequence naming different manifests; it was left as it is")
    }
  }
  return root
}

/**
 * Whether every host that can run [source] can also run [other]: [other] has no
 * constraints, requires no capability [source] doesn't, and needs no newer widget
 * protocol. (Both entries have passed [entryShapeProblem].)
 */
private fun noStricterThan(other: JsonObject, source: JsonObject): Boolean {
  fun caps(e: JsonObject) = (e["capabilities"] as JsonArray).map { (it as JsonPrimitive).content }.toSet()
  fun wv(e: JsonObject) = (e["widgetVersion"] as JsonPrimitive).intOrNull!!
  return other["constraints"] == null && caps(source).containsAll(caps(other)) && wv(other) <= wv(source)
}

/**
 * W4.5: the constraints a publish, republish or promotion sets on its entry, as
 * the W4.5 hosts read them (BundleIndex.kt, `admits`): `rollout` 0..100,
 * `minHostVersion` and `maxHostVersion` (the host build's integer version).
 * Hosts from before W4.5 skip any entry with constraints.
 */
internal data class Constraints(val rollout: Int? = null, val minHostVersion: Long? = null, val maxHostVersion: Long? = null) {
  val problem: String?
    get() = when {
      rollout != null && rollout !in 0..100 -> "a rollout is a percentage, 0 to 100 (got $rollout)"
      minHostVersion != null && minHostVersion < 0 -> "a minimum host version is not negative (got $minHostVersion)"
      maxHostVersion != null && maxHostVersion < 0 -> "a maximum host version is not negative (got $maxHostVersion)"
      minHostVersion != null && maxHostVersion != null && minHostVersion > maxHostVersion ->
        "the minimum host version $minHostVersion is above the maximum $maxHostVersion: no host could take it"
      else -> null
    }

  /**
   * Why setting these over [base] (constraints copied from an entry being
   * republished or promoted) would let a host take code the original kept from
   * it, or null: a host-version gate may be narrowed, never widened.
   */
  fun loosening(base: JsonElement?): String? {
    val b = base as? JsonObject ?: return null
    fun copied(key: String) = (b[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    copied("minHostVersion")?.let { if (minHostVersion != null && minHostVersion < it) return "--min-host-version $minHostVersion is below the copied minHostVersion $it: hosts older than the code needs would take it" }
    copied("maxHostVersion")?.let { if (maxHostVersion != null && maxHostVersion > it) return "--max-host-version $maxHostVersion is above the copied maxHostVersion $it: hosts the code does not support would take it" }
    return null
  }

  /** [base] (an entry's constraints, kept) with these set on top; null when the result is empty. */
  fun over(base: JsonElement?): JsonObject? {
    val merged = (base as? JsonObject).orEmpty() + buildMap {
      rollout?.let { put("rollout", JsonPrimitive(it)) }
      minHostVersion?.let { put("minHostVersion", JsonPrimitive(it)) }
      maxHostVersion?.let { put("maxHostVersion", JsonPrimitive(it)) }
    }
    return merged.takeIf { it.isNotEmpty() }?.let(::JsonObject)
  }
}

/** An entry's channel; one without a channel is on [DEFAULT_CHANNEL], as hosts read it. */
private fun entryChannel(e: JsonObject): String =
  (e["channel"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: DEFAULT_CHANNEL

internal fun entryJson(
  sequence: Long,
  version: Int,
  channel: String,
  capabilities: List<String>,
  manifestSha256: String,
  createdAt: Instant,
): JsonObject = buildJsonObject {
  put("sequence", sequence)
  put("version", version)
  put("channel", channel)
  put("widgetVersion", PUBLISHED_WIDGET_VERSION)
  put("capabilities", JsonArray(capabilities.map(::JsonPrimitive)))
  put("manifest", "v$version/manifest.zipline.json")
  put("manifestSha256", manifestSha256)
  put("createdAt", createdAt.toString())
}

internal data class StaticPublish(val version: Int, val sequence: Long, val dir: File, val entry: JsonObject)

/**
 * Publish the compile output [output] into the static layout under [outDir]:
 * verified first, then copied to `bundles/v<N>/` (staged and renamed), then
 * `bundles/index.json` rewritten through a temp file and an atomic rename, with
 * the new entry at the next sequence. Existing entries are kept verbatim,
 * fields this tool doesn't know included.
 *
 * Refused — with nothing written, not even the `bundles/` directory — when the
 * output is unsigned, signed by another key, or incomplete, when the existing
 * index can't be read, when [outDir] lies inside [output], or when there is no
 * `bundles/index.json` and [init] is false. A static site's live index is the
 * only record of the sequence and of the `v<N>` numbers already served, so
 * publishing into an empty directory would start again at v1 and overwrite
 * them when uploaded: CI must download the live `bundles/` first, and only the
 * very first publish passes `--init`.
 *
 * When another publish holds the lock it is refused too; only the lock file
 * `bundles/.publish.lock` may then have been created.
 */
internal fun publishStatic(
  output: File,
  outDir: File,
  publicKeyHex: String,
  channel: String = DEFAULT_CHANNEL,
  capabilities: List<String> = emptyList(),
  now: Instant = Instant.now(),
  init: Boolean = false,
  constraints: Constraints = Constraints(),
  extraFields: Map<String, JsonElement> = emptyMap(), // added to (or replacing) the new entry's fields
  beforeLock: () -> Unit = {}, // a test hook: another publish landing between the checks and the lock
): StaticPublish {
  if (!CHANNEL_RE.matches(channel)) throw PublishRefused("channel '$channel' must match ${CHANNEL_RE.pattern}")
  constraints.problem?.let { throw PublishRefused(it) }
  val outputRoot = output.canonicalFile.toPath()
  if (outDir.canonicalFile.toPath().startsWith(outputRoot)) {
    throw PublishRefused("--out $outDir is inside the compile output $output; choose a directory outside it")
  }
  staticOutputProblem(output, publicKeyHex)?.let { throw PublishRefused(it, aboutSigning = true) }
  val bundlesDir = File(outDir, "bundles")
  if (!File(bundlesDir, INDEX_FILE).exists() && !init) {
    throw PublishRefused(
      "there is no ${File(bundlesDir, INDEX_FILE)}. Publishing continues the LIVE index: download the " +
        "served bundles/index.json and every bundles/v<N>/ into $outDir first, or v1 and the sequence would " +
        "start again and overwrite what hosts already load. Only for the very first publish, pass --init.",
    )
  }
  // Read (and so validate) the index before creating anything, and check the
  // signed sequence against it: a refusal here writes nothing.
  val manifestText = File(output, "manifest.zipline.json").readText()
  sequenceProblem(manifestText, nextSequence(readIndex(bundlesDir), bundlesDir))?.let { throw PublishRefused(it) }
  beforeLock()

  return withPublishLock(bundlesDir) {
    val index = readIndex(bundlesDir) // again, under the lock
    val entries = (index["entries"] as JsonArray).map { it as JsonObject }
    val dirVersions = bundlesDir.listFiles { f -> f.isDirectory }.orEmpty()
      .mapNotNull { VERSION_DIR_RE.matchEntire(it.name)?.groupValues?.get(1)?.toInt() }
    val highest = (dirVersions + entries.map { (it["version"] as JsonPrimitive).intOrNull!! }).maxOrNull() ?: 0
    if (highest >= MAX_VERSION) throw PublishRefused("$bundlesDir already holds v$highest, the highest number this layout allows")
    val version = highest + 1
    val sequence = nextSequence(index, bundlesDir)

    val dest = File(bundlesDir, "v$version")
    val staging = File(bundlesDir, ".staging-${UUID.randomUUID()}")
    try {
      output.copyRecursively(staging, overwrite = false)
      // What was copied must still be what was verified.
      staticOutputProblem(staging, publicKeyHex)?.let {
        throw PublishRefused("the copy in $staging failed its check: $it", aboutSigning = true)
      }
      // Under the lock: another publish may have taken this sequence since the build.
      sequenceProblem(File(staging, "manifest.zipline.json").readText(), sequence)?.let { throw PublishRefused(it) }
      Files.move(staging.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } finally {
      if (staging.exists()) staging.deleteRecursively()
    }

    val manifestSha = sha256Hex(File(dest, "manifest.zipline.json").readBytes())
    val entryConstraints = constraints.over(extraFields["constraints"])
    val entry = JsonObject(
      entryJson(sequence, version, channel, capabilities, manifestSha, now) + extraFields - "constraints" +
        listOfNotNull(entryConstraints?.let { "constraints" to it }),
    )
    val next = JsonObject(index + ("entries" to JsonArray(entries + entry)))
    writeAtomically(File(bundlesDir, INDEX_FILE), prettyJson.encodeToString(JsonElement.serializer(), next) + "\n")
    StaticPublish(version, sequence, dest, entry)
  }
}

/**
 * Runs [block] holding `bundles/.publish.lock` (creating [bundlesDir]), or refuses
 * when another publish holds it.
 */
private fun <T> withPublishLock(bundlesDir: File, block: () -> T): T {
  bundlesDir.mkdirs()
  FileChannel.open(File(bundlesDir, ".publish.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
    // tryLock() answers null when another process holds the lock, and throws when another channel
    // in this JVM does; both are the same refusal.
    val lock = runCatching { ch.tryLock() }.getOrElse { e ->
      if (e is java.nio.channels.OverlappingFileLockException) null else throw e
    } ?: throw PublishRefused(
      "another keliver-publish holds $bundlesDir/.publish.lock. Wait for it to finish (in CI, serialise " +
        "publishes with a concurrency group), then publish again.",
    )
    try {
      return block()
    } finally {
      lock.release()
    }
  }
}

internal data class StaticPromotion(val sequence: Long, val version: Int, val from: String, val entry: JsonObject)

/**
 * Promotion (W4.4, `keliver-publish --promote <sequence> --channel <name>`): the
 * bundle already published at [sequence] is offered on [channel] too, as a
 * second index entry for the same `v<N>/` and manifest. Nothing is built or
 * signed, so no private key is involved: channels are selection policy in the
 * unsigned index, and the sequence floor still bounds what any entry can do.
 * The new entry copies the source entry verbatim (capabilities, widget version,
 * constraints, manifest and its sha256) onto [channel], with `promotedFrom` and
 * a new `createdAt`. Constraints are copied so that a gate never falls away on
 * the way: hosts the source entry skips, the promoted one skips too.
 *
 * Refused, with nothing written: no entry at [sequence]; [sequence] is already
 * on [channel]; [channel] already offers a higher sequence (hosts there would
 * never pick this one; `--republish` publishes older code as a new sequence);
 * the source entry is not shaped as hosts read it; or the bundle in `v<N>/` is
 * not the manifest the entry names, or does not verify against [publicKeyHex].
 */
internal fun promoteStatic(
  outDir: File,
  sequence: Long,
  channel: String,
  publicKeyHex: String,
  now: Instant = Instant.now(),
  constraints: Constraints = Constraints(), // set on top of the copied ones (W4.5)
): StaticPromotion {
  if (!CHANNEL_RE.matches(channel)) throw PublishRefused("channel '$channel' must match ${CHANNEL_RE.pattern}")
  constraints.problem?.let { throw PublishRefused(it) }
  val bundlesDir = File(outDir, "bundles")
  if (!File(bundlesDir, INDEX_FILE).exists()) {
    throw PublishRefused("there is no ${File(bundlesDir, INDEX_FILE)} to promote in: download the served bundles/ first")
  }
  fun check(index: JsonObject): JsonObject {
    val entries = (index["entries"] as JsonArray).map { it as JsonObject }
    val atSequence = entries.filter { (it["sequence"] as JsonPrimitive).longOrNull == sequence }
    val source = atSequence.firstOrNull() ?: throw PublishRefused("${File(bundlesDir, INDEX_FILE)} has no entry at sequence $sequence")
    if (atSequence.any { entryChannel(it) == channel }) throw PublishRefused("sequence $sequence is already on channel $channel")
    entryShapeProblem(source)?.let { throw PublishRefused("the index entry at sequence $sequence $it; it was left as it is") }
    constraints.loosening(source["constraints"])?.let { throw PublishRefused(it) }
    // Hosts on [channel] see its entries and stable's. A higher one that every host able
    // to run this bundle can also run (no constraints, nothing more required) means none
    // of them would pick this one. A higher entry that some of them skip does not.
    entries.filter { (entryChannel(it) == channel || entryChannel(it) == DEFAULT_CHANNEL) && entryShapeProblem(it) == null }
      .filter { (it["sequence"] as JsonPrimitive).longOrNull!! > sequence && noStricterThan(it, source) }
      .maxOfOrNull { (it["sequence"] as JsonPrimitive).longOrNull!! }
      ?.let {
        throw PublishRefused(
          "hosts on channel $channel already take sequence $it, above $sequence and asking no more of them, so none " +
            "would pick this one. To put older code back, republish it (--republish) as a new sequence.",
        )
      }
    if ((source["manifest"] as? JsonPrimitive)?.content != "v${(source["version"] as JsonPrimitive).content}/manifest.zipline.json") {
      throw PublishRefused("the entry at sequence $sequence names ${source["manifest"]}, not its own v<N>/manifest.zipline.json")
    }
    val version = (source["version"] as JsonPrimitive).intOrNull!!
    val dir = File(bundlesDir, "v$version")
    val manifest = File(dir, "manifest.zipline.json")
    val expected = (source["manifestSha256"] as? JsonPrimitive)?.content?.lowercase()
    if (!manifest.isFile || sha256Hex(manifest.readBytes()) != expected) {
      throw PublishRefused("$manifest is not the manifest the entry at sequence $sequence names (manifestSha256)")
    }
    staticOutputProblem(dir, publicKeyHex)?.let { throw PublishRefused("v$version: $it", aboutSigning = true) }
    return source
  }
  check(readIndex(bundlesDir)) // before the lock: a refusal writes nothing, not even the lock file
  return withPublishLock(bundlesDir) {
    val index = readIndex(bundlesDir)
    val source = check(index) // again, under the lock
    val entry = JsonObject(
      source - "constraints" + mapOf(
        "channel" to JsonPrimitive(channel),
        "promotedFrom" to JsonPrimitive(entryChannel(source)),
        "createdAt" to JsonPrimitive(now.toString()),
      ) + listOfNotNull(constraints.over(source["constraints"])?.let { "constraints" to it }),
    )
    val next = JsonObject(index + ("entries" to JsonArray((index["entries"] as JsonArray) + entry)))
    writeAtomically(File(bundlesDir, INDEX_FILE), prettyJson.encodeToString(JsonElement.serializer(), next) + "\n")
    StaticPromotion(sequence, (source["version"] as JsonPrimitive).intOrNull!!, entryChannel(source), entry)
  }
}

internal data class StaticRollout(val sequence: Long, val channel: String, val from: JsonElement?, val entry: JsonObject, val note: String? = null)

/**
 * W4.5: sets `constraints.rollout` on the entry at [sequence] (on [channel], which
 * may be omitted when the sequence has one entry). Raising it reaches more
 * installs; 0 halts it for installs that have not run it, while hosts that
 * already ran it keep it (their floor). Only the index changes, under the
 * publish lock; nothing is built or signed, and no key is needed.
 */
internal fun setRolloutStatic(outDir: File, sequence: Long, rollout: Int, channel: String? = null): StaticRollout {
  Constraints(rollout = rollout).problem?.let { throw PublishRefused(it) }
  if (channel != null && !CHANNEL_RE.matches(channel)) throw PublishRefused("channel '$channel' must match ${CHANNEL_RE.pattern}")
  val bundlesDir = File(outDir, "bundles")
  if (!File(bundlesDir, INDEX_FILE).exists()) {
    throw PublishRefused("there is no ${File(bundlesDir, INDEX_FILE)} to change: download the served bundles/ first")
  }
  fun target(index: JsonObject): Int {
    val entries = (index["entries"] as JsonArray).map { it as JsonObject }
    val at = entries.withIndex().filter { (_, e) -> (e["sequence"] as JsonPrimitive).longOrNull == sequence }
    if (at.isEmpty()) throw PublishRefused("${File(bundlesDir, INDEX_FILE)} has no entry at sequence $sequence")
    val chosen = when {
      channel != null -> at.singleOrNull { (_, e) -> entryChannel(e) == channel }
        ?: throw PublishRefused("sequence $sequence is not on channel $channel")
      at.size == 1 -> at.single()
      else -> throw PublishRefused("sequence $sequence is on channels ${at.joinToString { entryChannel(it.value) }}: name one with --channel")
    }
    entryShapeProblem(chosen.value)?.let { throw PublishRefused("the index entry at sequence $sequence $it; it was left as it is") }
    // Hosts from before W4.5 skip any entry with constraints. Giving a live, unconstrained
    // entry some would hide it from them (and their floor would then refuse the older one).
    if (chosen.value["constraints"] == null) {
      throw PublishRefused(
        "the entry at sequence $sequence has no constraints, and hosts from before staged rollouts skip any entry " +
          "that has some: adding a rollout now would hide a bundle they may already run. Stage a rollout when " +
          "publishing (--rollout), or republish with --rollout.",
      )
    }
    return chosen.index
  }
  target(readIndex(bundlesDir)) // before the lock: a refusal writes nothing
  return withPublishLock(bundlesDir) {
    val index = readIndex(bundlesDir)
    val i = target(index)
    val entries = (index["entries"] as JsonArray).map { it as JsonObject }.toMutableList()
    val old = entries[i]
    val entry = JsonObject(old - "constraints" + ("constraints" to Constraints(rollout = rollout).over(old["constraints"])!!))
    entries[i] = entry
    writeAtomically(File(bundlesDir, INDEX_FILE), prettyJson.encodeToString(JsonElement.serializer(), JsonObject(index + ("entries" to JsonArray(entries)))) + "\n")
    // Hosts on another channel also take stable's entry at this sequence (the same buckets).
    val stableTwin = entries.firstOrNull {
      entryChannel(entry) != DEFAULT_CHANNEL && entryChannel(it) == DEFAULT_CHANNEL &&
        (it["sequence"] as JsonPrimitive).longOrNull == sequence
    }
    val stableRollout = stableTwin?.let { ((it["constraints"] as? JsonObject)?.get("rollout") as? JsonPrimitive)?.intOrNull ?: 100 }
    val note = if (stableRollout != null && stableRollout > rollout) {
      "sequence $sequence is also on stable at rollout $stableRollout%, which ${entryChannel(entry)} hosts take too: " +
        "set it there as well (--channel stable) for this to take effect"
    } else {
      null
    }
    StaticRollout(sequence, entryChannel(entry), (old["constraints"] as? JsonObject)?.get("rollout"), entry, note)
  }
}

/**
 * W4.3: a build (here, the app's `keliverResign` task) that exited non-zero.
 * Nothing was published.
 */
internal class BuildFailed(val exitCode: Int) : Exception("the build exited $exitCode")

/**
 * Rollback as a new sequence (W4.3, `keliver-publish --republish <version>`).
 *
 * Hosts refuse a signed sequence below the highest they have run, so going back
 * to older code means publishing it again, ahead of everything: v[version]'s
 * modules are copied unchanged into a scratch directory, [resign] (the app's
 * `keliverResign` Gradle task, which holds the private key) writes the next
 * sequence into that copy's manifest and signs it again, and the copy is
 * published like any build, as a new `v<N>/` and index entry. The entry keeps
 * the original's capabilities, widget version and constraints (the code is the
 * same, and so are the hosts it may reach), and the channel it was first
 * published on unless [channel] is given (required when v[version] was promoted
 * to more than one channel), and records `republishOf`.
 *
 * Refused before [resign] runs when the index has no entry for v[version] (a
 * `v<N>/` without one is a publish that did not finish), when that entry is not
 * shaped as hosts read it (a republish must never loosen what the original
 * allowed), when that directory's manifest is not the one the entry names, or
 * when the bundle is not complete and signed with [publicKeyHex]. Refused after it when the re-signed manifest
 * changed anything but its sequence and signature. The scratch copy is always
 * deleted; a refusal writes nothing.
 */
internal fun republishStatic(
  outDir: File,
  version: Int,
  publicKeyHex: String,
  channel: String? = null,
  now: Instant = Instant.now(),
  constraints: Constraints = Constraints(), // set on top of the original's (W4.5)
  resign: (dir: File, sequence: Long) -> Int,
): StaticPublish {
  constraints.problem?.let { throw PublishRefused(it) }
  val bundlesDir = File(outDir, "bundles")
  if (!File(bundlesDir, INDEX_FILE).exists()) {
    throw PublishRefused("there is no ${File(bundlesDir, INDEX_FILE)} to republish from: download the served bundles/ first")
  }
  val index = readIndex(bundlesDir)
  val original = (index["entries"] as JsonArray).map { it as JsonObject }
    .firstOrNull { (it["version"] as JsonPrimitive).intOrNull == version }
    ?: throw PublishRefused(
      "${File(bundlesDir, INDEX_FILE)} has no entry for v$version. Only a published bundle can be republished; " +
        "a v<N>/ without an entry is a publish that did not finish.",
    )
  if (channel != null && !CHANNEL_RE.matches(channel)) throw PublishRefused("channel '$channel' must match ${CHANNEL_RE.pattern}")
  val onChannels = (index["entries"] as JsonArray).map { it as JsonObject }
    .filter { (it["version"] as JsonPrimitive).intOrNull == version }.map(::entryChannel).distinct()
  if (channel == null && onChannels.size > 1) {
    throw PublishRefused(
      "v$version is on channels ${onChannels.joinToString()} (it was promoted). Name the channel to roll back with " +
        "--channel; republish once per channel if it is more than one.",
    )
  }
  entryShapeProblem(original)?.let { throw PublishRefused("the index entry for v$version $it; it was left as it is") }
  constraints.loosening(original["constraints"])?.let { throw PublishRefused(it) }
  val source = File(bundlesDir, "v$version")
  val sourceManifest = File(source, "manifest.zipline.json")
  val expectedSha = (original["manifestSha256"] as? JsonPrimitive)?.content?.lowercase()
  if (!sourceManifest.isFile || sha256Hex(sourceManifest.readBytes()) != expectedSha) {
    throw PublishRefused("$sourceManifest is not the manifest the index entry for v$version names (manifestSha256); it was left as it is")
  }
  staticOutputProblem(source, publicKeyHex)?.let { throw PublishRefused("v$version: $it", aboutSigning = true) }
  val entryChannel = channel ?: (original["channel"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: DEFAULT_CHANNEL
  val capabilities = (original["capabilities"] as JsonArray).map { (it as JsonPrimitive).content }
  val extra = buildMap<String, JsonElement> {
    put("widgetVersion", original["widgetVersion"]!!)
    // Host-version gates are about the code, so they are copied. A rollout is not: a new
    // sequence draws new buckets, so a copied 10% would be another 10%, and a copied halt
    // would keep the rollback from the hosts that need it. Pass --rollout to stage it.
    constraints.over((original["constraints"] as? JsonObject)?.let { JsonObject(it - "rollout") })?.let { put("constraints", it) }
    put("republishOf", JsonPrimitive(version))
  }

  val scratch = Files.createTempDirectory("keliver-republish-").toFile()
  try {
    val copy = File(scratch, "v$version")
    source.copyRecursively(copy, overwrite = false)
    val sequence = nextSequence(index, bundlesDir)
    val code = resign(copy, sequence)
    if (code != 0) throw BuildFailed(code)
    republishProblem(sourceManifest.readText(), File(copy, "manifest.zipline.json").readText())?.let { throw PublishRefused(it) }
    try {
      return publishStatic(copy, outDir, publicKeyHex, entryChannel, capabilities, now, extraFields = extra)
    } catch (e: PublishRefused) {
      // Another publish took this sequence while the re-sign ran: the advice is not "build again".
      val signedFor = signedSequenceText(File(copy, "manifest.zipline.json").readText())
      if (signedFor == sequence.toString() && e.message.orEmpty().contains("but this publish is sequence")) {
        throw PublishRefused("another publish took sequence $sequence while this republish ran; nothing was written. Run --republish $version again.")
      }
      throw e
    }
  } finally {
    scratch.deleteRecursively()
  }
}

/**
 * Why an index entry would be read differently by a host once republished, or
 * null: hosts skip an entry whose channel is not a string, whose widget version
 * is not an integer, or whose capabilities are not a list of strings, and a
 * republish writes those fields explicitly.
 */
private fun entryShapeProblem(e: JsonObject): String? {
  e["channel"]?.let { c -> if (!(c is JsonPrimitive && c.isString && CHANNEL_RE.matches(c.content))) return "has a malformed channel" }
  val wv = e["widgetVersion"]
  if (!(wv is JsonPrimitive && !wv.isString && wv.intOrNull != null)) return "has no integer widgetVersion"
  val caps = e["capabilities"] as? JsonArray ?: return "has no capabilities list"
  if (!caps.all { it is JsonPrimitive && it.isString }) return "has a capability that is not a string"
  e["constraints"]?.let { if (it !is JsonObject) return "has constraints that are not an object" }
  return null
}

/**
 * Why [resignedJson] is not [originalJson] at a new sequence, or null: the
 * modules (with their sha256), the main module and function, Zipline's version
 * and every other metadata key must be unchanged.
 */
internal fun republishProblem(originalJson: String, resignedJson: String): String? {
  fun parse(s: String) = runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull()
  val original = parse(originalJson) ?: return "the original manifest is not a JSON object"
  val resigned = parse(resignedJson) ?: return "the re-signed manifest is not a JSON object"
  for (field in listOf("modules", "mainModuleId", "mainFunction", "version")) {
    if ((original[field] ?: JsonNull) != (resigned[field] ?: JsonNull)) return "the re-signed manifest's $field differs from the original's: a republish re-signs, it never changes code"
  }
  fun otherMetadata(m: JsonObject) = ((m["metadata"] as? JsonObject).orEmpty()) - SEQUENCE_METADATA_KEY
  if (otherMetadata(original) != otherMetadata(resigned)) return "the re-signed manifest's metadata differs from the original's in more than $SEQUENCE_METADATA_KEY"
  return null
}

private fun writeAtomically(target: File, text: String) {
  val tmp = File(target.parentFile, ".${target.name}.tmp-${UUID.randomUUID()}")
  try {
    tmp.writeText(text)
    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  } finally {
    tmp.delete()
  }
}

/**
 * The relay's `GET /bundles/index.json`: the same format, generated from its
 * store's `v<N>/meta.json` and manifests, so a host built from the current
 * templates reads one protocol from the relay and from a static server.
 * Sequence = version: the relay numbers its bundles in publish order.
 *
 * A `v<N>` without a manifest, or without a readable `meta.json` holding an
 * integer `widgetVersion`, is left out, as `/bundles/latest` never picks one:
 * the relay copies the bundle first and writes meta.json last, so such a
 * directory is a publish that did not finish.
 */
internal fun relayIndexJson(bundlesDir: File): String {
  val entries = bundlesDir.listFiles { f -> f.isDirectory }.orEmpty()
    .mapNotNull { dir -> VERSION_DIR_RE.matchEntire(dir.name)?.groupValues?.get(1)?.toInt()?.let { it to dir } }
    .sortedBy { it.first }
    .mapNotNull { (version, dir) ->
      val manifest = File(dir, "manifest.zipline.json").takeIf { it.isFile } ?: return@mapNotNull null
      val meta = runCatching { Json.parseToJsonElement(File(dir, "meta.json").readText()) as JsonObject }.getOrNull()
        ?: return@mapNotNull null
      val widgetVersion = (meta["widgetVersion"] as? JsonPrimitive)
        ?.takeUnless { it.isString }?.intOrNull ?: return@mapNotNull null
      val caps = (meta["capabilities"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
      val created = (meta["createdAt"] as? JsonPrimitive)?.longOrNull?.let(Instant::ofEpochMilli) ?: Instant.EPOCH
      JsonObject(
        entryJson(version.toLong(), version, DEFAULT_CHANNEL, caps, sha256Hex(manifest.readBytes()), created) +
          ("widgetVersion" to JsonPrimitive(widgetVersion)),
      )
    }
  return Json.encodeToString(
    JsonElement.serializer(),
    JsonObject(mapOf("format" to JsonPrimitive(INDEX_FORMAT), "entries" to JsonArray(entries))),
  )
}
