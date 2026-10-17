# Writing a screen in Keliver

How to structure a server-driven screen so guest code feels like native
Android — a **Repository**, a **Presenter** (the "ViewModel"), and a **Screen**
— while respecting the one hard constraint of the platform.

Worked example: the **Workouts** screen in [`sample/`](../sample) (fetches
workouts from an API, merges favorites from a host database, with live search),
implemented in both styles below and verified on device.

---

## 1. The mental model

You write three things, exactly like native MVVM:

| Layer | Native Android | Keliver guest |
|---|---|---|
| **Data** | `class FooRepository(api, dao)` | `class FooRepository(http, hostStore)` |
| **Logic / state** | `FooViewModel` exposing `StateFlow<UiState>` | `@Composable FooPresenter(events): FooModel` |
| **UI** | `@Composable FooScreen(vm)` | `@Composable FooScreen(model, onEvent)` |

The difference from native is *where the data and the heavy logic live* — and
that's dictated by the runtime.

## 2. The host/guest split (the hard constraint)

The guest is **Kotlin/JS running in QuickJS**. It **cannot**:

- open a database (no SQLite),
- perform authenticated networking or hold secrets/tokens,
- touch platform APIs (files, sensors, keystore).

So the **native host owns data, persistence, networking, and secrets**, and
exposes them to the guest as **Zipline services**. The guest owns **presentation
logic and UI**. This is not a workaround — it's the architecture:

- keeps the guest **thin and fast** (it's downloaded over the wire, so every KB
  and every cold-start millisecond counts),
- keeps **security native** (tokens never enter the sandbox),
- lets you **ship new screens on the fly** while the host stays a stable,
  capability-shaped surface.

> This is exactly how Cash App structures Treehouse: the guest calls back into
> host-provided `ZiplineService`s for everything it can't do itself.

## 3. Data layer

### 3a. API calls — `keliver-http`

The host binds **one** generic, endpoint-agnostic transport; the guest defines
endpoints, request/response types, and parsing. A new endpoint is **guest code
only — no native release.**

```kotlin
// HOST (once): your HttpClient + base URL + auth, behind HostHttpProvider.
class KtorHostHttpProvider(val client: HttpClient, val baseUrl: String) : HostHttpProvider {
  override suspend fun execute(req: HttpRequest): HttpResponse { /* client.request(baseUrl + req.path){…} */ }
}
zipline.bind<HostHttpProvider>("http", KtorHostHttpProvider(client, "https://api.example.com"))

// GUEST: define your own types + endpoints, call them typed.
@Serializable data class Workout(val id: String, val name: String, val durationMin: Int)
val http = KeliverHttp(provider)
val workouts: List<Workout> = http.get("/workouts")
```

> ⚠️ **Gotcha:** the guest module must apply the **`kotlin-serialization`
> compiler plugin** (`alias(libs.plugins.kotlinSerialization)`), or `@Serializable`
> types get no serializer on Kotlin/JS and you'll hit a runtime
> *"Serializer for class X not found"*.

### 3b. Database / persistence — a host Zipline service

The guest can't open SQLite, so expose your host store (SQLDelight / Room /
DataStore) as a `ZiplineService`:

```kotlin
// shared (visible to host + guest)
interface HostFavoritesStore : ZiplineService {
  suspend fun favorites(): FavoritesSnapshot
  suspend fun setFavorite(id: String, favorite: Boolean)
}
@Serializable data class FavoritesSnapshot(val ids: List<String>)
```

> ⚠️ **Gotcha (KNOWN_BUGS U1):** a `suspend` service method must **not** return
> `List<@Serializable T>` — that hangs `bind<>()`. Wrap lists in a single
> snapshot type (as above), the same way `keliver-http` returns a single
> `HttpResponse` with a `String` body. Single objects and `Unit` are fine.

### 3c. Repository — combine the sources (plain, testable)

```kotlin
class WorkoutsRepository(private val http: KeliverHttp, private val favorites: HostFavoritesStore) {
  suspend fun load(query: String): List<WorkoutRow> {
    val all = http.get<List<Workout>>("/workouts")     // API source
    val favIds = favorites.favorites().ids.toSet()      // DB source
    return all.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
              .map { WorkoutRow(it, isFavorite = it.id in favIds) }
  }
  suspend fun toggleFavorite(id: String, fav: Boolean) = favorites.setFavorite(id, fav)
}
```

