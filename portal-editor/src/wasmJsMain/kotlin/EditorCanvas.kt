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
/**
 * The zoom after a Cmd/Ctrl+wheel (a trackpad pinch arrives as one too): continuous, by
 * [deltaY] (negative = in), clamped to the zoom range.
 */
internal fun wheelZoom(current: Double, deltaY: Double): Double =
  (current * kotlin.math.exp(-deltaY * 0.0025)).coerceIn(ZOOM_MIN, ZOOM_MAX)

/** The zoom a keyboard shortcut asks for (⌘= / ⌘−, ⇧1 fit, ⇧0 100%), or null when the keys are not one. */
internal fun zoomForKeys(code: String, key: String, mod: Boolean, shift: Boolean, current: Double, fit: () -> Double): Double? = when {
  mod && (key == "=" || key == "+") -> nextZoom(current, up = true)
  mod && (key == "-" || key == "_") -> nextZoom(current, up = false)
  shift && !mod && code == "Digit1" -> fit()
  shift && !mod && code == "Digit0" -> 1.0
  else -> null
}

internal fun fitZoom(w: Int, h: Int, availW: Double, availH: Double): Double {
  if (w <= 0 || h <= 0 || availW <= 0 || availH <= 0) return 1.0
  val z = minOf(1.0, availW / w, availH / h)
  return (kotlin.math.floor(z * 100) / 100).coerceIn(ZOOM_MIN, ZOOM_MAX)
}

private var syncToolbar: () -> Unit = {}

private var frameW = 390
private var frameH = 780
private var canvasFrame: HTMLElement? = null
private var canvasZoomBox: HTMLElement? = null
private var canvasCenter: HTMLElement? = null

/** The device frame's size in dp. */
internal fun currentFrameSize(): Pair<Int, Int> = frameW to frameH

/** A "WIDTHxHEIGHT" preset as (w, h), or null when it is not two positive numbers. */
internal fun parsePreset(dims: String): Pair<Int, Int>? {
  val parts = dims.split("x")
  val w = parts.getOrNull(0)?.toIntOrNull() ?: return null
  val h = parts.getOrNull(1)?.toIntOrNull() ?: return null
  return if (parts.size == 2 && w in 120..4000 && h in 120..4000) w to h else null
}

/**
 * E23: resize the device frame in place (the composition follows its
 * container, so mocks, selection and Live state survive), zooming out to fit
 * when the new frame is larger than the window.
 */
internal fun applyFramePreset(dims: String) {
  val (w, h) = parsePreset(dims) ?: return
  val frame = canvasFrame ?: return
  val box = canvasZoomBox ?: return
  frameW = w
  frameH = h
  frame.style.width = "${w}px"
  frame.style.height = "${h}px"
  val center = canvasCenter
  val fits = center == null || (w * canvasZoom <= center.clientWidth - 40 && h * canvasZoom <= center.clientHeight - 72)
  applyZoom(if (fits) canvasZoom else fitZoom(w, h, (center?.clientWidth ?: w) - 40.0, (center?.clientHeight ?: h) - 72.0), box, frame)
}

internal fun canvasToolbarSync() = syncToolbar()

/** The canvas overlay's name tag: `Column`, `StatTile · component`, and on the selection its size in px. */
internal fun overlayLabel(type: String, component: Boolean, size: Pair<Double, Double>?): String =
  type + (if (component) " · component" else "") +
    (size?.let { (w, h) -> "  ${kotlin.math.round(w).toInt()}×${kotlin.math.round(h).toInt()}" } ?: "")

/** Where an overlay's tag goes: above its box, else below it, else (the box fills the frame) inside. */
internal enum class TagPlace { Above, Below, Inside }

/**
 * F12: the tag must not cover what it names — a box at the frame's top edge carries it below
 * (above it would be clipped); only a box with no room on either side carries it inside.
 */
internal fun tagPlace(top: Double, bottom: Double, frameHeight: Double, tagHeight: Double = 18.0): TagPlace = when {
  top >= tagHeight -> TagPlace.Above
  bottom + tagHeight <= frameHeight -> TagPlace.Below
  else -> TagPlace.Inside
}

