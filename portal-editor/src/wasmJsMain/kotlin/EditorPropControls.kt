/*
 * The properties panel's typed value controls, shared by plain props, modifier
 * args and conditional branches. Each takes the current value and reports a
 * committed new value through a callback, so it can be tested without a relay.
 */
import dev.keliver.portal.PropChoice
import dev.keliver.portal.PropKind
import dev.keliver.portal.isDpProp
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent

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

/** E10: an ARGB Int as `#AARRGGBB` — the byte order of the source's `0xAARRGGBB` literal. */
internal fun argbHex(argb: Int): String = "#" + (argb.toLong() and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')

/** E10: the CSS colour of an ARGB Int, alpha included. */
internal fun argbCss(argb: Int): String {
  val a = (argb ushr 24) and 0xFF
  val r = (argb ushr 16) and 0xFF
  val g = (argb ushr 8) and 0xFF
  val b = argb and 0xFF
  return "rgba($r, $g, $b, ${(a / 255.0).toString().take(5)})"
}

/** E10: the RGB part of an ARGB Int as `#rrggbb` — what `<input type=color>` holds (it has no alpha). */
internal fun argbRgbHex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')

/**
 * E10: [text] typed as a colour, or null when it is not one: `#AARRGGBB` (the
 * source's byte order) sets every byte; `#RRGGBB` and `#RGB` set the colour and
 * keep [current]'s alpha (opaque when [current] is fully transparent, so a
 * colour typed over the 0 "default" is visible). `#` is optional.
 */
internal fun parseArgbHex(text: String, current: Int): Int? {
  val h = text.trim().removePrefix("#")
  if (h.isEmpty() || h.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
  val keptAlpha = ((current ushr 24) and 0xFF).takeIf { it != 0 } ?: 0xFF
  return when (h.length) {
    8 -> h.toLong(16).toInt()
    6 -> (keptAlpha shl 24) or h.toInt(16)
    3 -> (keptAlpha shl 24) or h.map { "$it$it" }.joinToString("").toInt(16)
    else -> null
  }
}

/** E10: [argb] with its alpha byte set from an opacity [percent] (0..100). */
internal fun withOpacity(argb: Int, percent: Int): Int =
  (((percent.coerceIn(0, 100) * 255 + 50) / 100) shl 24) or (argb and 0xFFFFFF)

/** E10: [argb] with its colour from [rgb] (the system picker's `#rrggbb`) and its alpha kept — opaque over a fully transparent value. */
internal fun withRgb(argb: Int, rgb: Int): Int {
  val alpha = (argb ushr 24).takeIf { it != 0 } ?: 0xFF
  return (alpha shl 24) or (rgb and 0xFFFFFF)
}

/** E10: [argb]'s alpha as an opacity percent (0..100). */
internal fun opacityPercent(argb: Int): Int = (((argb ushr 24) and 0xFF) * 100 + 127) / 255

/** E10: a small colour swatch that shows alpha over a checkerboard. */
internal fun colorSwatch(argb: Int, size: Int = 14): HTMLElement {
  val sw = Ui.el("span", "swatch")
  sw.setAttribute(
    "style",
    "width:${size}px; height:${size}px; background:linear-gradient(${argbCss(argb)}, ${argbCss(argb)}), " +
      "repeating-conic-gradient(#bbb 0 25%, #fff 0 50%) 0 0 / 8px 8px;",
  )
  sw.setAttribute("title", argbHex(argb))
  return sw
}

/**
 * E10: a colour value's editor — a swatch that opens the system picker, the
 * hex with alpha (`#AARRGGBB`), and an opacity percent. Every path keeps the
 * alpha byte: the system picker has no alpha, so it changes only the colour.
 * A typed value commits on Enter or blur (Escape reverts); one that is not a
 * colour is not sent and marks the field invalid.
 */
internal fun colorControl(argb: Int, onCommit: (Int) -> Unit): HTMLElement {
  var current = argb
  val box = Ui.el("span", "color-ctl")
  val swatchWrap = Ui.el("span", "swatch-btn")
  swatchWrap.setAttribute("title", "pick a colour (keeps the opacity)")
  val swatch = colorSwatch(current, 18)
  val picker = Ui.input()
  picker.setAttribute("type", "color")
  picker.className = "swatch-picker"
  picker.value = argbRgbHex(current)
  swatchWrap.appendChild(swatch)
  swatchWrap.appendChild(picker)
  swatchWrap.setAttribute("role", "button")
  swatchWrap.setAttribute("aria-label", "pick a colour")
  swatchWrap.setAttribute("tabindex", "0")
  swatchWrap.addEventListener("click", { e -> if (e.target != picker) picker.click() })
  swatchWrap.addEventListener("keydown", { e -> if ((e as KeyboardEvent).key == "Enter" || e.key == " ") { e.preventDefault(); picker.click() } })
  val hex = Ui.input()
  hex.setAttribute("type", "text")
  hex.setAttribute("spellcheck", "false")
  hex.className = "input hex"
  hex.value = argbHex(current)
  hex.setAttribute("title", "#AARRGGBB, as the source's 0xAARRGGBB; #RRGGBB keeps the opacity")
  val alpha = Ui.input()
  alpha.setAttribute("type", "number")
  alpha.setAttribute("min", "0")
  alpha.setAttribute("max", "100")
  alpha.className = "input alpha"
  alpha.value = opacityPercent(current).toString()
  alpha.setAttribute("title", "opacity %")
  val pct = Ui.el("span", "muted unit", "%")
  val err = Ui.el("div", "field-err")

  fun show(v: Int) {
    current = v
    swatch.setAttribute("style", colorSwatch(v, 18).getAttribute("style") ?: "")
    swatch.setAttribute("title", argbHex(v))
    hex.value = argbHex(v)
    alpha.value = opacityPercent(v).toString()
    picker.value = argbRgbHex(v)
    markInvalid(hex, null, err)
    markInvalid(alpha, null, err)
  }
  fun commit(v: Int) {
    if (v == current) { show(v); return }
    show(v)
    onCommit(v)
  }
  picker.addEventListener("change", { _ -> picker.value.removePrefix("#").toIntOrNull(16)?.let { commit(withRgb(current, it)) } })
  fun commitHex() {
    clearPending(hex)
    val v = parseArgbHex(hex.value, current)
    if (v == null) markInvalid(hex, "Not a colour: type #AARRGGBB, #RRGGBB or #RGB", err) else commit(v)
  }
  hex.addEventListener("input", { _ -> markPending(hex, ::commitHex) })
  hex.addEventListener("change", { _ -> commitHex() })
  commitKeys(hex) { clearPending(hex); show(current) }
  fun commitAlpha() {
    clearPending(alpha)
    val p = alpha.value.trim().toIntOrNull()
    if (p == null || p !in 0..100) markInvalid(alpha, "Opacity is a whole percent from 0 to 100", err) else commit(withOpacity(current, p))
  }
  alpha.addEventListener("input", { _ -> markPending(alpha, ::commitAlpha) })
  alpha.addEventListener("change", { _ -> commitAlpha() })
  commitKeys(alpha) { clearPending(alpha); show(current) }
  box.appendChild(swatchWrap)
  box.appendChild(hex)
  box.appendChild(alpha)
  box.appendChild(pct)
  val wrap = Ui.el("div", "ctl-wrap")
  wrap.appendChild(box)
  wrap.appendChild(err)
  return wrap
}

/**
 * A typed field's own keys: Enter commits (by leaving the field, which fires
 * its `change`), Escape [revert]s and leaves it. Both are the field's — they
 * never reach the editor's shortcuts (Enter would open a component, Escape
 * deselect). With [newlineOnShiftEnter], Shift+Enter types a line break.
 */
internal fun commitKeys(field: HTMLElement, newlineOnShiftEnter: Boolean = false, revert: () -> Unit) {
  field.addEventListener("keydown", { ev ->
    val e = ev as KeyboardEvent
    when {
      e.key == "Enter" && !(newlineOnShiftEnter && e.shiftKey) && !e.isComposing -> {
        e.preventDefault()
        e.stopPropagation()
        field.blur()
      }
      e.key == "Escape" -> {
        e.preventDefault()
        e.stopPropagation()
        revert()
        field.blur()
      }
    }
  })
}

private var fieldKeys = 0

/** A stable per-field key, so one message line can serve several fields. */
private fun fieldKey(input: HTMLElement): String =
  input.getAttribute("data-field") ?: "f${++fieldKeys}".also { input.setAttribute("data-field", it) }

/**
 * Marks [input] invalid with [why], shown inline in [message] — or valid again
 * when [why] is null. Nothing is sent while a field is invalid.
 */
internal fun markInvalid(input: HTMLElement, why: String?, message: HTMLElement) {
  if (why == null) {
    input.classList.remove("invalid")
    input.removeAttribute("aria-invalid")
    if (message.getAttribute("data-for") == fieldKey(input)) message.textContent = ""
  } else {
    input.classList.add("invalid")
    input.setAttribute("aria-invalid", "true")
    message.setAttribute("data-for", fieldKey(input))
    message.textContent = why
  }
}

/**
 * One property row. The label and the row's action buttons (◆ @ {} detach)
 * share the first line; the control takes the panel's full width on the line
 * below, so a narrow panel never squeezes it. A [Layout.Compact] row (a
 * number, a dropdown) sits on one line — label, control, actions — when the
 * panel is wide enough (a CSS container query on the right pane); an
 * [Layout.Inline] row (a checkbox) always does.
 */
/** F15: a prop the call does not pass — its control would otherwise show `false`, `0` or black. */
internal fun isUnset(props: Map<String, Any?>, key: String, kind: PropKind): Boolean =
  kind in setOf(PropKind.Bool, PropKind.Color, PropKind.Int, PropKind.Double) && props[key] == null && key !in props

/**
 * F15: shows [row] as not set — the widget's own default applies, which the editor does not
 * know: a "default" tag on the label, a dimmed control, a checkbox neither on nor off and an
 * empty number field. Setting it writes the argument.
 */
internal fun markUnset(row: HTMLElement) {
  val prop = (if (row.classList.contains("prop")) row else row.querySelector(".prop") as? HTMLElement) ?: return
  prop.classList.add("unset")
  prop.setAttribute("title", "Not set: the widget's own default applies")
  (prop.querySelector("input[type=checkbox]") as? HTMLInputElement)?.indeterminate = true
  val inputs = prop.querySelectorAll("input:not([type=checkbox]):not([type=color])")
  for (i in 0 until inputs.length) (inputs.item(i) as? HTMLInputElement)?.let { it.value = ""; it.placeholder = "default" }
  // A dropdown keeps showing its first name: a coded enum's default (Start, Wrap, Clip).
}

internal class PropRow(label: String, layout: Layout = Layout.Stacked) {
  enum class Layout(val css: String) { Stacked("prop"), Compact("prop compact"), Inline("prop inline") }

  val root: HTMLElement = Ui.el("div", layout.css)
  private val lab = Ui.el("label", "prop-label", label)
  private val ctl = Ui.el("div", "prop-ctl")
  private val actions = Ui.el("div", "prop-actions")

  init {
    lab.setAttribute("title", label)
    root.appendChild(lab)
    root.appendChild(ctl)
    root.appendChild(actions)
  }

  /**
   * Adds the row's control. Its first field is the label's (`for`): screen
   * readers name it, and a click on the label focuses it — a dropdown without
   * opening its list, so the keyboard picks a value.
   */
  fun control(e: HTMLElement): PropRow = apply {
    ctl.appendChild(e)
    if (lab.getAttribute("for") == null) {
      val field = if (e.matches(FIELD)) e else e.querySelector(FIELD) as? HTMLElement
      if (field != null) {
        val id = field.id.ifEmpty { "prop-field-${++propFieldIds}".also { field.id = it } }
        lab.setAttribute("for", id)
      }
    }
  }

  fun action(e: HTMLElement?): PropRow = apply { if (e != null) actions.appendChild(e) }
}

private const val FIELD = "input:not([type=color]):not([hidden]), select, textarea"
private var propFieldIds = 0

/** The field holding a typed edit not yet committed, and how to commit it. */
private var pendingField: Pair<HTMLElement, () -> Unit>? = null

/**
 * Commits the typed edit still in a field, if any. The properties panel calls
 * it before it re-renders: a field removed while focused never fires `change`
 * or `blur`, so its edit would otherwise be lost.
 */
internal fun flushPendingField() {
  val p = pendingField ?: return
  pendingField = null
  p.second()
}

internal fun markPending(field: HTMLElement, commit: () -> Unit) { pendingField = field to commit }

internal fun clearPending(field: HTMLElement) { if (pendingField?.first == field) pendingField = null }

/** Grows [area] to its content (up to [maxPx]); a textarea needs to be in the document to be measured. */
internal fun fitToContent(area: HTMLTextAreaElement, maxPx: Int = 240) {
  area.style.height = "auto"
  val h = area.scrollHeight
  if (h > 0) area.style.height = "${minOf(h + 2, maxPx)}px"
}

/**
 * E9/E11: a text prop's field — full width, multi-line, growing with its text.
 * Typing [onPreview]s each keystroke (the canvas follows; nothing is written);
 * the edit is committed once — [onCommit] — on Enter or leaving the field, so
 * one edit is one write and one undo step. Shift+Enter types a line break;
 * Escape puts the committed text back.
 */
internal fun textField(value: String, onPreview: (String) -> Unit = {}, onCommit: (String) -> Unit): HTMLTextAreaElement {
  var committed = value
  val area = Ui.textarea("input grow")
  area.rows = 1
  area.value = value
  area.setAttribute("spellcheck", "false")
  area.setAttribute("title", "Enter to apply · Shift+Enter for a new line · Esc to cancel")
  fun commit() {
    clearPending(area)
    if (area.value == committed) return
    committed = area.value
    onCommit(committed)
  }
  area.addEventListener("input", { _ ->
    fitToContent(area)
    onPreview(area.value)
    markPending(area, ::commit)
  })
  area.addEventListener("change", { _ -> commit() })
  area.addEventListener("focus", { _ -> fitToContent(area) })
  commitKeys(area, newlineOnShiftEnter = true) {
    clearPending(area)
    area.value = committed
    onPreview(committed)
    fitToContent(area)
  }
  window.requestAnimationFrame { fitToContent(area) }
  return area
}

/**
 * E11: what a numeric prop accepts — a whole number ([whole]) or any number,
 * and at least [min] ([minWhy] says why). Out-of-range and non-numeric text
 * is never sent.
 */
internal data class NumberRule(val whole: Boolean, val min: Double? = null, val minWhy: String? = null)

/**
 * The rule for wire key [key] of a [kind] prop on a [nodeType] node: a Dp
 * (a Dp prop, a Margin side, a Dp modifier argument) is never negative — its
 * constructor requires it; a `fontSize` (sp) of 0 or less draws nothing.
 */
internal fun numberRule(nodeType: String, key: String, kind: PropKind): NumberRule {
  val whole = kind == PropKind.Int
  return when {
    isDpProp(nodeType, key) -> NumberRule(whole, 0.0, "A size can't be negative")
    key.substringAfterLast('.') == "fontSize" -> NumberRule(whole, 1.0, "Font size must be at least 1")
    else -> NumberRule(whole)
  }
}

/** [text] read under [rule]: the value (an Int or a Double), or why it is refused. */
internal fun parseNumber(text: String, rule: NumberRule): Result<Number> {
  val t = text.trim()
  if (t.isEmpty()) return Result.failure(IllegalArgumentException("Enter a number"))
  // Statements, not one `if` expression typed Number: Kotlin/Wasm emits invalid
  // code for an `if` whose branches box an Int and a Double.
  val v: Number
  if (rule.whole) {
    val i: Int = t.toIntOrNull() ?: return Result.failure(
      IllegalArgumentException(if (t.toLongOrNull() != null) "That number is too large" else "Enter a whole number"),
    )
    v = i
  } else {
    val d: Double = t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return Result.failure(IllegalArgumentException("Enter a number"))
    v = d
  }
  val min = rule.min
  if (min != null && v.toDouble() < min) return Result.failure(IllegalArgumentException(rule.minWhy ?: "Must be at least ${showNumber(min)}"))
  return Result.success(v)
}

/** A number as a field shows it: `8`, not `8.0`. */
internal fun showNumber(v: Number): String {
  val d = v.toDouble()
  return if (v is Int || (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15)) d.toLong().toString() else d.toString()
}

/**
 * E11: a numeric prop's field. Typing validates (an error shows inline, under
 * the field) and previews a valid value on the canvas through [onPreview];
 * nothing is written until the edit is committed — Enter or leaving the
 * field — and then only a valid, changed value, once, through [onCommit].
 * ↑/↓ step by 1 (Shift: 10). Escape puts the committed value back.
 */
internal fun numberField(value: Number, rule: NumberRule, onPreview: (Number) -> Unit = {}, onCommit: (Number) -> Unit): HTMLElement {
  var committed: Number = value
  val wrap = Ui.el("div", "ctl-wrap")
  val input = Ui.input("input num")
  input.setAttribute("type", "text")
  input.setAttribute("inputmode", if (rule.whole) "numeric" else "decimal")
  input.setAttribute("spellcheck", "false")
  input.value = showNumber(value)
  val err = Ui.el("div", "field-err")
  fun same(a: Number, b: Number) = a.toDouble() == b.toDouble()
  fun validate(): Number? {
    val r = parseNumber(input.value, rule)
    markInvalid(input, r.exceptionOrNull()?.message, err)
    return r.getOrNull()
  }
  fun commit() {
    clearPending(input)
    // Stays marked and nothing is sent; the canvas goes back to the value that is written.
    val v = validate() ?: return onPreview(committed)
    input.value = showNumber(v)
    if (same(v, committed)) return
    committed = v
    onCommit(v)
  }
  input.addEventListener("input", { _ ->
    validate()?.let(onPreview)
    markPending(input, ::commit)
  })
  input.addEventListener("change", { _ -> commit() })
  input.addEventListener("keydown", { ev ->
    val e = ev as KeyboardEvent
    if (e.key != "ArrowUp" && e.key != "ArrowDown") return@addEventListener
    e.preventDefault()
    val base = parseNumber(input.value, rule.copy(min = null)).getOrNull() ?: committed
    val step = (if (e.shiftKey) 10 else 1) * (if (e.key == "ArrowUp") 1 else -1)
    var next = base.toDouble() + step
    rule.min?.let { next = maxOf(next, it) }
    input.value = if (rule.whole) showNumber(next.toInt()) else showNumber(next)
    validate()?.let(onPreview)
    markPending(input, ::commit)
  })
  commitKeys(input) {
    clearPending(input)
    input.value = showNumber(committed)
    markInvalid(input, null, err)
    onPreview(committed)
  }
  wrap.appendChild(input)
  wrap.appendChild(err)
  return wrap
}