The repository is a **plain class** — no Compose — so the business logic is
unit-testable without any UI, regardless of which presentation style you pick.
The sample's `WorkoutsRepositoryTest` does exactly that — **3 passing tests**
with fake host services (`HostHttpProvider` + `HostFavoritesStore`), no UI, no
device (`./gradlew :guest:jsTest`).

## 4. Presentation layer — two styles

### Style B (recommended): Presenter + Screen

The native-like separation. The **Presenter** is the ViewModel equivalent —
`events` in, `Model` out; the **Screen** is a pure render.

```kotlin
@Composable
fun WorkoutsPresenter(events: Flow<WorkoutsEvent>, repo: WorkoutsRepository): WorkoutsModel {
  var query by remember { mutableStateOf("") }        // + loading / error / rows / reload
  LaunchedEffect(Unit) { events.collect { e -> when (e) {
    is Search -> query = e.query
    is ToggleFavorite -> { repo.toggleFavorite(e.id, e.favorite); reload++ }
  } } }
  LaunchedEffect(query, reload) { /* load via repo → loading/error/rows */ }
  return WorkoutsModel(query, loading, error, rows)
}

@Composable
fun WorkoutsScreen(model: WorkoutsModel, onEvent: (WorkoutsEvent) -> Unit) { /* pure render of model */ }

// in TreehouseUi.Show():
val events = remember { MutableSharedFlow<WorkoutsEvent>(extraBufferCapacity = 16) }
WorkoutsScreen(WorkoutsPresenter(events, repo)) { events.tryEmit(it) }
```

The screen's whole state machine is an inspectable `Model`, not logic fused into
the view — so it stays clean as the screen grows (loading, error, search,
toggles) and is testable in isolation.

### Style A (escape hatch): inline

Redwood's default — state + logic + UI in one `@Composable`. Fewest lines; fine
for trivial / leaf screens, but a complex screen tangles and you can only test
it by rendering the widget tree.

```kotlin
@Composable
fun WorkoutsScreenInline(repo: WorkoutsRepository) {
  val scope = rememberCoroutineScope()
  var query by remember { mutableStateOf("") }
  var rows by remember { mutableStateOf<List<WorkoutRow>?>(null) }
  LaunchedEffect(query) { rows = repo.load(query) }
  /* … Column { TextField; rows?.forEach { Card { Checkbox(…); Text(…) } } } … */
}
```

### When to use which

**Default to Style B** for any screen with real state (loading/error/inputs).
**Drop to Style A** when a Model + Events pair would be pure ceremony (a static
list, a leaf). They compose — a `Screen` can be inline or delegate to a
`Presenter`.

## 5. What about Molecule? And DI?

- **Molecule is not used in the guest runtime.** Treehouse's own
  `treehouse-guest-compose` already does Molecule's job (it runs the Compose
  runtime to drive the widget tree). Redwood's docs prescribe plain-Compose
  presenters; Cash App's Redwood samples have zero Molecule. Molecule is the
  *native-UI* sibling tool — and it's still handy in **tests** (drive a presenter
  as a `StateFlow` with `moleculeFlow(RecompositionMode.Immediate)`), where
  nothing ships in the bundle.
- **DI:** wire the graph **manually** in the guest's `main()` (Cash App's
  Treehouse sample does exactly this). **kotlin-inject** works on Kotlin/JS if
  the graph grows. **Hilt/Dagger do not work in the guest** (JVM reflection /
  annotation processing).

## 6. The worked example

| File | Role |
|---|---|
| `sample/shared/.../FavoritesStore.kt` | the host DB service (DB source) |
| `sample/guest/.../WorkoutsData.kt` | `Workout`, `WorkoutsRepository`, `WorkoutsModel`, events |
| `sample/guest/.../WorkoutsPresenter.kt` | Style B (Presenter + Screen) |
| `sample/guest/.../WorkoutsInline.kt` | Style A (inline) |
| `sample/guest/src/jsTest/.../WorkoutsRepositoryTest.kt` | the logic, unit-tested (3 green, no UI/device) |
| `sample/host-compose/.../SampleHostServices.kt` | host wiring (Android **+** iOS): serve `/workouts`, bind `HostHttpProvider` + `HostFavoritesStore` |

