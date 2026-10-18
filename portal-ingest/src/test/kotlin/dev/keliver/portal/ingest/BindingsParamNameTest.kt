package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * K3 review nit: a condition rewritten in place is spelled with the file's
 * own bindings parameter (`bindings.open`), not the exporter's `b`.
 */
class BindingsParamNameTest {
  private val src = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Row
    import dev.keliver.material.compose.StyledText
    import dev.keliver.ui.Margin
    import dev.keliver.ui.dp

    @Composable
    fun HomeScreen(bindings: HomeScreenBindings) {
      Column {
        // keep me
        if (bindings.open) {
          StyledText(text = "o")
        }
        Row(margin = Margin(start = if (bindings.raised) 16.dp else 0.dp)) {
          StyledText(text = if (bindings.open) "a" else "b")
        }
      }
    }

    interface HomeScreenBindings {
      val open: Boolean
      val raised: Boolean
    }
  """.trimIndent() + "\n"

  private fun doc(): UiDocument =
    Recognizer.recognize("app/screens/HomeScreen.kt", src)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }

  private fun merge(target: UiDocument) = WriteBack.merge(src, target, fileName = "app/screens/HomeScreen.kt")

  private fun child(d: UiDocument, i: Int) = (d.root as DocNode.Widget).children[i] as DocNode.Widget

  @Test fun aConditionNodeIsRewrittenWithTheFilesParameter() {
    val d = doc()
    assertEquals("Condition", child(d, 0).type)
    assertEquals(src.replace("if (bindings.open) {", "if (!bindings.open) {"), merge(d.apply(DocOp.SetProp(child(d, 0).handle, "negate", lit(true))).doc))
  }

  @Test fun aMarginSidesConditionIsRewrittenWithTheFilesParameter() {
    val d = doc()
    val row = child(d, 1)
    val start = row.props["margin.start"] as PropValue.Conditional
    assertEquals(
      src.replace("if (bindings.raised)", "if (!bindings.raised)"),
      merge(d.apply(DocOp.SetProp(row.handle, "margin.start", start.copy(negate = true))).doc),
    )
  }

  @Test fun aConditionalValuesConditionIsRewrittenWithTheFilesParameter() {
    val d = doc()
    val text = child(d, 1).children.single() as DocNode.Widget
    val v = text.props["text"] as PropValue.Conditional
    assertEquals(
      src.replace("if (bindings.open) \"a\"", "if (!bindings.open) \"a\""),
      merge(d.apply(DocOp.SetProp(text.handle, "text", v.copy(negate = true))).doc),
    )
  }
}
