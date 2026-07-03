/*
 * spike/keliver-web portal P2 — the redesigned EDITOR.
 * 3-pane dark chrome (Kotlin DOM on the Ui kit): left = project/screen +
 * searchable 60-widget palette + outline; center = live canvas in a device
 * frame; right = generated properties + modifiers + node ops. Undo/redo over
 * the immutable tree, autosaved drafts via portal-server (refresh-safe), and
 * the device keeps mirroring the active screen through GET /tree.
 */
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.keliver.portal.PropKind
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.editableProps
import dev.keliver.portal.ensureNodeIdsAbove
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.findNode
import dev.keliver.portal.insertChild
import dev.keliver.portal.maxId
import dev.keliver.portal.modifierSpecs
import dev.keliver.portal.moveNode
import dev.keliver.portal.removeNode
import dev.keliver.portal.serializeTree
import dev.keliver.portal.updateProps
import dev.keliver.portal.widgetSpec
import dev.keliver.portal.widgetSpecs
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.DragEvent
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.xhr.XMLHttpRequest

// ---------------------------------------------------------------------------
// State

private fun initialTree(): WidgetNode = WidgetNode(
  "StyledBox",
  mapOf(
    "gradientColorsArgb" to listOf(0xFFFFF4E8.toInt(), 0xFFFFE9D6.toInt()),
    "gradientStops" to listOf(0.0f, 1.0f),
    "gradientDirection" to 3,
    "borderColorArgb" to 0xFFFFD9B0.toInt(),
    "borderWidthDp" to 1, "cornerRadiusDp" to 12, "fillWidth" to true, "paddingDp" to 20,
  ),
  listOf(
    WidgetNode(
      "Column", emptyMap(),
      listOf(
        WidgetNode("StyledText", mapOf("text" to "Welcome to the keliver portal", "fontSize" to 20, "bold" to true, "colorArgb" to 0xFF111111.toInt())),
        WidgetNode("Spacer", mapOf("height" to 8.0)),
        WidgetNode("StyledText", mapOf("text" to "Add widgets from the palette — everything is live on web and device.", "fontSize" to 14, "colorArgb" to 0xFF555555.toInt())),
      ),
    ),
  ),
)

/** The single source of truth, observed by the canvas composition (RenderNode). */
val portalTree = mutableStateOf(initialTree())

private var selectedId: Int? = null
private var currentProject = "default"
private var currentScreen = "main"
private val undoStack = ArrayDeque<WidgetNode>()
private val redoStack = ArrayDeque<WidgetNode>()
private const val UNDO_CAP = 100
private var saveTimer = -1

private const val SERVER = "http://localhost:8077"

// ---------------------------------------------------------------------------
// Server IO (XHR reads with callbacks; fire-and-forget writes)

private fun serverGet(path: String, cb: (String) -> Unit) {
  val xhr = XMLHttpRequest()
  xhr.open("GET", "$SERVER$path")
  xhr.addEventListener("load", { _ -> cb(xhr.responseText) })
  xhr.send()
}

private fun sendBody(url: String, m: String, body: String) {
  js("fetch(url, { method: m, body: body }).catch(function(){})")
}

private fun sendEmpty(url: String, m: String) {
  js("fetch(url, { method: m }).catch(function(){})")
}

// ---------------------------------------------------------------------------
// Element refs (assigned in mountPortalChrome)

private lateinit var outlineEl: HTMLElement
private lateinit var propsEl: HTMLElement
private lateinit var modsEl: HTMLElement
private lateinit var opsEl: HTMLElement
private lateinit var paletteListEl: HTMLElement
private lateinit var saveDotEl: HTMLElement
private lateinit var saveTextEl: HTMLElement
private lateinit var undoBtn: HTMLElement
private lateinit var redoBtn: HTMLElement
private lateinit var projectSel: HTMLSelectElement
private lateinit var screenSel: HTMLSelectElement
private lateinit var exportOverlay: HTMLElement
private lateinit var exportPre: HTMLElement

// ---------------------------------------------------------------------------
// Tree editing core