Run it: `sample/README.md`. The guest's `Show()` renders Style B by default;
swap the one block for `WorkoutsScreenInline(repo)` to see Style A.

## 7. Project components ("molecules") — C1

App-owned reusable composables built from keliver primitives, whose Kotlin
**signature is the portal spec** — no annotation, no sidecar (D4). Screens call
them like any widget; the portal recognizes, edits, exports, and previews the
call **without a schema tag or protocol change** (D6/D9). Devices just call the
compiled composable — zero runtime cost.

```kotlin
// components/MenuRow.kt  (a SIBLING of screens/, configurable via componentsDir)
@Composable
fun MenuRow(title: String, subtitle: String, icon: String = "Star", onClick: () -> Unit) {
  ListItem(headline = title, supporting = subtitle, leadingIcon = icon,
    trailingIcon = "KeyboardArrowRight", onClick = onClick)
}

@Composable
fun SectionCard(content: @Composable () -> Unit) {
  StyledBox(fillWidth = true, cornerRadiusDp = 16) { content() }
}
```

- **Signature → spec.** `String`/`Int`/`Boolean`/`Double` params become editable
  props; `() -> Unit` / `(T) -> Unit` params become events; literal defaults are
  parsed (an omitted optional arg uses the default). One `@Composable () ->
  Unit` required parameter becomes a trailing editable content slot; plain
  lambdas remain events. The slot must be invoked exactly once inside a grammar
  container. Optional/defaulted slots, wrapperless pass-through slots, and
  multiple named slots are deliberately opaque until their source and editor
  semantics are designed.
- **Master / instance.** A component USE in a screen is one selectable node with
  a prop panel from the signature; its internals are edited on the DEFINITION
  (its own file), and every instance's preview updates live.
- **Transparent vs opaque.** A grammar body is macro-expanded in preview
  (props/events substituted, defaults applied, nested components recursed, cycles
  guarded). A body with non-grammar code stays **opaque**: still a real device
  composable, previewed as a labeled placeholder.
