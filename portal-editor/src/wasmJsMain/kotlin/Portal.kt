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
import dev.keliver.portal.Conditional
import dev.keliver.portal.condOf
import dev.keliver.portal.ELSE_TYPE
import dev.keliver.portal.PropKind
import dev.keliver.portal.ListOf
import dev.keliver.portal.Template
import dev.keliver.portal.Token
import dev.keliver.portal.tokenValue
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.collectContract
import dev.keliver.portal.collectPreviewBindingKeys
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
import dev.keliver.portal.acceptsModifier
import dev.keliver.portal.isDpProp
import dev.keliver.portal.isValidUnder
import dev.keliver.portal.modifierSpecs
import dev.keliver.portal.kotlinTypeOf
import dev.keliver.portal.render.PreviewBindings
import dev.keliver.portal.render.resolvePersona
import dev.keliver.protocol.guest.guestRedwoodVersion
import dev.keliver.portal.widgetSpec
import dev.keliver.portal.widgetSpecs
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
internal val portalTree = mutableStateOf(initialTree())

private var selectedId: Int? = null
internal var currentProject = "default"
private var currentScreen = "main"
  set(v) {
    field = v
    openComponents = emptyList() // E14: another screen is edited from its own root
  }

/** E14: the components opened from the screen, in turn (empty = the screen itself is edited). */
private var openComponents: List<String> = emptyList()

/** E14: the selection at each crumb when the next one was opened — selected again on the way back. */
private var crumbSelections: List<Int?> = emptyList()

private fun editTarget() = EditTarget(currentProject, currentScreen, openComponents)

// V2 M1: the SERVER owns the document; the editor is an op-emitting client.
// portalTree is a projection with WidgetNode.id == document HANDLE.
private var docVersion: Long = -1

/** K2: the relay's design-token table version last seen on the change stream. */
private var tokensVersionSeen: Long = -1
private var eventSource: EventSource? = null
/** One queued edit: its ops, whether panels re-render after it, and who hears if the relay accepted it. */
private class QueuedOps(val ops: List<DocOp>, val refreshPanels: Boolean, val onResult: ((Boolean) -> Unit)?)
private val opQueue = ArrayDeque<QueuedOps>()

/** Drops every queued edit, telling each sender it was not applied. */
private fun dropQueuedOps() {
  val dropped = opQueue.toList()
  opQueue.clear()
  dropped.forEach { it.onResult?.invoke(false) }
}
private var opInFlight = false

// The relay defaults to :8077, overridable via `?relay=<port>` so several per-app
// editors (each pointed at its own app's relay) can run side by side without the
// stop-one-start-another dance.
internal val SERVER: String = run {
  val port = Regex("[?&]relay=(\\d+)")
    .find(kotlinx.browser.window.location.search)?.groupValues?.get(1)
  "http://localhost:${port ?: "8077"}"
}
private fun queryParam(name: String): String? =
  kotlinx.browser.window.location.search.removePrefix("?").split('&')
    .firstOrNull { it.substringBefore('=') == name }
    ?.substringAfter('=', "")

private fun evidenceMode() = queryParam("evidence") == "1"
private fun evidenceWidth() =
  queryParam("width")?.toIntOrNull()?.coerceIn(320, 1440) ?: 402
private fun evidenceHeight() =
  queryParam("height")?.toIntOrNull()?.coerceIn(480, 1600) ?: 874
private const val SESSION = "editor"

// ── Playground mode: no portal-server reachable (e.g. the GitHub Pages build).
// The SAME UiDocument apply/invert engine runs locally; edits stay in-memory.
private var playground = false
private var localDoc = demoTemplate()
private val localUndo = ArrayDeque<List<DocOp>>()
private val localRedo = ArrayDeque<List<DocOp>>()
private var runtimeMetadataLoad: RuntimeMetadataLoad = RuntimeMetadataLoad.Loading

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
  seedScreenState(mocks)
  localRefresh(true)
}

/** A template's own sample values become the screen's State (what the State panel applies). */
private fun seedScreenState(values: Map<String, String>) {
  runCatching { localStorage.setItem("portal.state.${editTarget().key}", encodeScreenState(ScreenState(values = values))) }
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
  if (refreshPanels) refresh() else renderOutline()
}