internal fun installCanvasToolbar(center: HTMLElement, zoomBox: HTMLElement, frame: HTMLElement, w: Int, h: Int) {
  frameW = w
  frameH = h
  canvasFrame = frame
  canvasZoomBox = zoomBox
  canvasCenter = center
  val bar = Ui.el("div", "canvas-toolbar")
  bar.setAttribute("role", "toolbar")
  bar.setAttribute("aria-label", "Canvas")
  val select = Ui.button("🎯 Select", "seg") { setSelectMode(true) }
  val interact = Ui.button("☝ Interact", "seg") { setSelectMode(false) }
  select.setAttribute("title", "Click selects a node; the app does not see the click")
  interact.setAttribute("title", "Clicks go to the app (buttons, fields, scrolling lists)")
  val zoomOut = Ui.button("−", "seg") { applyZoom(nextZoom(canvasZoom, up = false), zoomBox, frame) }
  val level = Ui.el("span", "zoom-level", "100%")
  val zoomIn = Ui.button("+", "seg") { applyZoom(nextZoom(canvasZoom, up = true), zoomBox, frame) }
  val fit = Ui.button("Fit", "seg") { applyZoom(currentFitZoom(), zoomBox, frame) }
  // ⌘/Ctrl + wheel, and a trackpad pinch, zoom the frame (in capture: before the canvas scrolls).
  center.addEventListener("wheel", { ev ->
    val we = ev as org.w3c.dom.events.WheelEvent
    if (we.ctrlKey || we.metaKey) {
      ev.preventDefault()
      ev.stopPropagation()
      applyZoom(wheelZoom(canvasZoom, we.deltaY), zoomBox, frame)
    }
  }, nonPassiveCapture())
  zoomOut.setAttribute("title", "Zoom out (⌘−)")
  zoomIn.setAttribute("title", "Zoom in (⌘=, or ⌘/pinch + wheel)")
  fit.setAttribute("title", "Fit the frame in the window (⇧1; ⇧0 for 100%)")
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
    select.setAttribute("aria-pressed", "${selectModeOn()}")
    interact.setAttribute("aria-pressed", "${!selectModeOn()}")
  }
  val stored = runCatching { localStorage.getItem("portal.zoom")?.toDoubleOrNull() }.getOrNull()
  applyZoom(stored?.coerceIn(ZOOM_MIN, ZOOM_MAX) ?: 1.0, zoomBox, frame)
}

private var canvasZoomIsSet = false

/** The zoom that fits the frame in the canvas area. */
private fun currentFitZoom(): Double {
  val center = canvasCenter ?: return 1.0
  return fitZoom(frameW, frameH, center.clientWidth - 40.0, center.clientHeight - 72.0)
}

/** A zoom keyboard shortcut (outside text fields): true when [e] was one, and the zoom applied. */
internal fun handleZoomKeys(e: org.w3c.dom.events.KeyboardEvent): Boolean {
  val box = canvasZoomBox ?: return false
  val frame = canvasFrame ?: return false
  val z = zoomForKeys(e.code, e.key, e.metaKey || e.ctrlKey, e.shiftKey, canvasZoom, ::currentFitZoom) ?: return false
  e.preventDefault() // ⌘= / ⌘− would otherwise zoom the whole page
  applyZoom(z, box, frame)
  return true
}

// A plain object: the generated AddEventListenerOptions() passes unset options as null.
private fun nonPassiveCapture(): org.w3c.dom.AddEventListenerOptions = js("({ passive: false, capture: true })")

private fun applyZoom(z: Double, zoomBox: HTMLElement, frame: HTMLElement) {
  val w = frameW
  val h = frameH
  canvasZoom = z
  canvasZoomIsSet = true
  frame.style.transform = if (z == 1.0) "" else "scale($z)"
  frame.style.setProperty("transform-origin", "top left")
  zoomBox.style.width = "${w * z}px"
  zoomBox.style.height = "${h * z}px"
  runCatching { localStorage.setItem("portal.zoom", "$z") }
  canvasToolbarSync()
}
