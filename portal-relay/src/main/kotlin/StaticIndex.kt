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

/**
 * B4 (docs/BRIDGE_CODEGEN_DESIGN.md): the `bridges` constraint an entry for [manifestJson]
 * carries, read from the manifest's SIGNED `keliver.bridges` (`ShopActions@2#<shape>,…`), so
 * the index never says other than what was signed. [constraint] is null when the bundle needs
 * no bridge; [problem] is set when the value is malformed. The same rules as the hosts'
 * `parseBridges` (BundleIndex.kt); BridgeGateTest holds the two together.
 */
internal data class SignedBridges(val constraint: JsonObject?, val problem: String?)

internal const val BRIDGES_METADATA_KEY = "keliver.bridges"
private val BRIDGE_ITEM_RE = Regex("([A-Za-z_][A-Za-z0-9_.]*)@([1-9][0-9]{0,8})(?:#([0-9a-f]{16}))?")

internal fun signedBridges(manifestJson: String): SignedBridges {
  val root = runCatching { Json.parseToJsonElement(manifestJson) as? JsonObject }.getOrNull()
    ?: return SignedBridges(null, "the manifest is not a JSON object")
  val v = (root["metadata"] as? JsonObject)?.get(BRIDGES_METADATA_KEY) ?: return SignedBridges(null, null)
  val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: return SignedBridges(null, "the manifest's $BRIDGES_METADATA_KEY is not a string")
  if (text.isEmpty()) return SignedBridges(null, null)
  val bridges = linkedMapOf<String, JsonElement>()
  for (item in text.split(',')) {
    val m = BRIDGE_ITEM_RE.matchEntire(item)
      ?: return SignedBridges(null, "the manifest's $BRIDGES_METADATA_KEY '$text' is not name@min[#shape],… (at '$item')")
    val (name, min, shape) = m.destructured
    val bridge = buildJsonObject {
      put("min", min.toInt())
      if (shape.isNotEmpty()) put("shape", shape)
    }
    if (bridges.put(name, bridge) != null) return SignedBridges(null, "the manifest's $BRIDGES_METADATA_KEY names $name twice")
  }
  return SignedBridges(JsonObject(mapOf("bridges" to JsonObject(bridges))), null)
}

/** [constraints] with `bridges` set from [signed] (and any copied `bridges` dropped); null when empty. */
internal fun withSignedBridges(constraints: JsonObject?, signed: SignedBridges): JsonObject? {
  // Every caller has refused a malformed value; never write an entry without its gate.
  check(signed.problem == null) { signed.problem!! }
  val merged = (constraints.orEmpty() - "bridges") + signed.constraint.orEmpty()
  return merged.takeIf { it.isNotEmpty() }?.let(::JsonObject)
}

/*
 * NF2 (docs/GUEST_PROFILE.md, "NF2: the Zipline fork"): QuickJS bytecode loads only on the engine
 * that compiled it. A bundle compiled for an engine other than [DEFAULT_ENGINE] names it in its
 * SIGNED metadata (`keliver.engine`, written by the signing block from the modules' bytecode), and
 * its index entry carries the same as the `engine` constraint, which hosts from before NF2 skip (an
 * unknown constraint key), so they only ever take a [DEFAULT_ENGINE] bundle. One release (one
 * sequence) may have one entry per engine, each with its own `v<N>/` and signed manifest; a host
 * takes the one for its engine (the template's BundleIndex.kt). The same rules as the hosts'
 * `engineProblem`; EngineGateTest holds the two together.
 */

/** The engine of a manifest that names none: every bundle published before NF2. */
internal const val DEFAULT_ENGINE = "quickjs-2021"
internal const val ENGINE_METADATA_KEY = "keliver.engine"

/**
 * The engines a bundle may be published for, with the QuickJS bytecode version each one's
 * compiler writes and its loader alone reads (1: Zipline 1.28.0's QuickJS 2021-03-27, built
 * without BigNum; 5: QuickJS 2026-06-04, the NF2 fork). In the order publishing lists them.
 */
internal val ENGINE_BYTECODE_VERSIONS: Map<String, Int> = linkedMapOf(DEFAULT_ENGINE to 1, "quickjs-2026" to 5)

/** A manifest's engine (its signed `keliver.engine`, else [DEFAULT_ENGINE]), or why it is unusable. */
internal data class SignedEngine(val engine: String, val problem: String?) {
  /** The index constraint an entry for this engine carries; null for [DEFAULT_ENGINE], which hosts from before NF2 must keep taking. */
  val constraint: JsonObject? get() = if (problem == null && engine != DEFAULT_ENGINE) JsonObject(mapOf("engine" to JsonPrimitive(engine))) else null
}