private fun applyTree(t: WidgetNode, recordUndo: Boolean = true) {
  if (recordUndo) {
    undoStack.addLast(portalTree.value)
    while (undoStack.size > UNDO_CAP) undoStack.removeFirst()
    redoStack.clear()
  }
  portalTree.value = t
  Snapshot.sendApplyNotifications() // DOM callbacks write outside composition — flush
  scheduleSave()
  updateUndoButtons()
}

private fun undo() {
  val prev = undoStack.removeLastOrNull() ?: return
  redoStack.addLast(portalTree.value)
  portalTree.value = prev
  Snapshot.sendApplyNotifications()
  scheduleSave(); updateUndoButtons(); refresh()
}

private fun redo() {
  val next = redoStack.removeLastOrNull() ?: return
  undoStack.addLast(portalTree.value)
  portalTree.value = next
  Snapshot.sendApplyNotifications()
  scheduleSave(); updateUndoButtons(); refresh()
}

private fun updateUndoButtons() {
  if (undoStack.isEmpty()) undoBtn.setAttribute("disabled", "disabled") else undoBtn.removeAttribute("disabled")
  if (redoStack.isEmpty()) redoBtn.setAttribute("disabled", "disabled") else redoBtn.removeAttribute("disabled")
}

private fun scheduleSave() {
  saveDotEl.className = "dot saving"
  saveTextEl.textContent = "Saving…"
  if (saveTimer >= 0) window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout({
    sendBody("$SERVER/draft?project=$currentProject&screen=$currentScreen", "PUT", serializeTree(portalTree.value))
    saveDotEl.className = "dot"
    saveTextEl.textContent = "Saved"
    null
  }, 400)
}

private fun deepCopy(n: WidgetNode): WidgetNode =
  WidgetNode(n.type, n.props, n.children.map { deepCopy(it) }) // fresh ids via default

private fun newNode(type: String): WidgetNode =
  WidgetNode(type, widgetSpec(type)?.sampleProps ?: emptyMap())

private fun addToSelectedOrRoot(node: WidgetNode) {
  val sel = selectedId
  val selType = sel?.let { portalTree.value.findNode(it)?.type }
  val parentId = if (sel != null && selType != null && widgetSpec(selType)?.acceptsChildren == true) {
    sel
  } else {
    portalTree.value.children.firstOrNull()?.id ?: portalTree.value.id
  }
  applyTree(portalTree.value.insertChild(parentId, node, Int.MAX_VALUE))
  selectedId = node.id
  refresh()
}

// ---------------------------------------------------------------------------
// Chrome

fun mountPortalChrome() {
  Ui.installStylesheet()
  buildTopbar()
  buildLeftPane()
  buildCenter()
  buildRightPane()
  buildExportOverlay()
  installKeyboard()
  loadWorkspace()
}

private fun buildTopbar() {
  val bar = Ui.el("div", "topbar")
  bar.appendChild(Ui.el("span", "brand", "keliver portal"))

  projectSel = Ui.select()
  screenSel = Ui.select()
  projectSel.addEventListener("change", { _ -> switchProject(projectSel.value) })
  screenSel.addEventListener("change", { _ -> switchScreen(screenSel.value) })
  bar.appendChild(projectSel)
  bar.appendChild(Ui.button("+", "btn icon") { promptCreate(project = true) })
  bar.appendChild(Ui.el("span", "crumb", "/"))
  bar.appendChild(screenSel)
  bar.appendChild(Ui.button("+", "btn icon") { promptCreate(project = false) })

  bar.appendChild(Ui.el("div", "grow"))

  undoBtn = Ui.button("↩ Undo", "btn icon") { undo() }
  redoBtn = Ui.button("↪ Redo", "btn icon") { redo() }
  bar.appendChild(undoBtn)
  bar.appendChild(redoBtn)

  val preset = Ui.select()
  listOf("Phone" to "390x780", "Tablet" to "768x900", "Web" to "1080x760").forEach { (name, dims) ->
    val o = document.createElement("option") as HTMLOptionElement
    o.value = dims; o.textContent = name
    preset.appendChild(o)
  }
  preset.value = localStorage.getItem("portal.preset") ?: "390x780"
  preset.addEventListener("change", { _ ->
    localStorage.setItem("portal.preset", preset.value)
    window.location.reload() // canvas re-inits at the new size; draft is persisted
  })
  bar.appendChild(preset)

  val savePill = Ui.el("span", "pill")
  saveDotEl = Ui.el("span", "dot")
  saveTextEl = Ui.el("span", "", "Saved")
  savePill.appendChild(saveDotEl)
  savePill.appendChild(saveTextEl)
  bar.appendChild(savePill)

  bar.appendChild(Ui.button("Export Kotlin", "btn primary") { showExport() })
  document.body?.appendChild(bar)
}

