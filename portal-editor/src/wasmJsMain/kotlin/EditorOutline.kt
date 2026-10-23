/*
 * The outline's model: what a row says about its node, which rows are visible
 * under collapsed containers (keyboard navigation follows them), and where a
 * drop or an insert lands (E16/E17: before, after or inside a row — not always
 * at the end of the drop target).
 */
import dev.keliver.portal.ELSE_TYPE
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.condOf
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.findNode
import dev.keliver.portal.tokenValue

/** A short description of [node] for its outline row, or null when its type says it all. */
internal fun outlineHint(node: WidgetNode): String? = when (node.type) {
  "Condition" -> condOf(node)?.kotlin { it }?.let { "if $it" }
  ELSE_TYPE -> "else"
  "Repeat" -> (node.props["items"] as? String)?.takeIf { it.isNotBlank() }?.let { items ->
    "for ${(node.props["item"] as? String)?.takeIf { it.isNotBlank() } ?: "item"} in $items"
  }
  "Spacer" -> listOfNotNull(
    (node.props["height"] as? Number)?.let { "↕${trimNumber(it.toDouble())}" },
    (node.props["width"] as? Number)?.let { "↔${trimNumber(it.toDouble())}" },
  ).joinToString(" ").ifEmpty { null }
  else -> (tokenValue(node.props["text"]) as? String)?.let { "“$it”" }
    ?: node.props.values.firstNotNullOfOrNull { (tokenValue(it) as? String)?.takeIf { s -> s.isNotBlank() && s.length <= 40 } }?.let { "“$it”" }
}

private fun trimNumber(d: Double): String = if (d == kotlin.math.floor(d)) d.toInt().toString() else d.toString()

/** The rows the outline shows, in order: [collapsed] containers hide their descendants. */
internal fun visibleOrder(root: WidgetNode, collapsed: Set<Int>): List<Int> {
  val out = mutableListOf<Int>()
  fun walk(n: WidgetNode) {
    out += n.id
    if (n.id !in collapsed) n.children.forEach(::walk)
  }
  walk(root)
  return out
}

/** Where a dragged row or palette item lands relative to the row it is dropped on. */
internal enum class DropZone { Before, After, Inside }

/** The zone of a drop at [offsetY] in a row [height] tall: its top and bottom quarters are before/after. */
internal fun dropZone(offsetY: Double, height: Double, targetAcceptsChildren: Boolean): DropZone = when {
  !targetAcceptsChildren -> if (offsetY < height / 2) DropZone.Before else DropZone.After
  offsetY < height / 4 -> DropZone.Before
  offsetY > height * 3 / 4 -> DropZone.After
  else -> DropZone.Inside
}

/** A node's laid-out box on the canvas (compose px). */
internal class DropBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
  fun contains(x: Double, y: Double): Boolean = x in left..right && y in top..bottom
  infix fun union(o: DropBox): DropBox =
    DropBox(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
}

/**
 * F3: where a canvas drop at ([x], [y]) on [hit] (the node under the pointer) lands. A container
 * with laid-out children takes it between them — before or after the child nearest the pointer,
 * along the way they are laid out — not appended after its last child (out of sight in a long
 * column; in a box, drawn over the others). A point inside a child the hit test passed over goes
 * into that child. An empty container takes the drop inside (by its quarters); a leaf before or
 * after itself (by its halves).
 */
internal fun canvasDropPlace(
  root: WidgetNode,
  hit: Int,
  x: Double,
  y: Double,
  box: (WidgetNode) -> DropBox?,
  accepts: (WidgetNode) -> Boolean,
): Pair<Int, DropZone>? {
  val node = root.findNode(hit) ?: return null
  val kids = node.children.mapNotNull { c -> box(c)?.let { c to it } }
  if (accepts(node) && kids.isNotEmpty()) {
    kids.firstOrNull { it.second.contains(x, y) }?.let { return canvasDropPlace(root, it.first.id, x, y, box, accepts) }
    val sideBySide = kids.size > 1 && kids.zipWithNext().all { (a, b) -> b.second.left >= a.second.right - 1 }
    fun gap(lo: Double, hi: Double, p: Double) = if (p < lo) lo - p else if (p > hi) p - hi else 0.0
    val (kid, b) = kids.minBy { (_, b) -> if (sideBySide) gap(b.left, b.right, x) else gap(b.top, b.bottom, y) }
    val before = if (sideBySide) x < (b.left + b.right) / 2 else y < (b.top + b.bottom) / 2
    return kid.id to if (before) DropZone.Before else DropZone.After
  }
  if (hit == root.id) return hit to DropZone.Inside
  val b = box(node) ?: return hit to DropZone.Inside
  return hit to dropZone(y - b.top, b.bottom - b.top, accepts(node))
}

internal fun parentOf(root: WidgetNode, id: Int): WidgetNode? {
  if (root.children.any { it.id == id }) return root
  root.children.forEach { c -> parentOf(c, id)?.let { return it } }
  return null
}

private fun isWithin(root: WidgetNode, ancestor: Int, id: Int): Boolean {
  val a = root.findNode(ancestor) ?: return false
  return a.findNode(id) != null
}

/**
 * Where something dropped on [targetId] in [zone] goes: (parent, the sibling it follows — null =
 * first). Null when it cannot go there (the root's siblings, or inside a node that takes none).
 */
internal fun dropPlace(root: WidgetNode, targetId: Int, zone: DropZone, accepts: (WidgetNode) -> Boolean): Pair<Int, Int?>? {
  val target = root.findNode(targetId) ?: return null
  return when (zone) {
    DropZone.Inside -> if (accepts(target)) target.id to target.children.lastOrNull()?.id else null
    DropZone.Before, DropZone.After -> {
      val parent = parentOf(root, targetId) ?: return null // the root has no siblings
      val i = parent.children.indexOfFirst { it.id == targetId }
      parent.id to if (zone == DropZone.After) targetId else parent.children.getOrNull(i - 1)?.id
    }
  }
}

/**
 * The op that moves [dragId] to [place], or null when it would not move (onto itself, into its own
 * subtree, or to where it already is).
 */
internal fun moveOp(root: WidgetNode, dragId: Int, place: Pair<Int, Int?>): DocOp? {
  val (parent, after) = place
  if (dragId == root.id || dragId == parent || isWithin(root, dragId, parent)) return null
  // Following itself means "where it is"; following the sibling before it, too.
  val currentParent = parentOf(root, dragId)
  val i = currentParent?.children?.indexOfFirst { it.id == dragId } ?: -1
  val currentAfter = currentParent?.children?.getOrNull(i - 1)?.id
  if (after == dragId || (currentParent?.id == parent && currentAfter == after)) return null
  return DocOp.MoveNode(Handle(dragId.toLong()), Handle(parent.toLong()), after?.let { Handle(it.toLong()) })
}

/** The op that inserts [node] at [place]. */
internal fun insertOp(place: Pair<Int, Int?>, node: DocNode): DocOp =
  DocOp.InsertNode(Handle(place.first.toLong()), place.second?.let { Handle(it.toLong()) }, node)
