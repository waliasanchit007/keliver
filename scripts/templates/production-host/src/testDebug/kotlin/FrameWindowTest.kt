package @@PACKAGE@@

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guest frame profile's window (M4): its line, its percentiles and when it ends. The iOS host
 * carries a byte-identical KeliverFrames.kt.
 */
class FrameWindowTest {
  private val ms = 1_000_000L

  @Test fun anEmptyWindowHasNoLine() {
    assertNull(FrameWindow().take())
  }

  @Test fun aWindowReportsEachSpanAsP50P95MaxInMicroseconds() {
    val window = FrameWindow()
    for (i in 1..20) {
      window.frame(guestNanos = i * ms, changeLists = 1)
      window.changeList(leadNanos = i * ms / 2, sinkNanos = 30_000L, count = 3)
      window.applied(queueNanos = 100_000L, applyNanos = i * 200_000L)
    }
    assertEquals(
      "n=20 empty=0 lists=20 changes=60 guest=10000/19000/20000 lead=5000/9500/10000 " +
        "sink=30/30/30 apply=2000/3800/4000 queue=100/100/100",
      window.take(),
    )
  }

  @Test fun framesThatSendNothingAreCountedAsEmpty() {
    val window = FrameWindow()
    window.frame(guestNanos = 2 * ms, changeLists = 0)
    window.frame(guestNanos = 4 * ms, changeLists = 0)
    assertEquals("n=2 empty=2 lists=0 changes=0 guest=2000/4000/4000 lead=- sink=- apply=- queue=-", window.take())
  }

  @Test fun aChangeListOutsideAFrameHasNoLead() {
    val window = FrameWindow()
    window.changeList(leadNanos = null, sinkNanos = 50_000L, count = 12)
    window.applied(queueNanos = 1 * ms, applyNanos = 3 * ms)
    assertEquals("n=0 empty=0 lists=1 changes=12 guest=- lead=- sink=50/50/50 apply=3000/3000/3000 queue=1000/1000/1000", window.take())
  }

  @Test fun takingAWindowStartsAnEmptyOne() {
    val window = FrameWindow()
    window.frame(guestNanos = ms, changeLists = 1)
    window.take()
    assertNull(window.take())
    window.frame(guestNanos = 3 * ms, changeLists = 0)
    assertEquals("n=1 empty=1 lists=0 changes=0 guest=3000/3000/3000 lead=- sink=- apply=- queue=-", window.take())
  }

  @Test fun aWindowIsFullAtItsSize() {
    val window = FrameWindow(size = 3)
    repeat(2) { window.frame(guestNanos = ms, changeLists = 1) }
    assertFalse(window.isFull)
    window.frame(guestNanos = 9 * ms, changeLists = 1)
    assertTrue(window.isFull)
  }

  @Test fun aFrameAddedPastItsSizeIsKeptUpToTwiceTheSize() {
    val window = FrameWindow(size = 2)
    window.frame(guestNanos = ms, changeLists = 5)
    repeat(5) { window.changeList(leadNanos = it * ms, sinkNanos = ms, count = 1) }
    assertTrue(window.isFull)
    assertEquals(
      "n=1 empty=0 lists=5 changes=5 guest=1000/1000/1000 lead=1000/3000/3000 sink=1000/1000/1000 apply=- queue=-",
      window.take(),
    )
  }

  @Test fun theOrderOfSamplesDoesNotMatter() {
    val window = FrameWindow()
    listOf(7L, 1L, 5L, 3L).forEach { window.frame(guestNanos = it * ms, changeLists = 1) }
    assertEquals("n=4 empty=0 lists=0 changes=0 guest=3000/7000/7000 lead=- sink=- apply=- queue=-", window.take())
  }
}
