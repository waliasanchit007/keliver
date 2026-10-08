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
 * Ed25519 signature; hosts refuse a sequence below the highest they have run.
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

/** The sequence the next entry of [index] gets. */
internal fun nextSequence(index: JsonObject): Long =
  ((index["entries"] as JsonArray).maxOfOrNull { ((it as JsonObject)["sequence"] as JsonPrimitive).longOrNull!! } ?: 0L) + 1

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
  val sequences = HashSet<Long>()
  val versions = HashSet<Int>()
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
    if (!sequences.add(sequence)) throw PublishRefused("$f has two entries with sequence $sequence; it was left as it is")
    if (!versions.add(version)) throw PublishRefused("$f has two entries for v$version; it was left as it is")
  }
  return root
}

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
): StaticPublish {
  if (!CHANNEL_RE.matches(channel)) throw PublishRefused("channel '$channel' must match ${CHANNEL_RE.pattern}")
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
  sequenceProblem(manifestText, nextSequence(readIndex(bundlesDir)))
    ?.let { throw PublishRefused(it, aboutSigning = signedSequenceText(manifestText) == null) }

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
      val index = readIndex(bundlesDir) // again, under the lock
      val entries = (index["entries"] as JsonArray).map { it as JsonObject }
      val dirVersions = bundlesDir.listFiles { f -> f.isDirectory }.orEmpty()
        .mapNotNull { VERSION_DIR_RE.matchEntire(it.name)?.groupValues?.get(1)?.toInt() }
      val highest = (dirVersions + entries.map { (it["version"] as JsonPrimitive).intOrNull!! }).maxOrNull() ?: 0
      if (highest >= MAX_VERSION) throw PublishRefused("$bundlesDir already holds v$highest, the highest number this layout allows")
      val version = highest + 1
      val sequence = nextSequence(index)

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
      val entry = entryJson(sequence, version, channel, capabilities, manifestSha, now)
      val next = JsonObject(index + ("entries" to JsonArray(entries + entry)))
      writeAtomically(File(bundlesDir, INDEX_FILE), prettyJson.encodeToString(JsonElement.serializer(), next) + "\n")
      return StaticPublish(version, sequence, dest, entry)
    } finally {
      lock.release()
    }
  }
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
