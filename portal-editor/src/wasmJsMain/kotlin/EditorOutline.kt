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
  // G10: a text bound to a field shows the field.
  else -> (node.props["text"] as? dev.keliver.portal.Bind)?.let { "@${it.field}" }
    ?: (tokenValue(node.props["text"]) as? String)?.let { "“$it”" }
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
  padding: Double = 32.0,
): Pair<Int, DropZone>? {
  val node = root.findNode(hit) ?: return null
  val kids = node.children.mapNotNull { c -> box(c)?.let { c to it } }
  if (accepts(node) && kids.isNotEmpty()) {
    kids.firstOrNull { it.second.contains(x, y) }?.let { (kid, b) ->
      // Round 5 (L3): the gap between a list's rows is no place in its row template (that would put
      // the widget in every row) — the drop goes before or after the list. A row's own insides,
      // hit directly, still take it.
      if (kid.type == "Repeat") return kid.id to if (y < (b.top + b.bottom) / 2) DropZone.Before else DropZone.After
      return canvasDropPlace(root, kid.id, x, y, box, accepts, padding)
    }
    // J1: a box that only pads one container (a card around its column) passes a drop in that
    // padding to the column, at the point nearest the pointer — after the column, a box draws it
    // over the card. Review 5: only a tight wrapper does (a tall column holding one card takes a
    // drop below the card after it), and never into a list's row or a condition's branch.
    // Round 5 (L1): a box that STACKS its children always does — after its one child, a new widget
    // is drawn on top of it (a screen's root box: drawn over the top of the screen).
    val wrapped = kids.singleOrNull()?.takeIf { (kid, b) ->
      accepts(kid) && kid.type !in PASS_THROUGH_EXCLUDED &&
        (node.type in STACKING_TYPES || box(node)?.let { it.contains(x, y) && it.pads(b, padding) } == true)
    }
    wrapped?.let { (kid, b) ->
      // Review 5: an empty column takes the drop inside it, not beside it (over the card again).
      if (kid.children.none { box(it) != null }) return kid.id to DropZone.Inside
      return canvasDropPlace(root, kid.id, x.coerceIn(b.left, b.right), y.coerceIn(b.top, b.bottom), box, accepts, padding)
    }
    val sideBySide = sideBySide(kids.map { it.second })
    fun gap(lo: Double, hi: Double, p: Double) = if (p < lo) lo - p else if (p > hi) p - hi else 0.0
    val (kid, b) = kids.minBy { (_, b) -> if (sideBySide) gap(b.left, b.right, x) else gap(b.top, b.bottom, y) }
    val before = if (sideBySide) x < (b.left + b.right) / 2 else y < (b.top + b.bottom) / 2
    return kid.id to if (before) DropZone.Before else DropZone.After
  }
  if (hit == root.id) return hit to DropZone.Inside
  val b = box(node) ?: return hit to DropZone.Inside
  // In a row, before and after are left and right.
  return hit to if (laidOutAcross(root, hit, box)) dropZone(x - b.left, b.right - b.left, accepts(node))
    else dropZone(y - b.top, b.bottom - b.top, accepts(node))
}

/** Review 4 #8: the outline rows on either side of a deleted node (and its subtree), in reading order. */
internal data class DeletedPlace(val next: Int?, val previous: Int?)

/**
 * Review 4 #8: where the outline's place is after deleting [id] from [root] (rows under [collapsed]
 * hidden) — so ↓ goes on to the row after it and ↑ to the row before it, not from the top.
 */
internal fun placeAfterDelete(root: WidgetNode, collapsed: Set<Int>, id: Int): DeletedPlace? {
  if (id == root.id) return null
  val order = visibleOrder(root, collapsed)
  val i = order.indexOf(id).takeIf { it >= 0 } ?: return null
  val gone = HashSet<Int>().also { ids -> fun add(n: WidgetNode) { ids += n.id; n.children.forEach(::add) }; root.findNode(id)?.let(::add) }
  return DeletedPlace(next = order.drop(i).firstOrNull { it !in gone }, previous = order.take(i).lastOrNull())
}

/** Containers that draw their children on top of each other (not one after another). */
private val STACKING_TYPES = setOf("Box", "StyledBox", "Surface")

/** Children a drop in their parent's padding never passes into: a list's row template, a condition's branch. */
private val PASS_THROUGH_EXCLUDED = setOf("Repeat", "Condition", ELSE_TYPE)

/** Whether this box surrounds [inner] by at most [padding] on every side (a card around its content). */
private fun DropBox.pads(inner: DropBox, padding: Double): Boolean =
  inner.left - left <= padding && right - inner.right <= padding && inner.top - top <= padding && bottom - inner.bottom <= padding

/** Whether [boxes] (siblings, in order) are laid out side by side, left to right. */
internal fun sideBySide(boxes: List<DropBox>): Boolean =
  boxes.size > 1 && boxes.zipWithNext().all { (a, b) -> b.left >= a.right - 1 }

/** Whether node [id] and its laid-out siblings are side by side (a row): its drop line is then vertical. */
internal fun laidOutAcross(root: WidgetNode, id: Int, box: (WidgetNode) -> DropBox?): Boolean =
  parentOf(root, id)?.children?.mapNotNull(box)?.let(::sideBySide) == true

/**
 * F11: the instances drawing one node — a list's rows, a component used in each of them — from
 * the rects of every widget drawing it. Rects that overlap (a card's background holds its
 * texts) are one instance; rects that only touch or stand apart are separate ones. In reading
 * order, each as the box around its widgets.
 */
internal fun instanceBoxes(rects: List<DropBox>): List<DropBox> {
  fun overlaps(a: DropBox, b: DropBox) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
  val groups = rects.filter { it.right > it.left && it.bottom > it.top }.toMutableList()
  var merged = true
  while (merged) {
    merged = false
    loop@ for (i in groups.indices) for (j in i + 1 until groups.size) {
      if (overlaps(groups[i], groups[j])) {
        groups[i] = groups[i] union groups[j]
        groups.removeAt(j)
        merged = true
        break@loop
      }
    }
  }
  return groups.sortedWith(compareBy({ it.top }, { it.left }))
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
