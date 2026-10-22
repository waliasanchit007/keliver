/*
 * The canvas toolbar: Select / Interact, and zoom (−, the level, +, Fit). It
 * sits above the device frame, never over the app's own content. Zoom scales
 * the frame with a CSS transform; hit-testing divides it back out
 * ([canvasZoom]), and the selection overlays live inside the frame, so they
 * scale with it.
 */
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

internal const val ZOOM_MIN = 0.25
internal const val ZOOM_MAX = 2.0
private val ZOOM_STEPS = listOf(0.25, 0.33, 0.5, 0.67, 0.75, 0.9, 1.0, 1.25, 1.5, 2.0)

/** The frame's current scale (1 = 1 dp per CSS px). */
internal var canvasZoom = 1.0
  private set

/** The next zoom step above (or below, for [up] = false) [current]. */
internal fun nextZoom(current: Double, up: Boolean): Double =
  if (up) ZOOM_STEPS.firstOrNull { it > current + 1e-6 } ?: ZOOM_MAX
  else ZOOM_STEPS.lastOrNull { it < current - 1e-6 } ?: ZOOM_MIN

/** The largest zoom (at most 100%) at which a [w]×[h] frame fits in [availW]×[availH]. */
internal fun fitZoom(w: Int, h: Int, availW: Double, availH: Double): Double {
  if (w <= 0 || h <= 0 || availW <= 0 || availH <= 0) return 1.0
  val z = minOf(1.0, availW / w, availH / h)
  return (kotlin.math.floor(z * 100) / 100).coerceIn(ZOOM_MIN, ZOOM_MAX)
}

private var syncToolbar: () -> Unit = {}

internal fun canvasToolbarSync() = syncToolbar()

internal fun installCanvasToolbar(center: HTMLElement, zoomBox: HTMLElement, frame: HTMLElement, w: Int, h: Int) {
  val bar = Ui.el("div", "canvas-toolbar")
  val select = Ui.button("🎯 Select", "seg") { setSelectMode(true) }
  val interact = Ui.button("☝ Interact", "seg") { setSelectMode(false) }
  select.setAttribute("title", "Click selects a node; the app does not see the click")
  interact.setAttribute("title", "Clicks go to the app (buttons, fields, scrolling lists)")
  val zoomOut = Ui.button("−", "seg") { applyZoom(nextZoom(canvasZoom, up = false), zoomBox, frame, w, h) }
  val level = Ui.el("span", "zoom-level", "100%")
  val zoomIn = Ui.button("+", "seg") { applyZoom(nextZoom(canvasZoom, up = true), zoomBox, frame, w, h) }
  val fit = Ui.button("Fit", "seg") {
    applyZoom(fitZoom(w, h, center.clientWidth - 40.0, center.clientHeight - 72.0), zoomBox, frame, w, h)
  }
  zoomOut.setAttribute("title", "Zoom out")
  zoomIn.setAttribute("title", "Zoom in")
  fit.setAttribute("title", "Fit the frame in the window")
  listOf(select, interact).forEach { bar.appendChild(it) }
  bar.appendChild(Ui.el("span", "sep"))
  listOf(zoomOut, level, zoomIn, fit).forEach { bar.appendChild(it) }
  document.body?.appendChild(bar)

  val hint = Ui.el("div", "canvas-hint")
  hint.style.display = "none"
  document.body?.appendChild(hint)
  canvasHintElement(hint)

  syncToolbar = {
    if (canvasZoomIsSet) level.textContent = "${kotlin.math.round(canvasZoom * 100).toInt()}%"
    select.classList.toggle("on", selectModeOn())
    interact.classList.toggle("on", !selectModeOn())
  }
  val stored = runCatching { localStorage.getItem("portal.zoom")?.toDoubleOrNull() }.getOrNull()
  applyZoom(stored?.coerceIn(ZOOM_MIN, ZOOM_MAX) ?: 1.0, zoomBox, frame, w, h)
}

private var canvasZoomIsSet = false

private fun applyZoom(z: Double, zoomBox: HTMLElement, frame: HTMLElement, w: Int, h: Int) {
  canvasZoom = z
  canvasZoomIsSet = true
  frame.style.transform = if (z == 1.0) "" else "scale($z)"
  frame.style.setProperty("transform-origin", "top left")
  zoomBox.style.width = "${w * z}px"
  zoomBox.style.height = "${h * z}px"
  runCatching { localStorage.setItem("portal.zoom", "$z") }
  canvasToolbarSync()
}
