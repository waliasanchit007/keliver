# Single-Arg Action Events + List→Detail Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close dogfood frictions #1 and #2 — the recognizer/exporter learn single-arg action lambdas (`{ b.onDraftChange(it) }` for event payloads, `{ b.openNote(note.id) }` for item-carrying actions), enabling portal-authored text input and hand-owned list→detail navigation; Field Notes is retrofitted as the acceptance test and re-verified on-device end to end.

**Architecture:** `PropValue.Action` gains an optional `arg` (`"it"` = the event's payload, `"item.field"` = item-scoped data, `null` = today's zero-arg). The recognizer parses the two new lambda shapes; the generated exporter emits them back byte-identically and generates typed action signatures (`fun onDraftChange(value: String)`) using a new generated `eventParamType` map (widget-event → Kotlin param type, extracted from the schema FIR). Navigation stays hand-owned: `PublishedEntry` keeps a route state and per-item actions carry the note id — no new portal primitive (YAGNI until dogfooding demands one).

**Tech Stack:** Kotlin MPP, kotlinx-serialization (wire compat via defaulted fields), kotlin-compiler-embeddable PSI (recognizer), the `:portal-schema-codegen` generator (regenerates GeneratedCatalog/GeneratedExporter/GeneratedRenderNode), SQLDelight-over-Zipline, Pixel_9 emulator for device proof.

**Environment:** every gradle command needs `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`; device tasks need `export ANDROID_HOME=$HOME/Library/Android/sdk`. Repo root: `/Users/sanchitwalia/AndroidStudioProjects/konduit`.

---

### Task 0: Tag the Phase-1 checkpoint

**Files:** none (git only).

- [ ] **Step 1: Tag main at the dogfood commit and push the tag**

```bash
cd /Users/sanchitwalia/AndroidStudioProjects/konduit
git tag -a portal-v2.0 3b6c66ab0 -m "V2 portal + Phase 1 (dev loop, per-item Repeat binding, Field Notes dogfood)"
git push origin portal-v2.0
```

Expected: tag visible on origin.

---

### Task 1: Wire model — `Action.arg` + `Contract.actionParams`

**Files:**
- Modify: `portal-document/src/commonMain/kotlin/dev/keliver/portal/document/DocNode.kt` (PropValue.Action, Contract)
- Modify: `portal-document/src/commonMain/kotlin/dev/keliver/portal/document/Project.kt:34,51` (projection both ways)
- Modify: `portal-core/src/commonMain/kotlin/dev/keliver/portal/Bindings.kt` (tree-side Action)
- Modify: `portal-core/src/commonMain/kotlin/dev/keliver/portal/Serialize.kt:33,50` (V1 tree wire)
- Test: `portal-document/src/commonTest/kotlin/dev/keliver/portal/document/WireTest.kt`

- [ ] **Step 1: Write the failing tests** (append to WireTest.kt)

```kotlin
@Test fun actionArgSerializesAndOldJsonStillDecodes() {
  val a: PropValue = PropValue.Action("openNote", arg = "note.id")
  val json = docJson.encodeToString(PropValue.serializer(), a)
  assertEquals(a, docJson.decodeFromString(PropValue.serializer(), json))
  // wire compat: a pre-arg document must still decode (arg defaults to null)
  val old = json.replace(Regex(",?\"arg\":\"note.id\""), "")
  assertEquals(PropValue.Action("openNote"), docJson.decodeFromString(PropValue.serializer(), old))
}
```