internal fun signedEngine(manifestJson: String): SignedEngine {
  val root = runCatching { Json.parseToJsonElement(manifestJson) as? JsonObject }.getOrNull()
    ?: return SignedEngine(DEFAULT_ENGINE, "the manifest is not a JSON object")
  val v = (root["metadata"] as? JsonObject)?.get(ENGINE_METADATA_KEY) ?: return SignedEngine(DEFAULT_ENGINE, null)
  val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: return SignedEngine(DEFAULT_ENGINE, "the manifest's $ENGINE_METADATA_KEY is not a string")
  if (text !in ENGINE_BYTECODE_VERSIONS) {
    return SignedEngine(text, "the manifest's $ENGINE_METADATA_KEY '$text' is not an engine this tool publishes for (${ENGINE_BYTECODE_VERSIONS.keys.joinToString()})")
  }
  return SignedEngine(text, null)
}

/** [constraints] with `engine` set from [signed] (and any copied `engine` dropped); null when empty. */
internal fun withSignedEngine(constraints: JsonObject?, signed: SignedEngine): JsonObject? {
  // Every caller has refused a malformed value; never write an entry without its gate.
  check(signed.problem == null) { signed.problem!! }
  val merged = (constraints.orEmpty() - "engine") + signed.constraint.orEmpty()
  return merged.takeIf { it.isNotEmpty() }?.let(::JsonObject)
}

private val ZIPLINE_MAGIC = "ZIPLINE\u0000".encodeToByteArray()
private const val ZIPLINE_SECTION_QUICKJS_BYTECODE = 1

/**
 * The QuickJS bytecode version in a `.zipline` file (Zipline's ZiplineFile: an 8-byte magic, the
 * file format's version, then sections of a 4-byte id and a 4-byte length; the bytecode section's
 * first byte is QuickJS's BC_VERSION), or null when [bytes] is not a Zipline file.
 */
internal fun ziplineBytecodeVersion(bytes: ByteArray): Int? {
  if (bytes.size < 12 || !bytes.copyOfRange(0, 8).contentEquals(ZIPLINE_MAGIC)) return null
  fun int(at: Int) = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
    ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
  var at = 12
  while (at + 8 <= bytes.size) {
    val id = int(at)
    val length = int(at + 4)
    if (length < 0 || at + 8 + length > bytes.size) return null
    if (id == ZIPLINE_SECTION_QUICKJS_BYTECODE) return if (length > 0) bytes[at + 8].toInt() and 0xff else null
    at += 8 + length
  }
  return null
}

/**
 * Why the bundle in [output] (its manifest [manifestJson], already checked complete) is not what
 * its signed engine says, or null: every module that is a Zipline file must hold the bytecode
 * version of the engine the manifest names ([DEFAULT_ENGINE] when it names none). A module that is
 * not a Zipline file loads on no engine, so it is not this check's to refuse.
 */
internal fun engineBytecodeProblem(output: File, manifestJson: String): String? {
  val signed = signedEngine(manifestJson)
  signed.problem?.let { return it }
  val expected = ENGINE_BYTECODE_VERSIONS.getValue(signed.engine)
  val modules = (runCatching { Json.parseToJsonElement(manifestJson) as JsonObject }.getOrNull()?.get("modules") as? JsonObject).orEmpty()
  for ((id, m) in modules) {
    val url = ((m as? JsonObject)?.get("url") as? JsonPrimitive)?.content ?: continue
    val file = File(output, url).takeIf { it.isFile } ?: continue
    val version = ziplineBytecodeVersion(file.readBytes()) ?: continue
    if (version != expected) {
      val compiledFor = ENGINE_BYTECODE_VERSIONS.entries.firstOrNull { it.value == version }?.key ?: "no engine this tool knows"
      val named = if (signedEngineNamed(manifestJson)) "names ${signed.engine}" else "names no engine (so ${signed.engine})"
      return "module $id holds QuickJS bytecode version $version ($compiledFor), but the signed manifest $named, " +
        "whose bytecode is version $expected: hosts would fetch code their engine cannot load. The signing block " +
        "writes $ENGINE_METADATA_KEY from the modules' bytecode: run keliver-new-publish-target.sh to update it, and build again"
    }
  }
  return null
}

private fun signedEngineNamed(manifestJson: String): Boolean =
  runCatching { ((Json.parseToJsonElement(manifestJson) as JsonObject)["metadata"] as? JsonObject)?.containsKey(ENGINE_METADATA_KEY) }.getOrNull() == true

