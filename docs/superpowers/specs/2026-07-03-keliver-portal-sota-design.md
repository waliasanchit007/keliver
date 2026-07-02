# Keliver Portal → SOTA Internal Platform — Design

**Date:** 2026-07-03
**Status:** Approved (brainstormed + user-validated)
**Builds on:** `2026-06-27-keliver-web-portal-design.md` (portal MVP),
`2026-06-29-keliver-portal-m2-optionX.md` (device sync — verified end-to-end
browser→Android on 2026-07-03).

## Goal

Evolve the proven portal demo (browser drag-drop editor → live native render on
Android via relay + Zipline → Kotlin export) into a **state-of-the-art internal
server-driven-UI platform** — the Cash App/Airbnb model, done better: mixed
authors compose screens visually, drafts preview live on web and real devices,
and production ships **compiled, signed Kotlin** through keliver's native
Treehouse OTA path.

## Locked decisions

| Axis | Decision |
|---|---|
| Target | SOTA **internal platform** (not external product, not demo) |
| Prod artifact | **Compiled Kotlin → signed `.zipline`**; the tree is the *authoring* format only |
| Authors | Mixed (designers/PMs/engineers), **round-trip** via a generated contract interface |
| Logic scope | **Bindings only** — named actions + data fields in the tree; implementations are hand-written Kotlin |
| Safety bar | **Signed + versioned bundles + widget-protocol compat gating.** Review workflows, staged rollout, preview environments: explicitly OUT of scope |
| Deployment | **Local-first** (one JVM server, SQLite/files), with a clean seam so it can be deployed later. No cloud work now |
| Widget coverage | **Full keliver-material set (~60 widgets + modifiers) via schema codegen** — zero hand-maintained `when()`s |
| Editor chrome | **Kotlin DOM (kotlinx-browser) + a proper design system.** Canvas preview stays Compose-wasm. No React/TS, no Compose-canvas chrome |
| iOS | **Full peer** (dev + prod modes, verified on simulator) |
| Roadmap shape | **Platform spine first** (Approach 1), with the editor UI/UX redesign pulled forward to Phase 2 — the current bare-DOM chrome is explicitly unacceptable |

## Section 1: System architecture

One split governs everything: **authoring** (tree, instant) vs **shipping**
(Kotlin, signed).

```
                       ┌─ AUTHORING (instant) ─────────────┐
  portal-editor (web) ──► draft tree ──► portal-server ──► dev devices (interpreter guest, poll)
        │                                (SQLite/files)          ▲
        │ live wasm preview (same interpreter)                   │ dev mode
        │                                                        │
        └─ PUBLISH ──► exportKotlin ──► compile+sign (.zipline) ─┴─► prod devices
                       (generated Screen.kt + Bindings contract)     (signed, versioned, compat-gated)
```

### Module map (→ = evolves from today's module)

