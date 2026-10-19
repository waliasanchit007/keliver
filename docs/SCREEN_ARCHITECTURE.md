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
    whole list (an element of one is — K4, below), or a Long (no portal prop holds one) — Kotlin converts none of them,
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
    refused when one no longer exists, no longer fits its prop, or would no
    longer mean its declaration in the file — the same binding check as an
    edit, seeded with the imports of every token the step does not restore.
    So a `C` declared in the screen's package since the edit fails the
    same-package check (restoring `app.components.C.Ink` through
    `import app.components.*` would now mean `app.screens.C.Ink`), and another
    session's pending `x.C` blocks restoring `q.C.Muted`. A refusal that can
    clear without a hand edit of the screen file — a token missing or unfit,
    a same-package or star-imported owner declared since, a clash with
    another token the document holds — keeps the step on its stack ("undo
    blocked (step kept)"), and it applies once that is resolved. (A clash
    with another token usually lasts only until that token's import is
    written back; from then on it is the file's own import, below.) A
    refusal caused by the file's own imports (it already imports a different
    `C`, exactly or as an alias) drops the step ("undo failed (step
    dropped)"): surgical write-back only adds imports, so usually only a hand
    edit clears it, and that arrives as an external change that clears both
    stacks anyway. A whole-file export (written only when it is lossless —
    see "The write-back guarantee" below) rebuilds the imports and can
    remove an unused one, so a dropped step might have applied later;
    dropping is the deliberate trade against a stack wedged behind a step
    that may never apply. A step the tree can no longer take is dropped the
    same way. The gate and undo/redo are what keep two owners of one simple
    name out of a document; if two *missing* exact imports are forced past
    them, write-back (surgical or a lossless full export) writes both and
    the result is a conflicting-import compile error. Write-back does not guard every other
    shape (an owner covered by a star import or the package beside an
    explicit one); the gate does. (`portal-relay`
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
- **Conditions are grammar (D9, K3).** A value or a block chosen by a
  parameter keeps a component transparent (and a screen editable):

  ```kotlin
  @Composable
  fun ToggleRow(label: String, selected: Boolean, subtitle: String = "", note: String? = null,
                raised: Boolean = false, onTap: () -> Unit, onLongTap: (() -> Unit)? = null) {
    Column {
      StyledText(text = label, colorArgb = if (selected) C.Ink else C.Muted,
        modifier = Modifier.margin(Margin(top = if (raised) 8.dp else 0.dp)))
      Icon(name = if (selected) "KeyboardArrowUp" else "KeyboardArrowDown")
      Clickable(onClick = if (selected) onTap else null) { StyledText(text = "tap") }
      if (!selected) { StyledText(text = "off") } else { StyledText(text = "on") }
      if (subtitle.isNotEmpty()) { StyledText(text = subtitle) }
      if (note != null) { StyledText(text = "note") }
    }
  }
  ```

  - *One condition model.* A condition reads ONE field — a screen binding
    (`b.selected`) or, in a component body, a parameter (`selected`) — with
    one test, optionally negated, each form spelled exactly one way: `x` /
    `!x` (Boolean), `x.isEmpty()` / `x.isNotEmpty()`, `x.isBlank()` /
    `x.isNotBlank()` (String), and — only on a component parameter whose
    declared type is nullable — `x == null` / `x != null`. A screen has no
    null test: its contract is derived from its tree and cannot declare a
    nullable field. Anything else (`count > 0`, `a && b`, `else if`) keeps the
    call or block RawCode, and an opaque component's diagnostic names it.
  - *Conditional children.* `if (c) { … }` with an optional `else { … }` is a
    `Condition` node (props `field`, `test`, `negate`) whose last child may be
    one `Else` node holding the else block. `if`/`else` add no layout: the
    branches' children sit in the enclosing layout (scoped modifiers resolve
    against it).
  - *Conditional values.* `if (c) A else B` as an argument — a widget prop, a
    component argument, a modifier argument, a `Margin` side — is a
    `PropValue.Conditional(field, test, negate, whenTrue, whenFalse)`. Each
    branch is a value the argument takes by itself: a literal, a design
    token, a binding, an action, or `null` (only where the parameter is
    nullable, e.g. an event: `onClick = if (enabled) onTap else null`). In a
    screen an action branch is a reference or a parenthesized lambda
    (`if (b.enabled) b::tap else null` — a bare `{ … }` would be the if's
    block). Branches never nest; a list prop is never conditional. (K4: a branch may also
    be a string template, below.)
  - *Preview.* Expanding an instance decides each condition over a parameter
    against the instance's argument (its default when omitted, else the
    editor's sample value), splicing the chosen branch; when the argument is a
    screen binding, the condition is re-pointed at that binding and decided
    by its preview mock (an unmocked Boolean reads true, an unmocked String as
    a non-empty placeholder, anything unmocked as non-null). A compiled
    fixture (`ParityConditionals.kt`) renders the same value tree as the
    interpreted golden (`portal-render` `ConditionalParityTest`).
  - *Write-back.* A no-op is byte-identical. Editing one branch rewrites only
    that branch's text (a Dp keeps its spelling); editing the condition
    rewrites only the condition; edits inside an else block stay in it.
    Adding an `else` inserts just ` else {` … `}` after the then block (its
    children go in as inserts); removing one deletes just the text from
    `else` through its block's `}` — comments and formatting in the then
    block are untouched. A `Condition` — like every node — is matched to
    its statement by identity (see "The write-back guarantee" below), so an
    edited condition rewrites its own `if` in place (comments kept), a new
    `Condition` is an insert and a deleted one a delete, however they sit
    together. Without that base (it no longer reads as the file, or another
    caller), position decides: a `Condition` with the same condition is the
    same `if`, and an edited one rewrites in place only when it is the only
    changed `Condition` between the same kept siblings — so a delete plus an
    unrelated insert there pair as an edit (the text is right; the deleted
    `if`'s comments stay). A run of inserts keeps the document's order.
    The exporter writes every form (`colorArgb = if
    (b.selected) C.Ink else -1`, `Margin(top = if (b.raised) 8.dp else
    0.dp)`, `if (!b.x) { … } else { … }`) and types each condition's field in
    the derived contract (`Boolean`, or `String` for an emptiness/blankness
    test — except a Repeat's items field, which stays `List<…>`: a List has
    `isEmpty()` too). A component event's branch matches the event's
    signature: `onSelect: ((Int) -> Unit)?` with an action that ignores the
    payload is `if (b.x) ({ _ -> b.pick() }) else null` against `fun pick()`.
  - *On the wire.* The device tree carries a conditional value's TRUE branch
    under its usual keys plus `"cond"` and `"else"`, so an older reader
    renders the true branch. The relay sends `PropValue.Conditional` only to a
    client asking `GET /doc?…&conditionals=1` (this release's editor and MCP
    server); an older editor gets each conditional's true branch, and — as
    with tokens — its edits to other props leave the conditional in the
    document and the file. That compatibility covers conditional VALUES
    only: `Condition` nodes reach an older editor unchanged, with their
    `test`/`negate` props and any `Else` child. It shows only the `field`
    prop, previews the Condition as a plain Boolean (ignoring `test` and
    `negate`), and renders an `Else` node as an unknown widget inside the
    then branch; its edits keep `test`/`negate` (a SetProp of `field` leaves
    them), and deleting the `Else` node removes the else block.
  - *What an op may not create.* The document refuses, on SetProp/
    SetModifier and for every condition in an inserted or moved subtree: a
    `null` or action branch on a catalog value prop, a value branch on an
    event, a conditional with no value in either branch, a nested branch, a
    list prop, a null test, an unknown test, a token that does not fit; a
    `Condition` whose `field` is not a plain screen binding name (`if
    (item.x) { … }` is not grammar) or whose `test`/`negate` source cannot
    spell; an item field (`item.flag`) read by a conditional value outside a
    Repeat over that item; a Boolean test on a field the document reads
    elsewhere as a Repeat's List or as a String/number (bound to such a
    prop, or tested for emptiness); `isBlank()` on a Repeat's List. It does
    not know a project component's signature: the relay refuses a component
    instance's conditional whose branch the parameter cannot take (an
    action on a value parameter, a value on an event, `null` on a
    parameter — event or value — not declared nullable; `() -> Unit?` is a
    non-null function, the nullable one is `(() -> Unit)?`). These checks
    run when an op PLACES a condition: an op that later changes how the
    document reads a field already tested — binding it to a text prop,
    renaming a Repeat's `item` around an `item.x` conditional — is not
    refused for the conditions already there; the guest compile reports
    it. Every catalog event
    is nullable (codegen leaves a non-nullable event with a default out of
    the catalog). Ingest applies the same null rule: `null` in a branch of
    a non-nullable parameter keeps the call RawCode.
  - *Signatures.* A nullable function parameter (`onLongTap: (() -> Unit)? =
    null`) is an optional event. A nullable content slot (`footer:
    (@Composable () -> Unit)?`) is not supported and says so in the
    component's diagnostic.
