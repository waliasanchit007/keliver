package dev.keliver.portal

private var nodeIdCounter = 0
fun nextNodeId(): Int = ++nodeIdCounter

/** Highest id anywhere in this tree. */
fun WidgetNode.maxId(): Int = maxOf(id, children.maxOfOrNull { it.maxId() } ?: 0)

/**
 * After loading a persisted tree (whose ids came from an earlier session), lift
 * the id counter above them so freshly created nodes can't collide.
 */
fun ensureNodeIdsAbove(min: Int) {
  if (min > nodeIdCounter) nodeIdCounter = min
}

/** One node = a widget type, its properties (by name), children, and a stable id. */
data class WidgetNode(
  val type: String,
  val props: Map<String, Any?> = emptyMap(),
  val children: List<WidgetNode> = emptyList(),
  val id: Int = nextNodeId(),
)

fun WidgetNode.str(key: String, default: String = ""): String = props[key] as? String ?: default
fun WidgetNode.int(key: String, default: Int = 0): Int = props[key] as? Int ?: default
fun WidgetNode.bool(key: String, default: Boolean = false): Boolean = props[key] as? Boolean ?: default
fun WidgetNode.dbl(key: String, default: Double = 0.0): Double = props[key] as? Double ?: default

@Suppress("UNCHECKED_CAST")
fun WidgetNode.intList(key: String): List<Int> = props[key] as? List<Int> ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun WidgetNode.floatList(key: String): List<Float> = props[key] as? List<Float> ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun WidgetNode.strList(key: String): List<String> = props[key] as? List<String> ?: emptyList()

/** The shared sample tree: a card whose item count is data-driven. */
fun sampleTree(items: Int): WidgetNode = WidgetNode(
  type = "StyledBox",
  props = mapOf(
    "gradientColorsArgb" to listOf(0xFFFFF4E8.toInt(), 0xFFFFE9D6.toInt()),
    "gradientStops" to listOf(0.0f, 1.0f),
    "gradientDirection" to 3,
    "borderColorArgb" to 0xFFFFD9B0.toInt(),
    "borderWidthDp" to 1,
    "cornerRadiusDp" to 12,
    "fillWidth" to true,
    "paddingDp" to 20,
  ),
  children = listOf(
    WidgetNode(
      type = "Column",
      children = buildList {
        add(WidgetNode("StyledText", mapOf("text" to "PORTAL PREVIEW · rendered from a tree", "fontSize" to 12, "bold" to true, "colorArgb" to 0xFF8A8A8A.toInt())))
        add(WidgetNode("Spacer", mapOf("height" to 10.0)))
        add(WidgetNode("StyledText", mapOf("text" to "items: $items", "fontSize" to 24, "bold" to true, "colorArgb" to 0xFF111111.toInt())))
        add(WidgetNode("Spacer", mapOf("height" to 8.0)))
        repeat(items) { i ->
          add(WidgetNode("StyledText", mapOf("text" to "• item ${i + 1}", "fontSize" to 16, "colorArgb" to 0xFF333333.toInt())))
        }
      },
    ),
  ),
)
