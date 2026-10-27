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

/**
 * The devices chip: the newest bundle ([newest], from bundles/index.json) and how many devices are
 * [listening] live (the relay's /devstate; null from a relay that does not say). Text, then tooltip.
 */
internal fun devicesChip(newest: Pair<Int, String>?, at: (String) -> String, listening: Int?): Pair<String, String> {
  val live = when {
    listening == null || listening <= 0 -> ""
    listening == 1 -> " · 1 phone live"
    else -> " · $listening phones live"
  }
  val liveTip = when {
    listening == null -> ""
    listening <= 0 -> " No phone is listening live: a debug app picks up a publish while it is open (keliver.updates=live, the debug default), otherwise when it next starts or resumes."
    listening == 1 -> " 1 phone is listening live: a publish appears on it while it is open."
    else -> " $listening phones are listening live: a publish appears on them while they are open."
  }
  if (newest == null) return "Not published yet$live" to "No bundle published from this app yet: devices have nothing to load.$liveTip"
  val (v, iso) = newest
  return "On devices: v$v · ${at(iso)}$live" to
    "The newest published bundle (v$v): devices running this app load it on their next update check.$liveTip"
}

/** The relay's /devstate [text]: how many devices are listening live, or null when it does not say. */
internal fun devicesListening(text: String): Int? = runCatching {
  Json.parseToJsonElement(text).jsonObject["devicesListening"]?.jsonPrimitive?.content?.toInt()
}.getOrNull()