| Module | Role |
|---|---|
| `portal-core` → | Pure tree model: `WidgetNode`, type-tagged serialize/deserialize, `EditTree` helpers. No keliver dependencies. Unchanged heart. |
| `portal-schema-codegen` *(new)* | JVM Gradle tool reading the keliver-material `@Widget` **schema source** (reusing keliver's FIR codegen infrastructure). One schema walk, three generated backends: catalog, interpreter, exporter. |
| `portal-render` *(new, js + wasmJs)* | Home of the *generated* `RenderNode` interpreter — shared by the web preview and the device dev-guest. Ends the current RenderNode copy-paste (web-spike + portal-device-guest). |
| `portal-editor` → (from `web-spike`) | The redesigned editor app: Kotlin-DOM chrome + Compose-wasm canvas preview. |
| `portal-server` → (from `portal-relay`) | One JVM app, the deployable seam: serves the editor statically, stores projects/screens/drafts (SQLite), draft-tree endpoint for dev devices, publish API, versioned bundle store. |
| `portal-publish` *(new)* | The pipeline: tree → Kotlin → Gradle-compiled guest → `.zipline` + manifest signing → versioned, metadata-tagged bundle in the store. |
| `portal-device-android` / `portal-device-ios` → | Host apps with two modes: **dev** (interpreter guest + draft poll, `NO_SIGNATURE_CHECKS`) and **prod** (signed manifest verification, fetches the highest *compatible* bundle). |

## Section 2: Schema codegen engine (the SOTA core)

Today three parallel hand-written `when()`s (catalog / RenderNode / exportKotlin,
7 widgets each) must be kept in sync by hand. Replace them with generation from
the same schema source keliver's own codegen reads (`keliver-material-schema`):

1. **`GeneratedCatalog.kt`** → `portal-core`: per-widget `PropSpec`s (name,
   type, default, event-or-value), children-slot info, palette category. Drives
   the editor's palette and property panels for all ~60 widgets.
2. **`GeneratedRenderNode.kt`** → `portal-render`: `when(node.type)` mapping
   every widget to its keliver-material composable, every prop via the typed
   getters, children slots recursed, **including modifiers**
   (padding/size/etc.) — a coverage gap today.
3. **`GeneratedExporter.kt`** → `portal-core`: the Kotlin-emitting visitor,
   same coverage as the interpreter by construction.

Generated code is **committed** (reviewable diffs when the schema changes),
regenerated by a Gradle task, and guarded by a CI staleness check — the same
pattern as keliver's apiDump. A new widget added to the schema appears in the
palette, the preview, the device, and the exporter automatically.

## Section 3: Bindings + the round-trip contract

A prop value is **literal** (`{"s":"Buy now"}`), **bound** (`{"bind":"ctaLabel"}`),
or — for events — an **action** (`{"action":"addToCart"}`). Each screen's tree
declares its contract: `fields` (name: type) and `actions` (names).

Export emits two generated files plus one human-owned file:

```kotlin
// GENERATED — regenerated on every publish, never hand-edited
@Composable fun CheckoutScreen(b: CheckoutBindings) {
  … Button(text = b.ctaLabel, onClick = b::addToCart) …
}
interface CheckoutBindings { val ctaLabel: String; fun addToCart() }

// HAND-WRITTEN — engineers own this; codegen never touches it
class RealCheckoutBindings(private val repo: CartRepo) : CheckoutBindings { … }
```

The interface **is** the round-trip boundary: portal edits regenerate the
generated side freely; engineer logic lives behind the interface; neither
clobbers the other. A contract change (e.g. a new action) breaks the impl's
compile — the correct signal. In dev preview the editor supplies mock field
values and an "action fired" console, so authoring needs no implementation.

## Section 4: Publish pipeline + device runtime modes

**Publish** (a `portal-server` API call, runs locally):

1. Export the project's screens into a template guest module owned by
   `portal-publish` (`StandardAppLifecycle` + generated screens + the
   committed bindings impls).
2. Invoke Gradle (tooling API) → Kotlin/JS → `.zipline` bundle.
3. **Sign the manifest** with the project's EdDSA key (keliver's existing
   `ManifestSigner`); prod hosts embed the public key.
   `NO_SIGNATURE_CHECKS` becomes dev-mode-only.
4. Store as `bundle vN` with metadata: `widgetProtocolVersion`, keliver
   version, created-at, and the **tree snapshot hash** (every bundle traces to
   the exact tree that produced it — the audit trail).

