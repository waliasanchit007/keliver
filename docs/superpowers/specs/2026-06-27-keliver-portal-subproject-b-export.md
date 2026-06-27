# Portal Sub-project B — Export-to-Kotlin (design)

> Branch: `spike/keliver-web`. Follows sub-project A (the runtime engine).
> Parent: `2026-06-27-keliver-web-portal-design.md`.

## Goal

Turn a `WidgetNode` tree into real keliver guest **Kotlin** source (a `@Composable`
function) that **compiles** and would render identically to the live preview — the
"Export to Kotlin" half of the runtime-driven-edit + Kotlin-export model.

## Design

**Render and export are two backends over the same `WidgetNode`.** `RenderNode`
(sub-project A) renders; `exportKotlin` emits source. To make the model + codegen
usable by both the wasm engine *and* a JVM compile-verifier, they move into a small
shared module.

### `portal-core` (new module)

Plain Kotlin Multiplatform (`jvm` + `wasmJs`), **no keliver deps** (just stdlib).
Package `dev.keliver.portal`. Contains:
- `WidgetTree.kt` — `WidgetNode` + typed getters (moved out of `web-spike`).
- `sampleTree(items: Int): WidgetNode` — the shared sample tree (moved out of
  `web-spike`'s `buildTree`), so the engine demo and the export verifier use one
  source of truth.
- `Export.kt` — `exportKotlin(tree, functionName = "ExportedScreen"): String`.

`web-spike` depends on `portal-core` (deletes its local `WidgetTree.kt`; its engine
imports `dev.keliver.portal.*`).

### `exportKotlin`

Mirrors `RenderNode`'s `when(type)`, emitting for each node
`WidgetType(arg = literal, …) { children }`, wrapped in imports + `@Composable fun`.
Per-type literal rendering: `Int` → decimal; `String` → quoted/escaped; `Double` →
`Dp(x)` (Spacer height); `List<Int>`/`List<Float>` → `listOf(...)`. The emitted
args must match the args `RenderNode` reads per widget (so render ≡ export).

**Known coupling (accepted for MVP):** `RenderNode` and `exportKotlin` are two
parallel `when()`s that must list the same args per widget. Unifying them
(descriptor- or schema-generated) is deferred — same deferral as A's catalog.

### Verification — repurpose the orphaned `web-spike-guest-compiler`

It is a JVM module that already has the keliver composables on its classpath (was
gate-3's dead `screen.json` tool). Repurpose:
- Add `implementation project(':portal-core')`; trim to the deps needed to *compile*
  generated code: `keliver-material-compose`, `keliver-layout-compose`,
  `keliver-runtime` (for `Dp`), `jetbrains.compose.runtime`.
- `GuestCompiler.kt` `main()` → `exportKotlin(sampleTree(3))` written to
  `src/main/kotlin/Exported.kt`.

Two-step gate: `:web-spike-guest-compiler:run` (generate `Exported.kt`) then
`:web-spike-guest-compiler:compileKotlin` (compile it). A green compile proves the
exported Kotlin is valid guest source that uses the composables correctly.

## Out of scope (YAGNI)

Unifying the render/export `when()`s; exporting modifiers/events; pretty hex colors;
exporting the package/screen scaffolding beyond a single `@Composable fun`;
round-trip (Kotlin → tree).

## Done when

`:web-spike-guest-compiler:run` emits `Exported.kt` from the sample tree and
`:web-spike-guest-compiler:compileKotlin` compiles it green.
