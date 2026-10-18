import dev.keliver.portal.Action
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
    val row = conditionalRow(7, "StyledText", "text", "Text", PropKind.Text, text) { op, _ -> sent += op }
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
    val colorRow = conditionalRow(7, "StyledText", "colorArgb", "Color", PropKind.Color, color) { op, _ -> sent += op }
    assertTrue("◆ C.Ink" in (colorRow.textContent ?: ""), colorRow.textContent)
    for (kind in PropKind.entries) conditionalRow(7, "StyledText", "x", "x", kind, Conditional(Cond("f"), null, null)) { _, _ -> }
  }

  @Test fun aConditionalEventSendsNullForAnEmptyActionName() {
    val sent = mutableListOf<DocOp>()
    val row = conditionalEventRow(5, "onClick", Conditional(Cond("enabled"), Action("tap"), null)) { op, _ -> sent += op }
    val inputs = row.querySelectorAll("input")
    val thenInput = inputs.item(2) as HTMLInputElement
    assertEquals("tap", thenInput.value)
    thenInput.value = ""
    thenInput.dispatchEvent(Event("input"))
    assertEquals(DocOp.SetProp(Handle(5), "onClick", PropValue.Conditional("enabled")), sent.single())
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
