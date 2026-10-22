import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** E29: an image the canvas could not load is reported by host, with what to do about it. */
class ImageLoadFailuresTest {
  @AfterTest fun reset() {
    ImageLoadFailures.clear()
    ImageLoadFailures.onChange = {}
  }

  @Test fun theHostIsReadFromAnHttpUrl() {
    assertEquals("img.example.com", imageHost("https://IMG.example.com:8443/a/b.png?x=1"))
    assertEquals("cdn.example.org", imageHost("http://cdn.example.org"))
    assertNull(imageHost("data:image/png;base64,AAAA"))
  }

  @Test fun eachHostIsReportedOnceUntilItsReasonChanges() {
    var changes = 0
    ImageLoadFailures.onChange = { changes++ }
    val blocked = imageFailure(routed = false, status = null)
    ImageLoadFailures.record("https://img.example.com/a.png", blocked)
    ImageLoadFailures.record("https://img.example.com/b.png", blocked)
    assertEquals(1, changes)
    ImageLoadFailures.record("https://img.example.com/c.png", imageFailure(routed = false, status = 404))
    assertEquals(2, changes)
    assertEquals(mapOf("img.example.com" to "the host answered HTTP 404"), ImageLoadFailures.hosts())
  }

  @Test fun theReasonSaysWhereTheLoadFailed() {
    assertTrue("CORS" in imageFailure(routed = false, status = null))
    assertEquals("the relay could not fetch it (HTTP 502)", imageFailure(routed = true, status = 502))
  }
}
