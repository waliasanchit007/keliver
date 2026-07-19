import dev.keliver.portal.WidgetNode
import dev.keliver.portal.deriveFlowEdges
import dev.keliver.portal.flow.FlowSpec
import dev.keliver.portal.ingest.FlowRecognizer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * #13 F1: per-project flow declarations, parsed from the flows/ dir (the same
 * .kt files the app compiles for runtime nav — single source of truth).
 * Rebuilt whole-dir on any flows/ change, like [Components].
 */
object Flows {
  private val byProject = ConcurrentHashMap<String, Map<String, FlowSpec>>()

  fun specs(project: String): Map<String, FlowSpec> = byProject[project] ?: emptyMap()

  fun rebuild(project: String, dir: File): Map<String, FlowSpec> {
    val files = (dir.listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray()).sortedBy { it.name }
    val specs = LinkedHashMap<String, FlowSpec>()
    for (f in files) {
      val spec = runCatching { FlowRecognizer.recognize(f.name, f.readText()) }.getOrNull() ?: continue
      specs[spec.name] = spec
    }
    if (specs.isEmpty()) byProject.remove(project) else byProject[project] = specs
    return specs
  }

  /**
   * /flow JSON: each declared flow with its routes plus the nav graph DERIVED
   * against the project's CURRENT screen trees (nodes = start + targets + any
   * screen an edge leaves from; edges from [deriveFlowEdges]).
   */
  fun toJson(project: String, screens: Map<String, WidgetNode>): String {
    fun str(x: String) = "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val sb = StringBuilder("[")
    specs(project).values.forEachIndexed { i, s ->
      if (i > 0) sb.append(",")
      val edges = deriveFlowEdges(s.routes, screens)
      val nodes = (listOf(s.start) + s.routes.values + edges.map { it.from }).distinct()
      sb.append("{")
        .append("\"name\":${str(s.name)},")
        .append("\"start\":${str(s.start)},")
        .append("\"routes\":{")
        .append(s.routes.entries.joinToString(",") { (k, v) -> "${str(k)}:${str(v)}" })
        .append("},")
        .append("\"nodes\":[").append(nodes.joinToString(",") { str(it) }).append("],")
        .append("\"edges\":[")
        .append(edges.joinToString(",") { "{\"from\":${str(it.from)},\"key\":${str(it.key)},\"to\":${str(it.to)}}" })
        .append("]}")
    }
    return sb.append("]").toString()
  }
}
