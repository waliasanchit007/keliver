/*
 * Copyright (C) 2026 Keliver contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
  /** False when the app declares no per-app editor build (empty previewBuildTask). */
  private val enabled: Boolean = true,
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
    // No per-app editor configured (empty previewBuildTask) — there is nothing
    // to build, and attempting it is how consumers used to get a permanent red
    // "preview build failed" chip against THIS repo's `web-spike` module.
    // Stays "idle" so existing editors render it neutrally; `enabled` in the
    // status JSON is the explicit signal.
    if (!enabled) return
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
    return "{\"id\":${status.id},\"promotedId\":$promotedId,\"enabled\":$enabled,\"state\":\"${status.state}\"" +
      (e?.let { ",\"error\":\"$it\"" } ?: "") + ",\"at\":${status.atMillis}}"
  }
}
