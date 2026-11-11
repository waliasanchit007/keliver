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

/** ED3: a bundle server URL a debug host can use (the relay's device proxy), and its QR code as an SVG. */
internal data class DeviceLink(val url: String, val qrSvg: String?)

/** The QR codes the relay makes (QrCode.toSvg): one rect and one path of plain moves. Anything else is not inserted. */
private val QR_SVG = Regex(
  """^<svg xmlns="http://www\.w3\.org/2000/svg" viewBox="0 0 \d+ \d+" shape-rendering="crispEdges"><rect width="\d+" height="\d+" fill="#fff"/><path d="[Mhvz0-9,\-]*" fill="#000"/></svg>$""",
)
private val PROXY_URL = Regex("""^http://(\d{1,3}(\.\d{1,3}){3}|\[[0-9a-fA-F:]+]):\d{1,5}$""")

/**
 * The relay's `GET /device-proxy` [text]: the links (an empty list: the proxy is off), or null
 * from a relay that has no device proxy. A URL or QR code not of the shape the relay makes is dropped.
 */
internal fun deviceLinks(text: String): List<DeviceLink>? = runCatching {
  val o = Json.parseToJsonElement(text).jsonObject
  val enabled = o["enabled"]?.jsonPrimitive?.content ?: return@runCatching null
  if (enabled != "true") return@runCatching emptyList()
  o["urls"]!!.jsonArray.mapNotNull { e ->
    val item = e.jsonObject
    val url = item["url"]?.jsonPrimitive?.content?.takeIf { PROXY_URL.matches(it) } ?: return@mapNotNull null
    DeviceLink(url, item["qrSvg"]?.jsonPrimitive?.content?.takeIf { QR_SVG.matches(it) })
  }
}.getOrNull()

/** How a debug Android host is pointed at [url] (the host template's debugBundleServer); then restart the app. */
internal fun debugHostCommand(url: String): String =
  "adb shell run-as <applicationId> sh -c 'mkdir -p files/keliver && echo $url > files/keliver/bundle-server'"