(Use the file's existing Json instance name — if it differs from `docJson`, match it.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :portal-document:jvmTest --tests "*WireTest*"` → FAIL (no `arg` parameter).

- [ ] **Step 3: Implement the wire changes**

`DocNode.kt`:
```kotlin
@Serializable
data class Action(val name: String, val arg: String? = null) : PropValue
```
and
```kotlin
@Serializable
data class Contract(
  val fields: Map<String, String> = emptyMap(), // name -> Kotlin type
  val actions: List<String> = emptyList(),
  val actionParams: Map<String, String> = emptyMap(), // action name -> single param Kotlin type
)
```

`Bindings.kt`: `data class Action(val name: String, val arg: String? = null)`

`Project.kt`: line 34 → `put(name, Action(v.name, v.arg))`; line 51 → `is Action -> PropValue.Action(v.name, v.arg)`

`Serialize.kt`: line 33 → `is Action -> { put("action", v.name); v.arg?.let { put("actionArg", it) } }`; line 50 → `"action" in o -> Action(o.getValue("action").jsonPrimitive.content, o["actionArg"]?.jsonPrimitive?.content)`

- [ ] **Step 4: Run tests** — `./gradlew :portal-document:jvmTest :portal-core:compileKotlinJvm` (or jsTest equivalents) → PASS.

- [ ] **Step 5: Commit** — `feat(portal): Action prop values carry an optional single arg (wire + projection)`

---

### Task 2: Generator — typed event params, arg-aware emission, item-scoped action args

**Files:**
- Modify: `portal-schema-codegen/src/main/kotlin/dev/keliver/portal/codegen/PropModel.kt` (EventPlan)
- Modify: `portal-schema-codegen/src/main/kotlin/dev/keliver/portal/codegen/EmitExporter.kt` (fmtAction, eventParamType map, itemFieldsOf, interface emission)
- Modify: `portal-core/src/commonMain/kotlin/dev/keliver/portal/Bindings.kt` (ScreenContract.actionParams, collectContract)
- Regenerate: `portal-core/.../GeneratedCatalog.kt`, `GeneratedExporter.kt`, `portal-render/.../GeneratedRenderNode.kt`

- [ ] **Step 1: EventPlan carries the single param's Kotlin type**

`PropModel.kt`:
```kotlin
data class EventPlan(val name: String, val paramCount: Int, val paramType: String? = null)

internal fun mapEventParamType(t: FqType): String? = when (t.names.joinToString(".")) {
  "kotlin.String" -> "String"
  "kotlin.Int" -> "Int"
  "kotlin.Boolean" -> "Boolean"
  "kotlin.Float" -> "Float"
  "kotlin.Double" -> "Double"
  "kotlin.Long" -> "Long"
  else -> null
}
```
In `planWidget`'s `is Widget.Event ->` branch:
```kotlin
events += EventPlan(trait.name, trait.parameters.size,
  trait.parameters.singleOrNull()?.type?.let { mapEventParamType(it) })
```

- [ ] **Step 2: EmitExporter — generated `fmtAction` + `eventParamType` map**

Add to the trimMargin prelude (beside `bindRef`):
```
|// Phase 2: single-arg actions. arg=="it" -> event payload; "item.field" -> item-scoped.
|private fun fmtAction(a: Action, paramCount: Int): String {
|  val underscores = List(paramCount) { "_" }.joinToString(", ")
|  return when {
|    paramCount == 0 -> "{ b.${'$'}{a.name}(${'$'}{a.arg ?: ""}) }"
|    a.arg == "it" && paramCount == 1 -> "{ b.${'$'}{a.name}(it) }"
|    a.arg != null -> "{ ${'$'}underscores -> b.${'$'}{a.name}(${'$'}{a.arg}) }"
|    else -> "{ ${'$'}underscores -> b.${'$'}{a.name}() }"
|  }
|}
```
Replace the per-event emission loop body with:
```kotlin
for (e in w.events) {
  appendLine("      (node.props[\"${e.name}\"] as? Action)?.let { a -> sb.append(\"\${indent}  ${e.name} = \${fmtAction(a, ${e.paramCount})},\\n\") }")
}
```
After the `modifierImport` map emission, add the generated map:
```kotlin
appendLine("/** \"Widget.event\" -> the single param's Kotlin type (typed action contracts). */")
appendLine("val eventParamType: Map<String, String> = mapOf(")
for (w in sorted) for (e in w.events) if (e.paramCount == 1 && e.paramType != null) {
  appendLine("  \"${w.name}.${e.name}\" to \"${e.paramType}\",")
}
appendLine(")")
```

- [ ] **Step 3: itemFieldsOf collects Action item args** — in the prelude, inside the `for ((prop, v) in node.props)` loop add:
```
|      if (v is Action && v.arg != null && v.arg!!.startsWith("${'$'}itemVar.")) {
|        val sub = v.arg!!.substringAfter('.')
|        if (sub !in iface) iface[sub] = "String"
|      }
```

- [ ] **Step 4: interface emission with typed params** — replace `contract.actions.forEach { a -> sb.append("  fun \$a()\n") }` with:
```kotlin
appendLine("    contract.actions.forEach { a ->")
appendLine("      val p = contract.actionParams[a]")
appendLine("      sb.append(if (p != null) \"  fun \$a(value: \$p)\\n\" else \"  fun \$a()\\n\")")
appendLine("    }")
```

- [ ] **Step 5: collectContract (hand file Bindings.kt)** — `ScreenContract` gains `val actionParams: Map<String, String> = emptyMap()`; in `walk`:
```kotlin
is Action -> {
  actions += value.name
  when {
    value.arg == "it" -> actionParams[value.name] = eventParamType["${n.type}.$key"] ?: "String"
    value.arg != null -> actionParams[value.name] = "String" // item-scoped data (ids etc.)
  }
}
```
(`actionParams` = a `LinkedHashMap<String, String>` beside `actions`; pass into the ScreenContract result.)

- [ ] **Step 6: regenerate + compile**

```bash
./gradlew :portal-schema-codegen:generatePortalCode
./gradlew :portal-core:compileKotlinJvm :portal-render:compileKotlinJs :portal-schema-codegen:test :portal-schema-codegen:checkPortalCode
```
Expected: BUILD SUCCESSFUL (checkPortalCode includes the kitchen-sink export→compile gate).

- [ ] **Step 7: Commit** — `feat(codegen): arg-aware action emission + typed action contracts (eventParamType)`

---

### Task 3: Recognizer — parse the two single-arg lambda shapes + contract action params

**Files:**
- Modify: `portal-ingest/src/main/kotlin/dev/keliver/portal/ingest/Recognizer.kt` (parseValue ~line 161; contract extraction ~line 105)
- Test: `portal-ingest/src/test/kotlin/dev/keliver/portal/ingest/RecognizerTest.kt`

- [ ] **Step 1: Write the failing round-trip test**

```kotlin
@Test fun singleArgActionEventsRoundTrip() {
  val src = """
    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.Clickable
    import dev.keliver.material.compose.StyledText
    import dev.keliver.material.compose.TextField

    @Composable
    fun FormScreen(b: FormScreenBindings) {
      Column {
        TextField(text = b.draft, onValueChange = { b.onDraftChange(it) })
        b.notes.forEach { note ->
          Clickable(onClick = { b.openNote(note.id) }) {
            StyledText(text = note.title, fontSize = 16)
          }
        }
      }
    }

    interface FormScreenBindings {
      val draft: String
      val notes: List<Note>
      fun onDraftChange(value: String)
      fun openNote(value: String)
    }

    interface Note {
      val id: String
      val title: String
    }
  """.trimIndent()

  val r = Recognizer.recognize("FormScreen.kt", src)!!
  fun walk(n: DocNode): List<DocNode> = when (n) {
    is DocNode.Widget -> listOf(n) + n.children.flatMap { walk(it) }
    else -> listOf(n)
  }
  val all = walk(r.root)
  assertTrue(all.none { it is DocNode.RawCode }, all.filterIsInstance<DocNode.RawCode>().toString())

  val tf = all.filterIsInstance<DocNode.Widget>().first { it.type == "TextField" }
  assertEquals(PropValue.Action("onDraftChange", arg = "it"), tf.props["onValueChange"])
  val click = all.filterIsInstance<DocNode.Widget>().first { it.type == "Clickable" }
  assertEquals(PropValue.Action("openNote", arg = "note.id"), click.props["onClick"])
  assertEquals("String", r.contract.actionParams["onDraftChange"])

  val doc = UiDocument("form", r.root, r.contract, version = 0, nextHandle = 100)
  val exported = exportKotlin(doc.toWidgetTree(), functionName = "FormScreen")
  assertTrue("onValueChange = { b.onDraftChange(it) }" in exported, exported)
  assertTrue("onClick = { b.openNote(note.id) }" in exported, exported)
  assertTrue("fun onDraftChange(value: String)" in exported, exported)
  assertTrue("fun openNote(value: String)" in exported, exported)
  assertTrue("val id: String" in exported, exported)

  val r2 = Recognizer.recognize("FormScreen.kt", exported)!!
  assertEquals(Reconciler.reconcile(doc, r2).root, doc.root)
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew :portal-ingest:test --tests "*RecognizerTest*"` → FAIL (Clickable becomes RawCode / Action mismatch).

- [ ] **Step 3: Implement in Recognizer.parseValue** (after the existing zero-arg action regexes, before the P1-B item-bind regex):

```kotlin
// Phase 2: single-arg actions — { b.name(it) } (event payload) and
// { b.name(item.field) } (item-scoped data, e.g. a row id).
Regex("^\\{\\s*${Regex.escape(bindingsParam)}\\.([A-Za-z_][A-Za-z0-9_]*)\\(it\\)\\s*}$").find(t)
  ?.let { return PropValue.Action(it.groupValues[1], arg = "it") }
Regex("^\\{\\s*${Regex.escape(bindingsParam)}\\.([A-Za-z_][A-Za-z0-9_]*)\\(([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\)\\s*}$").find(t)
  ?.let { m ->
    if (m.groupValues[2] in itemScope) {
      return PropValue.Action(m.groupValues[1], arg = "${m.groupValues[2]}.${m.groupValues[3]}")
    }
  }
```
(These sit inside the `if (bindingsParam != null)` block.)

Contract extraction (~line 105): extend the `Contract(...)` construction with:
```kotlin
actionParams = iface.declarations.filterIsInstance<KtNamedFunction>()
  .mapNotNull { fn ->
    val p = fn.valueParameters.firstOrNull()?.typeReference?.text ?: return@mapNotNull null
    (fn.name ?: return@mapNotNull null) to p
  }.toMap(),
```

- [ ] **Step 4: Run tests** — `./gradlew :portal-ingest:test` → all PASS (including the 8 existing).

- [ ] **Step 5: Commit** — `feat(ingest): recognize single-arg action lambdas ({ b.x(it) }, { b.x(item.field) })`

---

### Task 4: Field Notes v2 — text input + list→detail (hand-owned nav)

**Files:**
- Modify: `portal-app-lib/src/jsMain/kotlin/screens/feed.kt`
- Create: `portal-app-lib/src/jsMain/kotlin/screens/detail.kt`
- Modify: `portal-app-lib/src/jsMain/kotlin/logic/NotesStore.kt` (rowid as id, byId)
- Modify: `portal-app-lib/src/jsMain/kotlin/logic/FeedPresenter.kt` (draft state, openNote callback)
- Create: `portal-app-lib/src/jsMain/kotlin/logic/DetailPresenter.kt`
- Modify: `portal-app-lib/src/jsMain/kotlin/dev/keliver/portalpublished/PublishedEntry.kt` (route state)
- Modify: `portal-ingest/src/test/kotlin/dev/keliver/portal/ingest/RecognizerTest.kt` (update the dogfood zero-RawCode test to the v2 screen)

- [ ] **Step 1: feed.kt v2** — add after the "Add note" area, replacing the Button block:

```kotlin
      TextField(
        text = b.draft,
        placeholder = "What did you notice?",
        onValueChange = { b.onDraftChange(it) },
      )
      Spacer(
        height = Dp(8.0),
      )
      Button(
        text = "Add note",
        onClick = { b.addNote() },
      )
```
and wrap each Repeat card in a Clickable carrying the id:
```kotlin
      b.notes.forEach { note ->
        Clickable(
          onClick = { b.openNote(note.id) },
        ) {
          Card {
            ...unchanged three StyledTexts...
          }
        }
        Spacer(
          height = Dp(10.0),
        )
      }
```
Bindings + item interface (field order must match the exporter: action args are collected at the Clickable before its children's binds, so `id` comes first):
```kotlin
interface FeedScreenBindings {
  val subtitle: String
  val draft: String
  val isEmpty: Boolean
  val notes: List<Note>
  fun onDraftChange(value: String)
  fun addNote()
  fun clearAll()
  fun openNote(value: String)
}

interface Note {
  val id: String
  val title: String
  val body: String
  val time: String
}
```
Add `import dev.keliver.material.compose.Clickable` and `import dev.keliver.material.compose.TextField`.

- [ ] **Step 2: detail.kt**

```kotlin
package dev.keliver.portalpublished.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.Divider
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.material.compose.TextButton
import dev.keliver.ui.Dp

/** DOGFOOD detail screen — reached by tapping a feed card (hand-owned nav). */
@Composable
fun DetailScreen(b: DetailScreenBindings) {
  StyledBox(
    paddingDp = 20,
    fillWidth = true,
  ) {
    Column {
      TextButton(
        text = "< Back",
        onClick = { b.back() },
      )
      Spacer(
        height = Dp(8.0),
      )
      StyledText(
        text = b.title,
        fontSize = 26,
        bold = true,
        colorArgb = -14540254,
      )
      Spacer(
        height = Dp(8.0),
      )
      Divider()
      Spacer(
        height = Dp(8.0),
      )
      StyledText(
        text = b.body,
        fontSize = 16,
        colorArgb = -11184811,
      )
      Spacer(
        height = Dp(12.0),
      )
      StyledText(
        text = b.time,
        fontSize = 12,
        colorArgb = -8355712,
      )
    }
  }
}

/** The round-trip boundary: implement this by hand; the portal never touches it. */
interface DetailScreenBindings {
  val title: String
  val body: String
  val time: String
  fun back()
}
```

- [ ] **Step 3: NotesStore — id + byId** — `all()` query becomes `SELECT rowid, title, body, time FROM notes ORDER BY rowid DESC` with mapper indices shifted (`id=getString(0)`, title 1, body 2, time 3); add:

```kotlin
suspend fun byId(id: String): Note? = byIdQuery(id).awaitAsOneOrNull()

private fun byIdQuery(id: String): ExecutableQuery<Note> = object : ExecutableQuery<Note>({ c ->
  object : Note {
    override val id: String = c.getString(0) ?: ""
    override val title: String = c.getString(1) ?: ""
    override val body: String = c.getString(2) ?: ""
    override val time: String = c.getString(3) ?: ""
  }
}) {
  override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
    driver.executeQuery(5, "SELECT rowid, title, body, time FROM notes WHERE rowid = ?", mapper, 1) { bindString(0, id) }
}
```
(import `awaitAsOneOrNull`; the `all()` item objects also gain `override val id`.)

- [ ] **Step 4: FeedPresenter v2** — signature `FeedPresenter(sql: HostSqlDriver?, onOpenNote: (String) -> Unit)`; add `var draft by remember { mutableStateOf("") }`; bindings gain:

```kotlin
override val draft: String = draft
override fun onDraftChange(value: String) { draft = value }
override fun openNote(value: String) = onOpenNote(value)
override fun addNote() {
  scope.launch {
    val n = (store?.count() ?: 0L) + 1L
    val text = draft.trim()
    store?.insert(
      title = if (text.isEmpty()) "Note #$n" else text,
      body = if (text.isEmpty()) "Captured from the portal-built feed." else "Typed in the portal-authored TextField.",
      time = "entry $n",
    )
    draft = ""
    refresh()
  }
}
```
(Watch the name shadow: rename the state var to `draftText` if `override val draft` collides.)

- [ ] **Step 5: DetailPresenter**

```kotlin
package dev.keliver.portalpublished.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.portal.sql.PortalSqlDriver
import dev.keliver.portalpublished.screens.DetailScreenBindings
import dev.keliver.portalpublished.screens.Note

/** HAND-OWNED: loads one note by rowid for the detail screen. */
@Composable
fun DetailPresenter(sql: HostSqlDriver?, noteId: String, onBack: () -> Unit): DetailScreenBindings {
  var note by remember { mutableStateOf<Note?>(null) }
  val store = remember { sql?.let { NotesStore(PortalSqlDriver(it)) } }
  LaunchedEffect(noteId) { note = store?.byId(noteId) }
  return object : DetailScreenBindings {
    override val title: String = note?.title ?: "loading…"
    override val body: String = note?.body ?: ""
    override val time: String = note?.time ?: ""
    override fun back() = onBack()
  }
}
```

- [ ] **Step 6: PublishedEntry — hand-owned route state**

```kotlin
@Composable
fun PublishedEntry(sql: HostSqlDriver?) {
  var openNoteId by remember { mutableStateOf<String?>(null) }
  val id = openNoteId
  if (id == null) {
    FeedScreen(FeedPresenter(sql, onOpenNote = { openNoteId = it }))
  } else {
    DetailScreen(DetailPresenter(sql, id, onBack = { openNoteId = null }))
  }
}
```
(imports: `remember`, `mutableStateOf`, `getValue`, `setValue`, the two screens + presenters.)

- [ ] **Step 7: update the dogfood ingest test** — extend `recognizesDogfoodFeedScreenWithNoRawCode`'s source with the TextField + Clickable exactly as feed.kt has them; assert additionally:
```kotlin
assertEquals(PropValue.Action("onDraftChange", arg = "it"),
  all.filterIsInstance<DocNode.Widget>().first { it.type == "TextField" }.props["onValueChange"])
assertEquals(PropValue.Action("openNote", arg = "note.id"),
  all.filterIsInstance<DocNode.Widget>().first { it.type == "Clickable" }.props["onClick"])
assertEquals(setOf("note.id", "note.title", "note.body", "note.time"),
  itemBinds + itemActionArgs) // adjust the collection to include Action args
assertEquals(setOf("subtitle", "draft", "isEmpty", "notes"), r.contract.fields.keys)
assertEquals(setOf("addNote", "clearAll", "onDraftChange", "openNote"), r.contract.actions.toSet())
```

- [ ] **Step 8: compile + test** — `./gradlew :portal-app-lib:compileKotlinJs :portal-ingest:test :portal-published-guest:compileDevelopmentExecutableKotlinJs :portal-device-guest:compileDevelopmentExecutableKotlinJs` → PASS.

- [ ] **Step 9: Commit** — `feat(dogfood): Field Notes v2 — typed note entry + list->detail via single-arg actions`

---

### Task 5: Full gates + wasm editor compatibility

- [ ] **Step 1: full portal test sweep**

```bash
./gradlew :portal-document:jvmTest :portal-ingest:test :portal-sql:jvmTest :portal-schema-codegen:test :portal-schema-codegen:checkPortalCode :portal-relay:installDist :web-spike:compileKotlinWasmJs
```
Expected: BUILD SUCCESSFUL (Portal.kt only reads `contract.fields/actions` and constructs zero-arg `PropValue.Action` — defaulted params keep it source-compatible).

- [ ] **Step 2: Commit any regenerated files** — `chore(portal): regenerate after arg-aware actions`.

---

### Task 6: Device verification (the acceptance test)

- [ ] **Step 1: boot** — emulator (`nohup emulator -avd Pixel_9 -no-snapshot-save -no-boot-anim &`), portal-server (`PORTAL_REPO=$PWD portal-relay/build/install/portal-relay/bin/portal-relay`, :8077).
- [ ] **Step 2: publish** — `curl -s -X POST http://localhost:8077/publish` → `publish OK: bundle v8` (or next).
- [ ] **Step 3: install + launch prod** — `./gradlew :portal-device-android:installDebug`; `adb shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity --es mode prod`; logcat shows `prod mode: verifying manifests with portal-ed25519` + `codeLoadSuccess`.
- [ ] **Step 4: type a note** — tap the TextField (screenshot first to locate), `adb shell input text "Emulator%stypes%sthis%snote"`, tap Add note; screenshot: the typed title appears as a card and the TextField cleared.
- [ ] **Step 5: navigate** — tap the new card → detail screen shows the typed title/body/time (screenshot); tap "< Back" → feed again (screenshot).
- [ ] **Step 6: persistence** — force-stop + relaunch → the typed note survives.
- [ ] **Step 7: ingest sanity (bidirectional editing still live)** — with the relay running, `curl -s http://localhost:8077/doc?screen=feed | python3 -m json.tool | grep -A2 onValueChange` shows `Action{name:onDraftChange, arg:"it"}` (file-watch ingested the v2 screen with zero RawCode).
- [ ] **Step 8: teardown** — kill relay, `adb emu kill`.

---

### Task 7: Docs, memory, push

- [ ] **Step 1: docs** — `docs/DOGFOOD_NOTES.md`: mark friction #1 FIXED (single-arg actions) and #2 ADDRESSED (hand-owned route state + item-carrying actions; a formal Navigator primitive stays deferred until dogfooding demands it); note #4 partially fixed (item ifaces now include action-arg fields). `docs/PORTAL_V2_COMPLETE.md`: update the known-follow-ups list.
- [ ] **Step 2: commit + push** — `git push origin main`.
- [ ] **Step 3: memory** — update `project_keliver_web_spike.md` with Phase-2 status + gotchas found.

---

## Self-review notes

- Spec coverage: friction #1 (Tasks 1–3), #2 minimal (Task 4 nav), acceptance retrofit (Task 4), regression safety (Tasks 3/5), device proof (Task 6). Friction #3 (preview mock list) intentionally out of scope. ✅
- Type consistency: `PropValue.Action(name, arg)` ↔ tree `Action(name, arg)` ↔ `fmtAction`; `Contract.actionParams` ↔ `ScreenContract.actionParams` — both maps `name -> Kotlin type`. ✅
- Editor (web-spike Portal.kt) intentionally untouched: it authors zero-arg actions; arg-authoring arrives via the `.kt` ingest path, which is a first-class editing surface by design.
