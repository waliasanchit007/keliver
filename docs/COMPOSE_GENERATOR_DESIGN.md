# The Compose-generated guest API — design

MS2 (Gate A) of [`PLAN_2026-11.md`](PLAN_2026-11.md), decision PD2 and workstream WS2: generate the
guest API from Compose's own API and sources, and have hosts call the real Compose functions.
This note fixes the generator's shape before code, as
[`BRIDGE_CODEGEN_DESIGN.md`](BRIDGE_CODEGEN_DESIGN.md) did for the bridges. Gate A builds a
prototype for one screen of the private 2026-10 feature (its listing screen); Gate B (MS3) confirms
or drops PD2. Nothing here amends D6 or D9 ([`DECISIONS.md`](DECISIONS.md)); section 13 says what
the editor would need, and that stays on a branch until a pull request after Gate B.

## What Keliver does today (the starting point)

- **Schemas to code.** A schema is Kotlin data classes annotated `@Widget`/`@Property`/`@Children`/
  `@Modifier` and listed in `@Schema(members)`. `keliver-tooling-schema` parses it with a K2 FIR
  session over sources and a classpath (`schemaParserFir.kt`, `kotlin-compiler-embeddable`), and
  `keliver-tooling-codegen` emits the guest `@Composable`s, the protocol adapters on both sides,
  the host's widget interfaces and testing helpers. Host renderers are written by hand
  (`keliver-material-composeui`, about 30 lines a widget, `WIDGET_PARITY.md`).
- **Parameters are Keliver's, not Compose's.** `Background(colorArgb: Int)`,
  `Padding(allDp: Int)`, `Icon(name: String, sizeDp: Int)`; 19 universal modifiers applied by
  `applyKeliverVisuals`, which pre-scans `CornerRadius` so shadow, border and clip share a shape
  whatever the order. `Row`/`Column` are Yoga flex containers (`ComposeUiFlexContainer`), not
  Compose's `Row`/`Column`.
- **The wire.** `Create(id, tag)`; `PropertyChange(id, widgetTag, tag, value: JsonElement)`,
  every property sent at creation; `ModifierChange(id, [[tag, json]…])`, where a modifier's JSON
  object omits properties equal to their `@Default`; children `Add`/`Move`/`Remove` by children
  tag; events `(id, tag, args)`. A dependency schema's tags are offset by `tag × 1,000,000`
  (`MAX_MEMBER_TAG`), so `keliver-material` sees layout at 1 and lazy layout at 2.
- **Mismatches.** A host's `ProtocolMismatchHandler` gets unknown widgets, modifiers, properties and
  children; the treehouse host forwards them to `EventListener.unknownWidget` etc., whose defaults
  do nothing, and the host templates do not override them. So today an unknown tag is ignored
  silently.
- **The gate (D2).** An index entry carries `widgetVersion` (one integer) and `constraints`;
  hosts skip an entry with a constraint key they do not know (`KNOWN_CONSTRAINTS` in
  `BundleIndex.kt`), which B4 used for `bridges`.
- **The editor.** `portal-schema-codegen` reads the same schemas and emits the catalog, exporter
  and `RenderNode`. The recognizer finds a widget by its simple callee name (`widgetSpec(type)`),
  and holds a modifier chain as one value per modifier name: a chain that repeats a modifier is
  `RawCode` (`ModifierChain.resolve`, "a repeat would be lossy").
- **The guest already runs the real Compose runtime** (`org.jetbrains.compose.runtime`, through
  `keliver-compose`), with a frame clock the host drives. Only the UI layer is Keliver's.

Sections 1 to 14 are the decisions; 15 is a worked example; 16 and 17 scope the gates.

## 1. Input: Compose's binaries for signatures, its source jars for defaults

| What | Read from | How |
|---|---|---|
| Declarations, parameters and types, `@Composable` function types and their receivers (`RowScope.() -> Unit`), `inline`, nullability, which parameters declare a default, `@Deprecated` levels, opt-in markers, `const` values | The Compose Multiplatform **desktop JVM** artifacts (`foundation-layout-desktop`, `foundation-desktop`, `ui-*-desktop`, `material3-desktop`, `animation-desktop`) and third-party ones (`coil-compose`) at the pinned versions (CMP 1.8.2 for Gate A, which is AndroidX Material 3 1.3.2 on Android) | A K2 FIR session over the jars, the same setup `schemaParserFir.kt` already runs; Kotlin metadata keeps everything in the left column |
| Default **expressions** (`LocalTextStyle.current`, `ButtonDefaults.shape`), KDoc | The same artifacts' `-sources.jar` (they carry `commonMain`) | PSI, as `portal-ingest` parses screens; matched to the FIR declaration by fully qualified name and parameter types |
| Which declarations are common | The sources' `commonMain` | A declaration only in `androidMain`/`desktopMain` is never generated as supported |

Not used: the binary-compatibility `.api` dumps (JVM shapes with `$default` methods and
`Composer` parameters, no defaults; at most a quick diff when Compose moves), AndroidX's Metalava
`current.txt` (not published as an artifact), and KLIB metadata (the same declarations, through a
loader the repository does not run today).

What is generated is chosen per group (section 14) by an include list of packages and fully
qualified names. Every other public declaration in an included package becomes an error stub
(section 11), so a guest never sees "unresolved reference" for a Compose name, only an error that
names the alternative.

## 2. The pipeline: emit a Redwood schema, reuse everything after it

