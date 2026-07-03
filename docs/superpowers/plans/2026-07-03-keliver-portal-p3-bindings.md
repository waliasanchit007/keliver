# Portal Phase 3: Bindings + Contract Codegen — Implementation Plan

> Executed autonomously (superpowers:executing-plans; user AFK, all-phases SOTA push).

**Goal:** Props can be **bound** to named data fields and events to **named actions**; the screen's contract is DERIVED from the tree (no separate contract store — single source of truth); export emits `@Composable fun <Name>Screen(b: <Name>Bindings)` + the `interface <Name>Bindings` (the round-trip boundary); the editor gets bind toggles, event rows, a bindings panel with MOCK values, and a live action console (tap a Button in the preview → see the action fire).

**Design (locked):**
- portal-core types: `data class Bind(val field: String)`, `data class Action(val name: String)` as prop VALUES. Wire tags: `{"bind":"f"}` / `{"action":"a"}` in Serialize.kt (backward compatible — new tags only).
- Contract derived by `collectContract(tree)` in portal-core using the generated catalog for kinds: fields `Map<String, PropKind>` (kind of the prop at the bind site; first wins on conflict), actions `List<String>`. List-kind props can't bind (no UI offered).
- Dev-preview resolution lives in **portal-render** (compose-capable): `object PreviewBindings { val mocks = mutableStateMapOf<String, String>(); var actionSink: (String) -> Unit }` + hand `*B` getters on WidgetNode (`strB/intB/boolB/dblB/dpB…`) that unwrap Bind via mocks (string-typed, parsed per kind) and fall back to literals/defaults. Generated RenderNode switches to `*B` getters and NOW WIRES EVENTS: `onClick = node.actionOf("onClick")?.let { n -> { PreviewBindings.fire(n) } }` (lambda arity from schema `Event.parameters`).
- Codegen: `EventPlan(name, paramCount)` in PropModel (nullable events only, as before); catalog `WidgetSpec.events: List<String>`; exporter's generated code computes the contract at runtime and emits the bindings signature + interface when non-empty (else the old parameterless shape). Kotlin types: Text→String, Int/Color→Int, Bool→Boolean, Double/DP→Double.
- Editor: per-prop **@ bind toggle** (literal input ⇄ field-name input); **Events** rows (action-name input per schema event); **Bindings panel** (derived fields + typed mock inputs, actions list); **Action console** card (live log via actionSink).
- Device dev-guest: same generated interpreter; mocks are editor-local so bound props render defaults on-device in P3 (documented); actions no-op on device until P4's compiled path.

**Tasks**
1. portal-core: Bind/Action + serialize tags + `collectContract` (+ unit-testable via guest-compiler round-trip). Catalog `events` field (CatalogTypes + emitter + PropModel EventPlan w/ arity) — regenerate.
2. portal-render: PreviewBindings + `*B` getters + `actionOf`; EmitRenderNode → `*B` + event wiring; regenerate; all compiles.
3. EmitExporter → contract-aware exportKotlin; guest-compiler gains a BINDINGS verifier: tree with bound text + action button → exportScreen → hand-written mock impl compiles + round-trip serialize check. All green.
4. Editor: bind toggles, event rows, bindings panel + mocks + action console; rebuild dist; Chrome-verify: bind a text to `title` + mock it, wire Button onClick→`buyTapped`, TAP the button in the canvas → console logs it; export shows `b.title` / `b::buyTapped` + interface. Commit + memory.
