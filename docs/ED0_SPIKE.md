# ED0 spike: the editor on the generated catalog (Gate B, part f)

Status: **a spike, behind a flag that is off by default.** Evidence toward Gate B
(docs/PLAN_2026-11.md §8, MS3). It shows ingest with 0 `RawCode` and byte-for-byte write-back of
Compose-shaped code, and a **separate canvas library** that draws the generated catalog exactly as
the device does. That canvas is **not wired into `portal-editor`**, so the criterion "the editor
renders the generated catalog" is only partly met (§4); the owner weighs that at Gate B. D6 and D9
still stand: nothing here changes how today's schemas ingest, render or write back. Amending D6
and D9 is a later pull request, after Gate B, with the owner's agreement (§5 below says what it
would need to say).

## 1. What was built

| Piece | Where | What it does |
|---|---|---|
| Editor metadata, extended | `keliver-compose-generator` (`Emit.entryJson`), `keliver-compose-gatea-schema/gatea.editor.json` | Every parameter in Compose's order (the `modifier` too, so positional arguments map), each value parameter's guest type and nullability, a slot's scope and whether it is optional, the guest's fully qualified name and a scoped modifier's scope |
| The canvas interpreter, generated | `portal-compose-render/src/commonMain/…/Generated.kt` (from `generateComposeApi`, `--out-editor-render`; covered by `checkComposeApi`) | One branch per widget overload and per modifier, each calling the **generated guest stub** with the node's arguments, or the stub's own default expression where the node sets none |
| The canvas | `portal-compose-render` (`ComposeCanvas`, jvm + wasmJs) | Draws a document node by node through those stubs; Repeat and Condition are evaluated against preview values; anything it cannot draw is reported, never guessed |
| Compose-shaped ingest | `portal-ingest`: `ComposeCatalog`, `ComposeSyntax`, `ComposeValues`, and a guarded branch in `Recognizer` | Resolution by import, overload choice, ordered modifier lists, Compose values, optional and multiple slots, `for` loops as Repeat |
| Byte-stable write-back | `portal-ingest`: `ComposeWriteBack`, a guarded branch in `WriteBack` | Text splices of the smallest piece that changed |
| The flag | `ComposeIngest` | Off unless a catalog is set for the thread (`ComposeIngest.with(catalog) { … }`) or the system property `keliver.portal.ed0.catalog` names editor-metadata files. Off, `Recognizer` and `WriteBack` take exactly today's paths |

