# Portal Sub-project A — Runtime Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A generic `WidgetNode` tree renders live in the browser via a `@Composable` interpreter, and mutating the tree updates the preview — the portal's engine, verified with real pixels.

**Architecture:** Reuse the gate-5 browser-side guest. The guest composition body becomes `RenderNode(treeState.value)` instead of a hardcoded screen; `RenderNode` maps each node to the real keliver composable via a `when(type)` over a curated widget subset. A host-side "Add item" control mutates the tree `MutableState`; Compose recomposes the guest, emitting minimal protocol changes to the host canvas.

**Tech Stack:** Kotlin/wasm, Compose, keliver-material/layout composables, the protocol pipeline from gate 5.

**Branch:** `spike/keliver-web`. Builds need `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

**Verification note:** This is Compose-canvas interpreter work; the "tests" are the wasm compile gate plus a real-browser visual check (the same method used for gates 1–6) — classic unit TDD doesn't fit a canvas renderer. Each task ends with a gate whose output must be observed before checking the box.

---

## Files

- Create: `web-spike/src/wasmJsMain/kotlin/WidgetTree.kt` — the `WidgetNode` model + typed prop getters.
- Create: `web-spike/src/wasmJsMain/kotlin/RenderNode.kt` — the `@Composable` interpreter (`when(type)` over the subset).
- Modify: `web-spike/src/wasmJsMain/kotlin/Main.kt` — drive the guest with `RenderNode(treeState.value)`; add `buildTree(n)` + a host "Add item" control.

---

## Task 1: WidgetNode model

**Files:**
- Create: `web-spike/src/wasmJsMain/kotlin/WidgetTree.kt`

- [ ] **Step 1: Write the model + getters**

```kotlin
/*
 * spike/keliver-web portal sub-project A — the WidgetNode tree model.
 * A generic, name-keyed description of a keliver composition: the single source
 * of truth the portal edits. In-memory Any? values are enough for the web-first
 * MVP (portal + preview are one app); serializable values come in M2.
 */

/** One node = a widget type, its properties (by name), and children. */
class WidgetNode(
  val type: String,
  val props: Map<String, Any?> = emptyMap(),
  val children: List<WidgetNode> = emptyList(),
)

fun WidgetNode.str(key: String, default: String = ""): String = props[key] as? String ?: default
fun WidgetNode.int(key: String, default: Int = 0): Int = props[key] as? Int ?: default
fun WidgetNode.bool(key: String, default: Boolean = false): Boolean = props[key] as? Boolean ?: default
fun WidgetNode.dbl(key: String, default: Double = 0.0): Double = props[key] as? Double ?: default

@Suppress("UNCHECKED_CAST")
fun WidgetNode.intList(key: String): List<Int> = props[key] as? List<Int> ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun WidgetNode.floatList(key: String): List<Float> = props[key] as? List<Float> ?: emptyList()
```

- [ ] **Step 2: Compile-gate**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :web-spike:compileKotlinWasmJs --console=plain 2>&1 | grep -E "^e: |BUILD"`
Expected: `BUILD SUCCESSFUL` (no `e:` errors).

- [ ] **Step 3: Commit**

```bash
git add web-spike/src/wasmJsMain/kotlin/WidgetTree.kt
git commit -m "feat(portal): WidgetNode tree model (sub-project A)"
```

---

## Task 2: RenderNode interpreter

**Files:**
- Create: `web-spike/src/wasmJsMain/kotlin/RenderNode.kt`

- [ ] **Step 1: Write the interpreter** — maps each node to the real keliver composable. Signatures are taken verbatim from the generated composables.

