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
 * K3 last review round: given the document the file was written from
 * (`base`), EVERY node pairs with its statement by identity (not only a
 * Condition) — so an insert or delete beside a same-type sibling is surgical
 * — and a moved node is cut with its exact source text (its own leading
 * comments and same-line trailing comment) and placed at its new position.
 * Each case asserts the whole file and that it re-ingests to the target.
 */
class WriteBackIdentityTest {
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

  // ── 2b: identity for every node type ───────────────────────────────────────

  private val siblings = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        StyledText(text = "a")
        Legacy(1)
        StyledText(text = "b")
      }
    }

    interface HomeScreenBindings
  """.trimIndent() + "\n"

  @Test fun anInsertAfterTheFirstOfTwoSameTypeSiblingsIsSurgical() {
    val d = doc(siblings)
    val node = DocNode.Widget(Handle(0), "StyledText", mapOf("text" to lit("new")))
    val target = d.apply(DocOp.InsertNode(d.root.handle, child(d, 0).handle, node)).doc
    val out = merge(siblings, target, d)
    assertEquals(
      splice(siblings, "    StyledText(text = \"a\")\n", "    StyledText(text = \"a\")\n    StyledText(\n      text = \"new\",\n    )\n"),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun deletingTheFirstOfTwoSameTypeSiblingsIsSurgical() {
    val d = doc(siblings)
    val target = d.apply(DocOp.DeleteNode(child(d, 0).handle)).doc
    val out = merge(siblings, target, d)
    assertEquals(splice(siblings, "    StyledText(text = \"a\")\n", ""), out)
    assertReingests(target, out)
  }

  @Test fun editingTheSecondOfTwoSameTypeSiblingsEditsThatOne() {
    val d = doc(siblings)
    val target = d.apply(DocOp.DeleteNode(child(d, 0).handle)).doc
      .let { it.apply(DocOp.SetProp(child(it, 1).handle, "text", lit("B"))).doc }
    val out = merge(siblings, target, d)
    assertEquals(splice(siblings, "    StyledText(text = \"a\")\n", "").replace("\"b\"", "\"B\""), out)
    assertReingests(target, out)
  }

  // ── 2c: surgical moves ─────────────────────────────────────────────────────

  private val moves = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        // first comment
        StyledText(text = "a") // trailing a
        Legacy(1)
        StyledText(text = "b")
        if (b.open) {
          StyledText(text = "o")
        }
        // last comment
        StyledText(text = "c")
      }
    }

    interface HomeScreenBindings {
      val open: Boolean
    }
  """.trimIndent() + "\n"

  private val aLines = "    // first comment\n    StyledText(text = \"a\") // trailing a\n"
  private val cLines = "    // last comment\n    StyledText(text = \"c\")\n"

  @Test fun movingTheFirstChildLastCarriesItsComments() {
    val d = doc(moves)
    val target = d.apply(DocOp.MoveNode(child(d, 0).handle, d.root.handle, child(d, 4).handle)).doc
    val out = merge(moves, target, d)
    assertEquals(splice(splice(moves, aLines, ""), cLines, cLines + aLines), out)
    assertReingests(target, out)
  }

  @Test fun movingTheLastChildFirstCarriesItsComments() {
    val d = doc(moves)
    val target = d.apply(DocOp.MoveNode(child(d, 4).handle, d.root.handle, null)).doc
    val out = merge(moves, target, d)
    assertEquals(splice(splice(moves, cLines, ""), aLines, cLines + aLines), out)
    assertReingests(target, out)
  }

  @Test fun aSwapOfNeighboursIsSurgical() {
    val d = doc(moves)
    // Legacy(1) after b: one node moves, the rest stay.
    val target = d.apply(DocOp.MoveNode(child(d, 1).handle, d.root.handle, child(d, 2).handle)).doc
    val out = merge(moves, target, d)
    assertEquals(
      splice(moves, "    Legacy(1)\n    StyledText(text = \"b\")\n", "    StyledText(text = \"b\")\n    Legacy(1)\n"),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun movingIntoAConditionsThenBlockIsSurgical() {
    val d = doc(moves)
    val cond = child(d, 3) as DocNode.Widget
    val target = d.apply(DocOp.MoveNode(child(d, 2).handle, cond.handle, cond.children[0].handle)).doc
    val out = merge(moves, target, d)
    assertEquals(
      splice(
        splice(moves, "    StyledText(text = \"b\")\n", ""),
        "      StyledText(text = \"o\")\n",
        "      StyledText(text = \"o\")\n      StyledText(text = \"b\")\n",
      ),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun movingOutOfAConditionsThenBlockIsSurgical() {
    val d = doc(moves)
    val cond = child(d, 3) as DocNode.Widget
    val target = d.apply(DocOp.MoveNode(cond.children[0].handle, d.root.handle, child(d, 0).handle)).doc
    val out = merge(moves, target, d)
    assertEquals(
      splice(
        splice(moves, "      StyledText(text = \"o\")\n", ""),
        "    StyledText(text = \"a\") // trailing a\n",
        "    StyledText(text = \"a\") // trailing a\n    StyledText(text = \"o\")\n",
      ),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun aMovedNodeKeepsItsEditsToo() {
    val d = doc(moves)
    val moved = d.apply(DocOp.MoveNode(child(d, 0).handle, d.root.handle, child(d, 4).handle)).doc
    val target = moved.apply(DocOp.SetProp(child(moved, 4).handle, "text", lit("A"))).doc
    val out = merge(moves, target, d)
    assertEquals(splice(splice(moves, aLines, ""), cLines, cLines + aLines.replace("\"a\"", "\"A\"")), out)
    assertReingests(target, out)
  }

  @Test fun aMultiLineNodeMovedDeeperIsReindentedAndKeepsItsInnerComments() {
    val src = """
      package app.screens

      import androidx.compose.runtime.Composable
      import dev.keliver.layout.compose.Column
      import dev.keliver.layout.compose.Row
      import dev.keliver.material.compose.StyledText

      @Composable
      fun HomeScreen(b: HomeScreenBindings) {
        Column {
          // the open branch
          if (b.open) {
            // inside
            StyledText(text = "o")
          }
          Row {
            StyledText(text = "r")
          }
        }
      }

      interface HomeScreenBindings {
        val open: Boolean
      }
    """.trimIndent() + "\n"
    val d = doc(src)
    val row = child(d, 1) as DocNode.Widget
    val target = d.apply(DocOp.MoveNode(child(d, 0).handle, row.handle, row.children[0].handle)).doc
    val out = merge(src, target, d)
    assertEquals(
      splice(
        splice(src, "    // the open branch\n    if (b.open) {\n      // inside\n      StyledText(text = \"o\")\n    }\n", ""),
        "      StyledText(text = \"r\")\n",
        "      StyledText(text = \"r\")\n      // the open branch\n      if (b.open) {\n        // inside\n        StyledText(text = \"o\")\n      }\n",
      ),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun withoutABaseAReorderIsNotSurgical() {
    val d = doc(moves)
    val target = d.apply(DocOp.MoveNode(child(d, 0).handle, d.root.handle, child(d, 4).handle)).doc
    val o = WriteBack.attempt(moves, target, fileName = file, base = null)
    assertTrue(o is WriteBack.Outcome.NotSurgical && "reordered" in o.reason, o.toString())
  }
}