**Nothing shipped carries the prototype.** `portal-ingest` takes `keliver-compose-values`
`compileOnly` (and for its tests), so `portal-relay` and the tools zip have the same runtime
classpath as before this spike (`:portal-relay:dependencies --configuration runtimeClasspath` is
identical to `main`'s). `ShippedClasspathTest` runs ingest and write-back with the flag off in a
class loader holding only `portal-ingest` and its runtime classpath: the prototype's classes are
absent and never needed. Turning the flag on there fails at once, with that reason.

To let the canvas run in the browser, the Gate A prototype modules (`keliver-compose-values`,
`-gatea-compose`, `-gatea-modifiers`, `-gatea-widget`, `-gatea-protocol-guest`,
`-gatea-protocol-host`, `-gatea-composeui`) gained the `wasmJs` browser target. They stay
unpublished prototypes.

## 2. The document shape (no document-model change)

A Compose node stays a `DocNode.Widget`; ED0 only fills it differently:

- **type** `<guest fq>#<lock tag>`, for example `dev.keliver.compose.material3.Text#6`. The tag
  names the overload (`Box#1` takes content, `Box#2` does not).
- **props**: the arguments the call passes, by parameter name. A Compose value is a `"c"` literal
  holding its wire JSON (the same JSON the protocol carries): `16.dp` is `"16"`,
  `MaterialTheme.colorScheme.primary` is `{"ref":"colorScheme.primary"}`,
  `RoundedCornerShape(12.dp)` is `{"rr":[12,12,12,12]}`. Strings, Ints, Booleans, Floats,
  bindings, templates and actions use today's `PropValue`s.
- **modifiers**: an ordered list flattened into today's map. Element *i* is the key
  `"<i>:<name>#<tag>"` and each argument it passes is `"<i>:<name>#<tag>.<param>"`, so
  `Modifier.padding(8.dp).background(…).padding(4.dp)` keeps both paddings and their order.
- **children**: a widget with exactly one required slot keeps the slot's statements as its
  children (today's D9 shape). Any other slot layout (optional slots, several named slots) has one
  `slot:<name>` child per slot the call passes, in source order; an absent optional slot has none.

## 3. Results

### Ingest: 0 RawCode

| Screen | Nodes | RawCode |
|---|---|---|
| Gate A's sample (`keliver-compose-gatea-composeui/…/samples/guest/SampleCard.kt`), read as a component | 14 (Column, Row, Box, Icon, Spacer, Column, Text, Text, Icon, FlowRow, Repeat, Box, Text, Spacer) | **0** |
| A Style B screen in Compose's shape (`ComposeFixtures.INBOX_SCREEN`: a bindings parameter, theme typography and colours, `Arrangement.spacedBy`, a comment inside the body) | 5 | **0** |

`ComposeIngestTest` (9 tests) covers each item the task names:

- **resolution by import**: a legacy `Text` (`dev.keliver.material.compose.Text`) and the
  generated one in two files of one project, and side by side in one file (with an alias,
  `import …material3.Text as M3Text`). A `dev.keliver.compose.*` name the catalog does not
  generate (`Button`) is RawCode with that reason. It is never read as a legacy widget of the same
  simple name;
- **resolution that is not certain is RawCode**, in a file that imports any guest name: two star
  imports that both give a generated `Text`; a name two guest star imports might give but the
  catalog does not generate (`Button` under `material3.*` and `foundation.layout.*`); an explicit
  import of Compose's own API (`androidx.compose.material3.Card`); a callee shadowed by a
  top-level declaration of the same file (a `Text` function, a `Modifier.padding` extension). A
  file that imports no guest name keeps today's reading even with the flag on;
- **ordered modifier lists with repeats**: `padding, background, padding, clip, background` keeps
  five elements in order;
- **overloads**, chosen as Kotlin chooses them: `padding(16.dp)` / `(horizontal = …)` /
  `(top = …)` / `(PaddingValues(…))`, `size(a)` / `size(a, b)`, `Box` with and without content.
  Of several overloads that fit, the one that uses no default value wins (Kotlin's tie-break is
  yes/no, not a count); any other tie is ambiguous, so `padding()` (#2 and #3, both on defaults
  only) is RawCode, as the compiler refuses it;
- **scoped modifiers** resolve against the parent's content scope: `weight` under `Row` is
  RowScope's and under `Column` is ColumnScope's; `align` is BoxScope's; `weight` under `Box` is
  RawCode with the reason;
- **Compose values** (`ComposeValuesTest`, 4 tests): `dp`, `sp`/`em`, `Color(0xFF…)`,
  `Color.White`, `MaterialTheme.colorScheme.x`, `MaterialTheme.typography.y`,
  `LocalTextStyle.current.copy(…)`, `TextStyle(…)`, `RoundedCornerShape`/`CutCornerShape`/
  `CircleShape`/`RectangleShape`, `FontWeight`, `TextAlign`, `TextOverflow`, `Alignment.*`,
  `Arrangement.*` and `spacedBy`, `PaddingValues(…)`, `Icons.Filled.*`. Every kind's canonical
  spelling reads back as the same value. Values are recognised **by their text, not by import**:
  `Icons.Filled.X` or `MaterialTheme.colorScheme.x` reads as the table's value even where an
  app's own object of that name is meant;
- **optional and multiple slots**: `ListItem` (one required and four optional slots) and
  `TopAppBar` (a required `title` and a RowScope `actions` trailing lambda). Gate A's group has
  neither, so the test describes them in the generator's metadata format;
- **outside the grammar is RawCode with its reason**: `Color(red = …)`, `Modifier.semantics {}`,
  a parameter that cannot cross to the host (`FlowRow(overflow = …)`), and a modifier held in a
  variable.

With the flag off, the same sample is not Compose nodes (`theFlagIsOffByDefault`).

### Write-back: byte for byte

`ComposeWriteBackTest` (19 tests: **16 round trips with an undo**, 3 refusals, 1 one-way edit and
1 no-op). A round trip applies document ops, writes back through `WriteBack.attempt` (whose safety
net re-reads the result as the document), then writes back the inverse ops. **The middle text
must equal the original with only the edited token changed, and the final text must equal the
original byte for byte.** The one-way edit (a middle element removed) checks the middle text only.

| Edit | Only this changes |
|---|---|
| A property: `fontSize` | `16.sp` → `18.sp` |
| A modifier value: Row padding `horizontal` | `16.dp` → `20.dp` |
| A modifier on a positional argument (`Column(Modifier…padding(16.dp), …)`) | `16.dp` → `24.dp`, still positional |
| A literal replaced by a theme reference | `Color(0xFF1F1F1F)` → `MaterialTheme.colorScheme.primary`; the `Color` import goes when unused and comes back on undo |
| A new argument | `, maxLines = 2` named and last; the undo removes exactly it |
| A new argument on a multi-line call | its own line, same indent, trailing comma kept |
| A modifier element removed | its `.padding(…)` piece and the line break before it |
| A middle element removed (one way) | that element only, and its now unused imports |
| An edit inside a named slot (`ListItem`'s `supportingContent`) | the one `Text` literal |
| Gate A's sample (3 round trips): `maxLines`, the card's vertical padding, a chip's background inside the `for` loop (a theme colour) | the one token each |
| The second of two repeated `padding` elements | its `4.dp`; the first `padding` keeps its bytes |
| An event passed as a trailing lambda (`clickable { b.archive() }`) | the lambda, in place |
| The last argument of a multi-line call with no trailing comma | that line and the comma before it; the undo puts both back |
| A modifier argument added to a call with none | `, modifier = Modifier.padding(8.dp)`; the undo removes the whole argument |
| An element inserted at the head of a chain (index 0) | `.padding(8.dp)` right after `Modifier` |
| A no-op merge (both screens) | nothing |
| A reorder of modifier elements | refused, with the reason (`NotSurgical`) |
| Removing an argument beside a comment (`Text("Draft", /* … */ color = …)`) | refused: the comment would go with it |
| Removing both arguments of `.padding(horizontal = …, vertical = …)` | refused: `.padding()` is ambiguous between #2 and #3, so the file would not compile |

### The canvas: the generated catalog, drawn

- `CanvasWireTest` (common: **runs on wasmJs and on the JVM**, 2 tests): the sample document
  (as ingested, 0 RawCode, checked in as a golden that `SampleCardDocumentTest` regenerates and
  compares) drawn by `ComposeCanvas` sends **exactly the protocol changes** (the JSON string)
  that the compiled `SampleCard` sends. After a binding changes (fewer tags), the canvas sends the
  same changes as the compiled screen with the same input.
- `CanvasPixelTest` (JVM, Skia, 1 test): the canvas's changes go through JSON into the
  **generated host renderers** (`keliver-compose-gatea-composeui`, which call the real Compose
  functions). The result is compared with the same screen written in native Compose: **0
  differing pixels** at 360 dp wide (images: `portal-compose-render/build/ed0/`). A tap on the
  canvas runs the document's action (`onOpen`).

## 4. What does not work yet (the spike's limits)

- **Not wired into `portal-editor`'s shell.** The canvas is a wasmJs library whose output is
  proven equal to the device's. `runPortalEditor` still draws today's `RenderNode` tree. Wiring
  it in needs: a guest composition in the browser feeding a host `ScopedChildren`, the relay
  serving the generated catalog, and the inspector reading the metadata's kinds. That is
  ED1-sized work, not a gate question.
- **Pixels on wasm are not captured**: this repository's wasm test runner cannot capture Compose
  pixels (skiko, U41). The wasm evidence is the protocol equality. The host renderers are common
  code and compile for wasmJs, but their pixels are checked on the JVM.
- **The canvas draws a bound Compose value (for example a `Color` from a binding) at its
  default** and reports it. Bound Strings, Ints, Booleans and lists are drawn.
- **Write-back that is not surgical** (refused with a reason, never guessed): reordering or
  changing several modifier elements at once; removing a positional argument that a later
  positional argument follows; a new value that is a template or a conditional; adding or
  removing a slot; adding or removing a trailing lambda; removing an argument with a comment
  beside it; an edit that leaves a call with no arguments whose overload is then ambiguous.
- **The value table is fixed**: `Color(red =, …)`, `dimensionResource`, arithmetic on `Dp`, and
  app-defined theme extensions are RawCode (`Color.copy(alpha =)` is read). Extending it is a
  table entry per value form. Values are matched **by their spelling, not their resolved type or
  import**.
- **Resolution is by the file's imports only.** A same-package declaration in *another* file, and
  a legacy star import that also gives a generated name, are not seen.
- **Conditions on the canvas** read single-term Boolean and emptiness tests only (ingest reads
  more; the canvas reports what it does not evaluate).
- **Overload choice** approximates Kotlin's without types: arguments must read as their
  parameter's kind, and of several candidates the one that uses no default value wins; any other
  tie is ambiguous (RawCode). Two overloads that differ only by a value parameter's type, with the
  same name, can be ambiguous where Kotlin is not; none of Gate A's group's overloads are. A binding
  fits any parameter type, so `Modifier.padding(b.inset)` is ambiguous between #1 and #4 and the
  whole widget is RawCode: safe, but coarse.

## 5. What D6 and D9 would need to say

**D6 (keep the grammar small).** Today it allows "widgets, literal/bind props, Repeat, Condition,
the three event shapes". It would add:

> For a generated group, the grammar also reads **Compose values from a fixed table** (units,
> colours and theme references, shapes, text styles, alignments and arrangements, padding values,
> icons), matched by their spelling, not by their resolved type or import, and a **`Modifier`
> chain as an ordered list** of generated modifiers. Resolution is **by import**: a call is a
> generated widget only when the file's imports name it. In a file that imports a guest name, a
> call is RawCode with its reason when its resolution is ambiguous (two star imports), when it
> names a `dev.keliver.compose.*` declaration the catalog does not generate (or one a guest star
> import might give), when it names Compose's own API, or when a declaration of the same file
> shadows it. Any other callee keeps today's simple-name reading, so legacy widgets and project
> components still mix with generated ones. Everything else is still RawCode with its reason. The
> table grows by entries, not by evaluating Kotlin.

A further **grammar addition, beyond the task**: a `for (x in items) { … }` loop over a list
binding reads as a Repeat (today's grammar has only `items.forEach { x -> … }`). It is in the
spike because Gate A's sample uses it; D6's amendment should accept or drop it on its own.

**D9 (one required trailing slot).** It would add:

> A generated widget may have **optional and several named slots**. A node whose widget has
> exactly one required slot keeps today's shape (the slot's statements are its children). Any other
> slot layout has one `slot:<name>` child per slot the call passes, and an absent optional slot has
> none. Hand-written components keep today's rule.

Both amendments are scoped to generated groups. For today's schemas the grammar and its document
shape stay as they are, as this spike shows: every existing `portal-ingest` test passes with the
code in place.

## 6. Running it

```
./gradlew :keliver-compose-generator:generateComposeApi        # regenerates the metadata and Generated.kt
./gradlew :portal-ingest:test --tests '*Compose*' --tests '*ShippedClasspath*'   # ingest, values, write-back, the flag-off classpath
./gradlew :portal-compose-render:jvmTest :portal-compose-render:wasmJsTest
./gradlew :portal-compose-render:jvmTest -Ped0.updateGolden=true   # after an ingest change: rewrite the golden document
```
