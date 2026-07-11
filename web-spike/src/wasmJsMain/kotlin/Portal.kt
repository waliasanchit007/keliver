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
import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.PropKind
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.collectContract
import dev.keliver.portal.document.DocJson
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.OpAck
import dev.keliver.portal.document.OpBatch
import dev.keliver.portal.document.OpEnvelope
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.toDocNode
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.editableProps
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.findNode
import dev.keliver.portal.modifierSpecs
import dev.keliver.portal.kotlinTypeOf
import dev.keliver.portal.render.PreviewBindings
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
import org.w3c.dom.EventSource
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

// V2 M1: the SERVER owns the document; the editor is an op-emitting client.
// portalTree is a projection with WidgetNode.id == document HANDLE.
private var docVersion: Long = -1
private var eventSource: EventSource? = null
private val opQueue = ArrayDeque<Pair<List<DocOp>, Boolean>>() // ops to refreshPanels
private var opInFlight = false

private const val SERVER = "http://localhost:8077"
private const val SESSION = "editor"

// ── Playground mode: no portal-server reachable (e.g. the GitHub Pages build).
// The SAME UiDocument apply/invert engine runs locally; edits stay in-memory.
private var playground = false
private var localDoc = demoTemplate()
private val localUndo = ArrayDeque<List<DocOp>>()
private val localRedo = ArrayDeque<List<DocOp>>()

private fun w(
  h: Long,
  type: String,
  props: Map<String, PropValue> = emptyMap(),
  children: List<dev.keliver.portal.document.DocNode> = emptyList(),
): dev.keliver.portal.document.DocNode.Widget =
  dev.keliver.portal.document.DocNode.Widget(Handle(h), type, props, children = children)

private fun s(v: String) = PropValue.Lit("s", s = v)
private fun i(v: Int) = PropValue.Lit("i", i = v)

/** The first thing a playground visitor sees: binds, mock rows, actions, Icon/ListItem. */
private fun demoTemplate(): UiDocument = UiDocument(
  "playground",
  w(1, "Column", children = listOf(
    w(2, "StyledText", mapOf("text" to s("Field Notes"), "fontSize" to i(26), "bold" to PropValue.Lit("b", b = true))),
    w(3, "StyledText", mapOf("text" to PropValue.Bind("subtitle"), "fontSize" to i(13))),
    w(4, "TextField", mapOf(
      "text" to PropValue.Bind("draft"),
      "placeholder" to s("What did you notice?"),
      "onValueChange" to PropValue.Action("onDraftChange", arg = "it"),
    )),
    w(5, "Button", mapOf("text" to s("Add note"), "onClick" to PropValue.Action("addNote"))),
    w(6, "Repeat", mapOf("items" to s("notes"), "item" to s("note")), children = listOf(
      w(7, "ListItem", mapOf(
        "headline" to PropValue.Bind("note.title"),
        "supporting" to PropValue.Bind("note.body"),
        "leadingIcon" to s("Star"),
        "onClick" to PropValue.Action("openNote", arg = "note.id"),
      )),
    )),
  )),
  dev.keliver.portal.document.Contract(),
  version = 0,
  nextHandle = 20,
)

private fun formTemplate(): UiDocument = UiDocument(
  "playground",
  w(1, "Column", children = listOf(
    w(2, "StyledText", mapOf("text" to s("Create account"), "fontSize" to i(24), "bold" to PropValue.Lit("b", b = true))),
    w(3, "TextField", mapOf("text" to PropValue.Bind("email"), "placeholder" to s("Email"), "onValueChange" to PropValue.Action("onEmail", arg = "it"))),
    w(4, "SegmentedButtonRow", mapOf(
      "options" to PropValue.Lit("ls", ls = listOf("Free", "Pro", "Team")),
      "selectedIndex" to i(0),
      "onSelect" to PropValue.Action("onPlan", arg = "it"),
    )),
    w(5, "Checkbox", mapOf("checked" to PropValue.Lit("b", b = true))),
    w(6, "Button", mapOf("text" to s("Sign up"), "onClick" to PropValue.Action("submit"))),
  )),
  dev.keliver.portal.document.Contract(),
  version = 0,
  nextHandle = 20,
)