private fun buildLeftPane() {
  val pane = Ui.el("div", "pane left")

  pane.appendChild(Ui.section("Palette"))
  val search = Ui.input()
  search.setAttribute("placeholder", "Search ${widgetSpecs.size} widgets…")
  search.setAttribute("style", "width:100%; margin-bottom:6px;")
  search.addEventListener("input", { _ -> renderPalette(search.value) })
  pane.appendChild(search)
  paletteListEl = Ui.el("div", "card")
  paletteListEl.setAttribute("style", "max-height:34vh; overflow:auto;")
  pane.appendChild(paletteListEl)
  renderPalette("")

  pane.appendChild(Ui.section("Outline"))
  outlineEl = Ui.el("div", "card")
  pane.appendChild(outlineEl)

  document.body?.appendChild(pane)
}

/** The container ComposeViewport renders into — sized by the device preset. */
const val PREVIEW_HOST_ID = "PreviewHost"

private fun buildCenter() {
  val center = Ui.el("div", "center")
  val frame = Ui.el("div", "frame")
  val dims = (localStorage.getItem("portal.preset") ?: "390x780").split("x")
  val w = dims.getOrNull(0)?.toIntOrNull() ?: 390
  val h = dims.getOrNull(1)?.toIntOrNull() ?: 780
  frame.setAttribute("style", "width:${w}px; height:${h}px;")
  val host = Ui.el("div")
  host.id = PREVIEW_HOST_ID
  host.setAttribute("style", "width:100%; height:100%;")
  frame.appendChild(host)
  center.appendChild(frame)
  document.body?.appendChild(center)
  // The legacy fixed-size canvas from index.html is unused now.
  (document.getElementById("ComposeTarget") as? HTMLElement)?.setAttribute("style", "display:none;")
}

private fun buildRightPane() {
  val pane = Ui.el("div", "pane right")
  pane.appendChild(Ui.section("Properties"))
  propsEl = Ui.el("div", "card")
  pane.appendChild(propsEl)
  pane.appendChild(Ui.section("Modifiers"))
  modsEl = Ui.el("div", "card")
  pane.appendChild(modsEl)
  pane.appendChild(Ui.section("Node"))
  opsEl = Ui.el("div", "card")
  pane.appendChild(opsEl)
  document.body?.appendChild(pane)
}

private fun buildExportOverlay() {
  exportOverlay = Ui.el("div", "overlay")
  exportOverlay.setAttribute("style", "display:none;")
  val head = Ui.el("div", "head", "Exported Kotlin")
  val close = Ui.button("✕", "btn icon") { exportOverlay.setAttribute("style", "display:none;") }
  close.setAttribute("style", "margin-left:auto;")
  head.appendChild(close)
  exportOverlay.appendChild(head)
  exportPre = Ui.el("pre")
  exportOverlay.appendChild(exportPre)
  document.body?.appendChild(exportOverlay)
}

private fun showExport() {
  exportPre.textContent = exportKotlin(portalTree.value)
  exportOverlay.removeAttribute("style")
}

private fun installKeyboard() {
  document.addEventListener("keydown", { ev ->
    val e = ev as KeyboardEvent
    val mod = e.metaKey || e.ctrlKey
    if (mod && e.key.lowercase() == "z") {
      ev.preventDefault()
      if (e.shiftKey) redo() else undo()
    }
  })
}

// ---------------------------------------------------------------------------
// Workspace (projects / screens / drafts)

private fun loadWorkspace() {
  serverGet("/active") { txt ->
    runCatching {
      val o = Json.parseToJsonElement(txt).jsonObject
      currentProject = o.getValue("project").jsonPrimitive.content
      currentScreen = o.getValue("screen").jsonPrimitive.content
    }
    reloadProjectList()
    reloadScreenList()
    loadDraft()
  }
}

