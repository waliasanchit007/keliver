# Widget Parity + Playground UX + README + IDE DX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the native-Compose parity gaps found by auditing the 74-widget material schema (add `Icon` + `ListItem`; onboard the already-implemented `DropdownMenu` + `SegmentedButtonRow` into the portal by adding a STRING_LIST prop kind), make the public playground land on a demo screen with onboarding hints and templates, replace the fork README with a keliver-first front door, and document/verify the IDE completion story.

**Architecture:** (1) STRING_LIST rides the existing typed-value wire as `Lit(tag="ls", ls=...)` and threads through the same seven touchpoints every kind uses (wire, MappedKind, PropKind, exporter fmt, render getter, editor row, V1 serialize). (2) `Icon` is name-based over a curated map of `Icons.Filled.*` (compose.material is already an api dep — zero new dependencies); `ListItem` reuses the icon map for leading/trailing. Guest composables + protocol adapters are fully generated from the schema; only the composeui host impl + factory override are hand-written (~30 lines each). (3) Playground seeds `localDoc` with a demo tree and adds a hint strip + template buttons (playground-only). (4) README + IDE docs are content work; sources-jar publishing is verified, not built.

**Audit verdict (recorded in docs/WIDGET_PARITY.md):** the schema is already strong (74 widgets; StyledText has full text-style parity: maxLines/overflow/align/lineHeight/letterSpacing/weight/colorRole). True gaps: Icon (the most-used Material composable), ListItem, RangeSlider (2-param event — defer), DatePicker/TimePicker/SearchBar/ModalDrawer/PullToRefresh (heavy — roadmap), Scaffold in-portal (2 children slots — portal limitation, works for guests), RichText in-portal (complex span type), LazyColumn/LazyRow in-portal (lazylayout schema not parsed by portal codegen — guests have them).

**Environment:** `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`; repo `/Users/sanchitwalia/AndroidStudioProjects/konduit`; work on `main`. Widget ids 61 (Icon) and 62 (ListItem) are free (current max 60). Adding widgets requires `apiDump` regen for the material modules.

---

### Task 1: STRING_LIST prop kind (onboards DropdownMenu + SegmentedButtonRow)

**Files:**
- Modify: `portal-document/.../DocNode.kt` (Lit gains `ls: List<String>? = null`, `lit()` + `toAny()` handle it)
- Modify: `portal-schema-codegen/.../PropModel.kt` (MappedKind.STRING_LIST; `mapType`: `List<kotlin.String>` → STRING_LIST)
- Modify: `portal-core/.../CatalogTypes.kt` (PropKind.StringList), `portal-core/.../Bindings.kt` (`kotlinTypeOf` → `List<String>`)
- Modify: `portal-schema-codegen/.../EmitExporter.kt` (`fmtStringList(v) = "listOf(" + joined quoted + ")"`; literalExpr branch), `EmitRenderNode.kt` (getter `node.strList("name")`), `EmitCatalog.kt` (PropKind.StringList emission + sampleProps)
- Modify: `portal-core/.../WidgetTree.kt` (`strList` helper), `portal-core/.../Serialize.kt` (`"lst"` tag both directions)
- Modify: `portal-ingest/.../Recognizer.kt` (`parseLiteral`: `listOf("a", "b")` string form)
- Modify: `web-spike/.../Portal.kt` (propRow: StringList = text input, pipe-separated ⇄ list)
- Tests: WireTest (ls round-trip + old-JSON compat), RecognizerTest (DropdownMenu round-trip: `options = listOf("A", "B")` ⇄ Lit(ls))

- [ ] Steps: failing tests → wire → codegen kinds → regen (`generatePortalCode` — expect `included=62`, DropdownMenu + SegmentedButtonRow now in) → editor input → full gates → commit `feat(portal): STRING_LIST prop kind — DropdownMenu + SegmentedButtonRow join the portal`.

### Task 2: Icon + ListItem widgets (schema → host → portal)

**Files:**
- Modify: `keliver-material-schema/.../KeliverMaterial.kt` — register + define:

```kotlin
/** Material icon by NAME from a curated Icons.Filled set (see MaterialIcons.kt). */
@Widget(61)
public data class Icon(
  @Property(1) val name: String,
  @Property(2) val sizeDp: Int = 24,
  /** ARGB tint; 0 => LocalContentColor. */
  @Property(3) val tintArgb: Int = 0,
  @Property(4) val contentDescription: String = "",
)

/** Material3 list row: headline + optional supporting/overline text and leading/trailing icons. */
@Widget(62)
public data class ListItem(
  @Property(1) val headline: String,
  @Property(2) val supporting: String = "",
  @Property(3) val overline: String = "",
  @Property(4) val leadingIcon: String = "",
  @Property(5) val trailingIcon: String = "",
  @Property(6) val onClick: (() -> Unit)? = null,
)
```

