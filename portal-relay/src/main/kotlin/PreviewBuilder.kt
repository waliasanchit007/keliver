import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * P3-12: the live-preview rebuild orchestrator. Logic/screen/component edits
 * [trigger] a DEBOUNCED (coalesced) single-flight build; a new trigger during a
 * build cancels it (gradle process destroyed) and schedules a fresh one; build
 * ids are monotonic and only the LATEST build's result is promoted — stale
 * results are rejected. Promotion (copying the dist to the serve dir) happens
 * ONLY on success, so the served editor is always the last-known-good build.
 *
 * The gradle/copy work is behind [Runner] so the state machine is unit-testable
 * without gradle or a browser.
 */
class PreviewBuilder(
  private val runner: Runner,
  private val debounceMs: Long = 1500,
) {
  interface Runner {
    /** Run the build; return null on success or an error summary on failure. May block. */
    fun build(cancelled: () -> Boolean): String?
    /** Promote the successful dist to the serve dir (atomic-ish copy). */
    fun promote()
  }

  data class Status(
    val id: Long,
    val state: String, // idle | building | ok | failed
    val error: String? = null,
    val atMillis: Long = 0,
  )

  private val exec = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "preview-builder").apply { isDaemon = true }
  }
  private val ids = AtomicLong(0)
  @Volatile private var pending: ScheduledFuture<*>? = null
  @Volatile private var runningId: Long = -1
  @Volatile var status: Status = Status(0, "idle")
    private set
  /** Bumped every time a build succeeds — pollers reload on change. */
  @Volatile var promotedId: Long = 0
    private set

  fun trigger() {
    val id = ids.incrementAndGet()
    pending?.cancel(false)
    pending = exec.schedule({ run(id) }, debounceMs, TimeUnit.MILLISECONDS)
    // Cancels an in-flight OLDER build cooperatively (runner polls cancelled()).
    runningId = id
  }

  private fun run(id: Long) {
    if (id != ids.get()) return // superseded while queued — stale, skip
    status = Status(id, "building", atMillis = System.currentTimeMillis())
    val err = runCatching { runner.build { id != ids.get() } }.getOrElse { it.message ?: "build crashed" }
    if (id != ids.get()) return // superseded while building — reject stale result
    if (err == null) {
      runCatching { runner.promote() }
        .onSuccess {
          promotedId = id
          status = Status(id, "ok", atMillis = System.currentTimeMillis())
        }
        .onFailure { status = Status(id, "failed", "promote failed: ${it.message}", System.currentTimeMillis()) }
    } else {
      // Last-known-good stays promoted; only the status reports the failure.
      status = Status(id, "failed", err, System.currentTimeMillis())
    }
  }

  fun statusJson(): String {
    val e = status.error?.replace("\\", "\\\\")?.replace("\"", "\\\"")?.replace("\n", "\\n")
    return "{\"id\":${status.id},\"promotedId\":$promotedId,\"state\":\"${status.state}\"" +
      (e?.let { ",\"error\":\"$it\"" } ?: "") + ",\"at\":${status.atMillis}}"
  }
}
