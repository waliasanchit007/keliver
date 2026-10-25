/*
 * Publishing, as the top bar says it (owner, 25 Oct): what a publish did, in one line — the full
 * build log stays one click away — and which bundle devices get now.
 */
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A publish's outcome in one line: the bundle [version] it stored, or why it stored none. */
internal data class PublishSummary(val version: Int?, val reason: String?) {
  val ok: Boolean get() = version != null
}

private val PUBLISHED = Regex("""publish OK: bundle v(\d+)""")
private val WHY_NOT = Regex("""(?i)(refused|failed|error|e: )""")

/** The summary of the relay's answer [text] to POST /publish ([ok]: it answered 200). */
internal fun publishSummary(ok: Boolean, text: String): PublishSummary {
  PUBLISHED.find(text)?.takeIf { ok }?.let { return PublishSummary(it.groupValues[1].toInt(), null) }
  val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
  val why = lines.firstOrNull { "REFUSED" in it }
    ?: lines.firstOrNull { WHY_NOT.containsMatchIn(it) && !it.startsWith(">") }
    ?: lines.lastOrNull()
  return PublishSummary(null, why?.removePrefix("publish ")?.take(240) ?: "the relay gave no reason")
}

/** The newest bundle in the relay's bundles/index.json [text]: its version and when it was made (ISO), or null. */
internal fun newestBundle(text: String): Pair<Int, String>? = runCatching {
  Json.parseToJsonElement(text).jsonObject["entries"]!!.jsonArray
    .map { it.jsonObject }
    .maxByOrNull { it["sequence"]!!.jsonPrimitive.content.toLong() }
    ?.let { it["version"]!!.jsonPrimitive.content.toInt() to it["createdAt"]!!.jsonPrimitive.content }
}.getOrNull()