private fun profileTemplate(): UiDocument = UiDocument(
  "playground",
  w(1, "Column", children = listOf(
    w(2, "Icon", mapOf("name" to s("AccountCircle"), "sizeDp" to i(64))),
    w(3, "StyledText", mapOf("text" to PropValue.Bind("displayName"), "fontSize" to i(22), "bold" to PropValue.Lit("b", b = true))),
    w(4, "Divider", emptyMap()),
    w(5, "ListItem", mapOf("headline" to s("Notifications"), "leadingIcon" to s("Notifications"), "trailingIcon" to s("KeyboardArrowRight"))),
    w(6, "ListItem", mapOf("headline" to s("Settings"), "leadingIcon" to s("Settings"), "trailingIcon" to s("KeyboardArrowRight"))),
    w(7, "ListItem", mapOf("headline" to s("Sign out"), "leadingIcon" to s("ExitToApp"), "onClick" to PropValue.Action("signOut"))),
  )),
  dev.keliver.portal.document.Contract(),
  version = 0,
  nextHandle = 20,
)

private fun loadTemplate(doc: UiDocument, mocks: Map<String, String>) {
  localDoc = doc
  localUndo.clear()
  localRedo.clear()
  PreviewBindings.mocks.clear()
  PreviewBindings.mocks.putAll(mocks)
  localRefresh(true)
}

private val demoMocks = mapOf(
  "subtitle" to "3 notes · persisted in SQLite, shipped OTA",
  "notes" to "3",
  "note.title" to "Trail by the lake|Morning espresso|Cloud shapes",
  "note.body" to "Saw two herons near the dock|Second cup was a mistake|One looked exactly like a whale",
)

private fun localRefresh(refreshPanels: Boolean) {
  docVersion = localDoc.version
  portalTree.value = localDoc.toWidgetTree(handleIds = true)
  Snapshot.sendApplyNotifications()
  if (refreshPanels) refresh()
}

private fun enterPlayground() {
  if (playground) return
  playground = true
  saveTextEl.textContent = "playground — edits are local to this tab"
  PreviewBindings.mocks.putAll(demoMocks)
  mountPlaygroundStrip()
  localRefresh(true)
}

/** The onboarding strip: what to try + one-click starter templates. */
private fun mountPlaygroundStrip() {
  val strip = Ui.el("div", "")
  strip.setAttribute(
    "style",
    "position:fixed; left:0; right:0; top:46px; z-index:29; display:flex; align-items:center; gap:10px; " +
      "padding:6px 14px; background:#2a2436; border-bottom:1px solid var(--border); font-size:12px;",
  )
  strip.appendChild(Ui.el("span", "", "Playground — try: ① drag widgets from the palette  ② bind props with @ (mocks drive the preview)  ③ Export Kotlin: it's real code."))
  strip.appendChild(Ui.el("span", "grow"))
  strip.appendChild(Ui.el("span", "muted", "Templates:"))
  strip.appendChild(Ui.button("Feed", "btn") { loadTemplate(demoTemplate(), demoMocks) })
  strip.appendChild(Ui.button("Form", "btn") { loadTemplate(formTemplate(), mapOf("email" to "ada@lovelace.dev")) })
  strip.appendChild(Ui.button("Profile", "btn") { loadTemplate(profileTemplate(), mapOf("displayName" to "Ada Lovelace")) })
  strip.appendChild(Ui.button("✕", "btn icon") { strip.remove() })
  kotlinx.browser.document.body?.appendChild(strip)
}

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
private lateinit var bindingsEl: HTMLElement
private lateinit var consoleEl: HTMLElement
private lateinit var fidelityEl: HTMLElement
private lateinit var inspectorEl: HTMLElement
private lateinit var liveBtn: HTMLElement
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
// V2 M1 ops client — every edit is a transactional op batch against the
// server document; the tree state here is only a projection of the ack'd doc.

private fun sendOps(ops: List<DocOp>, refreshPanels: Boolean = true) {
  opQueue.addLast(ops to refreshPanels)
  pumpOps()
}