private fun reloadProjectList() {
  serverGet("/projects") { txt ->
    fillSelect(projectSel, parseNames(txt), currentProject)
  }
}

private fun reloadScreenList() {
  serverGet("/screens?project=$currentProject") { txt ->
    fillSelect(screenSel, parseNames(txt), currentScreen)
  }
}

private fun parseNames(txt: String): List<String> =
  runCatching { Json.parseToJsonElement(txt).jsonArray.map { it.jsonPrimitive.content } }
    .getOrDefault(emptyList())

private fun fillSelect(sel: HTMLSelectElement, names: List<String>, current: String) {
  Ui.clear(sel)
  names.forEach { name ->
    val o = document.createElement("option") as HTMLOptionElement
    o.value = name; o.textContent = name
    sel.appendChild(o)
  }
  sel.value = current
}

private fun loadDraft() {
  serverGet("/draft?project=$currentProject&screen=$currentScreen") { txt ->
    val loaded = if (txt.isBlank() || txt.trim() == "{}") null else runCatching { deserializeTree(txt) }.getOrNull()
    val tree = loaded ?: initialTree()
    ensureNodeIdsAbove(tree.maxId())
    undoStack.clear(); redoStack.clear(); selectedId = null
    portalTree.value = tree
    Snapshot.sendApplyNotifications()
    if (loaded == null) scheduleSave() // persist the starter so the device mirrors it
    updateUndoButtons()
    refresh()
  }
}

private fun switchProject(name: String) {
  currentProject = name
  sendEmpty("$SERVER/active?project=$currentProject&screen=main", "POST")
  currentScreen = "main"
  reloadScreenList()
  loadDraft()
}

private fun switchScreen(name: String) {
  currentScreen = name
  sendEmpty("$SERVER/active?project=$currentProject&screen=$currentScreen", "POST")
  loadDraft()
}

private fun promptCreate(project: Boolean) {
  val name = window.prompt(if (project) "New project name:" else "New screen name:")?.trim().orEmpty()
  if (name.isEmpty()) return
  if (project) {
    sendBody("$SERVER/projects", "POST", name)
    window.setTimeout({
      currentProject = name; currentScreen = "main"
      sendEmpty("$SERVER/active?project=$currentProject&screen=main", "POST")
      reloadProjectList(); reloadScreenList(); loadDraft()
      null
    }, 200)
  } else {
    sendBody("$SERVER/screens?project=$currentProject", "POST", name)
    window.setTimeout({
      currentScreen = name
      sendEmpty("$SERVER/active?project=$currentProject&screen=$currentScreen", "POST")
      reloadScreenList(); loadDraft()
      null
    }, 200)
  }
}

// ---------------------------------------------------------------------------
// Panels

private fun refresh() {
  renderOutline(); renderProps(); renderMods(); renderOps()
}

private fun renderPalette(filter: String) {
  Ui.clear(paletteListEl)
  val q = filter.trim().lowercase()
  widgetSpecs.groupBy { it.category }.forEach { (category, specs) ->
    val hits = specs.filter { q.isEmpty() || it.type.lowercase().contains(q) }
    if (hits.isEmpty()) return@forEach
    paletteListEl.appendChild(Ui.el("div", "section", category))
    hits.forEach { spec ->
      val row = Ui.el("div", "pal-row")
      row.appendChild(Ui.el("span", "t", spec.type))
      if (spec.acceptsChildren) row.appendChild(Ui.el("span", "cat", "container"))
      row.setAttribute("draggable", "true")
      row.addEventListener("dragstart", { ev -> (ev as DragEvent).dataTransfer?.setData("text", "new:${spec.type}") })
      row.addEventListener("click", { _ -> addToSelectedOrRoot(newNode(spec.type)) })
      paletteListEl.appendChild(row)
    }
  }
}

