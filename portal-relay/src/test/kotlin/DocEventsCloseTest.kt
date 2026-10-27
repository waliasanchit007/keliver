import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * /doc-events: a browser that went away (a closed or reloaded editor tab) is
 * dropped AND its connection closed. Dropping it without closing the exchange
 * left the relay holding one file descriptor per tab for its lifetime.
 */
class DocEventsCloseTest {
  private fun service(): DocumentService {
    val dir = Files.createTempDirectory("doc-events").toFile().apply { deleteOnExit() }
    val file = File(dir, "home.kt").apply {
      writeText(
        """
        package app.screens

        import androidx.compose.runtime.Composable
        import dev.keliver.layout.compose.Column

        @Composable
        fun HomeScreen() {
          Column {
          }
        }
        """.trimIndent() + "\n",
      )
    }
    return DocumentService.fromFileOrTree(
      screenKey = "doc-events-${System.nanoTime()}/home",
      treeJson = null,
      onProjected = {},
      kotlinFile = file,
      functionName = "HomeScreen",
      packageName = "app.screens",
    )
  }

  @Test fun aTabThatWentAwayIsClosedNotOnlyForgotten() {
    val svc = service()
    val gone = object : OutputStream() {
      var fail = false
      override fun write(b: Int) { if (fail) throw IOException("broken pipe") }
    }
    var closed = 0
    val open = ByteArrayOutputStream()
    svc.subscribe(gone) { closed++ }
    svc.subscribe(open)
    gone.fail = true
    svc.announce()
    assertEquals(1, closed, "the broken connection is closed through its exchange")
    svc.announce()
    assertEquals(1, closed, "and forgotten: it is not written to (or closed) again")
    assertTrue(open.toString().split("data: ").size >= 4, "the open tab still hears every event: $open")
  }

  @Test fun eventsToOneTabNeverInterleave() {
    val svc = service()
    val writing = java.util.concurrent.atomic.AtomicInteger()
    var overlapped = false
    val tab = object : OutputStream() {
      override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
      override fun write(b: ByteArray, off: Int, len: Int) {
        if (writing.incrementAndGet() > 1) overlapped = true
        Thread.sleep(1) // a slow socket widens the window
        writing.decrementAndGet()
      }
    }
    svc.subscribe(tab)
    val threads = List(4) { Thread { repeat(25) { svc.announce() } } }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    assertTrue(!overlapped, "two events were written to one stream at the same time")
  }

  @Test fun aConnectionThatFailsOnTheFirstEventIsClosedAtOnce() {
    var closed = 0
    service().subscribe(object : OutputStream() { override fun write(b: Int) = throw IOException("reset") }) { closed++ }
    assertEquals(1, closed)
  }
}
