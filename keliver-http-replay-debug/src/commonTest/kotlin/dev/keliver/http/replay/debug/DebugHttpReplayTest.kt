/*
 * Copyright (C) 2026 Square, Inc.
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
package dev.keliver.http.replay.debug

import dev.keliver.capabilities.HostHttpRequest
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import dev.keliver.http.asHostHttp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DebugHttpReplayTest {
  private val fixedNow = Instant.parse("2026-07-27T12:00:00Z")

  private fun replay(vararg files: Pair<String, String>, now: Instant = fixedNow) =
    DebugHttpReplay(files.toMap(), now = { now })

  /** A fixture file in the relay's on-disk format (the relay's own test fixture's shape). */
  private fun file(
    vararg entries: String,
    expiresAt: String? = "2026-08-27T12:00:00Z",
    recordedAt: String = "2026-07-27T10:00:00Z",
  ): String = """
    {
      "formatVersion":1,
      "recordedAt":"$recordedAt",
      ${expiresAt?.let { "\"expiresAt\":\"$it\"," }.orEmpty()}
      "matchHeaders":["accept","content-type"],
      "entries":[${entries.joinToString(",")}]
    }
  """.trimIndent()

  private fun entry(
    path: String,
    body: String,
    method: String = "GET",
    status: Int = 200,
    reuse: Boolean = false,
    extra: String = "",
  ) = """
    {"request":{"method":"$method","path":"$path"$extra},
     "response":{"status":$status,"headers":{"content-type":"text/plain"},"body":"$body"},
     "reuse":$reuse}
  """.trimIndent()

  private fun get(path: String, query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()) =
    HttpRequest(method = "GET", path = path, query = query, headers = headers)

  private fun HttpResponse.errorField(name: String): String =
    Json.parseToJsonElement(body).jsonObject.getValue(name).jsonPrimitive.content

  @Test fun exactMatchReturnsTheRecordedResponseUnchanged() = runTest {
    val files = file(
      """
      {"request":{"method":"GET","path":"/v1/profile","query":{"view":"full"},"headers":{"Accept":"application/json"}},
       "response":{"status":200,"headers":{"content-type":"application/json"},"body":"{\"name\":\"Maya\"}"}}
      """.trimIndent(),
      entry("/v1/missing", "not here", status = 404),
    )
    val provider = replay("profile" to files).provider("profile")

    val response = provider.execute(
      HttpRequest(
        method = "get",
        path = "/v1/profile",
        query = mapOf("view" to "full"),
        headers = mapOf("accept" to "application/json", "X-Unmatched" to "ignored"),
      ),
    )
    assertEquals(HttpResponse(200, """{"name":"Maya"}""", mapOf("content-type" to "application/json")), response)

    // A recorded error response is a match like any other, not a miss.
    assertEquals(HttpResponse(404, "not here", mapOf("content-type" to "text/plain")), provider.execute(get("/v1/missing")))
  }

  @Test fun aMissIsAnswered424WithTheRelaysErrorShapeAndReported() = runTest {
    val misses = mutableListOf<DebugHttpReplayMiss>()
    val provider = replay("profile" to file(entry("/v1/profile", "ok"))).provider("profile") { misses += it }

    val response = provider.execute(HttpRequest(method = "post", path = "/v1/profile", body = "x"))

    assertEquals(424, response.status)
    assertEquals(mapOf("content-type" to "application/json"), response.headers)
    assertEquals(
      """{"error":"replay_miss","fixtureSet":"profile","method":"POST","path":"/v1/profile","reason":"no matching request"}""",
      response.body,
    )
    assertEquals(listOf(DebugHttpReplayMiss(424, "profile", "POST", "/v1/profile", "no matching request")), misses)
  }

  @Test fun theFilesRedactedKeysApplyToTheLiveRequest() = runTest {
    // Recorded with `nickname` as an additional sensitive key: the file stores it redacted and
    // lists it, so the live value is redacted the same way before matching.
    val recorded = entry(
      "/v1/verify",
      "ok",
      method = "POST",
      extra = ""","body":"{\"nickname\":\"<redacted>\",\"safe\":\"yes\"}"""",
    )
    val withKeys = file(recorded).replace("\"formatVersion\":1,", "\"formatVersion\":1,\"redactedKeys\":[\"nickname\"],")
    val live = HttpRequest(method = "POST", path = "/v1/verify", body = """{"nickname":"bob","safe":"yes"}""")

    assertEquals("ok", replay("keys" to withKeys).provider("keys").execute(live).body)
    // An older file without the field can't match the live value.
    assertEquals(424, replay("old" to file(recorded)).provider("old").execute(live).status)
  }

  @Test fun aFailingMissCallbackDoesNotChangeTheAnswer() = runTest {
    val provider = replay("s" to file(entry("/p", "ok"))).provider("s") { error("logging failed") }
    assertEquals(424, provider.execute(get("/q")).status)
    assertEquals("ok", provider.execute(get("/p")).body)
  }

  @Test fun everyOtherDifferenceIsAMiss() = runTest {
    val provider = replay(
      "s" to file(entry("/p", "ok", extra = ""","query":{"q":"1"},"headers":{"accept":"json"}""")),
    ).provider("s")
    val matching = get("/p", mapOf("q" to "1"), mapOf("accept" to "json"))
    listOf(
      matching.copy(path = "/p/"),
      matching.copy(method = "PUT"),
      matching.copy(query = mapOf("q" to "2")),
      matching.copy(query = emptyMap()),
      matching.copy(headers = mapOf("accept" to "xml")),
      matching.copy(body = "unexpected"),
    ).forEach { request ->
      val response = provider.execute(request)
      assertEquals(424, response.status, "$request")
      assertEquals("no matching request", response.errorField("reason"))
    }
    assertEquals("ok", provider.execute(matching).body)
  }

  @Test fun duplicatesReplayInFileOrderThenExhaust() = runTest {
    val provider = replay(
      "s" to file(entry("/sequence", "first"), entry("/other", "other"), entry("/sequence", "second")),
    ).provider("s")

    assertEquals("first", provider.execute(get("/sequence")).body)
    assertEquals("second", provider.execute(get("/sequence")).body)
    val exhausted = provider.execute(get("/sequence"))
    assertEquals(424, exhausted.status)
    assertEquals("matching response sequence exhausted", exhausted.errorField("reason"))
    // Still exhausted: a miss does not move the cursor.
    assertEquals(424, provider.execute(get("/sequence")).status)
  }

  @Test fun theFinalReuseEntryRepeats() = runTest {
    val provider = replay(
      "s" to file(entry("/stable", "first"), entry("/stable", "same", reuse = true)),
    ).provider("s")

    assertEquals("first", provider.execute(get("/stable")).body)
    repeat(5) { assertEquals("same", provider.execute(get("/stable")).body) }
  }

  @Test fun eachRequestKeyHasItsOwnCursor() = runTest {
    val provider = replay(
      "s" to file(entry("/a", "a1"), entry("/b", "b1"), entry("/a", "a2"), entry("/b", "b2")),
    ).provider("s")

    assertEquals("a1", provider.execute(get("/a")).body)
    assertEquals(424, provider.execute(get("/unknown")).status)
    assertEquals("b1", provider.execute(get("/b")).body)
    assertEquals("a2", provider.execute(get("/a")).body)
    assertEquals("b2", provider.execute(get("/b")).body)
  }

  @Test fun sessionsAreIsolatedAsTheRelaysAre() = runTest {
    val replay = replay("s" to file(entry("/sequence", "first"), entry("/sequence", "second")))
    val a = replay.provider("s")
    val b = replay.provider("s")

    assertEquals("first", a.execute(get("/sequence")).body)
    assertEquals("second", a.execute(get("/sequence")).body)
    assertEquals("first", b.execute(get("/sequence")).body)
    assertEquals(424, a.execute(get("/sequence")).status)
  }

  @Test fun startSessionRestartsEverySequenceAndCanSwitchSets() = runTest {
    val provider = replay(
      "one" to file(entry("/p", "one-1"), entry("/p", "one-2")),
      "two" to file(entry("/p", "two-1")),
    ).provider("one")

    assertEquals("one-1", provider.execute(get("/p")).body)
    provider.startSession()
    assertEquals("one", provider.fixtureSet)
    assertEquals("one-1", provider.execute(get("/p")).body)
    assertEquals("one-2", provider.execute(get("/p")).body)

    provider.startSession("two")
    assertEquals("two", provider.fixtureSet)
    assertEquals("two-1", provider.execute(get("/p")).body)
    val exhausted = provider.execute(get("/p"))
    assertEquals(424, exhausted.status)
    assertEquals("two", exhausted.errorField("fixtureSet"))
  }

  @Test fun sensitiveValuesAreRedactedBeforeMatching() = runTest {
    val provider = replay(
      "s" to file(
        """
        {"request":{"method":"POST","path":"/login","query":{"session_token":"<redacted>"},
                    "body":"{\"email\":\"<redacted>\",\"n\":1}"},
         "response":{"status":200,"body":"ok"}}
        """.trimIndent(),
      ),
    ).provider("s")

    val live = HttpRequest(
      method = "POST",
      path = "/login",
      query = mapOf("session_token" to "live-value"),
      body = """{"email": "someone@example.com", "n": 1}""",
    )
    assertEquals("ok", provider.execute(live).body)
  }

  @Test fun failuresUseTheRelaysStatuses() = runTest {
    val misses = mutableListOf<DebugHttpReplayMiss>()
    val replay = replay(
      "good" to file(entry("/p", "ok")),
      "expired" to file(entry("/p", "ok"), expiresAt = "2026-07-27T12:00:00Z"),
      "secret" to file(entry("/p", "ok", extra = ""","headers":{"Authorization":"Bearer x"}""")),
    )
    suspend fun answer(fixtureSet: String, request: HttpRequest): Pair<Int, String> {
      val response = replay.provider(fixtureSet) { misses += it }.execute(request)
      return response.status to response.errorField("reason")
    }

    assertEquals(400 to "invalid request", answer("good", HttpRequest(method = "GET", path = "relative")))
    assertEquals(400 to "invalid request", answer("good", HttpRequest(method = " ", path = "/p")))
    assertEquals(400 to "invalid request", answer("good", HttpRequest(method = "GET", path = "/https://elsewhere")))
    assertEquals(400 to "invalid fixture-set name", answer("../good", get("/p")))
    assertEquals(
      413 to "request body exceeds 1 MiB",
      answer("good", HttpRequest(method = "POST", path = "/p", body = "x".repeat(1024 * 1024 + 1))),
    )
    assertEquals(404 to "fixture set not found", answer("absent", get("/p")))
    val invalid = answer("secret", get("/p"))
    assertEquals(422, invalid.first)
    assertTrue("forbidden header" in invalid.second, invalid.second)
    // expiresAt is the first instant the set no longer answers.
    assertEquals(410 to "fixture set expired", answer("expired", get("/p")))
    assertEquals(8, misses.size)
    assertEquals(listOf(400, 400, 400, 400, 413, 404, 422, 410), misses.map { it.status })
  }

  @Test fun aSetAnswersUntilItExpires() = runTest {
    val files = arrayOf("s" to file(entry("/p", "ok"), expiresAt = "2026-07-27T12:00:01Z"))
    assertEquals(200, replay(*files).provider("s").execute(get("/p")).status)
    val later = replay(*files, now = Instant.parse("2026-07-27T12:00:01Z"))
    assertEquals(410, later.provider("s").execute(get("/p")).status)
    // No expiresAt: never expires.
    val forever = replay("s" to file(entry("/p", "ok"), expiresAt = null), now = Instant.parse("2099-01-01T00:00:00Z"))
    assertEquals(200, forever.provider("s").execute(get("/p")).status)
  }

  @Test fun theRelaysLoadRulesMakeASetInvalid() {
    val replay = replay(
      "bad-json" to "{not json",
      "bad-date" to file(entry("/p", "ok"), recordedAt = "yesterday"),
      "bad-expiry" to file(entry("/p", "ok"), expiresAt = "soon"),
      "unknown-key" to file(entry("/p", "ok")).replace("\"formatVersion\":1,", "\"formatVersion\":1,\"extra\":true,"),
      "version-2" to file(entry("/p", "ok")).replace("\"formatVersion\":1", "\"formatVersion\":2"),
      "no-entries" to file(),
      "reuse-early" to file(entry("/p", "a", reuse = true), entry("/p", "b")),
      "too-big" to file(entry("/p", "x".repeat(1024 * 1024)), entry("/q", "x".repeat(1024 * 1024)), entry("/r", "x".repeat(1024 * 1024)), entry("/s", "x".repeat(1024 * 1024))),
      "bad name!" to file(entry("/p", "ok")),
      "ok" to file(entry("/p", "ok")),
    )
    val catalog = replay.catalog().associateBy { it.id }

    assertEquals(
      listOf("bad name!", "bad-date", "bad-expiry", "bad-json", "no-entries", "ok", "reuse-early", "too-big", "unknown-key", "version-2"),
      replay.catalog().map { it.id },
    )
    assertEquals(DebugHttpReplaySetInfo("ok", valid = true, entries = 1, expiresAt = "2026-08-27T12:00:00Z", expired = false), catalog["ok"])
    catalog.values.filter { it.id != "ok" }.forEach { info ->
      assertEquals(false, info.valid, info.id)
      assertEquals(0, info.entries, info.id)
      assertTrue(info.error.orEmpty().isNotEmpty(), info.id)
    }
    assertEquals("invalid fixture-set filename: bad name!.json", catalog.getValue("bad name!").error)
    assertEquals("fixture file exceeds 4 MiB", catalog.getValue("too-big").error)
    assertEquals("unsupported formatVersion 2", catalog.getValue("version-2").error)
    assertEquals("fixture set has no entries", catalog.getValue("no-entries").error)
    assertTrue("reuse=true is allowed only on the final" in catalog.getValue("reuse-early").error.orEmpty())
  }

  @Test fun theCatalogIsInTheRelaysFileNameOrder() {
    // The relay sorts `<id>.json` file names: '-' sorts before '.', so `a-b.json` is first.
    val replay = replay("a" to file(entry("/p", "ok")), "a-b" to file(entry("/p", "ok")), "B" to file(entry("/p", "ok")))
    assertEquals(listOf("B", "a-b", "a"), replay.catalog().map { it.id })
  }

  @Test fun theCatalogReportsExpiryAsOfTheCall() {
    var now = fixedNow
    val replay = DebugHttpReplay(mapOf("s" to file(entry("/p", "ok"), expiresAt = "2026-07-28T00:00:00Z")), now = { now })
    assertEquals(false, replay.catalog().single().expired)
    now = Instant.parse("2026-07-28T00:00:00Z")
    assertEquals(true, replay.catalog().single().expired)
  }

  @Test fun itServesAsTheHostHttpCapability() = runTest {
    val http = replay("s" to file(entry("/p", "ok"))).provider("s").asHostHttp()
    assertEquals("ok", http.execute(HostHttpRequest("GET", "/p")).body)
    assertEquals(424, http.execute(HostHttpRequest("GET", "/q")).status)
  }
}