/** An entry's engine, as hosts read it: its `engine` constraint (as written), else [DEFAULT_ENGINE]. */
internal fun entryEngine(e: JsonObject): String =
  ((e["constraints"] as? JsonObject)?.get("engine") as? JsonPrimitive)?.content ?: DEFAULT_ENGINE

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
 * 1. The manifest is signed so that a host trusting the key(s) in
 *    [publicKeyHex] loads it (the relay's own check, [checkSignatures]; W8:
 *    one key per line, at most two).
 * 2. Every module the manifest names is a file INSIDE [output] whose sha256 is
 *    the one the signed manifest records. Zipline checks this again on the
 *    device; checking it here stops an incomplete layout reaching a server.
 */
internal fun staticOutputProblem(output: File, publicKeyHex: String, authenticOnly: Boolean = false): String? {
  val manifestFile = File(output, "manifest.zipline.json")
  if (!manifestFile.isFile) return "no manifest.zipline.json in $output"
  val manifestJson = manifestFile.readText()
  checkSignatures(manifestJson, publicKeyHex, authenticOnly).problem?.let { return it }
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
  // more than once, but once per channel, and always for the same v<N>. NF2: a release may have one
  // entry per engine, each its own v<N>; so the same holds per (sequence, engine).
  val onChannel = HashSet<Triple<Long, String, String>>()
  val versionOf = HashMap<Pair<Long, String>, Int>()
  val sequenceOf = HashMap<Int, Long>()
  val engineOf = HashMap<Int, String>()
  val manifestOf = HashMap<Pair<Long, String>, Pair<JsonElement?, JsonElement?>>()
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
    val engine = entryEngine(o)
    val forEngine = if (engine == DEFAULT_ENGINE) "" else " for engine $engine"
    if (!onChannel.add(Triple(sequence, channel, engine))) {
      throw PublishRefused("$f has two entries with sequence $sequence on channel $channel$forEngine; it was left as it is")
    }
    versionOf.put(sequence to engine, version)?.takeIf { it != version }?.let {
      throw PublishRefused("$f has sequence $sequence$forEngine for both v$it and v$version; it was left as it is")
    }
    sequenceOf.put(version, sequence)?.takeIf { it != sequence }?.let {
      throw PublishRefused("$f has two entries for v$version, at sequences $it and $sequence; it was left as it is")
    }
    engineOf.put(version, engine)?.takeIf { it != engine }?.let {
      throw PublishRefused("$f has entries for v$version for both engine $it and $engine; it was left as it is")
    }
    val manifest = o["manifest"] to o["manifestSha256"]
    manifestOf.put(sequence to engine, manifest)?.takeIf { it != manifest }?.let {
      throw PublishRefused("$f has entries at sequence $sequence$forEngine naming different manifests; it was left as it is")
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
  // NF2: an entry serves only hosts of its engine, so only one for the same engine competes; its
  // engine constraint asks nothing more of those hosts.
  val otherGates = ((other["constraints"] as? JsonObject).orEmpty() - "engine")
  return (other["constraints"] == null || other["constraints"] is JsonObject) && otherGates.isEmpty() &&
    entryEngine(other) == entryEngine(source) && caps(source).containsAll(caps(other)) && wv(other) <= wv(source)
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

/** A publish; [also] is what was published with it for the release's other engines (NF2, a republish). */
internal data class StaticPublish(val version: Int, val sequence: Long, val dir: File, val entry: JsonObject, val also: List<StaticPublish> = emptyList())

/**
 * W8: a guard against signing with the wrong key by mistake. Null when the
 * bundle in [manifestJson] shares a signing key with the newest bundle already
 * published (the highest sequence in [index], read from its `v<N>/` in
 * [bundlesDir]), or when nothing was published before; otherwise why not. Only
 * keys listed in [publicKeysText] count. A rotation never trips it: hosts that
 * trust both keys ship first, then bundles are signed with both keys, then with
 * the new key only (docs/KEY_ROTATION.md). Jumping from one key straight to
 * another does, and `--accept-key-change` says that is meant.
 */
internal fun keyChangeProblem(bundlesDir: File, index: JsonObject, manifestJson: String, publicKeysText: String): String? {
  val newest = (index["entries"] as JsonArray).map { it as JsonObject }
    .maxByOrNull { (it["sequence"] as? JsonPrimitive)?.longOrNull ?: -1L } ?: return null
  val sequence = (newest["sequence"] as JsonPrimitive).content
  val version = (newest["version"] as? JsonPrimitive)?.intOrNull
  val previous = version?.let { File(File(bundlesDir, "v$it"), "manifest.zipline.json") }?.takeIf { it.isFile }
    ?: return "cannot tell which key signed the newest published bundle (sequence $sequence): its v<N>/manifest.zipline.json " +
      "is not in ${bundlesDir}. Download every served bundles/v<N>/ first, or, if a change of signing key is meant, " +
      "publish again with --accept-key-change."
  val before = checkSignatures(previous.readText(), publicKeysText, authenticOnly = true).signers
  val now = checkSignatures(manifestJson, publicKeysText).signers
  if (now.any { it in before }) return null
  fun names(keys: List<String>) = keys.joinToString(" and ") { keySignatureName(it) }.ifEmpty { "no key in this app's public key file" }
  return "this bundle is signed by ${names(now)}, but the newest published one (v$version, sequence " +
    "$sequence) is signed by ${names(before)}. Hosts that trust only the old key " +
    "would refuse it. Rotate in steps (docs/KEY_ROTATION.md): ship hosts that trust both keys, sign with both, then " +
    "with the new key only. If this change of signing key is meant, publish again with --accept-key-change."
}

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
  acceptKeyChange: Boolean = false, // W8: publish although no signing key is shared with the newest published bundle
  beforeLock: () -> Unit = {}, // a test hook: another publish landing between the checks and the lock
): StaticPublish = publishStaticVariants(
  listOf(PublishVariant(output, extraFields)), outDir, publicKeyHex, channel, capabilities, now, init, constraints,
  acceptKeyChange, beforeLock,
).single()

/** One engine's compile output for a release, and the fields its entry adds to (or replaces in) the usual ones. */
internal data class PublishVariant(val output: File, val extraFields: Map<String, JsonElement> = emptyMap())

/**
 * NF2: publish one release for one or more engines: each output in [variants] is a compile output
 * for one engine (its signed `keliver.engine`; [DEFAULT_ENGINE] when it names none), all signed for
 * the same sequence. Each goes to a `v<N>/` of its own, in the order given, and gets an entry at that
 * one sequence; an entry for an engine other than [DEFAULT_ENGINE] carries the `engine` constraint,
 * so hosts from before NF2 skip it and hosts of each engine take their own. Everything
 * [publishStatic] checks is checked for every output, and two outputs for the same engine are
 * refused; a refusal writes nothing. The index is written once, after every `v<N>/` is in place.
 */
internal fun publishStaticVariants(
  variants: List<PublishVariant>,
  outDir: File,
  publicKeyHex: String,
  channel: String = DEFAULT_CHANNEL,
  capabilities: List<String> = emptyList(),
  now: Instant = Instant.now(),
  init: Boolean = false,
  constraints: Constraints = Constraints(),
  acceptKeyChange: Boolean = false,
  beforeLock: () -> Unit = {},
): List<StaticPublish> {
  require(variants.isNotEmpty()) { "nothing to publish" }
  if (!CHANNEL_RE.matches(channel)) throw PublishRefused("channel '$channel' must match ${CHANNEL_RE.pattern}")
  constraints.problem?.let { throw PublishRefused(it) }
  for ((output, _) in variants) {
    if (outDir.canonicalFile.toPath().startsWith(output.canonicalFile.toPath())) {
      throw PublishRefused("--out $outDir is inside the compile output $output; choose a directory outside it")
    }
    staticOutputProblem(output, publicKeyHex)?.let { throw PublishRefused(it, aboutSigning = true) }
  }
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
  val firstSequence = nextSequence(readIndex(bundlesDir), bundlesDir)
  val seenEngines = HashSet<String>()
  for ((output, _) in variants) {
    val manifestText = File(output, "manifest.zipline.json").readText()
    sequenceProblem(manifestText, firstSequence)?.let { throw PublishRefused(it) }
    signedBridges(manifestText).problem?.let { throw PublishRefused(it) }
    engineBytecodeProblem(output, manifestText)?.let { throw PublishRefused(it) }
    val engine = signedEngine(manifestText).engine
    if (!seenEngines.add(engine)) throw PublishRefused("two of the outputs are for engine $engine: one release has one bundle per engine")
    if (!acceptKeyChange) keyChangeProblem(bundlesDir, readIndex(bundlesDir), manifestText, publicKeyHex)?.let { throw PublishRefused(it) }
  }
  beforeLock()

  return withPublishLock(bundlesDir) {
    val index = readIndex(bundlesDir) // again, under the lock
    val entries = (index["entries"] as JsonArray).map { it as JsonObject }
    val dirVersions = bundlesDir.listFiles { f -> f.isDirectory }.orEmpty()
      .mapNotNull { VERSION_DIR_RE.matchEntire(it.name)?.groupValues?.get(1)?.toInt() }
    val highest = (dirVersions + entries.map { (it["version"] as JsonPrimitive).intOrNull!! }).maxOrNull() ?: 0
    if (highest > MAX_VERSION - variants.size) throw PublishRefused("$bundlesDir already holds v$highest, the highest number this layout allows")
    val sequence = nextSequence(index, bundlesDir)

    val published = variants.mapIndexed { i, (output, extraFields) ->
      val version = highest + 1 + i
      val dest = File(bundlesDir, "v$version")
      val staging = File(bundlesDir, ".staging-${UUID.randomUUID()}")
      try {
        output.copyRecursively(staging, overwrite = false)
        // What was copied must still be what was verified.
        staticOutputProblem(staging, publicKeyHex)?.let {
          throw PublishRefused("the copy in $staging failed its check: $it", aboutSigning = true)
        }
        val copied = File(staging, "manifest.zipline.json").readText()
        // Under the lock: another publish may have taken this sequence since the build.
        sequenceProblem(copied, sequence)?.let { throw PublishRefused(it) }
        // And what was copied must still name well-formed bridges, and the engine it was compiled for.
        signedBridges(copied).problem?.let { throw PublishRefused(it) }
        engineBytecodeProblem(staging, copied)?.let { throw PublishRefused(it) }
        if (!acceptKeyChange) keyChangeProblem(bundlesDir, index, copied, publicKeyHex)?.let { throw PublishRefused(it) }
        Files.move(staging.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
      } finally {
        if (staging.exists()) staging.deleteRecursively()
      }

      val manifestText = File(dest, "manifest.zipline.json").readText()
      val manifestSha = sha256Hex(File(dest, "manifest.zipline.json").readBytes())
      // B4: what the signed manifest says it needs, over any copied (republish) bridges; NF2: and
      // the engine it names.
      val entryConstraints = withSignedEngine(
        withSignedBridges(constraints.over(extraFields["constraints"]), signedBridges(manifestText)),
        signedEngine(manifestText),
      )
      val entry = JsonObject(
        entryJson(sequence, version, channel, capabilities, manifestSha, now) + extraFields - "constraints" +
          listOfNotNull(entryConstraints?.let { "constraints" to it }),
      )
      StaticPublish(version, sequence, dest, entry)
    }
    val next = JsonObject(index + ("entries" to JsonArray(entries + published.map { it.entry })))
    writeAtomically(File(bundlesDir, INDEX_FILE), prettyJson.encodeToString(JsonElement.serializer(), next) + "\n")
    published
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

/** A promotion; [entry] is the first engine's new entry, [also] the other engines' (NF2), in index order. */
internal data class StaticPromotion(val sequence: Long, val version: Int, val from: String, val entry: JsonObject, val also: List<JsonObject> = emptyList())

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
  // NF2: a release may have one entry per engine at [sequence]; each engine's is promoted, together.
  fun check(index: JsonObject): List<JsonObject> {
    val entries = (index["entries"] as JsonArray).map { it as JsonObject }
    val atSequence = entries.filter { (it["sequence"] as JsonPrimitive).longOrNull == sequence }
    if (atSequence.isEmpty()) throw PublishRefused("${File(bundlesDir, INDEX_FILE)} has no entry at sequence $sequence")
    if (atSequence.any { entryChannel(it) == channel }) throw PublishRefused("sequence $sequence is already on channel $channel")
    val sources = atSequence.groupBy(::entryEngine).values.map { it.first() }
    for (source in sources) {
      val forEngine = entryEngine(source).takeIf { it != DEFAULT_ENGINE }?.let { " (engine $it)" }.orEmpty()
      entryShapeProblem(source)?.let { throw PublishRefused("the index entry at sequence $sequence$forEngine $it; it was left as it is") }
      constraints.loosening(source["constraints"])?.let { throw PublishRefused(it) }
      // Hosts on [channel] see its entries and stable's. A higher one that every host able
      // to run this bundle can also run (no constraints, nothing more required) means none
      // of them would pick this one. A higher entry that some of them skip does not.
      entries.filter { (entryChannel(it) == channel || entryChannel(it) == DEFAULT_CHANNEL) && entryShapeProblem(it) == null }
        .filter { (it["sequence"] as JsonPrimitive).longOrNull!! > sequence && noStricterThan(it, source) }
        .maxOfOrNull { (it["sequence"] as JsonPrimitive).longOrNull!! }
        ?.let {
          throw PublishRefused(
            "hosts on channel $channel$forEngine already take sequence $it, above $sequence and asking no more of them, so none " +
              "would pick this one. To put older code back, republish it (--republish) as a new sequence.",
          )
        }
      if ((source["manifest"] as? JsonPrimitive)?.content != "v${(source["version"] as JsonPrimitive).content}/manifest.zipline.json") {
        throw PublishRefused("the entry at sequence $sequence$forEngine names ${source["manifest"]}, not its own v<N>/manifest.zipline.json")
      }
      val version = (source["version"] as JsonPrimitive).intOrNull!!
      val dir = File(bundlesDir, "v$version")
      val manifest = File(dir, "manifest.zipline.json")
      val expected = (source["manifestSha256"] as? JsonPrimitive)?.content?.lowercase()
      if (!manifest.isFile || sha256Hex(manifest.readBytes()) != expected) {
        throw PublishRefused("$manifest is not the manifest the entry at sequence $sequence$forEngine names (manifestSha256)")
      }
      staticOutputProblem(dir, publicKeyHex)?.let { throw PublishRefused("v$version: $it", aboutSigning = true) }
      signedBridges(manifest.readText()).problem?.let { throw PublishRefused("v$version: $it") }
      signedEngine(manifest.readText()).problem?.let { throw PublishRefused("v$version: $it") }
      if (signedEngine(manifest.readText()).engine != entryEngine(source)) {
        throw PublishRefused("v$version's signed manifest is for engine ${signedEngine(manifest.readText()).engine}, but its entry says ${entryEngine(source)}; it was left as it is")
      }
    }
    return sources
  }
  check(readIndex(bundlesDir)) // before the lock: a refusal writes nothing, not even the lock file
  return withPublishLock(bundlesDir) {
    val index = readIndex(bundlesDir)
    val sources = check(index) // again, under the lock
    val promoted = sources.map { source ->
      val manifestText = File(bundlesDir, "v${(source["version"] as JsonPrimitive).content}/manifest.zipline.json").readText()
      JsonObject(
        source - "constraints" + mapOf(
          "channel" to JsonPrimitive(channel),
          "promotedFrom" to JsonPrimitive(entryChannel(source)),
          "createdAt" to JsonPrimitive(now.toString()),
        ) + listOfNotNull(
          // B4: derived from the signed manifest, so an entry from before B4 gains its bridges; NF2: and its engine.
          withSignedEngine(withSignedBridges(constraints.over(source["constraints"]), signedBridges(manifestText)), signedEngine(manifestText))
            ?.let { "constraints" to it },
        ),
      )
    }
    val next = JsonObject(index + ("entries" to JsonArray((index["entries"] as JsonArray) + promoted)))
    writeAtomically(File(bundlesDir, INDEX_FILE), prettyJson.encodeToString(JsonElement.serializer(), next) + "\n")
    val first = sources.first()
    StaticPromotion(sequence, (first["version"] as JsonPrimitive).intOrNull!!, entryChannel(first), promoted.first(), promoted.drop(1))
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
  // NF2: a release may have one entry per engine on a channel; the rollout is set on each (the same buckets).
  fun target(index: JsonObject): List<Int> {
    val entries = (index["entries"] as JsonArray).map { it as JsonObject }
    val at = entries.withIndex().filter { (_, e) -> (e["sequence"] as JsonPrimitive).longOrNull == sequence }
    if (at.isEmpty()) throw PublishRefused("${File(bundlesDir, INDEX_FILE)} has no entry at sequence $sequence")
    val channels = at.map { entryChannel(it.value) }.distinct()
    val chosen = when {
      channel != null -> at.filter { (_, e) -> entryChannel(e) == channel }.ifEmpty { null }
        ?: throw PublishRefused("sequence $sequence is not on channel $channel")
      channels.size == 1 -> at
      else -> throw PublishRefused("sequence $sequence is on channels ${channels.joinToString()}: name one with --channel")
    }
    for ((_, e) in chosen) {
      entryShapeProblem(e)?.let { throw PublishRefused("the index entry at sequence $sequence $it; it was left as it is") }
      // Hosts from before W4.5 skip any entry with constraints. Giving a live, unconstrained
      // entry some would hide it from them (and their floor would then refuse the older one).
      if (e["constraints"] == null) {
        throw PublishRefused(
          "the entry at sequence $sequence has no constraints, and hosts from before staged rollouts skip any entry " +
            "that has some: adding a rollout now would hide a bundle they may already run. Stage a rollout when " +
            "publishing (--rollout), or republish with --rollout.",
        )
      }
    }
    return chosen.map { it.index }
  }
  target(readIndex(bundlesDir)) // before the lock: a refusal writes nothing
  return withPublishLock(bundlesDir) {
    val index = readIndex(bundlesDir)
    val targets = target(index)
    val entries = (index["entries"] as JsonArray).map { it as JsonObject }.toMutableList()
    for (i in targets) {
      entries[i] = JsonObject(entries[i] - "constraints" + ("constraints" to Constraints(rollout = rollout).over(entries[i]["constraints"])!!))
    }
    val i = targets.first()
    val old = (index["entries"] as JsonArray)[i] as JsonObject
    val entry = entries[i]
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
 * The start policies hosts know (the host templates' StartPolicy.kt, KeliverStartPolicy; a test
 * holds the two lists equal), by the names the index carries.
 */
internal val START_POLICIES = listOf("network-first", "parallel", "cache-first")

/** The index's top-level key for the per-channel start policy override. */
internal const val START_POLICY_KEY = "startPolicy"

/** What `--set-start-policy` changed: on [channel], from [from] to [to] (null: none, the app's own setting). */
internal data class StaticStartPolicy(val channel: String, val from: String?, val to: String?)

/** The index's start policy overrides, by channel; refuses a malformed value (hosts would ignore it). */
internal fun startPolicies(index: JsonObject): Map<String, String> {
  val o = index[START_POLICY_KEY] ?: return emptyMap()
  val byChannel = o as? JsonObject ?: throw PublishRefused("the index's $START_POLICY_KEY is not an object; it was left as it is")
  return byChannel.mapValues { (c, v) ->
    (v as? JsonPrimitive)?.takeIf { it.isString && CHANNEL_RE.matches(c) }?.content
      ?: throw PublishRefused("the index's $START_POLICY_KEY has a malformed entry for '$c' ($v); it was left as it is")
  }
}

/**
 * Sets the release owner's start policy override for [channel] (docs/DEVICE_HOST.md, "Start
 * policy"): [policy] is one of [START_POLICIES], or null to remove it (hosts then use their app's
 * own setting). Hosts read it from the next index they read and apply it from their NEXT start; a
 * host on another channel without its own value takes stable's. Only the index changes, under the
 * publish lock: the top-level `startPolicy` object, never an entry, so it changes no pick. Nothing
 * is built or signed, and no key is needed. The index is not signed (as for rollouts), so the
 * override is not either: it can only choose among orders that all keep every check.
 */
internal fun setStartPolicyStatic(outDir: File, policy: String?, channel: String = DEFAULT_CHANNEL): StaticStartPolicy {
  if (policy != null && policy !in START_POLICIES) {
    throw PublishRefused("start policy '$policy' is not one of ${START_POLICIES.joinToString()} (or default, to remove it)")
  }
  if (!CHANNEL_RE.matches(channel)) throw PublishRefused("channel '$channel' must match ${CHANNEL_RE.pattern}")
  val bundlesDir = File(outDir, "bundles")
  if (!File(bundlesDir, INDEX_FILE).exists()) {
    throw PublishRefused("there is no ${File(bundlesDir, INDEX_FILE)} to change: download the served bundles/ first")
  }
  startPolicies(readIndex(bundlesDir)) // before the lock: a refusal writes nothing
  return withPublishLock(bundlesDir) {
    val index = readIndex(bundlesDir)
    val before = startPolicies(index)
    val after = (if (policy == null) before - channel else before + (channel to policy)).toSortedMap()
    val next = if (after.isEmpty()) {
      JsonObject(index - START_POLICY_KEY)
    } else {
      JsonObject(index + (START_POLICY_KEY to JsonObject(after.mapValues { JsonPrimitive(it.value) })))
    }
    writeAtomically(File(bundlesDir, INDEX_FILE), prettyJson.encodeToString(JsonElement.serializer(), next) + "\n")
    StaticStartPolicy(channel, before[channel], policy)
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
  acceptKeyChange: Boolean = false, // W8, as for publishStatic
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
  val entryChannel = channel ?: (original["channel"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: DEFAULT_CHANNEL
  // NF2: v[version] is one engine's bundle of its release; the release's other engines' bundles
  // (the entries at its sequence for other engines) are republished with it, at the same new
  // sequence, so no engine's hosts are left on the code being rolled back.
  val originalSequence = (original["sequence"] as JsonPrimitive).longOrNull
  val siblings = (index["entries"] as JsonArray).map { it as JsonObject }
    .filter { (it["sequence"] as JsonPrimitive).longOrNull == originalSequence && entryEngine(it) != entryEngine(original) }
    .groupBy(::entryEngine).values.map { forEngine -> forEngine.firstOrNull { entryChannel(it) == entryChannel(original) } ?: forEngine.first() }
  val variants = (listOf(original) + siblings).map { e ->
    val v = (e["version"] as JsonPrimitive).intOrNull!!
    entryShapeProblem(e)?.let { throw PublishRefused("the index entry for v$v $it; it was left as it is") }
    constraints.loosening(e["constraints"])?.let { throw PublishRefused(it) }
    val source = File(bundlesDir, "v$v")
    val sourceManifest = File(source, "manifest.zipline.json")
    val expectedSha = (e["manifestSha256"] as? JsonPrimitive)?.content?.lowercase()
    if (!sourceManifest.isFile || sha256Hex(sourceManifest.readBytes()) != expectedSha) {
      throw PublishRefused("$sourceManifest is not the manifest the index entry for v$v names (manifestSha256); it was left as it is")
    }
    // Only whether a listed key signed it: the copy is signed again, and checked as a new publish.
    staticOutputProblem(source, publicKeyHex, authenticOnly = true)?.let { throw PublishRefused("v$v: $it", aboutSigning = true) }
    // B4: before the re-sign build, not after it (a version stored before B4 may be malformed).
    signedBridges(sourceManifest.readText()).problem?.let { throw PublishRefused("v$v: $it") }
    signedEngine(sourceManifest.readText()).problem?.let { throw PublishRefused("v$v: $it") }
    val extra = buildMap<String, JsonElement> {
      put("widgetVersion", e["widgetVersion"]!!)
      put("capabilities", e["capabilities"]!!)
      // Host-version gates are about the code, so they are copied. A rollout is not: a new
      // sequence draws new buckets, so a copied 10% would be another 10%, and a copied halt
      // would keep the rollback from the hosts that need it. Pass --rollout to stage it.
      constraints.over((e["constraints"] as? JsonObject)?.let { JsonObject(it - "rollout") })?.let { put("constraints", it) }
      put("republishOf", JsonPrimitive(v))
    }
    Triple(v, source, extra)
  }
  val capabilities = (original["capabilities"] as JsonArray).map { (it as JsonPrimitive).content }

  val scratch = Files.createTempDirectory("keliver-republish-").toFile()
  try {
    val sequence = nextSequence(index, bundlesDir)
    val copies = variants.map { (v, source, extra) ->
      val copy = File(scratch, "v$v")
      source.copyRecursively(copy, overwrite = false)
      val code = resign(copy, sequence)
      if (code != 0) throw BuildFailed(code)
      republishProblem(File(source, "manifest.zipline.json").readText(), File(copy, "manifest.zipline.json").readText())?.let { throw PublishRefused(it) }
      PublishVariant(copy, extra)
    }
    try {
      val published = publishStaticVariants(copies, outDir, publicKeyHex, entryChannel, capabilities, now, acceptKeyChange = acceptKeyChange)
      return published.first().copy(also = published.drop(1))
    } catch (e: PublishRefused) {
      // Another publish took this sequence while the re-sign ran: the advice is not "build again".
      val signedFor = signedSequenceText(File(copies.first().output, "manifest.zipline.json").readText())
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
      val bytes = manifest.readBytes()
      // B4: a malformed keliver.bridges leaves the version out (fail closed).
      val bridges = signedBridges(bytes.decodeToString()).takeIf { it.problem == null } ?: return@mapNotNull null
      // NF2: likewise a malformed keliver.engine; a bundle for another engine carries the `engine` constraint.
      val engine = signedEngine(bytes.decodeToString()).takeIf { it.problem == null } ?: return@mapNotNull null
      JsonObject(
        entryJson(version.toLong(), version, DEFAULT_CHANNEL, caps, sha256Hex(bytes), created) +
          ("widgetVersion" to JsonPrimitive(widgetVersion)) +
          listOfNotNull(withSignedEngine(bridges.constraint, engine)?.let { "constraints" to it }),
      )
    }
  return Json.encodeToString(
    JsonElement.serializer(),
    JsonObject(mapOf("format" to JsonPrimitive(INDEX_FORMAT), "entries" to JsonArray(entries))),
  )
}

/**
 * The relay's legacy `/bundles/latest` (hosts from tools 0.3.7 or earlier): the newest `v<N>/`
 * whose meta.json's widget version is at most [widgetVersion] and whose capabilities [hostCaps]
 * holds. B4: such a host serves no bridges, and this answer has no constraints to skip by, so a
 * bundle whose manifest names any bridge (or a malformed value) is never the answer.
 */
internal fun legacyLatest(bundlesDir: File, widgetVersion: Int, hostCaps: Set<String>): File? =
  bundlesDir.listFiles { f -> f.isDirectory && f.name.startsWith("v") }
    ?.filter { dir ->
      val metaText = File(dir, "meta.json").takeIf { it.exists() }?.readText() ?: ""
      val recorded = Regex("\"widgetVersion\":(\\d+)").find(metaText)
        ?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
      val required = Regex("\"capabilities\":\\[([^\\]]*)]").find(metaText)
        ?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
        ?: emptyList()
      val manifestText = File(dir, "manifest.zipline.json").takeIf { it.isFile }?.readText()
      val signed = manifestText?.let { signedBridges(it) }
      // NF2: such a host runs DEFAULT_ENGINE, so never a bundle for another (or a malformed value).
      val engine = manifestText?.let { signedEngine(it) }
      recorded <= widgetVersion && hostCaps.containsAll(required) && signed != null &&
        signed.problem == null && signed.constraint == null &&
        engine != null && engine.problem == null && engine.engine == DEFAULT_ENGINE
    }
    ?.maxByOrNull { it.name.removePrefix("v").toIntOrNull() ?: 0 }