private fun pumpOps() {
  if (playground) {
    while (true) {
      val (ops, refreshPanels) = opQueue.removeFirstOrNull() ?: break
      val t = localDoc.applyBatch(ops)
      val r = t.result
      if (r == null) {
        Ui.toast(t.error ?: "edit rejected")
      } else {
        localDoc = r.doc.copy(version = localDoc.version + 1)
        localUndo.addLast(r.inverseBatch)
        localRedo.clear()
      }
      localRefresh(refreshPanels)
    }
    return
  }
  if (opInFlight) return
  val (ops, refreshPanels) = opQueue.removeFirstOrNull() ?: return
  opInFlight = true
  saveDotEl.className = "dot saving"
  saveTextEl.textContent = "Saving…"
  val batch = OpBatch(docVersion, OpEnvelope(SESSION, 0), ops)
  val xhr = XMLHttpRequest()
  xhr.open("POST", "$SERVER/ops?project=$currentProject&screen=$currentScreen")
  xhr.setRequestHeader("X-Portal-Session", SESSION)
  xhr.addEventListener("load", { _ ->
    opInFlight = false
    val ack = runCatching { DocJson.decodeFromString<OpAck>(xhr.responseText) }.getOrNull()
    when {
      ack?.ok == true -> {
        docVersion = ack.version
        refetchDoc(refreshPanels)
        pumpOps()
      }
      xhr.status.toInt() == 409 -> { // raced an external edit — rebase on server state
        opQueue.clear()
        Ui.toast("Rebased on newer document")
        refetchDoc(true)
      }
      else -> {
        opQueue.clear()
        Ui.toast(ack?.error ?: "edit rejected")
        refetchDoc(true)
      }
    }
    saveDotEl.className = "dot"
    saveTextEl.textContent = "Saved"
  })
  xhr.send(DocJson.encodeToString(batch))
}

/** Pull the authoritative document; ids in the projected tree ARE handles. */
private fun refetchDoc(refreshPanels: Boolean = true, cb: (() -> Unit)? = null) {
  if (playground) {
    localRefresh(refreshPanels)
    cb?.invoke()
    return
  }
  val xhr = XMLHttpRequest()
  xhr.open("GET", "$SERVER/doc?project=$currentProject&screen=$currentScreen")
  xhr.addEventListener("load", { _ ->
    runCatching { DocJson.decodeFromString<UiDocument>(xhr.responseText) }.onSuccess { doc ->
      docVersion = doc.version
      portalTree.value = doc.toWidgetTree(handleIds = true)
      Snapshot.sendApplyNotifications()
      if (refreshPanels) refresh()
      cb?.invoke()
    }
  })
  // No server ever reached (fresh load, connection refused) -> playground.
  xhr.addEventListener("error", { _ -> if (docVersion == -1L) enterPlayground() })
  xhr.send()
}

