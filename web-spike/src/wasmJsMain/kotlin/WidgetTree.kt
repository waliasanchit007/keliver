/*
 * spike/keliver-web portal sub-project A — the WidgetNode tree model.
 * A generic, name-keyed description of a keliver composition: the single source
 * of truth the portal edits. In-memory Any? values are enough for the web-first
 * MVP (portal + preview are one app); serializable values come in M2.
 */

/** One node = a widget type, its properties (by name), and children. */
class WidgetNode(
  val type: String,
  val props: Map<String, Any?> = emptyMap(),
  val children: List<WidgetNode> = emptyList(),
)

fun WidgetNode.str(key: String, default: String = ""): String = props[key] as? String ?: default
fun WidgetNode.int(key: String, default: Int = 0): Int = props[key] as? Int ?: default
fun WidgetNode.bool(key: String, default: Boolean = false): Boolean = props[key] as? Boolean ?: default
fun WidgetNode.dbl(key: String, default: Double = 0.0): Double = props[key] as? Double ?: default

@Suppress("UNCHECKED_CAST")
fun WidgetNode.intList(key: String): List<Int> = props[key] as? List<Int> ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun WidgetNode.floatList(key: String): List<Float> = props[key] as? List<Float> ?: emptyList()
