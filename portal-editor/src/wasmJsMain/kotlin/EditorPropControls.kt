/*
 * The properties panel's typed value controls, shared by plain props, modifier
 * args and conditional branches. Each takes the current value and reports a
 * committed new value through a callback, so it can be tested without a relay.
 */
import dev.keliver.portal.PropChoice
import kotlinx.browser.document
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement

/**
 * E8: an Int-coded prop's dropdown — one option per named code. A value the
 * schema does not name (written in code, or bound elsewhere) stays shown and
 * selected as "Other (n)" instead of silently becoming the first choice.
 */
internal fun choiceSelect(choices: List<PropChoice>, current: Int?, onPick: (Int) -> Unit): HTMLSelectElement {
  val sel = Ui.select()
  fun option(value: Int, label: String) {
    val o = document.createElement("option") as HTMLOptionElement
    o.value = value.toString()
    o.textContent = label
    sel.appendChild(o)
  }
  choices.forEach { option(it.value, it.label) }
  if (current != null && choices.none { it.value == current }) option(current, "Other ($current)")
  sel.value = (current ?: choices.firstOrNull()?.value ?: 0).toString()
  sel.setAttribute("title", choices.joinToString(", ") { "${it.value} ${it.label}" })
  sel.addEventListener("change", { _ -> sel.value.toIntOrNull()?.let(onPick) })
  return sel
}