private fun subscribeDocEvents() {
  if (playground) return
  eventSource?.close()
  val project = currentProject
  val screen = currentScreen
  eventSource = EventSource("$SERVER/doc-events?project=$project&screen=$screen").also { es ->
    es.onmessage = { ev ->
      val v = (ev.data as? String)?.let { Regex("\"version\":(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
      if (v != null && v != docVersion) refetchDoc(true) // another session/editor changed the doc
    }
    es.onerror = { _ ->
      // Server restart kills the stream; recreate after a beat (fresh subscribe).
      if (project == currentProject && screen == currentScreen) {
        window.setTimeout({ if (eventSource == es) subscribeDocEvents(); null }, 2000)
      }
      null
    }
  }
  startVersionPoll()
}

// Belt and braces: a cheap version poll catches anything SSE misses.
private var versionPollStarted = false
private fun startVersionPoll() {
  if (playground || versionPollStarted) return
  versionPollStarted = true
  window.setInterval({
    if (!opInFlight && opQueue.isEmpty()) {
      val xhr = XMLHttpRequest()
      xhr.open("GET", "$SERVER/doc?project=$currentProject&screen=$currentScreen")
      xhr.addEventListener("load", { _ ->
        val v = Regex("\"version\":(\\d+)").find(xhr.responseText)?.groupValues?.get(1)?.toLongOrNull()
        if (v != null && v != docVersion) refetchDoc(true)
      })
      xhr.send()
    }
    null
  }, 5000)
}

private fun undo() {
  if (playground) {
    val inv = localUndo.removeLastOrNull() ?: return
    localDoc.applyBatch(inv).result?.let { r ->
      localDoc = r.doc.copy(version = localDoc.version + 1)
      localRedo.addLast(r.inverseBatch)
    }
    localRefresh(true)
    return
  }
  serverPost("/undo") { refetchDoc(true) }
}

private fun redo() {
  if (playground) {
    val inv = localRedo.removeLastOrNull() ?: return
    localDoc.applyBatch(inv).result?.let { r ->
      localDoc = r.doc.copy(version = localDoc.version + 1)
      localUndo.addLast(r.inverseBatch)
    }
    localRefresh(true)
    return
  }
  serverPost("/redo") { refetchDoc(true) }
}

private fun serverPost(path: String, cb: () -> Unit) {
  val xhr = XMLHttpRequest()
  xhr.open("POST", "$SERVER$path?project=$currentProject&screen=$currentScreen")
  xhr.setRequestHeader("X-Portal-Session", SESSION)
  xhr.addEventListener("load", { _ -> cb() })
  xhr.send()
}

private fun updateUndoButtons() {
  // Server-side stacks: buttons stay enabled; the server acks "nothing to undo".
  undoBtn.removeAttribute("disabled")
  redoBtn.removeAttribute("disabled")
}

/** Anchor for append-to-end inserts: the parent's last child handle (excluding [excludeId]). */
private fun lastChildHandle(parentId: Int, excludeId: Int? = null): Handle? =
  portalTree.value.findNode(parentId)?.children?.lastOrNull { it.id != excludeId }?.let { Handle(it.id.toLong()) }

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
  sendOps(listOf(DocOp.InsertNode(Handle(parentId.toLong()), lastChildHandle(parentId), node.toDocNode())))
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

  liveBtn = Ui.button("▶ Live", "btn") { toggleLive() }
  bar.appendChild(liveBtn)

  bar.appendChild(Ui.button("Export Kotlin", "btn") { showExport() })
  bar.appendChild(
    Ui.button("Publish", "btn primary") {
      exportPre.textContent = "Publishing… (compiles + signs the bundle, ~1-2 min)"
      exportOverlay.removeAttribute("style")
      val xhr = XMLHttpRequest()
      xhr.open("POST", "$SERVER/publish")
      xhr.addEventListener("load", { _ ->
        exportPre.textContent = (if (xhr.status.toInt() == 200) "✅ " else "❌ ") + xhr.responseText
      })
      xhr.send()
    },
  )
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
  pane.appendChild(Ui.section("Bindings"))
  bindingsEl = Ui.el("div", "card")
  pane.appendChild(bindingsEl)
  pane.appendChild(Ui.section("Preview fidelity"))
  fidelityEl = Ui.el("div", "card")
  fidelityEl.appendChild(Ui.el("div", "muted", "Mock mode — press ▶ Live to run real logic"))
  pane.appendChild(fidelityEl)
  pane.appendChild(Ui.section("State inspector"))
  inspectorEl = Ui.el("div", "card")
  inspectorEl.setAttribute("style", "font-size:11px;")
  inspectorEl.appendChild(Ui.el("div", "muted", "—"))
  pane.appendChild(inspectorEl)
  pane.appendChild(Ui.section("Action console"))
  consoleEl = Ui.el("div", "card")
  consoleEl.setAttribute("style", "max-height:130px; overflow:auto; font-size:11px;")
  consoleEl.appendChild(Ui.el("div", "muted", "Tap a wired widget in the preview…"))
  pane.appendChild(consoleEl)
  installMockActionSink()
  document.body?.appendChild(pane)
}

/** Mock mode: actions just log to the console (no logic runs). */
private fun installMockActionSink() {
  PreviewBindings.actionSink = { name ->
    val row = Ui.el("div", "", "⚡ $name")
    row.setAttribute("style", "color:var(--good);")
    consoleEl.insertBefore(row, consoleEl.firstChild)
  }
}

// ── M8: capability-driven live preview ──────────────────────────────────────

private fun toggleLive() {
  if (LivePresenter.enabled) disableLive() else enableLive()
}

private fun enableLive() {
  serverGet("/capabilities?project=$currentProject") { txt ->
    val required = parseNames(txt)
    renderFidelity(required)
    val fields = collectContract(portalTree.value).fields.keys.toList()
    LivePresenter.enable(fields) { renderInspector() }
    // Keep the console logging AND run the presenter transition.
    PreviewBindings.actionSink = { name ->
      val row = Ui.el("div", "", "⚡ $name → live logic")
      row.setAttribute("style", "color:var(--good);")
      consoleEl.insertBefore(row, consoleEl.firstChild)
      LivePresenter.fireLive(name)
      renderInspector()
      Snapshot.sendApplyNotifications()
    }
    liveBtn.textContent = "■ Stop"
    liveBtn.className = "btn primary"
    renderInspector()
    Snapshot.sendApplyNotifications()
  }
}

private fun disableLive() {
  LivePresenter.disable()
  PreviewBindings.mocks.clear()
  installMockActionSink()
  liveBtn.textContent = "▶ Live"
  liveBtn.className = "btn"
  fidelityEl.let { Ui.clear(it); it.appendChild(Ui.el("div", "muted", "Mock mode — press ▶ Live to run real logic")) }
  inspectorEl.let { Ui.clear(it); it.appendChild(Ui.el("div", "muted", "—")) }
  refresh()
  Snapshot.sendApplyNotifications()
}

private fun renderFidelity(required: List<String>) {
  Ui.clear(fidelityEl)
  if (required.isEmpty()) {
    fidelityEl.appendChild(Ui.el("div", "", "✅ Full fidelity — no host capabilities required"))
    return
  }
  val report = PreviewCapabilities.report(required)
  val full = report.all { it.real }
  val head = Ui.el("div", "", if (full) "✅ Full fidelity" else "⚠ Reduced fidelity")
  head.setAttribute("style", "color:" + (if (full) "var(--good)" else "var(--warn, #e0a030)") + "; font-weight:600; margin-bottom:4px;")
  fidelityEl.appendChild(head)
  report.forEach { c ->
    val row = Ui.el("div", "", (if (c.real) "● " else "○ ") + c.name + " — " + c.note)
    row.setAttribute("style", "color:" + (if (c.real) "var(--good)" else "var(--warn, #e0a030)") + ";")
    fidelityEl.appendChild(row)
  }
}

private fun renderInspector() {
  Ui.clear(inspectorEl)
  val state = LivePresenter.stateSnapshot()
  if (state.isEmpty()) { inspectorEl.appendChild(Ui.el("div", "muted", "no live bindings")); return }
  state.forEach { (k, v) ->
    inspectorEl.appendChild(Ui.el("div", "", "$k = \"$v\""))
  }
  // Fire wired actions from here too (DOM-reliable; complements canvas taps).
  val contract = collectContract(portalTree.value)
  if (contract.actions.isNotEmpty()) {
    val row = Ui.el("div", "row")
    row.setAttribute("style", "margin-top:6px; flex-wrap:wrap; gap:4px;")
    contract.actions.forEach { action ->
      row.appendChild(
        Ui.button("⚡ $action", "btn icon") {
          val log = Ui.el("div", "", "⚡ $action → live logic")
          log.setAttribute("style", "color:var(--good);")
          consoleEl.insertBefore(log, consoleEl.firstChild)
          LivePresenter.fireLive(action)
          renderInspector()
          Snapshot.sendApplyNotifications()
        },
      )
    }
    inspectorEl.appendChild(row)
  }
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
  // V2 M1: the server document is the truth — fetch it and subscribe to changes.
  selectedId = null
  opQueue.clear()
  refetchDoc(true) { subscribeDocEvents() }
  updateUndoButtons()
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
  renderOutline(); renderProps(); renderMods(); renderOps(); renderBindings()
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
    payload.startsWith("new:") -> sendOps(listOf(
      DocOp.InsertNode(Handle(targetId.toLong()), lastChildHandle(targetId), newNode(payload.removePrefix("new:")).toDocNode()),
    ))
    payload.startsWith("move:") -> {
      val id = payload.removePrefix("move:").toIntOrNull() ?: return
      sendOps(listOf(
        DocOp.MoveNode(Handle(id.toLong()), Handle(targetId.toLong()), lastChildHandle(targetId, excludeId = id)),
      ))
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
  val spec = widgetSpec(node.type)
  val specs = editableProps(node.type)
  if (specs.isEmpty()) propsEl.appendChild(Ui.el("div", "muted", "No editable properties"))
  specs.forEach { s ->
    propsEl.appendChild(propRow(node.id, node.props, s.name, s.kind, s.label, keyPrefix = ""))
  }
  // P3: events — each wires to a named Action.
  val events = spec?.events ?: emptyList()
  if (events.isNotEmpty()) {
    propsEl.appendChild(Ui.el("div", "section", "Events"))
    events.forEach { evName ->
      val rowEl = Ui.el("div", "row")
      val lab = Ui.el("label", "", evName)
      lab.setAttribute("title", evName)
      rowEl.appendChild(lab)
      val current = node.props[evName] as? Action
      val nameIn = Ui.input()
      nameIn.setAttribute("type", "text")
      nameIn.setAttribute("placeholder", "action name")
      nameIn.value = current?.name ?: ""
      // P2: the action's single arg — "it" (event payload) or "item.field".
      val argIn = Ui.input()
      argIn.setAttribute("type", "text")
      argIn.setAttribute("placeholder", "arg")
      argIn.setAttribute("title", "arg: it (event payload) or item.field (inside a Repeat)")
      argIn.setAttribute("style", "flex: 0 1 90px;")
      argIn.value = current?.arg ?: ""
      fun sendNow() {
        val name = nameIn.value.trim()
        val op = if (name.isEmpty()) {
          DocOp.RemoveProp(Handle(node.id.toLong()), evName)
        } else {
          DocOp.SetProp(Handle(node.id.toLong()), evName, PropValue.Action(name, argIn.value.trim().ifEmpty { null }))
        }
        sendOps(listOf(op), refreshPanels = false)
        renderBindings()
      }
      nameIn.addEventListener("input", { _ -> sendNow() })
      argIn.addEventListener("input", { _ -> sendNow() })
      rowEl.appendChild(nameIn)
      rowEl.appendChild(argIn)
      propsEl.appendChild(rowEl)
    }
  }
}

/** The derived contract + mock inputs, and everything bound across the screen. */
private fun renderBindings() {
  Ui.clear(bindingsEl)
  val contract = collectContract(portalTree.value)
  if (contract.isEmpty) {
    bindingsEl.appendChild(Ui.el("div", "muted", "Bind a prop (@) or wire an event to build the contract"))
    return
  }
  contract.fields.forEach { (field, kind) ->
    val rowEl = Ui.el("div", "row")
    val lab = Ui.el("label", "", "$field: ${kotlinTypeOf(kind)}")
    lab.setAttribute("title", field)
    rowEl.appendChild(lab)
    val mock = Ui.input()
    mock.setAttribute("type", "text")
    // P2: dotted fields are item-scoped — the mock holds per-row values.
    mock.setAttribute("placeholder", if ('.' in field) "rows: a|b|c" else "mock value")
    mock.value = PreviewBindings.mocks[field] ?: ""
    mock.addEventListener("input", { _ ->
      PreviewBindings.mocks[field] = mock.value
      Snapshot.sendApplyNotifications()
    })
    rowEl.appendChild(mock)
    bindingsEl.appendChild(rowEl)
  }
  // P2: each Repeat's items field mocks its preview ROW COUNT.
  val reps = mutableListOf<WidgetNode>()
  fun collectRepeats(n: WidgetNode) {
    if (n.type == "Repeat") reps += n
    n.children.forEach { collectRepeats(it) }
  }
  collectRepeats(portalTree.value)
  reps.forEach { rep ->
    val items = (rep.props["items"] as? String)?.takeIf { it.isNotBlank() } ?: return@forEach
    val rowEl = Ui.el("div", "row")
    rowEl.appendChild(Ui.el("label", "", "$items: rows"))
    val mock = Ui.input()
    mock.setAttribute("type", "text")
    mock.setAttribute("placeholder", "row count (3)")
    mock.value = PreviewBindings.mocks[items] ?: ""
    mock.addEventListener("input", { _ ->
      PreviewBindings.mocks[items] = mock.value
      Snapshot.sendApplyNotifications()
    })
    rowEl.appendChild(mock)
    bindingsEl.appendChild(rowEl)
  }
  contract.actions.forEach { a ->
    val p = contract.actionParams[a]
    bindingsEl.appendChild(Ui.el("div", "muted", if (p != null) "fun $a(value: $p)" else "fun $a()"))
  }
}

private val BINDABLE = setOf(PropKind.Text, PropKind.Int, PropKind.Bool, PropKind.Double, PropKind.Color)

/** One editable property row; [keyPrefix] namespaces modifier props ("mod.X."). */
private fun propRow(nodeId: Int, props: Map<String, Any?>, name: String, kind: PropKind, label: String, keyPrefix: String): HTMLElement {
  val key = keyPrefix + name
  val rowEl = Ui.el("div", "row")
  val lab = Ui.el("label", "", label)
  lab.setAttribute("title", label)
  rowEl.appendChild(lab)

  // P3: bound props show a field-name input instead of a literal editor.
  val bound = props[key] as? Bind
  if (bound != null) {
    val fieldInput = Ui.input()
    fieldInput.setAttribute("type", "text")
    fieldInput.setAttribute("style", "border-color:var(--accent); color:var(--accent);")
    fieldInput.value = bound.field
    fieldInput.addEventListener("input", { _ ->
      sendOps(listOf(DocOp.SetProp(Handle(nodeId.toLong()), key, PropValue.Bind(fieldInput.value.trim()))), refreshPanels = false)
      renderBindings()
    })
    rowEl.appendChild(fieldInput)
    rowEl.appendChild(
      Ui.button("@", "btn icon") {
        sendOps(listOf(DocOp.RemoveProp(Handle(nodeId.toLong()), key))) // back to literal default
      }.also { it.setAttribute("style", "border-color:var(--accent); color:var(--accent);") },
    )
    return rowEl
  }

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
    PropKind.StringList -> {
      // Editable string lists (Dropdown/SegmentedButton options): pipe-separated.
      input.setAttribute("type", "text")
      input.setAttribute("placeholder", "options: a|b|c")
      input.value = (props[key] as? List<*>)?.joinToString("|") ?: ""
      input.addEventListener("input", { _ ->
        val items = input.value.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        sendOps(listOf(DocOp.SetProp(Handle(nodeId.toLong()), key, PropValue.Lit("ls", ls = items))), refreshPanels = false)
      })
    }
  }
  rowEl.appendChild(input)
  if (kind in BINDABLE && keyPrefix.isEmpty()) {
    rowEl.appendChild(
      Ui.button("@", "btn icon") {
        sendOps(listOf(DocOp.SetProp(Handle(nodeId.toLong()), key, PropValue.Bind(name))))
      },
    )
  }
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
      val keys = node.props.keys.filter { it == "mod.$name" || it.startsWith("mod.$name.") }
      sendOps(keys.map { DocOp.RemoveModifier(Handle(node.id.toLong()), it.removePrefix("mod.")) })
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
      val defaults: Map<String, Any?> = if (spec.props.isEmpty()) {
        mapOf(name to true)
      } else {
        spec.props.associate { p ->
          "$name.${p.name}" to when (p.kind) {
            PropKind.Int -> 8
            PropKind.Double -> 8.0
            PropKind.Bool -> true
            PropKind.Color -> 0xFF8B7CF7.toInt()
            else -> ""
          }
        }
      }
      sendOps(defaults.map { (k, v) -> DocOp.SetModifier(Handle(node.id.toLong()), k, lit(v)) })
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
      val parentId = findParentId(portalTree.value, node.id) ?: portalTree.value.id
      sendOps(listOf(
        DocOp.InsertNode(Handle(parentId.toLong()), Handle(node.id.toLong()), deepCopy(node).toDocNode()),
      ))
    },
  )
  row.appendChild(
    Ui.button("Delete", "btn danger") {
      if (node.id == portalTree.value.id) return@button // never delete the root
      selectedId = null
      sendOps(listOf(DocOp.DeleteNode(Handle(node.id.toLong()))))
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
  val op = if (name.startsWith("mod.")) {
    DocOp.SetModifier(Handle(id.toLong()), name.removePrefix("mod."), lit(value))
  } else {
    DocOp.SetProp(Handle(id.toLong()), name, lit(value))
  }
  // refreshPanels=false: keeps input focus while typing; the preview updates live.
  sendOps(listOf(op), refreshPanels = false)
}

private fun argbToHex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')
private fun hexToArgb(hex: String): Int = (0xFF shl 24) or (hex.removePrefix("#").toIntOrNull(16) ?: 0)
