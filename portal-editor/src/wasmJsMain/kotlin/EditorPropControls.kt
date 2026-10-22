/*
 * The properties panel's typed value controls, shared by plain props, modifier
 * args and conditional branches. Each takes the current value and reports a
 * committed new value through a callback, so it can be tested without a relay.
 */
import dev.keliver.portal.PropChoice
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
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
internal class PropRow(label: String, layout: Layout = Layout.Stacked) {
  enum class Layout(val css: String) { Stacked("prop"), Compact("prop compact"), Inline("prop inline") }

  val root: HTMLElement = Ui.el("div", layout.css)
  private val ctl = Ui.el("div", "prop-ctl")
  private val actions = Ui.el("div", "prop-actions")

  init {
    val lab = Ui.el("label", "prop-label", label)
    lab.setAttribute("title", label)
    root.appendChild(lab)
    root.appendChild(ctl)
    root.appendChild(actions)
  }

  fun control(e: HTMLElement): PropRow = apply { ctl.appendChild(e) }

  fun action(e: HTMLElement?): PropRow = apply { if (e != null) actions.appendChild(e) }
}

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
