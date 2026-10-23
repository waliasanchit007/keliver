import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.CondTest
import dev.keliver.portal.Conditional
import dev.keliver.portal.PropKind
import dev.keliver.portal.Token
import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.toPropValue
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLOptionElement

// K3: the properties panel for a conditional value — `if <condition>: [when
// true] else [when false]` — with the condition and both branches editable.

/** How the panel names a test (E27: words, not Kotlin). */
internal fun testLabel(t: CondTest): String = when (t) {
  CondTest.Bool -> "is true"
  CondTest.Empty -> "is empty"
  CondTest.Blank -> "is blank"
  CondTest.Null -> "is missing"
  CondTest.Eq -> "equals a value"
}

/**
 * K6: whether the panel shows [c] read-only — an equality or a compound
 * condition (several terms joined by `&&` or `||`) is edited in code; its
 * branches stay editable.
 */
internal fun isReadOnlyCondition(c: dev.keliver.portal.Cond): Boolean = c.isExtended

/** K6: [c] as the screen's source spells it (`b.kind == "card" && b.open`), for a read-only condition. */
internal fun conditionSource(c: dev.keliver.portal.Cond): String = c.kotlin { if ('.' in it) it else "b.$it" }

/** [cur] as a document value with its true ([whenTrue]) or false branch replaced by [value]. */
internal fun withBranch(cur: Conditional, whenTrue: Boolean, value: PropValue?): PropValue.Conditional =
  withBranch(cur.toPropValue(), whenTrue, value)

internal fun withBranch(cur: PropValue.Conditional, whenTrue: Boolean, value: PropValue?): PropValue.Conditional =
  if (whenTrue) cur.copy(whenTrue = value) else cur.copy(whenFalse = value)

/** [cur] as a document value with a new condition. */
internal fun withCondition(cur: Conditional, field: String, test: CondTest, negate: Boolean): PropValue.Conditional =
  withCondition(cur.toPropValue(), field, test, negate)

internal fun withCondition(cur: PropValue.Conditional, field: String, test: CondTest, negate: Boolean): PropValue.Conditional =
  cur.copy(field = field, test = test.wire, negate = negate)

/**
 * The value a conditional panel edits: the one it was rendered with, then
 * each value it sent. The panel is not re-rendered after its own edits
 * (that would steal the input's focus), so every op builds on the previous
 * one — editing "then" and then "else" keeps both. An op the relay refuses
 * is not built on: the value returns to the last one the relay accepted (a
 * refusal also drops every op queued behind it).
 */
private class Latest(cur: Conditional) {
  private var accepted: PropValue.Conditional = cur.toPropValue()

  var value: PropValue.Conditional = accepted
    private set

  /** Sends [next] through [op], which reports whether the relay accepted it. */
  fun send(next: PropValue.Conditional, op: (PropValue.Conditional, (Boolean) -> Unit) -> Unit) {
    value = next
    op(next) { ok -> if (ok) accepted = next else value = accepted }
  }
}

/** The op setting wire key [key] (a prop, or "mod.X.p") of node [id] to [value]. */
internal fun valueOp(id: Int, key: String, value: PropValue): DocOp =
  if (key.startsWith("mod.")) DocOp.SetModifier(Handle(id.toLong()), key.removePrefix("mod."), value)
  else DocOp.SetProp(Handle(id.toLong()), key, value)

/**
 * A conditional VALUE prop: the condition (field, test, negation), then each
 * branch with an editor for the prop's [kind], and "keep true" to replace the
 * conditional by its true branch. [send] submits one op and later reports
 * whether the relay accepted it.
 */
