package dev.keliver.portal.ingest

import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E14: a component's body is a document the editor edits as it edits a screen's. Its parameters
 * are the contract, read by their bare names, and its edits are written to its own file —
 * surgically or not at all, since its signature is its contract with every screen that calls it.
 */
class ComponentDocumentTest {
  private val file = "app/components/StatPair.kt"

  private val component = """
    package app.components

    import androidx.compose.runtime.Composable
    import dev.keliver.Modifier
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    /** A titled value. */
    @Composable
    fun StatPair(title: String, value: String, onTap: () -> Unit, modifier: Modifier = Modifier) {
      Column(modifier = modifier) {
        StyledText(text = title, fontSize = 13)
        StyledText(text = value, fontSize = 16, weight = 600)
        StyledText(text = "per year", fontSize = 11)
      }
    }
  """.trimIndent() + "\n"

  private fun doc(text: String): UiDocument =
    Recognizer.recognize(file, text, asComponent = true)!!.let { UiDocument("component:StatPair", it.root, it.contract, 0, 1000) }

  private fun column(d: UiDocument) = d.root as DocNode.Widget

  private fun merge(text: String, target: UiDocument, base: UiDocument): String =
    when (val o = WriteBack.attempt(text, target, fileName = file, base = base, asComponent = true)) {
      is WriteBack.Outcome.Merged -> o.text
      is WriteBack.Outcome.NotSurgical -> throw AssertionError("the change is written surgically, not: ${o.reason}")
    }

  @Test fun theParametersAreTheContractAndTheBodyReadsThemByName() {
    val rec = Recognizer.recognize(file, component, asComponent = true)!!
    assertEquals(mapOf("title" to "String", "value" to "String"), rec.contract.fields)
    assertEquals(listOf("onTap"), rec.contract.actions)
    val texts = (rec.root as DocNode.Widget).children.map { (it as DocNode.Widget).props["text"] }
    assertEquals(listOf(PropValue.Bind("title"), PropValue.Bind("value"), lit("per year")), texts)
  }

  @Test fun aScreenFileIsStillReadAsAScreen() {
    // The default is unchanged: the first parameter of a screen is its bindings parameter.
    val rec = Recognizer.recognize(file, component)!!
    assertEquals("title", rec.bindingsParam)
  }

  @Test fun editingAnInternalTextChangesOnlyThatArgumentAndItsUndoGivesTheFileBack() {
    val d0 = doc(component)
    val applied = d0.apply(DocOp.SetProp(column(d0).children[2].handle, "text", lit("per month")))
    val mid = merge(component, applied.doc, d0)
    assertEquals(component.replace("\"per year\"", "\"per month\""), mid)
    val undone = applied.doc.apply(applied.inverse!!)
    assertEquals(component, merge(mid, undone.doc, applied.doc))
  }

  @Test fun aParameterIsWrittenByItsBareName() {
    val d0 = doc(component)
    val bound = d0.apply(DocOp.SetProp(column(d0).children[2].handle, "text", PropValue.Bind("title"))).doc
    assertTrue("StyledText(text = title, fontSize = 11)" in merge(component, bound, d0))

    val inserted = d0.apply(
      DocOp.InsertNode(
        d0.root.handle, column(d0).children[2].handle,
        DocNode.Widget(Handle(0), "StyledText", props = mapOf("text" to PropValue.Bind("value"))),
      ),
    ).doc
    val text = merge(component, inserted, d0)
    assertTrue(Regex("""StyledText\(\s*text = value,?\s*\)""").containsMatchIn(text), text)
    assertTrue("b.value" !in text, text)
  }

  @Test fun theWriterRefusesToChangeTheSignatureOrToCreateTheFile() {
    val d0 = doc(component)
    fun plan(onDisk: String?, d: UiDocument) = ScreenWriter.plan(
      onDisk = onDisk, doc = d, base = d0, baseText = component, fileName = file,
      functionName = "StatPair", packageName = null, asComponent = true,
    )
    val renamed = d0.copy(contract = Contract(fields = mapOf("heading" to "String", "value" to "String"), actions = listOf("onTap")))
    assertTrue("parameters" in assertIs<ScreenWriter.Refuse>(plan(component, renamed)).reason)
    assertIs<ScreenWriter.Refuse>(plan(null, d0))

    val edited = d0.apply(DocOp.SetProp(column(d0).children[1].handle, "weight", lit(700))).doc
    val written = assertIs<ScreenWriter.Write>(plan(component, edited))
    assertEquals(component.replace("weight = 600", "weight = 700"), written.text)
  }

  @Test fun aComponentWhoseBodyIsNotReadableIsNotADocument() {
    val noBody = component.replace(Regex("\\{\\n  Column[\\s\\S]*\\n}\\n$"), "= Unit\n")
    assertNull(Recognizer.recognize(file, noBody, asComponent = true))
  }
}
