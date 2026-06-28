# Portal Rich Editor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a tree outline (with selection), a property panel, and HTML5 drag-drop to the portal — a real visual editor over the live canvas preview + export.

**Architecture:** `portal-core` gains stable `WidgetNode.id`, immutable tree-edit helpers, and a property catalog. `web-spike`'s `Portal.kt` is rewritten into a fixed-position DOM editor panel (palette · outline · properties · export) that mutates the shared `portalTree`; the canvas (engine A) re-renders live. Drag-drop uses native HTML5 DnD.

**Tech Stack:** Kotlin/wasm, `kotlinx-browser` DOM, Compose snapshot state.

**Branch:** `spike/keliver-web`. Builds need `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

---

## Files

- Modify: `portal-core/src/commonMain/kotlin/dev/keliver/portal/WidgetTree.kt` — `WidgetNode` → data class + `id`.
- Create: `portal-core/src/commonMain/kotlin/dev/keliver/portal/EditTree.kt` — tree-edit helpers.
- Create: `portal-core/src/commonMain/kotlin/dev/keliver/portal/Catalog.kt` — property catalog.
- Rewrite: `web-spike/src/wasmJsMain/kotlin/Portal.kt` — the editor panel.

(`Main.kt`, `RenderNode.kt`, `exportKotlin` are unchanged — they ignore `id`, and `Portal.kt` keeps `portalTree` + `mountPortalChrome()`.)

---

## Task 1: Model `id` + tree-edit helpers + property catalog (`portal-core`)

- [ ] **Step 1: `WidgetNode` → data class with `id`.** In `WidgetTree.kt`, replace the `class WidgetNode(...)` declaration (keep the getters + `sampleTree` below it unchanged):

```kotlin
private var nodeIdCounter = 0
fun nextNodeId(): Int = ++nodeIdCounter

/** One node = a widget type, its properties (by name), children, and a stable id. */
data class WidgetNode(
  val type: String,
  val props: Map<String, Any?> = emptyMap(),
  val children: List<WidgetNode> = emptyList(),
  val id: Int = nextNodeId(),
)
```

- [ ] **Step 2: Create `EditTree.kt`** — immutable helpers (preserve untouched ids via `copy`):

```kotlin
package dev.keliver.portal

fun WidgetNode.findNode(id: Int): WidgetNode? =
  if (this.id == id) this else children.firstNotNullOfOrNull { it.findNode(id) }

fun WidgetNode.updateProps(id: Int, props: Map<String, Any?>): WidgetNode =
  if (this.id == id) copy(props = props) else copy(children = children.map { it.updateProps(id, props) })

fun WidgetNode.removeNode(id: Int): WidgetNode =
  copy(children = children.filter { it.id != id }.map { it.removeNode(id) })

fun WidgetNode.insertChild(parentId: Int, child: WidgetNode, index: Int): WidgetNode =
  if (this.id == parentId) copy(children = children.toMutableList().apply { add(index.coerceIn(0, size), child) })
  else copy(children = children.map { it.insertChild(parentId, child, index) })

fun WidgetNode.moveNode(id: Int, newParentId: Int, index: Int): WidgetNode {
  val node = findNode(id) ?: return this
  if (id == newParentId || node.findNode(newParentId) != null) return this // no move into self/descendant
  return removeNode(id).insertChild(newParentId, node, index)
}
```

- [ ] **Step 3: Create `Catalog.kt`** — editable properties per widget type:

```kotlin
package dev.keliver.portal

enum class PropKind { Text, Int, Bool, Color, Double }

data class PropSpec(val name: String, val kind: PropKind, val label: String)

fun editableProps(type: String): List<PropSpec> = when (type) {
  "StyledText" -> listOf(PropSpec("text", PropKind.Text, "Text"), PropSpec("fontSize", PropKind.Int, "Font size"), PropSpec("bold", PropKind.Bool, "Bold"), PropSpec("colorArgb", PropKind.Color, "Color"))
  "Button" -> listOf(PropSpec("text", PropKind.Text, "Text"))
  "Spacer" -> listOf(PropSpec("height", PropKind.Double, "Height"))
  "AsyncImage" -> listOf(PropSpec("url", PropKind.Text, "URL"))
  "StyledBox" -> listOf(PropSpec("paddingDp", PropKind.Int, "Padding"), PropSpec("cornerRadiusDp", PropKind.Int, "Corner radius"), PropSpec("borderWidthDp", PropKind.Int, "Border width"), PropSpec("borderColorArgb", PropKind.Color, "Border color"))
  else -> emptyList()
}
```

- [ ] **Step 4: Compile portal-core + re-verify export wasn't broken by the model change**

Run:
```
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :portal-core:compileKotlinJvm :portal-core:compileKotlinWasmJs --console=plain 2>&1 | grep -E "^e: |BUILD"
rm -f web-spike-guest-compiler/src/main/kotlin/Exported.kt
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :web-spike-guest-compiler:run :web-spike-guest-compiler:compileKotlin --console=plain 2>&1 | grep -E "export:|^e: |BUILD"
```
Expected: both `BUILD SUCCESSFUL`; `export: wrote …` — the `id` field didn't break render/export.

- [ ] **Step 5: Commit**
```bash
git add portal-core
git commit -m "feat(portal): WidgetNode id + tree-edit helpers + property catalog"
```

---

## Task 2: Rewrite `Portal.kt` into the editor panel

**Files:** Rewrite `web-spike/src/wasmJsMain/kotlin/Portal.kt`.

- [ ] **Step 1: Replace the whole file** with the editor (palette + outline + properties + drag-drop + export):

```kotlin
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