- Create: `keliver-material-composeui/.../MaterialIcons.kt` — `internal val materialIconByName: Map<String, ImageVector>` (~48 common names: Add, ArrowBack, ArrowForward, Check, Close, Delete, Edit, Email, Favorite, FavoriteBorder, Home, Info, KeyboardArrowDown/Up, List, LocationOn, Lock, Menu, MoreVert, Notifications, Person, Phone, PlayArrow, Refresh, Search, Settings, Share, ShoppingCart, Star, ThumbUp, Warning, AccountCircle, Call, CheckCircle, DateRange, Done, ExitToApp, Face, Clear, Send, MailOutline…) + `internal fun iconOrPlaceholder(name: String): ImageVector` (unknown → `Icons.Filled.Warning`? no — `Icons.Filled.Close`? use a neutral `Icons.Filled.Info`).
- Create: `keliver-material-composeui/.../ComposeUiIconWidgets.kt` — `ComposeUiIcon`, `ComposeUiListItem` (M3 `Icon`/`ListItem`, clickable when onClick set), + factory overrides in `ComposeUiKeliverMaterialWidgetFactory.kt`.
- [ ] Steps: schema → compile (factory compile error names every host to update — fix composeui) → `apiDump` → portal regen (`included=64`) → gates (`apiCheck`, kitchen-sink) → commit `feat(material): Icon + ListItem widgets (native-parity batch)`.

### Task 3: docs/WIDGET_PARITY.md

- [ ] The audit as a living doc: covered surface (74 widgets, grouped), portal-vs-guest availability (Scaffold/RichText/Lazy*), deliberate deferrals with reasons (RangeSlider 2-param events, DatePicker scope), and the "how to add a widget" recipe (schema → composeui impl → apiDump → regen). Commit with Task 2.

### Task 4: Playground first-run UX

**Files:** Modify `web-spike/.../Portal.kt`

- [ ] Seed `localDoc` with a demo tree (StyledBox → Column → StyledText title, TextField bound to `draft` with `onDraftChange(it)`, Button "Add" → `addTapped`, Repeat(items=`notes`, item=`note`) → ListItem(headline=`note.title`, leadingIcon="Star", onClick=`openNote(note.id)`) — shows binds, mock rows, actions, the new widgets).
- [ ] Hint strip (playground only, below topbar): "1 drag widgets in · 2 bind props with @ (mocks drive the preview) · 3 Export Kotlin — this is real code" + dismiss.
- [ ] Template buttons in the left pane (playground only): Feed / Form / Profile — each replaces `localDoc` with a canned tree (confirm via `window.confirm` before discarding edits).
- [ ] Compile wasm; commit `feat(playground): demo seed, onboarding hints, starter templates`.

### Task 5: README overhaul

**Files:** Replace `README.md` (keep a `docs/UPSTREAM_README.md` pointer? No — link Redwood attribution inline).

- [ ] Structure: what keliver is (2 sentences: server-driven UI where screens are real Kotlin composables, edited visually or in code, shipped OTA as signed compiled bundles) · playground link (http://keliver.me/keliver/) · the loop diagram (edit anywhere → live everywhere → publish signed) · 5-minute quickstart (`scripts/keliver-dev.sh`) · widget library (74 widgets, parity doc link) · docs index (PORTAL_USAGE, SCREEN_ARCHITECTURE, WIDGET_PARITY, USAGE) · Maven coordinates (dev.keliver 0.2.0) · attribution (fork of Cash App's Redwood, Apache 2.0) · license.
- [ ] Commit `docs: keliver-first README (playground, quickstart, widget library, attribution)`.

### Task 6: IDE completion DX

- [ ] Verify sources jars: `./gradlew :keliver-material-compose:publishToMavenLocal -Pversion=...` — actually just check `~/.m2` from the 0.2.x publishes or the vanniktech config (`grep -rn "sourcesJar\|javadocJar\|mavenPublishing" build-support gradle.properties | head`). Record finding.
- [ ] Create `docs/IDE_SETUP.md`: screens are plain Kotlin against generated `@Composable` functions → open the repo (or your app module) in IntelliJ/Android Studio and completion/docs/param-hints work like native Compose; published artifacts ship `-sources.jar` so external consumers get the same; KDoc on schema properties flows into the generated composables (verify one example); the three-shape event grammar cheat-sheet; portal-mcp for AI-assisted editing. Link from README.
- [ ] Commit `docs: IDE setup — native-like completion for keliver screens`.

### Task 7: Verification + ship

- [ ] Full gates: portal tests, `checkPortalCode`, `apiCheck`, wasm dist, guest compiles.
- [ ] Device spot-check (emulator, dev overlay — same recipe as the mock-rows check): a scratch tree with Icon, ListItem, SegmentedButtonRow, DropdownMenu renders natively.
- [ ] Push (Pages auto-redeploys the playground) → curl the live URL → memory update.

## Self-review
- Wire stability: new `@Widget(61/62)` and `Lit.ls` append-only; old docs decode (defaulted). ✓
- The factory compile gate makes missing host impls impossible to ship. ✓
- eventParamType already types `onSelect(Int)` via P2 codegen. ✓
- Icon names are strings → portal/editor need no special UI (Text prop). ✓