The generator (a JVM tool module, `keliver-compose-generator`, run by a Gradle task with a
`check…` staleness guard as `portal-schema-codegen` has) emits **checked-in sources**, per group:

1. **A Redwood schema** (`@Schema` with `@Widget(tag, internalComposable = true)` data classes and
   `@Modifier` classes, tags from the lock file, section 3). The existing `keliver-tooling-codegen`
   turns it into the protocol adapters, the host widget interfaces and internal guest composables,
   unchanged. One new emitter instead of three; the protocol, its mismatch handling and its tests
   are already reviewed.
2. **Guest stubs** in `dev.keliver.compose.*` (section 4) that encode their arguments and call
   the internal composables.
3. **Host renderers** implementing the generated widget interfaces by calling the real Compose
   function (section 8).
4. **Editor metadata** (section 13).

Checked in, every regeneration is a reviewable diff, and the lock file's diff is the API change.

## 3. Overloads, tags and the lock file

- **One wire widget per Compose overload.** The guest compiler picks the overload, because the
  stubs have the same overload set as Compose; the chosen stub knows its own tag. No resolution
  logic exists outside the Kotlin compiler.
- **The lock key** is the declaration's canonical signature:
  `androidx.compose.material3/Text(String,Modifier,Color,TextUnit,FontStyle?,…,TextStyle)`.
  Each key holds its widget or modifier tag, and each parameter its property or children tag by
  name: at first generation, its 1-based position in the signature (the `modifier` position goes
  unused, since the modifier chain has its own channel); a later parameter gets the next free tag.
- **Append-only.** A key never changes its tag, and a removed key's tag goes to
  `reservedWidgets`/`reservedModifiers` (the schema annotation already has both). The staleness
  guard fails a regeneration that changes anything but appends.
- **Compose's own evolution.** Compose adds a parameter by adding an overload and keeping the old
  one for binary compatibility, hidden from callers. In CMP 1.8.2, `material3.Text(String, …)`
  exists twice in the jar: the current one and an older one without `minLines`. A hidden overload
  keeps its tag (an old guest built against it still renders), gets no stub, and its renderer calls
  the current function with the new parameter left at its default.
- **The group level** is an integer in the lock that grows by one whenever a generation appends
  anything to that group. It is what the compatibility gate compares (section 12).

## 4. Guest stubs: Compose's signatures in `dev.keliver.compose.*`

- **Package rule:** prefix `dev.keliver.` and drop a leading `androidx.`:
  `androidx.compose.material3.Text` becomes `dev.keliver.compose.material3.Text`,
  `coil3.compose.AsyncImage` becomes `dev.keliver.coil3.compose.AsyncImage`.
  `androidx.compose.runtime.*` stays as it is: the guest runs the real runtime, so `remember`,
  `mutableStateOf`, `LaunchedEffect` and `@Composable` need no import change.
- **Same names, parameter order, types, defaults, `inline`ness and receivers.** Scope members
  (`RowScope.weight`) are members of a guest `RowScope` interface, as in Compose. `Row` is
  `inline` in Compose, so a native screen may `return` from inside its content; the stub is
  `inline` too, but Redwood's `RedwoodComposeContent.Children` is not, so the content lambda must
  be `crossinline` unless the emitted code inlines the children node (a small codegen change Gate
  A tries; otherwise such a `return` fails to compile, rare in screen code).
- **Value types are guest mirrors** with Compose's API: `Dp`, `TextUnit`, `Color`, `Shape`,
  `TextStyle`, `Alignment`, `Arrangement`, `PaddingValues`, `ImageVector` and so on, in the mirrored
  packages (`dev.keliver.compose.ui.unit.dp`). They are not the real classes because the real
  `ui-graphics`/`ui-text` pull Skia bindings into Kotlin/JS, and because a guest value must be able
  to be a theme reference (section 7). `Modifier` is a typealias of Redwood's `dev.keliver.Modifier`,
  so legacy and generated widgets nest inside each other in one tree.
- **What a stub does at run time:** encode each argument with its codec, decide default against
  explicit (section 5), and call the internal composable, which emits `Create`, `PropertyChange`,
  `ModifierChange` and children changes through Redwood's protocol, as today. A modifier stub
  (`Modifier.padding(16.dp)`) appends a Redwood modifier element, in chain order; repeats are
  allowed.
