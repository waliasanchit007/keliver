# Compiled component preview — drawing opaque components exactly

**Status:** merged to `main` (#132). Libraries: the next release (0.4.0).
Tools: the relay emitter ships with the next tools release. Nothing here
changes what a device runs.

## The problem

A project component (D9) whose body is outside the portal grammar is
**opaque**: the recognizer cannot turn its body into a tree, so the canvas
cannot interpret it and draws a labelled placeholder instead
(`expandForPreview` → `Expansion.Opaque` → `placeholder(name, reason)`). A
screen built from opaque components therefore looks nothing like the device.

The canvas, though, already runs a Redwood **guest composition** on wasm
(`ProtocolRedwoodComposition` in `EditorShell`; `RenderNode` runs inside its
`setContent`), and a per-app editor already compiles the app's code for wasm
(its `AppPreviewEntry` runs the real presenters). The app's component is an
ordinary `@Composable` written against the keliver compose APIs, so the
editor can **call it** in that composition — producing exactly the widget
tree a device produces — if it knows how to turn an instance's arguments into
the call. That translation is an *adapter*, one per component, and it is
generated from the component's signature.

## The hook

```kotlin
public interface AppPreviewEntry {
  …
  /** component name -> adapter calling the app's compiled component. */
  public val compiledComponents: Map<String, CompiledComponent> get() = emptyMap()
}

public fun interface CompiledComponent {
  /** Decode the instance's arguments (plain code, may throw) and return the call. */
  public fun bind(call: CompiledCall): @Composable () -> Unit
}
```

A generated adapter:

```kotlin
"PriceTag" to CompiledComponent { call ->
  call.noContent()
  val p0 = call.string("label")
  val p1 = call.int("count")
  val p2 = call.bool("highlighted", false)
  val p3 = call.eventOrNull("onTap")
  call.compose {
    PriceTag(label = p0, count = p1, modifier = call.modifier, highlighted = p2, onTap = p3)
  }
}
```

The member is additive with a default, so existing entries compile
unchanged. `bind` is deliberately **not** `@Composable`: decoding happens in
plain code, where a failure can be caught (Compose does not allow a
`try`/`catch` around a composable call).

## Where it applies (precedence)

The editor's component hook (`installComponentPreview` in portal-editor):

1. **Transparent** component → transparent expansion, as before. The grammar
   view is the *editable* one (slots, nested nodes, write-back), so it stays
   primary even when an adapter exists.
2. **Opaque** component with an adapter → drawn compiled.
3. Opaque without an adapter (or the adapter refuses/fails) → the
   placeholder, as before, with the reason.
4. Cycle → the cycle chip.

Nested instances: when a transparent component's body calls an opaque
component that has an adapter, `expandForPreview(node, registry, compiled)`
leaves that call in the expanded tree as a component node (its arguments
resolved against the outer instance — an argument the outer instance omits
takes its default, else the editor's sample, as template parts do — and its
slot content substituted), and the renderer draws it compiled. The same holds
for an opaque call passed as slot content to a transparent wrapper. Without an
adapter it is a placeholder, as before.

A component the relay flags as on a **dependency cycle** is never drawn
compiled, even with an adapter: it keeps its placeholder.

**A "device-exact" toggle?** Adapters are generated for every component whose
signature is supported, transparent ones included, so a toggle that draws
*every* instance compiled is cheap to add. It is not offered yet: for a
transparent component the interpreted tree already equals the compiled one
(the parity tests prove it), so the toggle would only change what is
*selectable* — compiled internals are not separately selectable or editable.
Its real use would be checking a component whose recognized body is suspected
of drifting from its source, which the ingest parity goldens already guard.
Revisit if users ask for it.

## Decoding arguments

`CompiledCall` reads the instance node's props with the same resolution the
canvas applies to widget properties — reusing the existing code, not a copy:

| Instance value | Decoded as |
|---|---|
| literal | itself (Int → Double where a Double is expected) |
| design token (`C.Ink`) | its resolved value (`tokenValue`) |
| screen binding (`b.title`) | the preview value (mocks / live presenter), as `strB`/`intB`… do. **Unmocked:** a `String` previews `{field}`; an `Int`/`Boolean`/`Double` (or a mock that does not parse as one) silently takes the adapter's default — the component's literal/token default, else the sample `0`/`false`/`0.0` — exactly as a widget property does on the canvas. There is no visible marker for that case; give the binding a preview value (persona / live presenter) to see the real one |
| conditional (`if (b.x) A else B`) | the branch the preview chooses (`PreviewBindings.chosen`) |
| template (`"Hi ${b.name}"`) | its text (`PreviewBindings.text`) |
| event (`{ b.open() }`) | a lambda firing the instance's action into the action console / live presenter — what a transparent instance's widgets do; a no-op when none is wired |
| omitted, with a literal or token default | the default, inlined by the generator |
| omitted, required | the editor's sample (`ComponentSpec.sampleFor`: name, 0, false…) as transparent expansion uses |
| omitted, any other default | **refused** for this instance: the adapter cannot reproduce an arbitrary expression |
| a value of the wrong kind (e.g. a String for an Int) | **refused** with the reason |

A refusal throws `CompiledArgException` inside `bind`; the canvas draws the
placeholder with `compiled preview: <reason>`.

**Content slot.** `call.content()` renders the instance's children through
`RenderNode`, so they stay ordinary document nodes — each keeps its own
selection handle and stays editable. A slot lambda has no layout receiver, so
the children get none (a device's source could not write scoped modifiers
there either). A component with no slot calls `call.noContent()`: an
instance that has children is refused rather than silently dropping them.

## Parent scope and modifiers

`call.modifier` is the instance's own modifier chain built **in the
instance's parent scope** by the same generated `nodeModifier` `RenderNode`
uses (now `internal` rather than `private`): a `flex`/`margin`/alignment
under a Row/Column/Box receiver applies, one outside its scope is dropped —
exactly what the screen's source can and cannot write. The component applies
it to its root as it does on a device, so the root sits in its parent exactly
as the compiled call does.

## Selection

The instance must stay **one selectable unit**. The canvas tags rendered
nodes with the portal-internal `SelectionTag(handle)` modifier, and the
editor hit-tests taps against the tagged bounds (innermost wins). For a
compiled instance the tag rides in `call.modifier`, so it lands on the
component's **root only**; its internals carry no tag, so a tap anywhere
inside selects the instance; slot children carry their own handles, so they
remain selectable. A nested compiled instance carries the *outer* instance's
handle (as `tagWith` gives every node of a transparent expansion).

**Components without a `modifier` parameter** render exactly but have no
place to put the tag: a canvas tap inside one selects its parent; select it
from the layers tree. Wrapping it in a tagged container would change layout
(cross-axis stretch, flex) and defeat the point. The fix is in the app: give
the component `modifier: Modifier = Modifier` on its root — the K5 convention
for components that take caller modifiers anyway.

## Failure: never crash the canvas

- **Decode failure** → placeholder with the reason (above).
- **The compiled call throws while composing** (`name.first()` on an empty
  name) — a throw inside the canvas composition would stop the whole
  preview, and Compose cannot catch it around a composable call. So before
  composing a compiled call, the editor runs it once in a **probe**: a
  throwaway guest composition on the same protocol widget system whose
  changes go nowhere (`DiscardingCompiledProbe`). Initial composition is
  synchronous, so the answer is known before the canvas composes the call;
  a throw → placeholder `compiled preview failed: <Exception>: <message>`.
  The probe composes a **probe copy** of the call: the adapter is bound a
  second time with events that do nothing (a `SideEffect` that calls an
  event must not reach the action console or a live presenter twice) and a
  slot that composes nothing. It runs inside the **canvas's composition
  locals at the instance** (`currentCompositionLocalContext`: the app's
  screen-wrapper Theme, UI configuration, …), so a component that reads one
  is not reported as failing.
  **Cost:** each probe builds a throwaway guest protocol adapter, a
  `Recomposer` and a composition. Results are remembered per component +
  decoded arguments + modifiers + composition locals, so a stable instance is
  probed once; but every distinct argument set is its own probe — a `Repeat`
  of N rows with different values probes N times, and a changing preview
  value re-probes. `compiledComponentProbe` is an editor-internal hook (the
  shell installs it); apps do not set it.
- **Not covered:** a throw from an *effect* (`LaunchedEffect`) or a later
  recomposition driven by the component's own internal state. That still
  reaches the editor's guest error handler ("live preview composition
  failed") as any presenter failure does. Covering it would need per-instance
  subcompositions with error isolation that Compose does not provide.

## Generating adapters

`CompiledAdapterGenerator` (portal-ingest) reads each component file's first
`@Composable` function — the recognizer's rule — and emits one adapter per
component, with the per-parameter rule:

| Parameter | Adapter |
|---|---|
| `modifier: Modifier` (K5), required or `= Modifier` | `call.modifier` |
| `String` / `Int` / `Boolean` / `Double`, nullable too — the types an instance can set | decoded; an omitted one: literal / token default inlined (`-16_745_729`, `0x7F000000` included), `= null` for a nullable type, any other default → `call.requireSet` (refused for an instance that omits it) |
| `() -> Unit`, `(T) -> Unit`, nullable too | `call.event…`; `(T)` passes through `{ v -> f(v) }` so `T` needs no import |
| one required `@Composable () -> Unit` | `call.content()` |
| anything else **with** a default (enums, `Float`, lists, optional slots, `Any?`…) | left out — the compiled default applies; the portal cannot set it |
| anything else **without** a default | the component is **refused** |

A component is also refused when it is `private`, has a receiver
(`RowScope.Foo`), type parameters, a `vararg`, more than one content slot, a
`modifier` default other than `Modifier` (`= Modifier.padding(8)` would be
lost whenever the instance passes no modifiers, so the preview would not match
the device), collides with a primitive widget name, shares its name with
another component (an instance names its component by simple name, so the
two cannot both be previewed), or is `internal` while the adapters compile in
another module (`sameModule = false`). Refusals are listed, with reasons, in
the generated file's header; a refused component keeps its placeholder.

**The emitted file always parses.** Keyword parameter names and package
segments are backtick-quoted (`` `in` = p0 ``, ``import app.`in`.ui…``);
`@Composable` is recognized by its annotation's short name, so
`@androidx.compose.runtime.Composable () -> Unit` is a slot; every component is
imported under an alias (`import a.b.PriceTag as PriceTag__c3`) and the render types
are written fully qualified, so a component named `CompiledComponent` or
`mapOf` cannot clash. Each adapter is **parsed** before it is emitted, and one
that does not parse is refused instead. Parsing is not compiling, so the
signatures known to parse but not compile are refused up front: a function
annotation other than `@Composable`/`@Suppress` (an opt-in marker,
`@Deprecated(level = ERROR)`), context parameters, annotated parameters, a
name that looks like a generated alias (`…__c<N>`), and a name shared with a
class/object/typealias declared in the scanned component files. What the
generator cannot see — e.g. a same-named type declared elsewhere in the
package — can still produce a file that does not compile; the relay's preview
build then fails (the last-known-good editor stays served) until the
component is renamed or the file is fixed. The fixture set
(`portal-parity-fixtures`) compiles the supported edge cases on wasm.

**Who runs it: the relay.** `keliver.portal.json` gains an optional block:

```json
"compiledComponents": {
  "out": "editor/src/wasmJsMain/kotlin/AppCompiledComponents.kt",
  "packageName": "",
  "valName": "AppCompiledComponents",
  "sameModule": true
}
```

Before every preview build the relay regenerates `out` from the app's
components dir with the project's design-token table (each file under its
repo-relative identity, so file-private token defaults resolve), writing only
when the text changes (an unchanged signature costs no recompile). The write
is guarded: `out` must stay inside the repo, neither it nor a directory on its
way may be a symbolic link, an existing file whose first line is not the
`GENERATED` header is never overwritten, the text keeps the existing file's
line endings (CRLF stays CRLF), and it is written to a temp file and renamed
into place. A generation failure fails that preview build; the last-known-good
editor stays served. Without the block nothing changes; with the block but no
`previewBuildTask` the relay warns at start, since nothing would use the file.
The relay already rebuilds the editor on any component edit, so a changed body
shows after the rebuild and a changed signature regenerates its adapter.

