package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.UiDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * K3 fourth review: moves and inserts at the edges of a block — the only
 * statement of a lambda moved out (and back), a statement placed first in a
 * block whose first statement has comments above it.
 * Each case asserts the whole file and that it re-ingests to the target.
 */
class WriteBackMoveEdgesTest {
  private val file = "app/screens/HomeScreen.kt"

  private fun doc(text: String): UiDocument =
    Recognizer.recognize(file, text)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }

  private fun attempt(text: String, target: UiDocument, base: UiDocument) =
    WriteBack.attempt(text, target, fileName = file, base = base)

  private fun merge(text: String, target: UiDocument, base: UiDocument): String =
    when (val o = attempt(text, target, base)) {
      is WriteBack.Outcome.Merged -> o.text
      is WriteBack.Outcome.NotSurgical -> throw AssertionError("the change is written surgically, not: ${o.reason}")
    }

  private fun column(d: UiDocument) = d.root as DocNode.Widget
  private fun child(d: UiDocument, i: Int) = column(d).children[i]
  private fun widget(d: UiDocument, i: Int) = child(d, i) as DocNode.Widget

  private fun strip(n: DocNode): DocNode = when (n) {
    is DocNode.RawCode -> n.copy(handle = Handle(0), kindHint = null)
    is DocNode.Widget -> n.copy(handle = Handle(0), children = n.children.map(::strip))
  }

  private fun assertReingests(target: UiDocument, out: String) {
    assertEquals(strip(target.root), strip(doc(out).root), "the result re-ingests to the target document")
    val again = doc(out)
    assertEquals(out, WriteBack.merge(out, again, fileName = file, base = again), "a no-op merge of the result is byte-identical")
  }

  // ── S1: the only statement of a lambda block ───────────────────────────────

  private val onlyChildren = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Row
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        Row {
          StyledText(text = "only")
        }
        b.rows.forEach { row ->
          StyledText(text = "each")
        }
        StyledText(text = "z")
      }
    }

    interface HomeScreenBindings {
      val rows: List<String>
    }
  """.trimIndent() + "\n"

  @Test fun theOnlyChildOfARowMovesToTheTopLevelAndBack() {
    val d = doc(onlyChildren)
    val row = widget(d, 0)
    val out = merge(onlyChildren, d.apply(DocOp.MoveNode(row.children[0].handle, d.root.handle, child(d, 2).handle)).doc, d)
    assertEquals(
      onlyChildren
        .replace("    Row {\n      StyledText(text = \"only\")\n    }\n", "    Row {\n    }\n")
        .replace("    StyledText(text = \"z\")\n", "    StyledText(text = \"z\")\n    StyledText(text = \"only\")\n"),
      out,
    )
    assertReingests(d.apply(DocOp.MoveNode(row.children[0].handle, d.root.handle, child(d, 2).handle)).doc, out)
    // And back in.
    val d2 = doc(out)
    val back = d2.apply(DocOp.MoveNode(child(d2, 3).handle, widget(d2, 0).handle, null)).doc
    val out2 = merge(out, back, d2)
    assertEquals(onlyChildren, out2)
    assertReingests(back, out2)
  }

  @Test fun theOnlyChildOfAForEachMovesToTheTopLevelAndBack() {
    val d = doc(onlyChildren)
    val each = widget(d, 1)
    val target = d.apply(DocOp.MoveNode(each.children[0].handle, d.root.handle, null)).doc
    val out = merge(onlyChildren, target, d)
    assertEquals(
      onlyChildren
        .replace("    b.rows.forEach { row ->\n      StyledText(text = \"each\")\n    }\n", "    b.rows.forEach { row ->\n    }\n")
        .replace("  Column {\n", "  Column {\n    StyledText(text = \"each\")\n"),
      out,
    )
    assertReingests(target, out)
    val d2 = doc(out)
    val back = d2.apply(DocOp.MoveNode(child(d2, 0).handle, widget(d2, 2).handle, null)).doc
    val out2 = merge(out, back, d2)
    assertEquals(onlyChildren, out2)
    assertReingests(back, out2)
  }
}
