package dev.keliver.portal.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** K1 review (B1/S1): the document never holds a scoped modifier outside its scope or a negative Dp. */
class LayoutInvariantsTest {
  private fun d(v: Double) = PropValue.Lit("d", d = v)

  private val doc = UiDocument(
    "s",
    DocNode.Widget(
      Handle(1), "Column",
      children = listOf(
        DocNode.Widget(Handle(2), "Row", children = listOf(
          DocNode.Widget(Handle(3), "Condition", mapOf("field" to PropValue.Lit("s", s = "show")), children = listOf(
            DocNode.Widget(Handle(4), "StyledText", mapOf("text" to PropValue.Lit("s", s = "a")),
              modifiers = mapOf("Flex.value" to d(1.0), "Padding.allDp" to PropValue.Lit("i", i = 2))),
          )),
        )),
        DocNode.Widget(Handle(5), "StyledBox"),
      ),
    ),
    Contract(), 0, 6,
  )

  private fun mods(d: UiDocument, h: Long) = (d.find(Handle(h)) as DocNode.Widget).modifiers

  @Test fun setModifierRefusesAScopedModifierOutsideItsScope() {
    assertNull(doc.tryApply(DocOp.SetModifier(Handle(5), "Flex.value", d(1.0))).error) // StyledBox sits in a Column
    assertNotNull(doc.tryApply(DocOp.SetModifier(Handle(1), "Flex.value", d(1.0))).error) // the root: no scope
    // Through a Condition the scope is the Row's: vertical (Row) yes, horizontal (Column/Box) no.
    assertNull(doc.tryApply(DocOp.SetModifier(Handle(4), "VerticalAlignment.alignment", PropValue.Lit("i", i = 1))).error)
    assertNotNull(doc.tryApply(DocOp.SetModifier(Handle(4), "HorizontalAlignment.alignment", PropValue.Lit("i", i = 1))).error)
    // Unscoped modifiers go anywhere.
    assertNull(doc.tryApply(DocOp.SetModifier(Handle(1), "Padding.allDp", PropValue.Lit("i", i = 1))).error)
  }

  @Test fun moveStripsInvalidScopedModifiersAndUndoRestoresThem() {
    // Move the Condition (and its child's flex) under the StyledBox.
    val moved = doc.apply(DocOp.MoveNode(Handle(3), Handle(5), null))
    assertEquals(mapOf("Padding.allDp" to PropValue.Lit("i", i = 2)), mods(moved.doc, 4))
    val undone = moved.doc.apply(moved.inverse!!)
    assertEquals(doc.root, undone.doc.root)
    // Redo (the inverse of the undo) strips again.
    assertEquals(mapOf("Padding.allDp" to PropValue.Lit("i", i = 2)), mods(undone.doc.apply(undone.inverse!!).doc, 4))
  }

  @Test fun aValidMoveKeepsTheModifiersAndAPlainInverse() {
    val inColumn = doc.apply(DocOp.MoveNode(Handle(4), Handle(1), Handle(2)))
    assertEquals(d(1.0), mods(inColumn.doc, 4)["Flex.value"]) // Column scope has flex
    assertTrue(inColumn.inverse is DocOp.MoveNode)
  }

  @Test fun insertedSubtreesAreSanitizedToo() {
    val sub = DocNode.Widget(Handle(0), "StyledText", mapOf("text" to PropValue.Lit("s", s = "n")),
      modifiers = mapOf("Flex.value" to d(1.0), "Width.width" to d(-3.0)))
    val inBox = doc.apply(DocOp.InsertNode(Handle(5), null, sub)).doc
    val inserted = (inBox.find(Handle(5)) as DocNode.Widget).children.single() as DocNode.Widget
    assertEquals(emptyMap(), inserted.modifiers) // Width/Flex need a layout parent
    val inRow = doc.apply(DocOp.InsertNode(Handle(2), null, sub)).doc
    val ok = (inRow.find(Handle(2)) as DocNode.Widget).children.first() as DocNode.Widget
    assertEquals(mapOf("Flex.value" to d(1.0), "Width.width" to d(0.0)), ok.modifiers) // clamped, not dropped
  }

