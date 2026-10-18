package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K3 review B-1: several statements inserted in a row land in the target's
 * order — each is anchored on the statement before it in the TARGET (a kept
 * one, or the one just inserted), never all on the last kept sibling (which
 * wrote a run of inserts reversed). Each case asserts the whole file and
 * that it re-ingests to the target document.
 */
class WriteBackInsertOrderTest {
  private val src = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Row
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        StyledText(text = "top")
        if (b.alpha) {
          // alpha comment
          StyledText(text = "A")
        }
        if (b.beta) {
          StyledText(text = "B")
        } else {
          StyledText(text = "E")
        }
        Row {
          StyledText(text = "r")
        }
        b.items.forEach { item ->
          if (b.gamma) {
            StyledText(text = item.name)
          }
          StyledText(text = "tail")
        }
      }
    }

    interface HomeScreenBindings {
      val alpha: Boolean
      val beta: Boolean
      val gamma: Boolean
      val items: List<Item>
    }
  """.trimIndent() + "\n"

  private fun doc(text: String = src): UiDocument =
    Recognizer.recognize("app/screens/HomeScreen.kt", text)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }

  private fun merge(target: UiDocument, text: String = src): String =
    assertNotNull(WriteBack.merge(text, target, fileName = "app/screens/HomeScreen.kt"))

  private fun column(d: UiDocument) = d.root as DocNode.Widget
  private fun child(d: UiDocument, i: Int) = column(d).children[i] as DocNode.Widget

  private fun text(x: String) = DocNode.Widget(Handle(0), "StyledText", mapOf("text" to lit(x)))
  private fun condition(field: String, vararg texts: String) = DocNode.Widget(
    Handle(0), "Condition", mapOf("field" to lit(field)), children = texts.map(::text),
  )

  /** The exporter's StyledText at [indent]. */
  private fun st(indent: String, x: String) = "${indent}StyledText(\n$indent  text = \"$x\",\n$indent)\n"
  private fun cond(indent: String, field: String, x: String) = "${indent}if (b.$field) {\n${st("$indent  ", x)}$indent}\n"

  /**
   * [nodes] inserted one op at a time as a run under the node [parent] picks,
   * the first after its child [afterIndex] (-1 = at the start), each next one
   * after the one inserted before it — as an editor inserting "after the
   * selection" does.
   */
  private fun insertRun(d0: UiDocument, parent: (UiDocument) -> DocNode.Widget, afterIndex: Int, nodes: List<DocNode>): UiDocument {
    var d = d0
    nodes.forEachIndexed { k, n ->
      val p = parent(d)
      val at = afterIndex + k
      d = d.apply(DocOp.InsertNode(p.handle, if (at < 0) null else p.children[at].handle, n)).doc
    }
    return d
  }

  /** [src] with [anchor] (occurring once) replaced by [replacement]. */
  private fun splice(anchor: String, replacement: String, text: String = src): String {
    assertEquals(1, text.split(anchor).size - 1, "anchor occurs once: $anchor")
    return text.replace(anchor, replacement)
  }

  private fun strip(n: DocNode): DocNode = when (n) {
    is DocNode.RawCode -> n.copy(handle = Handle(0), kindHint = null)
    is DocNode.Widget -> n.copy(handle = Handle(0), children = n.children.map(::strip))
  }

  private fun assertReingests(target: UiDocument, out: String) {
    assertEquals(strip(target.root), strip(doc(out).root), "the result re-ingests to the target document")
    assertEquals(out, merge(doc(out), out), "a no-op merge of the result is byte-identical")
  }

  @Test fun twoInsertsAfterAKeptSiblingKeepTheirOrder() {
    val d = doc()
    val target = insertRun(d, ::column, 0, listOf(text("P1"), text("P2")))
    val out = merge(target)
    assertEquals(splice("    StyledText(text = \"top\")\n", "    StyledText(text = \"top\")\n" + st("    ", "P1") + st("    ", "P2")), out)
    assertReingests(target, out)
  }

  @Test fun threeInsertsAfterAKeptSiblingKeepTheirOrder() {
    val d = doc()
    val target = insertRun(d, ::column, 0, listOf(text("P1"), text("P2"), text("P3")))
    val out = merge(target)
    assertEquals(
      splice("    StyledText(text = \"top\")\n", "    StyledText(text = \"top\")\n" + st("    ", "P1") + st("    ", "P2") + st("    ", "P3")),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun twoConditionsInsertedAfterAKeptSiblingKeepTheirOrder() {
    val d = doc()
    val target = insertRun(d, ::column, 0, listOf(condition("x1", "X1"), condition("x2", "X2")))
    val out = merge(target)
    assertEquals(
      splice("    StyledText(text = \"top\")\n", "    StyledText(text = \"top\")\n" + cond("    ", "x1", "X1") + cond("    ", "x2", "X2")),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun insertsAtTheBlockStartKeepTheirOrder() {
    val d = doc()
    // Conditions: a StyledText first would pair with "top" (the in-order shape match), not insert.
    val target = insertRun(d, ::column, -1, listOf(condition("s1", "S1"), condition("s2", "S2"), condition("s3", "S3")))
    val out = merge(target)
    assertEquals(
      splice("  Column {\n", "  Column {\n" + cond("    ", "s1", "S1") + cond("    ", "s2", "S2") + cond("    ", "s3", "S3")),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun insertsAtTheBlockEndKeepTheirOrder() {
    val d = doc()
    val target = insertRun(d, ::column, 4, listOf(text("E1"), text("E2")))
    val out = merge(target)
    assertEquals(
      splice("      StyledText(text = \"tail\")\n    }\n", "      StyledText(text = \"tail\")\n    }\n" + st("    ", "E1") + st("    ", "E2")),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun insertsBesideADeleteKeepTheirOrder() {
    val d = doc()
    // alpha's if deleted; Q0, Q1 inserted where it was (after "top").
    val withoutAlpha = d.apply(DocOp.DeleteNode(child(d, 1).handle)).doc
    val target = insertRun(withoutAlpha, ::column, 0, listOf(text("Q0"), text("Q1")))
    val out = merge(target)
    assertEquals(
      splice(
        "    if (b.alpha) {\n      // alpha comment\n      StyledText(text = \"A\")\n    }\n",
        st("    ", "Q0") + st("    ", "Q1"),
      ),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun insertsInsideAConditionsThenBlockKeepTheirOrder() {
    val d = doc()
    val target = insertRun(d, { child(it, 1) }, 0, listOf(text("T1"), text("T2")))
    val out = merge(target)
    assertEquals(
      splice("      StyledText(text = \"A\")\n", "      StyledText(text = \"A\")\n" + st("      ", "T1") + st("      ", "T2")),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun insertsInsideAnElseBlockKeepTheirOrder() {
    val d = doc()
    val elseOf = { x: UiDocument -> child(x, 2).children.last() as DocNode.Widget }
    val target = insertRun(d, elseOf, -1, listOf(condition("l1", "L1"), condition("l2", "L2")))
    val out = merge(target)
    assertEquals(
      splice("    } else {\n", "    } else {\n" + cond("      ", "l1", "L1") + cond("      ", "l2", "L2")),
      out,
    )
    assertReingests(target, out)
  }

  // A new else block starts empty: its first statement opens it, the next follows that one.
  @Test fun insertsIntoANewEmptyElseKeepTheirOrder() {
    val d = doc()
    val alpha = child(d, 1)
    val elseNode = DocNode.Widget(Handle(0), "Else", children = listOf(text("N1"), text("N2")))
    val target = d.apply(DocOp.InsertNode(alpha.handle, alpha.children.last().handle, elseNode)).doc
    val out = merge(target)
    assertEquals(
      splice(
        "      StyledText(text = \"A\")\n    }\n",
        "      StyledText(text = \"A\")\n    } else {\n" + st("      ", "N1") + st("      ", "N2") + "    }\n",
      ),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun insertsInsideARepeatBodyKeepTheirOrder() {
    val d = doc()
    val target = insertRun(d, { child(it, 4) }, 0, listOf(condition("r1", "R1"), condition("r2", "R2")))
    val out = merge(target)
    assertEquals(
      splice(
        "        StyledText(text = item.name)\n      }\n",
        "        StyledText(text = item.name)\n      }\n" + cond("      ", "r1", "R1") + cond("      ", "r2", "R2"),
      ),
      out,
    )
    assertReingests(target, out)
  }

  // Without the document's identities (no base), an edited condition beside a new
  // one is an insert and a delete — but the two land in the target's order.
  @Test fun anEditedConditionAndAnInsertAfterItLandInOrder() {
    val d = doc()
    val target = d.applyBatch(
      listOf(
        DocOp.SetProp(child(d, 1).handle, "field", lit("alphaX")),
        DocOp.InsertNode(column(d).handle, child(d, 1).handle, condition("mid", "M")),
      ),
    ).result!!.doc
    val out = merge(target)
    assertEquals(
      splice(
        "    if (b.alpha) {\n      // alpha comment\n      StyledText(text = \"A\")\n    }\n",
        cond("    ", "alphaX", "A") + cond("    ", "mid", "M"),
      ),
      out,
    )
    assertReingests(target, out)
    assertTrue("alphaX" in out)
  }
}
