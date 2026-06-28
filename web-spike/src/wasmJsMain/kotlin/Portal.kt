/*
 * spike/keliver-web portal — the RICH EDITOR (outline + properties + drag-drop).
 * A fixed-position DOM panel drives the shared portalTree; the canvas shows the
 * live preview (engine A); Export prints exportKotlin (B). All Kotlin DOM.
 */
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.keliver.portal.PropKind
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.editableProps
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.findNode
import dev.keliver.portal.insertChild
import dev.keliver.portal.moveNode
import dev.keliver.portal.serializeTree
import dev.keliver.portal.updateProps
import kotlinx.browser.document
import org.w3c.dom.DragEvent
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

private val BOX_PROPS: Map<String, Any?> = mapOf(
  "gradientColorsArgb" to listOf(0xFFFFF4E8.toInt(), 0xFFFFE9D6.toInt()),
  "gradientStops" to listOf(0.0f, 1.0f),
  "gradientDirection" to 3,
  "borderColorArgb" to 0xFFFFD9B0.toInt(),
  "borderWidthDp" to 1, "cornerRadiusDp" to 12, "fillWidth" to true, "paddingDp" to 20,
)

private fun initialTree(): WidgetNode = WidgetNode(
  "StyledBox", BOX_PROPS,
  listOf(WidgetNode("Column", emptyMap(), listOf(
    WidgetNode("StyledText", mapOf("text" to "Select me in the outline →", "fontSize" to 18, "bold" to true, "colorArgb" to 0xFF111111.toInt())),
  ))),
)

/** The single source of truth, observed by the canvas composition (RenderNode). */
val portalTree = mutableStateOf(initialTree())
private var selectedId: Int? = null
private val PALETTE = listOf("StyledText", "Spacer", "Button", "AsyncImage")

private lateinit var outlineEl: HTMLElement
private lateinit var propsEl: HTMLElement
private lateinit var exportEl: HTMLElement

private const val RELAY = "http://localhost:8077/tree"

/** Fire-and-forget POST of the serialized tree to the relay (M2 device sync). */
private fun postTree(url: String, body: String) {
  js("fetch(url, { method: 'POST', body: body }).catch(function(){})")
}

private fun applyTree(t: WidgetNode) {
  portalTree.value = t
  Snapshot.sendApplyNotifications() // write happens outside composition (DOM callback) — flush
  postTree(RELAY, serializeTree(t)) // push to the relay; the device polls GET /tree
}

private fun el(tag: String, style: String = "", text: String = ""): HTMLElement {
  val e = document.createElement(tag) as HTMLElement
  if (style.isNotEmpty()) e.setAttribute("style", style)
  if (text.isNotEmpty()) e.textContent = text
  return e
}

private fun clear(host: HTMLElement) { while (host.firstChild != null) host.removeChild(host.firstChild!!) }

private fun newNode(type: String): WidgetNode = when (type) {
  "StyledText" -> WidgetNode("StyledText", mapOf("text" to "text", "fontSize" to 16, "colorArgb" to 0xFF333333.toInt()))
  "Spacer" -> WidgetNode("Spacer", mapOf("height" to 12.0))
  "Button" -> WidgetNode("Button", mapOf("text" to "Button"))
  "AsyncImage" -> WidgetNode("AsyncImage", mapOf("url" to "https://picsum.photos/seed/keliver/96/96"))
  else -> WidgetNode(type)
}

fun mountPortalChrome() {
  val panel = el("div", "position:fixed; left:0; top:0; width:320px; height:100%; overflow:auto; background:#fafafa; border-right:1px solid #ccc; font-family:sans-serif; font-size:13px; padding:8px; box-sizing:border-box; z-index:10;")

  panel.appendChild(el("div", "font-weight:bold; margin:4px 0;", "Palette  (drag onto outline, or click to add)"))
  val palette = el("div", "display:flex; gap:6px; flex-wrap:wrap; margin-bottom:8px;")
  PALETTE.forEach { type ->
    val chip = el("div", "border:1px solid #888; border-radius:4px; padding:4px 8px; cursor:grab; background:#fff;", type)
    chip.setAttribute("draggable", "true")
    chip.addEventListener("dragstart", { ev -> (ev as DragEvent).dataTransfer?.setData("text", "new:$type") })
    chip.addEventListener("click", { _ -> addToSelectedOrRoot(newNode(type)) })
    palette.appendChild(chip)
  }
  panel.appendChild(palette)

  panel.appendChild(el("div", "font-weight:bold; margin:8px 0 4px;", "Outline"))
  outlineEl = el("div", "border:1px solid #ddd; background:#fff; padding:4px; min-height:80px;")
  panel.appendChild(outlineEl)

  panel.appendChild(el("div", "font-weight:bold; margin:8px 0 4px;", "Properties"))
  propsEl = el("div", "border:1px solid #ddd; background:#fff; padding:6px; min-height:60px;")
  panel.appendChild(propsEl)

  val exportBtn = el("button", "margin:10px 0 6px; padding:6px 10px; cursor:pointer;", "Export Kotlin")
  exportBtn.addEventListener("click", { _ -> exportEl.textContent = exportKotlin(portalTree.value) })
  panel.appendChild(exportBtn)
  exportEl = el("pre", "background:#f0f0f0; font-size:10px; white-space:pre-wrap; padding:6px; max-height:240px; overflow:auto;")
  panel.appendChild(exportEl)

  document.body?.appendChild(panel)
  (document.getElementById("ComposeTarget") as? HTMLElement)?.setAttribute("style", "position:absolute; left:340px; top:0;")
  refresh()
  postTree(RELAY, serializeTree(portalTree.value)) // push the initial tree to the relay
}

