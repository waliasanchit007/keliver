package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K3 review safety net: a surgical merge is written only when its text
 * re-ingests to the target document (handles aside). Otherwise [WriteBack.merge]
 * returns null and the caller writes the full export, as for any change that
 * is not surgical.
 */
class WriteBackSafetyNetTest {
  private val src = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        // keep me
        StyledText(text = "top")
        Legacy(1)
        if (b.open) {
          StyledText(text = "o")
        }
      }
    }

    interface HomeScreenBindings {
      val open: Boolean
    }
  """.trimIndent() + "\n"

  private fun doc(text: String = src): UiDocument =
    Recognizer.recognize("app/screens/HomeScreen.kt", text)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }

  private fun merge(target: UiDocument, text: String = src): String? =
    WriteBack.merge(text, target, fileName = "app/screens/HomeScreen.kt")

  private fun column(d: UiDocument) = d.root as DocNode.Widget

  private fun <T> diverging(corrupt: (String) -> String, block: () -> T): T {
    WriteBack.afterSpliceForTest.set(corrupt)
    try {
      return block()
    } finally {
      WriteBack.afterSpliceForTest.remove()
    }
  }

  @Test fun aSurgicalResultThatReingestsToTheTargetIsWritten() {
    val d = doc()
    val top = column(d).children[0]
    val out = assertNotNull(merge(d.apply(DocOp.SetProp(top.handle, "text", lit("TOP"))).doc))
    assertEquals(src.replace("\"top\"", "\"TOP\""), out)
  }

  @Test fun aSurgicalResultThatDivergesFallsBackToTheExport() {
    val d = doc()
    val top = column(d).children[0]
    val target = d.apply(DocOp.SetProp(top.handle, "text", lit("TOP"))).doc
    assertNull(diverging({ it.replace("\"TOP\"", "\"T0P\"") }) { merge(target) })
  }

  @Test fun rawCodeComparesByItsText() {
    val d = doc()
    val raw = column(d).children[1]
    assertTrue(raw is DocNode.RawCode, raw.toString())
    // RawCode kept as it is: a no-op merge is byte-identical, never an export.
    assertEquals(src, merge(d))
    // RawCode replaced: written surgically when the file holds the new text ...
    val target = d.apply(DocOp.ReplaceRaw(raw.handle, "Legacy(2)")).doc
    assertEquals(src.replace("Legacy(1)", "Legacy(2)"), merge(target))
    // ... and not when it holds another one.
    assertNull(diverging({ it.replace("Legacy(2)", "Legacy(3)") }) { merge(target) })
  }

  @Test fun aConditionThatReingestsAsRawCodeDiverges() {
    val d = doc()
    val cond = column(d).children[2]
    val target = d.apply(DocOp.SetProp(cond.handle, "negate", lit(true))).doc
    assertEquals(src.replace("if (b.open)", "if (!b.open)"), merge(target))
    // `else if` is not grammar: the if re-ingests as RawCode, not the target's Condition.
    assertNull(
      diverging({ it.replace("    }\n  }\n}", "    } else if (b.open) {\n    }\n  }\n}") }) { merge(target) },
    )
  }

  @Test fun theComparisonIgnoresHandlesOnly() {
    val a = DocNode.Widget(Handle(1), "Column", children = listOf(DocNode.RawCode(Handle(2), "x()", "effect")))
    val b = DocNode.Widget(Handle(9), "Column", children = listOf(DocNode.RawCode(Handle(8), "x()")))
    assertTrue(WriteBack.sameTree(a, b))
    assertFalse(WriteBack.sameTree(a, a.copy(props = mapOf("p" to lit(1)))))
    assertFalse(WriteBack.sameTree(a, a.copy(modifiers = mapOf("Padding.allDp" to lit(1)))))
    assertFalse(WriteBack.sameTree(a, a.copy(type = "Row")))
    assertFalse(WriteBack.sameTree(a, a.copy(children = listOf(DocNode.RawCode(Handle(2), "y()")))))
    assertFalse(WriteBack.sameTree(a, a.copy(children = emptyList())))
    // A Condition compares by its condition: an explicit `negate = false` is no negation.
    val c = DocNode.Widget(Handle(1), "Condition", mapOf("field" to lit("open")))
    assertTrue(WriteBack.sameTree(c, c.copy(props = c.props + ("negate" to lit(false)))))
    assertFalse(WriteBack.sameTree(c, c.copy(props = c.props + ("negate" to lit(true)))))
    assertFalse(WriteBack.sameTree(c, c.copy(props = mapOf("field" to lit("shut")))))
  }
}
