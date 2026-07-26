/*
 * V2 M8 — capability-driven preview fidelity. The browser preview runs the
 * REAL logic path and auto-substitutes a PREVIEW IMPLEMENTATION for each host
 * capability it can (SQL -> in-memory). Capabilities with no preview impl
 * (Camera, BLE, Biometrics, …) are STUBBED and the surface is marked
 * reduced-fidelity. So fidelity is an EMERGENT property of the app's
 * capability graph, not a separate "mock vs real" mode.
 */

import dev.keliver.capabilities.HOST_HTTP_CAPABILITY
import dev.keliver.portal.render.PreviewPersona
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class PreviewHttpFixtureDescriptor(
  val id: String,
  val revision: String?,
  val entries: Int,
  val expiresAt: String?,
  val expired: Boolean,
  val valid: Boolean,
  val error: String? = null,
)

/** A capability's status in the browser preview. */
public data class CapStatus(val name: String, val real: Boolean, val note: String)

public object PreviewCapabilities {
  /** capability name@version -> preview provider label (absent = stubbed). */
  private val providers: Map<String, String> = mapOf(
    "HostSqlDriver@1" to "in-memory SQLite",
    // convergence targets: "HostHttp@1" to "browser fetch()", "HostStorage@1" to "localStorage"
  )
  private var httpFixtures: Map<String, PreviewHttpFixtureDescriptor> = emptyMap()
  private var httpCatalogError: String? = "fixture catalog not loaded"
  private var httpSessionMiss: String? = null

  public fun statusOf(cap: String): CapStatus = statusOf(cap, persona = null)

  public fun statusOf(cap: String, persona: PreviewPersona?): CapStatus =
    if (cap == HOST_HTTP_CAPABILITY) {
      httpStatus(persona)
    } else persona?.fixtureStates()?.get(cap)?.let {
      CapStatus(cap, real = true, note = "persona fixture: $it")
    } ?: providers[cap]?.let { CapStatus(cap, real = true, note = "preview impl: $it") }
      ?: CapStatus(cap, real = false, note = "no preview impl — stubbed (reduced fidelity)")

  public fun report(required: List<String>): List<CapStatus> = required.map { statusOf(it) }

  public fun report(required: List<String>, persona: PreviewPersona?): List<CapStatus> =
    required.map { statusOf(it, persona) }

  /** Full fidelity only when EVERY required capability has a preview impl. */
  public fun isFullFidelity(required: List<String>): Boolean = required.all { statusOf(it).real }

  public fun isFullFidelity(required: List<String>, persona: PreviewPersona?): Boolean =
    required.all { statusOf(it, persona).real }

  /** True when the SQL capability can back the real data path in-browser. */
  public val sqlAvailable: Boolean get() = "HostSqlDriver@1" in providers

  internal fun updateHttpFixtureCatalog(payload: String) {
    runCatching {
      Json.decodeFromString<List<PreviewHttpFixtureDescriptor>>(payload)
    }.onSuccess { descriptors ->
      httpFixtures = descriptors.associateBy { it.id }
      httpCatalogError = null
    }.onFailure {
      httpFixtures = emptyMap()
      httpCatalogError = "invalid fixture catalog"
    }
  }

  internal fun httpCatalogUnavailable(reason: String) {
    httpFixtures = emptyMap()
    httpCatalogError = reason
  }

  internal fun beginHttpSession() {
    httpSessionMiss = null
  }

  internal fun markHttpMiss(reason: String) {
    httpSessionMiss = reason
  }

  private fun httpStatus(persona: PreviewPersona?): CapStatus {
    httpCatalogError?.let {
      return CapStatus(HOST_HTTP_CAPABILITY, real = false, note = it)
    }
    val fixtureSet = persona?.httpFixtureSet
      ?: return CapStatus(
        HOST_HTTP_CAPABILITY,
        real = false,
        note = "no fixture set selected by persona",
      )
    val descriptor = httpFixtures[fixtureSet]
      ?: return CapStatus(
        HOST_HTTP_CAPABILITY,
        real = false,
        note = "fixture set '$fixtureSet' not found",
      )
    if (!descriptor.valid) {
      return CapStatus(
        HOST_HTTP_CAPABILITY,
        real = false,
        note = "fixture set '$fixtureSet' invalid: ${descriptor.error ?: "unknown error"}",
      )
    }
    if (descriptor.expired) {
      return CapStatus(
        HOST_HTTP_CAPABILITY,
        real = false,
        note = "fixture set '$fixtureSet' expired",
      )
    }
    httpSessionMiss?.let {
      return CapStatus(HOST_HTTP_CAPABILITY, real = false, note = "replay miss: $it")
    }
    return CapStatus(
      HOST_HTTP_CAPABILITY,
      real = true,
      note = "replay: $fixtureSet (${descriptor.entries} exchanges)",
    )
  }
}

/**
 * The preview substitute for HostSqlDriver@1: an in-memory store understanding
 * the SQL shapes the app data layers use (per-table storage, rowid synthesis,
 * WHERE rowid, ORDER BY rowid DESC). Mirrors the production wire so the REAL
 * query strings run unchanged — only the executor is swapped. P3-12 upgraded
 * it from single-table to per-table so multiple live presenters coexist.
 */
public class PreviewSqlHost {
  private val tables = mutableMapOf<String, MutableList<List<String?>>>()

  private fun tableOf(sql: String): String {
    val m = Regex("(?:INTO|FROM|EXISTS|TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?)\\s+([A-Za-z_][A-Za-z0-9_]*)", RegexOption.IGNORE_CASE)
      .findAll(sql).lastOrNull() ?: return "_"
    return m.groupValues[1].lowercase()
  }

  public fun execute(sql: String, args: List<String?>): List<List<String?>> {
    val s = sql.trim()
    val rows = tables.getOrPut(tableOf(s)) { mutableListOf() }
    val wantsRowid = s.contains("rowid", ignoreCase = true)
    fun withRowid(): List<List<String?>> = rows.mapIndexed { i, r -> listOf((i + 1).toString()) + r }
    return when {
      s.startsWith("CREATE TABLE", ignoreCase = true) -> emptyList()
      s.startsWith("INSERT", ignoreCase = true) -> { rows.add(args); emptyList() }
      s.startsWith("DELETE", ignoreCase = true) -> { rows.clear(); emptyList() }
      s.startsWith("SELECT COUNT", ignoreCase = true) -> listOf(listOf(rows.size.toString()))
      s.startsWith("SELECT", ignoreCase = true) && s.contains("WHERE rowid", ignoreCase = true) -> {
        val id = args.firstOrNull()?.toIntOrNull() ?: return emptyList()
        withRowid().filter { it.firstOrNull() == id.toString() }
      }
      s.startsWith("SELECT", ignoreCase = true) && wantsRowid -> {
        val all = withRowid()
        if (s.contains("ORDER BY rowid DESC", ignoreCase = true)) all.reversed() else all
      }
      s.startsWith("SELECT", ignoreCase = true) -> rows.toList()
      else -> emptyList()
    }
  }
}