private fun renderOutline() {
  Ui.clear(outlineEl)
  fun row(node: WidgetNode, depth: Int) {
    val r = Ui.el("div", "tree-row" + if (node.id == selectedId) " selected" else "")
    r.setAttribute("style", "padding-left:${6 + depth * 14}px;")
    r.appendChild(Ui.el("span", "t", node.type))
    (node.props["text"] as? String)?.let { r.appendChild(Ui.el("span", "hint", "“$it”")) }
    r.addEventListener("click", { _ -> selectedId = node.id; refresh() })
    r.setAttribute("draggable", "true")
    r.addEventListener("dragstart", { ev -> (ev as DragEvent).dataTransfer?.setData("text", "move:${node.id}"); ev.stopPropagation() })
    r.addEventListener("dragover", { ev -> ev.preventDefault(); r.className += " droptarget" })
    r.addEventListener("dragleave", { _ -> r.className = r.className.replace(" droptarget", "") })
    r.addEventListener("drop", { ev ->
      ev.preventDefault()
      r.className = r.className.replace(" droptarget", "")
      handleDrop((ev as DragEvent).dataTransfer?.getData("text"), node.id)
    })
    outlineEl.appendChild(r)
    node.children.forEach { row(it, depth + 1) }
  }
  row(portalTree.value, 0)
}

private fun handleDrop(payload: String?, targetId: Int) {
  if (payload == null) return
  when {
    payload.startsWith("new:") -> {
      applyTree(portalTree.value.insertChild(targetId, newNode(payload.removePrefix("new:")), Int.MAX_VALUE))
      refresh()
    }
    payload.startsWith("move:") -> {
      val id = payload.removePrefix("move:").toIntOrNull() ?: return
      applyTree(portalTree.value.moveNode(id, targetId, Int.MAX_VALUE))
      refresh()
    }
  }
}

private fun renderProps() {
  Ui.clear(propsEl)
  val node = selectedId?.let { portalTree.value.findNode(it) }
  if (node == null) {
    propsEl.appendChild(Ui.el("div", "muted", "Select a node in the outline"))
    return
  }
  propsEl.appendChild(Ui.el("div", "muted", "${node.type} #${node.id}"))
  val specs = editableProps(node.type)
  if (specs.isEmpty()) propsEl.appendChild(Ui.el("div", "muted", "No editable properties"))
  specs.forEach { spec ->
    propsEl.appendChild(propRow(node.id, node.props, spec.name, spec.kind, spec.label, keyPrefix = ""))
  }
}

/** One editable property row; [keyPrefix] namespaces modifier props ("mod.X."). */
private fun propRow(nodeId: Int, props: Map<String, Any?>, name: String, kind: PropKind, label: String, keyPrefix: String): HTMLElement {
  val key = keyPrefix + name
  val rowEl = Ui.el("div", "row")
  val lab = Ui.el("label", "", label)
  lab.setAttribute("title", label)
  rowEl.appendChild(lab)
  val input = Ui.input()
  when (kind) {
    PropKind.Text -> {
      input.setAttribute("type", "text")
      input.value = props[key] as? String ?: ""
      input.addEventListener("input", { _ -> editProp(nodeId, key, input.value) })
    }
    PropKind.Int -> {
      input.setAttribute("type", "number")
      input.value = (props[key] as? Int ?: 0).toString()
      input.addEventListener("input", { _ -> editProp(nodeId, key, input.value.toIntOrNull() ?: 0) })
    }
    PropKind.Double -> {
      input.setAttribute("type", "number")
      input.setAttribute("step", "0.1")
      input.value = (props[key] as? Double ?: 0.0).toString()
      input.addEventListener("input", { _ -> editProp(nodeId, key, input.value.toDoubleOrNull() ?: 0.0) })
    }
    PropKind.Bool -> {
      input.setAttribute("type", "checkbox")
      input.className = ""
      if (props[key] as? Boolean == true) input.setAttribute("checked", "checked")
      input.addEventListener("change", { _ -> editProp(nodeId, key, input.checked) })
    }
    PropKind.Color -> {
      input.setAttribute("type", "color")
      input.setAttribute("style", "padding:1px; height:26px;")
      input.value = argbToHex(props[key] as? Int ?: 0xFF000000.toInt())
      input.addEventListener("input", { _ -> editProp(nodeId, key, hexToArgb(input.value)) })
    }
    PropKind.IntList, PropKind.FloatList -> {
      input.setAttribute("type", "text")
      input.setAttribute("disabled", "disabled")
      input.value = (props[key] as? List<*>)?.joinToString(", ") ?: "(list)"
    }
  }
  rowEl.appendChild(input)
  return rowEl
}

