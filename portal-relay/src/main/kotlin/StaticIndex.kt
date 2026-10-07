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

/** A publish that was refused before anything was written. */
internal class PublishRefused(message: String) : Exception(message)

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
  entries.forEachIndexed { i, e ->
    val o = e as? JsonObject ?: throw PublishRefused("$f entry $i is not an object")
    if ((o["sequence"] as? JsonPrimitive)?.longOrNull == null || (o["version"] as? JsonPrimitive)?.intOrNull == null) {
      throw PublishRefused("$f entry $i has no integer sequence and version; it was left as it is")
    }
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
 * index can't be read, or when another publish holds the lock.
 */
internal fun publishStatic(
  output: File,
  outDir: File,
  publicKeyHex: String,
  channel: String = DEFAULT_CHANNEL,
  capabilities: List<String> = emptyList(),
  now: Instant = Instant.now(),
): StaticPublish {
  if (!CHANNEL_RE.matches(channel)) throw PublishRefused("channel '$channel' must match ${CHANNEL_RE.pattern}")
  staticOutputProblem(output, publicKeyHex)?.let { throw PublishRefused(it) }
  val bundlesDir = File(outDir, "bundles")
  // Read (and so validate) the index before creating anything.
  readIndex(bundlesDir)

  bundlesDir.mkdirs()
  FileChannel.open(File(bundlesDir, ".publish.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
    val lock = ch.tryLock() ?: throw PublishRefused("another keliver-publish is writing $bundlesDir")
    try {
      val index = readIndex(bundlesDir) // again, under the lock
      val entries = (index["entries"] as JsonArray).map { it as JsonObject }
      val dirVersions = bundlesDir.listFiles { f -> f.isDirectory }.orEmpty()
        .mapNotNull { VERSION_DIR_RE.matchEntire(it.name)?.groupValues?.get(1)?.toInt() }
      val version = (dirVersions + entries.map { (it["version"] as JsonPrimitive).intOrNull!! }).maxOrNull()?.plus(1) ?: 1
      val sequence = (entries.maxOfOrNull { (it["sequence"] as JsonPrimitive).longOrNull!! } ?: 0L) + 1

      val dest = File(bundlesDir, "v$version")
      val staging = File(bundlesDir, ".staging-${UUID.randomUUID()}")
      try {
        output.copyRecursively(staging, overwrite = false)
        // What was copied must still be what was verified.
        staticOutputProblem(staging, publicKeyHex)?.let { throw PublishRefused("the copy in $staging failed its check: $it") }
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
 */
internal fun relayIndexJson(bundlesDir: File): String {
  val entries = bundlesDir.listFiles { f -> f.isDirectory }.orEmpty()
    .mapNotNull { dir -> VERSION_DIR_RE.matchEntire(dir.name)?.groupValues?.get(1)?.toInt()?.let { it to dir } }
    .sortedBy { it.first }
    .mapNotNull { (version, dir) ->
      val manifest = File(dir, "manifest.zipline.json").takeIf { it.isFile } ?: return@mapNotNull null
      val meta = runCatching { Json.parseToJsonElement(File(dir, "meta.json").readText()) as JsonObject }.getOrNull()
      val caps = (meta?.get("capabilities") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
      val created = (meta?.get("createdAt") as? JsonPrimitive)?.longOrNull?.let(Instant::ofEpochMilli) ?: Instant.EPOCH
      val widgetVersion = (meta?.get("widgetVersion") as? JsonPrimitive)?.intOrNull ?: PUBLISHED_WIDGET_VERSION
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