private fun enterPlayground() {
  if (playground) return
  playground = true
  runtimeMetadataLoad = RuntimeMetadataLoad.Unavailable("relay unavailable in playground mode")
  PreviewAssetRouting.loaded(emptySet())
  saveTextEl.textContent = "playground — edits are local to this tab"
  PreviewBindings.mocks.putAll(demoMocks)
  seedScreenState(demoMocks)
  mountPlaygroundStrip()
  localRefresh(true)
  renderMockFidelity()
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
  strip.appendChild(Ui.button("✕", "btn icon") { strip.remove() }.also { it.setAttribute("title", "Dismiss") })
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

private fun copyToClipboard(text: String) {
  js("navigator.clipboard && navigator.clipboard.writeText(text).catch(function(){})")
}

private fun downloadText(name: String, text: String) {
  js("(function(){ var a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([text], { type: 'text/plain' })); a.download = name; document.body.appendChild(a); a.click(); a.remove(); })()")
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
private var flowSel: HTMLSelectElement? = null // #13 F2: null when the app registered no flows
private var personaSel: HTMLSelectElement? = null // #16: null when the app registered no personas
private lateinit var paletteListEl: HTMLElement
private lateinit var saveDotEl: HTMLElement
private lateinit var saveTextEl: HTMLElement
private lateinit var undoBtn: HTMLElement
private lateinit var redoBtn: HTMLElement
private lateinit var projectSel: HTMLSelectElement
private lateinit var screenSel: HTMLSelectElement
private lateinit var exportOverlay: HTMLElement
private lateinit var exportPre: HTMLElement
private lateinit var flowGraphOverlay: HTMLElement // #13 F3: nav-graph view
private lateinit var flowGraphBody: HTMLElement

// ---------------------------------------------------------------------------
// V2 M1 ops client — every edit is a transactional op batch against the
// server document; the tree state here is only a projection of the ack'd doc.

/** Queues [ops]; [onResult] (if any) hears whether the document accepted them. */
private fun sendOps(ops: List<DocOp>, refreshPanels: Boolean = true, onResult: ((Boolean) -> Unit)? = null) {
  opQueue.addLast(QueuedOps(ops, refreshPanels, onResult))
  pumpOps()
}

private fun pumpOps() {
  if (playground) {
    while (true) {
      val q = opQueue.removeFirstOrNull() ?: break
      val t = localDoc.applyBatch(q.ops)
      val r = t.result
      if (r == null) {
        Ui.toast(t.error ?: "edit rejected")
      } else {
        localDoc = r.doc.copy(version = localDoc.version + 1)
        localUndo.addLast(r.inverseBatch)
        localRedo.clear()
      }
      q.onResult?.invoke(r != null)
      localRefresh(q.refreshPanels)
    }
    return
  }
  if (opInFlight) return
  val q = opQueue.removeFirstOrNull() ?: return
  val ops = q.ops
  val refreshPanels = q.refreshPanels
  opInFlight = true
  saveDotEl.className = "dot saving"
  saveTextEl.textContent = "Saving…"
  val batch = OpBatch(docVersion, OpEnvelope(SESSION, 0), ops)
  val xhr = XMLHttpRequest()
  xhr.open("POST", "$SERVER/ops?${editTarget().query}")
  xhr.setRequestHeader("X-Portal-Session", SESSION)
  xhr.timeout = 15_000
  var settled = false
  fun settle(reply: OpsReply) {
    if (settled) return // load and error/timeout never both count
    settled = true
    opInFlight = false
    // A refusal that is still current keeps the pill "Not written" (settling never hides it).
    pillAfterReply(reply, writeBackWarnings.showing).let { saveDotEl.className = it.dot; saveTextEl.textContent = it.text }
    when (reply) {
      is OpsReply.Accepted -> {
        docVersion = reply.version
        q.onResult?.invoke(true)
        refetchDoc(refreshPanels)
        pumpOps()
      }
      OpsReply.Rebase -> { // raced an external edit — rebase on server state
        q.onResult?.invoke(false)
        dropQueuedOps()
        Ui.toast("Rebased on newer document")
        refetchDoc(true)
      }
      is OpsReply.Rejected -> {
        q.onResult?.invoke(false)
        dropQueuedOps()
        Ui.toast(reply.error ?: "edit rejected")
        saveTextEl.setAttribute("title", reply.error ?: "edit rejected")
        refetchDoc(true)
      }
      OpsReply.Unknown -> {
        // A 200 the editor cannot read, or no answer in 15 s: the edit may be
        // in. Kept (not rolled back) — the refetched document decides; the
        // queue resumes on it.
        q.onResult?.invoke(keepsEdit(reply))
        refetchDoc(true) { pumpOps() }
      }
      OpsReply.NetworkError -> {
        // No answer at all: never leave the edit "in flight" (the queue would wedge).
        q.onResult?.invoke(keepsEdit(reply))
        dropQueuedOps()
        Ui.toast("The relay did not answer; the edit was not saved")
        refetchDoc(true)
      }
    }
  }
  xhr.addEventListener("load", { _ ->
    readStackDepths(xhr)
    settle(opsReply(xhr.status.toInt(), xhr.responseText))
  })
  for (event in listOf("error", "timeout", "abort")) xhr.addEventListener(event, { _ -> settle(replyWithoutLoad(event)) })
  xhr.send(DocJson.encodeToString(batch))
}

/** K3: refused / failed write-backs of the open screen, shown once per version. */
private var writeBackWarnings = WriteBackWarnings()
private var writeBackBanner: HTMLElement? = null
private var writeBackScreen = ""

/**
 * Shows the change stream's write-back status: a refused or failed one as a
 * visible, non-blocking banner (the edit is in the document but NOT in the
 * .kt file) and the pill "Not written"; a discarded one as a banner and the
 * pill "Discarded" (the file was read, the change is gone). A later write
 * that lands, a read of the file, or an event with no status removes it.
 */
private fun showWriteBack(n: WriteBackNotice?) {
  val warning = writeBackWarnings.next(n)
  writeBackWarnings.pill?.let { saveDotEl.className = it.dot; saveTextEl.textContent = it.text }
  if (!writeBackWarnings.showing) {
    // A discard's banner stays until dismissed or a later write / read replaces it.
    if (n == null || n.written) {
      writeBackBanner?.remove()
      writeBackBanner = null
    }
  }
  if (warning == null) return
  writeBackBanner?.remove()
  val banner = Ui.el("div", "", "")
  banner.setAttribute(
    "style",
    "position:fixed; top:54px; left:50%; transform:translateX(-50%); z-index:98; max-width:640px; " +
      "background:var(--panel, #222); color:var(--text, #eee); border:1px solid var(--danger, #e5534b); " +
      "border-radius:8px; padding:8px 12px; font-size:12px; box-shadow:0 4px 14px rgba(0,0,0,.4); display:flex; gap:10px;",
  )
  banner.appendChild(Ui.el("span", "", "⚠ $warning"))
  banner.appendChild(Ui.button("×", "btn icon") { banner.remove(); if (writeBackBanner == banner) writeBackBanner = null })
  kotlinx.browser.document.body?.appendChild(banner)
  writeBackBanner = banner
}

/** Pull the authoritative document; ids in the projected tree ARE handles. */
private fun refetchDoc(refreshPanels: Boolean = true, cb: (() -> Unit)? = null) {
  if (playground) {
    localRefresh(refreshPanels)
    cb?.invoke()
    return
  }
  val xhr = XMLHttpRequest()
  // K2: `tokens=1` — this editor understands PropValue.Token (an older one is served literals).
  // K3: `conditionals=1` — and PropValue.Conditional (an older one is served the true branch).
  // K4: `templates=1&lists=1` — and PropValue.Template / PropValue.ListOf (else text / a resolved list).
  // K6: `extendedConditions=1` — and equality / compound conditionals (a K3 editor is served the true branch).
  // K7: `arithmetic=1` — and PropValue.Arith / PropValue.IntDp (an older editor gets the computed literal, or no prop).
  val target = editTarget()
  xhr.open("GET", "$SERVER/doc?${target.query}&$DOC_FEATURES")
  xhr.setRequestHeader("X-Portal-Session", SESSION)
  xhr.addEventListener("load", { _ ->
    if (target != editTarget()) return@addEventListener // another document was opened meanwhile
    if (xhr.status.toInt() == 404 && target.component != null) {
      // E14: a component whose body is edited in code (or that is gone) — say why, and go back.
      val why = Regex("\"error\":\"([^\"]*)\"").find(xhr.responseText)?.groupValues?.get(1) ?: "it cannot be opened"
      showCanvasHint(why)
      goToCrumb(target.components.size - 1)
      return@addEventListener
    }
    readStackDepths(xhr)
    runCatching { DocJson.decodeFromString<UiDocument>(xhr.responseText) }.onSuccess { doc ->
      docVersion = doc.version
      portalTree.value = doc.toWidgetTree(handleIds = true)
      // E15: select what was just inserted (its handle is the relay's to assign).
      pendingInsertPlace?.let { (parent, after) ->
        pendingInsertPlace = null
        val siblings = portalTree.value.findNode(parent)?.children.orEmpty()
        val i = if (after == null) 0 else siblings.indexOfFirst { it.id == after } + 1
        siblings.getOrNull(i)?.let { selectedId = it.id }
      }
      if (LiveEngine.isRunning) LiveEngine.prepare(collectPreviewBindingKeys(portalTree.value))
      Snapshot.sendApplyNotifications()
      // A typed edit keeps the field being typed in; the outline's labels follow it.
      if (refreshPanels) refresh() else renderOutline()
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
  val target = editTarget()
  if (writeBackScreen != target.key) { // another document: its own write-back status
    writeBackScreen = target.key
    writeBackWarnings = WriteBackWarnings()
    writeBackBanner?.remove()
    writeBackBanner = null
    // The pill was the other screen's: start this one idle (its first event sets its own).
    WriteBackWarnings.IDLE.let { saveDotEl.className = it.dot; saveTextEl.textContent = it.text }
  }
  eventSource = EventSource("$SERVER/doc-events?${target.query}").also { es ->
    es.onmessage = { ev ->
      val v = (ev.data as? String)?.let { Regex("\"version\":(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
      // C3 master updates: a component edit re-ingests screens (doc bumps) — refresh
      // the registry too so instance previews reflect the new definition without reload.
      // K2: the project's design-token table version rides on the same event.
      val t = (ev.data as? String)?.let { Regex("\"tokens\":(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
      val tokensChanged = t != null && t != tokensVersionSeen
      if (t != null) tokensVersionSeen = t
      // K3: the screen's write-back status — a refused or failed write is shown, never silent.
      (ev.data as? String)?.let { showWriteBack(writeBackOf(it)) }
      when {
        v != null && v != docVersion -> fetchComponents { refetchDoc(true) }
        tokensChanged -> fetchComponents { refresh() } // tokens changed, the document did not: refresh the picker
      }
    }
    es.onerror = { _ ->
      // Server restart kills the stream; recreate after a beat (fresh subscribe).
      if (target == editTarget()) {
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
      xhr.open("GET", "$SERVER/doc?${editTarget().query}&$DOC_FEATURES")
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
  xhr.open("POST", "$SERVER$path?${editTarget().query}")
  xhr.setRequestHeader("X-Portal-Session", SESSION)
  xhr.addEventListener("load", { _ ->
    readStackDepths(xhr)
    cb()
  })
  xhr.send()
}

/** E30: the session's undo/redo depths the relay reports (null = a relay that does not say). */
private var undoDepth: Int? = null
private var redoDepth: Int? = null

private fun readStackDepths(xhr: XMLHttpRequest) {
  undoDepth = xhr.getResponseHeader("X-Undo-Depth")?.toIntOrNull()
  redoDepth = xhr.getResponseHeader("X-Redo-Depth")?.toIntOrNull()
  updateUndoButtons()
}

private fun updateUndoButtons() {
  // Disabled when the relay says there is nothing to step; a relay that does not
  // report depths keeps them enabled (it acks "nothing to undo" itself).
  fun set(btn: HTMLElement, depth: Int?, verb: String) {
    if (depth == 0) btn.setAttribute("disabled", "") else btn.removeAttribute("disabled")
    btn.setAttribute("title", if (depth == null) verb else "$verb ($depth step${if (depth == 1) "" else "s"})")
  }
  if (playground) {
    set(undoBtn, localUndo.size, "Undo")
    set(redoBtn, localRedo.size, "Redo")
    return
  }
  set(undoBtn, undoDepth, "Undo")
  set(redoBtn, redoDepth, "Redo")
}

/** Anchor for append-to-end inserts: the parent's last child handle (excluding [excludeId]). */
private fun lastChildHandle(parentId: Int, excludeId: Int? = null): Handle? =
  portalTree.value.findNode(parentId)?.children?.lastOrNull { it.id != excludeId }?.let { Handle(it.id.toLong()) }

private fun deepCopy(n: WidgetNode): WidgetNode =
  WidgetNode(n.type, n.props, n.children.map { deepCopy(it) }) // fresh ids via default

private fun newNode(type: String): WidgetNode {
  // C3: a project-component instance starts with sample values for REQUIRED
  // scalar props (optional ones use the signature default = omitted); events
  // begin unbound. A single-slot component starts as an empty container; the
  // exporter still emits its required trailing lambda.
  editorComponents.spec(type)?.let { c ->
    val props = LinkedHashMap<String, Any?>()
    c.props.forEach { p -> if (p.name !in c.defaults) props[p.name] = c.sampleFor(p) }
    // REQUIRED events must emit compiling Kotlin: wire them to a same-named
    // action — ContractWriteBack adds the defaulted TODO(portal) member.
    c.events.forEach { e -> if (e.required) props[e.name] = Action(e.name) }
    return WidgetNode(type, props)
  }
  return WidgetNode(type, widgetSpec(type)?.sampleProps ?: emptyMap())
}

private fun addToSelectedOrRoot(node: WidgetNode) {
  val sel = selectedId
  val selType = sel?.let { portalTree.value.findNode(it)?.type }
  val acceptsChildren = selType?.let { type ->
    widgetSpec(type)?.acceptsChildren == true || type == ELSE_TYPE || editorComponents.spec(type)?.slots?.size == 1
  } == true
  // E17: inside a selected container; otherwise right after the selected node
  // (where the author is looking), and with no selection at the end of the root.
  val place: Pair<Int, Int?> = when {
    sel != null && acceptsChildren -> sel to lastChildHandle(sel)?.v?.toInt()
    sel != null && sel != portalTree.value.id -> dropPlace(portalTree.value, sel, DropZone.After, ::acceptsChildrenNode)
      ?: return
    else -> (portalTree.value.children.firstOrNull()?.id ?: portalTree.value.id).let { it to lastChildHandle(it)?.v?.toInt() }
  }
  pendingInsertPlace = place
  sendOps(listOf(insertOp(place, node.toDocNode())))
}

// ---------------------------------------------------------------------------
// Chrome

internal fun mountPortalChrome() {
  Ui.installStylesheet()
  if (evidenceMode()) installEvidenceStylesheet()
  buildTopbar()
  buildLeftPane()
  buildCenter()
  buildRightPane()
  if (!evidenceMode()) installPanelResizers()
  buildExportOverlay()
  installKeyboard()
  watchImageFailures()
  nameIconButtons()
  loadWorkspace()
}

private fun installEvidenceStylesheet() {
  val style = document.createElement("style")
  style.textContent = """
    html, body { width: 100%; height: 100%; background: #fff; }
    .topbar, .pane, .splitter, .canvas-toolbar, .canvas-hint { display: none !important; }
    .center {
      inset: 0 !important; padding: 0 !important; overflow: hidden !important;
      align-items: flex-start !important; justify-content: flex-start !important;
      background: #fff !important;
    }
    .frame {
      border-radius: 0 !important; box-shadow: none !important;
      outline: none !important;
    }
  """.trimIndent()
  document.head?.appendChild(style)
}

private lateinit var publishBtn: HTMLElement
private var publishing = false

/**
 * E26: Publish asks first (it builds, signs and stores a bundle every device of
 * this app will load) and runs once: the button is disabled while a publish is
 * in flight, so a double click cannot publish twice.
 */
private fun confirmPublish() {
  if (publishing) return
  val back = Ui.el("div", "modal-back")
  val box = Ui.el("div", "modal")
  markDialog(box)
  box.appendChild(Ui.el("div", "modal-title", "Publish to devices?"))
  box.appendChild(Ui.el("div", "muted", "Builds and signs project “$currentProject” (every screen, including “$currentScreen”) " +
    "and stores it as the next bundle version. Devices running this app load it on their next update check."))
  val actions = Ui.el("div", "modal-actions")
  fun close() { back.remove() }
  val cancel = Ui.button("Cancel", "btn") { close() }
  val go = Ui.button("Publish", "btn primary") {}
  go.addEventListener("click", { _ ->
    if (publishing) return@addEventListener
    close()
    runPublish()
  })
  actions.appendChild(cancel)
  actions.appendChild(go)
  box.appendChild(actions)
  back.appendChild(box)
  back.addEventListener("click", { ev -> if (ev.target == back) close() })
  back.addEventListener("keydown", { ev -> if ((ev as KeyboardEvent).key == "Escape") close() })
  document.body?.appendChild(back)
  go.focus()
}

/** E41: [box] is a modal dialog, named by its title. */
private fun markDialog(box: HTMLElement) {
  box.setAttribute("role", "dialog")
  box.setAttribute("aria-modal", "true")
  box.setAttribute("aria-labelledby", "modal-title")
  kotlinx.browser.window.setTimeout({ (box.querySelector(".modal-title") as? HTMLElement)?.id = "modal-title"; null }, 0)
}

/**
 * E41: a button showing only a symbol (＋, ⟲, ✕, ◆, @) is named by its tooltip, for a screen
 * reader — every one the chrome adds, whenever it is added.
 */
private fun nameIconButtons() {
  fun name(root: org.w3c.dom.Element) {
    val buttons = root.querySelectorAll("button")
    for (i in 0 until buttons.length) {
      val b = buttons.item(i) as? HTMLElement ?: continue
      if (b.hasAttribute("aria-label")) continue
      val text = b.textContent.orEmpty()
      val title = b.getAttribute("title")
      if (title != null && text.none { it.isLetter() }) b.setAttribute("aria-label", title)
    }
  }
  document.body?.let(::name)
  observeAdded { added -> name(added) }
}

private fun runPublish() {
  publishing = true
  publishBtn.setAttribute("disabled", "")
  publishBtn.textContent = "Publishing…"
  showExportText("Publish", null, "Publishing… (compiles + signs the bundle, ~1-2 min)", "")
  exportOverlay.removeAttribute("style")
  val xhr = XMLHttpRequest()
  xhr.open("POST", "$SERVER/publish?project=$currentProject")
  fun done(text: String) {
    publishing = false
    publishBtn.removeAttribute("disabled")
    publishBtn.textContent = "Publish"
    showExportText("Publish", null, text, "publish.log")
  }
  xhr.addEventListener("load", { _ -> done((if (xhr.status.toInt() == 200) "✅ " else "❌ ") + xhr.responseText) })
  xhr.addEventListener("error", { _ -> done("❌ The relay did not answer the publish request.") })
  xhr.send()
}

private fun buildTopbar() {
  val bar = Ui.el("div", "topbar")
  bar.appendChild(Ui.el("span", "brand", "keliver portal"))

  projectSel = Ui.select()
  screenSel = Ui.select()
  projectSel.addEventListener("change", { _ -> switchProject(projectSel.value) })
  screenSel.addEventListener("change", { _ -> switchScreen(screenSel.value) })
  bar.appendChild(projectSel)
  bar.appendChild(Ui.button("+", "btn icon") { promptCreate(project = true) }.also { it.setAttribute("title", "New project") })
  bar.appendChild(Ui.el("span", "crumb", "/"))
  bar.appendChild(screenSel)
  bar.appendChild(Ui.button("+", "btn icon") { promptCreate(project = false) }.also { it.setAttribute("title", "New screen") })

  bar.appendChild(Ui.el("div", "grow"))

  undoBtn = Ui.button("↩ Undo", "btn icon") { undo() }
  redoBtn = Ui.button("↪ Redo", "btn icon") { redo() }
  bar.appendChild(undoBtn)
  bar.appendChild(redoBtn)

  val preset = Ui.select()
  // Frame sizes in dp (1 CSS px = 1 dp on the canvas). K5: a large modern
  // phone's default 412×915 dp next to the original presets.
  DEVICE_PRESETS.forEach { (name, dims) ->
    val o = document.createElement("option") as HTMLOptionElement
    o.value = dims; o.textContent = "$name · ${dims.replace("x", "×")}"
    preset.appendChild(o)
  }
  preset.value = localStorage.getItem("portal.preset") ?: "390x780"
  preset.setAttribute("title", "Device frame (dp)")
  // E23: the frame resizes in place (the composition follows its container):
  // mocks, selection and Live state survive.
  preset.addEventListener("change", { _ ->
    localStorage.setItem("portal.preset", preset.value)
    applyFramePreset(preset.value)
  })
  bar.appendChild(preset)
  fun ensureOption(dims: String) {
    if ((0 until preset.options.length).none { (preset.options.item(it) as? HTMLOptionElement)?.value == dims }) {
      val o = document.createElement("option") as HTMLOptionElement
      o.value = dims; o.textContent = "Custom · ${dims.replace("x", "×")}"
      preset.appendChild(o)
    }
    preset.value = dims
  }
  ensureOption(preset.value.ifEmpty { "390x780" }.let { v -> if (parsePreset(v) != null) v else "390x780" })
  bar.appendChild(Ui.button("⟲", "btn icon") {
    val (w, h) = currentFrameSize()
    val rotated = "${h}x$w"
    localStorage.setItem("portal.preset", rotated)
    ensureOption(rotated)
    applyFramePreset(rotated)
  }.also { it.setAttribute("title", "Rotate the frame") })

  val savePill = Ui.el("span", "pill")
  saveDotEl = Ui.el("span", "dot")
  saveTextEl = Ui.el("span", "", "Saved")
  savePill.appendChild(saveDotEl)
  savePill.appendChild(saveTextEl)
  bar.appendChild(savePill)

  // #16: app-owned named capability/domain start-state. A live selection
  // change rekeys the presenter/flow composition; mock mode remains untouched.
  val entry = dev.keliver.portal.render.appPreviewEntry
  val personas = entry?.personas.orEmpty()
  if (entry != null && personas.isNotEmpty()) {
    val sel = Ui.select()
    personas.forEach { persona ->
      val option = document.createElement("option") as HTMLOptionElement
      option.value = persona.id
      option.textContent = "Persona: ${persona.label}"
      sel.appendChild(option)
    }
    val stored = localStorage.getItem("portal.persona")
    val selected = entry.resolvePersona(stored)
    sel.value = selected?.id ?: personas.first().id
    LiveEngine.selectPersona(sel.value)
    sel.setAttribute("style", "max-width:180px;")
    sel.setAttribute("title", selected?.description ?: "Named preview start-state")
    sel.addEventListener("change", { _ ->
      val persona = entry.resolvePersona(sel.value)
      sel.value = persona?.id ?: entry.defaultPersonaId.orEmpty()
      localStorage.setItem("portal.persona", sel.value)
      sel.setAttribute("title", persona?.description ?: "Named preview start-state")
      LiveEngine.selectPersona(persona?.id)
      if (LiveEngine.isRunning) {
        renderFidelity(liveRequiredCapabilities, livePresenterLabel())
        Snapshot.sendApplyNotifications()
      }
    })
    bar.appendChild(sel)
    personaSel = sel
  }

  // #13 F2: pick a flow to run Live as a WALKTHROUGH (screen-swap-on-nav).
  // Only shown when the per-app build registered flows; "—" = single-screen live.
  val flowNames = dev.keliver.portal.render.appFlowEntry?.flows?.keys.orEmpty()
  if (flowNames.isNotEmpty()) {
    val sel = Ui.select()
    fillSelect(sel, listOf("—") + flowNames.toList(), "—")
    sel.setAttribute("title", "Flow walkthrough: Live follows the flow's navigation")
    bar.appendChild(sel)
    flowSel = sel
    // #13 F3: open the derived nav-graph view (nodes = screens, edges = navs).
    bar.appendChild(Ui.button("⛓", "btn icon") { showFlowGraph() }
      .also { it.setAttribute("title", "Nav graph: the flow's screens + navigations (click a node to open it)") })
  }
  liveBtn = Ui.button("▶ Live", "btn") { toggleLive() }
  bar.appendChild(liveBtn)
  // Follow the live flow: when its current screen changes, load that screen's
  // tree (the flow composition itself is keyed by FLOW and survives the swap).
  LiveEngine.onFlowScreen = { s -> if (s != currentScreen) flowFollow(s) }
  buildChip = Ui.el("span", "", "")
  buildChip.setAttribute("style", "font-size:11px; padding:2px 8px; border-radius:8px; margin-left:6px;")
  bar.appendChild(buildChip)

  bar.appendChild(Ui.button("Export Kotlin", "btn") { showExport() })
  publishBtn = Ui.button("Publish", "btn primary") { confirmPublish() }
  publishBtn.setAttribute("title", "Build, sign and store a new bundle for devices")
  bar.appendChild(publishBtn)
  // E41: the topbar is first in the tab order (it is first on screen), and says what it is.
  bar.setAttribute("role", "toolbar")
  bar.setAttribute("aria-label", "Editor")
  projectSel.setAttribute("aria-label", "Project")
  screenSel.setAttribute("aria-label", "Screen")
  document.body?.let { it.insertBefore(bar, it.firstChild) }
}

private fun buildLeftPane() {
  val pane = Ui.el("div", "pane left")
  pane.setAttribute("role", "region")
  pane.setAttribute("aria-label", "Palette and outline")

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
  outlineEl.setAttribute("role", "tree")
  outlineEl.setAttribute("aria-label", "Outline")
  pane.appendChild(outlineEl)

  document.body?.appendChild(pane)
}

/** Device frame presets: label to "WIDTHxHEIGHT" in dp. Stored values stay valid across versions. */
internal val DEVICE_PRESETS: List<Pair<String, String>> = listOf(
  "Phone" to "390x780",
  "Large phone 412×915" to "412x915",
  "Tablet" to "768x900",
  "Web" to "1080x760",
)

/**
 * K5: the Bold box of a StyledText whose `weight` decides its weight on a
 * device (weight in 100..900 wins over `bold` there): checked when that
 * weight draws bold (600 and up), null when `bold` itself decides.
 */
internal fun boldFromWeight(props: Map<String, Any?>): Boolean? {
  val raw = props["weight"].let { if (it is Token) it.value else it }
  val weight = (raw as? Number)?.toInt() ?: return null
  return if (weight in 100..900) weight >= 600 else null
}

/** The container ComposeViewport renders into — sized by the device preset. */
internal const val PREVIEW_HOST_ID = "PreviewHost"

private fun buildCenter() {
  val center = Ui.el("div", "center")
  val frame = Ui.el("div", "frame")
  val dims = (localStorage.getItem("portal.preset") ?: "390x780").split("x")
  val w = if (evidenceMode()) evidenceWidth() else dims.getOrNull(0)?.toIntOrNull() ?: 390
  val h = if (evidenceMode()) evidenceHeight() else dims.getOrNull(1)?.toIntOrNull() ?: 780
  frame.setAttribute("style", "width:${w}px; height:${h}px; position:relative;")
  val host = Ui.el("div")
  host.id = PREVIEW_HOST_ID
  host.setAttribute("style", "width:100%; height:100%;")
  frame.appendChild(host)
  installClickToSelect(frame, host)
  // The zoom wrapper takes the frame's SCALED size, so the center scrolls to it.
  val zoomBox = Ui.el("div", "frame-zoom")
  zoomBox.appendChild(frame)
  center.appendChild(zoomBox)
  document.body?.appendChild(center)
  if (!evidenceMode()) installCanvasToolbar(center, zoomBox, frame, w, h)
  // The legacy fixed-size canvas from index.html is unused now.
  (document.getElementById("ComposeTarget") as? HTMLElement)?.setAttribute("style", "display:none;")
}

// ── P3-11: canvas click-to-select. The composition tags every rendered node
// with its handle (SelectionTag modifier); the ComposeUi host reports bounds
// into SelectionRegistry (compose px, canvas-root coords). The chrome
// hit-tests DOM events against it and mirrors selection/hover as overlay divs.
//
// Select mode owns the pointer: button presses are captured before they reach
// the Compose canvas, so selecting never also fires the widget's action (in
// Live mode that would run the app's logic). Interact mode hands them back.
private var selectMode = true
private lateinit var selOverlay: HTMLElement
private lateinit var hoverOverlay: HTMLElement
private lateinit var canvasHint: HTMLElement

/** Pointer button events the canvas must not see while selecting. */
private val CAPTURED_POINTER_EVENTS = listOf(
  "pointerdown", "pointerup", "mousedown", "mouseup", "click", "dblclick", "touchstart", "touchend", "contextmenu",
)

/** Movement (CSS px) past which a press is a drag, not a click. */
private const val CLICK_SLOP_PX = 5.0

private fun installClickToSelect(frame: HTMLElement, host: HTMLElement) {
  selOverlay = Ui.el("div", "sel-overlay").also {
    it.setAttribute("style", "position:absolute; pointer-events:none; border:2px solid #4f8cff; border-radius:3px; display:none; z-index:5;")
    frame.appendChild(it)
  }
  hoverOverlay = Ui.el("div").also {
    it.setAttribute("style", "position:absolute; pointer-events:none; border:1px dashed rgba(79,140,255,.7); border-radius:3px; display:none; z-index:4;")
    frame.appendChild(it)
  }

  fun hitAt(clientX: Double, clientY: Double): Int? {
    val r = host.getBoundingClientRect()
    val dpr = kotlinx.browser.window.devicePixelRatio
    // The frame may be zoomed (a CSS scale): the host's client rect is scaled, compose px are not.
    val x = ((clientX - r.left) / canvasZoom * dpr).toFloat()
    val y = ((clientY - r.top) / canvasZoom * dpr).toFloat()
    // E47: a stale rect (a node no longer in the tree) is passed over for the next one under the click.
    val tree = portalTree.value
    return dev.keliver.material.composeui.SelectionRegistry.hitTest(x, y) { tree.findNode(it) != null }
  }

  var downX = 0.0
  var downY = 0.0
  var pressed = false
  CAPTURED_POINTER_EVENTS.forEach { type ->
    host.addEventListener(type, { ev ->
      if (!selectMode) return@addEventListener
      ev.stopPropagation()
      if (type != "pointerdown" && type != "touchstart") ev.preventDefault()
      val me = ev as? org.w3c.dom.events.MouseEvent ?: return@addEventListener
      when (type) {
        "pointerdown" -> {
          pressed = true
          downX = me.clientX.toDouble()
          downY = me.clientY.toDouble()
        }
        "dblclick" -> hitAt(me.clientX.toDouble(), me.clientY.toDouble())?.let(::openOrEditInline)
        "pointerup" -> {
          if (!pressed) return@addEventListener
          pressed = false
          val moved = kotlin.math.hypot(me.clientX - downX, me.clientY - downY)
          if (moved > CLICK_SLOP_PX) return@addEventListener // a drag selects nothing
          // A miss (empty canvas) clears the selection.
          selectedId = hitAt(me.clientX.toDouble(), me.clientY.toDouble())
          refresh()
        }
      }
    }, true)
  }
  host.addEventListener("mousemove", { ev ->
    if (!selectMode) { hideOverlay(hoverOverlay); return@addEventListener }
    val me = ev as org.w3c.dom.events.MouseEvent
    val h = hitAt(me.clientX.toDouble(), me.clientY.toDouble())
    if (h == null || h == selectedId) hideOverlay(hoverOverlay) else positionOverlay(hoverOverlay, h)
  })
  host.addEventListener("mouseleave", { _ -> hideOverlay(hoverOverlay) })

  // E15: drop a palette item (or an outline row) onto the canvas: before/after
  // the node under the pointer, or inside it when it is a container and the
  // pointer is in its middle. An insertion line (or the container's box) shows
  // where it will land.
  val dropLine = Ui.el("div", "drop-line")
  dropLine.style.display = "none"
  frame.appendChild(dropLine)
  fun canvasDrop(ev: org.w3c.dom.events.Event): Pair<Int, DropZone>? {
    val me = ev as org.w3c.dom.events.MouseEvent
    val h = hitAt(me.clientX.toDouble(), me.clientY.toDouble()) ?: portalTree.value.id
    val node = portalTree.value.findNode(h) ?: return null
    val rect = dev.keliver.material.composeui.SelectionRegistry.bounds[h] ?: return h to DropZone.Inside
    val dpr = kotlinx.browser.window.devicePixelRatio
    val hr = host.getBoundingClientRect()
    val y = (me.clientY - hr.top) / canvasZoom * dpr
    val zone = if (h == portalTree.value.id) DropZone.Inside
      else dropZone(y - rect.top, rect.height.toDouble(), acceptsChildrenNode(node))
    return h to zone
  }
  fun showDropMark(h: Int, zone: DropZone) {
    val rect = dev.keliver.material.composeui.SelectionRegistry.bounds[h] ?: run { dropLine.style.display = "none"; return }
    val dpr = kotlinx.browser.window.devicePixelRatio
    dropLine.style.display = "block"
    dropLine.style.left = "${rect.left / dpr}px"
    dropLine.style.width = "${rect.width / dpr}px"
    when (zone) {
      DropZone.Inside -> {
        dropLine.className = "drop-line inside"
        dropLine.style.top = "${rect.top / dpr}px"
        dropLine.style.height = "${rect.height / dpr}px"
      }
      else -> {
        dropLine.className = "drop-line"
        dropLine.style.top = "${(if (zone == DropZone.Before) rect.top else rect.bottom) / dpr - 1}px"
        dropLine.style.height = "3px"
      }
    }
  }
  frame.addEventListener("dragover", { ev ->
    ev.preventDefault()
    val (h, zone) = canvasDrop(ev) ?: return@addEventListener
    showDropMark(h, zone)
  }, true)
  frame.addEventListener("dragleave", { ev ->
    if ((ev as org.w3c.dom.events.MouseEvent).relatedTarget == null) dropLine.style.display = "none"
  }, true)
  frame.addEventListener("drop", { ev ->
    ev.preventDefault()
    ev.stopPropagation()
    dropLine.style.display = "none"
    val (h, zone) = canvasDrop(ev) ?: return@addEventListener
    handleDrop((ev as DragEvent).dataTransfer?.getData("text"), h, zone)
  }, true)

  // Rects move under layout/scroll; a light tick keeps the selection box glued.
  kotlinx.browser.window.setInterval({
    if (selectMode) {
      val sel = selectedId
      if (sel != null && portalTree.value.findNode(sel) != null) positionOverlay(selOverlay, sel)
      else hideOverlay(selOverlay)
    }
    null
  }, 120)
}

/**
 * E35: edit a node's plain text in place, over its box on the canvas. Enter (or
 * leaving the field) commits one SetProp; Escape cancels. A text that is not a
 * literal (a binding, a template, a token) opens nothing.
 */
internal fun editTextInline(handle: Int) {
  val node = portalTree.value.findNode(handle) ?: return
  val current = node.props["text"] as? String ?: return
  val rect = dev.keliver.material.composeui.SelectionRegistry.bounds[handle] ?: return
  val frame = document.querySelector(".frame") as? HTMLElement ?: return
  (document.querySelector(".inline-edit") as? HTMLElement)?.remove()
  val dpr = kotlinx.browser.window.devicePixelRatio
  val input = document.createElement("textarea") as org.w3c.dom.HTMLTextAreaElement
  input.className = "inline-edit"
  input.value = current
  input.style.left = "${rect.left / dpr}px"
  input.style.top = "${rect.top / dpr}px"
  input.style.width = "${maxOf(rect.width / dpr, 120.0)}px"
  input.style.height = "${maxOf(rect.height / dpr, 28.0)}px"
  var done = false
  fun finish(commit: Boolean) {
    if (done) return
    done = true
    val text = input.value
    input.remove()
    if (commit && text != current) {
      sendOps(listOf(DocOp.SetProp(Handle(handle.toLong()), "text", PropValue.Lit("s", s = text))))
    }
  }
  input.addEventListener("keydown", { ev ->
    val k = ev as KeyboardEvent
    if (k.key == "Enter" && !k.shiftKey) { k.preventDefault(); finish(true) }
    if (k.key == "Escape") { k.preventDefault(); k.stopPropagation(); finish(false) }
  })
  input.addEventListener("blur", { _ -> finish(true) })
  frame.appendChild(input)
  input.focus()
  input.select()
}

/** Select (click selects, the app does not see it) or Interact (the app gets the pointer). */
internal fun setSelectMode(on: Boolean) {
  selectMode = on
  if (!on) { hideOverlay(selOverlay); hideOverlay(hoverOverlay) }
  canvasToolbarSync()
}

/**
 * E25: after the outline selects [handle], bring it into view on the canvas and
 * flash its box; if it is not drawn at all (an else branch, a false condition,
 * a Repeat with no rows), say so instead of doing nothing.
 */
internal fun revealOnCanvas(handle: Int) {
  val rect = dev.keliver.material.composeui.SelectionRegistry.bounds[handle]
  if (rect == null) {
    val node = portalTree.value.findNode(handle)
    showCanvasHint(
      if (node?.type in setOf("Condition", "Repeat", ELSE_TYPE)) "${node?.type} is logic: it draws only its children"
      else "Not drawn in the current preview state (a false condition, an else branch, or an empty list)",
    )
    return
  }
  val host = document.getElementById(PREVIEW_HOST_ID) as? HTMLElement
  val dpr = kotlinx.browser.window.devicePixelRatio
  val visible = (host?.clientHeight ?: 0) * dpr
  val margin = 24 * dpr
  val dy = when {
    rect.top < 0f -> rect.top - margin
    rect.bottom > visible -> minOf(rect.top - margin, rect.bottom - visible + margin)
    else -> 0.0f
  }
  if (dy != 0.0f) canvasScroller?.invoke(dy.toFloat())
  if (selectMode) {
    selOverlay.classList.remove("flash")
    kotlinx.browser.window.setTimeout({ selOverlay.classList.add("flash"); null }, 10)
  }
}

/**
 * [revealOnCanvas] once the canvas has drawn the document just loaded: the rects of the one
 * before (whose handles restart at 1 too) stay until it recomposes, so a reveal right after a
 * document swap read stale or missing bounds and said the node was not drawn.
 */
private fun revealWhenDrawn(handle: Int, framesLeft: Int = 60, settle: Int = 2) {
  kotlinx.browser.window.requestAnimationFrame {
    when {
      selectedId != handle -> {} // the selection moved on meanwhile
      settle > 0 -> revealWhenDrawn(handle, framesLeft, settle - 1)
      dev.keliver.material.composeui.SelectionRegistry.bounds[handle] != null || framesLeft <= 0 -> revealOnCanvas(handle)
      else -> revealWhenDrawn(handle, framesLeft - 1, 0)
    }
  }
}

/** Set by the canvas composition: scroll the canvas by compose px (null when the screen scrolls itself). */
internal var canvasScroller: ((Float) -> Unit)? = null

internal fun selectModeOn(): Boolean = selectMode

internal fun canvasHintElement(el: HTMLElement) { canvasHint = el }

private fun showCanvasHint(text: String) {
  if (!::canvasHint.isInitialized) return
  canvasHint.textContent = text
  canvasHint.style.display = "block"
  kotlinx.browser.window.setTimeout({ canvasHint.style.display = "none"; null }, 3500)
}

private fun positionOverlay(el: HTMLElement, handle: Int) {
  val rect = dev.keliver.material.composeui.SelectionRegistry.bounds[handle] ?: run { hideOverlay(el); return }
  val dpr = kotlinx.browser.window.devicePixelRatio
  el.style.display = "block"
  el.style.left = "${rect.left / dpr}px"
  el.style.top = "${rect.top / dpr}px"
  el.style.width = "${rect.width / dpr}px"
  el.style.height = "${rect.height / dpr}px"
}

private fun hideOverlay(el: HTMLElement) {
  el.style.display = "none"
}

private fun buildRightPane() {
  val pane = Ui.el("div", "pane right")
  pane.setAttribute("role", "region")
  pane.setAttribute("aria-label", "Properties and state")
  pane.appendChild(Ui.section("Properties"))
  propsEl = Ui.el("div", "card")
  pane.appendChild(propsEl)
  pane.appendChild(Ui.section("Modifiers"))
  modsEl = Ui.el("div", "card")
  pane.appendChild(modsEl)
  pane.appendChild(Ui.section("Node"))
  opsEl = Ui.el("div", "card")
  pane.appendChild(opsEl)
  pane.appendChild(Ui.section("State"))
  bindingsEl = Ui.el("div", "card")
  pane.appendChild(bindingsEl)
  pane.appendChild(Ui.section("Preview fidelity"))
  fidelityEl = Ui.el("div", "card")
  renderMockFidelity()
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
  PreviewBindings.actionSink = { name, arg ->
    val row = Ui.el("div", "", "⚡ $name${arg?.let { "($it)" } ?: ""}")
    row.setAttribute("style", "color:var(--good);")
    consoleEl.insertBefore(row, consoleEl.firstChild)
  }
}

// ── M8: capability-driven live preview ──────────────────────────────────────

private fun toggleLive() {
  if (LiveEngine.request.value != null || liveBtn.textContent == "■ Stop") disableLive()
  else if (openComponents.isNotEmpty()) showCanvasHint("Live runs the screen's presenter — go back to “$currentScreen” to run it")
  else enableLive()
}

private var liveRequiredCapabilities: List<String> = emptyList()

private fun livePresenterLabel(): String? {
  val flowName = flowSel?.value?.takeIf { it != "—" }
  val flowEntry = dev.keliver.portal.render.appFlowEntry
  val entry = dev.keliver.portal.render.appPreviewEntry
  return when {
    flowName != null && flowEntry?.flows?.get(flowName) != null ->
      "${flowEntry.label} · flow '$flowName'"
    entry?.screens?.get(currentScreen) != null -> entry.label
    else -> null
  }
}

private fun enableLive() {
  loadHttpFixtureCatalog {
    enableLiveWithFixtureCatalog()
  }
}

private fun enableLiveWithFixtureCatalog() {
  serverGet("/capabilities?project=$currentProject") { txt ->
    val required = parseNames(txt)
    liveRequiredCapabilities = required
    // #13 F2: a selected flow wins — Live becomes a WALKTHROUGH: the app's
    // FlowScope owns which screen shows; the editor follows its navigation.
    val flowName = flowSel?.value?.takeIf { it != "—" }
    val flowEntry = dev.keliver.portal.render.appFlowEntry
    // P3-12: run the APP'S REAL presenter when the per-app entry registers one
    // for this screen; otherwise stay honest — mock tier, clearly labeled.
    val entry = dev.keliver.portal.render.appPreviewEntry
    val flowRegistered = flowName != null && flowEntry?.flows?.get(flowName) != null
    val registered = entry?.screens?.get(currentScreen) != null
    renderFidelity(required, livePresenter = livePresenterLabel())
    LiveEngine.onError = { msg ->
      val row = Ui.el("div", "", "⚠ $msg")
      row.setAttribute("style", "color:var(--bad, #e57373);")
      consoleEl.insertBefore(row, consoleEl.firstChild)
    }
    LiveEngine.onValuesApplied = {
      if (::inspectorEl.isInitialized) renderInspector()
    }
    LiveEngine.onFidelityChanged = {
      renderFidelity(liveRequiredCapabilities, livePresenterLabel())
    }
    if (flowRegistered || registered) {
      LiveEngine.prepare(collectPreviewBindingKeys(portalTree.value))
      if (flowRegistered) LiveEngine.flowRequest.value = flowName else LiveEngine.request.value = currentScreen
      PreviewBindings.actionSink = { name, arg ->
        val row = Ui.el("div", "", "⚡ $name${arg?.let { "($it)" } ?: ""} → real presenter")
        row.setAttribute("style", "color:var(--good);")
        consoleEl.insertBefore(row, consoleEl.firstChild)
        LiveEngine.dispatch(name, arg)
        Snapshot.sendApplyNotifications()
      }
    } else {
      LiveEngine.stop()
      PreviewBindings.mocks.clear()
    }
    liveBtn.textContent = "■ Stop"
    liveBtn.className = "btn primary"
    renderInspector()
    Snapshot.sendApplyNotifications()
  }
}

/** #13 F2: follow the live flow to [screen] — load its tree WITHOUT re-keying
 *  the flow composition (LiveEngine keys by flow; state survives the swap). */
private fun flowFollow(screen: String) {
  currentScreen = screen
  sendEmpty("$SERVER/active?project=$currentProject&screen=$currentScreen", "POST")
  reloadScreenList()
  loadDraft()
}

private fun disableLive() {
  LiveEngine.stop()
  PreviewBindings.mocks.clear()
  installMockActionSink()
  liveBtn.textContent = "▶ Live"
  liveBtn.className = "btn"
  renderMockFidelity()
  inspectorEl.let { Ui.clear(it); it.appendChild(Ui.el("div", "muted", "—")) }
  refresh()
  Snapshot.sendApplyNotifications()
}

private fun renderMockFidelity() {
  if (!::fidelityEl.isInitialized) return
  Ui.clear(fidelityEl)
  fidelityEl.appendChild(Ui.el("div", "muted", "Mock mode — press ▶ Live to run real logic"))
  appendRuntimeCompatibility()
  appendImageFailures()
}

/** E29: each image host the canvas could not load from, why, and what to do about it. */
private fun appendImageFailures() {
  val hosts = ImageLoadFailures.hosts()
  if (hosts.isEmpty()) return
  val title = Ui.el("div", "", "⚠ Images not shown from ${hosts.size} host${if (hosts.size == 1) "" else "s"}")
  title.setAttribute("style", "font-weight:600; margin-top:7px; color:var(--warn, #e0a030);")
  fidelityEl.appendChild(title)
  hosts.forEach { (host, why) ->
    fidelityEl.appendChild(Ui.el("div", "", host).also { it.setAttribute("style", "font-family:ui-monospace,monospace;") })
    fidelityEl.appendChild(Ui.el("div", "muted", why))
  }
  fidelityEl.appendChild(
    Ui.el(
      "div", "muted",
      "Add ${hosts.keys.joinToString(", ") { "\"$it\"" }} to \"previewAssetHosts\" in keliver.portal.json and restart the relay: " +
        "it then serves those images to the canvas. The device loads them directly and is not affected.",
    ),
  )
}

/** E29: a newly failing image host re-renders the fidelity panel and is pointed out once. */
private fun watchImageFailures() {
  ImageLoadFailures.onChange = {
    if (LiveEngine.isRunning) renderFidelity(liveRequiredCapabilities, livePresenterLabel()) else renderMockFidelity()
    showCanvasHint("Some images can't load in the editor — Preview fidelity (right) says why and how to fix it")
  }
}

private fun renderFidelity(required: List<String>, livePresenter: String? = null) {
  Ui.clear(fidelityEl)
  // P3-12: name the presenter tier first — real app entry vs mock tier.
  val pres = Ui.el("div", "", if (livePresenter != null) "● real presenter — $livePresenter" else "○ no presenter registered for '$currentScreen' — mock tier")
  pres.setAttribute("style", "color:" + (if (livePresenter != null) "var(--good)" else "var(--warn, #e0a030)") + ";")
  fidelityEl.appendChild(pres)
  appendRuntimeCompatibility()
  appendImageFailures()
  val persona = dev.keliver.portal.render.appPreviewEntry?.resolvePersona(LiveEngine.personaId.value)
  if (persona != null) {
    val row = Ui.el("div", "", "👤 ${persona.label}")
    row.setAttribute("style", "font-weight:600; margin-top:5px;")
    row.setAttribute("title", persona.description)
    fidelityEl.appendChild(row)
    if (persona.description.isNotBlank()) {
      fidelityEl.appendChild(Ui.el("div", "muted", persona.description))
    }
    persona.fixtureStates().forEach { (name, state) ->
      fidelityEl.appendChild(Ui.el("div", "muted", "  $name — $state"))
    }
  }
  if (required.isEmpty()) {
    fidelityEl.appendChild(Ui.el("div", "", "✅ Full fidelity — no host capabilities required"))
    return
  }
  val report = PreviewCapabilities.report(required, persona)
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

private fun appendRuntimeCompatibility() {
  val title = Ui.el("div", "", "Runtime compatibility")
  title.setAttribute("style", "font-weight:600; margin-top:7px;")
  fidelityEl.appendChild(title)

  val editor = AppRuntimeMetadata(
    keliverVersion = guestRedwoodVersion.toString(),
    widgetVersion = EDITOR_WIDGET_VERSION,
  )
  when (val load = runtimeMetadataLoad) {
    RuntimeMetadataLoad.Loading -> {
      fidelityEl.appendChild(Ui.el("div", "muted", "… loading app runtime metadata"))
    }
    RuntimeMetadataLoad.Undeclared -> {
      val status = Ui.el("div", "", "○ App runtime not declared")
      status.setAttribute("style", "color:var(--warn, #e0a030);")
      fidelityEl.appendChild(status)
      fidelityEl.appendChild(runtimeVersionRow("Editor", editor))
      fidelityEl.appendChild(
        Ui.el("div", "muted", "Add appRuntime to keliver.portal.json; version match is unknown."),
      )
    }
    is RuntimeMetadataLoad.Unavailable -> {
      val status = Ui.el("div", "", "○ Runtime metadata unavailable")
      status.setAttribute("style", "color:var(--warn, #e0a030);")
      status.setAttribute("title", load.reason)
      fidelityEl.appendChild(status)
      fidelityEl.appendChild(runtimeVersionRow("Editor", editor))
    }
    is RuntimeMetadataLoad.Declared -> {
      val report = compareRuntimeMetadata(editor, load.appRuntime)
      val (label, color, help) = when (report.status) {
        RuntimeCompatibilityStatus.Match ->
          Triple("● Runtime versions match", "var(--good)", null)
        // E46: an app on an older release with the same widget protocol is supported — say so, once.
        RuntimeCompatibilityStatus.AppOlder ->
          Triple(
            "● Compatible (same widget protocol)",
            "var(--good)",
            "The app is on Keliver ${report.app.keliverVersion}; the preview runs ${report.editor.keliverVersion}. " +
              "Layout is the same; a fix made after ${report.app.keliverVersion} shows here before the app updates.",
          )
        RuntimeCompatibilityStatus.KeliverSkew ->
          Triple(
            "⚠ Editor older than the app",
            "var(--warn, #e0a030)",
            "The app is on Keliver ${report.app.keliverVersion}, newer than this editor (${report.editor.keliverVersion}): " +
              "the preview may miss what changed since. Update the portal tools to ${report.app.keliverVersion} or later.",
          )
        RuntimeCompatibilityStatus.WidgetMismatch ->
          Triple(
            "⚠ Widget protocol mismatch",
            "var(--danger, #e5534b)",
            "Preview and app use different widget/schema compatibility versions.",
          )
      }
      val status = Ui.el("div", "", label)
      status.setAttribute("style", "color:$color;")
      fidelityEl.appendChild(status)
      fidelityEl.appendChild(runtimeVersionRow("Editor", report.editor))
      fidelityEl.appendChild(runtimeVersionRow("App", report.app))
      if (help != null) fidelityEl.appendChild(Ui.el("div", "muted", help))
    }
  }
}

private fun runtimeVersionRow(label: String, runtime: AppRuntimeMetadata): HTMLElement =
  Ui.el(
    "div",
    "muted",
    "$label — Keliver ${runtime.keliverVersion} · widgets v${runtime.widgetVersion}",
  )

private fun renderInspector() {
  Ui.clear(inspectorEl)
  val state = if (LiveEngine.isRunning) PreviewBindings.mocks.toMap() else emptyMap()
  if (state.isEmpty()) { inspectorEl.appendChild(Ui.el("div", "muted", "no live bindings")); return }
  state.forEach { (k, v) ->
    inspectorEl.appendChild(Ui.el("div", "", "$k = \"$v\""))
  }
  // Fire wired actions from here too (DOM-reliable; complements canvas taps).
  val contract = collectContract(portalTree.value, editorComponents)
  if (contract.actions.isNotEmpty()) {
    val row = Ui.el("div", "row")
    row.setAttribute("style", "margin-top:6px; flex-wrap:wrap; gap:4px;")
    contract.actions.forEach { action ->
      row.appendChild(
        Ui.button("⚡ $action", "btn icon") {
          val log = Ui.el("div", "", "⚡ $action → real presenter")
          log.setAttribute("style", "color:var(--good);")
          consoleEl.insertBefore(log, consoleEl.firstChild)
          LiveEngine.dispatch(action, null)
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
  val head = Ui.el("div", "head")
  exportTitle = Ui.el("span", "export-title", "Kotlin")
  head.appendChild(exportTitle)
  val copy = Ui.button("Copy", "btn") {
    copyToClipboard(exportText)
    showCanvasHint("Copied ${exportName.ifEmpty { "the source" }}")
  }
  copy.setAttribute("style", "margin-left:auto;")
  head.appendChild(copy)
  head.appendChild(Ui.button("Download", "btn") { if (exportText.isNotEmpty()) downloadText(exportName.ifEmpty { "Screen.kt" }, exportText) })
  val close = Ui.button("✕", "btn icon") { exportOverlay.setAttribute("style", "display:none;") }
  close.setAttribute("title", "Close (Esc)")
  head.appendChild(close)
  exportOverlay.appendChild(head)
  exportNote = Ui.el("div", "export-note")
  exportNote.style.display = "none"
  exportOverlay.appendChild(exportNote)
  exportPre = Ui.el("pre")
  exportOverlay.appendChild(exportPre)
  document.body?.appendChild(exportOverlay)

  // #13 F3: the nav-graph overlay (populated per-open from /flow).
  flowGraphOverlay = Ui.el("div", "overlay")
  flowGraphOverlay.setAttribute("style", "display:none;")
  val gHead = Ui.el("div", "head", "Nav graph")
  val gClose = Ui.button("✕", "btn icon") { flowGraphOverlay.setAttribute("style", "display:none;") }
  gClose.setAttribute("title", "Close (Esc)")
  gClose.setAttribute("style", "margin-left:auto;")
  gHead.appendChild(gClose)
  flowGraphOverlay.appendChild(gHead)
  flowGraphBody = Ui.el("div", "")
  flowGraphBody.setAttribute("style", "padding:8px 4px; overflow:auto;")
  flowGraphOverlay.appendChild(flowGraphBody)
  document.body?.appendChild(flowGraphOverlay)
}

/**
 * E20: Export shows the document's own file, exactly as the relay keeps it — the package,
 * imports, function, Bindings interface and declared types are the app's. Only the
 * playground, which saves no file, shows the tree's export, and says it is a snippet.
 */
private fun showExport() {
  flowGraphOverlay.setAttribute("style", "display:none;") // E36: one overlay at a time
  exportOverlay.removeAttribute("style")
  if (playground) {
    showExportText("Kotlin snippet — the playground saves no file", null, exportKotlin(portalTree.value), "Screen.kt")
    return
  }
  showExportText("Loading…", null, "", "")
  val target = editTarget()
  serverGet("/source?${target.query}") { txt ->
    if (target != editTarget()) return@serverGet
    val o = runCatching { Json.parseToJsonElement(txt).jsonObject }.getOrNull()
    val file = o?.get("file")?.jsonPrimitive?.content
    if (o == null || file == null) {
      showExportText("Export failed", "the relay did not return the file: ${txt.take(160)}", "", "")
      return@serverGet
    }
    val text = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val wb = o["writeBack"] as? JsonObject
    val behind = wb?.get("written")?.jsonPrimitive?.content == "false"
    showExportText(
      file,
      if (behind) "⚠ the file is behind the editor: ${wb?.get("message")?.jsonPrimitive?.content}" else null,
      text ?: "(no file yet)",
      file.substringAfterLast('/'),
    )
  }
}

private lateinit var exportTitle: HTMLElement
private lateinit var exportNote: HTMLElement
private var exportText = ""
private var exportName = ""

private fun showExportText(title: String, note: String?, text: String, fileName: String) {
  exportTitle.textContent = title
  exportNote.textContent = note.orEmpty()
  exportNote.style.display = if (note == null) "none" else "block"
  exportText = text
  exportName = fileName
  exportPre.textContent = text
}

/**
 * #13 F3: render the DERIVED nav graph (GET /flow) — per flow, its screens as
 * clickable node chips (start marked ▶, the current screen ringed) and its
 * navigations as "from —key→ to" edge rows. Clicking any node/edge opens that
 * screen in the editor. Pure read view over the same graph the relay derives
 * from the recognized trees — no second source of truth.
 */
private fun showFlowGraph() {
  Ui.clear(flowGraphBody)
  flowGraphBody.appendChild(Ui.el("div", "muted", "loading /flow…"))
  flowGraphOverlay.removeAttribute("style")
  serverGet("/flow?project=$currentProject") { txt ->
    Ui.clear(flowGraphBody)
    val flows = runCatching { Json.parseToJsonElement(txt).jsonArray }.getOrNull()
    if (flows == null || flows.isEmpty()) {
      flowGraphBody.appendChild(Ui.el("div", "muted", "No flows declared (add a flows/ file with flow{ … })."))
      return@serverGet
    }
    flows.forEach { fEl ->
      val f = fEl.jsonObject
      val name = f["name"]?.jsonPrimitive?.content ?: "?"
      val start = f["start"]?.jsonPrimitive?.content ?: ""
      val nodes = f["nodes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
      val edges = f["edges"]?.jsonArray?.map { it.jsonObject } ?: emptyList()

      flowGraphBody.appendChild(Ui.el("div", "", name).also {
        it.setAttribute("style", "font-weight:600; margin:6px 0 4px;")
      })
      // Nodes row: each node = an "open for edit" chip + a ▶ "walk from here".
      val nodeRow = Ui.el("div", "row"); nodeRow.setAttribute("style", "flex-wrap:wrap; gap:8px; margin-bottom:6px;")
      nodes.forEach { n ->
        val group = Ui.el("div", "row"); group.setAttribute("style", "gap:0; align-items:stretch;")
        val chip = Ui.button((if (n == start) "▶ " else "") + n, "btn") { openFlowNode(n) }
        val ring = if (n == currentScreen) "outline:2px solid var(--accent, #7a6cff);" else ""
        chip.setAttribute("style", "font-size:12px; padding:3px 10px; border-top-right-radius:0; border-bottom-right-radius:0; $ring")
        chip.setAttribute("title", "Open $n for editing")
        val walk = Ui.button("▶", "btn primary") { walkFlowFrom(name, n) }
        walk.setAttribute("style", "font-size:11px; padding:3px 8px; border-top-left-radius:0; border-bottom-left-radius:0;")
        walk.setAttribute("title", "Walk the flow live starting at $n")
        group.appendChild(chip); group.appendChild(walk)
        nodeRow.appendChild(group)
      }
      flowGraphBody.appendChild(nodeRow)
      // Edge rows: from —key→ to (each opens the target).
      if (edges.isEmpty()) {
        flowGraphBody.appendChild(Ui.el("div", "muted", "no navigations found in the current screen trees"))
      } else {
        edges.forEach { e ->
          val from = e["from"]?.jsonPrimitive?.content ?: ""
          val key = e["key"]?.jsonPrimitive?.content ?: ""
          val to = e["to"]?.jsonPrimitive?.content ?: ""
          val row = Ui.button("$from  —$key→  $to", "btn") { openFlowNode(to) }
          row.setAttribute("style", "display:block; width:100%; text-align:left; font-size:12px; margin:2px 0; padding:4px 8px;")
          flowGraphBody.appendChild(row)
        }
      }
    }
  }
}

/** #13 F3: open a graph node's screen in the editor (loads its tree + syncs the picker). */
private fun openFlowNode(screen: String) {
  flowGraphOverlay.setAttribute("style", "display:none;")
  if (screen.isEmpty() || screen == currentScreen) return
  if (::screenSel.isInitialized) screenSel.value = screen // reflect the jump in the picker
  switchScreen(screen)
}

/**
 * #13 F4: start a Live flow WALKTHROUGH beginning at [node] (not the declared
 * start) — deep-link / mid-flow preview. Selects the flow, sets the start
 * override, and enters flow mode; the flow follows navigation from there.
 */
private fun walkFlowFrom(flowName: String, node: String) {
  flowGraphOverlay.setAttribute("style", "display:none;")
  // Restart clean if already live; the flow's onFlowScreen(node) then loads the
  // node's tree via flowFollow, so no manual screen switch is needed here.
  if (LiveEngine.request.value != null || LiveEngine.flowRequest.value != null) LiveEngine.stop()
  flowSel?.value = flowName
  LiveEngine.flowStartOverride = node
  enableLive()
}

private var clipboardNode: WidgetNode? = null

/** E36: hide the topmost open layer (modal, inline editor, picker, overlay); false when none is open. */
private fun closeTopmostLayer(): Boolean {
  (document.querySelector(".modal-back") as? HTMLElement)?.let { it.remove(); return true }
  (document.querySelector(".inline-edit") as? HTMLElement)?.let { it.remove(); return true }
  val pickers = document.querySelectorAll(".token-picker")
  val open = (0 until pickers.length).mapNotNull { pickers.item(it) as? HTMLElement }.filter { it.firstChild != null }
  if (open.isNotEmpty()) {
    open.forEach { Ui.clear(it) }
    return true
  }
  for (o in listOf(flowGraphOverlay, exportOverlay)) {
    if (o.getAttribute("style") != "display:none;") { o.setAttribute("style", "display:none;"); return true }
  }
  return false
}

/** E14: double-click / Enter — open a component instance, edit a text in place. */
private fun openOrEditInline(handle: Int) {
  val node = portalTree.value.findNode(handle)
  if (isComponentInstance(node, editorComponents)) openComponent(node!!.type) else editTextInline(handle)
}

/** E14: edit component [name]'s insides; the breadcrumb leads back. */
private fun openComponent(name: String) {
  if (playground) return
  if (LiveEngine.isRunning) disableLive()
  val next = editTarget().open(name)
  crumbSelections = (crumbSelections.take(openComponents.size) + selectedId).take(next.components.size)
  openComponents = next.components
  loadDraft()
}

/** E14: back to the crumb at [depth] (0 = the screen), its selection as it was. */
private fun goToCrumb(depth: Int) {
  val back = editTarget().backTo(depth)
  if (back == editTarget()) return
  val reselect = crumbSelections.getOrNull(back.components.size)
  openComponents = back.components
  crumbSelections = crumbSelections.take(back.components.size)
  // The registry first: an instance shows the component as it was just edited.
  fetchComponents {
    loadDraft {
      if (reselect != null && portalTree.value.findNode(reselect) != null) {
        selectedId = reselect
        refresh()
        revealWhenDrawn(reselect)
      }
    }
  }
}

private var crumbsEl: HTMLElement? = null

/** E14: the breadcrumb — shown only inside a component: the screen › each component opened. */
private fun renderCrumbs() {
  val target = editTarget()
  document.body?.classList?.toggle("editing-component", target.component != null)
  if (target.component == null) { crumbsEl?.style?.display = "none"; return }
  val bar = crumbsEl ?: Ui.el("div", "crumbs").also { crumbsEl = it; document.body?.appendChild(it) }
  Ui.clear(bar)
  bar.style.display = "flex"
  target.crumbs.forEachIndexed { depth, label ->
    if (depth > 0) bar.appendChild(Ui.el("span", "crumb-sep", "›"))
    val last = depth == target.crumbs.lastIndex
    val item = if (last) Ui.el("span", "crumb here", "◇ $label") else Ui.button(if (depth == 0) "▢ $label" else "◇ $label", "crumb") { goToCrumb(depth) }
    item.setAttribute("title", if (last) "Editing $label — every instance changes" else "Back to $label")
    bar.appendChild(item)
  }
  bar.appendChild(Ui.button("Done", "crumb done") { goToCrumb(0) }.also { it.setAttribute("title", "Back to the screen (Esc)") })
}

/** E21: keyboard editing on the selection (outside text fields). */
private fun handleEditorKey(e: KeyboardEvent) {
  val tree = portalTree.value
  val mod = e.metaKey || e.ctrlKey
  val sel = selectedId?.let { tree.findNode(it) }
  fun select(id: Int?) { if (id != null) { selectedId = id; refresh(); revealOnCanvas(id) } }
  when {
    e.key == "ArrowDown" || e.key == "ArrowUp" -> {
      val order = visibleOrder(tree, collapsedRows)
      val i = order.indexOf(sel?.id ?: -1)
      select(if (i < 0) order.firstOrNull() else order.getOrNull(if (e.key == "ArrowDown") i + 1 else i - 1) ?: order[i])
    }
    e.key == "ArrowLeft" && sel != null ->
      if (sel.children.isNotEmpty() && sel.id !in collapsedRows) { collapsedRows += sel.id; renderOutline() }
      else select(parentOf(tree, sel.id)?.id)
    e.key == "ArrowRight" && sel != null ->
      if (sel.id in collapsedRows) { collapsedRows -= sel.id; renderOutline() } else select(sel.children.firstOrNull()?.id)
    (e.key == "Delete" || e.key == "Backspace") && sel != null && sel.id != tree.id -> {
      val order = visibleOrder(tree, collapsedRows)
      val next = parentOf(tree, sel.id)?.let { p -> p.children.getOrNull(p.children.indexOf(sel) + 1)?.id ?: p.id }
      selectedId = next ?: order.firstOrNull()
      sendOps(listOf(DocOp.DeleteNode(Handle(sel.id.toLong()))))
    }
    mod && e.key.lowercase() == "d" && sel != null && sel.id != tree.id -> {
      val parentId = parentOf(tree, sel.id)?.id ?: return
      sendOps(listOf(DocOp.InsertNode(Handle(parentId.toLong()), Handle(sel.id.toLong()), deepCopy(sel).toDocNode())))
    }
    mod && e.key.lowercase() == "c" && sel != null -> clipboardNode = sel
    mod && e.key.lowercase() == "v" -> clipboardNode?.let { addToSelectedOrRoot(deepCopy(it)) }
    (e.key == "Enter" || e.key == "F2") && sel != null -> openOrEditInline(sel.id)
    else -> return
  }
  e.preventDefault()
}

private fun installKeyboard() {
  document.addEventListener("keydown", { ev ->
    val e = ev as KeyboardEvent
    val mod = e.metaKey || e.ctrlKey
    val inField = (document.activeElement as? HTMLElement)?.tagName?.lowercase() in setOf("input", "textarea")
    if (mod && e.key.lowercase() == "z" && !inField) { // E31: in a field, the field's own undo
      ev.preventDefault()
      if (e.shiftKey) redo() else undo()
    }
    val typing = (document.activeElement as? HTMLElement)?.tagName?.lowercase() in setOf("input", "textarea", "select")
    if (e.key == "Escape") {
      // E36: Escape closes the topmost popover/overlay first, then deselects.
      if (closeTopmostLayer()) { ev.preventDefault(); return@addEventListener }
      if (!typing && selectedId != null) { selectedId = null; refresh(); return@addEventListener }
      // E14: with nothing selected, Escape steps out of an opened component.
      if (!typing && openComponents.isNotEmpty()) goToCrumb(openComponents.size - 1)
      return@addEventListener
    }
    if (typing) return@addEventListener
    handleEditorKey(e)
  })
}

// ---------------------------------------------------------------------------
// Workspace (projects / screens / drafts)

// ── P3-12: live-build poll + state-preserving auto-reload ───────────────────
private lateinit var buildChip: HTMLElement
private var buildBaseline: Long = -1

private fun startPreviewBuildPoll() {
  window.setInterval({
    serverGet("/preview-build") { txt ->
      runCatching {
        val o = Json.parseToJsonElement(txt).jsonObject
        val promoted = o.getValue("promotedId").jsonPrimitive.content.toLong()
        val state = o.getValue("state").jsonPrimitive.content
        val err = o["error"]?.jsonPrimitive?.content
        renderBuildChip(state, err)
        if (buildBaseline < 0) {
          buildBaseline = promoted // first poll = baseline, never reload on boot
        } else if (promoted > buildBaseline) {
          // A newer editor build was promoted: snapshot UI state and swap to it.
          window.sessionStorage.setItem(
            "portal.reload",
            "{\"project\":\"$currentProject\",\"screen\":\"$currentScreen\"," +
              "\"selected\":${selectedId ?: -1},\"live\":${LiveEngine.request.value != null}}",
          )
          window.location.reload()
        }
      }
    }
    null
  }, 3000)
}

private fun renderBuildChip(state: String, err: String?) {
  if (!::buildChip.isInitialized) return
  when (state) {
    "building" -> { buildChip.textContent = "\u23f3 building preview\u2026"; buildChip.setAttribute("style", buildChipStyle("#8a6d3b")) }
    "failed" -> {
      buildChip.textContent = "\u2715 preview build failed"
      buildChip.setAttribute("style", buildChipStyle("#a94442"))
      buildChip.setAttribute("title", err ?: "")
    }
    "ok" -> { buildChip.textContent = "\u2713 preview up to date"; buildChip.setAttribute("style", buildChipStyle("#3c763d")) }
    else -> buildChip.textContent = ""
  }
}

private fun buildChipStyle(color: String) =
  "font-size:11px; padding:2px 8px; border-radius:8px; margin-left:6px; color:#fff; background:$color;"

private fun restoreSnapshotIfAny(): Boolean {
  val raw = window.sessionStorage.getItem("portal.reload") ?: return false
  window.sessionStorage.removeItem("portal.reload")
  return runCatching {
    val o = Json.parseToJsonElement(raw).jsonObject
    currentProject = o.getValue("project").jsonPrimitive.content
    currentScreen = o.getValue("screen").jsonPrimitive.content
    val sel = o.getValue("selected").jsonPrimitive.content.toInt()
    if (sel >= 0) selectedId = sel
    val live = o.getValue("live").jsonPrimitive.content.toBoolean()
    if (live) window.setTimeout({ enableLive(); null }, 800) // after the doc loads
    true
  }.getOrDefault(false)
}

private fun loadWorkspace() {
  startPreviewBuildPoll()
  loadRuntimeMetadata()
  loadPreviewAssetHosts()
  serverGet("/active") { txt ->
    runCatching {
      val o = Json.parseToJsonElement(txt).jsonObject
      currentProject = o.getValue("project").jsonPrimitive.content
      currentScreen = o.getValue("screen").jsonPrimitive.content
    }
    // P3-12: a post-rebuild reload restores the exact editing context.
    restoreSnapshotIfAny()
    loadHttpFixtureCatalog()
    reloadProjectList()
    reloadScreenList()
    fetchComponents { loadDraft() } // C3: components before the doc so instances render
  }
}

private fun loadHttpFixtureCatalog(then: (() -> Unit)? = null) {
  val xhr = XMLHttpRequest()
  xhr.open("GET", "$SERVER/http-fixtures?project=$currentProject")
  xhr.addEventListener("load", { _ ->
    if (xhr.status.toInt() in 200..299) {
      PreviewCapabilities.updateHttpFixtureCatalog(xhr.responseText)
    } else {
      PreviewCapabilities.httpCatalogUnavailable("fixture catalog unavailable")
    }
    then?.invoke()
  })
  xhr.addEventListener("error", { _ ->
    PreviewCapabilities.httpCatalogUnavailable("fixture catalog request failed")
    then?.invoke()
  })
  xhr.send()
}

private fun loadRuntimeMetadata() {
  val xhr = XMLHttpRequest()
  xhr.open("GET", "$SERVER/runtime-metadata")
  xhr.addEventListener("load", { _ ->
    runtimeMetadataLoad = parseRuntimeMetadata(xhr.responseText)
    if (liveBtn.textContent == "■ Stop") {
      renderFidelity(liveRequiredCapabilities, livePresenterLabel())
    } else {
      renderMockFidelity()
    }
  })
  xhr.addEventListener("error", { _ ->
    runtimeMetadataLoad = RuntimeMetadataLoad.Unavailable("relay request failed")
    renderMockFidelity()
  })
  xhr.send()
}

/** K5: which image hosts the relay serves for the canvas (none from an older relay). */
private fun loadPreviewAssetHosts() {
  val xhr = XMLHttpRequest()
  xhr.open("GET", "$SERVER/preview-assets")
  xhr.addEventListener("load", { _ ->
    PreviewAssetRouting.loaded(if (xhr.status.toInt() == 200) parsePreviewAssetHosts(xhr.responseText) else emptySet())
  })
  xhr.addEventListener("error", { _ -> PreviewAssetRouting.loaded(emptySet()) })
  xhr.send()
}

/** C3: pull the active project's component registry (and, K2, its design tokens) for palette + preview. */
private fun fetchComponents(then: (() -> Unit)? = null) {
  if (playground) { then?.invoke(); return }
  serverGet("/components?project=$currentProject") { txt ->
    setEditorComponents(parseComponents(txt))
    renderPalette(paletteFilter)
    serverGet("/tokens?project=$currentProject") { tokens ->
      setEditorTokens(parseTokens(tokens))
      then?.invoke()
    }
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

private fun loadDraft(cb: (() -> Unit)? = null) {
  // V2 M1: the server document is the truth — fetch it and subscribe to changes.
  selectedId = null
  renderCrumbs()
  dropQueuedOps()
  refetchDoc(true) { subscribeDocEvents(); cb?.invoke() }
  updateUndoButtons()
}

private fun switchProject(name: String) {
  currentProject = name
  sendEmpty("$SERVER/active?project=$currentProject&screen=main", "POST")
  currentScreen = "main"
  reloadScreenList()
  // The component registry is per-project — refetch it for the new project
  // BEFORE the doc loads, or every instance falls back to an opaque placeholder
  // (mirrors the init path at boot).
  fetchComponents { loadDraft() }
}

private fun switchScreen(name: String) {
  val resumeLive = LiveEngine.isRunning && LiveEngine.flowRequest.value == null
  if (resumeLive) {
    LiveEngine.stop()
    PreviewBindings.mocks.clear()
    renderInspector()
  }
  currentScreen = name
  sendEmpty("$SERVER/active?project=$currentProject&screen=$currentScreen", "POST")
  loadDraft { if (resumeLive) enableLive() }
  // P3-12: live mode follows the screen — re-enable for the new screen's
  // presenter (or fall back to the honest no-presenter state).
}

/** P3-12: append a line to the action console (used by PreviewEnv.log). */
internal fun portalLiveLog(msg: String) {
  if (!::consoleEl.isInitialized) return
  val row = Ui.el("div", "", msg)
  row.setAttribute("style", "color:var(--muted);")
  consoleEl.insertBefore(row, consoleEl.firstChild)
}

/**
 * E40: a new screen or project, named in a dialog that says what a name may be
 * before anything is created, and opened once the relay has created it (not
 * after a guessed delay).
 */
private fun promptCreate(project: Boolean) {
  val what = if (project) "project" else "screen"
  val sel = if (project) projectSel else screenSel
  val existing = (0 until sel.options.length).mapNotNull { (sel.options.item(it) as? HTMLOptionElement)?.value }
  val back = Ui.el("div", "modal-back")
  val box = Ui.el("div", "modal")
  markDialog(box)
  box.appendChild(Ui.el("div", "modal-title", if (project) "New project" else "New screen in “$currentProject”"))
  box.appendChild(
    Ui.el(
      "div", "muted",
      if (project) "A project holds its own screens and components."
      else "Creates screens/<name>.kt with a <Name>Screen function, and opens it.",
    ),
  )
  val input = Ui.input()
  input.setAttribute("type", "text")
  input.setAttribute("placeholder", if (project) "e.g. onboarding" else "e.g. order_status")
  input.setAttribute("aria-label", "$what name")
  box.appendChild(input)
  val problem = Ui.el("div", "modal-problem")
  box.appendChild(problem)
  val actions = Ui.el("div", "modal-actions")
  fun close() { back.remove() }
  val cancel = Ui.button("Cancel", "btn") { close() }
  val create = Ui.button("Create", "btn primary") {}
  fun check(): String? {
    val why = nameProblem(input.value, existing, what)
    // Say why only once something was typed; an empty field just cannot be created yet.
    problem.textContent = if (input.value.isEmpty()) "" else why.orEmpty()
    if (why == null) create.removeAttribute("disabled") else create.setAttribute("disabled", "")
    return why
  }
  fun submit() {
    if (check() != null || create.hasAttribute("disabled")) return
    val name = input.value.trim()
    create.setAttribute("disabled", "")
    create.textContent = "Creating…"
    val xhr = XMLHttpRequest()
    xhr.open("POST", if (project) "$SERVER/projects" else "$SERVER/screens?project=$currentProject")
    xhr.addEventListener("load", { _ ->
      if (xhr.status.toInt() !in 200..299) {
        problem.textContent = "The relay refused it (HTTP ${xhr.status}): ${xhr.responseText.take(160)}"
        create.textContent = "Create"
        create.removeAttribute("disabled")
        return@addEventListener
      }
      close()
      if (project) {
        currentProject = name
        currentScreen = "main"
        sendEmpty("$SERVER/active?project=$currentProject&screen=main", "POST")
        reloadProjectList(); reloadScreenList(); loadDraft()
      } else {
        currentScreen = name
        sendEmpty("$SERVER/active?project=$currentProject&screen=$currentScreen", "POST")
        reloadScreenList(); loadDraft()
      }
    })
    xhr.addEventListener("error", { _ ->
      problem.textContent = "The relay did not answer — is it running?"
      create.textContent = "Create"
      create.removeAttribute("disabled")
    })
    xhr.send(name)
  }
  input.addEventListener("input", { _ -> check() })
  input.addEventListener("keydown", { ev -> if ((ev as KeyboardEvent).key == "Enter") { ev.preventDefault(); submit() } })
  create.addEventListener("click", { _ -> submit() })
  actions.appendChild(cancel)
  actions.appendChild(create)
  box.appendChild(actions)
  back.appendChild(box)
  back.addEventListener("click", { ev -> if (ev.target == back) close() })
  back.addEventListener("keydown", { ev -> if ((ev as KeyboardEvent).key == "Escape") close() })
  document.body?.appendChild(back)
  check()
  input.focus()
}

// ---------------------------------------------------------------------------
// Panels

private fun refresh() {
  renderOutline(); renderProps(); renderMods(); renderOps(); renderBindings()
}

private var paletteFilter = ""

private fun renderPalette(filter: String) {
  paletteFilter = filter
  Ui.clear(paletteListEl)
  val q = filter.trim().lowercase()
  fun paletteRow(type: String, tag: String?, cycle: Boolean) {
    val row = Ui.el("div", "pal-row")
    row.appendChild(Ui.el("span", "t", type))
    if (tag != null) row.appendChild(Ui.el("span", "cat", tag))
    row.setAttribute("draggable", "true")
    row.addEventListener("dragstart", { ev -> (ev as DragEvent).dataTransfer?.setData("text", "new:$type") })
    row.addEventListener("click", { _ -> addToSelectedOrRoot(newNode(type)) })
    paletteListEl.appendChild(row)
  }
  // C3: project components FIRST — the app's own vocabulary is what authors reach for.
  val comps = editorComponents.names().sorted().mapNotNull { editorComponents.spec(it) }
    .filter { q.isEmpty() || it.name.lowercase().contains(q) }
  if (comps.isNotEmpty()) {
    paletteListEl.appendChild(Ui.el("div", "section", "Project components"))
    comps.forEach { c ->
      val tag = when {
        c.slots.size == 1 -> "container"
        !c.transparent -> "opaque"
        else -> null
      }
      paletteRow(c.name, tag, false)
    }
  }
  widgetSpecs.groupBy { it.category }.forEach { (category, specs) ->
    val hits = specs.filter { q.isEmpty() || it.type.lowercase().contains(q) }
    if (hits.isEmpty()) return@forEach
    paletteListEl.appendChild(Ui.el("div", "section", category))
    hits.forEach { spec -> paletteRow(spec.type, if (spec.acceptsChildren) "container" else null, false) }
  }
}

private val collapsedRows = mutableSetOf<Int>()

/** E15: where the last insert went (parent, the sibling it follows), to select it once the document reloads. */
private var pendingInsertPlace: Pair<Int, Int?>? = null

/** Whether [node] can hold children in the editor (a container, an Else, a one-slot component). */
private fun acceptsChildrenNode(node: WidgetNode): Boolean =
  widgetSpec(node.type)?.acceptsChildren == true || node.type == ELSE_TYPE || node.type == "Condition" ||
    node.type == "Repeat" || editorComponents.spec(node.type)?.slots?.size == 1

private fun renderOutline() {
  // E41: keyboard focus stays on the outline when it is re-rendered (arrow keys move the selection).
  val hadFocus = (document.activeElement as? HTMLElement)?.classList?.contains("tree-row") == true
  Ui.clear(outlineEl)
  val focusable = selectedId?.takeIf { portalTree.value.findNode(it) != null } ?: portalTree.value.id
  fun row(node: WidgetNode, depth: Int) {
    val r = Ui.el("div", "tree-row" + if (node.id == selectedId) " selected" else "")
    r.setAttribute("data-id", "${node.id}")
    // E41: a tree of treeitems, reachable with Tab (one stop: the selected row) and the arrow keys.
    r.setAttribute("role", "treeitem")
    r.setAttribute("aria-level", "${depth + 1}")
    r.setAttribute("aria-selected", "${node.id == selectedId}")
    if (node.children.isNotEmpty()) r.setAttribute("aria-expanded", "${node.id !in collapsedRows}")
    r.setAttribute("aria-label", listOfNotNull(node.type, outlineHint(node)).joinToString(" "))
    r.tabIndex = if (node.id == focusable) 0 else -1
    r.setAttribute("style", "padding-left:${4 + depth * 14}px;")
    val twisty = Ui.el("span", "twisty", if (node.children.isEmpty()) "" else if (node.id in collapsedRows) "▸" else "▾")
    twisty.addEventListener("click", { ev ->
      ev.stopPropagation()
      if (!collapsedRows.remove(node.id)) collapsedRows += node.id
      renderOutline()
    })
    r.appendChild(twisty)
    r.appendChild(Ui.el("span", "t", node.type))
    outlineHint(node)?.let { r.appendChild(Ui.el("span", "hint", it)) }
    r.addEventListener("click", { _ ->
      selectedId = node.id
      refresh()
      revealOnCanvas(node.id)
    })
    r.setAttribute("draggable", "true")
    r.addEventListener("dragstart", { ev -> (ev as DragEvent).dataTransfer?.setData("text", "move:${node.id}"); ev.stopPropagation() })
    // E16/E17: the row's top and bottom quarters drop before/after it; its middle, inside.
    fun zoneOf(ev: DragEvent): DropZone {
      val b = r.getBoundingClientRect()
      return dropZone(ev.clientY - b.top, b.height, acceptsChildrenNode(node))
    }
    fun clearMarks() { listOf("drop-before", "drop-after", "droptarget").forEach { r.classList.remove(it) } }
    r.addEventListener("dragover", { ev ->
      ev.preventDefault()
      clearMarks()
      r.classList.add(when (zoneOf(ev as DragEvent)) { DropZone.Before -> "drop-before"; DropZone.After -> "drop-after"; DropZone.Inside -> "droptarget" })
    })
    r.addEventListener("dragleave", { _ -> clearMarks() })
    r.addEventListener("drop", { ev ->
      ev.preventDefault()
      clearMarks()
      val de = ev as DragEvent
      handleDrop(de.dataTransfer?.getData("text"), node.id, zoneOf(de))
    })
    outlineEl.appendChild(r)
    if (node.id !in collapsedRows) node.children.forEach { row(it, depth + 1) }
  }
  row(portalTree.value, 0)
  if (hadFocus) (outlineEl.querySelector(".tree-row[tabindex='0']") as? HTMLElement)?.focus()
  // Keep the selected row in view (keyboard navigation, a canvas click).
  (outlineEl.querySelector(".tree-row.selected") as? HTMLElement)?.let { el ->
    val box = el.getBoundingClientRect()
    val pane = (outlineEl.closest(".pane") as? HTMLElement)?.getBoundingClientRect()
    if (pane != null && (box.top < pane.top || box.bottom > pane.bottom)) el.scrollIntoView(false)
  }
}

private fun handleDrop(payload: String?, targetId: Int, zone: DropZone = DropZone.Inside) {
  if (payload == null) return
  val tree = portalTree.value
  val place = dropPlace(tree, targetId, zone, ::acceptsChildrenNode) ?: return
  val parentType = tree.findNode(place.first)?.type
  val parentComponent = parentType?.let(editorComponents::spec)
  if (parentComponent != null && parentComponent.slots.size != 1) return
  when {
    payload.startsWith("new:") -> {
      pendingInsertPlace = place
      sendOps(listOf(insertOp(place, newNode(payload.removePrefix("new:")).toDocNode())))
    }
    payload.startsWith("move:") -> {
      val id = payload.removePrefix("move:").toIntOrNull() ?: return
      moveOp(tree, id, place)?.let { sendOps(listOf(it)) }
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
  // C3: a component instance uses its SIGNATURE spec; internals are not shown.
  val comp = editorComponents.spec(node.type)
  if (comp != null) {
    propsEl.appendChild(Ui.el("div", "muted", "${node.type} #${node.id} · component" +
      (if (comp.slots.size == 1) " · content slot" else "") +
      (if (!comp.transparent) " (opaque)" else "")))
    // E14: its insides are edited in the component itself (every instance changes).
    propsEl.appendChild(
      Ui.button("Edit component ↗", "btn") { openComponent(node.type) }.also {
        it.setAttribute("title", "Edit ${node.type}'s insides — every ${node.type} changes (double-click or Enter)")
      },
    )
  } else {
    propsEl.appendChild(Ui.el("div", "muted", "${node.type} #${node.id}"))
  }
  // K3: a Condition without an else branch can gain one (its last child, an Else node).
  if (node.type == "Condition" && node.children.none { it.type == ELSE_TYPE }) {
    propsEl.appendChild(
      Ui.button("+ else branch", "btn") {
        sendOps(listOf(DocOp.InsertNode(Handle(node.id.toLong()), lastChildHandle(node.id), WidgetNode(ELSE_TYPE).toDocNode())))
      },
    )
  }
  if (node.type == ELSE_TYPE) propsEl.appendChild(Ui.el("div", "muted", "else branch: its children render when the Condition does not hold"))
  val spec = widgetSpec(node.type)
  // K6: an equality or compound Condition is shown, not edited (its shape is the code's).
  val readOnlyCond = if (node.type == "Condition") condOf(node)?.takeIf(::isReadOnlyCondition) else null
  if (readOnlyCond != null) {
    propsEl.appendChild(Ui.el("div", "row").also { r ->
      r.appendChild(Ui.el("span", "muted", "if"))
      r.appendChild(Ui.el("code", "", conditionSource(readOnlyCond)))
    })
    propsEl.appendChild(Ui.el("div", "muted", "an equality or compound condition is edited in code"))
  }
  val specs = if (readOnlyCond != null) emptyList() else comp?.props ?: editableProps(node.type)
  if (specs.isEmpty() && readOnlyCond == null) propsEl.appendChild(Ui.el("div", "muted", "No editable properties"))
  specs.forEach { s ->
    propsEl.appendChild(propRow(node.id, node.type, node.props, s.name, s.kind, s.label, keyPrefix = ""))
  }
  // P3: events — each wires to a named Action.
  val events = comp?.events?.map { it.name } ?: spec?.events ?: emptyList()
  if (events.isNotEmpty()) {
    propsEl.appendChild(Ui.el("div", "section", "Events"))
    events.forEach { evName ->
      val rowEl = Ui.el("div", "row")
      val lab = Ui.el("label", "", evName)
      lab.setAttribute("title", evName)
      rowEl.appendChild(lab)
      // K3: a conditional event — `if (b.enabled) b::tap else null`.
      (node.props[evName] as? Conditional)?.let { c ->
        propsEl.appendChild(conditionalEventRow(node.id, evName, c) { op, refresh, done -> sendOps(listOf(op), refreshPanels = refresh, onResult = done); renderBindings() })
        return@forEach
      }
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
/**
 * E4/E6/E38: the State panel. Every value the canvas reads — the fields `if`
 * blocks test, the fields props are bound to, the lists' row counts — with a
 * scenario's coherent defaults and realistic samples, overridable and kept per
 * screen in this browser (never written to the app's source).
 */
private fun renderBindings() {
  Ui.clear(bindingsEl)
  val contract = collectContract(portalTree.value, editorComponents)
  val fields = stateFields(portalTree.value, contract)
  val key = "portal.state.${editTarget().key}"
  var state = decodeScreenState(runCatching { localStorage.getItem(key) }.getOrNull())
  fun save() { runCatching { localStorage.setItem(key, encodeScreenState(state)) } }
  fun apply() {
    if (LiveEngine.isRunning) return // Live feeds the real presenter's values
    val mocks = effectiveMocks(fields, state.scenario, state.values)
    if (PreviewBindings.mocks.toMap() != mocks) {
      PreviewBindings.mocks.clear()
      PreviewBindings.mocks.putAll(mocks)
      Snapshot.sendApplyNotifications()
    }
  }
  apply()
  if (fields.isEmpty() && contract.actions.isEmpty()) {
    bindingsEl.appendChild(Ui.el("div", "muted", "Bind a prop (@) or wire an event to build the contract"))
    return
  }
  if (LiveEngine.isRunning) {
    bindingsEl.appendChild(Ui.el("div", "muted", "Live: the app's presenter supplies these values"))
  }

  val scenarios = scenariosFor(fields)
  if (scenarios.size > 1) {
    val chips = Ui.el("div", "state-scenarios")
    scenarios.forEach { sc ->
      val chip = Ui.button(sc.label, if (sc == state.scenario) "chip on" else "chip") {
        // A scenario resets what it decides: the flags. Texts and numbers the user typed stay.
        val flags = fields.filter { it.kind == StateKind.Bool || it.source != StateSource.Prop }.map { it.name }.toSet()
        state = ScreenState(sc, state.values - flags)
        save(); renderBindings()
      }
      chip.setAttribute("title", "Preview the screen ${sc.label.lowercase()}")
      chips.appendChild(chip)
    }
    bindingsEl.appendChild(chips)
  }

  fun setValue(field: String, value: String?) {
    state = state.copy(values = if (value == null) state.values - field else state.values + (field to value))
    save(); apply()
  }

  fun group(title: String, source: StateSource) {
    val inGroup = fields.filter { it.source == source }
    if (inGroup.isEmpty()) return
    bindingsEl.appendChild(Ui.el("div", "state-group", title))
    inGroup.forEach { f ->
      val rowEl = Ui.el("div", "row")
      val lab = Ui.el("label", "", f.name)
      lab.setAttribute("title", when (f.source) {
        StateSource.Condition -> "Tested by an if block on this screen"
        StateSource.Prop -> "${f.name}: ${kotlinTypeOf(contract.fields[f.name] ?: PropKind.Text)}"
        StateSource.List -> "Rows in the preview (0-10)"
      })
      rowEl.appendChild(lab)
      val default = defaultValue(f, state.scenario)
      val userSet = f.name in state.values
      when (f.kind) {
        StateKind.Bool -> {
          val box = document.createElement("input") as HTMLInputElement
          box.type = "checkbox"
          box.checked = (state.values[f.name] ?: default) == "true"
          box.addEventListener("change", { _ ->
            val v = box.checked.toString()
            setValue(f.name, if (v == default) null else v)
          })
          rowEl.appendChild(box)
        }
        else -> {
          val input = Ui.input()
          input.setAttribute("type", if (f.kind == StateKind.Text) "text" else "number")
          if (f.kind == StateKind.Rows) { input.setAttribute("min", "0"); input.setAttribute("max", "10") }
          if (f.kind == StateKind.Int) input.setAttribute("step", "1")
          input.setAttribute("placeholder", if (default.isEmpty()) "(empty)" else default)
          input.value = if (userSet) state.values.getValue(f.name) else ""
          input.addEventListener("input", { _ ->
            val v = input.value
            val ok = when (f.kind) {
              StateKind.Int, StateKind.Rows -> v.isEmpty() || v.trim().toIntOrNull() != null
              StateKind.Double -> v.isEmpty() || v.trim().toDoubleOrNull() != null
              else -> true
            }
            input.classList.toggle("invalid", !ok)
            if (ok) setValue(f.name, v.takeIf { it.isNotEmpty() })
          })
          rowEl.appendChild(input)
        }
      }
      if (userSet) {
        val reset = Ui.button("↺", "btn icon mini") { setValue(f.name, null); renderBindings() }
        reset.setAttribute("title", "Back to the scenario's value")
        rowEl.appendChild(reset)
      }
      bindingsEl.appendChild(rowEl)
    }
  }
  group("Conditions", StateSource.Condition)
  group("Data", StateSource.Prop)
  group("Lists", StateSource.List)

  if (contract.actions.isNotEmpty()) {
    val details = document.createElement("details") as HTMLElement
    details.appendChild(Ui.el("summary", "state-group", "Actions (${contract.actions.size})"))
    contract.actions.forEach { a ->
      val p = contract.actionParams[a]
      details.appendChild(Ui.el("div", "muted", if (p != null) "fun $a(value: $p)" else "fun $a()"))
    }
    bindingsEl.appendChild(details)
  }
}

private val BINDABLE = setOf(PropKind.Text, PropKind.Int, PropKind.Bool, PropKind.Double, PropKind.Color)

/**
 * One editable property row; [keyPrefix] namespaces modifier props ("mod.X.").
 * K2: wrapped with the row's design-token picker (opened from the ◆ button).
 */
private fun propRow(nodeId: Int, nodeType: String, props: Map<String, Any?>, name: String, kind: PropKind, label: String, keyPrefix: String): HTMLElement {
  val wrap = Ui.el("div")
  val picker = Ui.el("div", "token-picker")
  wrap.appendChild(propRowInner(nodeId, nodeType, props, name, kind, label, keyPrefix, picker))
  wrap.appendChild(picker)
  return wrap
}

private fun propRowInner(
  nodeId: Int,
  nodeType: String,
  props: Map<String, Any?>,
  name: String,
  kind: PropKind,
  label: String,
  keyPrefix: String,
  picker: HTMLElement,
): HTMLElement {
  val key = keyPrefix + name
  val rowEl = Ui.el("div", "row")
  val lab = Ui.el("label", "", label)
  lab.setAttribute("title", label)
  rowEl.appendChild(lab)

  // K2: a prop bound to a design token shows the token (reference + value),
  // can switch token, or detach to the literal value.
  val token = props[key] as? Token
  if (token != null) {
    rowEl.appendChild(tokenChip(token.ref, token.value, kind))
    rowEl.appendChild(tokenButton(nodeId, nodeType, key, picker))
    rowEl.appendChild(
      Ui.button("detach", "btn icon") { sendOps(listOf(literalOp(nodeId, key, token.value))) }
        .also { it.setAttribute("title", "replace ${token.ref} with its value (${token.value})") },
    )
    if (kind in BINDABLE && keyPrefix.isEmpty() && '.' !in name) rowEl.appendChild(bindButton(nodeId, key, name))
    return rowEl
  }

  // K3: a conditional value: its condition and both branches, each edited in place.
  (props[key] as? Conditional)?.let { c ->
    return conditionalRow(nodeId, nodeType, key, label, kind, c) { op, refresh, done -> sendOps(listOf(op), refreshPanels = refresh, onResult = done); renderBindings() }
  }

  // K7: an arithmetic value — read-only source; replaced whole by a value, or (@) a binding.
  if (isArithmeticValue(props[key])) {
    rowEl.appendChild(arithmeticEditor(props[key]) { sendOps(listOf(arithmeticLiteralOp(nodeId, key, props[key]))) })
    if (kind in BINDABLE && keyPrefix.isEmpty() && '.' !in name) rowEl.appendChild(bindButton(nodeId, key, name))
    return rowEl
  }

  // K4: a template — its text with {references}; an edit is validated before it is sent.
  (props[key] as? Template)?.let { t -> return templateRow(rowEl, nodeId, key, t, picker) }
  // K4: an Int or String list — its elements, each a literal, a ◆token or an @binding.
  // A list bound as a whole (`options = b.options`) keeps the binding row below.
  if (editsAsList(kind, props[key]) && keyPrefix.isEmpty()) {
    return listRow(rowEl, nodeId, key, props[key], kind, picker)
  }

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

  // Text is a <textarea>: an <input type=text> strips newlines from its value,
  // so the first keystroke would join a multi-line text's lines, and a first
  // line break could never be typed (U33).
  if (kind == PropKind.Text) {
    val text = props[key] as? String ?: ""
    val area = Ui.textarea()
    area.rows = text.lines().size.coerceIn(1, 6)
    area.value = text
    area.addEventListener("input", { _ -> editProp(nodeId, key, area.value) })
    rowEl.appendChild(area)
    tokenButtonIfAny(nodeId, nodeType, key, picker)?.let(rowEl::appendChild)
    if (keyPrefix.isEmpty()) {
      rowEl.appendChild(bindButton(nodeId, key, name))
      // K4: start a template — the text so far, then a binding named like the prop.
      rowEl.appendChild(
        Ui.button("{}", "btn icon") {
          val parts = listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { lit(it) }, PropValue.Bind(name))
          sendOps(listOf(DocOp.SetProp(Handle(nodeId.toLong()), key, PropValue.Template(parts))))
        }.also { it.setAttribute("title", "template: text with {bindings} and {◆tokens}") },
      )
    }
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
      // K1: a Dp (Dp prop, Margin side, Dp modifier arg) is never negative —
      // the document clamps it too, so input, canvas and saved file agree.
      val nonNegative = isDpProp(nodeType, key)
      if (nonNegative) input.setAttribute("min", "0")
      input.value = (props[key] as? Double ?: 0.0).toString()
      input.addEventListener("input", { _ ->
        val v = input.value.toDoubleOrNull() ?: 0.0
        editProp(nodeId, key, if (nonNegative) v.coerceAtLeast(0.0) else v)
      })
    }
    PropKind.Bool -> {
      input.setAttribute("type", "checkbox")
      input.className = ""
      val fromWeight = if (nodeType == "StyledText" && key == "bold") boldFromWeight(props) else null
      if (fromWeight != null) {
        // The device draws this text at its `weight`; Bold has no effect there.
        if (fromWeight) input.setAttribute("checked", "checked")
        input.setAttribute("disabled", "disabled")
        input.setAttribute("title", "set by weight = ${props["weight"].let { if (it is Token) it.value else it }}; clear Weight to use Bold")
      } else {
        if (props[key] as? Boolean == true) input.setAttribute("checked", "checked")
        input.addEventListener("change", { _ -> editProp(nodeId, key, input.checked) })
      }
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
  tokenButtonIfAny(nodeId, nodeType, key, picker)?.let(rowEl::appendChild)
  // A Margin side ("margin.start") is one quarter of a Margin(...) literal: not bindable.
  if (kind in BINDABLE && keyPrefix.isEmpty() && '.' !in name) rowEl.appendChild(bindButton(nodeId, key, name))
  return rowEl
}

/** K4: the `/doc` features this editor decodes (an older relay ignores what it does not know). */
private const val DOC_FEATURES = "tokens=1&conditionals=1&extendedConditions=1&templates=1&lists=1&arithmetic=1"

/** K4: a template prop row — the template's text, and ◆ to insert a design token. */
private fun templateRow(rowEl: HTMLElement, nodeId: Int, key: String, t: Template, picker: HTMLElement): HTMLElement {
  val (field, append) = refValueEditor(
    templateSource(t), multiline = true,
    placeholder = "text with {field}, {item.x}, {◆C.Name}; {{ and }} are braces",
    parse = { parseTemplateSource(it, knownTokens(t)) },
  ) { v ->
    sendOps(listOf(valueOp(nodeId, key, v)), refreshPanels = false)
    renderBindings()
  }
  rowEl.appendChild(field)
  if (knownTokens(t).isNotEmpty()) {
    rowEl.appendChild(
      Ui.button("◆", "btn icon") {
        // A template reads String, Int and Boolean tokens — never a Double (the device prints 1.0 as "1").
        if (picker.firstChild != null) Ui.clear(picker) else renderInsertTokenPicker(picker, ::templateTokenOffered) { ref -> append("{◆$ref}") }
      }.also { it.setAttribute("title", "insert a design token") },
    )
  }
  return rowEl
}

/** K4: an Int / String list prop row — its elements, and ◆ to add a design token of the element kind. */
private fun listRow(rowEl: HTMLElement, nodeId: Int, key: String, value: Any?, kind: PropKind, picker: HTMLElement): HTMLElement {
  val strings = kind == PropKind.StringList
  val (field, append) = refValueEditor(
    listSource(value, kind), multiline = false,
    placeholder = if (strings) "a|b|◆C.Name|@field" else "16777215, #FF8800, ◆C.Name, @field",
    parse = { parseListSource(it, kind, knownTokens(value)) },
  ) { v ->
    sendOps(listOf(valueOp(nodeId, key, v)), refreshPanels = false)
    renderBindings()
  }
  rowEl.appendChild(field)
  val fits = { t: EditorToken -> t.kind == if (strings) "String" else "Int" }
  if (editorTokens.any { !it.ambiguous && fits(it) }) {
    rowEl.appendChild(
      Ui.button("◆", "btn icon") {
        if (picker.firstChild != null) Ui.clear(picker) else renderInsertTokenPicker(picker, fits, colours = true) { ref ->
          append((if (listSource(value, kind).isEmpty() && (field as? HTMLInputElement)?.value.isNullOrBlank()) "" else if (strings) "|" else ", ") + "◆$ref")
        }
      }.also { it.setAttribute("title", "add a design token element") },
    )
  }
  return rowEl
}

/** K4: a searchable list of the tokens [fits] accepts; choosing one hands its reference to [onPick]. */
private fun renderInsertTokenPicker(picker: HTMLElement, fits: (EditorToken) -> Boolean, colours: Boolean = false, onPick: (String) -> Unit) {
  Ui.clear(picker)
  val box = Ui.el("div", "card")
  val search = Ui.input()
  search.setAttribute("type", "text")
  search.setAttribute("placeholder", "search tokens")
  search.setAttribute("style", "width:100%; margin-bottom:4px;")
  val list = Ui.el("div")
  list.setAttribute("style", "max-height:180px; overflow:auto;")
  fun fill() {
    Ui.clear(list)
    val q = search.value.trim().lowercase()
    val shown = editorTokens.filter { !it.ambiguous && fits(it) }
      .filter { q.isEmpty() || q in it.ref.lowercase() || q in it.value.toString().lowercase() }
      .sortedBy { it.ref }
    if (shown.isEmpty()) list.appendChild(Ui.el("div", "muted", "no matching token"))
    shown.forEach { t ->
      val row = Ui.el("div", "pal-row")
      row.appendChild(tokenChip(t.ref, t.value, if (colours && t.kind == "Int") PropKind.Color else PropKind.Text))
      row.addEventListener("click", { _ ->
        Ui.clear(picker)
        onPick(t.ref)
      })
      list.appendChild(row)
    }
  }
  search.addEventListener("input", { _ -> fill() })
  box.appendChild(search)
  box.appendChild(list)
  picker.appendChild(box)
  fill()
  search.focus()
}

/** K2: a token's chip — a colour swatch for a colour, its reference, and its value. */
private fun tokenChip(ref: String, value: Any?, kind: PropKind): HTMLElement {
  val chip = Ui.el("span", "pill")
  chip.setAttribute("style", "flex:1; min-width:0; border-color:var(--accent); color:var(--text); overflow:hidden;")
  chip.setAttribute("title", "$ref = $value")
  if (kind == PropKind.Color && value is Int) {
    val sw = Ui.el("span")
    sw.setAttribute("style", "width:12px; height:12px; border-radius:3px; flex-shrink:0; border:1px solid var(--border); background:${argbToHex(value)};")
    chip.appendChild(sw)
  }
  chip.appendChild(Ui.el("span", "", "◆ $ref"))
  val shown = if (kind == PropKind.Color && value is Int) argbToHex(value) else value.toString()
  chip.appendChild(Ui.el("span", "muted", shown).also { it.setAttribute("style", "overflow:hidden; text-overflow:ellipsis; white-space:nowrap;") })
  return chip
}

/** K2: the ◆ button that opens [key]'s token picker — only when some token fits the prop. */
private fun tokenButtonIfAny(nodeId: Int, nodeType: String, key: String, picker: HTMLElement): HTMLElement? =
  if (tokensFor(nodeType, key, editorComponents).isEmpty()) null else tokenButton(nodeId, nodeType, key, picker)

private fun tokenButton(nodeId: Int, nodeType: String, key: String, picker: HTMLElement): HTMLElement =
  Ui.button("◆", "btn icon") {
    if (picker.firstChild != null) Ui.clear(picker) else renderTokenPicker(nodeId, nodeType, key, picker)
  }.also { it.setAttribute("title", "use a design token") }

/** K2: a searchable list of the tokens that fit [key]; choosing one binds the prop to it. */
private fun renderTokenPicker(nodeId: Int, nodeType: String, key: String, picker: HTMLElement) {
  Ui.clear(picker)
  val box = Ui.el("div", "card")
  val search = Ui.input()
  search.setAttribute("type", "text")
  search.setAttribute("placeholder", "search tokens")
  search.setAttribute("style", "width:100%; margin-bottom:4px;")
  val list = Ui.el("div")
  list.setAttribute("style", "max-height:180px; overflow:auto;")
  val kind = when (key.startsWith("mod.")) {
    true -> modifierSpecs.firstOrNull { it.name == key.removePrefix("mod.").substringBefore('.') }
      ?.props?.firstOrNull { it.name == key.removePrefix("mod.").substringAfter('.') }?.kind
    false -> (editorComponents.spec(nodeType)?.props ?: editableProps(nodeType)).firstOrNull { it.name == key }?.kind
  } ?: PropKind.Text
  fun fill() {
    Ui.clear(list)
    val fits = tokensFor(nodeType, key, editorComponents, query = search.value)
    if (fits.isEmpty()) list.appendChild(Ui.el("div", "muted", "no matching token"))
    fits.forEach { t ->
      val row = Ui.el("div", "pal-row")
      row.setAttribute("title", listOfNotNull(t.ref, t.file?.let { "in $it" }).joinToString(" "))
      row.appendChild(tokenChip(t.ref, t.value, kind))
      row.addEventListener("click", { _ ->
        val op = if (key.startsWith("mod.")) {
          DocOp.SetModifier(Handle(nodeId.toLong()), key.removePrefix("mod."), t.toPropValue())
        } else {
          DocOp.SetProp(Handle(nodeId.toLong()), key, t.toPropValue())
        }
        Ui.clear(picker)
        sendOps(listOf(op))
      })
      list.appendChild(row)
    }
  }
  search.addEventListener("input", { _ -> fill() })
  box.appendChild(search)
  box.appendChild(list)
  picker.appendChild(box)
  fill()
  search.focus()
}

private fun bindButton(nodeId: Int, key: String, name: String): HTMLElement =
  Ui.button("@", "btn icon") {
    sendOps(listOf(DocOp.SetProp(Handle(nodeId.toLong()), key, PropValue.Bind(name))))
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
  // K1: scoped layout modifiers (flex, margin, …) only exist inside their
  // parent's content lambda — offer them only under a Row/Column/Box they fit.
  val parentType = scopeParentType(portalTree.value, node.id)

  onNode.forEach { name ->
    val spec = modifierSpecs.firstOrNull { it.name == name }
    val chip = Ui.el("div", "mod-chip")
    val head = Ui.el("div", "mod-head")
    head.appendChild(Ui.el("span", "t", name))
    if (spec != null && !spec.isValidUnder(parentType)) {
      val warn = Ui.el("span", "muted", " · no effect here and not saved (needs a ${spec.scopes.joinToString("/")} parent)")
      warn.setAttribute("title", "scoped layout modifier: only meaningful inside ${spec.scopes.joinToString(", ")}")
      head.appendChild(warn)
    }
    val x = Ui.el("span", "x", "✕")
    fun remove() {
      val keys = node.props.keys.filter { it == "mod.$name" || it.startsWith("mod.$name.") }
      sendOps(keys.map { DocOp.RemoveModifier(Handle(node.id.toLong()), it.removePrefix("mod.")) })
    }
    // E41: a button for the keyboard and a screen reader too.
    x.setAttribute("role", "button")
    x.setAttribute("aria-label", "Remove $name")
    x.setAttribute("title", "Remove $name")
    x.tabIndex = 0
    x.addEventListener("click", { _ -> remove() })
    x.addEventListener("keydown", { ev ->
      val k = (ev as KeyboardEvent).key
      if (k == "Enter" || k == " ") { ev.preventDefault(); ev.stopPropagation(); remove() }
    })
    head.appendChild(x)
    chip.appendChild(head)
    spec?.props?.forEach { p ->
      chip.appendChild(propRow(node.id, node.type, node.props, p.name, p.kind, p.label, keyPrefix = "mod.$name."))
    }
    modsEl.appendChild(chip)
  }

  // K1: a component instance takes modifiers only if its signature declares
  // `modifier: Modifier` (the relay refuses them otherwise).
  val comp = editorComponents.spec(node.type)
  if (comp != null && !comp.acceptsModifier) {
    modsEl.appendChild(Ui.el("div", "muted", "${node.type} has no `modifier: Modifier` parameter"))
    return
  }

  // Add-modifier control.
  val addRow = Ui.el("div", "row")
  val sel = Ui.select()
  val available = modifiersOfferedUnder(parentType).filter { it !in onNode }
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
          "$name.${p.name}" to when {
            // flex/grow/shrink weights start at 1; alignments at Start (0).
            p.kind == PropKind.Double && p.name == "value" -> 1.0
            p.kind == PropKind.Int && "Alignment" in spec.argTypes.joinToString() -> 0
            else -> when (p.kind) {
              PropKind.Int -> 8
              PropKind.Double -> 8.0
              PropKind.Bool -> true
              PropKind.Color -> 0xFF8B7CF7.toInt()
              else -> ""
            }
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
  // refreshPanels=false: keeps input focus while typing; the preview updates live.
  sendOps(listOf(literalOp(id, name, value)), refreshPanels = false)
}

/** The op setting wire key [name] (a prop, or "mod.X.p") of node [id] to the literal [value]. */
private fun literalOp(id: Int, name: String, value: Any?): DocOp =
  if (name.startsWith("mod.")) {
    DocOp.SetModifier(Handle(id.toLong()), name.removePrefix("mod."), lit(value))
  } else {
    DocOp.SetProp(Handle(id.toLong()), name, lit(value))
  }

private fun argbToHex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')
private fun hexToArgb(hex: String): Int = (0xFF shl 24) or (hex.removePrefix("#").toIntOrNull(16) ?: 0)
