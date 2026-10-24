package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K3 last review round: what an insert or an edit writes is spelled as the
 * FILE spells it — the screen's bindings parameter (not the exporter's `b`),
 * a RawCode as its own text — and a modifier reorder is written as the
 * reordered chain (read-back compares modifier order). Each case asserts the
 * whole file and that it re-ingests to the target.
 */
class WriteBackSpellingTest {
  private val file = "app/screens/HomeScreen.kt"

  private fun doc(text: String): UiDocument =
    Recognizer.recognize(file, text)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }

  /** Surgical merge against [base] (the document the file was written from). */
  private fun merge(text: String, target: UiDocument, base: UiDocument?): String =
    when (val o = WriteBack.attempt(text, target, fileName = file, base = base)) {
      is WriteBack.Outcome.Merged -> o.text
      is WriteBack.Outcome.NotSurgical -> throw AssertionError("the change is written surgically, not: ${o.reason}")
    }

  private fun column(d: UiDocument) = d.root as DocNode.Widget
  private fun child(d: UiDocument, i: Int) = column(d).children[i]

  private fun strip(n: DocNode): DocNode = when (n) {
    is DocNode.RawCode -> n.copy(handle = Handle(0), kindHint = null)
    is DocNode.Widget -> n.copy(handle = Handle(0), children = n.children.map(::strip))
  }

  private fun assertReingests(target: UiDocument, out: String) {
    assertEquals(strip(target.root), strip(doc(out).root), "the result re-ingests to the target document")
    val again = doc(out)
    assertEquals(out, WriteBack.merge(out, again, fileName = file, base = again), "a no-op merge of the result is byte-identical")
  }

  private fun splice(text: String, anchor: String, replacement: String): String {
    assertEquals(1, text.split(anchor).size - 1, "anchor occurs once: $anchor")
    return text.replace(anchor, replacement)
  }

  // ── 2a: the file's bindings parameter ──────────────────────────────────────

  private val reviewer = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(bindings: HomeScreenBindings) {
      Column {
        // keep me
        Legacy(bindings.title)
      }
    }

    private fun helper() = Unit

    interface HomeScreenBindings {
      val title: String
    }
  """.trimIndent() + "\n"

  @Test fun anInsertSpellsTheFilesBindingsParameter() {
    for (withBase in listOf(true, false)) {
      val d = doc(reviewer)
      val raw = child(d, 0)
      assertTrue(raw is DocNode.RawCode, raw.toString())
      val node = DocNode.Widget(Handle(0), "StyledText", mapOf("text" to PropValue.Bind("title")))
      val target = d.apply(DocOp.InsertNode(d.root.handle, raw.handle, node)).doc
      val out = merge(reviewer, target, if (withBase) d else null)
      assertEquals(
        splice(reviewer, "    Legacy(bindings.title)\n", "    Legacy(bindings.title)\n    StyledText(\n      text = bindings.title,\n    )\n"),
        out,
      )
      assertReingests(target, out)
    }
  }

  @Test fun anInsertedActionAndConditionSpellTheFilesParameter() {
    val d = doc(reviewer)
    val raw = child(d, 0)
    val button = DocNode.Widget(Handle(0), "TextButton", mapOf("text" to lit("Go"), "onClick" to PropValue.Action("go")))
    val cond = DocNode.Widget(Handle(0), "Condition", mapOf("field" to lit("open")), children = listOf(button))
    val target = d.apply(DocOp.InsertNode(d.root.handle, raw.handle, cond)).doc
    val out = merge(reviewer, target, d)
    assertTrue("if (bindings.open) {" in out, out)
    assertTrue("bindings.go()" in out, out)
    assertFalse(Regex("\\bb\\.").containsMatchIn(out), out)
    assertReingests(target, out)
  }

  @Test fun aReinsertedRawCodeIsWrittenAsItsOwnText() {
    // Undo of a delete that was already written: the RawCode comes back as an insert.
    val d = doc(reviewer)
    val raw = child(d, 0)
    val (deleted, inverse) = d.applyBatch(listOf(DocOp.DeleteNode(raw.handle))).result!!.let { it.doc to it.inverseBatch }
    val afterDelete = merge(reviewer, deleted, d)
    assertEquals(reviewer.replace("    Legacy(bindings.title)\n", ""), afterDelete)
    val undone = deleted.applyBatch(inverse).result!!.doc
    assertEquals(reviewer, merge(afterDelete, undone, deleted))
  }

  @Test fun anInsertedContainerWritesItsRawCodeChildAsItsOwnText() {
    val d = doc(reviewer)
    val raw = child(d, 0)
    val row = DocNode.Widget(Handle(0), "Row", children = listOf(DocNode.RawCode(Handle(0), "Legacy(bindings.title, 2)")))
    val target = d.apply(DocOp.InsertNode(d.root.handle, raw.handle, row)).doc
    val out = merge(reviewer, target, d)
    // F20: no arguments, so `Row {`; the RawCode child is its own text.
    assertTrue("    Row {\n      Legacy(bindings.title, 2)\n    }\n" in out, out)
    assertFalse("RawCode(" in out, out)
    assertReingests(target, out)
  }

  @Test fun aBoundInsertIntoAScreenWithoutABindingsParameterSaysWhy() {
    val src = reviewer.replace("fun HomeScreen(bindings: HomeScreenBindings)", "fun HomeScreen()").replace("Legacy(bindings.title)", "Legacy(1)")
    val d = doc(src)
    val node = DocNode.Widget(Handle(0), "Condition", mapOf("field" to lit("open")))
    val o = WriteBack.attempt(src, d.apply(DocOp.InsertNode(d.root.handle, null, node)).doc, fileName = file, base = d)
    assertTrue(o is WriteBack.Outcome.NotSurgical && "takes no bindings parameter" in o.reason, o.toString())
    // A literal insert needs no parameter and is written in place.
    val text = DocNode.Widget(Handle(0), "StyledText", mapOf("text" to lit("x")))
    assertTrue(WriteBack.attempt(src, d.apply(DocOp.InsertNode(d.root.handle, null, text)).doc, fileName = file, base = d) is WriteBack.Outcome.Merged)
  }

  // ── 2d: modifier order ─────────────────────────────────────────────────────

  @Test fun sameTreeComparesModifierOrder() {
    val a = DocNode.Widget(
      Handle(1), "StyledText",
      modifiers = linkedMapOf("Background.colorArgb" to lit(1), "Padding.allDp" to lit(8)),
    )
    val b = a.copy(modifiers = linkedMapOf("Padding.allDp" to lit(8), "Background.colorArgb" to lit(1)))
    assertFalse(WriteBack.sameTree(a, b))
    assertTrue(WriteBack.sameTree(a, a.copy(modifiers = LinkedHashMap(a.modifiers))))
  }

  @Test fun aModifierReorderIsWrittenAsTheReorderedChain() {
    val src = """
      package app.screens

      import androidx.compose.runtime.Composable
      import dev.keliver.Modifier
      import dev.keliver.layout.compose.Column
      import dev.keliver.material.compose.StyledText
      import dev.keliver.material.compose.background
      import dev.keliver.material.compose.padding

      @Composable
      fun HomeScreen(b: HomeScreenBindings) {
        Column {
          // keep me
          StyledText(text = "x", modifier = Modifier.background(-16777216).padding(8))
        }
      }

      interface HomeScreenBindings
    """.trimIndent() + "\n"
    val d = doc(src)
    val t = child(d, 0) as DocNode.Widget
    assertEquals(listOf("Background.colorArgb", "Padding.allDp"), t.modifiers.keys.toList())
    val target = d.apply(DocOp.RemoveModifier(t.handle, "Background.colorArgb")).doc
      .apply(DocOp.SetModifier(t.handle, "Background.colorArgb", lit(-16777216))).doc
    val out = merge(src, target, d)
    assertEquals(src.replace("Modifier.background(-16777216).padding(8)", "Modifier.padding(8).background(-16777216)"), out)
    assertReingests(target, out)
  }

  // ── 6: condKey ─────────────────────────────────────────────────────────────

  @Test fun unknownConditionTestsCompareByTheirWireName() {
    val c = DocNode.Widget(Handle(1), "Condition", mapOf("field" to lit("open"), "test" to lit("someday")))
    assertFalse(WriteBack.sameTree(c, c.copy(props = mapOf("field" to lit("open"), "test" to lit("never")))))
    assertTrue(WriteBack.sameTree(c, c.copy(props = LinkedHashMap(c.props))))
  }
}