**Without a relay** (CI, a hook): `./gradlew :portal-relay:compiledComponentAdapters`
writes the file and `-Pcheck` fails when it is stale (`-PappDir=<dir>` for
another app). It is the relay's own code path with the same token table.
This repo's CI runs the check for the dogfood editor.

The file is generated source checked into the editor's sources (it must be
there for a build without the relay, e.g. CI). This repo's dogfood editor
(`web-spike`) does exactly that: `AppLibCompiledComponents.kt`, kept current
by `CompiledAdaptersTest`.

## What an adopter needs

1. **The components compiled for wasm.** Components may only use APIs that
   exist on wasmJs: the keliver compose libraries do; `kotlin.js`,
   `external`, `dynamic`, browser APIs and Zipline do not. The simplest route
   needs no new target in the app: the per-app editor scaffold
   (`keliver-new-editor.sh <App> <components dir>`) compiles listed source
   dirs straight into the editor's `wasmJsMain`; set `"sameModule": true`
   then. A components dir that imports app logic drags that logic in too.
2. **A per-app editor** (`previewBuildTask`/`previewDist`) and the
   `compiledComponents` block above.
3. `override val compiledComponents = AppCompiledComponents` in its
   `AppPreviewEntry` (the scaffold now shows the line, commented).
4. Libraries ≥ 0.4.0 and a tools bundle with the relay emitter. (The tools
   bundle does not yet ship a stand-alone generator command; the
   `compiledComponentAdapters` task exists in this repo only.)

