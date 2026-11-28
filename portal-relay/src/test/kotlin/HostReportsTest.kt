/*
 * Copyright (C) 2026 Keliver contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
      "bridge bind failed: A bound [] (it must bind A, once)",
      // NF2: a manifest for another engine.
      "engine refused: the bundle is compiled for quickjs-2026; this host runs quickjs-2021",
      "manifest signature for key k did not verify", "lookup failed", "timeout", "something else",
      // WS6: a set-aside manifest refused, and what runs in place of the set-aside bundle.
      "set aside: sequence 5 failed 2 times on this install (crash rollback); only a newer one runs",
      hosttemplate.crashRollbackDetail(crashStep, 2, hosttemplate.crashGoingBackTo(4)),
      hosttemplate.crashRollbackDetail(crashStep, 2, hosttemplate.CRASH_TO_NEWER),
      hosttemplate.crashRollbackDetail(crashStep, 2, hosttemplate.CRASH_TO_APP_VIEW),
    )
    val outcomes = listOf(
      "loaded", "fell-back", "update-applied", "update-failed", "not-loaded", "no-bundle", "refused", "screen-failed", "crash-rollback",
      // MS4-04: with the detail the host's listener gives it.
      hosttemplate.SCHEMA_MISMATCH_OUTCOME,
    )
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
      setOf(
        "", "sha256-mismatch", "below-floor", "set-aside", "bridge-missing", "bridge-shape", "bridge-bind", "engine", "signature", "lookup-failed",
        "network", "config", "exception", "previous-good", "newer-bundle", "app-view", "schema-mismatch", "other",
      ),
      produced,
    )
  }

  /** MS4-04: a host's report that a bundle used a tag the host lacks, once per tag. */
  @Test
  fun aSchemaMismatchIsAcceptedAndShownWithItsReason() {
    val r = HostReports(dir())
    val reason = hosttemplate.reportReason(hosttemplate.SCHEMA_MISMATCH_OUTCOME, "unknown modifier 777000001")
    assertEquals("schema-mismatch", reason)
    val mismatch = report(outcome = hosttemplate.SCHEMA_MISMATCH_OUTCOME, sequence = "3").replace("\"reason\":\"\"", "\"reason\":\"$reason\"")
    assertNull(r.accept(report(sequence = "3")))
    assertNull(r.accept(mismatch))
    // Only the category leaves the device: the tag stays in the app's detail.
    assertEquals(400, r.accept(mismatch.replace("\"reason\":\"schema-mismatch\"", "\"reason\":\"unknown modifier 777000001\""))!!.first)

    val bundle = (r.summary()["bundles"] as JsonArray).single() as JsonObject
    assertEquals(1, (bundle["outcomes"] as JsonObject)["schema-mismatch"]!!.jsonPrimitive.int)
    assertEquals(1, (bundle["outcomes"] as JsonObject)["loaded"]!!.jsonPrimitive.int)
    // A plain load gives no reason, so only the mismatch is counted under reasons.
    assertEquals(mapOf("schema-mismatch" to 1), (bundle["reasons"] as JsonObject).mapValues { it.value.jsonPrimitive.int })
    val page = r.page()
    assertTrue("<th>reasons</th>" in page && "<td>schema-mismatch 1</td>" in page, page)
  }

  private val crashStep = hosttemplate.CrashStep.SetAside(5, 2, hosttemplate.CrashKind.EXCEPTION)
}
