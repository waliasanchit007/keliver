import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Live device updates: the relay's `GET /bundles/events` stream (BundleEvents.kt). */
class BundleEventsTest {
  private var newest: Int? = 3
  private val events = BundleEvents({ newest }, heartbeatSeconds = 3600, maxListeners = 3)

  @AfterTest fun close() = events.close()

  @Test fun aListenerHearsTheNewestBundleAtOnceAndAfterEachPublish() {
    val phone = ByteArrayOutputStream()
    events.subscribe(phone)
    events.drain()
    assertEquals(BundleEvents.event(3), phone.toString(), "a device that connects after a publish still hears of it")
    newest = 4
    events.announce()
    events.drain()
    assertEquals(BundleEvents.event(3) + BundleEvents.event(4), phone.toString())
    assertEquals("event: bundle\ndata: {\"version\":4}\n\n", BundleEvents.event(4))
  }

  @Test fun anEmptyStoreSaysSo() {
    newest = null
    val phone = ByteArrayOutputStream()
    events.subscribe(phone)
    events.drain()
    assertEquals("event: bundle\ndata: {\"version\":null}\n\n", phone.toString())
  }

  @Test fun aListenerThatWentAwayIsDroppedAndItsConnectionClosed() {
    val gone = object : OutputStream() {
      var fail = false
      override fun write(b: Int) { if (fail) throw IOException("broken pipe") }
    }
    var closed = 0
    val phone = ByteArrayOutputStream()
    events.subscribe(gone) { closed++ }
    events.subscribe(phone)
    events.drain()
    assertEquals(2, events.listening)
    gone.fail = true
    newest = 5
    events.announce()
    events.drain()
    assertEquals(1, events.listening, "the closed connection is no longer counted as a device listening")
    assertEquals(1, closed, "the connection itself is closed (the exchange), not only its stream")
    assertTrue(phone.toString().endsWith(BundleEvents.event(5)))
  }

  @Test fun aPublishNeverWaitsForAPhoneThatStoppedReading() {
    val stalled = CountDownLatch(1)
    val stuck = object : OutputStream() {
      override fun write(b: Int) { stalled.await(10, TimeUnit.SECONDS) } // a full send buffer
    }
    events.subscribe(stuck)
    val start = System.nanoTime()
    repeat(3) { events.announce() }
    val tookMillis = (System.nanoTime() - start) / 1_000_000
    assertTrue(tookMillis < 1_000, "announce() returned in $tookMillis ms while a listener was stuck")
    stalled.countDown()
  }

  @Test fun moreDevicesThanTheLimitAreRefused() {
    repeat(3) { events.subscribe(ByteArrayOutputStream()) }
    assertFalse(events.hasRoom())
  }

  @Test fun aConnectionThatFailsAtOnceIsNotCounted() {
    events.subscribe(object : OutputStream() { override fun write(b: Int) = throw IOException("reset") })
    events.drain()
    assertEquals(0, events.listening)
  }
}
