# Portal Sub-project B — Export-to-Kotlin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `exportKotlin(tree)` turns a `WidgetNode` tree into real keliver guest Kotlin that compiles — verified by compiling the generated source.

**Architecture:** Extract `WidgetNode` + sample + `exportKotlin` into a shared `portal-core` (jvm + wasmJs) module. `web-spike`'s engine consumes it (wasm); the repurposed `web-spike-guest-compiler` (jvm) generates `Exported.kt` and compiles it as the gate. Render (`RenderNode`) and export (`exportKotlin`) are two backends over one `WidgetNode`.

**Tech Stack:** Kotlin Multiplatform, Gradle, keliver composables (jvm).

**Branch:** `spike/keliver-web`. Builds need `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

---

## Files

- Create: `portal-core/build.gradle`, `portal-core/src/commonMain/kotlin/dev/keliver/portal/WidgetTree.kt`, `.../Export.kt`
- Modify: `settings.gradle` (include `:portal-core`)
- Delete: `web-spike/src/wasmJsMain/kotlin/WidgetTree.kt` (moves to portal-core)
- Modify: `web-spike/build.gradle` (+portal-core dep), `web-spike/src/wasmJsMain/kotlin/RenderNode.kt` (+imports), `web-spike/src/wasmJsMain/kotlin/Main.kt` (drop `buildTree`, use `sampleTree`)
- Modify: `web-spike-guest-compiler/build.gradle` (deps), `.../GuestCompiler.kt` (generate `Exported.kt`)

---

## Task 1: `portal-core` module with the model + sample

**Files:**
- Create: `portal-core/build.gradle`
- Create: `portal-core/src/commonMain/kotlin/dev/keliver/portal/WidgetTree.kt`
- Modify: `settings.gradle`

- [ ] **Step 1: settings.gradle** — add the module. Append after the existing `web-spike` includes:

```gradle
include ':portal-core'
```

- [ ] **Step 2: portal-core/build.gradle** — plain MPP, jvm + wasmJs, no keliver deps:

```gradle
// Platform-agnostic portal core: the WidgetNode tree model + Kotlin codegen.
// No keliver/Compose deps — pure Kotlin, consumed by the wasm engine (web-spike)
// and the jvm export verifier (web-spike-guest-compiler).
apply plugin: 'org.jetbrains.kotlin.multiplatform'

kotlin {
  jvm()
  wasmJs {
    browser()
  }
}
```

- [ ] **Step 3: WidgetTree.kt** in `portal-core/src/commonMain/kotlin/dev/keliver/portal/` — the model + getters + sample (moved from web-spike, now packaged):

```kotlin
package dev.keliver.portal

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

