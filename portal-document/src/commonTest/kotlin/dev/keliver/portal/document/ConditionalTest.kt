package dev.keliver.portal.document

import dev.keliver.portal.Action
import dev.keliver.portal.Cond
import dev.keliver.portal.CondTest
import dev.keliver.portal.Conditional
import dev.keliver.portal.Token
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.serializeTree
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** K3: conditional values and else branches through the document, both wires and the exporter. */
class ConditionalTest {
  private val ink = PropValue.Token("C.Ink", lit(-14737633), "app.components.C")
  private val color = PropValue.Conditional("selected", whenTrue = ink, whenFalse = lit(-1))
  private val onClick = PropValue.Conditional("enabled", whenTrue = PropValue.Action("tap"), whenFalse = null)

  private fun doc(vararg children: DocNode): UiDocument =
    UiDocument("t", DocNode.Widget(Handle(1), "Column", children = children.toList()), Contract(), 0, 10)

  private fun text(h: Long) = DocNode.Widget(Handle(h), "StyledText", props = mapOf("text" to lit("x")))

  @Test fun aConditionalRoundTripsOnTheDocumentWire() {
    val node: DocNode = DocNode.Widget(
      Handle(2), "Button",
      props = mapOf("text" to PropValue.Conditional("note", test = "blank", negate = true, whenTrue = lit("A"), whenFalse = PropValue.Bind("note")), "onClick" to onClick),
    )
    val json = DocJson.encodeToString(node)
    assertEquals(node, DocJson.decodeFromString<DocNode>(json))
    // Defaults are not written: a Boolean, un-negated condition is just its field and branches.
    assertTrue("\"field\":\"enabled\",\"whenTrue\":" in json && "\"whenFalse\"" !in json.substringAfter("\"enabled\""), json)
    val batch = OpBatch(1, OpEnvelope("s", 0), listOf(DocOp.SetProp(Handle(2), "colorArgb", color)))
    assertEquals(batch, DocJson.decodeFromString<OpBatch>(DocJson.encodeToString(batch)))
  }

  @Test fun theTreeAndTheDeviceWireCarryTheTrueBranchForOlderReaders() {
    val d = doc(DocNode.Widget(Handle(2), "Button", props = mapOf("text" to lit("Go"), "containerArgb" to color, "onClick" to onClick)))
    val tree = d.toWidgetTree()
    val button = tree.children.single()
    assertEquals(Conditional(Cond("selected"), Token("C.Ink", -14737633, "app.components.C"), -1), button.props["containerArgb"])
    assertEquals(Conditional(Cond("enabled"), Action("tap"), null), button.props["onClick"])
    val json = serializeTree(tree)
    // The true branch rides under its usual keys (an older reader renders it), the rest beside it.
    assertTrue("{\"i\":-14737633,\"token\":\"C.Ink\",\"tokenImport\":\"app.components.C\",\"cond\":{\"field\":\"selected\"},\"else\":{\"i\":-1}}" in json, json)
    assertTrue("{\"action\":\"tap\",\"cond\":{\"field\":\"enabled\"}}" in json, json)
    assertEquals(tree, deserializeTree(json))
    // Editor -> server (duplicate) lifts it back unchanged.
    assertEquals(color, button.toDocNode().props["containerArgb"])
    assertEquals(onClick, button.toDocNode().props["onClick"])
  }

  @Test fun aBindBranchResolvesThroughTheMocksLikeAnyBind() {
    val d = doc(DocNode.Widget(Handle(2), "StyledText", props = mapOf("text" to PropValue.Conditional("open", whenTrue = PropValue.Bind("title"), whenFalse = lit("-")))))
    assertEquals(Conditional(Cond("open"), "Hi", "-"), d.toWidgetTree(mocks = mapOf("title" to "Hi")).children.single().props["text"])
  }

  @Test fun theExporterWritesEachBranchAndTheElseBlock() {
    val cond = DocNode.Widget(
      Handle(3), "Condition", mapOf("field" to lit("note"), "test" to lit("empty"), "negate" to lit(true)),
      children = listOf(text(4), DocNode.Widget(Handle(5), "Else", children = listOf(text(6)))),
    )
    val d = doc(DocNode.Widget(Handle(2), "Button", props = mapOf("text" to lit("Go"), "containerArgb" to color, "onClick" to onClick)), cond)
    val src = exportKotlin(d.toWidgetTree(), functionName = "Home")
    assertTrue("containerArgb = if (b.selected) C.Ink else -1," in src, src)
    assertTrue("onClick = if (b.enabled) b::tap else null," in src, src)
    assertTrue("import app.components.C\n" in src, src)
    assertTrue("  if (b.note.isNotEmpty()) {\n" in src && "  } else {\n" in src, src)
    assertTrue("  val selected: Boolean\n" in src && "  val note: String\n" in src && "  fun tap()\n" in src, src)
  }

