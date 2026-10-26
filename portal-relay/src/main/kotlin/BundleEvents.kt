import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Live device updates (adoption item 6): `GET /bundles/events` is a server-sent
 * event stream that says "a new bundle is in the store" — on connect, and after
 * every publish that stored one. A debug host that holds it open while a screen
 * is on show runs its ordinary update check on each event, so a publish reaches
 * an open app without a reopen.
 *
 * An event is only a nudge. It carries the newest bundle version and nothing a
 * host acts on directly: the host reads `bundles/index.json` and verifies the
 * manifest's signature as on any other update, so a forged or replayed event
 * can at most make it look again. The stream is read-only and lives under
 * `/bundles/`, like everything a device may be given.
 *
 * Every write happens on this class's own thread, never on the relay's request
 * thread: a phone that stops reading (its send buffer full) can delay events,
 * but never a publish or any other request.
 */
internal class BundleEvents(
  /** The newest bundle version in the store, or null when there is none. */
  private val newest: () -> Int?,
  heartbeatSeconds: Long = 15,
  /** At most this many devices listen at once; more are refused (503). */
  private val maxListeners: Int = 32,
) {
  /** A listening device: its stream, and how to close its connection (the exchange, not just the stream). */
  private class Listener(val out: OutputStream, val close: () -> Unit)

  private val listeners = CopyOnWriteArrayList<Listener>()

  private val writer = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "bundle-events").apply { isDaemon = true }
  }.also {
    // A comment every heartbeatSeconds: proxies and phones drop an idle
    // connection, and a write is how a listener that went away is noticed.
    it.scheduleAtFixedRate({ sendAll(": keep-alive\n\n") }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS)
  }

  /** How many devices are listening now (the editor's devices chip shows it). */
  val listening: Int get() = listeners.size

  /** Whether one more device may listen. */
  fun hasRoom(): Boolean = listeners.size < maxListeners

  /** Adds [out] and sends it the store's newest version; [close] closes its connection when it goes away. */
  fun subscribe(out: OutputStream, close: () -> Unit = { out.close() }) {
    val listener = Listener(out, close)
    listeners += listener
    writer.execute { if (!send(listener, event(newest()))) drop(listener) }
  }

  /** Tells every listener the store changed; call after a bundle is completely stored. Returns at once. */
  fun announce() = writer.execute { sendAll(event(newest())) }

  /** Waits until every write asked for so far has been made (tests). */
  internal fun drain() = writer.submit {}.get(5, TimeUnit.SECONDS)

  fun close() {
    writer.shutdownNow()
    listeners.forEach { runCatching { it.close() } }
    listeners.clear()
  }

  private fun sendAll(text: String) {
    for (l in listeners) if (!send(l, text)) drop(l)
  }

  private fun send(l: Listener, text: String): Boolean = try {
    l.out.write(text.toByteArray())
    l.out.flush()
    true
  } catch (_: IOException) {
    false
  }

  /**
   * Forgets [l] and closes its connection. Through the exchange: closing only
   * the stream of a broken connection throws before the connection is released,
   * and its file descriptor stayed open for the relay's lifetime.
   */
  private fun drop(l: Listener) {
    listeners -= l
    runCatching { l.close() }
  }

  internal companion object {
    fun event(version: Int?): String = "event: bundle\ndata: {\"version\":${version ?: "null"}}\n\n"
  }
}
