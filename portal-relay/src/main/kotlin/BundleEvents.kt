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
 */
internal class BundleEvents(
  /** The newest bundle version in the store, or null when there is none. */
  private val newest: () -> Int?,
  heartbeatSeconds: Long = 15,
) {
  private val listeners = CopyOnWriteArrayList<OutputStream>()

  /**
   * A comment line every [heartbeatSeconds]: proxies and phones drop an idle
   * connection, and a write is how a listener that went away is noticed.
   */
  private val heartbeat = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "bundle-events").apply { isDaemon = true }
  }.also { it.scheduleAtFixedRate({ sendAll(": keep-alive\n\n") }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS) }

  /** How many devices are listening now (the editor's devices chip shows it). */
  val listening: Int get() = listeners.size

  /** Adds [out] and sends it the store's newest version at once. */
  fun subscribe(out: OutputStream) {
    listeners += out
    if (!send(out, event(newest()))) listeners -= out
  }

  /** Tells every listener the store changed; call after a bundle is completely stored. */
  fun announce() = sendAll(event(newest()))

  fun close() {
    heartbeat.shutdownNow()
    listeners.forEach { runCatching { it.close() } }
    listeners.clear()
  }

  private fun sendAll(text: String) {
    for (out in listeners) if (!send(out, text)) listeners -= out
  }

  private fun send(out: OutputStream, text: String): Boolean = try {
    synchronized(out) {
      out.write(text.toByteArray())
      out.flush()
    }
    true
  } catch (_: IOException) {
    runCatching { out.close() }
    false
  }

  internal companion object {
    fun event(version: Int?): String = "event: bundle\ndata: {\"version\":${version ?: "null"}}\n\n"
  }
}
