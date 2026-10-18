import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.Cond
import dev.keliver.portal.CondTest
import dev.keliver.portal.Conditional
import dev.keliver.portal.PropKind
import dev.keliver.portal.Token
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** K3: the properties panel's conditional editors — what they show and the ops they send. */
class EditorConditionalsTest {
  private val color = Conditional(Cond("selected"), Token("C.Ink", -14737633, "app.C"), -1)

  @Test fun aBranchOrConditionEditKeepsTheRest() {
    assertEquals(
      PropValue.Conditional("selected", whenTrue = PropValue.Token("C.Ink", lit(-14737633), "app.C"), whenFalse = lit(-16777216)),
      withBranch(color, whenTrue = false, value = lit(-16777216)),
    )
    assertEquals(
      PropValue.Conditional("note", test = "blank", negate = true, whenTrue = PropValue.Token("C.Ink", lit(-14737633), "app.C"), whenFalse = lit(-1)),
      withCondition(color, "note", CondTest.Blank, negate = true),
    )
    assertEquals(DocOp.SetModifier(Handle(3), "Background.colorArgb", lit(1)), valueOp(3, "mod.Background.colorArgb", lit(1)))
    assertEquals(DocOp.SetProp(Handle(3), "colorArgb", lit(1)), valueOp(3, "colorArgb", lit(1)))
  }

  @Test fun thePanelRendersBothBranchesAndSendsTheEditedOne() {
    val sent = mutableListOf<DocOp>()
    val text = Conditional(Cond("open"), "Open", "Closed")
    val row = conditionalRow(7, "StyledText", "text", "Text", PropKind.Text, text) { op, _, _ -> sent += op }
    val inputs = row.querySelectorAll("input")
    // field, negate checkbox, then-branch, else-branch
    assertEquals(4, inputs.length)
    val elseInput = inputs.item(3) as HTMLInputElement
    assertEquals("Closed", elseInput.value)
    elseInput.value = "Shut"
    elseInput.dispatchEvent(Event("input"))
    assertEquals(
      DocOp.SetProp(Handle(7), "text", PropValue.Conditional("open", whenTrue = lit("Open"), whenFalse = lit("Shut"))),
      sent.single(),
    )
    // A token branch shows its reference; the whole thing never crashes on any kind.
    val colorRow = conditionalRow(7, "StyledText", "colorArgb", "Color", PropKind.Color, color) { op, _, _ -> sent += op }
    assertTrue("◆ C.Ink" in (colorRow.textContent ?: ""), colorRow.textContent)
    for (kind in PropKind.entries) conditionalRow(7, "StyledText", "x", "x", kind, Conditional(Cond("f"), null, null)) { _, _, _ -> }
  }

  @Test fun aConditionalEventSendsNullForAnEmptyActionName() {
    val sent = mutableListOf<DocOp>()
    val row = conditionalEventRow(5, "onClick", Conditional(Cond("enabled"), Action("tap"), null)) { op, _, _ -> sent += op }
    val inputs = row.querySelectorAll("input")
    val thenInput = inputs.item(2) as HTMLInputElement
    assertEquals("tap", thenInput.value)
    thenInput.value = ""
    thenInput.dispatchEvent(Event("input"))
    assertEquals(DocOp.SetProp(Handle(5), "onClick", PropValue.Conditional("enabled")), sent.single())
  }

