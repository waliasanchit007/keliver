import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.CondTest
import dev.keliver.portal.Conditional
import dev.keliver.portal.PropKind
import dev.keliver.portal.Token
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

/** The tests the panel offers for a screen field (a screen contract cannot type a nullable field). */
internal val PANEL_TESTS: List<CondTest> = listOf(CondTest.Bool, CondTest.Empty, CondTest.Blank)

/** How the panel names a test. */
internal fun testLabel(t: CondTest): String = when (t) {
  CondTest.Bool -> "is true"
  CondTest.Empty -> "isEmpty()"
  CondTest.Blank -> "isBlank()"
  CondTest.Null -> "== null"
  CondTest.Eq -> "== value"
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
    val input = Ui.input()
    input.setAttribute("type", "text")
    input.setAttribute("placeholder", "action name (empty = null)")
    input.value = action?.name ?: ""
    input.addEventListener("input", { _ ->
      val name = input.value.trim()
      val v = if (name.isEmpty()) null else PropValue.Action(name, action?.arg)
      sendValue(withBranch(latest.value, branch, v))
    })
    row.appendChild(input)
    wrap.appendChild(row)
  }
  return wrap
}

/** `label  if [field] [test] [not]` — the condition's editor (K6: read-only text for an equality or compound condition). */
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
  val field = Ui.input()
  field.setAttribute("type", "text")
  field.setAttribute("placeholder", "field")
  field.value = cur.cond.field
  val test = Ui.select()
  val tests = if (cur.cond.test in PANEL_TESTS) PANEL_TESTS else PANEL_TESTS + cur.cond.test
  tests.forEach { t ->
    val o = document.createElement("option") as HTMLOptionElement
    o.value = t.name
    o.textContent = testLabel(t)
    if (t == cur.cond.test) o.selected = true
    test.appendChild(o)
  }
  val negate = Ui.input()
  negate.setAttribute("type", "checkbox")
  negate.className = ""
  negate.setAttribute("title", "not (negate the condition)")
  if (cur.cond.negate) negate.setAttribute("checked", "checked")
  fun changed() {
    val f = field.value.trim().ifEmpty { return }
    onChange(f, CondTest.valueOf(test.value), negate.checked)
  }
  field.addEventListener("input", { _ -> changed() })
  test.addEventListener("change", { _ -> changed() })
  negate.addEventListener("change", { _ -> changed() })
  row.appendChild(field)
  row.appendChild(test)
  row.appendChild(Ui.el("span", "muted", "not"))
  row.appendChild(negate)
  return row
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