The tools bundle's *generic* editor has no app code, so it cannot draw
compiled components; opaque components stay placeholders there.

## Compatibility and API

All library changes are additive (`apiDump`):

- portal-render: `CompiledComponent`, `CompiledCall`, `CompiledArgException`,
  `CompiledComponentProbe`, `compiledComponentProbe`,
  `RenderCompiledComponent`, `AppPreviewEntry.compiledComponents` (default
  empty).
- portal-core: an `expandForPreview(node, registry, compiled, stack)`
  overload; the existing signature is unchanged and delegates.
- portal-editor: no public change (an internal object's compose-stability
  symbol appears in the klib dump).

## Evidence

- `portal-render` `CompiledComponentParityTest` (wasm): an opaque instance
  rendered through its **generated** adapter builds the same widget tree as
  the compiled call made directly — literal, omitted (defaults),
  token/binding/template/conditional arguments, a slot, a component without
  `modifier`, scoped modifiers in Row/Box/Column and outside a scope; the
  tag on the root only; events firing the instance's action; decode and
  probe failures → placeholder with the rest of the canvas rendered.
- `portal-editor` `CompiledComponentEditorTest` (wasm) through the editor's
  own hook: a component that throws while composing → placeholder and the
  canvas still renders (removing the probe makes it fail); one selectable
  unit with selectable slot children; nested in a transparent component;
  precedence (a transparent component is never drawn compiled).
