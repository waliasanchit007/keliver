/*
 * V2 M8 — capability-driven preview fidelity. The browser preview runs the
 * REAL logic path and auto-substitutes a PREVIEW IMPLEMENTATION for each host
 * capability it can (SQL -> in-memory). Capabilities with no preview impl
 * (Camera, BLE, Biometrics, …) are STUBBED and the surface is marked
 * reduced-fidelity. So fidelity is an EMERGENT property of the app's
 * capability graph, not a separate "mock vs real" mode.
 */

/** A capability's status in the browser preview. */
data class CapStatus(val name: String, val real: Boolean, val note: String)

object PreviewCapabilities {
  /** capability name@version -> preview provider label (absent = stubbed). */
  private val providers: Map<String, String> = mapOf(
    "HostSqlDriver@1" to "in-memory SQLite",
    // convergence targets: "HostHttp@1" to "browser fetch()", "HostStorage@1" to "localStorage"
  )

  fun statusOf(cap: String): CapStatus =
    providers[cap]?.let { CapStatus(cap, real = true, note = "preview impl: $it") }
      ?: CapStatus(cap, real = false, note = "no preview impl — stubbed (reduced fidelity)")

  fun report(required: List<String>): List<CapStatus> = required.map { statusOf(it) }

  /** Full fidelity only when EVERY required capability has a preview impl. */
  fun isFullFidelity(required: List<String>): Boolean = required.all { statusOf(it).real }

  /** True when the SQL capability can back the real data path in-browser. */
  val sqlAvailable: Boolean get() = "HostSqlDriver@1" in providers
}

/**
 * The preview substitute for HostSqlDriver@1: an in-memory store understanding
 * the tiny SQL the demo data layer uses. Mirrors the production wire so the
 * REAL query strings run unchanged — only the executor is swapped.
 */
class PreviewSqlHost {
  private val rows = mutableListOf<List<String?>>()

  fun execute(sql: String, args: List<String?>): List<List<String?>> {
    val s = sql.trim()
    return when {
      s.startsWith("CREATE TABLE", ignoreCase = true) -> emptyList()
      s.startsWith("INSERT", ignoreCase = true) -> { rows.add(args); emptyList() }
      s.startsWith("DELETE", ignoreCase = true) -> { rows.clear(); emptyList() }
      s.startsWith("SELECT COUNT", ignoreCase = true) -> listOf(listOf(rows.size.toString()))
      s.startsWith("SELECT", ignoreCase = true) -> rows.toList()
      else -> emptyList()
    }
  }
}
