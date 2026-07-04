package dev.keliver.portal.ingest

import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.exportKotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecognizerTest {
  private val screen = """
    import androidx.compose.runtime.Composable
    import dev.keliver.material.compose.Button
    import dev.keliver.material.compose.StyledBox
    import dev.keliver.material.compose.StyledText
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Spacer
    import dev.keliver.Modifier
    import dev.keliver.material.compose.padding
    import dev.keliver.material.compose.animateContentSize
    import dev.keliver.ui.Dp

    // human comment
    @Composable
    fun CheckoutScreen(b: CheckoutBindings) {
      StyledBox(
        cornerRadiusDp = 12,
        fillWidth = true,
        gradientColorsArgb = listOf(-2840, -5674),
        gradientStops = listOf(0.0f, 1.0f),
      ) {
        Column {
          StyledText(
            modifier = Modifier.padding(8).animateContentSize(),
            text = b.title,
            fontSize = 22,
          )
          if (b.title.length > 40) {
            StyledText(text = "long", fontSize = 9)
          }
          Spacer(height = Dp(12.0))
          Button(text = "Buy", onClick = b::buy)
        }
      }
    }

    interface CheckoutBindings {
      val title: String
      fun buy()
    }
  """.trimIndent()

  @Test fun goldenRecognize() {
    val r = Recognizer.recognize("CheckoutScreen.kt", screen)!!
    assertEquals("CheckoutScreen", r.screenName)
    assertEquals(Contract(fields = mapOf("title" to "String"), actions = listOf("buy")), r.contract)

    val box = r.root
    assertEquals("StyledBox", box.type)
    assertEquals(lit(12), box.props["cornerRadiusDp"])
    assertEquals(lit(true), box.props["fillWidth"])
    assertEquals(PropValue.Lit("li", li = listOf(-2840, -5674)), box.props["gradientColorsArgb"])
    assertEquals(PropValue.Lit("lf", lf = listOf(0.0f, 1.0f)), box.props["gradientStops"])

    val column = box.children[0] as DocNode.Widget
    val text = column.children[0] as DocNode.Widget
    assertEquals(PropValue.Bind("title"), text.props["text"])
    assertEquals(lit(22), text.props["fontSize"])
    assertEquals(lit(8), text.modifiers["Padding.allDp"])
    assertEquals(lit(true), text.modifiers["AnimateContentSize"])

    val raw = assertIs<DocNode.RawCode>(column.children[1])
    assertTrue(raw.text.startsWith("if (b.title.length > 40)"))
    assertEquals("condition", raw.kindHint)

    val spacer = column.children[2] as DocNode.Widget
    assertEquals(PropValue.Lit("d", d = 12.0), spacer.props["height"])

    val button = column.children[3] as DocNode.Widget
    assertEquals(PropValue.Action("buy"), button.props["onClick"])
  }

  /** THE M3 gate: the dual-written exporter output re-ingests to the same document. */
  @Test fun exporterRoundTrip() {
    val doc = UiDocument(
      screen = "main",
      root = DocNode.Widget(Handle(1), "StyledBox",
        props = mapOf("cornerRadiusDp" to lit(12), "fillWidth" to lit(true)),
        children = listOf(
          DocNode.Widget(Handle(2), "Column", children = listOf(
            DocNode.Widget(Handle(3), "StyledText",
              props = mapOf("text" to PropValue.Bind("title"), "fontSize" to lit(22)),
              modifiers = mapOf("Padding.allDp" to lit(8))),
            DocNode.Widget(Handle(4), "Button",
              props = mapOf("text" to lit("Buy"), "onClick" to PropValue.Action("buy"))),
            DocNode.Widget(Handle(5), "Spacer", props = mapOf("height" to PropValue.Lit("d", d = 12.0))),
          )),
        )),
      contract = Contract(fields = mapOf("title" to "String"), actions = listOf("buy")),
      version = 7, nextHandle = 10,
    )
    val exported = exportKotlin(doc.toWidgetTree(), functionName = "PortalScreen")
    val r = Recognizer.recognize("main.kt", exported)!!
    val reconciled = Reconciler.reconcile(doc, r)

    // Same semantic content, version bumped, HANDLES PRESERVED.
    assertEquals(doc.root, reconciled.root)
    assertEquals(doc.contract, reconciled.contract)
    assertEquals(doc.version + 1, reconciled.version)
  }

  @Test fun reconcilerPreservesHandlesAcrossEditsAndAllocatesNew() {
    val old = UiDocument(
      screen = "main",
      root = DocNode.Widget(Handle(1), "Column", children = listOf(
        DocNode.Widget(Handle(2), "StyledText", mapOf("text" to lit("a"))),
        DocNode.Widget(Handle(3), "Button", mapOf("text" to lit("Buy"))),
      )),
      contract = Contract(), version = 5, nextHandle = 10,
    )
    // parsed: text edited, a Spacer inserted between, button kept
    val parsed = Recognized(
      root = DocNode.Widget(Handle(-1), "Column", children = listOf(
        DocNode.Widget(Handle(-2), "StyledText", mapOf("text" to lit("EDITED"))),
        DocNode.Widget(Handle(-3), "Spacer", mapOf("height" to PropValue.Lit("d", d = 8.0))),
        DocNode.Widget(Handle(-4), "Button", mapOf("text" to lit("Buy"))),
      )),
      contract = Contract(),
      screenName = "PortalScreen",
    )
    val out = Reconciler.reconcile(old, parsed)
    val root = out.root as DocNode.Widget
    assertEquals(Handle(1), root.handle)
    assertEquals(Handle(2), root.children[0].handle)            // matched: kept
    assertEquals(lit("EDITED"), (root.children[0] as DocNode.Widget).props["text"])
    assertEquals(Handle(10), root.children[1].handle)           // new: allocated
    assertEquals(Handle(3), root.children[2].handle)            // matched: kept
    assertEquals(11, out.nextHandle)
  }
}
