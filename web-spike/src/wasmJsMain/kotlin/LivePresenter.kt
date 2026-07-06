/*
 * V2 M8 — the live presenter drives the preview's bindings from REAL logic
 * running against preview-substituted capabilities. When enabled, it takes
 * over PreviewBindings: computes each contract field's value from the running
 * logic and routes wired actions into real state transitions (persisted via
 * the in-memory SQL provider). Disable -> back to static mocks.
 *
 * NOTE (convergence): this reference presenter mirrors the app's real
 * MainPresenter (a SQLite-persisted tap counter) using the SAME query strings.
 * Generalizing to arbitrary project logic = the per-app preview build (a
 * documented next increment); the CAPABILITY-FIDELITY MODEL here is the M8
 * deliverable and is project-agnostic.
 */
import dev.keliver.portal.render.PreviewBindings

object LivePresenter {
  var enabled: Boolean = false
    private set

  private var sql: PreviewSqlHost? = null
  private var taps: Long = 0
  private var textField: String? = null // the contract field the presenter feeds

  /** Called with the screen's contract field names when Live mode turns on. */
  fun enable(fields: List<String>, onState: () -> Unit) {
    enabled = true
    // Substitute the SQL capability if available; run the real data path.
    sql = if (PreviewCapabilities.sqlAvailable) PreviewSqlHost() else null
    sql?.execute("CREATE TABLE IF NOT EXISTS taps (at TEXT)", emptyList())
    taps = sql?.execute("SELECT COUNT(*) FROM taps", emptyList())?.firstOrNull()?.firstOrNull()?.toLongOrNull() ?: 0
    // Feed a String contract field (the reference app's `text`).
    textField = fields.firstOrNull { it == "text" } ?: fields.firstOrNull()
    pushState()
    onState()
  }

  fun disable() {
    enabled = false
    sql = null
    taps = 0
    textField = null
  }

  /** A wired action fired in the canvas — run the real transition + persist. */
  fun fireLive(action: String) {
    if (!enabled) return
    // Reference behavior: any wired action = a persisted "tap".
    taps += 1
    sql?.execute("INSERT INTO taps (at) VALUES (?)", listOf(taps.toString()))
    pushState()
  }

  private fun pushState() {
    val f = textField ?: return
    PreviewBindings.mocks[f] = when {
      sql == null -> "capability stubbed — logic degraded"
      taps == 0L -> "Compiled + SIGNED Kotlin from the portal"
      else -> "buyTapped ×$taps — persisted in SQLite (preview impl)"
    }
  }

  /** For the State Inspector: the live binding values the presenter produced. */
  fun stateSnapshot(): Map<String, String> =
    if (enabled) PreviewBindings.mocks.toMap() else emptyMap()
}