```kotlin
/*
 * spike/keliver-web portal sub-project A — the runtime engine.
 * RenderNode is a guest @Composable that interprets a WidgetNode tree into real
 * keliver composables. Editing the tree (a MutableState) recomposes this and the
 * host re-renders the minimal change — no recompile, no protocol tags by hand.
 * The per-widget when() covers the curated MVP subset; it is codegen-able from
 * the schema later, and the same mapping drives Export-to-Kotlin (sub-project B).
 */
import androidx.compose.runtime.Composable
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Row
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.AsyncImage
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.ui.Dp

@Composable
fun RenderNode(node: WidgetNode) {
  when (node.type) {
    "StyledBox" -> StyledBox(
      colorArgb = node.int("colorArgb"),
      gradientColorsArgb = node.intList("gradientColorsArgb"),
      gradientStops = node.floatList("gradientStops"),
      gradientDirection = node.int("gradientDirection"),
      borderColorArgb = node.int("borderColorArgb"),
      borderWidthDp = node.int("borderWidthDp"),
      cornerRadiusDp = node.int("cornerRadiusDp"),
      fillWidth = node.bool("fillWidth"),
      paddingDp = node.int("paddingDp"),
      heightDp = node.int("heightDp"),
      contentAlignment = node.int("contentAlignment"),
    ) { node.children.forEach { RenderNode(it) } }

    "Column" -> Column(
      width = if (node.bool("fillWidth", true)) Constraint.Fill else Constraint.Wrap,
      horizontalAlignment = CrossAxisAlignment.Stretch,
    ) { node.children.forEach { RenderNode(it) } }

    "Row" -> Row(
      width = if (node.bool("fillWidth")) Constraint.Fill else Constraint.Wrap,
    ) { node.children.forEach { RenderNode(it) } }

    "StyledText" -> StyledText(
      text = node.str("text"),
      fontSize = node.int("fontSize", 14),
      bold = node.bool("bold"),
      colorArgb = node.int("colorArgb"),
    )

    "Button" -> Button(text = node.str("text"), onClick = {})

    "AsyncImage" -> AsyncImage(url = node.str("url"), fillWidth = node.bool("fillWidth"))

    "Spacer" -> Spacer(height = Dp(node.dbl("height")))

    else -> StyledText(text = "⚠ unknown widget: ${node.type}", colorArgb = 0xFFB00020.toInt())
  }
}
```

- [ ] **Step 2: Compile-gate**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :web-spike:compileKotlinWasmJs --console=plain 2>&1 | grep -E "^e: |BUILD"`
Expected: `BUILD SUCCESSFUL`. If a composable arg name/type mismatches, fix it against the generated signature and re-run.

- [ ] **Step 3: Commit**

```bash
git add web-spike/src/wasmJsMain/kotlin/RenderNode.kt
git commit -m "feat(portal): RenderNode @Composable interpreter over keliver subset"
```

---

## Task 3: Drive the guest from a tree + add an edit control

**Files:**
- Modify: `web-spike/src/wasmJsMain/kotlin/Main.kt`

- [ ] **Step 1: Add `buildTree(n)`** at the bottom of `Main.kt` (replaces the old `GuestUi`/demo composable). This builds the sample preview tree with `n` list items.

```kotlin
/** Sample portal tree: a card whose item count is data-driven. */
private fun buildTree(items: Int): WidgetNode = WidgetNode(
  type = "StyledBox",
  props = mapOf(
    "gradientColorsArgb" to listOf(0xFFFFF4E8.toInt(), 0xFFFFE9D6.toInt()),
    "gradientStops" to listOf(0.0f, 1.0f),
    "gradientDirection" to 3,
    "borderColorArgb" to 0xFFFFD9B0.toInt(),
    "borderWidthDp" to 1,
    "cornerRadiusDp" to 12,
    "fillWidth" to true,
    "paddingDp" to 20,
  ),
  children = listOf(
    WidgetNode(
      type = "Column",
      children = buildList {
        add(WidgetNode("StyledText", mapOf("text" to "PORTAL PREVIEW · rendered from a tree", "fontSize" to 12, "bold" to true, "colorArgb" to 0xFF8A8A8A.toInt())))
        add(WidgetNode("Spacer", mapOf("height" to 10.0)))
        add(WidgetNode("StyledText", mapOf("text" to "items: $items", "fontSize" to 24, "bold" to true, "colorArgb" to 0xFF111111.toInt())))
        add(WidgetNode("Spacer", mapOf("height" to 8.0)))
        repeat(items) { i ->
          add(WidgetNode("StyledText", mapOf("text" to "• item ${i + 1}", "fontSize" to 16, "colorArgb" to 0xFF333333.toInt())))
        }
      },
    ),
  ),
)
```

- [ ] **Step 2: Add tree state + edit control + drive the guest.** In the `CanvasBasedWindow` content of `Main.kt`, replace the `taps` state and the `composition.setContent { GuestUi() }` line and the host `RawColumn` body.

Replace this (current gate-5 code):
```kotlin
    var taps by remember { mutableStateOf(0) }
