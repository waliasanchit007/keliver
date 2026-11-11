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

  private fun entry(
    request: HostHttpRequest,
    body: String,
    reuse: Boolean = false,
    responseHeaders: Map<String, String> = emptyMap(),
  ) = HttpFixtureEntry(request, HostHttpResponse(status = 200, headers = responseHeaders, body = body), reuse)

  private fun set(
    vararg entries: HttpFixtureEntry,
    matchHeaders: List<String> = listOf("accept", "content-type"),
    formatVersion: Int = 1,
    recordedAt: String = "2026-11-01T00:00:00Z",
    expiresAt: String? = null,
  ) = HttpFixtureSet(
    formatVersion = formatVersion,
    recordedAt = recordedAt,
    expiresAt = expiresAt,
    matchHeaders = matchHeaders,
    entries = entries.toList(),
  )

  private fun HttpFixtureMatcher.body(request: HostHttpRequest, index: Int): String =
    assertIs<HttpFixtureSelection.Match>(select(request, index)).response.body

  private fun HttpFixtureMatcher.miss(request: HostHttpRequest, index: Int = 0): String =
    assertIs<HttpFixtureSelection.Miss>(select(request, index)).reason

  private fun rejects(message: String, set: () -> HttpFixtureSet) {
    val error = assertFailsWith<IllegalArgumentException> { HttpFixtureMatcher(set()) }
    assertTrue(message in error.message.orEmpty(), "expected '$message' in '${error.message}'")
  }

  private fun accepts(set: HttpFixtureSet): HttpFixtureMatcher = HttpFixtureMatcher(set)

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
    val r = request()
    rejects("reuse=true is allowed only on the final") { set(entry(r, "a", reuse = true), entry(r, "b")) }
    rejects("unsupported formatVersion 2") { set(entry(r, "a"), formatVersion = 2) }
    rejects("no entries") { set() }
    rejects("matchHeaders must be unique") { set(entry(r, "a"), matchHeaders = listOf("Accept", "accept")) }
    rejects("sensitive headers cannot") { set(entry(r, "a"), matchHeaders = listOf("Authorization")) }
    rejects("forbidden header") { set(entry(request(headers = mapOf("Cookie" to "c")), "a")) }
    rejects("sensitive query key") { set(entry(request(query = mapOf("api_key" to "k")), "a")) }
    rejects("sensitive JSON key") { set(entry(r, """{"password":"p"}""")) }
  }

  @Test fun bodiesAreLimitedToOneMebibyteOfUtf8() {
    val limit = HttpFixtureMatcher.MAX_BODY_BYTES
    assertEquals(1024 * 1024, limit)
    // Each large string is built once: repeat() over 1 MiB is slow in the development
    // wasm build that ChromeHeadless runs.
    val ascii = "a".repeat(limit)
    // Exactly at the limit is fine, in either direction.
    accepts(set(entry(request(method = "POST", body = ascii), ascii)))
    rejects("request body exceeds 1 MiB") { set(entry(request(method = "POST", body = ascii + "a"), "ok")) }
    rejects("response body exceeds 1 MiB") { set(entry(request(), ascii + "b")) }
    // The limit counts UTF-8 bytes, not characters: "é" is two bytes, "€" three and a
    // surrogate pair four.
    for ((char, bytes) in listOf("é" to 2, "€" to 3, "😀" to 4)) {
      val wide = char.repeat(limit / bytes)
      val padding = "x".repeat(limit % bytes)
      accepts(set(entry(request(), wide + padding)))
      rejects("response body exceeds 1 MiB") { set(entry(request(), wide + padding + "x")) }
    }
  }

  @Test fun controlCharactersOtherThanTabLfAndCrAreRefused() {
    accepts(set(entry(request(method = "POST", body = "a\tb\nc\r\n"), "line 1\r\nline 2\tend\n")))
    for (c in listOf('\u0000', '\u0001', '\u001b', '\u001f')) {
      rejects("request body contains unsupported control characters") {
        set(entry(request(method = "POST", body = "a${c}b"), "ok"))
      }
      rejects("response body contains unsupported control characters") { set(entry(request(), "a${c}b")) }
    }
  }

  @Test fun aFixtureCannotHoldAnAbsoluteOrSchemePath() {
    // HostHttpRequest refuses these when a fixture file is decoded, before the matcher sees them.
    for (path in listOf("profile", "https://api.example/profile", "/redirect?to=https://elsewhere.example")) {
      assertFailsWith<IllegalArgumentException>(path) {
        HttpFixtureMatcher(
          Json.decodeFromString(
            HttpFixtureSet.serializer(),
            """{"formatVersion":1,"recordedAt":"2026-11-01T00:00:00Z","entries":[
              {"request":{"method":"GET","path":"$path"},"response":{"status":200,"body":"ok"}}]}""",
          ),
        )
      }
    }
  }

  @Test fun sensitiveResponseHeadersAreRefusedInAnyCase() {
    for (header in listOf("Set-Cookie", "set-cookie", "AUTHORIZATION", "X-Api-Key", "x-auth-token", "Proxy-Authorization")) {
      rejects("response contains forbidden header '$header'") {
        set(entry(request(), "ok", responseHeaders = mapOf(header to "secret")))
      }
    }
    accepts(set(entry(request(), "ok", responseHeaders = mapOf("Content-Type" to "application/json", "X-Request-Id" to "1"))))
  }

  @Test fun sensitiveJsonKeysAreRefusedAtAnyDepth() {
    rejects("sensitive JSON key 'token' must be redacted") {
      set(entry(request(), """{"data":{"items":[{"id":1},{"token":"t"}]}}"""))
    }
    rejects("sensitive JSON key 'email' must be redacted") {
      set(entry(request(method = "POST", body = """[{"user":{"email":"a@example.com"}}]"""), "ok"))
    }
    // A sensitive key's value must be the redaction marker itself, not an object holding redacted values.
    rejects("sensitive JSON key 'account' must be redacted") {
      set(entry(request(), """{"account":{"id":"<redacted>"}}"""))
    }
    accepts(set(entry(request(), """{"data":{"items":[{"id":1},{"token":"<redacted>"}]}}""")))
  }

  @Test fun nestedSensitiveValuesAreRedactedBeforeMatching() {
    val matcher = accepts(
      set(entry(request(method = "POST", body = """{"user":{"email":"<redacted>","tags":[{"phone":"<redacted>"}]}}"""), "ok")),
    )
    val live = request(method = "POST", body = """{"user":{"email":"a@example.com","tags":[{"phone":"555"}]}}""")
    assertEquals("ok", matcher.body(live, 0))
    assertEquals(
      """{"user":{"email":"<redacted>","tags":[{"phone":"<redacted>"}]}}""",
      HttpFixtureRedaction.redactJson("""{ "user": { "email": "a@example.com", "tags": [ { "phone": "555" } ] } }"""),
    )
  }

  @Test fun jsonBodiesIgnoreWhitespaceButKeepKeyOrder() {
    val matcher = accepts(set(entry(request(method = "POST", body = """{"a":1,"b":[1,2]}"""), "ok")))
    assertEquals("ok", matcher.body(request(method = "POST", body = "{ \"a\" : 1,\n \"b\" : [ 1, 2 ] }"), 0))
    assertEquals("no matching request", matcher.miss(request(method = "POST", body = """{"b":[1,2],"a":1}""")))
  }

  @Test fun aNonJsonBodyMatchesByteForByteAndIsNeverRedacted() {
    val form = "a=1&b=2&password=hunter2"
    val matcher = accepts(set(entry(request(method = "POST", body = form), "ok")))
    assertEquals("ok", matcher.body(request(method = "POST", body = form), 0))
    for (other in listOf("b=2&a=1&password=hunter2", "$form ", "$form\n", " $form", "a=1&b=2&password=other", "A=1&b=2&password=hunter2")) {
      assertEquals("no matching request", matcher.miss(request(method = "POST", body = other)), other)
    }
    // Bare tokens that kotlinx would read as a JSON literal keep their bytes too.
    for (body in listOf(form, "OK\n", " 42 ", "true\n", "hello world", "<a>1</a>")) {
      assertEquals(body, HttpFixtureRedaction.redactJson(body))
    }
    // A missing body and an empty one are the same request.
    val empty = accepts(set(entry(request(method = "POST", body = ""), "ok")))
    assertEquals("ok", empty.body(request(method = "POST", body = null), 0))
  }

  @Test fun additionalKeysAreComparedLowerCasedOnBothSides() {
    assertTrue(HttpFixtureRedaction.isSensitiveKey("Nickname", setOf("nickname")))
    assertTrue(HttpFixtureRedaction.isSensitiveKey("NICKNAME", setOf("nickname")))
    assertTrue(HttpFixtureRedaction.isSensitiveKey("nickname", setOf("NickName")))
    assertEquals(false, HttpFixtureRedaction.isSensitiveKey("nick", setOf("nickname")))
    assertEquals(
      """{"NickName":"<redacted>","nick":"n"}""",
      HttpFixtureRedaction.redactJson("""{"NickName":"x","nick":"n"}""", setOf("NICKNAME")),
    )
    // The built-in keys ignore case too, and match on word boundaries ('_' or '-').
    assertTrue(HttpFixtureRedaction.isSensitiveKey("Session_Token"))
    assertTrue(HttpFixtureRedaction.isSensitiveKey("X-API-KEY"))
    assertEquals(false, HttpFixtureRedaction.isSensitiveKey("passwordless"))
  }

  @Test fun theMatcherHasNoClock() {
    // Dates are the caller's: an expired set, and one whose dates aren't even dates, still match.
    val expired = accepts(set(entry(request(), "ok"), recordedAt = "1999-01-01T00:00:00Z", expiresAt = "2000-01-01T00:00:00Z"))
    assertEquals("ok", expired.body(request(), 0))
    val undated = accepts(set(entry(request(), "ok"), recordedAt = "not a date", expiresAt = "never"))
    assertEquals("ok", undated.body(request(), 0))
  }

  @Test fun valueTypesCompareByValue() {
    val r = request()
    assertEquals(entry(r, "a"), entry(r, "a"))
    assertEquals(entry(r, "a").hashCode(), entry(r, "a").hashCode())
    assertEquals(set(entry(r, "a")), set(entry(r, "a")))
    assertTrue(set(entry(r, "a")) != set(entry(r, "a"), expiresAt = "2027-01-01T00:00:00Z"))
    val match = accepts(set(entry(r, "a"))).select(r, 0)
    assertEquals(HttpFixtureSelection.Match(HostHttpResponse(status = 200, body = "a"), 1), match)
    assertEquals(HttpFixtureSelection.Miss("x"), HttpFixtureSelection.Miss("x"))
    assertTrue("nextIndex=1" in match.toString(), match.toString())
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
