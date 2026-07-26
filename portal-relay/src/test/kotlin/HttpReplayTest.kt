import dev.keliver.capabilities.HostHttpRequest
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpReplayTest {
  private val clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC)

  @Test fun exactMatchReturnsRecordedResponse() = withService(
    fixture(
      entries = """
        {
          "request":{"method":"GET","path":"/v1/profile","query":{"view":"full"},"headers":{"Accept":"application/json"}},
          "response":{"status":200,"headers":{"content-type":"application/json"},"body":"{\"name\":\"Maya\"}"}
        }
      """,
    ),
  ) { service ->
    val result = service.replay(
      "profile",
      "session-1",
      HostHttpRequest(
        "get",
        "/v1/profile",
        query = mapOf("view" to "full"),
        headers = mapOf("accept" to "application/json"),
      ),
    )

    assertEquals("""{"name":"Maya"}""", assertIs<HttpReplayResult.Match>(result).response.body)
  }

  @Test fun duplicateResponsesAreOrderedAndIsolatedPerSession() = withService(
    fixture(
      entries = """
        {
          "request":{"method":"GET","path":"/sequence"},
          "response":{"status":200,"body":"first"}
        },
        {
          "request":{"method":"GET","path":"/sequence"},
          "response":{"status":200,"body":"second"}
        }
      """,
    ),
  ) { service ->
    val request = HostHttpRequest("GET", "/sequence")

    assertEquals("first", assertIs<HttpReplayResult.Match>(service.replay("profile", "a", request)).response.body)
    assertEquals("second", assertIs<HttpReplayResult.Match>(service.replay("profile", "a", request)).response.body)
    assertEquals("first", assertIs<HttpReplayResult.Match>(service.replay("profile", "b", request)).response.body)
    val exhausted = assertIs<HttpReplayResult.Failure>(service.replay("profile", "a", request))
    assertEquals(424, exhausted.status)
    assertEquals("matching response sequence exhausted", exhausted.error.reason)
  }

  @Test fun finalReusableResponseCanRepeat() = withService(
    fixture(
      entries = """
        {
          "request":{"method":"GET","path":"/stable"},
          "response":{"status":200,"body":"same"},
          "reuse":true
        }
      """,
    ),
  ) { service ->
    val request = HostHttpRequest("GET", "/stable")

    repeat(3) {
      assertEquals(
        "same",
        assertIs<HttpReplayResult.Match>(service.replay("profile", "session", request)).response.body,
      )
    }
  }

  @Test fun missNeverFallsBackToNetwork() = withService(fixture()) { service ->
    val result = assertIs<HttpReplayResult.Failure>(
      service.replay("profile", "session", HostHttpRequest("GET", "/not-recorded")),
    )

    assertEquals(424, result.status)
    assertEquals("replay_miss", result.error.error)
    assertEquals("no matching request", result.error.reason)
  }

  @Test fun expiredFixtureFailsClosed() = withService(
    fixture(expiresAt = "2026-07-27T11:59:59Z"),
  ) { service ->
    val result = assertIs<HttpReplayResult.Failure>(
      service.replay("profile", "session", HostHttpRequest("GET", "/v1/profile")),
    )

    assertEquals(410, result.status)
    assertEquals("fixture set expired", result.error.reason)
  }

  @Test fun privacyLintRejectsSecretHeaders() = withService(
    fixture(
      entries = """
        {
          "request":{"method":"GET","path":"/v1/profile","headers":{"Authorization":"Bearer secret"}},
          "response":{"status":200,"body":"ok"}
        }
      """,
    ),
  ) { service ->
    val descriptor = service.catalog().single()

    assertEquals(false, descriptor.valid)
    assertTrue(descriptor.error.orEmpty().contains("forbidden header"))
  }

  @Test fun privacyLintRejectsUnredactedJsonSecrets() = withService(
    fixture(
      entries = """
        {
          "request":{"method":"POST","path":"/login","body":"{\"password\":\"secret\"}"},
          "response":{"status":200,"body":"ok"}
        }
      """,
    ),
  ) { service ->
    val descriptor = service.catalog().single()

    assertEquals(false, descriptor.valid)
    assertTrue(descriptor.error.orEmpty().contains("must be redacted"))
  }

  private fun withService(fixture: String, block: (HttpReplayService) -> Unit) {
    val repo = createTempDirectory("http-replay-").toFile()
    try {
      val dir = File(repo, "portal-fixtures/http").apply { mkdirs() }
      File(dir, "profile.json").writeText(fixture)
      block(HttpReplayService(repo, PortalConfig(), clock))
    } finally {
      repo.deleteRecursively()
    }
  }

  private fun fixture(
    entries: String = """
      {
        "request":{"method":"GET","path":"/v1/profile"},
        "response":{"status":200,"body":"{\"name\":\"Maya\"}"}
      }
    """,
    expiresAt: String = "2026-08-27T12:00:00Z",
  ): String =
    """
    {
      "formatVersion":1,
      "recordedAt":"2026-07-27T10:00:00Z",
      "expiresAt":"$expiresAt",
      "matchHeaders":["accept","content-type"],
      "entries":[$entries]
    }
    """.trimIndent()
}