private fun addToSelectedOrRoot(node: WidgetNode) {
  val sel = selectedId
  val parentId = if (sel != null && portalTree.value.findNode(sel)?.type in setOf("StyledBox", "Column", "Row")) sel
  else (portalTree.value.children.firstOrNull()?.id ?: portalTree.value.id)
  applyTree(portalTree.value.insertChild(parentId, node, Int.MAX_VALUE))
  refresh()
}

private fun refresh() { renderOutline(); renderProps() }

private fun renderOutline() {
  clear(outlineEl)
  fun row(node: WidgetNode, depth: Int) {
    val label = (node.props["text"] as? String)?.let { " · \"$it\"" } ?: ""
    val r = el(
      "div",
      "padding:2px 4px; cursor:pointer; white-space:nowrap;" + if (node.id == selectedId) " background:#cfe3ff;" else "",
      "${"  ".repeat(depth)}▸ ${node.type}$label",
    )
    r.addEventListener("click", { _ -> selectedId = node.id; refresh() })
    r.setAttribute("draggable", "true")
    r.addEventListener("dragstart", { ev -> (ev as DragEvent).dataTransfer?.setData("text", "move:${node.id}"); ev.stopPropagation() })
    r.addEventListener("dragover", { ev -> ev.preventDefault() })
    r.addEventListener("drop", { ev -> ev.preventDefault(); handleDrop((ev as DragEvent).dataTransfer?.getData("text"), node.id) })
    outlineEl.appendChild(r)
    node.children.forEach { row(it, depth + 1) }
  }
  row(portalTree.value, 0)
}

private fun handleDrop(payload: String?, targetId: Int) {
  if (payload == null) return
  when {
    payload.startsWith("new:") -> { applyTree(portalTree.value.insertChild(targetId, newNode(payload.removePrefix("new:")), Int.MAX_VALUE)); refresh() }
    payload.startsWith("move:") -> { val id = payload.removePrefix("move:").toIntOrNull() ?: return; applyTree(portalTree.value.moveNode(id, targetId, Int.MAX_VALUE)); refresh() }
  }
}

private fun renderProps() {
  clear(propsEl)
  val id = selectedId
  if (id == null) { propsEl.textContent = "(select a node in the outline)"; return }
  val node = portalTree.value.findNode(id)
  if (node == null) { propsEl.textContent = "(node not found)"; return }
  propsEl.appendChild(el("div", "color:#888; margin-bottom:4px;", "${node.type} #${node.id}"))
  editableProps(node.type).forEach { spec ->
    val rowEl = el("div", "margin:3px 0; display:flex; gap:6px; align-items:center;")
    rowEl.appendChild(el("label", "width:90px;", spec.label))
    val input = document.createElement("input") as HTMLInputElement
    when (spec.kind) {
      PropKind.Text -> { input.setAttribute("type", "text"); input.value = node.props[spec.name] as? String ?: ""
        input.addEventListener("input", { _ -> editProp(id, spec.name, input.value) }) }
      PropKind.Int -> { input.setAttribute("type", "number"); input.value = (node.props[spec.name] as? Int ?: 0).toString()
        input.addEventListener("input", { _ -> editProp(id, spec.name, input.value.toIntOrNull() ?: 0) }) }
      PropKind.Double -> { input.setAttribute("type", "number"); input.value = (node.props[spec.name] as? Double ?: 0.0).toString()
        input.addEventListener("input", { _ -> editProp(id, spec.name, input.value.toDoubleOrNull() ?: 0.0) }) }
      PropKind.Bool -> { input.setAttribute("type", "checkbox"); if (node.props[spec.name] as? Boolean == true) input.setAttribute("checked", "checked")
        input.addEventListener("change", { _ -> editProp(id, spec.name, input.checked) }) }
      PropKind.Color -> { input.setAttribute("type", "color"); input.value = argbToHex(node.props[spec.name] as? Int ?: 0xFF000000.toInt())
        input.addEventListener("input", { _ -> editProp(id, spec.name, hexToArgb(input.value)) }) }
    }
    rowEl.appendChild(input)
    propsEl.appendChild(rowEl)
  }
}

private fun editProp(id: Int, name: String, value: Any?) {
  val cur = portalTree.value.findNode(id) ?: return
  applyTree(portalTree.value.updateProps(id, cur.props + (name to value)))
  // no refresh(): keep the input's focus while typing; the preview updates live.
}

private fun argbToHex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')
private fun hexToArgb(hex: String): Int = (0xFF shl 24) or (hex.removePrefix("#").toIntOrNull(16) ?: 0)