internal fun conditionalRow(
  nodeId: Int,
  nodeType: String,
  key: String,
  label: String,
  kind: PropKind,
  cur: Conditional,
  send: (DocOp, Boolean, (Boolean) -> Unit) -> Unit,
): HTMLElement {
  val wrap = Ui.el("div", "card")
  val latest = Latest(cur)
  fun sendValue(v: PropValue.Conditional) = latest.send(v) { next, done -> send(valueOp(nodeId, key, next), false, done) }
  wrap.appendChild(conditionHeader(label, cur) { field, test, negate -> sendValue(withCondition(latest.value, field, test, negate)) })
  for (branch in listOf(true, false)) {
    val row = Ui.el("div", "row")
    row.appendChild(Ui.el("label", "", if (branch) "then" else "else"))
    val value = if (branch) cur.whenTrue else cur.whenFalse
    row.appendChild(branchEditor(nodeType, key, kind, value) { v -> sendValue(withBranch(latest.value, branch, v)) })
    wrap.appendChild(row)
  }
  if (cur.whenTrue != null) {
    wrap.appendChild(
      Ui.button("keep then", "btn") { send(valueOp(nodeId, key, latest.value.whenTrue ?: return@button), true) {} }
        .also { it.setAttribute("title", "replace the condition with its true branch") },
    )
  }
  return wrap
}

/**
 * A conditional EVENT: `if <condition>: [action] else [action]`, an empty
 * action name meaning `null` (no handler in that branch).
 */
internal fun conditionalEventRow(nodeId: Int, evName: String, cur: Conditional, send: (DocOp, Boolean, (Boolean) -> Unit) -> Unit): HTMLElement {
  val wrap = Ui.el("div", "card")
  val latest = Latest(cur)
  fun sendValue(v: PropValue.Conditional) = latest.send(v) { next, done -> send(valueOp(nodeId, evName, next), false, done) }
  wrap.appendChild(conditionHeader(evName, cur) { field, test, negate -> sendValue(withCondition(latest.value, field, test, negate)) })
  for (branch in listOf(true, false)) {
    val row = Ui.el("div", "row")
    row.appendChild(Ui.el("label", "", if (branch) "then" else "else"))
    val action = (if (branch) cur.whenTrue else cur.whenFalse) as? Action
    // E28: each branch's action from the contract's list; "no action" is `null` there.
    val sel = actionSelect(openContract(), action?.name, canDeclare = false)
    sel.setAttribute("aria-label", "${if (branch) "then" else "else"} action")
    sel.addEventListener("change", { _ ->
      val v = sel.value.takeIf { it.isNotEmpty() }?.let { PropValue.Action(it, action?.arg) }
      sendValue(withBranch(latest.value, branch, v))
    })
    row.appendChild(sel)
    wrap.appendChild(row)
  }
  return wrap
}

/** `label  if [field] [test]` — the condition's editor (K6: read-only text for an equality or compound condition). */
private fun conditionHeader(label: String, cur: Conditional, onChange: (String, CondTest, Boolean) -> Unit): HTMLElement {
  val row = Ui.el("div", "row")
  val lab = Ui.el("label", "", label)
  lab.setAttribute("title", "$label = if (${conditionSource(cur.cond)}) … else …")
  row.appendChild(lab)
  row.appendChild(Ui.el("span", "muted", "if"))
  if (isReadOnlyCondition(cur.cond)) {
    row.appendChild(
      Ui.el("code", "", conditionSource(cur.cond))
        .also { it.setAttribute("title", "an equality or compound condition is edited in code; each branch is edited here") },
    )
    return row
  }
  val wrap = Ui.el("div")
  wrap.appendChild(row)
  row.appendChild(conditionEditor(openContract(), cur.cond.field, cur.cond.test, cur.cond.negate, newFieldSlot = null, onChange = onChange))
  return wrap
}

// ---------------------------------------------------------------------------
// E27: a condition in plain words — "Show when [field] [is true]" — with the
// field chosen from the contract and the test (negation folded in) from a
// list that fits the field's type. No free text, no `isEmpty()`.

/** One entry of the condition's test dropdown: what it says, and the test and negation it means. */
internal data class TestChoice(val label: String, val test: CondTest, val negate: Boolean)

