/*
 * Resizable, collapsible side panels. Each panel's inner edge is a splitter:
 * drag it to resize (within [PANEL_MIN_PX, PANEL_MAX_PX]), double-click it to
 * collapse the panel (or bring it back). Widths and collapsed state persist in
 * this browser (localStorage), per side.
 */
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.pointerevents.PointerEvent

internal const val PANEL_MIN_PX = 200
internal const val PANEL_MAX_PX = 640

/** Which side a panel is on: its CSS width variable and its storage keys. */
internal enum class PanelSide(val cssVar: String, val defaultPx: Int, val cls: String) {
  Left("--left-w", 272, "left"),
  Right("--right-w", 300, "right"),
}

/**
 * The width a drag ends at: [startPx] plus the pointer's travel ([dx], positive to the right),
 * which widens the left panel and narrows the right one, clamped to the allowed range.
 */
internal fun draggedWidth(side: PanelSide, startPx: Int, dx: Double): Int {
  val raw = if (side == PanelSide.Left) startPx + dx else startPx - dx
  return raw.toInt().coerceIn(PANEL_MIN_PX, PANEL_MAX_PX)
}

/** A stored width, if it is a number in range; otherwise the side's default. */
internal fun storedWidth(side: PanelSide, stored: String?): Int =
  stored?.toIntOrNull()?.takeIf { it in PANEL_MIN_PX..PANEL_MAX_PX } ?: side.defaultPx

private fun store(key: String): String? = runCatching { localStorage.getItem(key) }.getOrNull()
private fun store(key: String, value: String) { runCatching { localStorage.setItem(key, value) } }

internal fun installPanelResizers() {
  PanelSide.entries.forEach(::installResizer)
}

private fun installResizer(side: PanelSide) {
  val pane = document.querySelector(".pane.${side.cls}") as? HTMLElement ?: return
  val root = document.documentElement as HTMLElement
  val widthKey = "portal.panel.${side.cls}.width"
  val collapsedKey = "portal.panel.${side.cls}.collapsed"
  var width = storedWidth(side, store(widthKey))
  var collapsed = store(collapsedKey) == "true"

  val splitter = Ui.el("div", "splitter ${side.cls}")
  splitter.setAttribute("role", "separator")
  splitter.setAttribute("aria-orientation", "vertical")
  splitter.setAttribute("title", "Drag to resize · double-click to ${if (collapsed) "show" else "hide"} the panel")

  fun apply() {
    root.style.setProperty(side.cssVar, if (collapsed) "0px" else "${width}px")
    if (collapsed) pane.classList.add("collapsed") else pane.classList.remove("collapsed")
    splitter.setAttribute("aria-valuenow", if (collapsed) "0" else "$width")
    splitter.setAttribute("title", "Drag to resize · double-click to ${if (collapsed) "show" else "hide"} the panel")
    // The canvas's frame and overlays are positioned from the center's box.
    window.dispatchEvent(Event("resize"))
  }

  var startX = 0.0
  var startWidth = width
  var dragging = false
  splitter.addEventListener("pointerdown", { ev ->
    val pe = ev as PointerEvent
    if (pe.button.toInt() != 0) return@addEventListener
    dragging = true
    startX = pe.clientX.toDouble()
    startWidth = if (collapsed) PANEL_MIN_PX else width
    splitter.setPointerCapture(pe.pointerId)
    splitter.classList.add("dragging")
    document.body?.classList?.add("resizing")
    pe.preventDefault()
  })
  splitter.addEventListener("pointermove", { ev ->
    if (!dragging) return@addEventListener
    val pe = ev as PointerEvent
    collapsed = false
    width = draggedWidth(side, startWidth, pe.clientX - startX)
    apply()
  })
  fun endDrag(ev: Event) {
    if (!dragging) return
    dragging = false
    runCatching { splitter.releasePointerCapture((ev as PointerEvent).pointerId) }
    splitter.classList.remove("dragging")
    document.body?.classList?.remove("resizing")
    store(widthKey, "$width")
    store(collapsedKey, "$collapsed")
  }
  splitter.addEventListener("pointerup", ::endDrag)
  splitter.addEventListener("pointercancel", ::endDrag)
  splitter.addEventListener("dblclick", { _ ->
    collapsed = !collapsed
    store(collapsedKey, "$collapsed")
    apply()
  })

  document.body?.appendChild(splitter)
  apply()
}

/** E41: calls [onAdded] with each element added anywhere under the page's body. */
internal fun observeAdded(onAdded: (org.w3c.dom.Element) -> Unit) {
  val observer = org.w3c.dom.MutationObserver { records, _ ->
    for (i in 0 until records.length) {
      val added = records[i]?.addedNodes ?: continue
      for (j in 0 until added.length) (added.item(j) as? org.w3c.dom.Element)?.let(onAdded)
    }
  }
  kotlinx.browser.document.body?.let { observer.observe(it, childListSubtree()) }
}

// A plain object: the generated MutationObserverInit() passes its unset options as null, which observe() rejects.
private fun childListSubtree(): org.w3c.dom.MutationObserverInit = js("({ childList: true, subtree: true })")
