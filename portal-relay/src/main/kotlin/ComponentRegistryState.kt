import dev.keliver.portal.ComponentEventSpec
import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropSpec
import dev.keliver.portal.serializeTree
import dev.keliver.portal.ingest.Recognizer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * C1: per-project component registries (project isolation, thread-safe). Each
 * project holds an immutable [ComponentRegistry] snapshot rebuilt whenever any
 * of its component files changes. Rebuild is a deterministic two-pass parse
 * (pass 1 discovers names; pass 2 resolves nested component calls) plus cycle
 * detection, so the result never depends on file-event ordering.
 */
object Components {
  private val byProject = ConcurrentHashMap<String, ComponentRegistry>()

  fun registry(project: String): ComponentRegistry = byProject[project] ?: EmptyComponentRegistry

  /** Rebuild [project]'s registry from all .kt files in [dir]. Returns the new registry. */
  fun rebuild(project: String, dir: File): ComponentRegistry {
    val files = (dir.listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray())
      .sortedBy { it.name } // deterministic order
    if (files.isEmpty()) {
      byProject.remove(project)
      return EmptyComponentRegistry
    }
    // Pass 1: names only (nested calls may RawCode here — we just need the set).
    val names = files.mapNotNull { runCatching { Recognizer.recognizeComponent(it.name, it.readText()) }.getOrNull()?.spec?.name }.toSet()
    val skeleton = MapComponentRegistry(names.map { ComponentSpec(it, emptyList(), emptyList(), emptyMap()) })
    // Pass 2: full recognition with all names known → nested calls resolve.
    val specs = LinkedHashMap<String, ComponentSpec>()
    for (f in files) {
      val rc = runCatching { Recognizer.recognizeComponent(f.name, f.readText(), skeleton) }.getOrNull() ?: continue
      specs[rc.spec.name] = rc.spec
    }
    val withCycles = flagCycles(specs)
    val reg = MapComponentRegistry(withCycles.values)
    byProject[project] = reg
    return reg
  }

  /** Mark every component on a dependency cycle opaque with a diagnostic (safety net for preview). */
  private fun flagCycles(specs: Map<String, ComponentSpec>): Map<String, ComponentSpec> {
    val onCycle = mutableSetOf<String>()
    val visiting = mutableSetOf<String>()
    val done = mutableSetOf<String>()
    fun dfs(name: String, stack: List<String>) {
      if (name in done) return
      if (name in visiting) {
        // stack contains the cycle members from the first occurrence of name.
        val start = stack.indexOf(name)
        if (start >= 0) onCycle += stack.subList(start, stack.size)
        onCycle += name
        return
      }
      visiting += name
      specs[name]?.dependencies?.forEach { dfs(it, stack + name) }
      visiting -= name
      done += name
    }
    specs.keys.forEach { dfs(it, emptyList()) }
    if (onCycle.isEmpty()) return specs
    return specs.mapValues { (n, s) ->
      if (n in onCycle) s.copy(transparent = false, body = null,
        diagnostic = "cyclic component dependency involving $n") else s
    }
  }

  /** Deterministic JSON for the /components endpoint (editor + preview consume this). */
  fun toJson(project: String): String {
    val reg = registry(project)
    val specs = reg.names().sorted().mapNotNull { reg.spec(it) }
    val sb = StringBuilder("[")
    specs.forEachIndexed { i, s ->
      if (i > 0) sb.append(",")
      sb.append(specJson(s))
    }
    sb.append("]")
    return sb.toString()
  }

  private fun specJson(s: ComponentSpec): String {
    fun str(x: String) = "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val props = s.props.joinToString(",") { propJson(it) }
    val events = s.events.joinToString(",") { eventJson(it) }
    val defaults = s.defaults.entries.joinToString(",") { (k, v) -> "${str(k)}:${defaultJson(v)}" }
    val deps = s.dependencies.sorted().joinToString(",") { str(it) }
    val body = s.body?.let { serializeTree(it) } ?: "null"
    return "{" +
      "\"name\":${str(s.name)}," +
      "\"props\":[$props]," +
      "\"events\":[$events]," +
      "\"defaults\":{$defaults}," +
      "\"transparent\":${s.transparent}," +
      "\"dependencies\":[$deps]," +
      (s.diagnostic?.let { "\"diagnostic\":${str(it)}," } ?: "") +
      "\"body\":$body" +
      "}"
  }

  private fun propJson(p: PropSpec): String =
    "{\"name\":\"${p.name}\",\"kind\":\"${p.kind.name}\",\"label\":\"${p.label}\"}"

  private fun eventJson(e: ComponentEventSpec): String =
    "{\"name\":\"${e.name}\"" + (e.paramType?.let { ",\"paramType\":\"$it\"" } ?: "") + "}"

  private fun defaultJson(v: Any?): String = when (v) {
    null -> "null"
    is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    is Boolean, is Int, is Double -> v.toString()
    else -> "\"$v\""
  }
}
