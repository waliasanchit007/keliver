/*
 * E12: binding a prop to a field of the screen's contract. `@` never changes
 * the contract by itself: it opens a picker of the fields the screen's
 * Bindings interface already declares with the prop's type, and declaring a
 * NEW field is a separate, explicit step that says what it adds to the file.
 */
import dev.keliver.portal.document.Contract
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** Kotlin's hard keywords: not a field name (`val in: Int` does not compile). */
private val KOTLIN_KEYWORDS = setOf(
  "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
  "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
)

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** The contract's fields a prop of Kotlin type [type] can be bound to, by name. */
internal fun bindableFields(contract: Contract, type: String): List<String> =
  contract.fields.filter { (_, t) -> t == type }.keys.sorted()

/**
 * Why [name] cannot be declared as a new `[type]` field of [contract], or null
 * when it can. A name the contract already declares with [type] is not new —
 * the picker binds it without declaring anything.
 */
internal fun newFieldProblem(contract: Contract, name: String, type: String): String? = when {
  contract.fields[name] == type -> null
  name.isEmpty() -> "Type a name for the field"
  !IDENTIFIER.matches(name) -> "A field name is letters, digits and _ (not starting with a digit)"
  name in KOTLIN_KEYWORDS -> "\"$name\" is a Kotlin keyword"
  contract.fields[name]?.let { it != type } == true -> "$name is already declared as ${contract.fields[name]}"
  contract.unlistedFieldsUnknown != null -> contract.unlistedFieldsUnknown
  else -> null
}

/** A field name made from a prop name: `"Font size"`/`fontSize` → `fontSize`. */
internal fun suggestedFieldName(prop: String): String {
  val words = prop.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
  if (words.isEmpty()) return "value"
  val name = words.first().replaceFirstChar { it.lowercase() } + words.drop(1).joinToString("") { w -> w.replaceFirstChar { it.uppercase() } }
  return if (name.first().isDigit()) "f$name" else name
}

/**
 * The binding picker, rendered into [picker] (cleared first): the [contract]'s
 * fields of [type] — choosing one calls [onPick] with `isNew = false` and adds
 * nothing to the contract — then, when [canDeclare], "New field…": a name
 * (suggested from [suggest]), the member it will add to the [owner] in the
 * file, and a confirm button; only that confirm calls [onPick] with
 * `isNew = true`. [current] is the field already bound, if any.
 */
internal fun renderBindPicker(
  picker: HTMLElement,
  contract: Contract,
  type: String,
  suggest: String,
  current: String?,
  owner: String,
  canDeclare: Boolean,
  onPick: (field: String, isNew: Boolean) -> Unit,
) {
  Ui.clear(picker)
  val box = Ui.el("div", "card bind-picker")
  val article = if (type.first().lowercaseChar() in "aeiou") "an" else "a"
  box.appendChild(Ui.el("div", "muted", "Bind to $article $type field of $owner"))
  val fields = bindableFields(contract, type)
  val list = Ui.el("div", "bind-list")
  if (fields.isEmpty()) list.appendChild(Ui.el("div", "muted", "No $type field yet"))
  fields.forEach { f ->
    val row = Ui.el("div", "pal-row" + if (f == current) " selected" else "")
    row.setAttribute("role", "button")
    row.setAttribute("tabindex", "0")
    row.setAttribute("data-field", f)
    row.appendChild(Ui.el("span", "", "@ $f"))
    row.appendChild(Ui.el("span", "cat", type))
    row.addEventListener("click", { _ -> Ui.clear(picker); onPick(f, false) })
    row.addEventListener("keydown", { e -> if ((e as KeyboardEvent).key == "Enter") { Ui.clear(picker); onPick(f, false) } })
    list.appendChild(row)
  }
  val others = contract.fields.size - fields.size
  if (others > 0) list.appendChild(Ui.el("div", "muted", "$others other field${if (others == 1) "" else "s"} of other types"))
  box.appendChild(list)

  if (!canDeclare) {
    box.appendChild(Ui.el("div", "muted", "A new value is a parameter added to $owner in code."))
    picker.appendChild(box)
    return
  }
  val newBtn = Ui.button("New field…", "btn") {}
  val form = Ui.el("div", "bind-new")
  form.setAttribute("hidden", "")
  val name = Ui.input()
  name.setAttribute("type", "text")
  name.setAttribute("spellcheck", "false")
  name.setAttribute("aria-label", "new field name")
  var start = suggestedFieldName(suggest)
  var n = 2
  while (contract.fields.containsKey(start) && contract.fields[start] != type) start = suggestedFieldName(suggest) + n++
  name.value = start
  val preview = Ui.el("code", "bind-preview")
  val warn = Ui.el("div", "muted", "This changes the contract: the app must implement the new field.")
  val err = Ui.el("div", "field-err")
  val confirm = Ui.button("Add field and bind", "btn primary") {}
  fun check(): String? {
    val f = name.value.trim()
    val problem = newFieldProblem(contract, f, type)
    preview.textContent = if (contract.fields[f] == type) "$f is already declared — binds it, adds nothing" else "+ val $f: $type   (in $owner)"
    markInvalid(name, problem, err)
    if (problem == null) confirm.removeAttribute("disabled") else confirm.setAttribute("disabled", "")
    return if (problem == null) f else null
  }
  fun submit() {
    val f = check() ?: return
    Ui.clear(picker)
    onPick(f, contract.fields[f] != type)
  }
  name.addEventListener("input", { _ -> check() })
  name.addEventListener("keydown", { ev ->
    val e = ev as KeyboardEvent
    if (e.key == "Enter") { e.preventDefault(); e.stopPropagation(); submit() }
    if (e.key == "Escape") { e.preventDefault(); e.stopPropagation(); Ui.clear(picker) }
  })
  confirm.addEventListener("click", { _ -> submit() })
  newBtn.addEventListener("click", { _ ->
    newBtn.setAttribute("hidden", "")
    form.removeAttribute("hidden")
    check()
    name.focus()
    name.select()
  })
  form.appendChild(name)
  form.appendChild(preview)
  form.appendChild(warn)
  form.appendChild(err)
  val actions = Ui.el("div", "row")
  actions.appendChild(confirm)
  actions.appendChild(Ui.button("Cancel", "btn") { Ui.clear(picker) })
  form.appendChild(actions)
  box.appendChild(newBtn)
  box.appendChild(form)
  picker.appendChild(box)
}