/** The shared sample tree: a card whose item count is data-driven. */
fun sampleTree(items: Int): WidgetNode = WidgetNode(
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

- [ ] **Step 4: Compile-gate** (both targets)

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :portal-core:compileKotlinJvm :portal-core:compileKotlinWasmJs --console=plain 2>&1 | grep -E "^e: |BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle portal-core
git commit -m "feat(portal): portal-core module — WidgetNode model + sampleTree"
```

---

## Task 2: `exportKotlin` codegen in portal-core

**Files:**
- Create: `portal-core/src/commonMain/kotlin/dev/keliver/portal/Export.kt`

- [ ] **Step 1: Write the codegen** — mirrors `RenderNode`'s `when()`, emitting source:

```kotlin
package dev.keliver.portal

/**
 * Export a WidgetNode tree to real keliver guest Kotlin (a @Composable function).
 * The emitted call/args mirror RenderNode's interpreter so render == export.
 * (The two parallel when()s are an accepted MVP coupling; unify via schema later.)
 */
fun exportKotlin(tree: WidgetNode, functionName: String = "ExportedScreen"): String = buildString {
  appendLine("import androidx.compose.runtime.Composable")
  appendLine("import dev.keliver.layout.api.Constraint")
  appendLine("import dev.keliver.layout.api.CrossAxisAlignment")
  appendLine("import dev.keliver.layout.compose.Column")
  appendLine("import dev.keliver.layout.compose.Row")
  appendLine("import dev.keliver.layout.compose.Spacer")
  appendLine("import dev.keliver.material.compose.AsyncImage")
  appendLine("import dev.keliver.material.compose.Button")
  appendLine("import dev.keliver.material.compose.StyledBox")
  appendLine("import dev.keliver.material.compose.StyledText")
  appendLine("import dev.keliver.ui.Dp")
  appendLine()
  appendLine("@Composable")
  appendLine("fun $functionName() {")
  emitNode(tree, "  ")
  appendLine("}")
}

private fun StringBuilder.emitNode(node: WidgetNode, indent: String) {
  when (node.type) {
    "StyledBox" -> {
      appendLine("${indent}StyledBox(")
      appendLine("$indent  colorArgb = ${node.int("colorArgb")},")
      appendLine("$indent  gradientColorsArgb = ${intListLit(node.intList("gradientColorsArgb"))},")
      appendLine("$indent  gradientStops = ${floatListLit(node.floatList("gradientStops"))},")
      appendLine("$indent  gradientDirection = ${node.int("gradientDirection")},")
      appendLine("$indent  borderColorArgb = ${node.int("borderColorArgb")},")
      appendLine("$indent  borderWidthDp = ${node.int("borderWidthDp")},")
      appendLine("$indent  cornerRadiusDp = ${node.int("cornerRadiusDp")},")
      appendLine("$indent  fillWidth = ${node.bool("fillWidth")},")
      appendLine("$indent  paddingDp = ${node.int("paddingDp")},")
      appendLine("$indent  heightDp = ${node.int("heightDp")},")
      appendLine("$indent  contentAlignment = ${node.int("contentAlignment")},")
      appendLine("$indent) {")
      node.children.forEach { emitNode(it, "$indent  ") }
      appendLine("$indent}")
    }
    "Column" -> {
      appendLine("${indent}Column(")
      appendLine("$indent  width = ${if (node.bool("fillWidth", true)) "Constraint.Fill" else "Constraint.Wrap"},")
      appendLine("$indent  horizontalAlignment = CrossAxisAlignment.Stretch,")
      appendLine("$indent) {")
      node.children.forEach { emitNode(it, "$indent  ") }
      appendLine("$indent}")
    }
    "Row" -> {
      appendLine("${indent}Row(width = ${if (node.bool("fillWidth")) "Constraint.Fill" else "Constraint.Wrap"}) {")
      node.children.forEach { emitNode(it, "$indent  ") }
      appendLine("$indent}")
    }
    "StyledText" -> appendLine("${indent}StyledText(text = ${strLit(node.str("text"))}, fontSize = ${node.int("fontSize", 14)}, bold = ${node.bool("bold")}, colorArgb = ${node.int("colorArgb")})")
    "Button" -> appendLine("${indent}Button(text = ${strLit(node.str("text"))}, onClick = {})")
    "AsyncImage" -> appendLine("${indent}AsyncImage(url = ${strLit(node.str("url"))}, fillWidth = ${node.bool("fillWidth")})")
    "Spacer" -> appendLine("${indent}Spacer(height = Dp(${node.dbl("height")}))")
    else -> appendLine("$indent// unknown widget: ${node.type}")
  }
}

private fun strLit(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun intListLit(xs: List<Int>): String = if (xs.isEmpty()) "emptyList()" else "listOf(${xs.joinToString(", ")})"

private fun floatListLit(xs: List<Float>): String = if (xs.isEmpty()) "emptyList()" else "listOf(${xs.joinToString(", ") { "${it}f" }})"
```

- [ ] **Step 2: Compile-gate**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :portal-core:compileKotlinJvm --console=plain 2>&1 | grep -E "^e: |BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add portal-core/src/commonMain/kotlin/dev/keliver/portal/Export.kt
git commit -m "feat(portal): exportKotlin — WidgetNode tree -> keliver guest Kotlin source"
```

---

## Task 3: Rewire web-spike onto portal-core

**Files:**
- Delete: `web-spike/src/wasmJsMain/kotlin/WidgetTree.kt`
- Modify: `web-spike/build.gradle`, `RenderNode.kt`, `Main.kt`

- [ ] **Step 1: Delete the moved file**

```bash
git rm web-spike/src/wasmJsMain/kotlin/WidgetTree.kt
```

- [ ] **Step 2: web-spike/build.gradle** — add the dep. Insert after the `keliver-layout-compose` line:

```gradle
        implementation project(':portal-core')                 // WidgetNode model + exportKotlin
```

- [ ] **Step 3: RenderNode.kt** — add imports for the now-packaged model. Insert after the file's first `import` line (`import androidx.compose.runtime.Composable`):

```kotlin
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.bool
import dev.keliver.portal.dbl
import dev.keliver.portal.floatList
import dev.keliver.portal.int
import dev.keliver.portal.intList
import dev.keliver.portal.str
```

- [ ] **Step 4: Main.kt** — drop the local `buildTree` and use `sampleTree`. Delete the entire `private fun buildTree(items: Int): WidgetNode = ...` block at the bottom of the file. Add imports near the other `dev.keliver` imports:

```kotlin
import dev.keliver.portal.sampleTree
```

Then replace the two `buildTree(` call sites:
- `val treeState = remember { mutableStateOf(buildTree(1)) }` → `val treeState = remember { mutableStateOf(sampleTree(1)) }`
- inside the auto-driver: `treeState.value = buildTree(items)` → `treeState.value = sampleTree(items)`
- inside the host button: `onClick = { items += 1; treeState.value = buildTree(items) }` → `onClick = { items += 1; treeState.value = sampleTree(items) }`

- [ ] **Step 5: Compile + link the dev dist**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :web-spike:wasmJsBrowserDevelopmentExecutableDistribution --console=plain 2>&1 | grep -E "^e: |BUILD"`
Expected: `BUILD SUCCESSFUL`. (The engine logic is unchanged — only the model's home moved — so no browser re-verify is needed; the green wasm build is sufficient.)

- [ ] **Step 6: Commit**

```bash
git add web-spike
git commit -m "refactor(portal): web-spike engine consumes portal-core (WidgetNode + sampleTree)"
```

---

## Task 4: Repurpose the guest-compiler as the export verifier

**Files:**
- Modify: `web-spike-guest-compiler/build.gradle`
- Modify: `web-spike-guest-compiler/src/main/kotlin/GuestCompiler.kt`

- [ ] **Step 1: build.gradle** — replace the whole `dependencies { }` block with just what's needed to *compile* generated code:

```gradle
dependencies {
  implementation project(':portal-core')          // WidgetNode + exportKotlin + sampleTree
  implementation project(':keliver-material-compose')  // composables the generated code calls
  implementation project(':keliver-layout-compose')
  implementation project(':keliver-runtime')       // Dp
  implementation libs.jetbrains.compose.runtime    // @Composable
}
```

- [ ] **Step 2: GuestCompiler.kt** — replace the whole file with the export generator:

```kotlin
/*
 * spike/keliver-web portal sub-project B — the EXPORT VERIFIER.
 * Generates Exported.kt from the sample tree via exportKotlin(); compiling this
 * module then proves the generated Kotlin is valid guest source that uses the
 * keliver composables correctly. (Repurposed from the dead gate-3 screen.json tool.)
 */
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.sampleTree
import java.io.File

private const val OUT = "/Users/sanchitwalia/AndroidStudioProjects/konduit/web-spike-guest-compiler/src/main/kotlin/Exported.kt"

fun main() {
  val source = exportKotlin(sampleTree(3))
  File(OUT).writeText(source)
  println("export: wrote ${source.length} chars -> $OUT")
}
```

- [ ] **Step 3: Compile the generator (no Exported.kt yet)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :web-spike-guest-compiler:compileKotlin --console=plain 2>&1 | grep -E "^e: |BUILD"`
Expected: `BUILD SUCCESSFUL` (only `GuestCompiler.kt` exists; it must compile against portal-core).

- [ ] **Step 4: Commit**

```bash
git add web-spike-guest-compiler/build.gradle web-spike-guest-compiler/src/main/kotlin/GuestCompiler.kt
git commit -m "feat(portal): repurpose guest-compiler as the Export-to-Kotlin verifier"
```

---

## Task 5: Verify — generate and compile the exported Kotlin

**Files:** generates `web-spike-guest-compiler/src/main/kotlin/Exported.kt`.

- [ ] **Step 1: Generate `Exported.kt`** (remove any stale copy first so the generator compiles clean)

Run: `cd /Users/sanchitwalia/AndroidStudioProjects/konduit && rm -f web-spike-guest-compiler/src/main/kotlin/Exported.kt && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :web-spike-guest-compiler:run --console=plain 2>&1 | grep -E "export:|BUILD"`
Expected: `export: wrote <N> chars -> …/Exported.kt` and `BUILD SUCCESSFUL`.

- [ ] **Step 2: Inspect the generated source**

Run: `cat web-spike-guest-compiler/src/main/kotlin/Exported.kt`
Expected: a `@Composable fun ExportedScreen()` with `StyledBox(...) { Column(...) { StyledText(...); Spacer(...); … 3 "• item N" lines } }`, matching the live preview's structure.

- [ ] **Step 3: COMPILE the generated source — the verification gate**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :web-spike-guest-compiler:compileKotlin --console=plain 2>&1 | grep -E "^e: |BUILD"`
Expected: `BUILD SUCCESSFUL` — the exported Kotlin is valid guest source. If `e:` errors, fix `exportKotlin` (an emitted arg/literal doesn't match a composable signature), `rm Exported.kt`, and re-run Steps 1+3.

- [ ] **Step 4: Commit the generated artifact**

```bash
git add web-spike-guest-compiler/src/main/kotlin/Exported.kt
git commit -m "test(portal): Exported.kt — generated guest Kotlin compiles green (sub-project B verified)"
```

---

## Self-review

- **Spec coverage:** portal-core w/ model+sample (Task 1) ✓; `exportKotlin` mirroring RenderNode (Task 2) ✓; web-spike consumes portal-core (Task 3) ✓; repurposed guest-compiler generates+compiles (Tasks 4–5) ✓. Out-of-scope items (unify when()s, modifiers/events, hex colors) excluded.
- **Type consistency:** `WidgetNode`/getters/`sampleTree`/`exportKotlin` all `dev.keliver.portal`; the emitted args (Task 2) match the composable signatures captured in sub-project A planning and the same args `RenderNode` reads.
- **No placeholders:** every code step is complete; the chicken-egg (generator compiling its own output) is handled by `rm Exported.kt` before `run`.

## Done when

`:web-spike-guest-compiler:run` emits `Exported.kt` from `sampleTree(3)` and `:web-spike-guest-compiler:compileKotlin` compiles it green.
