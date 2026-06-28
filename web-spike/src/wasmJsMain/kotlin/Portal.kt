/*
 * spike/keliver-web portal sub-project C — the portal SHELL (DOM chrome).
 *
 * A DOM toolbar (palette + remove + export) drives the shared WidgetNode tree;
 * the wasm canvas (RenderNode, engine A) shows the live preview; Export prints the
 * keliver Kotlin (exportKotlin, B). All Kotlin — the chrome shares the tree and
 * calls exportKotlin directly, no JS bridge. Real DOM controls (reliable clicks).
 */
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.exportKotlin
import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

private val BOX_PROPS: Map<String, Any?> = mapOf(
  "gradientColorsArgb" to listOf(0xFFFFF4E8.toInt(), 0xFFFFE9D6.toInt()),
  "gradientStops" to listOf(0.0f, 1.0f),
  "gradientDirection" to 3,
  "borderColorArgb" to 0xFFFFD9B0.toInt(),
  "borderWidthDp" to 1,
  "cornerRadiusDp" to 12,
  "fillWidth" to true,
  "paddingDp" to 20,
)

/** The editable column children. The first two are the fixed header. */
private val children = mutableListOf(
  WidgetNode("StyledText", mapOf("text" to "Portal shell · add widgets with the toolbar", "fontSize" to 14, "bold" to true, "colorArgb" to 0xFF8A8A8A.toInt())),
  WidgetNode("Spacer", mapOf("height" to 8.0)),
)

private fun buildTree(): WidgetNode =
  WidgetNode("StyledBox", BOX_PROPS, listOf(WidgetNode("Column", emptyMap(), children.toList())))

/** The single source of truth, observed by the canvas composition (RenderNode). */
val portalTree = mutableStateOf(buildTree())

private fun rebuild() {
  portalTree.value = buildTree()
  // The write happens outside any composition (a DOM callback) — flush so the
  // canvas recomposer observes it promptly.
  Snapshot.sendApplyNotifications()
}

/** Build the DOM toolbar + export panel and mount them around the canvas. */
fun mountPortalChrome() {
  val bar = document.createElement("div") as HTMLElement
  bar.setAttribute("style", "font-family: sans-serif; padding: 8px; display: flex; gap: 8px; flex-wrap: wrap; align-items: center;")

  val exportPre = document.createElement("pre") as HTMLElement
  exportPre.setAttribute("style", "margin: 8px; padding: 8px; background: #f0f0f0; font-size: 11px; white-space: pre-wrap;")

  fun button(label: String, onClick: () -> Unit) {
    val b = document.createElement("button") as HTMLElement
    b.textContent = label
    b.setAttribute("style", "padding: 6px 10px; cursor: pointer;")
    b.addEventListener("click", { _: Event -> onClick() })
    bar.appendChild(b)
  }

  var n = 0
  button("Add Text") { n++; children.add(WidgetNode("StyledText", mapOf("text" to "text $n", "fontSize" to 16, "colorArgb" to 0xFF333333.toInt()))); rebuild() }
  button("Add Spacer") { children.add(WidgetNode("Spacer", mapOf("height" to 12.0))); rebuild() }
  button("Add Button") { n++; children.add(WidgetNode("Button", mapOf("text" to "Button $n"))); rebuild() }
  button("Remove last") { if (children.size > 2) { children.removeAt(children.size - 1); rebuild() } }
  button("Export Kotlin") { exportPre.textContent = exportKotlin(portalTree.value) }

  val body = document.body
  val canvas: Element? = document.getElementById("ComposeTarget")
  body?.insertBefore(bar, canvas)
  body?.appendChild(exportPre)
}
