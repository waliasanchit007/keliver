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
}
