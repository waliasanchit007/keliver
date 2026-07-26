import dev.keliver.capabilities.HOST_HTTP_CAPABILITY
import dev.keliver.portal.render.PreviewPersona
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewHttpFidelityTest {
  @AfterTest fun reset() {
    PreviewCapabilities.httpCatalogUnavailable("fixture catalog not loaded")
    PreviewCapabilities.beginHttpSession()
  }

  @Test fun validSelectedFixtureIsFullFidelity() {
    PreviewCapabilities.updateHttpFixtureCatalog(
      """
      [{
        "id":"profile",
        "revision":"abc",
        "entries":2,
        "expiresAt":"2026-08-27T12:00:00Z",
        "expired":false,
        "valid":true,
        "error":null
      }]
      """.trimIndent(),
    )

    val status = PreviewCapabilities.statusOf(
      HOST_HTTP_CAPABILITY,
      PreviewPersona("researcher", httpFixtureSet = "profile"),
    )

    assertTrue(status.real)
    assertEquals("replay: profile (2 exchanges)", status.note)
  }

  @Test fun missingFixtureIsReducedFidelity() {
    PreviewCapabilities.updateHttpFixtureCatalog("[]")

    val status = PreviewCapabilities.statusOf(
      HOST_HTTP_CAPABILITY,
      PreviewPersona("researcher", httpFixtureSet = "missing"),
    )

    assertFalse(status.real)
    assertTrue(status.note.contains("not found"))
  }

  @Test fun replayMissDowngradesCurrentSession() {
    PreviewCapabilities.updateHttpFixtureCatalog(
      """
      [{
        "id":"profile",
        "revision":"abc",
        "entries":1,
        "expiresAt":null,
        "expired":false,
        "valid":true,
        "error":null
      }]
      """.trimIndent(),
    )
    PreviewCapabilities.markHttpMiss("no matching request")

    val status = PreviewCapabilities.statusOf(
      HOST_HTTP_CAPABILITY,
      PreviewPersona("researcher", httpFixtureSet = "profile"),
    )

    assertFalse(status.real)
    assertEquals("replay miss: no matching request", status.note)
  }
}