```
with:
```kotlin
    var items by remember { mutableStateOf(1) }
    val treeState = remember { mutableStateOf(buildTree(1)) }
```

Replace:
```kotlin
      composition.setContent { GuestUi() }
```
with:
```kotlin
      composition.setContent { RenderNode(treeState.value) }
```

Replace the host UI block:
```kotlin
    RawColumn(modifier = Modifier.padding(8.dp)) {
      RawText("browser-side keliver guest · taps recompose locally, no network · host events=$taps", fontSize = 11.sp)
      root.Render()
    }
```
with:
```kotlin
    RawColumn(modifier = Modifier.padding(8.dp)) {
      RawText("portal engine · preview is rendered from an in-memory WidgetNode tree · items=$items", fontSize = 11.sp)
      RawButton(onClick = { items += 1; treeState.value = buildTree(items) }) { RawText("Add item to preview") }
      root.Render()
    }
```

- [ ] **Step 3: Fix imports.** In `Main.kt`, add `import androidx.compose.material3.Button as RawButton`. Remove the now-unused gate-5 event-sink `taps++` line inside the `UiEventSink { … }` (change it to `UiEventSink { uiEvent -> guestAdapter.sendEvent(uiEvent.toProtocol()) }`), and delete the old `GuestUi()` composable and its now-unused imports (`Button`, `StyledBox`, `StyledText`, `Column`, `Spacer`, `Constraint`, `CrossAxisAlignment`, `Dp` if only used there — the compiler will flag unused; leave imports that RenderNode.kt owns since that's a separate file).

- [ ] **Step 4: Compile + link the dev dist**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :web-spike:wasmJsBrowserDevelopmentExecutableDistribution --console=plain 2>&1 | tail -2`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add web-spike/src/wasmJsMain/kotlin/Main.kt
git commit -m "feat(portal): drive the browser guest from a WidgetNode tree + edit control"
```

---

## Task 4: Browser verification (real pixels)

**Files:** none (verification only).

- [ ] **Step 1: Serve the fresh dist (cache-bust).** The dev `web-spike.js` has a stable filename → Chrome caches it; bust it.

```bash
cd web-spike/build/dist/wasmJs/developmentExecutable
(lsof -ti tcp:8096 | xargs kill -9 2>/dev/null; true)
perl -pi -e 's{web-spike\.js(\?v=\d+)?}{web-spike.js?v=A1}' index.html
nohup python3 -m http.server 8096 >/tmp/portal-server.log 2>&1 &
cd -
```

- [ ] **Step 2: Load + screenshot the initial preview.** Via the claude-in-chrome extension: `navigate` to `http://localhost:8096/index.html?b=a1`, wait for the wasm to instantiate, `screenshot`.
Expected: the host header `… items=1` + a host "Add item to preview" button, and **below it the tree-rendered card**: "PORTAL PREVIEW · rendered from a tree", "items: 1", and "• item 1".

- [ ] **Step 3: Click "Add item" twice, screenshot after each.** Click the host button (find its coordinates from the screenshot), screenshot, click again, screenshot.
Expected: the preview updates live — "items: 2" then "items: 3", with "• item 2" / "• item 3" appearing. This proves a tree edit drives the live render (the engine working end-to-end).

- [ ] **Step 4: Record the result.** If green, the engine is verified. If the preview doesn't update, read the browser console (`read_console_messages` pattern `error|Exception`) and debug the tree-state → guest-recompose path before claiming done.

---

## Self-review

- **Spec coverage:** tree model (Task 1) ✓; `@Composable` interpreter over the subset (Task 2) ✓; drive guest from tree + verify edit→live update in browser (Tasks 3–4) ✓. Export, portal UI, device transport, catalog-from-schema = explicitly out of scope per spec.
- **Type consistency:** `WidgetNode` / `str/int/bool/dbl/intList/floatList` defined in Task 1 are used consistently in Tasks 2–3; composable arg names match the generated signatures captured during planning.
- **No placeholders:** every code step is complete; the one runtime risk (guest observing a host-owned `MutableState`) is real but sound — Compose's snapshot system is global within the wasm runtime, so a host write notifies the guest recomposer (verified conceptually; Task 4 confirms with pixels).

## Done when

The browser shows a card rendered from the `WidgetNode` tree, and clicking "Add item" makes the preview grow live — confirmed with screenshots in Chrome.
