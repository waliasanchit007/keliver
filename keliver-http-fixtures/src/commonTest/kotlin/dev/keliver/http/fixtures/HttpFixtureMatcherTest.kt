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
package dev.keliver.http.fixtures

import dev.keliver.capabilities.HostHttpRequest
import dev.keliver.capabilities.HostHttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class HttpFixtureMatcherTest {
  private fun request(
    method: String = "GET",
    path: String = "/profile",
    query: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
  ) = HostHttpRequest(method = method, path = path, query = query, headers = headers, body = body)

  private fun entry(request: HostHttpRequest, body: String, reuse: Boolean = false) =
    HttpFixtureEntry(request, HostHttpResponse(status = 200, headers = emptyMap(), body = body), reuse)

  private fun set(vararg entries: HttpFixtureEntry, matchHeaders: List<String> = listOf("accept", "content-type")) =
    HttpFixtureSet(formatVersion = 1, recordedAt = "2026-11-01T00:00:00Z", matchHeaders = matchHeaders, entries = entries.toList())

  private fun HttpFixtureMatcher.body(request: HostHttpRequest, index: Int): String =
    assertIs<HttpFixtureSelection.Match>(select(request, index)).response.body

  private fun HttpFixtureMatcher.miss(request: HostHttpRequest, index: Int = 0): String =
    assertIs<HttpFixtureSelection.Miss>(select(request, index)).reason

  @Test fun exactMatchIgnoresMethodCaseQueryOrderAndHeaderNameCase() {
    val matcher = HttpFixtureMatcher(
      set(entry(request(query = mapOf("a" to "1", "b" to "2"), headers = mapOf("Accept" to "json")), "ok")),
    )
    val incoming = request(
      method = "get",
      query = mapOf("b" to "2", "a" to "1"),
      headers = mapOf("ACCEPT" to "json", "X-Trace" to "unmatched"),
    )
    assertEquals("ok", matcher.body(incoming, 0))
  }

  @Test fun anyOtherDifferenceIsAMiss() {
    val matcher = HttpFixtureMatcher(set(entry(request(headers = mapOf("accept" to "json"), body = "x"), "ok")))
    listOf(
      request(path = "/profile/", headers = mapOf("accept" to "json"), body = "x"),
      request(method = "POST", headers = mapOf("accept" to "json"), body = "x"),
      request(headers = mapOf("accept" to "xml"), body = "x"),
      request(headers = mapOf("accept" to "json"), body = "y"),
      request(query = mapOf("q" to "1"), headers = mapOf("accept" to "json"), body = "x"),
    ).forEach { assertEquals("no matching request", matcher.miss(it)) }
    assertEquals(424, HttpFixtureMatcher.MISS_STATUS)
  }

  @Test fun matchHeadersAreLowerCasedAndOnlyTheyCount() {
    val matcher = HttpFixtureMatcher(
      set(entry(request(headers = mapOf("X-Variant" to "b", "accept" to "a")), "ok"), matchHeaders = listOf("X-Variant")),
    )
    assertEquals("ok", matcher.body(request(headers = mapOf("x-variant" to "b")), 0))
    assertEquals("no matching request", matcher.miss(request(headers = mapOf("x-variant" to "c"))))
  }

  @Test fun sensitiveValuesAreRedactedBeforeMatching() {
    val matcher = HttpFixtureMatcher(
      set(
        entry(
          request(
            method = "POST",
            query = mapOf("session_token" to "<redacted>"),
            body = """{"email":"<redacted>","n":1}""",
          ),
          "ok",
        ),
      ),
    )
    val live = request(
      method = "POST",
      query = mapOf("session_token" to "abc"),
      body = """{"email": "a@example.com", "n": 1}""",
    )
    assertEquals("ok", matcher.body(live, 0))
    assertEquals(matcher.requestKey(live), matcher.requestKey(live.copy(query = mapOf("session_token" to "zzz"))))
  }

  @Test fun duplicatesReplayInFileOrderThenExhaust() {
    val r = request()
    val matcher = HttpFixtureMatcher(set(entry(r, "first"), entry(request(path = "/other"), "other"), entry(r, "second")))
    val first = assertIs<HttpFixtureSelection.Match>(matcher.select(r, 0))
    assertEquals("first", first.response.body)
    assertEquals(1, first.nextIndex)
    assertEquals("second", matcher.body(r, first.nextIndex))
    assertEquals("matching response sequence exhausted", matcher.miss(r, 2))
  }

  @Test fun finalReuseRepeatsWithoutAdvancing() {
    val r = request()
    val matcher = HttpFixtureMatcher(set(entry(r, "first"), entry(r, "last", reuse = true)))
    val last = assertIs<HttpFixtureSelection.Match>(matcher.select(r, 1))
    assertEquals("last", last.response.body)
    assertEquals(1, last.nextIndex)
    assertEquals("last", matcher.body(r, 7))
  }

  @Test fun validationRejectsUnsafeSets() {
    fun rejects(message: String, set: HttpFixtureSet) {
      val error = assertFailsWith<IllegalArgumentException> { HttpFixtureMatcher(set) }
      assertTrue(message in error.message.orEmpty(), "expected '$message' in '${error.message}'")
    }
    val r = request()
    rejects("reuse=true is allowed only on the final", set(entry(r, "a", reuse = true), entry(r, "b")))
    rejects("unsupported formatVersion", set(entry(r, "a")).copy(formatVersion = 2))
    rejects("no entries", set())
    rejects("matchHeaders must be unique", set(entry(r, "a"), matchHeaders = listOf("Accept", "accept")))
    rejects("sensitive headers cannot", set(entry(r, "a"), matchHeaders = listOf("Authorization")))
    rejects("forbidden header", set(entry(request(headers = mapOf("Cookie" to "c")), "a")))
    rejects("sensitive query key", set(entry(request(query = mapOf("api_key" to "k")), "a")))
    rejects("sensitive JSON key", set(entry(r, """{"password":"p"}""")))
  }

  @Test fun fixtureFileDecodesWithDefaults() {
    val set = Json.decodeFromString(
      HttpFixtureSet.serializer(),
      """{"formatVersion":1,"recordedAt":"2026-11-01T00:00:00Z","entries":[
        {"request":{"method":"GET","path":"/p","query":{},"headers":{}},
         "response":{"status":200,"headers":{},"body":"ok"}}]}""",
    )
    assertEquals(listOf("accept", "content-type"), set.matchHeaders)
    assertEquals(false, set.entries.single().reuse)
    assertEquals("ok", HttpFixtureMatcher(set).body(request(path = "/p"), 0))
  }

  @Test fun redactionKeysAndNonJson() {
    assertTrue(HttpFixtureRedaction.isSensitiveKey("X-Session-Id"))
    assertTrue(HttpFixtureRedaction.isSensitiveKey("nickname", setOf("nickname")))
    assertEquals(false, HttpFixtureRedaction.isSensitiveKey("tokens_left"))
    assertEquals("not json", HttpFixtureRedaction.redactJson("not json"))
  }
}