- **Templates and lists are grammar (D9, K4).** A string built from
  references, and a list of tokens, keep a component transparent (and a
  screen editable):

  ```kotlin
  @Composable
  fun ScoreRow(label: String, count: Int, start: Int = C.Start) {
    Column {
      StyledText(text = "$label: $count ${C.Unit}")
      StyledBox(gradientColorsArgb = listOf(start, C.End)) { … }
    }
  }
  // in a screen
  StyledText(text = "Rating: ${b.rating}/${C.Max}")
  ScoreRow(label = "Hi ${b.name}", count = 3)
  SegmentedButtonRow(options = listOf("All", C.Brand, b.extra))
  b.notes.forEach { note -> StyledText(text = "${note.title} (${note.count})") }
  ```

  - *What a template is.* A double-quoted string whose embedded expressions
    (`$x` or `${…}`) are each ONE reference: a screen binding (`b.x`, spelled
    through the file's bindings parameter), an item field inside a Repeat
    over that item (`note.title`), a component parameter (in a component
    body), or a design token of a String, Int or Boolean kind
    (`${C.Max}`, a bare top-level `$Brand`), resolved exactly as K2
    resolves a token argument. The text between references is decoded
    with the plain-literal rules (`\n`, `\"`, `\$`, `\uXXXX`, `${'$'}` keep
    their meaning). Anything else — `${a + b}`, `${x.uppercase()}`,
    `${1}`, an unknown name, a raw string — keeps the call RawCode.
  - *What a template never reads: a Double or a nullable value.* The device
    guest is Kotlin/JS, the preview wasm (the relay the JVM), and they print
    these differently: Kotlin/JS prints `1.0` as `1` (`1.5` as `1.5`),
    wasm/JVM print `1.0`; a null prints `null` on the device and nothing in
    the preview. No preview of such a template could be faithful, so — as
    K2 refuses a Double inside a String token's own template — a Double
    token, a component parameter declared `Double` or nullable (`String?`,
    `Int?`…), and a screen field the Bindings interface declares other than
    `String`/`Int`/`Long`/`Boolean` (`Double`, `Float`, any nullable type)
    keep the call RawCode (the component opaque); the document refuses the
    same (a Double token part, a part reading a field the contract declares
    so), the relay re-resolves a token part on undo/redo only as String,
    Int or Boolean, and the editor offers no Double token in a template.
    String, Int, Long and Boolean print alike on every target (a common
    `portal-render` test runs on both js and wasm and asserts it, Long past
    2^53 included). Format such a value in the presenter (a `String` field
    the presenter fills) to show it.
  - *Where ingest reads a reference's type.* A screen field's in the
    Bindings interface the screen takes — the interface its parameter's type
    names (`?`, type arguments and a package qualifier stripped; never
    another `…Bindings` interface the file declares first, here or in
    contract write-back) — declared in the same file, with the
    members of its supertypes declared in that file; `kotlin.String`,
    `kotlin.Int`, `kotlin.Long` and `kotlin.Boolean` are those types. An
    item field's (`row.price`) in the Repeat's element type (`List<Row>`)
    when `Row` — an interface, or a class with `val`/`var` constructor
    parameters — is declared in the same file: a `Double`, `Float` or
    nullable member keeps the call RawCode. A type ingest cannot read is
    *unknown*, and a template never reads it: a property without a declared
    type (`val x get() = 1.5`, typed `<unknown>` in the contract, never
    silently `String`), and every field the interface does not declare in
    the file — inherited or brand new — when the screen has no Bindings
    parameter, takes a type not declared in its file, or takes an interface
    extending one declared in another file (the RawCode reason says which).
    The contract carries that reason as `unlistedFieldsUnknown` (null —
    absent on the wire, and what older JSON decodes as — when the interface
    is read whole: an unlisted field is then new, and contract write-back
    declares it). The document refuses, when the op is made (set, insert,
    a conditional's branch, an undo reinserting it; the editor's and an
    agent's ops alike), a template reading a field the contract cannot
    type — "the screen's Bindings type for `title` is not readable here
    (…); bind it directly or make this change in code" — because ingest
    reads such a template as RawCode, so write-back would refuse it and
    block every later edit of the screen (§7a). A plain binding and a list
    element of such a field are allowed: nothing is printed, the compile
    types them, and they read back. A `ContractEdit` cannot clear the
    signal (the file decides it). Limit: an item type declared in another
    file is not read (an item field in a template stays grammar, as
    before), and the contract carries no item types, so the document does
    not check an item field's type: a template reading an item field ingest
    types `Double` or nullable is refused by the relay's write-back
    preflight (§7a) when the op is made — the file would not read back as
    the document.
  - A template fits only where source takes a `String`: a widget's String
    prop, a component's String parameter, or a conditional value's branch on
    one (`if (b.on) "Hi ${b.n}" else "-"`).
  - *What a list is.* `listOf(…)` on a widget's `List<Int>` or
    `List<String>` prop whose elements are literals of the element kind,
    design tokens of the element kind (`listOf(C.Start, C.End)`,
    `listOf(16777215, C.A, C.B)`) or bindings (`b.x`, `item.x`, a component
    parameter; one the Bindings interface or the signature declares is
    declared as the element type). A `List<Float>` stays literal-only (no token is a Float), a
    list prop is never conditional, and a component parameter is never a
    list. A list of literals is the plain literal it always was.
  - *On the wire.* `PropValue.Template(parts)` — each part a `Lit` (tag
    `s`, the decoded text), a `Bind` or a `Token` — and
    `PropValue.ListOf(tag, items)` (`li`/`ls`, each item a `Lit` of the
    element kind, a `Token` or a `Bind`). Both are stored CANONICAL: no
    empty or adjacent text parts, and a template with no reference left (or
    a list with only literals) is the plain `Lit`, which is what the
    recognizer reads back. The author's spelling (`$x` vs `${x}`, escapes)
    is not in the model; write-back keeps it. The device tree carries a
    template's text (token values, `{field}` for a binding) under `"s"`
    plus `"template"`, and a list's resolved values under `"li"`/`"lst"`
    plus `"items"`, so an older device guest renders them. The relay sends
    `PropValue.Template` only to a client asking `GET /doc?…&templates=1`
    and `PropValue.ListOf` only to one asking `lists=1` (this release's
    editor and MCP server); an older editor gets the text / the resolved
    list, and — as with tokens — its edits to other props leave both in the
    document and the file (a prop it rewrites becomes a literal).
  - *Preview.* A binding part takes its mock (an unmocked one shows
    `{field}`), a token its value, an item field its row's mock; preview
    expansion substitutes a component's parameters with the instance's
    arguments (a template argument is evaluated inside the body's
    template). A compiled
    fixture (`ParityTemplates.kt`) renders the same value tree as the
    interpreted golden (`portal-render` `TemplateListParityTest`).
  - *Write-back.* A no-op is byte-identical. An edit compares the old and
    new parts and rewrites only the run between the unchanged leading and
    trailing parts: changing one text segment rewrites that segment's
    source, changing one reference rewrites `${…}` alone, and every other
    part keeps its bytes (`$x` stays `$x`, an escape stays as written). A
    rewritten part is spelled canonically: text escaped (`\$`, `\"`,
    controls), a reference as `$name` where that reads back (a simple name
    not followed by an identifier character) else `${ref}` — and a kept
    `$name` that new text would extend is re-spelled `${name}`. The result
    must parse back as the new parts, else the whole string is written
    canonically (still only that argument). A list edit that keeps the
    length rewrites only the changed elements; otherwise the argument is
    written whole. A token added in a template or a list adds its import
    (structural, from the token).
  - *Contract.* A screen field read only by templates is typed `String` in
    the derived contract (and an item field only in templates is a
    `String` item member); a field the Bindings interface already declares
    keeps its declared type (a `String`, `Int`, `Long` or `Boolean` — see
    above), and another use (an Int prop) types it first. A field a
    template or a list reads is never a Boolean — unless the Bindings
    interface declares it `Boolean` (a template prints one: `"on: ${b.on}"`
    beside `if (b.on) { … }` compiles): otherwise the document refuses a
    Boolean test on it, and placing a template or a list that reads a field
    the screen already tests as one; the conditional value being placed is
    not evidence against itself (`if (b.on) "on: ${b.on}" else "-"` with
    `on` declared `Boolean`). A field a Repeat iterates is a List: no
    template, no list element and no binding on a prop that is not a List
    reads it (either order; a `List<String>` prop may bind the same list). A
    bound list element is the list's element type.
  - *What an op may not create.* The document refuses a template on a
    non-String prop or a modifier, a list with references on anything but a
    `List<Int>`/`List<String>` widget prop or with the other prop's tag, a
    part or an element that is not a reference of the right kind (an
    action, a nested template, a Double, Long or wrong-kind token, a
    non-field name, a field the contract declares Double/Float/nullable or —
    for an element — other than the element type, a template part reading a
    field the contract cannot type), a conditional list, a
    Boolean test on a field a template or list reads unless declared
    `Boolean` (either order), a Repeat's field read as one value (either
    order), and —
    where it is placed (set, insert, move) — an item field read outside a
    Repeat over its item. The relay
    refuses a template on a component parameter not declared `String`, a
    list on any component parameter, and runs every token part and element
    through the K2 gate (the server's value, the binding check in the target
    file, re-resolution on undo/redo).
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

## 7a. The write-back guarantee — what the editor does to your .kt file

**Write-back never silently destroys author code.** Every edit made in the
editor (or through MCP) is written to the screen's `.kt` file in one of three
ways, and the editor is told which:

0. **Only onto the file the relay last wrote or read.** Write-back merges
   onto the file only when it still reads as the document the relay last
   wrote it from (or read it as) — the *base*. If it does not (an IDE save
   landed between an editor edit and its write, an IDE undo took the file
   back to a text the relay once wrote, a file appeared that was never read),
   nothing is written: the relay reads the file instead, the document
   becomes the file, and the status is `discarded` — the editor's unwritten
   change is not in the file and is gone (make it again). Statements are
   never paired with nodes by position. Any read of the file forgets the
   relay's last written text, so a later file equal to it is the author's
   edit, not the relay's own write.
   - The file is compared as **text** with what the base was read from or
     written as. The same text is the same file even when it reads
     differently now (a design token's value or a component's signature
     changed meanwhile): the base is re-read from it under the current
     tables, the document's tokens take their current values, and the edit
     is written. Another text that still reads as the base (only comments or
     formatting changed) is merged onto as well.
   - The file watcher's read of a text the relay just wrote or read is not a
     change: the writer re-reading an IDE save before the watcher gets to it
     reports `discarded` once, and the watcher's read of the same text does
     not turn it into `external`. Neither does a read of a file that is the
     document a discard left (nothing new happened).
   - "Ahead" is by content, not version: a document whose edits were undone
     back to the file discards nothing when the file is read.
   - The check and the write are one step for the relay's own threads, not
     for other processes: an IDE save landing in the microseconds between the
     relay's last read of the file and its write is overwritten (the IDE
     usually reports the file changed on disk). The window is the gap
     between two system calls, not the 400 ms debounce.