private fun applyTree(t: WidgetNode) {
  portalTree.value = t
  Snapshot.sendApplyNotifications() // write happens outside composition (DOM callback) — flush
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
      "${"  ".repeat(depth)}▸ ${node.type}$label",
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
```

- [ ] **Step 2: Compile + link the dev dist**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :web-spike:wasmJsBrowserDevelopmentExecutableDistribution --console=plain 2>&1 | grep -E "^e: |BUILD"`
Expected: `BUILD SUCCESSFUL`. Fix any `kotlinx-browser` API mismatch against the compiler error (likely candidates: `HTMLInputElement.checked` getter, `DragEvent.dataTransfer` nullability) and re-run.

- [ ] **Step 3: Commit**
```bash
git add web-spike/src/wasmJsMain/kotlin/Portal.kt
git commit -m "feat(portal): rich editor — outline + selection + property panel + drag-drop"
```

---

## Task 3: Browser verification

**Files:** none (verification). Serve with cache-bust (per the dev-cache gotcha):
```bash
cd web-spike/build/dist/wasmJs/developmentExecutable
(lsof -ti tcp:8096 | xargs kill -9 2>/dev/null; true)
perl -pi -e 's{web-spike\.js(\?v=\w+)?}{web-spike.js?v=E1}' index.html
nohup python3 -m http.server 8096 >/tmp/portal-server.log 2>&1 &
cd -
```
Then in Chrome (extension) navigate `http://localhost:8096/index.html?b=e1`.

- [ ] **Step 1: Outline + selection.** Screenshot — the left panel shows Palette / Outline (`▸ StyledBox ▸ Column ▸ StyledText · "Select me…"`) / Properties, and the canvas preview on the right. Click the `StyledText` outline row → it highlights and the Properties panel shows Text/Font size/Bold/Color inputs (verify via `javascript_tool`: the props panel has inputs).

- [ ] **Step 2: Property edit → live preview.** Set the StyledText text input via JS and dispatch an `input` event, then screenshot — the preview's text updates live. Example:
```js
const inp = document.querySelectorAll('input[type=text]')[0];
inp.value = 'EDITED LIVE'; inp.dispatchEvent(new Event('input'));
```
Expected: the canvas preview shows "EDITED LIVE".

- [ ] **Step 3: Drag-drop (synthetic events).** The automation has no drag gesture, so dispatch DnD events via `javascript_tool`: a `dragstart` on a palette chip (set its `dataTransfer`) then `drop` on an outline row, OR directly assert the helper path by simulating the payload. Concretely, verify a palette drop inserts a node:
```js
const dt = new DataTransfer(); dt.setData('text','new:Button');
const target = [...document.querySelectorAll('#__nonexistent, div')].find(d=>d.textContent && d.textContent.includes('Column'));
target.dispatchEvent(new DragEvent('dragover',{bubbles:true,cancelable:true,dataTransfer:dt}));
target.dispatchEvent(new DragEvent('drop',{bubbles:true,cancelable:true,dataTransfer:dt}));
```
Then screenshot — the preview gained a Button and the outline shows a new `▸ Button` row. (If `DragEvent` construction with `dataTransfer` is restricted, fall back to verifying `handleDrop` indirectly by clicking a palette chip — click-add shares `insertChild` — and reordering via a `move:` synthetic drop.)

- [ ] **Step 4: Export reflects edits.** Click "Export Kotlin"; read the `<pre>` via JS; confirm it contains the edited text / added node.

- [ ] **Step 5: Record the result.** If all green, the rich editor is verified. If a step fails, read the console (`error|Exception`), fix `Portal.kt` or the helpers, rebuild, re-verify.

---

## Self-review

- **Spec coverage:** model id+helpers+catalog (Task 1) ✓; outline+selection + property panel (Task 2 `renderOutline`/`renderProps`, Task 3 Steps 1–2) ✓; drag-drop (Task 2 `dragstart`/`drop`, Task 3 Step 3) ✓. Out-of-scope items (undo, multi-select, positional drop) excluded.
- **Type consistency:** helpers/`PropSpec`/`PropKind`/`editableProps` are `dev.keliver.portal` and used with matching signatures in `Portal.kt`; `WidgetNode.copy` exists because Task 1 makes it a data class.
- **No placeholders:** full `Portal.kt` provided; the two real risks (wasm DOM API names; synthetic `DragEvent` construction) have explicit fix/fallback notes.

## Done when

In Chrome: selecting an outline node shows its properties; editing a property updates the preview live; a drag-drop (synthetic) inserts/reorders a node and the preview + outline reflect it; Export shows matching Kotlin.
