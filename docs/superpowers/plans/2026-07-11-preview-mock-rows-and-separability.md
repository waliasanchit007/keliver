# Repeat Preview Mock Rows + Editor Completeness + Separability Groundwork Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close dogfood friction #3 (Repeat preview renders N data-driven mock rows instead of one `{note.title}` template row), finish the editor's P2 surface (author action args; see & mock item fields and row counts), and lay separability groundwork (`keliver.portal.json` config consumed by the relay and dev script, plus a `new-screen` scaffolder). The **repo split and full `keliver init` new-project scaffolder are explicitly deferred** — they need published portal artifacts and a repo decision that belongs to the user.

**Architecture:** A pure `resolveItemRow(node, itemVar, index, mockOf)` in portal-core rewrites one Repeat-child subtree per mock row (item-scoped Binds → literal strings; everything else passes through; nested Repeats keep their own scope). `PreviewBindings` overlays it with the live mocks map using two conventions that reuse the existing mock inputs: the **items field's mock is the row count** (`mocks["notes"] = "2"`, default 3) and **item-field mocks are `|`-separated per-row values** (`mocks["note.title"] = "First|Second"`, clamped to last). The generated RenderNode's Repeat branch loops rows; the Condition branch honors a boolean mock. The relay reads a `keliver.portal.json` at the repo root (all fields defaulted to today's hardcoded values) so a future split-out app repo only needs that file.

**Tech Stack:** Kotlin MPP (portal-core commonMain; tests ride portal-document's commonTest infra — portal-render has no JVM target), the `:portal-schema-codegen` generator, kotlinx-serialization (relay config), Kotlin-DOM (wasm editor Portal.kt), bash + python3 (scripts), claude-in-chrome for editor verification (`Claude_Preview` 404s `.wasm`; dev server on :8096).

**Environment:** every gradle command needs `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`. Repo root: `/Users/sanchitwalia/AndroidStudioProjects/konduit`. Work lands on `main` (user's established choice).

---

### Task 1: Pure item-row resolver (portal-core) — TDD

**Files:**
- Create: `portal-core/src/commonMain/kotlin/dev/keliver/portal/ItemMocks.kt`
- Test: `portal-document/src/commonTest/kotlin/dev/keliver/portal/document/ItemMockTest.kt` (portal-document already depends on portal-core and has test infra)

Note: `WidgetNode` is a data class (`type, props: Map<String, Any?>, children, id`) — `copy` is available.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.keliver.portal.document

import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.resolveItemRow
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemMockTest {
  private val row = WidgetNode("Clickable", mapOf("onClick" to Action("openNote", "note.id")), listOf(
    WidgetNode("StyledText", mapOf("text" to Bind("note.title"), "fontSize" to 16)),
    WidgetNode("StyledText", mapOf("text" to Bind("subtitle"))), // screen bind: untouched
  ))

  @Test fun resolvesPipeSeparatedRowValuesAndClamps() {
    val mocks = mapOf("note.title" to "First|Second")
    val r0 = resolveItemRow(row, "note", 0, mocks::get)
    val r1 = resolveItemRow(row, "note", 1, mocks::get)
    val r2 = resolveItemRow(row, "note", 2, mocks::get)
    assertEquals("First", r0.children[0].props["text"])
    assertEquals("Second", r1.children[0].props["text"])
    assertEquals("Second", r2.children[0].props["text"]) // clamped to last
    assertEquals(Bind("subtitle"), r0.children[1].props["text"]) // screen binds untouched
    assertEquals(Action("openNote", "note.id"), r0.props["onClick"]) // actions untouched
  }

  @Test fun unmockedItemBindShowsNumberedPlaceholder() {
    val r = resolveItemRow(row, "note", 1) { null }
    assertEquals("{note.title} 2", r.children[0].props["text"])
  }

  @Test fun nestedRepeatKeepsItsOwnScope() {
    val nested = WidgetNode("Repeat", mapOf("items" to "tags", "item" to "tag"),
      listOf(WidgetNode("StyledText", mapOf("text" to Bind("tag.name")))))
    val out = resolveItemRow(WidgetNode("Column", emptyMap(), listOf(nested)), "note", 0) { null }
    assertEquals(Bind("tag.name"), out.children[0].children[0].props["text"])
  }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew :portal-document:jvmTest --tests "*ItemMockTest*"` → FAIL (unresolved `resolveItemRow`).

- [ ] **Step 3: Implement**

```kotlin
package dev.keliver.portal

/**
 * P2 preview fidelity for lists: resolve ONE mock row of a Repeat child
 * subtree. Item-scoped Binds ("note.title") become literal strings from
 * [mockOf] — pipe-separated per-row values ("First|Second"), clamped to the
 * last entry — falling back to a numbered "{note.title} N" placeholder.
 * Actions and screen binds pass through; a nested Repeat keeps its own scope.
 */
fun resolveItemRow(node: WidgetNode, itemVar: String, index: Int, mockOf: (String) -> String?): WidgetNode {
  if (node.type == "Repeat") return node
  val props = node.props.mapValues { (_, v) ->
    if (v is Bind && v.field.startsWith("$itemVar.")) {
      val rows = mockOf(v.field)?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }
      if (rows.isNullOrEmpty()) "{${v.field}} ${index + 1}" else rows[minOf(index, rows.size - 1)]
    } else {
      v
    }
  }
  return node.copy(props = props, children = node.children.map { resolveItemRow(it, itemVar, index, mockOf) })
}
```

- [ ] **Step 4: Run tests** — `./gradlew :portal-document:jvmTest` → PASS.
- [ ] **Step 5: Commit** — `feat(portal): pure per-row item-bind resolver for Repeat preview mocks`

---

### Task 2: PreviewBindings row helpers + generated Repeat/Condition branches

**Files:**
- Modify: `portal-render/src/commonMain/kotlin/dev/keliver/portal/render/PreviewBindings.kt`
- Modify: `portal-schema-codegen/src/main/kotlin/dev/keliver/portal/codegen/EmitRenderNode.kt` (the `"Condition", "Repeat" ->` line)
- Regenerate: `portal-render/.../GeneratedRenderNode.kt`

- [ ] **Step 1: PreviewBindings helpers** (append inside the object; add `import dev.keliver.portal.resolveItemRow`)

```kotlin
  /** Mocked preview row count for a Repeat: the ITEMS field's mock parses as an int (default 3, clamped 0..10). */
  fun rowCount(itemsField: String): Int = mocks[itemsField]?.trim()?.toIntOrNull()?.coerceIn(0, 10) ?: 3

  /** One preview row: item binds resolved against the mocks map ("a|b|c" = per-row values). */
  fun mockItemRow(node: WidgetNode, itemVar: String, index: Int): WidgetNode =
    resolveItemRow(node, itemVar, index, mocks::get)
```

- [ ] **Step 2: Generator branches** — in `emitRenderNode`, replace

```kotlin
appendLine("    \"Condition\", \"Repeat\" -> Column { node.children.forEach { RenderNode(it) } }")
```
with
```kotlin
// M5/P2: Condition honors a boolean mock (default shown); Repeat renders
// rowCount() mock rows with item binds resolved per row.
appendLine("    \"Condition\" -> {")
appendLine("      val field = (node.props[\"field\"] as? String) ?: \"\"")
appendLine("      if (PreviewBindings.mocks[field]?.toBooleanStrictOrNull() != false) {")
appendLine("        Column { node.children.forEach { RenderNode(it) } }")
appendLine("      }")
appendLine("    }")
appendLine("    \"Repeat\" -> Column {")
appendLine("      val itemVar = (node.props[\"item\"] as? String) ?: \"item\"")
appendLine("      val itemsField = (node.props[\"items\"] as? String) ?: \"items\"")
appendLine("      repeat(PreviewBindings.rowCount(itemsField)) { i ->")
appendLine("        node.children.forEach { RenderNode(PreviewBindings.mockItemRow(it, itemVar, i)) }")
appendLine("      }")
appendLine("    }")
```
(`Column` is already imported — the current Condition/Repeat branch uses it.)

- [ ] **Step 3: Regenerate + compile** — `./gradlew :portal-schema-codegen:generatePortalCode :portal-render:compileKotlinJs :portal-schema-codegen:checkPortalCode :web-spike:compileKotlinWasmJs` → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** — `feat(render): Repeat preview renders N mock rows; Condition honors boolean mock`

Note: the device DEV-overlay interpreter shares this RenderNode — on-device overlay previews of un-recompiled Repeat edits now show 3 numbered placeholder rows instead of 1 (no mocks on device); prod/compiled path unaffected.

---

### Task 3: Editor — action-arg authoring + Repeat rows & item-field mock hints

**Files:**
- Modify: `web-spike/src/wasmJsMain/kotlin/Portal.kt` (event rows ~line 711; `renderBindings` ~line 740)

- [ ] **Step 1: Event rows gain an arg input.** Replace the single-input event row body with two inputs sharing one send helper:

```kotlin
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
      val argIn = Ui.input()
      argIn.setAttribute("type", "text")
      argIn.setAttribute("placeholder", "arg: it / item.field")
      argIn.setAttribute("style", "max-width: 96px;")
      argIn.value = current?.arg ?: ""
      val send = { _: dev.keliver.portal.WidgetNode? ->
        val name = nameIn.value.trim()
        val arg = argIn.value.trim().ifEmpty { null }
        val op = if (name.isEmpty()) {
          DocOp.RemoveProp(Handle(node.id.toLong()), evName)
        } else {
          DocOp.SetProp(Handle(node.id.toLong()), evName, PropValue.Action(name, arg))
        }
        sendOps(listOf(op), refreshPanels = false)
        renderBindings()
      }
      nameIn.addEventListener("input", { _ -> send(null) })
      argIn.addEventListener("input", { _ -> send(null) })
      rowEl.appendChild(nameIn)
      rowEl.appendChild(argIn)
      propsEl.appendChild(rowEl)
    }
```
(If the lambda-type dance fights wasm, use a plain `fun sendNow()` local function — same body.)

- [ ] **Step 2: renderBindings — item-aware hints + Repeat row-count rows.** In the `contract.fields.forEach` loop, make the mock placeholder item-aware:

```kotlin
    mock.setAttribute("placeholder", if ('.' in field) "rows: a|b|c" else "mock value")
```
After the fields loop (before actions rendering), append Repeat row-count inputs:

```kotlin
    // P2: each Repeat's items field mocks its preview ROW COUNT.
    fun repeats(n: dev.keliver.portal.WidgetNode, out: MutableList<dev.keliver.portal.WidgetNode>) {
      if (n.type == "Repeat") out += n
      n.children.forEach { repeats(it, out) }
    }
    val reps = mutableListOf<dev.keliver.portal.WidgetNode>()
    repeats(portalTree.value, reps)
    reps.forEach { rep ->
      val items = (rep.props["items"] as? String) ?: return@forEach
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
```
(Match the file's existing import style — `WidgetNode` may already be imported unqualified; `Snapshot.sendApplyNotifications()` follows the existing mock-input listener pattern.)

- [ ] **Step 3: Compile** — `./gradlew :web-spike:compileKotlinWasmJs` → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** — `feat(editor): author action args; Repeat row-count + per-row item mocks in Bindings panel`

---

### Task 4: `keliver.portal.json` — relay config + dev-script consumption

**Files:**
- Create: `keliver.portal.json` (repo root)
- Create: `portal-relay/src/main/kotlin/PortalConfig.kt`
- Modify: `portal-relay/src/main/kotlin/Relay.kt` (PORT :37, root :39, appScreensDir :178, publish task :107, publish output :116)
- Modify: `scripts/keliver-dev.sh` (SERVER_PORT :23)

- [ ] **Step 1: the config file** (defaults == today's hardcoded values, so behavior is identical)

```json
{
  "port": 8077,
  "screensDir": "portal-app-lib/src/jsMain/kotlin/screens",
  "publishTask": ":portal-published-guest:compileDevelopmentZipline",
  "publishOutput": "portal-published-guest/build/zipline/Development",
  "store": "~/.keliver-portal"
}
```

- [ ] **Step 2: PortalConfig.kt**

```kotlin
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Separability groundwork: everything the portal-server needs to know about
 * the app repo it serves, read from keliver.portal.json at the repo root.
 * Every field defaults to this repo's layout, so the file is optional here
 * and REQUIRED only for a future split-out app repo.
 */
@Serializable
data class PortalConfig(
  val port: Int = 8077,
  val screensDir: String = "portal-app-lib/src/jsMain/kotlin/screens",
  val publishTask: String = ":portal-published-guest:compileDevelopmentZipline",
  val publishOutput: String = "portal-published-guest/build/zipline/Development",
  val store: String = "~/.keliver-portal",
)

fun loadPortalConfig(repoDir: File): PortalConfig {
  val f = File(repoDir, "keliver.portal.json")
  if (!f.exists()) return PortalConfig()
  return Json { ignoreUnknownKeys = true }.decodeFromString(PortalConfig.serializer(), f.readText())
}

fun PortalConfig.storeDir(): File =
  if (store.startsWith("~/")) File(System.getProperty("user.home"), store.removePrefix("~/")) else File(store)
```

- [ ] **Step 3: Relay.kt uses it** — after the `repoDir` val add `private val config = loadPortalConfig(repoDir)`; replace `private const val PORT = 8077` with `private val PORT = config.port`; `private val root = File(System.getProperty("user.home"), ".keliver-portal")` with `private val root = config.storeDir()`; `File(repoDir, "portal-app-lib/src/jsMain/kotlin/screens")` with `File(repoDir, config.screensDir)`; the publish ProcessBuilder task string with `config.publishTask`; the zipline output path with `File(repoDir, config.publishOutput)`. (Order matters: `repoDir` is declared at :45 but `PORT` at :37 — move the `repoDir`+`config` declarations above `PORT`.)

- [ ] **Step 4: keliver-dev.sh reads the port**

```bash
SERVER_PORT=$(python3 -c "import json;print(json.load(open('$ROOT/keliver.portal.json')).get('port',8077))" 2>/dev/null || echo 8077)
```

- [ ] **Step 5: Verify** — `./gradlew :portal-relay:installDist` green; boot the relay and confirm the banner still says `:8077` and publish still finds its paths (full boot check happens in Task 6). Quick config-override smoke: `cd $(mktemp -d) && echo '{"port": 9123}' > keliver.portal.json && PORTAL_REPO=$PWD <repo>/portal-relay/build/install/portal-relay/bin/portal-relay` → banner says `:9123` (Ctrl-C; screens dir won't exist — only the port line matters).

- [ ] **Step 6: Commit** — `feat(relay): keliver.portal.json — de-hardcode repo layout (separability groundwork)`

---

### Task 5: `scripts/keliver-new-screen.sh` scaffolder

**Files:**
- Create: `scripts/keliver-new-screen.sh` (chmod +x)

- [ ] **Step 1: the script** — generates a portal-recognizable screen + presenter skeleton in the configured screensDir and prints the PublishedEntry wiring line:

```bash
#!/bin/bash
# keliver new-screen — scaffold a portal-editable screen + hand-owned presenter.
# Usage: scripts/keliver-new-screen.sh <ScreenName>   (e.g. Profile)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME="${1:?usage: keliver-new-screen.sh <ScreenName>}"
[[ "$NAME" =~ ^[A-Z][A-Za-z0-9]*$ ]] || { echo "ScreenName must be UpperCamelCase"; exit 1; }
lower="$(echo "${NAME:0:1}" | tr '[:upper:]' '[:lower:]')${NAME:1}"
SCREENS_DIR="$ROOT/$(python3 -c "import json;print(json.load(open('$ROOT/keliver.portal.json')).get('screensDir','portal-app-lib/src/jsMain/kotlin/screens'))" 2>/dev/null || echo portal-app-lib/src/jsMain/kotlin/screens)"
LOGIC_DIR="$(dirname "$SCREENS_DIR")/logic"
SCREEN_FILE="$SCREENS_DIR/$lower.kt"
PRESENTER_FILE="$LOGIC_DIR/${NAME}Presenter.kt"
[ -e "$SCREEN_FILE" ] && { echo "refusing to overwrite $SCREEN_FILE"; exit 1; }
[ -e "$PRESENTER_FILE" ] && { echo "refusing to overwrite $PRESENTER_FILE"; exit 1; }
mkdir -p "$SCREENS_DIR" "$LOGIC_DIR"

cat > "$SCREEN_FILE" <<EOF
package dev.keliver.portalpublished.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledText

/** Scaffolded by keliver-new-screen — every node below is portal-editable. */
@Composable
fun ${NAME}Screen(b: ${NAME}ScreenBindings) {
  Column {
    StyledText(
      text = b.title,
      fontSize = 24,
      bold = true,
    )
  }
}

/** The round-trip boundary: implement this by hand; the portal never touches it. */
interface ${NAME}ScreenBindings {
  val title: String
}
EOF

cat > "$PRESENTER_FILE" <<EOF
package dev.keliver.portalpublished.logic

import androidx.compose.runtime.Composable
import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.portalpublished.screens.${NAME}ScreenBindings

/** HAND-OWNED: produce ${NAME}Screen's bindings (Style B presenter). */
@Composable
fun ${NAME}Presenter(sql: HostSqlDriver?): ${NAME}ScreenBindings {
  return object : ${NAME}ScreenBindings {
    override val title: String = "$NAME"
  }
}
EOF

echo "created  ${SCREEN_FILE#$ROOT/}"
echo "created  ${PRESENTER_FILE#$ROOT/}"
echo ""
echo "wire it in PublishedEntry.kt:  ${NAME}Screen(${NAME}Presenter(sql))"
echo "it is already live for editing: scripts/keliver-dev.sh, then select '$lower' in the editor"
```

- [ ] **Step 2: Verify** — `scripts/keliver-new-screen.sh Scratch` → both files created; `./gradlew :portal-app-lib:compileKotlinJs` green; then `git clean -f portal-app-lib/src/jsMain/kotlin/screens/scratch.kt portal-app-lib/src/jsMain/kotlin/logic/ScratchPresenter.kt` (verification artifacts, not kept).
- [ ] **Step 3: Commit** — `feat(scripts): keliver-new-screen scaffolder (config-aware)`

---

### Task 6: Live verification (editor in Chrome + full gates)

- [ ] **Step 1: full gates** — `./gradlew :portal-document:jvmTest :portal-ingest:test :portal-sql:jvmTest :portal-schema-codegen:test :portal-schema-codegen:checkPortalCode :portal-relay:installDist :web-spike:compileKotlinWasmJs` → green.
- [ ] **Step 2: boot** — `scripts/keliver-dev.sh` (server :8077 + editor :8096 + zipline :8080).
- [ ] **Step 3: Chrome checks (claude-in-chrome; Claude_Preview can't serve .wasm)** — open `http://localhost:8096`, switch to the `feed` screen; verify in the canvas: **3 mock rows** render (numbered `{note.title} 1..3` placeholders); in Bindings panel set `notes` rows to `2` → 2 rows; set `note.title` to `First|Second` → per-row titles. Mock edits are PreviewBindings-only — they never write to feed.kt (git stays clean).
- [ ] **Step 4: arg-authoring check without touching repo files** — create a store-side scratch screen (`curl -X POST localhost:8077/screens -d '{"project":"default","screen":"scratch"}'` — match the existing /screens POST shape; it's store-backed, not in-project), open it in the editor, add a Button, set its onClick action name `tapRow` and arg `it`; confirm `/doc?screen=scratch` shows `Action{name:tapRow, arg:"it"}`.
- [ ] **Step 5: teardown + `git status` clean** (except intended files).

---

### Task 7: Docs, memory, push

- [ ] **Step 1:** `docs/DOGFOOD_NOTES.md` — mark #3 FIXED (mock rows + conventions), #4 improved (item fields visible/mockable in the Bindings panel; editing item *interfaces* still ingest-only). `docs/PORTAL_USAGE.md` — document the two mock conventions, the event arg input, `keliver.portal.json`, and `keliver-new-screen.sh`. Note the deferred items (repo split, full `keliver init`) as the user's call.
- [ ] **Step 2:** commit + `git push origin main`.
- [ ] **Step 3:** update the memory file with outcomes + gotchas.

---

## Self-review notes

- Coverage: friction #3 (T1/T2), editor completeness incl. #4-visibility (T3), separability groundwork (T4/T5), verification (T6), docs (T7). Repo split + full init: deliberately out, flagged for the user. ✅
- Types: `resolveItemRow(WidgetNode, String, Int, (String) -> String?)` used identically in T1/T2; `PortalConfig` fields match the JSON and Relay call sites; scaffolder output uses only recognizer-grammar constructs. ✅
- Placeholders: none — all code inline. The one intentionally loose spot: the exact `/screens` POST body in T6-Step4 says "match the existing shape" because the route exists but its body wasn't re-read; executor must check `Relay.kt`'s `/screens` handler before calling. ✅ (acceptable: verification step, not implementation)