- **Parameters that cannot cross** (a host-measured result such as `onTextLayout:
  (TextLayoutResult) -> Unit`, or a per-frame producer such as `Icon`'s `ColorProducer` overload):
  the main stub leaves the parameter out, and a second overload that keeps it is
  `@Deprecated(level = ERROR)` with the alternative in its message. A call that does not pass it
  resolves to the main stub (Kotlin prefers the candidate with fewer unused defaults); a call that
  passes it fails to compile with the message. Kotlin has no deprecation on a single parameter,
  so this is the only compile-time route.

## 5. Default against explicit

A host must call Compose with Compose's default when the guest left the argument out. Each
parameter falls into one of three cases, decided by the generator from the default expression:

1. **Literal default** (`null`, `true`, `1`, `Int.MAX_VALUE`, `TextOverflow.Clip`,
   `Color.Unspecified`, `TextUnit.Unspecified`, `Arrangement.Start`, a `const val`): copied into
   the stub, the schema (`@Default`) and the renderer. The value is always sent; an explicit value
   equal to the default renders the same.
2. **Non-literal default** (`LocalTextStyle.current`, `LocalContentColor.current`,
   `MaterialTheme.shapes.small`, `ButtonDefaults.buttonColors()`): the stub's default is the same
   expression, copied verbatim. In the guest it evaluates to a reference or a call record
   (sections 6 and 7). The stub sends `null` ("the default") when the argument equals that value,
   and the value otherwise; the wire type is nullable. The renderer passes `arg ?: <the same
   expression>`, evaluated on the host, inside the same composition locals as Compose's own default
   would be. Copying the expression, not its value, means the app's resolved Compose version
   supplies the value.
3. **A default that references non-public API**, so the copied expression does not compile on the
   host: the renderer splits on presence instead
   (`if (x == null) F(…) else F(…, x = x)`). At most two such parameters per function; beyond that
   the function is excluded and the generator says why. A parameter that is nullable with a
   non-null default (rare) is wrapped (`[v]` explicit, `null` default) so that an explicit `null`
   survives.

Why not every combination: Kotlin can only use a default by leaving the argument out of the call
text, so "pass it only when present" for n parameters is 2ⁿ call sites. Case 2 covers almost all
of them with one call site.

## 6. Value codecs (written by hand, once)

Each mirror type has one `@Serializable(with = …)` serializer (guest and host) and one host
converter to the real Compose value, given a theme resolver. Encodings are short JSON, chosen to
read well in a protocol trace:

| Type | Wire | Notes |
|---|---|---|
| `Dp`, `TextUnit` | `16` / `"16sp"`, `"1.2em"`; `"u"` for `Unspecified` | `Unspecified` is a literal default, sent as `"u"` |
| `Color` | `4279983648` (ARGB, sRGB), `"u"`, or `{"ref":"colorScheme.primary","a":0.5}` | the mirror stays a 64-bit value class; a reference uses a colour-space id Compose never assigns, plus an index and the alpha, so `.copy(alpha = …)` works on both |
| `Brush` | `{"lin":[c…],"stops":[…],"from":[x,y],"to":[x,y]}`, `vert`, `horiz`, `rad`, `sweep`, `solid` | colours may be references |
| `Shape` | `"rect"`, `{"rr":[ts,te,be,bs]}` (dp) or `{"rr%":[…]}`, `"circle"`, `{"cut":[…]}`, `{"ref":"shapes.medium"}` | a guest-defined `Shape` subclass is an error stub (it is per-layout code) |
| `TextStyle` | `{"ref":"typography.titleMedium","set":{"fontWeight":700,"color":…}}` | only fields the guest set; the host takes the reference and `merge`s the set fields; `copy` on a reference records into `set` |
| `FontWeight`, `FontStyle`, `TextAlign`, `TextOverflow`, `TextDecoration`, `ContentScale` | an int or a short name | |
| `FontFamily` | `"sans"`, `"serif"`, `"mono"`, or a host-registered name | a resource font is an error stub (section 11) |
| `Alignment` (+ `.Horizontal`, `.Vertical`) | biases `[h,v]` / `h` / `v` | named constants are biases, as in Compose |
| `Arrangement` | `"start"`, `"sb"` (space between) …, `{"sp":8,"a":-1}` for `spacedBy` | a custom `Arrangement` is an error stub |
| `PaddingValues` | `[s,t,e,b]`, `{"abs":[l,t,r,b]}` | |
| `ImageVector` | `"Filled.Search"` (an icon from an included icon group) or path data | path-built vectors are Gate B |
| `Painter` | `{"vec":…}`, `{"color":…}` | `painterResource` is an error stub |
| `*Defaults.*()` factories (`ButtonDefaults.buttonColors(containerColor = …)`) | `{"call":"ButtonDefaults.buttonColors","args":{…}}` | one hand-written call-record codec; the factories themselves are generated like widgets, and the host calls the real factory |
| Coil `model: Any?` | a URL string, or an `ImageRequest` call record (`data`, `crossfade`) | another runtime type is reported, not sent |

## 7. Theme references resolve on the host

`MaterialTheme.colorScheme.primary`, `MaterialTheme.typography.titleMedium`,
`MaterialTheme.shapes.medium`, `LocalContentColor.current` and `LocalTextStyle.current` are
references in the guest, not snapshot values. The host resolves them where the widget renders,
under the app's `MaterialTheme`, so dark mode, dynamic colour and an app's own typography apply
with no guest change and no recomposition in the guest. For guest logic that reads a value
(`primary.luminance() > 0.5f`), the host also sends a snapshot of its theme with the
`UiConfiguration` it already sends (dark mode, insets, density): a read gets the snapshot's value,
and the value's reference is kept as long as no arithmetic touches it (an arithmetic result is a
literal; a lint check can flag that).

## 8. Host renderers call the real function

A generated renderer implements the generated widget interface for the Compose host (the
`Widget<@Composable (Modifier) -> Unit>` shape every composeui widget has today): each property
setter stores the decoded value in a `mutableStateOf`, and `value` calls the Compose function with
them (section 15 has one). Layout, drawing, semantics, ripples and defaults are Compose's, by
construction; there is no hand-written renderer for a generated widget ("no host overrides" in
Gate A). The renderers are common Kotlin against Compose Multiplatform, so the same code serves
Android, the iOS host and the editor's wasm canvas (ED0).

The modifier chain becomes a real Compose chain in the guest's order: one generated `when` from
element tag to the real modifier call. There is no pre-scan; that exists in
`applyKeliverVisuals` only because `CornerRadius` is not a Compose concept. Legacy elements in a
generated widget's chain still go through `applyKeliverVisuals`.

