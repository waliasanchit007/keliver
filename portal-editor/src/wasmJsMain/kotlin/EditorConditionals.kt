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
import dev.keliver.portal.isDpProp
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
}

/** [cur] as a document value with its true ([whenTrue]) or false branch replaced by [value]. */
internal fun withBranch(cur: Conditional, whenTrue: Boolean, value: PropValue?): PropValue.Conditional {
  val pv = cur.toPropValue()
  return if (whenTrue) pv.copy(whenTrue = value) else pv.copy(whenFalse = value)
}

/** [cur] as a document value with a new condition. */
internal fun withCondition(cur: Conditional, field: String, test: CondTest, negate: Boolean): PropValue.Conditional =
  cur.toPropValue().copy(field = field, test = test.wire, negate = negate)

/** The op setting wire key [key] (a prop, or "mod.X.p") of node [id] to [value]. */
internal fun valueOp(id: Int, key: String, value: PropValue): DocOp =
  if (key.startsWith("mod.")) DocOp.SetModifier(Handle(id.toLong()), key.removePrefix("mod."), value)
  else DocOp.SetProp(Handle(id.toLong()), key, value)

/**
 * A conditional VALUE prop: the condition (field, test, negation), then each
 * branch with an editor for the prop's [kind], and "keep true" to replace the
 * conditional by its true branch. [send] submits one op.
 */
internal fun conditionalRow(
  nodeId: Int,
  nodeType: String,
  key: String,
  label: String,
  kind: PropKind,
  cur: Conditional,
  send: (DocOp, Boolean) -> Unit,
): HTMLElement {
  val wrap = Ui.el("div", "card")
  wrap.appendChild(conditionHeader(label, cur) { field, test, negate -> send(valueOp(nodeId, key, withCondition(cur, field, test, negate)), false) })
  for (branch in listOf(true, false)) {
    val row = Ui.el("div", "row")
    row.appendChild(Ui.el("label", "", if (branch) "then" else "else"))
    val value = if (branch) cur.whenTrue else cur.whenFalse
    row.appendChild(branchEditor(nodeType, key, kind, value) { v -> send(valueOp(nodeId, key, withBranch(cur, branch, v)), false) })
    wrap.appendChild(row)
  }
  if (cur.whenTrue != null) {
    wrap.appendChild(
      Ui.button("keep then", "btn") { send(valueOp(nodeId, key, cur.toPropValue().whenTrue ?: return@button), true) }
        .also { it.setAttribute("title", "replace the condition with its true branch") },
    )
  }
  return wrap
}

/**
 * A conditional EVENT: `if <condition>: [action] else [action]`, an empty
 * action name meaning `null` (no handler in that branch).
 */
internal fun conditionalEventRow(nodeId: Int, evName: String, cur: Conditional, send: (DocOp, Boolean) -> Unit): HTMLElement {
  val wrap = Ui.el("div", "card")
  wrap.appendChild(conditionHeader(evName, cur) { field, test, negate -> send(valueOp(nodeId, evName, withCondition(cur, field, test, negate)), false) })
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
      send(valueOp(nodeId, evName, withBranch(cur, branch, v)), false)
    })
    row.appendChild(input)
    wrap.appendChild(row)
  }
  return wrap
}

/** `label  if [field] [test] [not]` — the condition's editor. */
private fun conditionHeader(label: String, cur: Conditional, onChange: (String, CondTest, Boolean) -> Unit): HTMLElement {
  val row = Ui.el("div", "row")
  val lab = Ui.el("label", "", label)
  lab.setAttribute("title", "$label = if (${cur.cond.kotlin { "b.$it" }}) … else …")
  row.appendChild(lab)
  row.appendChild(Ui.el("span", "muted", "if"))
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
  val input = Ui.input()
  if (value is Bind) {
    input.setAttribute("type", "text")
    input.setAttribute("style", "border-color:var(--accent); color:var(--accent);")
    input.value = value.field
    input.addEventListener("input", { _ -> onChange(PropValue.Bind(input.value.trim())) })
    return input
  }
  when (kind) {
    PropKind.Text -> {
      input.setAttribute("type", "text")
      input.value = value as? String ?: ""
      input.addEventListener("input", { _ -> onChange(lit(input.value)) })
    }
    PropKind.Int -> {
      input.setAttribute("type", "number")
      input.value = (value as? Int ?: 0).toString()
      input.addEventListener("input", { _ -> onChange(lit(input.value.toIntOrNull() ?: 0)) })
    }
    PropKind.Double -> {
      input.setAttribute("type", "number")
      input.setAttribute("step", "0.1")
      val nonNegative = isDpProp(nodeType, key)
      if (nonNegative) input.setAttribute("min", "0")
      input.value = (value as? Double ?: 0.0).toString()
      input.addEventListener("input", { _ ->
        val v = input.value.toDoubleOrNull() ?: 0.0
        onChange(lit(if (nonNegative) v.coerceAtLeast(0.0) else v))
      })
    }
    PropKind.Bool -> {
      input.setAttribute("type", "checkbox")
      input.className = ""
      if (value == true) input.setAttribute("checked", "checked")
      input.addEventListener("change", { _ -> onChange(lit(input.checked)) })
    }
    PropKind.Color -> {
      input.setAttribute("type", "color")
      input.setAttribute("style", "padding:1px; height:26px;")
      val argb = value as? Int ?: 0xFF000000.toInt()
      input.value = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')
      input.addEventListener("input", { _ ->
        onChange(lit((0xFF shl 24) or (input.value.removePrefix("#").toIntOrNull(16) ?: 0)))
      })
    }
    PropKind.IntList, PropKind.FloatList, PropKind.StringList -> {
      input.setAttribute("type", "text")
      input.setAttribute("disabled", "disabled")
      input.value = value?.toString() ?: "null"
    }
  }
  return input
}
