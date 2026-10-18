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
 * K3 review S-c: given the document the file was last written from (`base`),
 * write-back pairs a Condition with an `if` only when the target says it IS
 * that node (the same handle) — an edited condition keeps its `if` (and its
 * comments), a new Condition is an insert, a deleted one is a delete, however
 * they sit next to each other.
 */
class ConditionIdentityWriteBackTest {
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
          // beta comment
          StyledText(text = "B")
        }
        Row {
          StyledText(text = "r")
        }
        b.items.forEach { item ->
          if (b.gamma) {
            // gamma comment
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

  private val alphaIf = "    if (b.alpha) {\n      // alpha comment\n      StyledText(text = \"A\")\n    }\n"
  private val betaIf = "    if (b.beta) {\n      // beta comment\n      StyledText(text = \"B\")\n    }\n"

  private fun doc(text: String = src): UiDocument =
    Recognizer.recognize("app/screens/HomeScreen.kt", text)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }

  private fun merge(target: UiDocument, base: UiDocument): String =
    assertNotNull(WriteBack.merge(src, target, fileName = "app/screens/HomeScreen.kt", base = base))

  private fun column(d: UiDocument) = d.root as DocNode.Widget
  private fun child(d: UiDocument, i: Int) = column(d).children[i] as DocNode.Widget

  private fun condition(field: String, vararg texts: String) = DocNode.Widget(
    Handle(0), "Condition", mapOf("field" to lit(field)),
    children = texts.map { DocNode.Widget(Handle(0), "StyledText", mapOf("text" to lit(it))) },
  )

  private fun st(indent: String, x: String) = "${indent}StyledText(\n$indent  text = \"$x\",\n$indent)\n"
  private fun cond(indent: String, field: String, x: String) = "${indent}if (b.$field) {\n${st("$indent  ", x)}$indent}\n"

  private fun strip(n: DocNode): DocNode = when (n) {
    is DocNode.RawCode -> n.copy(handle = Handle(0), kindHint = null)
    is DocNode.Widget -> n.copy(handle = Handle(0), children = n.children.map(::strip))
  }

  private fun check(target: UiDocument, expected: String) {
    val out = merge(target, doc())
    assertEquals(expected, out)
    assertEquals(strip(target.root), strip(doc(out).root), "the result re-ingests to the target document")
  }

  // Deleting alpha's if and inserting an unrelated zeta in the same gap: alpha's
  // comment leaves with alpha, zeta is new text — never alpha's if rewritten.
  @Test fun aDeleteAndAnInsertInOneGapAreADeleteAndAnInsert() {
    val d = doc()
    val target = d.applyBatch(
      listOf(
        DocOp.DeleteNode(child(d, 1).handle),
        DocOp.InsertNode(column(d).handle, child(d, 0).handle, condition("zeta", "Z")),
      ),
    ).result!!.doc
    check(target, src.replace(alphaIf, cond("    ", "zeta", "Z")))
  }

  // Deleting beta and editing alpha's condition to beta: alpha's if (and comment)
  // stays with its new condition; beta's if is the one deleted.
  @Test fun anEditToADeletedSiblingsConditionKeepsTheEditedIf() {
    val d = doc()
    val target = d.applyBatch(
      listOf(
        DocOp.DeleteNode(child(d, 2).handle),
        DocOp.SetProp(child(d, 1).handle, "field", lit("beta")),
      ),
    ).result!!.doc
    check(target, src.replace(alphaIf + betaIf, alphaIf.replace("b.alpha", "b.beta")))
  }

  // An edited condition and a new one after it in one batch: the edit is a
  // rewrite of the condition only, the new one an insert after it.
  @Test fun anEditAndAnInsertInOneGapStaySurgical() {
    val d = doc()
    val target = d.applyBatch(
      listOf(
        DocOp.SetProp(child(d, 1).handle, "field", lit("alphaX")),
        DocOp.InsertNode(column(d).handle, child(d, 1).handle, condition("mid", "M")),
      ),
    ).result!!.doc
    check(target, src.replace(alphaIf, alphaIf.replace("b.alpha", "b.alphaX") + cond("    ", "mid", "M")))
  }

  @Test fun bothNeighboursEditedAreBothRewritten() {
    val d = doc()
    val target = d.applyBatch(
      listOf(
        DocOp.SetProp(child(d, 1).handle, "field", lit("beta2")),
        DocOp.SetProp(child(d, 2).handle, "field", lit("alpha2")),
      ),
    ).result!!.doc
    check(target, src.replace("if (b.alpha)", "if (b.beta2)").replace("if (b.beta)", "if (b.alpha2)"))
  }

  @Test fun aConditionInsideARepeatIsPairedByIdentityToo() {
    val d = doc()
    val gamma = child(d, 4).children[0] as DocNode.Widget
    val target = d.applyBatch(
      listOf(
        DocOp.DeleteNode(gamma.handle),
        DocOp.InsertNode(child(d, 4).handle, null, condition("delta", "D")),
      ),
    ).result!!.doc
    check(
      target,
      src.replace(
        "      if (b.gamma) {\n        // gamma comment\n        StyledText(text = item.name)\n      }\n",
        cond("      ", "delta", "D"),
      ),
    )
  }

  // A base that is not the file's tree (the file changed since) gives no identities:
  // the positional pairing applies, as without a base.
  @Test fun aBaseThatDoesNotMatchTheFileIsIgnored() {
    val d = doc()
    val stale = d.apply(DocOp.DeleteNode(child(d, 3).handle)).doc
    val target = d.apply(DocOp.SetProp(child(d, 1).handle, "negate", lit(true))).doc
    assertEquals(src.replace("if (b.alpha)", "if (!b.alpha)"), merge(target, stale))
  }
}
