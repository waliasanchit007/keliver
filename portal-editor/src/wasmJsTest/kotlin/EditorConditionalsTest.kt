import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.Cond
import dev.keliver.portal.CondTest
import dev.keliver.portal.Conditional
import dev.keliver.portal.PropKind
import dev.keliver.portal.Token
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.document.Contract
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
  init {
    // E27: a conditional value's field is chosen from the open document's contract.
    openContract = { Contract(fields = mapOf("open" to "Boolean", "shown" to "Boolean", "enabled" to "Boolean", "note" to "String"), actions = listOf("tap", "go", "stop")) }
  }

  /** Chooses [field] in the condition's field dropdown, as a user does. */
  /** Chooses action [name] ("" = no action) in the [i]th action dropdown, as a user does (E28). */
  private fun pickAction(row: org.w3c.dom.Element, i: Int, name: String) {
    val sel = row.querySelectorAll("select[aria-label$=action]").item(i) as org.w3c.dom.HTMLSelectElement
    sel.value = name
    sel.dispatchEvent(Event("change"))
  }

  private fun pickField(row: org.w3c.dom.Element, field: String) {
    val sel = row.querySelector(".cond-editor select") as org.w3c.dom.HTMLSelectElement
    sel.value = field
    sel.dispatchEvent(Event("change"))
  }
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
    // E27: the field and test are dropdowns; the inputs are the then- and else-branch
    assertEquals(2, inputs.length)
    assertEquals(2, row.querySelectorAll(".cond-editor select").length)
    val elseInput = inputs.item(1) as HTMLInputElement
    assertEquals("Closed", elseInput.value)
    elseInput.value = "Shut"
    elseInput.dispatchEvent(Event("input")); elseInput.dispatchEvent(Event("change")) /* E11: committed */
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
    assertEquals("tap", (row.querySelector("select[aria-label$=action]") as org.w3c.dom.HTMLSelectElement).value)
    pickAction(row, 0, "")
    assertEquals(DocOp.SetProp(Handle(5), "onClick", PropValue.Conditional("enabled")), sent.single())
  }

  // Review S4: the panel is not re-rendered between edits — each op builds on the previous one.
  @Test fun successiveEditsWithoutAReRenderKeepEachOther() {
    val sent = mutableListOf<DocOp>()
    val row = conditionalRow(7, "StyledText", "text", "Text", PropKind.Text, Conditional(Cond("open"), "Open", "Closed")) { op, _, _ -> sent += op }
    val inputs = row.querySelectorAll("input")
    fun type(i: Int, v: String) = (inputs.item(i) as HTMLInputElement).let { it.value = v; it.dispatchEvent(Event("input")); it.dispatchEvent(Event("change")) /* E11: committed */ }
    type(0, "Yes")
    type(1, "No")
    assertEquals(
      DocOp.SetProp(Handle(7), "text", PropValue.Conditional("open", whenTrue = lit("Yes"), whenFalse = lit("No"))),
      sent.last(),
    )
    pickField(row, "shown")
    assertEquals(
      DocOp.SetProp(Handle(7), "text", PropValue.Conditional("shown", whenTrue = lit("Yes"), whenFalse = lit("No"))),
      sent.last(),
    )
    // The same for a conditional event.
    val events = mutableListOf<DocOp>()
    val ev = conditionalEventRow(5, "onClick", Conditional(Cond("enabled"), Action("tap"), null)) { op, _, _ -> events += op }
    pickAction(ev, 1, "stop")
    pickAction(ev, 0, "go")
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
    fun type(i: Int, v: String) = (inputs.item(i) as HTMLInputElement).let { it.value = v; it.dispatchEvent(Event("input")); it.dispatchEvent(Event("change")) /* E11: committed */ }
    type(0, "Yes")
    results.last()(true)
    type(1, "No")
    results.last()(false)
    pickField(row, "shown")
    assertEquals(
      DocOp.SetProp(Handle(7), "text", PropValue.Conditional("shown", whenTrue = lit("Yes"), whenFalse = lit("Closed"))),
      sent.last(),
    )
    // Edits still in flight build on each other; a refusal returns to the last accepted one.
    results.last()(true)
    type(0, "A")
    type(1, "B")
    results[results.size - 2](false)
    type(1, "C")
    assertEquals(
      DocOp.SetProp(Handle(7), "text", PropValue.Conditional("shown", whenTrue = lit("Yes"), whenFalse = lit("C"))),
      sent.last(),
    )
  }

  // Review nit: clearing a bound branch's field name sends nothing (never `Bind("")`).
  @Test fun clearingABoundBranchSendsNothing() {
    val sent = mutableListOf<DocOp>()
    val row = conditionalRow(7, "StyledText", "text", "Text", PropKind.Text, Conditional(Cond("open"), Bind("title"), "-")) { op, _, _ -> sent += op }
    val bound = row.querySelectorAll("input").item(0) as HTMLInputElement
    assertEquals("title", bound.value)
    bound.value = "  "
    bound.dispatchEvent(Event("input")); bound.dispatchEvent(Event("change")) /* E11: committed */
    assertTrue(sent.isEmpty(), sent.toString())
    bound.value = "name"
    bound.dispatchEvent(Event("input")); bound.dispatchEvent(Event("change")) /* E11: committed */
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

  /** E27: the test dropdown says what it means for the field's type; no free text, no Kotlin. */
  @Test fun aConditionIsAFieldAndATestInWords() {
    assertEquals(listOf("is true", "is false"), testChoices("Boolean").map { it.label })
    assertEquals(listOf("is empty", "is not empty", "is blank", "is not blank"), testChoices("String").map { it.label })
    assertEquals(listOf("is empty", "is not empty"), testChoices("List<Deal>").map { it.label })
    val contract = Contract(fields = mapOf("loading" to "Boolean", "note" to "String", "deals" to "List<Deal>", "count" to "Int"))
    assertEquals(listOf("deals", "loading", "note"), conditionFields(contract).map { it.first }) // an Int is not a condition

    val changes = mutableListOf<Triple<String, CondTest, Boolean>>()
    val ed = conditionEditor(contract, "loading", CondTest.Bool, negate = true, newFieldSlot = null) { f, t, n -> changes += Triple(f, t, n) }
    val (fieldSel, testSel) = ed.querySelectorAll("select").let { it.item(0) as org.w3c.dom.HTMLSelectElement to it.item(1) as org.w3c.dom.HTMLSelectElement }
    assertEquals("loading", fieldSel.value)
    assertEquals("is false", testSel.options.item(testSel.selectedIndex)?.textContent)
    assertTrue(changes.isEmpty())
    // A String field: its tests replace the Boolean ones.
    fieldSel.value = "note"
    fieldSel.dispatchEvent(Event("change"))
    assertEquals(Triple("note", CondTest.Empty, false), changes.last())
    testSel.value = "3" // is not blank
    testSel.dispatchEvent(Event("change"))
    assertEquals(Triple("note", CondTest.Blank, true), changes.last())
  }

  /** E27: "New field…" opens the E12 picker; nothing changes until a name is confirmed. */
  @Test fun aNewConditionFieldIsDeclaredOnlyOnConfirm() {
    val changes = mutableListOf<Triple<String, CondTest, Boolean>>()
    val slot = Ui.el("div")
    val ed = conditionEditor(Contract(fields = mapOf("loading" to "Boolean")), "loading", CondTest.Bool, false, newFieldSlot = slot) { f, t, n -> changes += Triple(f, t, n) }
    val fieldSel = ed.querySelector("select") as org.w3c.dom.HTMLSelectElement
    fieldSel.value = fieldSel.options.item(fieldSel.options.length - 1)!!.getAttribute("value")!!
    fieldSel.dispatchEvent(Event("change"))
    assertEquals("loading", fieldSel.value) // still the current field
    assertTrue(changes.isEmpty())
    assertTrue(slot.querySelector(".bind-picker") != null)
  }
}