**Compat gating:** hosts request `/bundles/<app>/latest?widgetVersion=W`; the
server returns the newest bundle whose recorded protocol version ≤ W. Old
installed binaries keep receiving bundles they can render; new widgets reach
only hosts that know them. (Makes keliver's `widgetVersion=1U` stub real.)

**Failure containment:** compile/sign failures return the full log to the
portal as a publish log. A bundle becomes `latest` only after signing plus a
headless QuickJS load smoke test pass. On-device, Treehouse's cache fallback
already keeps the last good bundle rendering if a new one fails to load —
rollback-by-default inherited for free — plus a manual "pin previous version"
on the server.

**Device modes** (same host app, build flag or debug-drawer switch):
- **dev** — interpreter guest polling the draft tree (today's verified loop,
  kept forever: it is the authoring superpower).
- **prod** — signed compiled bundle; no interpreter, no relay.

## Section 5: Editor UI/UX redesign (Phase 2 — pulled forward)

The current bare-DOM chrome is not acceptable. Redesign lands immediately after
codegen (it needs the generated catalog to lay out a 60-widget palette), on
**Kotlin DOM + a small typed design system** (panels, lists, inputs, icons,
real stylesheet — no framework, no npm/TS toolchain; all-Kotlin repo stays
true; the trade-off is hand-rolling primitives a framework would provide).

Scope: proper panel layout + visual design + dark theme, canvas framing with
device-size presets, **undo/redo** (command stack over the already-immutable
`EditTree`), categorized + searchable palette, modifier editing, drag handles
and drop indicators done properly, keyboard shortcuts, always-visible save
state. The bindings panel (fields/actions UI) arrives with Phase 3.

## Section 6: iOS peer

`portal-device-ios` mirrors the Android host 1:1 — keliver Treehouse on iOS is
already runtime-proven (sample app, iPhone 16 Pro sim), including the signed
manifest path. Same `TreehouseAppFactory` + `KeliverMaterialHostProtocol.Factory`
+ `TreehouseContent(ComposeUiKeliverMaterialWidgetSystem)` in a
`MainViewController`; a darwin `HostApi` impl for the dev draft poll; both dev
and prod modes verified on the simulator. The guest needs **zero** iOS work —
the same JS bundle serves both platforms.

## Section 7: Projects + persistence

`portal-server` owns `portal.db` (SQLite): **projects → screens** (tree JSON +
contract) → **drafts vs published snapshots**, plus the bundle store (files on
disk, metadata rows). The editor gains a project/screen switcher; every edit
autosaves the draft (debounced POST — today's `applyTree` push, kept).
Refresh-loses-everything dies here. All state sits behind one server
interface, so a later move to Postgres/S3 is an implementation swap.

## Section 8: Error handling & testing

**Error handling**
- Editor: typed catalog makes malformed trees unconstructible; deserialize
  failure on load → toast + keep last good tree; server unreachable →
  visible "offline, edits local" badge, edits queued in memory.
- Device dev mode: unknown widget type → placeholder box showing the type
  name (schema drift visible, never a crash); poll failure → keep last tree.
- Publish: no bundle becomes `latest` without signing + headless load test.

**Testing**
- `portal-core`: pure-Kotlin unit tests (EditTree ops, serialize round-trip,
  exporter golden files).
- Codegen: golden-file tests (schema in → expected catalog/interpreter/
  exporter out) + CI staleness check.
- Export→compile: extend today's guest-compiler verifier to compile a
  bindings screen against a mock impl — the executable proof of the contract.
- Publish: integration test — tree in → signed bundle out → manifest verifies.
- Device hosts: screenshot verification stays the manual gate per milestone.

## Section 9: Phasing

Each phase independently shippable; the end-to-end demo never breaks.

| Phase | Delivers | SOTA box ticked |
|---|---|---|
| **P1 Codegen engine** | `portal-schema-codegen`, generated catalog/interpreter/exporter, `portal-render` shared module (kills the copies), ~60 widgets + modifiers everywhere | Schema as single source of truth |
| **P2 Editor redesign + persistence** | Kotlin-DOM design system, undo/redo, categorized palette, modifier panel; projects/screens/autosave (`portal-server` + SQLite) | Professional authoring UX |
| **P3 Bindings + contract codegen** | Binding props/actions in the tree, editor bindings panel, generated `Bindings` interfaces, mock-preview action console | Round-trip; real screens |
| **P4 Publish pipeline** | Export→compile→sign→versioned bundle store, compat gating, prod device mode (Android) | Ship OTA safely |
| **P5 iOS peer** | iOS host, dev + prod modes verified on simulator | Full platform parity |

P4 precedes P5 because signing/versioning is the platform's credibility core
and iOS reuses it wholesale.

## Out of scope (explicit)

Review/approval workflows, staged rollout + percentage rollouts, shareable
preview environments, an expression language (conditions/loops in the portal),
Kotlin→tree parsing (the contract interface removes the need), cloud
deployment, multi-user auth/collaboration.