- `portal-ingest` `CompiledAdapterGeneratorTest`: the rules and refusals; the
  fixture adapters are a staleness-guarded generated file
  (`KELIVER_UPDATE_COMPILED_ADAPTERS=1` regenerates).
- `portal-ingest` `ComponentExpansionTest`: the `compiled` overload (nested,
  slot content, omitted outer argument → sample).
- `portal-relay` `CompiledAdaptersTest`: emission, no-op rewrite, repo
  boundary, symbolic links, never clobbering a hand-written file, CRLF,
  file-private token defaults, the config warning, `check`, a failed step
  stops the build, the dogfood file is current.
- `:web-spike:wasmJsBrowserDistribution` links with the dogfood adapters.

Browser session on the dogfood editor (main `84d0447b0`, 2026-10-20;
[`superpowers/evidence/compiled-component-preview/`](superpowers/evidence/compiled-component-preview/README.md)):
two `NoteCountPill` instances added to the feed screen draw compiled
("3 NOTES", "1 ITEM" — computed text the grammar cannot express), a tap
selects one as a single unit (`NoteCountPill · component (opaque)`, outlined
in the tree), and editing Count 3 → 7 in the properties panel redraws
"7 NOTES" and writes `count = 7` back to the screen file surgically.

Not yet done: a device screenshot comparison for an adopter component (D14).