  @Test fun aConditionalThatCouldNotCompileIsRefused() {
    val d = doc(text(2), DocNode.Widget(Handle(3), "Button", props = mapOf("text" to lit("Go"))), DocNode.Widget(Handle(4), "Row"))
    fun refused(op: DocOp) = assertNotNull(d.tryApply(op).error, op.toString())
    // A null or action branch on a value prop; a value branch on an event.
    refused(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("x", whenTrue = lit("a"), whenFalse = null)))
    refused(DocOp.SetProp(Handle(3), "onClick", PropValue.Conditional("x", whenTrue = lit("a"), whenFalse = null)))
    // Nested, a null test (a screen contract cannot type it), an unknown test, a bad field.
    refused(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("x", whenTrue = lit("a"), whenFalse = PropValue.Conditional("y", whenTrue = lit("b"), whenFalse = lit("c")))))
    refused(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("x", test = "null", whenTrue = lit("a"), whenFalse = lit("b"))))
    refused(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("x", test = "long", whenTrue = lit("a"), whenFalse = lit("b"))))
    refused(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("b.x", whenTrue = lit("a"), whenFalse = lit("b")).copy(field = "1x")))
    // A token branch that does not fit (an Int token on a Text prop).
    refused(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("x", whenTrue = ink, whenFalse = lit("b"))))
    // A modifier argument takes values in both branches.
    refused(DocOp.SetModifier(Handle(2), "Padding.allDp", PropValue.Conditional("x", whenTrue = lit(4), whenFalse = null)))
    // Accepted where every branch fits; a negative Dp branch clamps at 0.
    assertNull(d.tryApply(DocOp.SetProp(Handle(3), "onClick", onClick)).error)
    assertNull(d.tryApply(DocOp.SetProp(Handle(3), "containerArgb", color)).error)
    val margin = d.apply(DocOp.SetProp(Handle(4), "margin.top", PropValue.Conditional("x", whenTrue = lit(-3.0), whenFalse = lit(2.0)))).doc
    assertEquals(PropValue.Conditional("x", whenTrue = lit(0.0), whenFalse = lit(2.0)), (margin.find(Handle(4)) as DocNode.Widget).props["margin.top"])
    // A Condition's own test/negate take only what source can spell.
    val c = doc(DocNode.Widget(Handle(2), "Condition", mapOf("field" to lit("x"))))
    assertNotNull(c.tryApply(DocOp.SetProp(Handle(2), "test", lit("null"))).error)
    assertNull(c.tryApply(DocOp.SetProp(Handle(2), "test", lit("blank"))).error)
    assertNotNull(c.tryApply(DocOp.SetProp(Handle(2), "negate", lit("yes"))).error)
  }

  @Test fun anElseSitsOnlyDirectlyUnderAConditionOncePerCondition() {
    val d = doc(
      DocNode.Widget(Handle(2), "Condition", mapOf("field" to lit("x")), children = listOf(text(3), DocNode.Widget(Handle(4), "Else"))),
      DocNode.Widget(Handle(5), "Condition", mapOf("field" to lit("y")), children = listOf(text(6))),
    )
    assertNotNull(d.tryApply(DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "Else"))).error)
    assertNotNull(d.tryApply(DocOp.InsertNode(Handle(2), null, DocNode.Widget(Handle(0), "Else"))).error)
    assertNotNull(d.tryApply(DocOp.MoveNode(Handle(4), Handle(1), null)).error)
    // Moving the Else to the Condition that has none, or within its own, is fine; undo restores it.
    val moved = d.apply(DocOp.MoveNode(Handle(4), Handle(5), Handle(6)))
    assertEquals(listOf(6L, 4L), (moved.doc.find(Handle(5)) as DocNode.Widget).children.map { it.handle.v })
    assertEquals(d.root, moved.doc.apply(assertNotNull(moved.inverse)).doc.root)
    assertNull(d.tryApply(DocOp.InsertNode(Handle(5), Handle(6), DocNode.Widget(Handle(0), "Else"))).error)
  }

  @Test fun anEditorThatPredatesConditionalsIsServedTheTrueBranch() {
    val d = doc(DocNode.Widget(Handle(2), "Button", props = mapOf("text" to lit("Go"), "containerArgb" to color, "onClick" to onClick.copy(whenTrue = null, whenFalse = PropValue.Action("tap")))))
    val old = d.withoutConditionals()
    val button = old.find(Handle(2)) as DocNode.Widget
    assertEquals(ink, button.props["containerArgb"])
    assertEquals(setOf("text", "containerArgb"), button.props.keys) // a null true branch: the prop is left out
    assertTrue("Conditional" !in DocJson.encodeToString(old))
    // CondTest wire names are what the document carries.
    assertEquals(listOf(null, "empty", "blank", "null"), CondTest.entries.map { it.wire })
  }
}
