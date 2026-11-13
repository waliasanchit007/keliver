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
import dev.keliver.capabilities.HostHttpRequest
import dev.keliver.capabilities.HostHttpResponse
import java.io.File
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class HttpRecordingTest {
  private val clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC)

  @Test fun disabledRecorderHasNoTokenAndRejectsAuthorization() = withRepo { repo, store ->
    store.mkdirs()
    File(store, "http-record.token").writeText("stale-token")
    val service = service(repo, store, enabled = false)

    assertFalse(service.enabled)
    assertFalse(File(store, "http-record.token").exists())
    assertFalse(
      service.authorize(
        InetAddress.getLoopbackAddress(),
        "http://localhost:8096",
        "anything",
      ),
    )
  }

  @Test fun enabledRecorderRequiresLoopbackOriginAndRotatingToken() = withRepo { repo, store ->
    val service = service(repo, store)
    val token = File(store, "http-record.token").readText()

    assertTrue(service.authorize(InetAddress.getLoopbackAddress(), null, token))
    assertTrue(service.authorize(InetAddress.getLoopbackAddress(), "http://localhost:8096", token))
    assertFalse(service.authorize(InetAddress.getByName("93.184.216.34"), null, token))
    assertFalse(service.authorize(InetAddress.getLoopbackAddress(), "http://localhost:9999", token))
    assertFalse(service.authorize(InetAddress.getLoopbackAddress(), null, "wrong"))

    val replacement = service(repo, store)
    assertFalse(replacement.authorize(InetAddress.getLoopbackAddress(), null, token))
  }

  @Test fun missingAuthFailsBeforeAProcessTokenIsCreated() = withRepo { repo, store ->
    assertFailsWith<IllegalArgumentException> {
      HttpRecordingService(
        repoDir = repo,
        config = config(),
        storeDir = store,
        enabledByEnvironment = true,
        environment = { null },
        clock = clock,
        dnsResolver = RecordingDnsResolver { listOf(InetAddress.getByName("93.184.216.34")) },
        transport = RecordingTransport { _, _, _, _ -> HostHttpResponse(200, "{}") },
      )
    }

    assertFalse(File(store, "http-record.token").exists())
  }

  @Test fun recordRedactsBeforeCandidateResponseAuditAndReplay() = withRepo { repo, store ->
    var seenRequest: HostHttpRequest? = null
    var seenAddresses: List<InetAddress>? = null
    var seenAuth: String? = null
    val transport = RecordingTransport { _, request, addresses, auth ->
      seenRequest = request
      seenAddresses = addresses
      seenAuth = auth
      HostHttpResponse(
        status = 200,
        body = """{"email":"maya@example.com","displayName":"Maya"}""",
        headers = mapOf(
          "content-type" to "application/json",
          "set-cookie" to "session=upstream-secret",
        ),
      )
    }
    val service = service(repo, store, transport = transport)
    val created = assertIs<HttpRecordingResult.Success>(
      service.createSession(HttpRecordSessionCommand("profile-api", "field-researcher")),
    )
    val descriptor = Json.decodeFromString<HttpRecordSessionCreated>(created.body)
    val session = descriptor.session
    assertTrue(descriptor.candidate.startsWith("portal-fixtures/http/.candidates/"))
    assertFalse(descriptor.candidate.contains(".."))

    val recorded = assertIs<HttpRecordingResult.Success>(
      service.record(
        session,
        HostHttpRequest(
          method = "GET",
          path = "/profile",
          query = mapOf("token" to "caller-secret", "view" to "full"),
          headers = mapOf(
            "accept" to "application/json",
            "authorization" to "caller-auth",
          ),
          body = """{"session":"body-secret","safe":"yes"}""",
        ),
      ),
    )

    assertEquals("server-auth", seenAuth)
    assertEquals(listOf("93.184.216.34"), seenAddresses.orEmpty().map { it.hostAddress })
    assertFalse(seenRequest!!.headers.keys.any { it.equals("authorization", ignoreCase = true) })
    assertContains(recorded.body, "<redacted>")
    assertFalse(recorded.body.contains("maya@example.com"))
    assertFalse(recorded.body.contains("upstream-secret"))

    val candidate = File(repo, ".").walkTopDown()
      .firstOrNull { it.isFile && it.parentFile.name == ".candidates" }
    assertNotNull(candidate)
    val candidateText = candidate.readText()
    assertFalse(candidateText.contains("caller-secret"))
    assertFalse(candidateText.contains("body-secret"))
    assertFalse(candidateText.contains("maya@example.com"))
    assertFalse(candidateText.contains("server-auth"))
    assertFalse(candidateText.contains("caller-auth"))
    assertContains(candidateText, "<redacted>")

    val audit = File(store, "http-record-audit.jsonl").readText()
    assertFalse(audit.contains("/profile"))
    assertFalse(audit.contains("secret"))
    assertFalse(audit.contains("example.com"))
    assertContains(audit, """"durationBucket":""")
    assertContains(audit, """"responseStatus":200""")

    val closed = assertIs<HttpRecordingResult.Success>(service.close(session))
    assertContains(closed.body, """"entries":1""")

    val reviewed = File(repo, "portal-fixtures/http/field-researcher.json")
    reviewed.parentFile.mkdirs()
    candidate.copyTo(reviewed)
    val replay = HttpReplayService(repo, config(), clock)
    val match = assertIs<HttpReplayResult.Match>(
      replay.replay(
        "field-researcher",
        "replay-session",
        HostHttpRequest(
          method = "GET",
          path = "/profile",
          query = mapOf("token" to "different-runtime-secret", "view" to "full"),
          headers = mapOf("accept" to "application/json"),
          body = """{"session":"different-body-secret","safe":"yes"}""",
        ),
      ),
    )
    assertContains(match.response.body, "<redacted>")
  }

  @Test fun unsafePathsAndAddressesFailClosed() {
    listOf(
      "//metadata.internal/latest",
      "/../secret",
      "/%2e%2e/secret",
      "/safe%2f..%2fsecret",
      "/back\\slash",
      "/path?host=evil",
    ).forEach { assertEquals(null, validateRecordPath(it), it) }
    assertEquals("/v1/profile", validateRecordPath("/v1/profile"))

    listOf(
      "127.0.0.1",
      "10.0.0.1",
      "169.254.169.254",
      "192.168.1.1",
      "198.51.100.1",
      "203.0.113.1",
      "::1",
      "fc00::1",
      "fe80::1",
      "2001:db8::1",
    ).forEach { assertFalse(isGlobalAddress(InetAddress.getByName(it)), it) }
    assertTrue(isGlobalAddress(InetAddress.getByName("93.184.216.34")))
    assertTrue(isGlobalAddress(InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")))
  }

  @Test fun nonGlobalResolutionNeverReachesTransport() = withRepo { repo, store ->
    var called = false
    val service = service(
      repo,
      store,
      resolver = RecordingDnsResolver { listOf(InetAddress.getByName("127.0.0.1")) },
      transport = RecordingTransport { _, _, _, _ ->
        called = true
        HostHttpResponse(200, "unexpected")
      },
    )
    val created = assertIs<HttpRecordingResult.Success>(
      service.createSession(HttpRecordSessionCommand("profile-api", "profile")),
    )
    val session = Json.decodeFromString<HttpRecordSessionCreated>(created.body).session

    val result = assertIs<HttpRecordingResult.Failure>(
      service.record(session, HostHttpRequest("GET", "/profile")),
    )

    assertEquals(422, result.status)
    assertEquals("non_global_address", result.error.reason)
    assertFalse(called)
  }

  @Test fun aBareTokenIsNotJsonForTheUnredactableGuard() = withRepo { repo, store ->
    var responseBody = "{}"
    var calls = 0
    val service = service(
      repo,
      store,
      transport = RecordingTransport { _, _, _, _ ->
        calls++
        HostHttpResponse(200, responseBody)
      },
      config = config(additionalSensitiveKeys = listOf("nickname")),
    )
    val session = session(service)

    // kotlinx reads a bare token as a JSON literal; the guard must not take it for JSON,
    // because redaction leaves it unchanged.
    val request = assertIs<HttpRecordingResult.Failure>(
      service.record(session, HostHttpRequest("POST", "/profile", body = "nick=bob")),
    )
    assertEquals(422 to "unredactable_request", request.status to request.error.reason)
    assertEquals(0, calls)

    responseBody = "a=1&nickname=bob"
    val response = assertIs<HttpRecordingResult.Failure>(
      service.record(session, HostHttpRequest("POST", "/profile", body = """{"a":1}""")),
    )
    assertEquals(422 to "unredactable_response", response.status to response.error.reason)

    responseBody = """{"nickname":"bob"}"""
    assertIs<HttpRecordingResult.Success>(
      service.record(session, HostHttpRequest("POST", "/profile", body = """{"a":1}""")),
    )
    assertTrue(candidateText(repo).let { "<redacted>" in it && "bob" !in it })
  }

  @Test fun aFormBodyWithASensitiveKeyIsRefusedBeforeTheTransport() = withRepo { repo, store ->
    var calls = 0
    val service = service(
      repo,
      store,
      transport = RecordingTransport { _, _, _, _ ->
        calls++
        HostHttpResponse(200, "{}")
      },
      config = config(additionalSensitiveKeys = listOf("nickname")),
    )
    val session = session(service)
    val formType = mapOf("content-type" to "application/x-www-form-urlencoded")
    for (request in listOf(
      HostHttpRequest("POST", "/login", body = "username=a&password=hunter2"),
      HostHttpRequest("POST", "/login", headers = formType, body = "username=a&password=hunter2"),
      HostHttpRequest("POST", "/login", headers = formType, body = "note=a b&nickname=bob"),
    )) {
      val refused = assertIs<HttpRecordingResult.Failure>(service.record(session, request))
      assertEquals(422 to "form_body_with_sensitive_key", refused.status to refused.error.reason, request.body)
    }
    assertEquals(0, calls)
    assertTrue(File(repo, ".").walkTopDown().none { it.isFile && it.parentFile.name == ".candidates" })
  }

  @Test fun valuesRedactedWithConfiguredKeysMatchOnReplay() = withRepo { repo, store ->
    val config = config(additionalSensitiveKeys = listOf("nickname"))
    val service = service(
      repo,
      store,
      transport = RecordingTransport { _, _, _, _ -> HostHttpResponse(200, """{"nickname":"bob","n":1}""") },
      config = config,
    )
    val session = session(service)
    val request = HostHttpRequest(
      method = "POST",
      path = "/profile",
      query = mapOf("nickname" to "bob"),
      body = """{"nickname":"bob","safe":"yes"}""",
    )
    assertIs<HttpRecordingResult.Success>(service.record(session, request))
    assertIs<HttpRecordingResult.Success>(service.close(session))
    val text = candidateText(repo)
    assertFalse("bob" in text, text)
    assertContains(text, """"redactedKeys":["nickname"]""")

    val reviewed = File(repo, "portal-fixtures/http/profile.json")
    reviewed.writeText(text)
    val match = assertIs<HttpReplayResult.Match>(HttpReplayService(repo, config, clock).replay("profile", "s", request))
    assertEquals("""{"nickname":"<redacted>","n":1}""", match.response.body)
  }

  private fun session(service: HttpRecordingService): String {
    val created = assertIs<HttpRecordingResult.Success>(
      service.createSession(HttpRecordSessionCommand("profile-api", "profile")),
    )
    return Json.decodeFromString<HttpRecordSessionCreated>(created.body).session
  }

  private fun candidateText(repo: File): String =
    File(repo, ".").walkTopDown().single { it.isFile && it.parentFile.name == ".candidates" }.readText()

  private fun service(
    repo: File,
    store: File,
    enabled: Boolean = true,
    resolver: RecordingDnsResolver = RecordingDnsResolver {
      listOf(InetAddress.getByName("93.184.216.34"))
    },
    transport: RecordingTransport = RecordingTransport { _, _, _, _ ->
      HostHttpResponse(200, "{}")
    },
    config: PortalConfig = config(),
  ): HttpRecordingService = HttpRecordingService(
    repoDir = repo,
    config = config,
    storeDir = store,
    enabledByEnvironment = enabled,
    environment = { name -> if (name == "PROFILE_API_TOKEN") "server-auth" else null },
    clock = clock,
    dnsResolver = resolver,
    transport = transport,
  )

  private fun config(additionalSensitiveKeys: List<String> = emptyList()): PortalConfig = PortalConfig(
    httpRecording = HttpRecordingConfig(
      additionalSensitiveKeys = additionalSensitiveKeys,
      allowedOrigins = listOf("http://localhost:8096"),
      upstreams = mapOf(
        "profile-api" to HttpRecordingUpstream(
          baseUrl = "https://api.example.com/v1/",
          allowedMethods = listOf("GET", "POST"),
          authEnv = "PROFILE_API_TOKEN",
        ),
      ),
    ),
  )

  private fun withRepo(block: (repo: File, store: File) -> Unit) {
    val repo = createTempDirectory("http-recording-").toFile()
    val store = File(repo, ".store")
    try {
      block(repo, store)
    } finally {
      repo.deleteRecursively()
    }
  }
}