  // Review S4: the panel is not re-rendered between edits — each op builds on the previous one.
  @Test fun successiveEditsWithoutAReRenderKeepEachOther() {
    val sent = mutableListOf<DocOp>()
    val row = conditionalRow(7, "StyledText", "text", "Text", PropKind.Text, Conditional(Cond("open"), "Open", "Closed")) { op, _, _ -> sent += op }
    val inputs = row.querySelectorAll("input")
    fun type(i: Int, v: String) = (inputs.item(i) as HTMLInputElement).let { it.value = v; it.dispatchEvent(Event("input")) }
    type(2, "Yes")
    type(3, "No")
    assertEquals(
      DocOp.SetProp(Handle(7), "text", PropValue.Conditional("open", whenTrue = lit("Yes"), whenFalse = lit("No"))),
      sent.last(),
    )
    type(0, "shown")
    assertEquals(
      DocOp.SetProp(Handle(7), "text", PropValue.Conditional("shown", whenTrue = lit("Yes"), whenFalse = lit("No"))),
      sent.last(),
    )
    // The same for a conditional event.
    val events = mutableListOf<DocOp>()
    val ev = conditionalEventRow(5, "onClick", Conditional(Cond("enabled"), Action("tap"), null)) { op, _, _ -> events += op }
    val evInputs = ev.querySelectorAll("input")
    (evInputs.item(3) as HTMLInputElement).let { it.value = "stop"; it.dispatchEvent(Event("input")) }
    (evInputs.item(2) as HTMLInputElement).let { it.value = "go"; it.dispatchEvent(Event("input")) }
    assertEquals(
      DocOp.SetProp(Handle(5), "onClick", PropValue.Conditional("enabled", whenTrue = PropValue.Action("go"), whenFalse = PropValue.Action("stop"))),
      events.last(),
    )
  }

  // Review S4 nit: an edit the relay refused is not built on — the next one
  // starts from the last accepted value.
  @Test fun aRefusedEditIsNotBuiltOn() {
    val sent = mutableListOf<DocOp>()
    val results = mutableListOf<(Boolean) -> Unit>()
    val row = conditionalRow(7, "StyledText", "text", "Text", PropKind.Text, Conditional(Cond("open"), "Open", "Closed")) { op, _, done ->
      sent += op
      results += done
    }
    val inputs = row.querySelectorAll("input")
    fun type(i: Int, v: String) = (inputs.item(i) as HTMLInputElement).let { it.value = v; it.dispatchEvent(Event("input")) }
    type(2, "Yes")
    results.last()(true)
    type(3, "No")
    results.last()(false)
    type(0, "shown")
    assertEquals(
      DocOp.SetProp(Handle(7), "text", PropValue.Conditional("shown", whenTrue = lit("Yes"), whenFalse = lit("Closed"))),
      sent.last(),
    )
    // Edits still in flight build on each other; a refusal returns to the last accepted one.
    results.last()(true)
    type(2, "A")
    type(3, "B")
    results[results.size - 2](false)
    type(3, "C")
    assertEquals(
      DocOp.SetProp(Handle(7), "text", PropValue.Conditional("shown", whenTrue = lit("Yes"), whenFalse = lit("C"))),
      sent.last(),
    )
  }

  // Review nit: clearing a bound branch's field name sends nothing (never `Bind("")`).
  @Test fun clearingABoundBranchSendsNothing() {
    val sent = mutableListOf<DocOp>()
    val row = conditionalRow(7, "StyledText", "text", "Text", PropKind.Text, Conditional(Cond("open"), Bind("title"), "-")) { op, _, _ -> sent += op }
    val bound = row.querySelectorAll("input").item(2) as HTMLInputElement
    assertEquals("title", bound.value)
    bound.value = "  "
    bound.dispatchEvent(Event("input"))
    assertTrue(sent.isEmpty(), sent.toString())
    bound.value = "name"
    bound.dispatchEvent(Event("input"))
    assertEquals(DocOp.SetProp(Handle(7), "text", PropValue.Conditional("open", whenTrue = PropValue.Bind("name"), whenFalse = lit("-"))), sent.single())
  }

  @Test fun anElseBranchIsLookedThroughForTheScopeParent() {
    val tree = WidgetNode(
      "Row", id = 1,
      children = listOf(
        WidgetNode("Condition", mapOf("field" to "x"), id = 2, children = listOf(
          WidgetNode("Else", id = 3, children = listOf(WidgetNode("StyledText", id = 4))),
        )),
      ),
    )
    assertEquals("Row", scopeParentType(tree, 4))
  }
}