- **Layout is grammar (D9, K1).** A layout widget's `margin = Margin(…)` and the
  scoped layout modifiers — `flex`, `grow`, `shrink`, `margin`, `width`,
  `height`, `size(Dp, Dp)`, `horizontalAlignment`, `verticalAlignment` — keep a
  body transparent:

  ```kotlin
  Row(width = Constraint.Fill, margin = Margin(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)) {
    Column(horizontalAlignment = CrossAxisAlignment.Center, modifier = Modifier.flex(1.0)) { … }
    Column(modifier = Modifier.flex(1.0).margin(Margin(all = 4.dp))) { … }
  }
  ```

  - *Margin values* parse as `Margin.Zero`, `Margin(all = X)`,
    `Margin(horizontal = X, vertical = Y)` (either alone), any named subset of
    `start`/`end`/`top`/`bottom`, or positional values read the way Kotlin's
    overload resolution reads them: `Margin(X)` = all, `Margin(X, Y)` =
    (horizontal, vertical), three or four values = (start, end, top, bottom).
    A compiled fixture (`portal-parity-fixtures/.../ParityLayout.kt`) pins each
    reading. X is `N.dp`, `N.M.dp` or `Dp(N.M)` — `Dp(N)` with an Int literal
    does not compile and is not grammar; mixing positional and named arguments
    is not grammar either. On the wire a Margin is four Double props
    `<prop>.start/.end/.top/.bottom` (absent = 0), edited as four number
    fields; export writes the shortest exact form (`Margin.Zero`, `Margin(all =
    …)`, `Margin(horizontal = …, vertical = …)`, else the non-zero named sides).
  - *Dp values are never negative* (Dp's constructor rejects them): the
    editor's number field has `min = 0`, and the document, the exporter,
    write-back and the preview all clamp a Dp prop, Margin side or Dp modifier
    argument at 0, so what you see is what is saved. Numbers are written
    without exponent notation (`0.0001`, never `1.0E-4`).
  - *Scoped modifiers* are members of `RowScope`/`ColumnScope`/`BoxScope`, so
    they resolve against the node's **parent** — `if`/`forEach` add no receiver
    and are looked through. A scoped modifier under a parent that is not one of
    its scopes (or at the body's root, or under a material container) is not
    grammar. Kotlin tries a scope member before an imported top-level
    extension but skips a member whose arguments don't fit, so inside a Row
    `Modifier.size(12, 12)` is material's `Size` while `Modifier.size(12.dp,
    12.dp)` is the layout one, catalogued as **`LayoutSize`** (its extension
    stays `size`).
  - *What you see is what is saved, and saved code compiles.* The document
    refuses a scoped modifier on a node whose parent is not one of its scopes,
    and a move/insert that puts a node under such a parent strips the scoped
    modifiers that became invalid. Undo moves the node back and re-applies
    only the stripped entries to the nodes as they are NOW — a node another
    session deleted or moved under a non-scope parent since is skipped, and an
    entry another session set since is kept — never a snapshot of the old
    subtree. As a safety net the exporter writes a scoped modifier only under
    a valid parent; the preview likewise ignores it elsewhere.
  - *A legacy off-scope entry* (from a document saved before these rules):
    it shows in the panel as "no effect here and not saved"; editing its value
    is refused, removing it works, and undoing that removal is refused too —
    the document never re-creates an invalid entry. The next save drops it.
  - *Component instances* take modifiers only when the component's signature
    declares a `modifier: Modifier` parameter: they are then written as
    `modifier = Modifier…`; otherwise the relay refuses them (SetModifier, or
    an inserted instance carrying them) and the exporter never writes them.
  - *Order and imports.* Modifiers keep their AUTHOR order through ingest,
    the document, export and the preview (scoped and unscoped mixed). Imports
    are derived STRUCTURALLY from what is emitted, never by matching names
    over text: the exporter records each widget, modifier (a top-level
    extension only — a scoped one is a scope member, so layout's `size` never
    pulls in material's) and literal type it writes; surgical write-back
    derives a fragment's imports from the grammar that produced it (Margin
    form, Dp spelling, modifier spec and argument types). Write-back adds a
    missing import at its sorted place (after `package`, or after `@file:`
    annotations when there is no package), never one an exact or star import
    already covers — trailing comments, `;` and backticks included — and
    never one whose simple name the file imports from elsewhere. A surgical Dp edit keeps the
    author's spelling (`12.dp` stays `.dp`, `Dp(10.0)` stays `Dp(…)`).
  - The editor offers a scoped modifier only under one of its scopes, and the
    preview applies it with the parent's receiver, so preview and device build
    the same value tree (`portal-render` `LayoutParityTest`).
  - Still outside the grammar: helper functions (`m(8)`) and file-local
    helper composables. Design-token constants are grammar (below).
- **Design tokens are grammar (D9, K2).** An argument that references a
  constant in the app's own sources is a token reference, not RawCode — so a
  component written with the app's tokens stays transparent, and a screen
  can use `C.Ink` instead of a magic number:

  ```kotlin
  object C {                 // components/Tokens.kt
    const val White = -1     // #FFFFFF
    const val Ink = -14737633 // #1F1F1F
  }
  object Assets {            // may share a file with other declarations
    private const val WEB = "https://example.com"
    const val Logo = "$WEB/logo.png"
  }

  StyledBox(colorArgb = C.White) {
    AsyncImage(url = Assets.Logo, widthDp = 24)
    StyledText(text = title, colorArgb = C.Ink, modifier = Modifier.background(C.White))
  }
  ```

  - *What is a token.* A `const val`, or a `val` whose initializer is a
    compile-time constant, declared at top level or in a top-level `object`
    in the scanned sources: the screens and components dirs, plus any
    `tokensDirs` (keliver.portal.json — repo-relative or absolute, scanned
    recursively; a missing entry is skipped, never created). Its value is
    computed at ingest: Int/Long/Double/Boolean literals (negative, hex,
    binary, `_` separators; finite only), `<Int or Long constant>.toInt()` —
    the ARGB idiom `0xFF1F1F1F.toInt()`, with Kotlin's two's complement — and
    String literals, templates (`"$WEB/x.png"`, `"${A}b"`) and `+`
    concatenations of other String/Int/Long/Boolean constants, transitively,
    with cycle detection. A Double is never rendered into a String (Kotlin/JVM
    and Kotlin/JS print it differently). A declared type is honoured (`val X:
    Long = 7` is a Long). Anything else does not resolve; known conservative
    misses: a `Float`, a `Char` (so `"${'$'}"`), other conversions
    (`toLong()`), arithmetic, and `-2147483648` (read as a Long, so declared
    `Int` it is not a token).
  - *How a reference resolves.* `Owner.Name` (one qualifier) or a bare
    top-level `Name`, bound the way Kotlin binds it: a name the composable
    binds first — any of its parameters, any lambda parameter (a Repeat item
    variable, the implicit `it`), any local or destructured name anywhere in
    the function — is never a token (conservatively for the whole function);
    an aliased import (`import q.C as Colors`) is never followed and shadows
    what it names; then an explicit import, then the file's own package (ANY
    top-level declaration of the name there — a class, a typealias, a
    function, in a scanned file or the referencing file — beats a star import,
    and wins over a token elsewhere), then exactly one star-imported package
    declaring the name. A same-package declaration in a file OUTSIDE the
    scanned dirs is not known to the portal. Visibility is Kotlin's, per file
    (a file's identity is its repo-relative path): a `private` constant
    evaluates (others may be built from it) but can be referenced only inside
    its object (a private top-level one only in its file); a private object's
    constants only in its file; `internal`/public are fine.
  - *Where it fits.* Only where source takes a plain literal of the token's
    own type: an Int token on an Int or Color prop, a String on Text, a
    Double on Double, a Boolean on Bool — for widget props, material modifier
    arguments (`background(C.Ink)`), scoped Double arguments (`flex(C.W)`)
    and project-component parameters. Never a `Dp` (`Spacer(height = …)`,
    Margin sides, `width(…)`), a `Float`, an enum (`Constraint`, alignments), a
    list, or a Long (no portal prop holds one) — Kotlin converts none of them,
    so those stay literals; a token there, or an unknown, private, cyclic or
    wrong-kind reference, keeps the call RawCode rather than becoming silently
    wrong. Event-action arguments and component signature defaults are not
    token sites (a default that references a token previews as its value).
  - *On the wire.* `PropValue.Token(ref, value, import)`: the reference as
    written, the resolved literal, and the declaration a file must import. The
    canvas renders the value; export and write-back write the reference (an
    unchanged one is never touched; a changed argument — a prop or a modifier
    argument, including a modifier added with a token — is written by the
    exporter, adding `import <pkg>.<Owner>` and whatever the modifier needs,
    taken from the token itself, not the text; no duplicate, star imports and
    the file's own package respected). Detaching writes the literal. The
    device tree carries the literal under its usual tag plus a `"token"` key,
    so an older device guest renders the value.
  - *When a declaration changes.* The relay re-scans (re-parsing only files
    whose content changed — a SHA-256 of the bytes, so a same-size edit
    inside the filesystem's mtime granularity is seen — and forgetting
    deleted files; never for its own write-back); if anything
    resolution observes changed, components rebuild and every screen is
    re-recognized from its file: a new value updates the canvas; a token that
    vanished or changed kind turns the argument's call RawCode. Change events
    carry the token table's version, so an open editor refreshes its picker
    even when no document changed. `GET /tokens` lists every exported token a
    prop can hold (ref, kind, value, package, import, declaring file,
    `ambiguous` when the same `Owner.Name` exists in two packages — the
    picker does not offer those).
  - *The server owns token values.* A client names a token by `ref` +
    `import`. The relay accepts one that Kotlin lets the target file's top
    level see: of a literal kind, never private in its object, and exported —
    or a private top-level constant, or one in a private object, of the target
    file itself. It replaces the value with its own BEFORE any check (a stale
    or forged client value never decides a fit); and it refuses a token that
    would not mean the chosen declaration in the target file: the screen
    function binds the name itself (a parameter, lambda parameter or local);
    the file imports a different owner of that name; or its import would
    change what an existing reference means (the name is declared in the
    file's package, star-imported, aliased, declared in the file, or a Kotlin
    default import such as `Pair` the file mentions). One scope runs through
    the whole batch, seeded with the imports the document's tokens already
    imply (the document can be ahead of the file inside the write-back
    window), so two owners sharing a simple name (`x.C`, `q.C`) are refused
    rather than written as clashing imports. Functions never shadow a token.
    An ambiguous reference is accepted when it binds correctly. Undo/redo
    re-read the values of the tokens the step restores (only those) and are
    refused when one no longer exists or no longer fits its prop; such a
    refused step stays on its stack, so it applies once the declaration is
    back. (`portal-relay`
    `DesignTokenRelayTest`, `DesignTokenGateTest`.) A compiled fixture
    (`ParityTokens.kt`) renders the same value tree as the interpreted golden
    (`portal-render` `TokenParityTest`).
  - *Editors that predate tokens.* The relay sends `PropValue.Token` only to
    a client that asks with `GET /doc?…&tokens=1` (this release's editor and
    MCP server do); an older per-app editor gets the literal values, renders
    them, and its edits to other props leave the references in the document
    and the file (a prop it rewrites becomes a literal). Libraries (the
    per-app editor) and the tools bundle (relay) release independently: a new
    editor against an old relay simply sees no tokens.
- **componentsDir** (keliver.portal.json): defaults to a `components` sibling of
  `screensDir`; old configs stay valid.
- **Scaffold:** `scripts/keliver-new-component.sh <Name>` for a leaf or add
  `--slot` for a container (config-aware, package-derived, refuses overwrite).
- **Contract:** a component instance's `b.x` / `b.go(...)` args contribute to the
  screen's Bindings with the component's declared param types — definition-
  internal param names never leak into the screen contract.

Worked dogfood: `portal-app-lib/.../components/{MenuRow,SectionCard}.kt` +
`screens/settings.kt` (3 SectionCard containers + 4 editable MenuRow children,
**0 RawCode**). Stashfin Profile uses the same slot capability for its five
canonical white card wrappers and is verified in its editor, Android, and iOS.

## 8. Live-presenter preview — P3-12

The editor's ▶ Live tier runs the app's REAL presenters/repositories in the
browser; ONLY host capabilities get preview substitutes (D7). Kotlin/Wasm has
no dynamic linking, so the app's logic compiles INTO the preview binary via an
explicit app-owned entry:

```kotlin
// the app registers one AppPreviewEntry (same explicitness as PublishedEntry)
object AppLibPreview : AppPreviewEntry {
  override val screens = mapOf(
    "feed" to ScreenPreview { env ->
      val b = FeedPresenter(previewDriver, onOpenNote = { env.log("→ $it") })
      PreviewFrame(
        values = buildMap { put("subtitle", b.subtitle); putRows("notes", "note", …) },
        dispatch = { a, arg -> when (a) { "addNote" -> b.addNote(); … } },
      )
    },
  )
}
```

- **Capability purity is compiler-enforced:** presenters may not reference
  Zipline types (they won't compile to wasm). They take capability interfaces
  (e.g. sqldelight's `SqlDriver`); device wiring adapts at the edge
  (`PortalSqlDriver` in `PublishedEntry`), the preview provides browser impls
  (`PreviewSqlDriver` over in-memory SQL). Fidelity reporting stays emergent.
- **Values transport** through the string-typed `PreviewBindings.mocks`
  (lists via `putRows` — count + pipe-joined row fields), so Repeat rows and
  component expansion light up with real data automatically.
- **Rebuild loop:** the relay watches `logicDirs` (config; default `logic/`
  sibling), debounces into a single-flight `previewBuildTask` build, rejects
  stale results, and promotes ONLY successful dists (`previewServeDir`) — a
  failed build keeps the last-known-good editor serving, with the error in
  the build chip. The editor auto-reloads on a newly promoted build,
  restoring project/screen/selection/live from a session snapshot.
- **Honesty rules:** a screen without a registered presenter shows
  "no presenter registered — mock tier"; presenter dispatch errors surface in
  the action console; canvas event payloads are not yet delivered (dispatch
  receives `arg = null` from canvas taps — the ⚡ console fires named actions).