1. **Surgically, when possible** (`surgical`). Only the statements and
   arguments that changed are rewritten; comments, blank lines, formatting,
   helper functions, RawCode and the Bindings interface elsewhere stay byte
   for byte. What is written — after the Bindings interface pass — is first
   read back and compared with the document; it is written only if it reads
   back as exactly that document.
   - Every node is paired with its statement by **identity** (its handle in
     the base), so an insert or delete beside a same-type sibling, with code
     between, touches just that statement.
   - A **move** (within a block, or into/out of an `if`, `else`, `forEach` or
     container — including the only statement of a block) cuts the node with
     its exact source text — including the comment lines directly above it
     and a comment after it on its line — and places it after its new
     previous sibling, re-indented (string and comment contents untouched).
     A multi-line code statement moved to another depth reads back as the
     same code: code lines compare without their indentation; a line that
     starts inside a string literal compares whole.
   - A node placed **first** in a block goes at the very top, right after
     `{`, at the depth of the block's first line — above any comments there
     (the first statement's own, or a section header). A comment on the `{`
     line itself (`Column { // header`, `forEach { row -> // why`) stays on
     that line; the statement goes on the line below it.
   - Inserted nodes and changed values are spelled as the file spells them:
     its own bindings parameter name (`bindings.title`, not `b.title`), and a
     RawCode node as its own text.
   - A modifier reorder is written as the reordered chain.
2. **As the full export, only when that is lossless** (`export`). The whole
   file is rewritten from the document only if the file as it is IS the
   export of its own tree (a trailing newline aside) — it holds nothing the
   exporter would drop — and the export reads back as the document.
3. **Otherwise refused** — and **an edit write-back would refuse is
   refused when it is made**. The relay plans the write-back of every edit
   it is asked to make — submit, dry run (same answer), undo and redo —
   exactly as the writer will: `ScreenWriter.plan` of the document the
   edit leaves, against the file as it is now (read once, under the
   screen's lock), with the same base and component and token tables. If
   the plan is a refusal, so is the op: "this change could not be written
   to `<file>` without losing code: `<reason>` — make this change in code";
   the document never takes it, and nothing is blocked. An undo or redo
   step the plan refuses is dropped (what refuses it is the file's code,
   which only an edit of the file changes — and that edit, read, clears
   both stacks). This covers what the document cannot check: a template
   reading a list item's field the file types `Double` or nullable (the
   contract carries no item types), a plain binding set in a screen
   function that takes no bindings parameter, and any reason the writer
   has. The preflight lets an edit through when
   - it changes nothing, or brings the document back to the file (nothing
     new to write; an undo out of a refusal is never refused);
   - the plan is "re-read" (the file changed since the relay last read
     it, and the watcher has not read it yet): the edit is accepted and the
     writer re-reads the file and reports it `discarded` (0. above).
     Reading the file at the edit instead would swap the document the
     client's batch was made against under its feet — and the change would
     be lost all the same;
   - the document could not be written before the edit either (a change
     refused at write time, below, or a file that does not read as a
     screen): the edit is not why, so it is accepted and the writer reports
     as before.

   The write itself is unchanged: it plans again when it runs (400 ms
   later; the file may have changed). Cost: one plan per edit — 6.0 ms
   median, 6.7 ms p95, 8.4 ms max on this repo's largest screen
   (`layout_evidence.kt`, 230 lines, 49 nodes), about 1.5 ms on the
   reference app's screens, while the edit holds the screen's lock.

   **What is still refused at write time** (`refused`) is what the plan at
   the edit could not see: the file changed between the edit and its write
   in a way that still reads as the base (a reformat, a comment), so the
   write merges onto a text the edit's plan never saw and cannot make the
   change in place; or a component or design token the screen uses
   changed in between. A write that fails (I/O) is `failed`. Then nothing
   is written and the file is left exactly as it is. The relay logs one
   line, the screen's write-back status (`writeBack` on the `/doc-events`
   stream and in `/devstate`) says why, and the editor shows a non-blocking banner and marks the save pill
   "Not written". **The document keeps the change that could not be written,
   so every later edit of this screen is refused with it** (the banner says
   so, and names the version that first failed): "this change could not be
   written to `<file>` without losing code: `<reason>`; undo it (from the
   session that made it) or make it in code — until then no edit to this
   screen is written". Undo is per session: only the session that made the
   change can undo it. A write that fails with an error is reported the same
   way (`failed`), never swallowed.

