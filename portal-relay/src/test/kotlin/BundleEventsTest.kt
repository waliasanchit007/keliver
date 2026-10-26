import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Live device updates: the relay's `GET /bundles/events` stream (BundleEvents.kt). */
class BundleEventsTest {
  private var newest: Int? = 3
  private val events = BundleEvents({ newest }, heartbeatSeconds = 3600)

  @AfterTest fun close() = events.close()

  @Test fun aListenerHearsTheNewestBundleAtOnceAndAfterEachPublish() {
    val phone = ByteArrayOutputStream()
    events.subscribe(phone)
    assertEquals(BundleEvents.event(3), phone.toString(), "a device that connects after a publish still hears of it")
    newest = 4
    events.announce()
    assertEquals(BundleEvents.event(3) + BundleEvents.event(4), phone.toString())
    assertEquals("event: bundle\ndata: {\"version\":4}\n\n", BundleEvents.event(4))
  }

  @Test fun anEmptyStoreSaysSo() {
    newest = null
    val phone = ByteArrayOutputStream()
    events.subscribe(phone)
    assertEquals("event: bundle\ndata: {\"version\":null}\n\n", phone.toString())
  }

  @Test fun aListenerThatWentAwayIsDroppedAndTheOthersStillHear() {
    val gone = object : OutputStream() {
      var fail = false
      override fun write(b: Int) { if (fail) throw IOException("broken pipe") }
    }
    val phone = ByteArrayOutputStream()
    events.subscribe(gone)
    events.subscribe(phone)
    assertEquals(2, events.listening)
    gone.fail = true
    newest = 5
    events.announce()
    assertEquals(1, events.listening, "the closed connection is no longer counted as a device listening")
    assertTrue(phone.toString().endsWith(BundleEvents.event(5)))
  }

  @Test fun aConnectionThatFailsAtOnceIsNotCounted() {
    events.subscribe(object : OutputStream() { override fun write(b: Int) = throw IOException("reset") })
    assertEquals(0, events.listening)
  }
}