private val BOOL_CHOICES = listOf(TestChoice("is true", CondTest.Bool, false), TestChoice("is false", CondTest.Bool, true))
private val EMPTY_CHOICES = listOf(TestChoice("is empty", CondTest.Empty, false), TestChoice("is not empty", CondTest.Empty, true))
private val BLANK_CHOICES = listOf(TestChoice("is blank", CondTest.Blank, false), TestChoice("is not blank", CondTest.Blank, true))

/**
 * The tests a field of Kotlin type [type] can take: a Boolean is true or false,
 * a String empty or blank (or not), a List empty (or not). An unknown type
 * (a field the contract does not list) is offered all of them.
 */
internal fun testChoices(type: String?): List<TestChoice> = when {
  type == "Boolean" -> BOOL_CHOICES
  type == "String" -> EMPTY_CHOICES + BLANK_CHOICES
  type != null && isListType(type) -> EMPTY_CHOICES
  else -> BOOL_CHOICES + EMPTY_CHOICES + BLANK_CHOICES
}

private fun isListType(type: String) = type.startsWith("List<") || type.startsWith("Collection<")

/** The contract's fields a condition can test (Boolean, String, List), by name, with their types. */
internal fun conditionFields(contract: Contract): List<Pair<String, String>> =
  contract.fields.filter { (_, t) -> t == "Boolean" || t == "String" || isListType(t) }.toList().sortedBy { it.first }

/** The open document's contract (empty in a test or before the first document). */
internal var openContract: () -> Contract = { Contract() }

private const val NEW_FIELD = "\u0000new"

/**
 * The condition's two dropdowns: its field — the contract's testable fields
 * with their types, the current one even when the contract does not list it,
 * and, when [newFieldSlot] is given, "New field…" (the E12 picker, opened in
 * that slot: a field is declared only on its confirm) — and its test, the
 * [testChoices] of the field's type. [onChange] hears the field, test and
 * negation after each choice.
 */
internal fun conditionEditor(
  contract: Contract,
  field: String,
  test: CondTest,
  negate: Boolean,
  newFieldSlot: HTMLElement?,
  onChange: (String, CondTest, Boolean) -> Unit,
): HTMLElement {
  val box = Ui.el("span", "cond-editor")
  val fieldSel = Ui.select()
  fieldSel.setAttribute("aria-label", "field")
  val testSel = Ui.select()
  testSel.setAttribute("aria-label", "test")
  fun option(sel: org.w3c.dom.HTMLSelectElement, value: String, label: String) {
    val o = document.createElement("option") as HTMLOptionElement
    o.value = value
    o.textContent = label
    sel.appendChild(o)
  }
  val fields = conditionFields(contract)
  if (field.isEmpty()) option(fieldSel, "", "choose a field…")
  fields.forEach { (f, t) -> option(fieldSel, f, "$f · $t") }
  if (field.isNotEmpty() && fields.none { it.first == field }) option(fieldSel, field, if ('.' in field) field else "$field · not declared")
  if (newFieldSlot != null) option(fieldSel, NEW_FIELD, "New field…")
  fieldSel.value = field

  var current = field
  var choices = testChoices(contract.fields[field])
  fun fillTests(t: CondTest, n: Boolean) {
    Ui.clear(testSel)
    // A test the field's type does not list (an older edit) stays shown, never silently changed.
    if (choices.none { it.test == t && it.negate == n }) choices = choices + TestChoice((if (n) "not " else "") + testLabel(t), t, n)
    choices.forEachIndexed { i, c -> option(testSel, i.toString(), c.label) }
    testSel.value = choices.indexOfFirst { it.test == t && it.negate == n }.toString()
  }
  fillTests(test, negate)

  fun send() {
    if (current.isEmpty()) return
    val c = choices[testSel.value.toInt()]
    onChange(current, c.test, c.negate)
  }
  fieldSel.addEventListener("change", { _ ->
    val picked = fieldSel.value
    if (picked == NEW_FIELD && newFieldSlot != null) {
      fieldSel.value = current // until a field is confirmed
      renderBindPicker(newFieldSlot, contract, "Boolean", suggest = "show", current = null, owner = "the screen's Bindings", canDeclare = true) { f, _ ->
        current = f
        choices = BOOL_CHOICES
        onChange(f, CondTest.Bool, false)
      }
      return@addEventListener
    }
    current = picked
    val prev = choices.getOrNull(testSel.value.toInt())
    choices = testChoices(contract.fields[picked])
    // Keep the test when the new field's type has it; else its first.
    val keep = prev?.let { p -> choices.firstOrNull { it.test == p.test && it.negate == p.negate } } ?: choices.first()
    fillTests(keep.test, keep.negate)
    send()
  })
  testSel.addEventListener("change", { _ -> send() })
  box.appendChild(fieldSel)
  box.appendChild(testSel)
  return box
}

