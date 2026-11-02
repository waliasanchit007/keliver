import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class HostReportsTest {
  /** What a host's KeliverReport.toJson() sends (the same eight fields, in its order). */
  private fun report(
    outcome: String = "loaded",
    sequence: String = "8",
    platform: String = "android",
    installId: String = "4f0c2a8e-1b2c-4d5e-8f90-0123456789ab",
    extra: String = "",
  ) = """{"installId":"$installId","channel":"stable","hostVersion":1,"sequence":$sequence,"source":"network",""" +
    """"outcome":"$outcome","reason":"","platform":"$platform"$extra}"""

  private fun dir(): File = Files.createTempDirectory("host-reports").toFile()

  @Test
  fun aHostReportIsStoredAndCounted() {
    val r = HostReports(dir())
    assertNull(r.accept(report()))
    assertNull(r.accept(report(outcome = "update-applied", sequence = "9")))
    assertNull(r.accept(report(platform = "ios", installId = "B2D1")))
    val s = r.summary()
    assertEquals(3, s["total"]!!.jsonPrimitive.int)
    assertEquals(2, s["installs"]!!.jsonPrimitive.int)
    val bundles = (s["bundles"] as JsonArray).map { it as JsonObject }
    val android8 = bundles.single { it["platform"]!!.jsonPrimitive.content == "android" && it["sequence"]!!.jsonPrimitive.int == 8 }
    assertEquals(1, (android8["outcomes"] as JsonObject)["loaded"]!!.jsonPrimitive.int)
    assertTrue("update-applied" in r.page() && "<table>" in r.page())
  }

  @Test
  fun anythingButTheEightFieldsIsRefused() {
    val r = HostReports(dir())
    // The free-text detail never piles up here (W6 review).
    assertEquals(400, r.accept(report(extra = ""","detail":"failed to connect to /10.0.2.16"""))!!.first)
    assertEquals(400, r.accept(report(outcome = "pwned"))!!.first)
    assertEquals(400, r.accept(report(platform = "web"))!!.first)
    assertEquals(400, r.accept(report(installId = "../../etc"))!!.first)
    assertEquals(400, r.accept(report(sequence = "\"8\""))!!.first)
    assertEquals(400, r.accept("[]")!!.first)
    assertEquals(400, r.accept("not json")!!.first)
    assertEquals(0, r.summary()["total"]!!.jsonPrimitive.int)
    // A null sequence (no bundle) is a report.
    assertNull(r.accept(report(outcome = "no-bundle", sequence = "null")))
  }

  @Test
  fun theStoreIsBounded() {
    val r = HostReports(dir(), maxLines = 2)
    assertNull(r.accept(report()))
    assertNull(r.accept(report()))
    val full = r.accept(report())
    assertNotNull(full)
    assertEquals(507, full.first)
  }

  @Test
  fun overHttp() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val r = HostReports(dir())
    HostReports.register(server, r) { _, block -> block() }
    server.start()
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
        val c = URI("$base$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) {
          c.doOutput = true
          c.setRequestProperty("Content-Type", "application/json")
          c.outputStream.use { it.write(body.encodeToByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
        return code to text
      }
      assertEquals(204, call("POST", "/report", report()).first)
      assertEquals(400, call("POST", "/report", report(extra = ""","detail":"x"""")).first)
      assertEquals(413, call("POST", "/report", "x".repeat(5000)).first)
      assertEquals(405, call("GET", "/report").first)
      val (code, json) = call("GET", "/reports")
      assertEquals(200, code)
      assertTrue("\"total\":1" in json, json)
      assertTrue("Host reports" in call("GET", "/reports/view").second)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun everyOutcomeAndReasonAHostSendsIsAccepted() {
    // The hosts' reportReason (BundleIndex.kt, compiled into these tests) against HostReports' sets.
    val details = listOf(
      "", "manifest sha256 mismatch: x", "rollback refused: sequence 1 is below 2",
      "bridge refused: the bundle needs A@1, which this host does not serve",
      "bridge refused: the bundle was built against another A@1 contract (shape a; this host's is b)",
      "manifest signature for key k did not verify", "lookup failed", "timeout", "something else",
    )
    val outcomes = listOf("loaded", "fell-back", "update-applied", "update-failed", "not-loaded", "no-bundle", "refused", "screen-failed")
    val r = HostReports(dir())
    val produced = mutableSetOf<String>()
    for (outcome in outcomes) {
      for (d in details) {
        val reason = hosttemplate.reportReason(outcome, d)
        produced += reason
        val body = report(outcome = outcome).replace("\"reason\":\"\"", "\"reason\":\"$reason\"")
        assertNull(r.accept(body), "$outcome / $reason")
      }
    }
    // Every category the hosts send was produced above, so each was checked.
    assertEquals(
      setOf("", "sha256-mismatch", "below-floor", "bridge-missing", "bridge-shape", "signature", "lookup-failed", "network", "config", "exception", "other"),
      produced,
    )
  }
}
