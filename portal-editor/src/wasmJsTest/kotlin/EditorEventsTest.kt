import dev.keliver.portal.Action
import dev.keliver.portal.document.Contract
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** E28: an event is wired to an action picked from the contract. */
class EditorEventsTest {
  private val contract = Contract(actions = listOf("retry", "invest", "toggleFaq"), actionParams = mapOf("invest" to "String", "toggleFaq" to "Int"))

  private fun HTMLSelectElement.labels() = (0 until options.length).map { (options.item(it) as HTMLOptionElement).textContent }

  @Test fun theActionsAreListedWithTheirSignatures() {
    val sel = actionSelect(contract, "retry", canDeclare = true)
    assertEquals(listOf("— no action —", "invest(String)", "retry()", "toggleFaq(Int)", "New action…"), sel.labels())
    assertEquals("retry", sel.value)
    // An action the contract does not declare stays shown.
    assertEquals("old() · not declared", actionSelect(contract, "old", canDeclare = false).labels().last())
  }

  @Test fun pickingAnActionWiresItAndAnArgumentIsSentOnceCommitted() {
    val sets = mutableListOf<Pair<String?, String?>>()
    val ctl = eventControl(contract, Action("retry"), canDeclare = true, newSlot = Ui.el("div")) { n, a -> sets += n to a }
    val sel = ctl.querySelector("select") as HTMLSelectElement
    val arg = ctl.querySelector("input") as HTMLInputElement
    assertTrue(arg.hasAttribute("hidden")) // retry() takes none
    sel.value = "invest"
    sel.dispatchEvent(Event("change"))
    // invest(String) takes one: nothing is wired without it (`b.invest()` would not compile).
    assertTrue(sets.isEmpty())
    assertTrue(!arg.hasAttribute("hidden"))
    assertTrue(arg.classList.contains("invalid"))
    assertTrue("invest takes a String" in (ctl.querySelector(".field-err")?.textContent ?: ""))
    arg.value = "item.isin"
    arg.dispatchEvent(Event("input"))
    assertTrue(sets.isEmpty()) // typing sends nothing
    arg.dispatchEvent(Event("change"))
    assertEquals(listOf<Pair<String?, String?>>("invest" to "item.isin"), sets)
    sel.value = "retry"
    sel.dispatchEvent(Event("change"))
    assertEquals("retry" to null, sets.last()) // retry() takes no argument: it is dropped
    assertTrue(arg.hasAttribute("hidden"))
    sel.value = ""
    sel.dispatchEvent(Event("change"))
    assertEquals(null to null, sets.last())
  }

  @Test fun aNewActionIsDeclaredOnlyOnConfirm() {
    val sets = mutableListOf<Pair<String?, String?>>()
    val slot = Ui.el("div")
    val ctl = eventControl(contract, null, canDeclare = true, newSlot = slot) { n, a -> sets += n to a }
    val sel = ctl.querySelector("select") as HTMLSelectElement
    sel.value = sel.options.item(sel.options.length - 1)!!.getAttribute("value")!!
    sel.dispatchEvent(Event("change"))
    assertEquals("", sel.value) // still "no action" until confirmed
    assertTrue(sets.isEmpty())
    val name = slot.querySelector("input") as HTMLInputElement
    val buttons = slot.querySelectorAll("button")
    val confirm = (0 until buttons.length).map { buttons.item(it) as HTMLButtonElement }.first { it.textContent == "Add action and wire" }
    name.value = "retry"
    name.dispatchEvent(Event("input"))
    assertTrue(confirm.hasAttribute("disabled")) // already an action
    name.value = "notifyMe"
    name.dispatchEvent(Event("input"))
    assertTrue("+ fun notifyMe()" in (slot.textContent ?: ""))
    assertTrue("changes the contract" in (slot.textContent ?: ""))
    confirm.click()
    assertEquals(listOf<Pair<String?, String?>>("notifyMe" to null), sets)
  }
}
