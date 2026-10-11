/*
 * W6: the relay as a report collector, for development. A production host
 * built with keliver.reportUrl / REPORT_URL = http(s)://<relay>/report POSTs one
 * JSON record per outcome; the relay keeps them under the store and shows
 * counts at GET /reports (JSON) and GET /reports/view (a page).
 *
 * The record is exactly the hosts' eight fields (KeliverReport.toJson):
 * installId, channel, hostVersion, sequence, source, outcome, reason, platform.
 * Anything else is refused, the free-text detail included, so this collector
 * cannot become a place where error text with addresses or paths piles up.
 * Storage is bounded: past MAX_LINES records, new ones are refused (507) until
 * the file is removed.
 */
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class HostReports(private val dir: File, private val maxLines: Int = MAX_LINES) {
  private val file get() = File(dir, "reports.jsonl")
  private val lock = Any()

  /** Stores one report; null when stored, else why not (and the HTTP status). */
  fun accept(body: String): Pair<Int, String>? {
    val r = parse(body).getOrElse { return 400 to (it.message ?: "not a report") }
    synchronized(lock) {
      dir.mkdirs()
      val lines = if (file.exists()) file.useLines { it.count() } else 0
      if (lines >= maxLines) return 507 to "the report store is full ($maxLines records): remove ${file.path}"
      file.appendText(r.toString() + "\n")
    }
    return null
  }

  /** Counts per platform, channel and sequence: distinct installs and outcomes; and the newest records. */
  fun summary(recent: Int = 20): JsonObject {
    val records = synchronized(lock) { if (file.exists()) file.readLines() else emptyList() }
      .mapNotNull { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }
    data class Key(val platform: String, val channel: String, val sequence: Long?)
    val groups = records.groupBy { Key(it.str("platform") ?: "", it.str("channel") ?: "", it.num("sequence")) }
    return buildJsonObject {
      put("total", records.size)
      put("installs", records.mapNotNull { it.str("installId") }.toSet().size)
      put("bundles", buildJsonArray {
        groups.entries.sortedWith(compareBy({ it.key.platform }, { it.key.channel }, { it.key.sequence ?: -1 })).forEach { (k, rs) ->
          add(buildJsonObject {
            put("platform", k.platform)
            put("channel", k.channel)
            put("sequence", k.sequence)
            put("installs", rs.mapNotNull { it.str("installId") }.toSet().size)
            put("outcomes", buildJsonObject {
              rs.groupingBy { it.str("outcome") ?: "" }.eachCount().toSortedMap().forEach { (o, n) -> put(o, n) }
            })
          })
        }
      })
      put("recent", JsonArray(records.takeLast(recent).reversed()))
    }
  }

  /** The summary as a small page: what the portal shows of the reports. */
  fun page(): String {
    val s = summary()
    fun esc(x: String) = x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val rows = (s["bundles"] as JsonArray).joinToString("") { b ->
      b as JsonObject
      val outcomes = (b["outcomes"] as JsonObject).entries.joinToString(", ") { (o, n) -> "${esc(o)} $n" }
      "<tr><td>${esc(b.str("platform") ?: "")}</td><td>${esc(b.str("channel") ?: "")}</td>" +
        "<td>${b.num("sequence") ?: "—"}</td><td>${b.num("installs")}</td><td>$outcomes</td></tr>"
    }
    return """<!doctype html><html><head><meta charset="utf-8"><title>Keliver host reports</title>
      |<style>body{font:14px system-ui,sans-serif;margin:24px}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:4px 8px;text-align:left}</style>
      |</head><body><h1>Host reports</h1><p>${s.num("total")} reports from ${s.num("installs")} installs.</p>
      |<table><tr><th>platform</th><th>channel</th><th>sequence</th><th>installs</th><th>outcomes</th></tr>$rows</table>
      |</body></html>""".trimMargin()
  }

  companion object {
    const val MAX_LINES = 20_000
    private val KEYS = setOf("installId", "channel", "hostVersion", "sequence", "source", "outcome", "reason", "platform")
    private val OUTCOMES = setOf("loaded", "fell-back", "update-applied", "update-failed", "not-loaded", "no-bundle", "refused")
    private val REASONS = setOf("", "sha256-mismatch", "below-floor", "signature", "lookup-failed", "network", "config", "other")
    private val INSTALL_ID = Regex("[A-Za-z0-9-]{1,64}")
    private val CHANNEL = Regex("[a-z0-9][a-z0-9-]{0,31}")

    /** The record, re-serialized with only its eight fields, or why it is not one. */
    fun parse(body: String): Result<JsonObject> = runCatching {
      val o = Json.parseToJsonElement(body) as? JsonObject ?: error("not a JSON object")
      require(o.keys == KEYS) { "a report has exactly the fields ${KEYS.sorted()} (got ${o.keys.sorted()})" }
      val installId = o.str("installId")
      require(installId != null && INSTALL_ID.matches(installId)) { "installId" }
      require(o.str("channel")?.let { CHANNEL.matches(it) } == true) { "channel" }
      require(o.isNumOrNull("hostVersion") && o.isNumOrNull("sequence")) { "hostVersion and sequence are numbers or null" }
      require(o["source"].let { it is JsonNull || o.str("source") in setOf("network", "cache") }) { "source" }
      require(o.str("outcome") in OUTCOMES) { "outcome" }
      require(o.str("reason") in REASONS) { "reason" }
      require(o.str("platform") in setOf("android", "ios")) { "platform" }
      JsonObject(KEYS.sorted().associateWith { o.getValue(it) })
    }

    /** POST /report, GET /reports, GET /reports/view. */
    fun register(server: HttpServer, reports: HostReports, wrap: (HttpExchange, () -> Unit) -> Unit) {
      server.createContext("/report") { ex ->
        wrap(ex) {
          if (ex.requestMethod != "POST") { send(ex, 405, null); return@wrap }
          val bytes = ex.requestBody.readNBytes(4097)
          if (bytes.size > 4096) { send(ex, 413, "{\"error\":\"a report is at most 4096 bytes\"}"); return@wrap }
          val refused = reports.accept(bytes.decodeToString())
          if (refused == null) send(ex, 204, null)
          else send(ex, refused.first, buildJsonObject { put("error", refused.second) }.toString())
        }
      }
      server.createContext("/reports") { ex ->
        wrap(ex) {
          when (ex.requestURI.path.trimEnd('/')) {
            "/reports" -> send(ex, 200, reports.summary().toString())
            "/reports/view" -> send(ex, 200, reports.page(), "text/html; charset=utf-8")
            else -> send(ex, 404, null)
          }
        }
      }
    }

    private fun send(ex: HttpExchange, code: Int, body: String?, contentType: String = "application/json") {
      if (body == null) { ex.sendResponseHeaders(code, -1); ex.close(); return }
      val bytes = body.encodeToByteArray()
      ex.responseHeaders.add("Content-Type", contentType)
      ex.sendResponseHeaders(code, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
  }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.num(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
private fun JsonObject.isNumOrNull(k: String): Boolean = this[k].let { it is JsonNull || num(k) != null }

