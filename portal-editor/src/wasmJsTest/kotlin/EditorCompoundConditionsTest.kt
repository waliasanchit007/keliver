import dev.keliver.portal.Action
import dev.keliver.portal.Cond
import dev.keliver.portal.CondJoin
import dev.keliver.portal.CondTest
import dev.keliver.portal.Conditional
import dev.keliver.portal.PropKind
import dev.keliver.portal.Token
import dev.keliver.portal.condOfTerms
import dev.keliver.portal.document.CondTerm
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * K6: an equality or compound condition is shown read-only (its shape is the
 * code's); each branch is still edited, and the op it sends keeps every term.
 */
class EditorCompoundConditionsTest {
  private val compound = condOfTerms(listOf(Cond("kind", CondTest.Eq, operand = Token("C.Card", "card", "app.C")), Cond("busy", negate = true)), CondJoin.Or)

  @Test fun anExtendedConditionIsReadOnlyAndSpelledAsSource() {
    assertTrue(isReadOnlyCondition(compound))
    assertTrue(isReadOnlyCondition(Cond("n", CondTest.Eq, operand = 3)))
    assertFalse(isReadOnlyCondition(Cond("open", CondTest.Blank, negate = true)))
    assertEquals("b.kind == C.Card || !b.busy", conditionSource(compound))
    assertEquals("row.kind != \"a\"", conditionSource(Cond("row.kind", CondTest.Eq, negate = true, operand = "a")))
    assertEquals("equals a value", testLabel(CondTest.Eq))
  }

  @Test fun editingABranchKeepsEveryTerm() {
    val sent = mutableListOf<DocOp>()
    val row = conditionalRow(7, "StyledText", "text", "Text", PropKind.Text, Conditional(compound, "Card", "Other")) { op, _, _ -> sent += op }
    assertTrue("b.kind == C.Card || !b.busy" in (row.textContent ?: ""), row.textContent)
    val inputs = row.querySelectorAll("input")
    // No field / negate editor: just the two branches.
    assertEquals(2, inputs.length)
    (inputs.item(1) as HTMLInputElement).let { it.value = "Cash"; it.dispatchEvent(Event("input")); it.dispatchEvent(Event("change")) /* E11: committed */ }
    assertEquals(
      DocOp.SetProp(
        Handle(7), "text",
        PropValue.Conditional(
          "kind", "eq", whenTrue = lit("Card"), whenFalse = lit("Cash"),
          operand = PropValue.Token("C.Card", lit("card"), "app.C"), more = listOf(CondTerm("busy", negate = true)), join = "or",
        ),
      ),
      sent.single(),
    )
    // A conditional event too.
    val events = mutableListOf<DocOp>()
    val ev = conditionalEventRow(5, "onClick", Conditional(compound, Action("tap"), null)) { op, _, _ -> events += op }
    val evInputs = ev.querySelectorAll("input")
    assertEquals(2, evInputs.length)
    (evInputs.item(0) as HTMLInputElement).let { it.value = "go"; it.dispatchEvent(Event("input")); it.dispatchEvent(Event("change")) /* E11: committed */ }
    assertEquals(PropValue.Action("go"), ((events.single() as DocOp.SetProp).value as PropValue.Conditional).whenTrue)
    assertEquals("or", ((events.single() as DocOp.SetProp).value as PropValue.Conditional).join)
  }
}