private fun renderMods() {
  Ui.clear(modsEl)
  val node = selectedId?.let { portalTree.value.findNode(it) }
  if (node == null) {
    modsEl.appendChild(Ui.el("div", "muted", "—"))
    return
  }

  // Modifiers currently on the node (any prop key "mod.<Name>" or "mod.<Name>.<prop>").
  val onNode = node.props.keys.filter { it.startsWith("mod.") }
    .map { it.removePrefix("mod.").substringBefore('.') }.distinct().sorted()

  onNode.forEach { name ->
    val spec = modifierSpecs.firstOrNull { it.name == name }
    val chip = Ui.el("div", "mod-chip")
    val head = Ui.el("div", "mod-head")
    head.appendChild(Ui.el("span", "t", name))
    val x = Ui.el("span", "x", "✕")
    x.addEventListener("click", { _ ->
      val cur = portalTree.value.findNode(node.id) ?: return@addEventListener
      val cleaned = cur.props.filterKeys { !(it == "mod.$name" || it.startsWith("mod.$name.")) }
      applyTree(portalTree.value.updateProps(node.id, cleaned))
      refresh()
    })
    head.appendChild(x)
    chip.appendChild(head)
    spec?.props?.forEach { p ->
      chip.appendChild(propRow(node.id, node.props, p.name, p.kind, p.label, keyPrefix = "mod.$name."))
    }
    modsEl.appendChild(chip)
  }

  // Add-modifier control.
  val addRow = Ui.el("div", "row")
  val sel = Ui.select()
  val available = modifierSpecs.map { it.name }.filter { it !in onNode }
  available.forEach { name ->
    val o = document.createElement("option") as HTMLOptionElement
    o.value = name; o.textContent = name
    sel.appendChild(o)
  }
  addRow.appendChild(sel)
  addRow.appendChild(
    Ui.button("Add", "btn") {
      val name = sel.value.ifEmpty { return@button }
      val spec = modifierSpecs.firstOrNull { it.name == name } ?: return@button
      val cur = portalTree.value.findNode(node.id) ?: return@button
      val newProps = if (spec.props.isEmpty()) {
        mapOf("mod.$name" to true)
      } else {
        spec.props.associate { p ->
          "mod.$name.${p.name}" to when (p.kind) {
            PropKind.Int -> 8
            PropKind.Double -> 8.0
            PropKind.Bool -> true
            PropKind.Color -> 0xFF8B7CF7.toInt()
            else -> ""
          }
        }
      }
      applyTree(portalTree.value.updateProps(node.id, cur.props + newProps))
      refresh()
    },
  )
  modsEl.appendChild(addRow)
}

private fun renderOps() {
  Ui.clear(opsEl)
  val node = selectedId?.let { portalTree.value.findNode(it) }
  if (node == null) {
    opsEl.appendChild(Ui.el("div", "muted", "—"))
    return
  }
  val row = Ui.el("div", "row")
  row.appendChild(
    Ui.button("Duplicate", "btn") {
      val copy = deepCopy(node)
      // insert as a sibling: find the parent by scanning
      val parentId = findParentId(portalTree.value, node.id) ?: portalTree.value.id
      applyTree(portalTree.value.insertChild(parentId, copy, Int.MAX_VALUE))
      selectedId = copy.id
      refresh()
    },
  )
  row.appendChild(
    Ui.button("Delete", "btn danger") {
      if (node.id == portalTree.value.id) return@button // never delete the root
      applyTree(portalTree.value.removeNode(node.id))
      selectedId = null
      refresh()
    },
  )
  opsEl.appendChild(row)
}

private fun findParentId(root: WidgetNode, childId: Int): Int? {
  if (root.children.any { it.id == childId }) return root.id
  root.children.forEach { c -> findParentId(c, childId)?.let { return it } }
  return null
}

private fun editProp(id: Int, name: String, value: Any?) {
  val cur = portalTree.value.findNode(id) ?: return
  applyTree(portalTree.value.updateProps(id, cur.props + (name to value)))
  // no refresh(): keeps input focus while typing; the preview updates live.
}

private fun argbToHex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')
private fun hexToArgb(hex: String): Int = (0xFF shl 24) or (hex.removePrefix("#").toIntOrNull(16) ?: 0)
