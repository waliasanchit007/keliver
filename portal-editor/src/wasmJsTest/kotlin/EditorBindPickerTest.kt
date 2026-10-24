import dev.keliver.portal.document.Contract
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** E12: `@` binds to a field the contract declares; a new field is declared only on confirm. */
class EditorBindPickerTest {
  private val contract = Contract(fields = mapOf("title" to "String", "subtitle" to "String", "count" to "Int", "open" to "Boolean"))

  private fun picker(c: Contract = contract, type: String = "String", canDeclare: Boolean = true, picks: MutableList<Pair<String, Boolean>>): HTMLElement {
    val host = Ui.el("div")
    renderBindPicker(host, c, type, suggest = "Font size", current = null, owner = "the screen's Bindings", canDeclare = canDeclare) { f, isNew -> picks += f to isNew }
    return host
  }

  @Test fun onlyFieldsOfThePropsTypeAreOffered() {
    assertEquals(listOf("subtitle", "title"), bindableFields(contract, "String"))
    assertEquals(listOf("count"), bindableFields(contract, "Int"))
    val picks = mutableListOf<Pair<String, Boolean>>()
    val host = picker(picks = picks)
    val rows = host.querySelectorAll("[data-field]")
    assertEquals(listOf("subtitle", "title"), (0 until rows.length).map { (rows.item(it) as HTMLElement).getAttribute("data-field") })
    assertTrue("2 other fields of other types" in (host.textContent ?: ""))
    assertTrue(picks.isEmpty()) // opening the picker writes nothing
  }

  @Test fun choosingAnExistingFieldDeclaresNothing() {
    val picks = mutableListOf<Pair<String, Boolean>>()
    val host = picker(picks = picks)
    (host.querySelector("[data-field=title]") as HTMLElement).click()
    assertEquals(listOf("title" to false), picks)
    assertEquals(0, host.childElementCount) // closed
  }

  @Test fun aNewFieldNeedsANameAndAnExplicitConfirm() {
    val picks = mutableListOf<Pair<String, Boolean>>()
    val host = picker(picks = picks)
    val buttons = host.querySelectorAll("button")
    val newBtn = (0 until buttons.length).map { buttons.item(it) as HTMLButtonElement }.first { it.textContent == "New field…" }
    newBtn.click()
    val name = host.querySelector(".bind-new input") as HTMLInputElement
    assertEquals("fontSize", name.value) // suggested from the prop
    assertTrue("+ val fontSize: String" in (host.querySelector(".bind-preview")?.textContent ?: ""))
    assertTrue("changes the contract" in (host.textContent ?: ""))
    assertTrue(picks.isEmpty())
    name.value = "2x"
    name.dispatchEvent(Event("input"))
    val confirm = (0 until buttons.length).map { buttons.item(it) as HTMLButtonElement }.first { it.textContent == "Add field and bind" }
    assertTrue(confirm.hasAttribute("disabled"))
    confirm.click()
    assertTrue(picks.isEmpty())
    name.value = "headline"
    name.dispatchEvent(Event("input"))
    confirm.click()
    assertEquals(listOf("headline" to true), picks)
  }

  @Test fun aComponentsParametersAreOfferedButNotDeclaredHere() {
    val picks = mutableListOf<Pair<String, Boolean>>()
    val host = picker(canDeclare = false, picks = picks)
    val buttons = host.querySelectorAll("button")
    assertTrue((0 until buttons.length).none { buttons.item(it)!!.textContent == "New field…" })
  }

  @Test fun newFieldNamesAreChecked() {
    assertNull(newFieldProblem(contract, "headline", "String"))
    assertNull(newFieldProblem(contract, "title", "String")) // already declared with this type: just binds
    assertEquals("title is already declared as String", newFieldProblem(contract, "title", "Int"))
    assertEquals("\"in\" is a Kotlin keyword", newFieldProblem(contract, "in", "Int"))
    assertTrue(newFieldProblem(contract, "a-b", "Int") != null)
    assertTrue(newFieldProblem(contract, "", "Int") != null)
    // A screen whose contract the file does not declare whole cannot gain a field here.
    assertEquals("why", newFieldProblem(contract.copy(unlistedFieldsUnknown = "why"), "headline", "String"))
    assertEquals("fontSize", suggestedFieldName("Font size"))
    assertEquals("colorArgb", suggestedFieldName("colorArgb"))
    assertEquals("value", suggestedFieldName("··"))
  }
}