Lambda modifiers (`Modifier.clickable { … }`, `toggleable`, `selectable`): today's schema parser
refuses function-typed modifier properties (U6). The generated stub keeps the lambda in the
guest, in a per-node table, and the element carries only a handle; every generated widget has a
reserved event `modifierEvent(handle, args)` that the host's `clickable` calls. Recomposing
replaces the lambda in the table without changing the chain, so a new lambda does not resend
modifiers. This works on generated widgets; a legacy widget keeps its own click properties.

## 9. Slots, children and scopes

- `content: @Composable RowScope.() -> Unit` is a `@Children` with that scope (Redwood supports
  scoped children). Several slots (`TopAppBar`'s `title`, `navigationIcon`, `actions`) are several
  `@Children` tags.
- **An optional slot** (`leadingContent: (@Composable () -> Unit)? = null`) gets a hidden boolean
  property, "present", and the renderer passes `null` when it is absent: Material lays out an
  absent slot differently from an empty one (`ListItem`'s leading space).
- **Scoped modifiers** (`weight`, `align`, `alignBy`, `matchParentSize`) are Redwood modifiers
  with scopes. The parent's renderer converts each child's chain inside its own content lambda,
  with its real scope as receiver (`children.Render(scope = this)`, a scoped variant of
  `ComposeWidgetChildren.Render`), so `Modifier.weight(1f)` is the real `RowScope.weight`, at its
  place in the chain.
- **Slots with parameters** (all Gate B), by what the parameter is: guest data
  (`AnimatedContent(target) { state -> … }`) is composed in the guest, the host keeping the
  outgoing children for the transition; a host-measured value (`Scaffold`'s `PaddingValues`,
  `BoxWithConstraints`) comes to the guest as an event when it changes, one round trip; a
  composable to place (`decorationBox`'s `innerTextField`) is a placeholder child the host fills.

## 10. Host-owned state and per-frame APIs

| API | Runs on | Crosses the bridge |
|---|---|---|
| `rememberScrollState`, `rememberLazyListState`, `rememberPagerState`, `SheetState`, `TextFieldState` | the host owns the real object, created by an id the guest's mirror carries as a property | commands guest to host (`scrollToItem`, `animateScrollToPage`; a `suspend` command returns when the host reports completion); fields host to guest (`firstVisibleItemIndex`, `currentPage`, `isScrollInProgress`) only when the guest reads them, on change, at most once a frame. Text editing reuses `keliver-material-api`'s `TextFieldState` round trip (its `userEditCount`) |
| `AnimatedVisibility`, `AnimatedContent`, `Crossfade`, `animateContentSize`, `animateItem`, enter and exit specs | the host (generated composables and modifiers) | the flag or target, and the spec, once per change |
| `animate*AsState`, `rememberInfiniteTransition().animate*` | proposed: the host. The stub returns a `State` whose value is an animated reference (target + spec); the renderer wraps that argument in the real `animate*AsState` | the target and spec once per change; a guest read gets the target. The alternative (the guest's frame clock, a `PropertyChange` per frame) is what Gate B measures against |
| `Animatable` with `animateTo` in a `LaunchedEffect` | the guest, on the frame clock the host drives | one change per frame: allowed, measured, and flagged by a lint check |
| `Modifier.graphicsLayer { … }` | the block runs in the guest at composition, against a recording scope; the assigned values become a static element (animated references stay references) | the recorded values on change; reading layer-phase values (`size`) in the block is an error stub |
| `Canvas`, `drawBehind`, `drawWithContent` | drawing commands recorded in the guest, replayed by the host; `size` comes from the host after the first layout | the command list on change (Gate B) |

## 11. What cannot work fails at compile time

Error stubs are `@Deprecated(message, level = ERROR)` declarations with Compose's signature, so a
call fails to compile with a message that names the alternative. A curated table maps each to its
message; any other public declaration of an included package that is not generated gets a generic
one naming its category. First entries:

| Declaration | Message points to |
|---|---|
| `Layout {}`, `SubcomposeLayout`, `Modifier.layout {}` | `Row`/`Column`/`Box` with `weight`/`align`, `FlowRow`, or an app component (section 14) |
| `Modifier.pointerInput`, `detectTapGestures`, `detectDragGestures` | `clickable`, `combinedClickable`, `draggable`, `anchoredDraggable` |
| `LocalContext`, `LocalView`, `LocalActivity`-style Android locals | a generated bridge (`BRIDGE_CODEGEN_DESIGN.md`) |
| `stringResource`, `painterResource`, `vectorResource`, `Font(R.font…)` | strings in the guest; `AsyncImage`; an icon group; host-registered images and fonts (open question 6) |
| `onTextLayout`, `ColorProducer` overloads, a custom `Shape`/`Arrangement` | the declarative alternative, by name |
| Android-only APIs (`AndroidView`, `BackHandler` from activity) | `dev.keliver.compose.BackHandler` (exists today), an app component |

## 12. The compatibility gate (extends D2)

- **What a bundle needs** is written by its build into the signed manifest metadata,
  `keliver.compose = "foundation-layout@1,material3@1"`: each generated group the guest depends
  on, at the level it was compiled against. Gradle reads it from the generated `ComposeApi`
  constants of the guest's dependencies, so nobody writes it by hand (unlike `keliver.bridges`).
- **The index entry is derived from the manifest**, on the same paths B4 derives `bridges`
  (`publishStatic`, republish and promotion, the relay's per-request index):
  `constraints.compose = {"foundation-layout": 1, "material3": 1}`.
- **A host** knows the groups and levels its generated renderers were built from (constants, no
  app configuration). At lookup it skips an entry needing a group it lacks or a higher level; at
  load (`ManifestPinningHttpClient`, `AcceptCachedBundle`) it refuses such a manifest with
  `compose refused: …`, reported as `compose-missing`, through the paths a refused bridge takes.
- **Old hosts** skip the entry, since `compose` is a constraint key they do not know (W4.5's
  fail-closed rule). The relay's legacy `/bundles/latest` route leaves out bundles with this
  metadata.
- **Inside the gate**, `ProtocolMismatchHandler` stays the second line: an unknown widget,
  modifier, property or children tag is ignored, as today, and the host templates now report it
  (a `schema-mismatch` report reason, de-duplicated by tag) instead of dropping it silently.
- **Granularity:** a whole group's level is coarse. A guest compiled at `material3@5` that uses
  only level-1 widgets is refused by a level-4 host. Computing the levels a bundle actually uses
  (the stubs' tags are constants that survive dead-code elimination, so a manifest step can scan
  the JavaScript) is open question 4.

## 13. The editor

- **Metadata the generator emits, per widget, modifier and parameter:** palette group (the Compose
  package), property kind (from the codec: colour, dp, text unit, text style, shape, alignment,
  arrangement, icon, padding), the default as source text ("default:
  `MaterialTheme.typography.bodyLarge`"), the first KDoc sentence, slot names and whether each is
  optional, and opt-in markers. `portal-schema-codegen` already reads Redwood schemas, so the
  generated schemas reach its catalog, exporter and `RenderNode` emitters; its `MappedKind` gains
  those kinds.
- **What the recognizer would need** (ED0): resolution by import, not by simple name (a legacy
  `Text` and `dev.keliver.compose.material3.Text` can be in one project); ordered modifier lists
  with repeats; Compose values (`16.dp` is read today as K1; theme references,
  `RoundedCornerShape(12.dp)`, `TextStyle(…)` are not); optional and multiple slots. That is the
  D6 and D9 amendment; it stays on a branch until Gate B's evidence and the owner's agreement.
- **The canvas** calls the generated renderers on wasm, so the canvas draws what a device draws.
- **Gate A does not exercise the editor** (the plan: Gate A uses D14's compile and screenshot
  steps). It emits the metadata, so that Gate B starts from it.

## 14. The legacy schema, side by side, and widget groups

- **Wire:** `KeliverMaterial` stays the root schema with its tags, so every bundle and host
  built today is unchanged. The generated groups join it as Redwood dependencies at new dependency
  tags (offset `× 1,000,000`), next to layout (1) and lazy layout (2). One tree can mix legacy and
  generated widgets in either direction (same `Modifier`, same composeui widget shape).
- **Groups**, each a schema with its own lock level:

| Group (dependency tag) | Compose packages | Gate |
|---|---|---|
| `foundation-layout` (10) | `androidx.compose.foundation.layout` | A: `Box`, `Row`, `Column`, `Spacer`, `padding`, `size`/`width`/`height`, `fillMax*`, `weight`, `align` |
| `foundation` (11) | `androidx.compose.foundation` (+ `.shape`): `background`, `border`, `clickable`, `Image`, shapes; later `lazy`, `pager`, `text` | A in part |
| `ui` (12) | `androidx.compose.ui` modifiers (`clip`, `alpha`, `rotate`, `graphicsLayer`) and the value packages | A in part |
| `material3` (13) | `androidx.compose.material3` | A: `Text` (the `String` overload), `Icon` (the `ImageVector` overload), theme references |
| `icons-core` (14), `icons-extended` (15) | `material-icons-*` as names | A: core; extended is opt-in per app with an allowlist (today's curated `MaterialIcons.kt` exists for the same size reason) |
| `animation` (16) | `androidx.compose.animation(.core)` | B |
| `coil` (17) | `coil3.compose` | A: `AsyncImage` (URL model) |
| `extras` (18) | Keliver-only, imported from `dev.keliver.compose.extras` so a reader sees it is not Compose | A: the shimmer, the one hand-written renderer |
| app components (100+) | an app's own composables, exported by explicit opt-in | after Gate B |

- **Groups and app size:** a host ships only the groups it includes. Today the generated host
  factory requires an implementation for every dependency; an absent group would need a codegen
  change (its widgets reported as unknown). Gate A ships every group it uses; Gate B measures
  app size and decides.
- **Migration:** a tool that rewrites legacy calls (`Background(colorArgb)` to
  `Modifier.background(Color(…))`) is MS4's.

## 15. A worked example

The screen code, as written for Android (only the imports differ in the guest):

```kotlin
@Composable
fun ItemRow(title: String, quantity: String) {
  Row(
    modifier = Modifier.fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
    Text(quantity, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
  }
}
```

The guest's imports are the native file's with `androidx.` replaced by `dev.keliver.`
(`dev.keliver.compose.foundation.layout.Row`, `dev.keliver.compose.foundation.background`, …,
`dev.keliver.compose.ui.unit.dp`); `androidx.compose.runtime.Composable` is unchanged. Two of the
generated stubs:

```kotlin
// dev/keliver/compose/foundation/layout/Row.kt (generated)
@Composable
public inline fun Row(
  modifier: Modifier = Modifier,
  horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
  verticalAlignment: Alignment.Vertical = Alignment.Top,
  content: @Composable RowScope.() -> Unit,   // crossinline unless the children node is inlined (section 4)
) {
  // RowW1: the Redwood composable from the emitted schema (internal, @PublishedApi for inline)
  RowW1(horizontalArrangement, verticalAlignment, modifier) { RowScopeInstance.content() }
}

// dev/keliver/compose/material3/Text.kt (generated; 10 parameters elided, copied in order;
// onTextLayout moves to an ERROR overload, section 4)
@Composable
public fun Text(
  text: String,
  modifier: Modifier = Modifier,
  color: Color = Color.Unspecified,
  fontWeight: FontWeight? = null,
  /* … */
  minLines: Int = 1,
  style: TextStyle = LocalTextStyle.current,   // in the guest: a reference to the host's local
) {
  TextW1(text, color, fontWeight, /* … */ minLines,
    style = style.takeUnless { it == LocalTextStyle.current }, modifier = modifier)
}
```

The wire for the first `Text`, in the protocol's change list (framing abbreviated; `material3` is
dependency 13, so its `Text` overload 1 is widget tag `13_000_001`):

```
create   id=9 tag=13000001
property id=9 tag=1 "Packing tape"                                // text
property id=9 tag=3 "u"                                           // color: Color.Unspecified, a literal default, sent
property id=9 tag=17 {"ref":"typography.titleMedium"}             // style
modifier id=9 [[10000301,{"weight":1.0}]]                         // RowScope.weight, foundation-layout
add      id=7 tag=4 childId=9 index=0                             // into the Row's content (its 4th parameter)
```

(`fill = true` is omitted because it equals its `@Default`, as Redwood's modifier serializers do
today.) The generated host renderer for that overload:

```kotlin
internal class ComposeUiTextW1 : TextW1<@Composable (Modifier) -> Unit> {
  private var text by mutableStateOf("")
  private var style by mutableStateOf<WTextStyle?>(null)   // null: the guest left it out
  /* … one state and one setter per property: override fun style(style: WTextStyle?) { … } … */
  override val value: @Composable (Modifier) -> Unit = { m ->
    androidx.compose.material3.Text(
      text = text,
      modifier = m,
      color = color.resolve(),
      fontWeight = fontWeight?.toCompose(),
      /* … */
      style = style?.resolve() ?: LocalTextStyle.current,   // absent: Compose's own default expression
    )
  }
}
```

`m` comes from the `Row`'s renderer, which converted the child's chain inside its content lambda
with its `RowScope` as receiver, so `weight` is Compose's.

## 16. What Gate A exercises, and what waits for Gate B

**Gate A** (on Kotlin 2.2.0, Zipline 1.22.0, CMP 1.8.2, the Android emulator), only what the
listing screen needs:
- the generator reading the real artifacts and source jars, for the include list in section 14's
  "A" column, with the lock file and group levels;
- codecs for `Dp`, `TextUnit`, `Color` (with references), `Shape` (rectangle, rounded, circle,
  theme references), `TextStyle` (with references), `FontWeight`, `Alignment`, `Arrangement`,
  `ImageVector` (icon names) and the Coil URL model;
- `Box`, `Row`, `Column`, `Spacer`; `background`, `clip`, `border`, `padding`, `size`, `fillMax*`,
  `weight`, `align`; `Text` with `TextStyle`; `Icon`; `AsyncImage`; the shimmer as an extra;
  `Modifier.clickable` if the screen's rows are tappable (open question 2);
- the generated renderers in the Android development host; the screen ported from its native
  source with only imports changed, and compared with today's port on the emulator.

**The baseline is native Compose, not only today's port.** The listing screen is written once as
one plain-Compose source file. The same file is compiled twice: as native Compose (Compose's
imports) inside the host app, and as the guest (`dev.keliver.compose.*` imports); a script writes
one from the other and diffs them, so "only imports differ" is checked, not claimed. The host app
draws the native copy in an activity set up exactly as the Keliver screen (the same edge-to-edge
window, theme and insets), so three screenshots are taken on the same emulator and host: A
(today's Keliver port), B (the generated-API guest) and native. The web version is a fourth only
if it is reachable without signing in; nobody signs in or requests a one-time password for it.

Its criteria are the plan's: plain Compose with only imports changed (the import diff is the
whole diff, outside the app's own private helpers, which are counted and reported); screenshots
match (B against native pixel for pixel where nothing animates; A against both by eye, since
today's layouts are Yoga's); no hand-written renderer for a generated widget; first frame no slower than today's port;
a new component costs minutes (timed: add one Compose function outside the set, regenerate, see
it render). Also recorded: the guest bundle's size against today's port, and how many of the
generated parameters needed case 3 of section 5.

**Gate B** (MS3, upgraded stack): two more screens; the iOS simulator; an agent writing a screen
from its spec; `LazyColumn` (through the existing lazy-layout windowing, behind a Compose-shaped
`LazyListScope`), state objects, slots with parameters, `Canvas`; host-side against guest-side
animation, timed; the gate of section 12 in the hosts and the publishing paths; the editor
(section 13) with 0 `RawCode` and byte-stable write-back; app size with groups; the lock carried
across the Compose version MS1 brings, with appends only.

## 17. Risks and open questions

| # | Risk or question | Answered by |
|---|---|---|
| 1 | A copied default expression drifts from the app's Compose (the expression changes between versions) or references non-public API | Gate A counts case-3 parameters in its set; Gate B regenerates on MS1's Compose and diffs the copied expressions (a change is a lock-review item) |
| 2 | **Decide:** include `Modifier.clickable` (the modifier-event design, section 8) in Gate A, or defer it | The main session; proposed: include it if the listing rows are tappable, since a listing without taps is not a port |
| 3 | First frame and tree size: references and style objects make properties larger; real `Row`/`Column` replace Yoga | Gate A measures the first frame against today's port on the emulator; batching or a binary encoding later if needed (plan, section 11) |
| 4 | **Decide:** the gate's granularity: whole group level (simple, refuses more) or levels actually used (scan of the dead-code-eliminated JavaScript) | Gate A uses whole levels; Gate B decides with the lock's first real version change |
| 5 | **Decide:** mirror value types (proposed) or the real `ui-unit` on JS for `Dp`/`TextUnit` | Gate A measures what the real `ui-unit` adds to a bundle; mirrors stay for `Color`, `TextStyle`, `Shape` either way (references) |
| 6 | **Decide:** app images, icons and fonts the native screen takes from resources: host-registered names in `extras` (`KeliverImages.named("…")`), an app component, or `AsyncImage` URLs | The listing screen's port shows which it needs; the choice is recorded at Gate A |
| 7 | Animated values on the host (references) against the guest's frame clock | Gate B's timing measurement (plan WS2) |
| 8 | App size: renderers referencing all of Material 3 keep R8 from removing it | Gate B measures; groups, and optional groups in the host factory if needed |
| 9 | Overload parity: a call resolves to the same overload in the guest as in Compose | By construction for mirrored overloads; MS4's same-source corpus compiles one file against both |
| 10 | Theme values read by guest logic lose their reference after arithmetic | The snapshot (section 7) plus a lint check; Gate B's agent-written screen shows how often it happens |
| 11 | The editor's grammar against Compose-shaped code (D6, D9) | Gate B, then a pull request against `DECISIONS.md` |
| 12 | Hosts that embed a generated renderer raise the app's Compose floor (item 3's K3) | The floor report; Gate B on the upgraded stack |
| 13 | Error stubs that miss a declaration leave "unresolved reference" | The generic stub for every non-generated public declaration of an included package; Gate B's agent-written screen |

## 18. Steps (phase 2)

1. `keliver-compose-generator`: the FIR and source-jar readers, the lock file, the group levels,
   golden-file tests on Gate A's include list.
2. The mirrors and codecs of section 16, with round-trip tests (guest encode, host decode).
3. The emitted schema, stubs and renderers for the Gate A set; `apiCheck`; a compile test of the
   worked example against both Compose and the stubs (imports swapped).
4. The renderers in the Android development host; the listing screen ported; screenshots, first
   frame and bundle size against today's port on the emulator. The private code and numbers stay
   out of this repository; the verdict goes in, criterion by criterion.

## 19. What the Gate A prototype does differently

The prototype (the `keliver-compose-*` modules) follows this note except where noted; each point is
a Gate B item. Results: [`GATE_A_VERDICT.md`](GATE_A_VERDICT.md).

- **Reader.** Kotlin metadata is read with `kotlin-metadata-jvm` over the desktop jars, not a FIR
  session (§1): the same declarations, with far less setup. Overloads with the same parameter names
  are told apart by a simple type in the config (`size(size:Dp)`).
- **One group, its own root schema.** The generated declarations form one group, `gatea`, whose
  schema is a new root with `keliver-material` (and layout, lazy layout) as dependencies, so nothing
  existing changes (§14 attaches groups to the legacy root instead). A guest that uses it needs the
  root's protocol factory, and its legacy widgets travel under offset tags.
- **Stubs are not `inline`** (§4): Redwood's children node is not inline.
- **Modifiers.** A nullable modifier parameter is left out of the stub (Redwood's modifier
  serializers cannot take one); an expression default in a modifier travels as the value plus an
  `…IsDefault` flag rather than as `null`.
- **Defaults.** Case 3 (§5, the presence split) is not implemented; no Gate A parameter needed it.
- **Not generated yet:** error stubs for every other declaration of an included package (only a
  curated two, plus those the classifier produces), optional slots, the D2 gate's metadata (the
  level is in the lock; nothing reads it yet), widget groups. (Scope supertypes and `*Defaults`
  mirrors: Gate B part a, §20.)

## 20. Gate B, part a: the generator on the stack's Compose, and a wider set

Still the prototype modules, still unpublished (`-PkeliverComposePrototype` only). What changed:

- **Input.** The generator reads the desktop artifacts of the same catalog aliases the renderers
  compile against (`jbCompose` for foundation and ui, material3's own pin, coil), so bumping
  `jbCompose` fails `checkComposeApi` until the group is regenerated. Gate A's separate
  `composeGeneratorInput` pin is gone. The schema's header names the versions read.
- **Hidden overloads (§3).** A configured overload that Compose now hides keeps its lock entry and
  wire shape, gets no stub (an `ERROR`-level one keeps Compose's message), and its renderer calls
  the visible overload with the parameters both have. The generator refuses a hidden overload
  whose visible successor is not configured, so a Compose bump cannot silently drop one. Compose's
  `WARNING` deprecations are copied onto the stubs.
- **The lock records what travels.** Each entry's `wire` maps the parameters sent on the wire to
  the level they started at. A parameter that starts travelling later (its type gained a codec)
  is an append at a new level with the tag it always had; a synthetic one (a lazy list's window)
  takes the next free tag. Stopping, or changing a level, fails `checkAppendOnly`. Factories
  (below) have their own tags. Renderers treat a parameter with a default as "not sent" until it
  arrives, so a guest built before a parameter travelled still renders.
- **`*Defaults` mirrors (§5–§7).** Every `XxxDefaults.member` a configured default names is
  mirrored into `keliver-compose-values` (`GeneratedDefaults.kt`): a `@Composable` getter of a
  colour or shape becomes a reference the host resolves by calling it (`ButtonDefaults.shape`,
  `DividerDefaults.color`); a plain value is read from the jar at generation time and written as a
  literal (`DividerDefaults.Thickness`, `ButtonDefaults.ContentPadding`); a function returning a
  type only factories make (`ButtonColors`, `CardElevation`) is mirrored with all its public
  overloads as a recorded call (`DefaultsCall`, the lock's `factories`) that the host makes with
  the real factory. As for a widget's parameters (§5), the call records only the arguments that
  differ from their defaults, and the host evaluates each missing one with Compose's default
  expression, in parameter order: `CardDefaults.cardColors(containerColor = Color.White)` gets the
  host's `contentColorFor(Color.White)` (which matches White to a theme colour the guest cannot
  see), not the guest's approximation. A default that names a non-public value
  (`FilledButtonTokens.ContainerElevation`, material3's `DisabledAlpha`) has that value written in
  as a literal on both sides. A member that cannot be mirrored, or a factory of a type the config
  says cannot cross (`ScrollableDefaults.flingBehavior()`), becomes an `ERROR` member, and a widget
  parameter defaulting to it is left out of the stub with that reason.
- **Explicit null (§5's wrap).** A nullable parameter with a non-null default (`Button`'s
  `elevation`, `OutlinedButton`'s `border`) sends `null` for "the default" and a marker for an
  explicit `null`, for the types that have one (factory-made types and `BorderStroke`).
- **New codecs:** `BorderStroke` (solid colour, or a factory call), `Painter` (an icon's vector
  painter, `ColorPainter`), `ColorFilter.tint`, and the host-owned `LazyListState` (below).
  `contentColorFor` is mirrored by hand (theme references to their "on" colours).
- **Lazy lists (§16).** A `LazyListScope.() -> Unit` parameter is windowed: the guest's
  hand-written, Compose-shaped `LazyListScope` (`item`, `items`, `itemsIndexed`, keys) feeds
  keliver-lazylayout's loading strategy, which composes only a window of items; each composed item
  travels as one `ExtraLazyItem` node, and the window's place in the list (`content.before`,
  `content.after`) and the host's viewport (`content.viewport`, an event) are synthetic
  properties. The host calls the real `LazyColumn` with the whole count, renders each item's
  children inside the real `LazyItemScope` (so `fillParentMaxWidth` and `animateItem` are
  Compose's), and shows a 48 dp placeholder outside the window. `LazyListState` is host-owned
  (§10): `scrollToItem` and `animateScrollToItem` are commands in the `state` property;
  `firstVisibleItemIndex` and its offset follow the viewport event. The guest's
  `rememberLazyListState` saves the position with a `Saver`, as Compose's does, and the host saves
  the id of the last command it ran beside its state, so a restored subtree does not run the
  guest's last command again.
- **A newer guest on an older host.** A `*Defaults` factory the host does not know (appended at a
  later level) gives Compose's own default for that parameter where it renders, and an icon
  outside the host's groups a placeholder; each is reported once (`GeneratedMismatches`), never a
  crash in composition.
- **Scope supertypes** are mirrored (`FlowRowScope : RowScope`).
- **The lock across the Compose move, level by level.** Gate A left level 2 (read from CMP 1.8.2).
  `GeneratorTest.theLockCarriesGateAsWithAppendsOnly` keeps that lock as a fixture and fails if
  any of its tags, parameter tags or levels change; Redwood's own `redwoodApiGenerate`, which
  refuses removals, accepted the schema (198 lines added to `keliver-api.xml`, none removed).
  - *Level 3: CMP 1.11.1 with Gate A's set*, only what Compose itself changed. material3 1.9.0's
    `Text` gained `autoSize` as a new overload and hid the old one: the old one keeps widget 6
    (its renderer calls the new one), the new one is widget 10 (`autoSize` is dropped for now:
    an `ERROR` overload names it). foundation 1.11.1 hid
    `clickable(enabled, onClickLabel, role, onClick)` for one that adds `interactionSource`: the
    old one keeps modifier 20, the new one is modifier 26. While the added parameter does not
    travel, the new overload's wire is the old one's apart from its tags, so its stub still sends
    the old entry (widget 6, modifier 20) and a guest built now renders on a host built before
    level 3; the new entries stay in the lock, and a stub moves to its own entry once an added
    parameter travels (`sentAs`). Each Gate A entry gained its `wire`
    map, at the level it was created (an append nothing reads as new). `FlowRow` carries
    Compose's new `WARNING` deprecation (its `overflow`).
  - *Level 4: the wider set.* Widgets 11–26 (coil's `AsyncImage` with painters, which a `Painter`
    codec now makes possible; `Image` with a painter and with a vector; `LazyColumn`, `LazyRow`;
    `Icon` with a painter; `Surface` and its clickable form; `Card` and its clickable form;
    `Button`, `TextButton`, `OutlinedButton`, `IconButton`; `HorizontalDivider`; `TopAppBar`),
    modifiers 27–31 (`border(BorderStroke, Shape)`; `LazyItemScope`'s `fillParentMaxSize`,
    `fillParentMaxWidth`, `fillParentMaxHeight`, `animateItem`), factories 1–15, and one
    parameter of an old entry that starts travelling: widget 8 (`AsyncImage`) sends
    `colorFilter`, with the tag (9) it always had, from level 4. `Scaffold` is an `ERROR` stub
    (its content takes the host-measured `PaddingValues`).