  @Test fun dpValuesClampAtZero() {
    val t = doc
      .apply(DocOp.SetProp(Handle(2), "margin.start", d(-4.0))).doc
      .apply(DocOp.SetModifier(Handle(4), "Margin.margin.top", d(-1.0))).doc
      .apply(DocOp.SetProp(Handle(4), "fontSize", PropValue.Lit("i", i = -2))).doc // not a Dp: untouched
    assertEquals(d(0.0), (t.find(Handle(2)) as DocNode.Widget).props["margin.start"])
    assertEquals(d(0.0), mods(t, 4)["Margin.margin.top"])
    assertEquals(PropValue.Lit("i", i = -2), (t.find(Handle(4)) as DocNode.Widget).props["fontSize"])
  }

  private fun handles(n: DocNode): List<Long> =
    listOf(n.handle.v) + ((n as? DocNode.Widget)?.children?.flatMap(::handles) ?: emptyList())

  /** N-1: undo of a stripping move never re-inserts a snapshot over another session's edits. */
  @Test fun undoOfAStrippingMoveIsSafeAgainstAConcurrentSession() {
    val withSibling = doc.apply(DocOp.InsertNode(Handle(3), Handle(4),
      DocNode.Widget(Handle(0), "StyledText", mapOf("text" to PropValue.Lit("s", s = "y"))))).doc // handle 6
    // Session A: move the Condition (X=4 has flex) Row -> StyledBox; X is stripped.
    val a = withSibling.apply(DocOp.MoveNode(Handle(3), Handle(5), null))
    // Session B: moves X out of the Condition to the Column root, and edits the sibling inside it.
    val b = a.doc
      .apply(DocOp.MoveNode(Handle(4), Handle(1), null)).doc
      .apply(DocOp.SetProp(Handle(6), "text", PropValue.Lit("s", s = "edited by B"))).doc
    // Session A undoes.
    val undone = b.apply(a.inverse!!).doc
    val all = handles(undone.root)
    assertEquals(all.toSet().size, all.size, "no duplicated handles: $all")
    val cond = undone.find(Handle(3)) as DocNode.Widget
    assertEquals(listOf(6L), cond.children.map { it.handle.v }) // X stays where B put it
    assertEquals(PropValue.Lit("s", s = "edited by B"), (undone.find(Handle(6)) as DocNode.Widget).props["text"])
    assertEquals(Handle(2), (undone.root as DocNode.Widget).children.first { it.handle == Handle(2) }.handle)
    assertTrue((undone.find(Handle(2)) as DocNode.Widget).children.any { it.handle == Handle(3) }) // C back in the Row
    // X now sits in the Column, where flex is valid: its stripped entry is re-applied.
    assertEquals(d(1.0), mods(undone, 4)["Flex.value"])
  }

  @Test fun undoSkipsStrippedEntriesThatNoLongerQualify() {
    val a = doc.apply(DocOp.MoveNode(Handle(3), Handle(5), null)) // X (4) stripped of flex
    // B moves X under the StyledBox directly (still no flex scope there) ...
    val b = a.doc.apply(DocOp.MoveNode(Handle(4), Handle(5), null)).doc
    val undone = b.apply(a.inverse!!).doc
    assertEquals(null, mods(undone, 4)["Flex.value"]) // ... so undo does not re-apply it
    // ... and a handle deleted since is skipped, not an error.
    val c = a.doc.apply(DocOp.DeleteNode(Handle(4))).doc
    assertNull(c.apply(a.inverse!!).doc.find(Handle(4)))
  }

  @Test fun engineOnlyOpsAreNotClientOps() {
    val moved = doc.apply(DocOp.MoveNode(Handle(3), Handle(5), null))
    assertTrue(!moved.inverse!!.isClientOp()) // MoveBack
    assertTrue(!doc.apply(DocOp.DeleteNode(Handle(5))).inverse!!.isClientOp()) // InsertExisting
    assertTrue(DocOp.MoveNode(Handle(3), Handle(5), null).isClientOp())
  }

  /** N8: the codegen copy of the Margin sides must match portal-core's (see EmitCatalogTest). */
  @Test fun marginSidesAreTheWireOrder() {
    assertEquals(listOf("start", "end", "top", "bottom"), dev.keliver.portal.MARGIN_SIDES)
  }
}