/** One branch's editor for a prop of [kind]: a token, a binding, or a literal. */
private fun branchEditor(nodeType: String, key: String, kind: PropKind, value: Any?, onChange: (PropValue?) -> Unit): HTMLElement {
  if (value is Token) {
    val box = Ui.el("span", "row")
    box.appendChild(Ui.el("span", "pill", "◆ ${value.ref}").also { it.setAttribute("title", "${value.ref} = ${value.value}") })
    box.appendChild(Ui.button("detach", "btn icon") { onChange(lit(value.value)) })
    return box
  }
  // K7: an arithmetic branch — read-only source, replaced whole by its value.
  if (isArithmeticValue(value)) return arithmeticEditor(value) { onChange(it) }
  // K4: a template branch — its text with {references}, validated before it is sent.
  if (value is dev.keliver.portal.Template) {
    return refValueEditor(
      templateSource(value), multiline = false,
      placeholder = "text with {field}, {◆C.Name}",
      parse = { parseTemplateSource(it, knownTokens(value)) },
    ) { v -> onChange(v) }.first
  }
  val input = Ui.input()
  if (value is Bind) {
    input.setAttribute("type", "text")
    input.setAttribute("style", "border-color:var(--accent); color:var(--accent);")
    input.value = value.field
    // E11: sent once committed. An empty field name is no binding (never `Bind("")`).
    input.addEventListener("change", { _ -> input.value.trim().takeIf { it.isNotEmpty() && it != value.field }?.let { onChange(PropValue.Bind(it)) } })
    commitKeys(input) { input.value = value.field }
    return input
  }
  when (kind) {
    PropKind.Text -> {
      input.setAttribute("type", "text")
      val text = value as? String ?: ""
      input.value = text
      // E11: sent once committed — Enter or leaving the field.
      input.addEventListener("change", { _ -> onChange(lit(input.value)) })
      commitKeys(input) { input.value = text }
    }
    // E11: validated as typed (K1: a Dp is never negative), sent once committed.
    PropKind.Int -> return numberField(value as? Int ?: 0, numberRule(nodeType, key, kind)) { onChange(lit(it.toInt())) }
    PropKind.Double -> return numberField(value as? Double ?: 0.0, numberRule(nodeType, key, kind)) { onChange(lit(it.toDouble())) }
    PropKind.Bool -> {
      input.setAttribute("type", "checkbox")
      input.className = ""
      if (value == true) input.setAttribute("checked", "checked")
      input.addEventListener("change", { _ -> onChange(lit(input.checked)) })
    }
    // E10: the same alpha-keeping colour editor as a plain colour prop.
    PropKind.Color -> return colorControl(value as? Int ?: 0) { onChange(lit(it)) }
    PropKind.IntList, PropKind.FloatList, PropKind.StringList -> {
      input.setAttribute("type", "text")
      input.setAttribute("disabled", "disabled")
      input.value = value?.toString() ?: "null"
    }
  }
  return input
}

/** G2: the field a new condition tests — the screen's first Boolean field by name — or null when it has none. */
internal fun newConditionField(contract: Contract): String? =
  contract.fields.filter { (_, t) -> t == "Boolean" }.keys.minOrNull()
