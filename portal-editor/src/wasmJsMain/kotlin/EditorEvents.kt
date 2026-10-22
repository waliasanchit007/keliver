/*
 * E28: wiring an event to one of the screen's actions — the functions its
 * Bindings interface declares — picked from a list that shows each one's
 * signature. A new action is declared only on an explicit confirm, which says
 * that it changes the contract (as E12 does for a field).
 */
import dev.keliver.portal.Action
import dev.keliver.portal.document.Contract
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.KeyboardEvent

private const val NO_ACTION = ""
private const val NEW_ACTION = "\u0000new"

/** How the panel shows action [name]: `order(String)`, `retry()`. */
internal fun actionSignature(contract: Contract, name: String): String =
  "$name(${contract.actionParams[name].orEmpty()})"

/** The contract's actions as a dropdown: none, each action by its signature, [current] even when undeclared, then "New action…" when [canDeclare]. */
internal fun actionSelect(contract: Contract, current: String?, canDeclare: Boolean): HTMLSelectElement {
  val sel = Ui.select()
  fun option(value: String, label: String) {
    val o = document.createElement("option") as HTMLOptionElement
    o.value = value
    o.textContent = label
    sel.appendChild(o)
  }
  option(NO_ACTION, "— no action —")
  contract.actions.sorted().forEach { option(it, actionSignature(contract, it)) }
  if (current != null && current !in contract.actions) option(current, "$current() · not declared")
  if (canDeclare) option(NEW_ACTION, "New action…")
  sel.value = current ?: NO_ACTION
  return sel
}

/** Why [name] cannot be declared as a new action of [contract], or null when it can. */
internal fun newActionProblem(contract: Contract, name: String): String? = when {
  name.isEmpty() -> "Type a name for the action"
  !Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name) -> "An action name is letters, digits and _ (not starting with a digit)"
  name in contract.actions -> "$name is already an action: choose it from the list"
  name in contract.fields -> "$name is already a field"
  else -> null
}

/**
 * E28: an event's editor. The action is chosen from [contract]'s actions; its
 * argument — `it` (the event's value) or `item.field` inside a Repeat — is a
 * field shown when the action takes one (or one is set), sent once
 * committed. "New action…" opens a name form in [newSlot]; only its confirm
 * calls [onSet] with a name the contract does not declare. [onSet] gets the
 * action name (null: no action) and its argument.
 */
internal fun eventControl(
  contract: Contract,
  current: Action?,
  canDeclare: Boolean,
  newSlot: HTMLElement,
  onSet: (name: String?, arg: String?) -> Unit,
): HTMLElement {
  val box = Ui.el("div", "event-ctl")
  val sel = actionSelect(contract, current?.name, canDeclare)
  sel.setAttribute("aria-label", "action")
  val arg = Ui.input()
  arg.setAttribute("type", "text")
  arg.setAttribute("spellcheck", "false")
  arg.setAttribute("placeholder", "argument: it, or item.field in a Repeat")
  arg.setAttribute("aria-label", "argument")
  arg.value = current?.arg ?: ""
  val err = Ui.el("div", "field-err")
  var name: String? = current?.name
  var committedArg = current?.arg ?: ""
  fun showArg() {
    val takes = name != null && (contract.actionParams[name] != null || arg.value.isNotEmpty())
    if (takes) arg.removeAttribute("hidden") else arg.setAttribute("hidden", "")
  }
  showArg()
  /** Sends [n] with the typed argument — unless [n] takes a parameter and none is given (`b.toggleHelp()` would not compile). */
  fun wire(n: String) {
    val a = arg.value.trim()
    val param = contract.actionParams[n]
    if (param != null && a.isEmpty()) {
      val article = if (param.first().lowercaseChar() in "aeiou") "an" else "a"
      markInvalid(arg, "$n takes $article $param: give it an argument (it, or item.field)", err)
      arg.focus()
      return
    }
    markInvalid(arg, null, err)
    // A declared action without a parameter takes no argument (`b.retry(x)` would not compile).
    val sent = if (n in contract.actions && param == null) "" else a
    if (sent.isEmpty()) arg.value = ""
    committedArg = sent
    showArg()
    onSet(n, sent.ifEmpty { null })
  }
  sel.addEventListener("change", { _ ->
    when (val v = sel.value) {
      NEW_ACTION -> {
        sel.value = name ?: NO_ACTION // until one is confirmed
        renderNewAction(newSlot, contract) { n ->
          name = n
          wire(n)
        }
      }
      NO_ACTION -> { name = null; markInvalid(arg, null, err); showArg(); onSet(null, null) }
      else -> { name = v; showArg(); wire(v) }
    }
  })
  arg.addEventListener("change", { _ ->
    val n = name ?: return@addEventListener
    if (arg.value.trim() == committedArg && !arg.classList.contains("invalid")) return@addEventListener
    wire(n)
  })
  commitKeys(arg) { arg.value = committedArg }
  box.appendChild(sel)
  box.appendChild(arg)
  box.appendChild(err)
  return box
}

/** The "New action…" form: a name, the member it adds, a note that this changes the contract, and a confirm. */
private fun renderNewAction(slot: HTMLElement, contract: Contract, onConfirm: (String) -> Unit) {
  Ui.clear(slot)
  val box = Ui.el("div", "card bind-picker")
  val name = Ui.input()
  name.setAttribute("type", "text")
  name.setAttribute("spellcheck", "false")
  name.setAttribute("aria-label", "new action name")
  name.setAttribute("placeholder", "action name")
  val preview = Ui.el("code", "bind-preview")
  val err = Ui.el("div", "field-err")
  val confirm = Ui.button("Add action and wire", "btn primary") {}
  fun check(): String? {
    val n = name.value.trim()
    val problem = newActionProblem(contract, n)
    preview.textContent = "+ fun ${n.ifEmpty { "…" }}()   (in the screen's Bindings)"
    markInvalid(name, if (n.isEmpty()) null else problem, err)
    if (problem == null) confirm.removeAttribute("disabled") else confirm.setAttribute("disabled", "")
    return if (problem == null) n else null
  }
  fun submit() {
    val n = check() ?: return
    Ui.clear(slot)
    onConfirm(n)
  }
  name.addEventListener("input", { _ -> check() })
  name.addEventListener("keydown", { ev ->
    val e = ev as KeyboardEvent
    if (e.key == "Enter") { e.preventDefault(); e.stopPropagation(); submit() }
    if (e.key == "Escape") { e.preventDefault(); e.stopPropagation(); Ui.clear(slot) }
  })
  confirm.addEventListener("click", { _ -> submit() })
  box.appendChild(Ui.el("div", "muted", "New action"))
  box.appendChild(name)
  box.appendChild(preview)
  box.appendChild(Ui.el("div", "muted", "This changes the contract: the app must implement the new function."))
  box.appendChild(err)
  val actions = Ui.el("div", "row")
  actions.appendChild(confirm)
  actions.appendChild(Ui.button("Cancel", "btn") { Ui.clear(slot) })
  box.appendChild(actions)
  slot.appendChild(box)
  check()
  name.focus()
}