Every read of the file is reported too: `external` (the file was read; the
editor's warning clears) or `discarded` (read while the document was ahead
of it — an unwritten or refused change is gone; this includes the re-read
after a component or design token the screen uses changed, when that change
made the screen read differently; the editor's pill reads "Discarded", never
"Saved"). Switching screens resets the pill to the new screen's status. An event with no
status means none is current.

A new screen (no file yet) is created from the export — RawCode as its own
text — and only if that reads back as the document. Some edits are refused
up front, as ops, because they could never read back or would not compile:
a RawCode replacement that is not exactly one statement (several, a parse
error, or a comment after it — `Legacy(2) // c`) or that reads as something
the editor models (a widget, `if` or `forEach`: insert that as a widget
instead); a bound value, condition or list inserted into a screen function
that takes no bindings parameter (add the parameter in code first); a
template reading a screen field whose type the screen's file does not show
(the interface the screen takes is not in the file or extends one declared
elsewhere, or the field has no declared type — §7, "Where ingest reads a
reference's type": bind it directly, or make the change in code); any
edit write-back would refuse (the preflight in 3. above); and a
move that takes a local declaration away from its uses — a code node that
declares something (`val`, `var`, `fun`, `class`, `object`, …) never moves
to another block, nothing that mentions the declared name (a whole-word
text match, or the name in backticks) moves to before it or out of its
block, and nothing that uses a `forEach`'s item (a bound `note.title`, an
item condition, code naming `note`) moves out of that `forEach`. Deleting a
use is not moving it: it is written.

**What "reads back" does not cover.** The read-back check proves the file
reads as the document, not that it compiles. Beyond the declaration rule
above, a change can still leave code that does not compile — e.g. code
using a name declared by something the editor does not model as a
declaration (a lambda parameter of a container's content lambda, a
`remember`ed local hidden in a helper), moved where that name is not in
scope; or a name matched only by text (a shadowing local) letting a move
through that the compiler then rejects. The app's build is the check for
that.

Line endings: a CRLF file stays CRLF. In any other file every line the edit
does not touch keeps its own ending (an LF file's raw string with a `\r\n`
keeps it); an edit that would rewrite or drop a CRLF line of a file that
mixes endings is refused.

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
