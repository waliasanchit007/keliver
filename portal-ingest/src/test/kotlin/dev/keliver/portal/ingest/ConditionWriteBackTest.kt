package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * K3 review (B1/B2): write-back around `Condition` nodes is surgical — a new
 * Condition is an insert (never a rewrite of a neighbouring `if`), and adding
 * or removing an `else` touches only the `else { … }` text. Each scenario
 * asserts the whole file: [src] with exactly one splice.
 */
class ConditionWriteBackTest {
  private val src = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Row
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        if (b.open) {
          // keep me
          StyledText(colorArgb = 1, text = "open") // trailing
        } else {
          // closed comment
          StyledText(text = "closed")
        }
        Row {
          StyledText(text = "r")
        }
        if (b.ready) {
          // ready comment
          StyledText(colorArgb = 2, text = "ready") // trailing
        }
      }
    }

    interface HomeScreenBindings {
      val open: Boolean
      val ready: Boolean
    }
  """.trimIndent() + "\n"

  private fun doc(text: String = src): UiDocument =
    Recognizer.recognize("app/screens/HomeScreen.kt", text)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }

  private fun merge(target: UiDocument, text: String = src): String =
    assertNotNull(WriteBack.merge(text, target, fileName = "app/screens/HomeScreen.kt"))

  private fun column(d: UiDocument) = d.root as DocNode.Widget
  private fun child(d: UiDocument, i: Int) = column(d).children[i] as DocNode.Widget

  private fun condition(field: String, vararg texts: String) = DocNode.Widget(
    Handle(0), "Condition", mapOf("field" to lit(field)),
    children = texts.map { DocNode.Widget(Handle(0), "StyledText", mapOf("text" to lit(it))) },
  )

  /** [src] with [anchor] (occurring once) replaced by [replacement]. */
  private fun splice(anchor: String, replacement: String): String {
    assertEquals(1, src.split(anchor).size - 1, "anchor occurs once: $anchor")
    return src.replace(anchor, replacement)
  }

  private val newIf = "    if (b.showThis) {\n      StyledText(\n        text = \"new\",\n      )\n    }\n"

  // P8a: a palette Condition inserted before an existing if/else is an insert; the if/else stays put.
  @Test fun aNewConditionBeforeAnIfElseIsInsertedNotPairedWithIt() {
    val d = doc()
    val out = merge(d.apply(DocOp.InsertNode(column(d).handle, null, condition("showThis", "new"))).doc)
    assertEquals(splice("    if (b.open) {\n", newIf + "    if (b.open) {\n"), out)
    assertEquals(out, merge(doc(out), out), "the result re-ingests to the same document")
  }

  // P8c: a new Condition next to an existing one of the same else-ness never rewrites the existing one.
  @Test fun aNewConditionBesideOneOfTheSameShapeIsInserted() {
    val d = doc()
    val row = child(d, 1)
    val out = merge(d.apply(DocOp.InsertNode(column(d).handle, row.handle, condition("showThis", "new"))).doc)
    assertEquals(splice("    if (b.ready) {\n", newIf + "    if (b.ready) {\n"), out)
  }

  // A condition edit is still surgical: one Condition on each side of a gap is the same node.
  @Test fun aConditionEditBesideAnInsertStaysSurgical() {
    val d = doc()
    val ready = child(d, 2)
    val edited = d.applyBatch(
      listOf(
        DocOp.SetProp(ready.handle, "negate", lit(true)),
        DocOp.InsertNode(column(d).handle, null, DocNode.Widget(Handle(0), "StyledText", mapOf("text" to lit("top")))),
      ),
    ).result!!.doc
    val out = merge(edited)
    val expected = splice("    if (b.ready) {", "    if (!b.ready) {")
      .replace("  Column {\n", "  Column {\n    StyledText(\n      text = \"top\",\n    )\n")
    assertEquals(expected, out)
  }

  // P8b: deleting an Else and inserting after its Condition in one window: the insert lands after the if.
  @Test fun deletingAnElseAndInsertingAfterItsConditionKeepsTheInsertInPlace() {
    val d = doc()
    val cond = child(d, 0)
    val elseNode = cond.children.last() as DocNode.Widget
    val edited = d.applyBatch(
      listOf(
        DocOp.DeleteNode(elseNode.handle),
        DocOp.InsertNode(column(d).handle, cond.handle, DocNode.Widget(Handle(0), "StyledText", mapOf("text" to lit("after")))),
      ),
    ).result!!.doc
    val out = merge(edited)
    val expected = splice(
      "    } else {\n      // closed comment\n      StyledText(text = \"closed\")\n    }\n",
      "    }\n    StyledText(\n      text = \"after\",\n    )\n",
    )
    assertEquals(expected, out)
  }

  // P1: `+ else branch` adds just ` else {\n}` — comments and argument order in the then block stay.
  @Test fun addingAnElseInsertsOnlyTheElseBlock() {
    val d = doc()
    val ready = child(d, 2)
    val out = merge(d.apply(DocOp.InsertNode(ready.handle, ready.children.last().handle, DocNode.Widget(Handle(0), "Else"))).doc)
    assertEquals(
      splice("\"ready\") // trailing\n    }\n", "\"ready\") // trailing\n    } else {\n    }\n"),
      out,
    )
    assertEquals(out, merge(doc(out), out), "an empty else re-ingests and a no-op stays byte-identical")
  }

  @Test fun addingAnElseWithContentInsertsItInsideTheElseBlock() {
    val d = doc()
    val ready = child(d, 2)
    val elseNode = DocNode.Widget(Handle(0), "Else", children = listOf(DocNode.Widget(Handle(0), "StyledText", mapOf("text" to lit("not yet")))))
    val out = merge(d.apply(DocOp.InsertNode(ready.handle, ready.children.last().handle, elseNode)).doc)
    assertEquals(
      splice(
        "\"ready\") // trailing\n    }\n",
        "\"ready\") // trailing\n    } else {\n      StyledText(\n        text = \"not yet\",\n      )\n    }\n",
      ),
      out,
    )
  }

  @Test fun removingAnElseDeletesOnlyTheElseBlock() {
    val d = doc()
    val cond = child(d, 0)
    val out = merge(d.apply(DocOp.DeleteNode(cond.children.last().handle)).doc)
    assertEquals(splice("    } else {\n      // closed comment\n      StyledText(text = \"closed\")\n    }\n", "    }\n"), out)
  }

  @Test fun removingAnElseWhileEditingTheThenBlockKeepsBoth() {
    val d = doc()
    val cond = child(d, 0)
    val open = cond.children.first() as DocNode.Widget
    val edited = d.applyBatch(
      listOf(DocOp.DeleteNode(cond.children.last().handle), DocOp.SetProp(open.handle, "text", lit("OPEN"))),
    ).result!!.doc
    val out = merge(edited)
    assertEquals(
      splice(
        "text = \"open\") // trailing\n    } else {\n      // closed comment\n      StyledText(text = \"closed\")\n    }\n",
        "text = \"OPEN\") // trailing\n    }\n",
      ),
      out,
    )
  }

  // The same rule for any replaced statement: an insert anchored on replaced RawCode lands right after it.
  @Test fun anInsertAfterReplacedRawCodeLandsAfterIt() {
    val text = src.replace("    Row {\n", "    Legacy(1)\n    Row {\n")
    val d = doc(text)
    val raw = column(d).children[1]
    assertEquals(true, raw is DocNode.RawCode, raw.toString())
    val edited = d.applyBatch(
      listOf(
        DocOp.ReplaceRaw(raw.handle, "Legacy(2)"),
        DocOp.InsertNode(column(d).handle, raw.handle, DocNode.Widget(Handle(0), "StyledText", mapOf("text" to lit("x")))),
      ),
    ).result!!.doc
    assertEquals(
      text.replace("    Legacy(1)\n", "    Legacy(2)\n    StyledText(\n      text = \"x\",\n    )\n"),
      merge(edited, text),
    )
  }
}
