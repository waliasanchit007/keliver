# Keliver Portal V2 — Bidirectional Editing + End-to-End App Platform

**Date:** 2026-07-04
**Status:** Approved (brainstormed + user-validated)
**Supersedes the authoring model of:** `2026-07-03-keliver-portal-sota-design.md`
(V1's publish/signing/versioning pipeline and runtime interchange carry over
unchanged; V1's "tree as source of truth" and one-way generation are replaced.)

## Vision

One app, one git repo, editable from the visual portal, any code editor, and
AI agents — all live on web, Android, and iOS while editing, and shipped as
compiled, Ed25519-signed Kotlin. Neither the portal nor Kotlin "owns" a
screen: both edit the same semantic model, and the runtime never knows where
an edit originated.

## Locked decisions

| Axis | Decision |
|---|---|
| Durable source of truth | **The Kotlin file in git.** The UI Document is a live, in-memory session model derived from it and continuously checkpointed back into it. One durable store; git-native history/review/merge. |
| Sync primary path | **Headless ingest in portal-server** (file-watch → Kotlin PSI parse → diff-by-identity → ops). Works for every editor and every AI agent with zero integration. The IntelliJ plugin is a later UX enhancer, not load-bearing. |
| Widget identity | **Implicit structural addresses** (`parent/slot/Type[i]`) by default; explicit human-named `id("buy-button")` modifier only where needed (ambiguity, binding targets, refactor anchors). Ops target **internal node handles**; identity strings exist only at the file boundary. |
| Concurrency | **One human + their tools** (portal session, IDE, AI agent live simultaneously). Op log + document version + rebase-on-external-change. No OT/CRDT; op schema designed so it can be added later. |
| Conflict policy | File wins on true same-prop collisions inside the write-back debounce window; portal shows a rebase notice; undo stack invalidates for touched nodes only. |
| Agents | **First-class authors** via two entry styles: file edits (ingest path) and a `portal-mcp` op surface with catalog-grounded validation. |
| Logic split | **Maximal guest:** repositories, persistence logic, networking, business rules, presenters — all guest Kotlin, shipped OTA. Host = dumb generic drivers (HTTP, SQL) + genuinely native capabilities (auth, push, sensors) as typed Zipline services. |
| Local data | **Guest-owned data layer over a generic `HostSqlDriver`** (keliver-http pattern applied to SQL). Schema/queries/migrations ship in the bundle. Room/SQLite is a host implementation detail. (Room KMP cannot run inside QuickJS — no JS target, no filesystem; this split is physics.) |
| Preview | **Progressive fidelity:** Mock (instant, default) → Live Presenter (real logic compiled to wasm, early milestone) → Connected Device (real hardware). |
| Dev devices | **Overlay Model:** compiled guest is always primary; visual edits render through a temporary interpreter overlay (~1 s) on the active screen; continuous debounced compilation hot-swaps the real bundle (3–10 s) and the overlay auto-discards on versioned catch-up. |
| Publish | Unchanged from V1 (compile → sign → versioned store → compat gating), except it now compiles **the canonical file directly** — the `generated/` projection dies. |

---

# Part I — The bidirectional document

## 1. UiDocument & DocNode

`portal-server` holds one live `UiDocument` per open screen: screen name,
contract (fields/actions), a `DocNode` tree, a monotonic `version`, an op log,
and per-node **source spans** (text ranges in the last-ingested file).

`DocNode` variants:
- **Widget** — catalog type, props (`Literal | Bind(field) | Action(name)`),
  modifiers, children, optional explicit id.
- **RawCode** — a verbatim source span the recognizer didn't understand.
  Byte-preserved through every round trip. Rendered as a labeled placeholder
  box in interpreter surfaces; compiles and runs for real in compiled
  surfaces. **Code is never dropped — invariant #1.**

**Identity.** Every node has an *internal handle* (stable for the document's
lifetime — all ops, broadcasts, and undo entries use handles, never
addresses, because structural addresses shift on sibling insert). At the file
boundary, identity = explicit `id("...")` when present, else the structural
address, else similarity matching (type + prop overlap) to recognize moves.

The V1 `WidgetNode` tree remains the **runtime interchange**: the Document
projects onto it (RawCode → placeholder node), so `portal-render`, the wasm
preview, and device dev-guests are unchanged.

## 2. Operations & sync engine

Op vocabulary (one pipeline for every editor):
`InsertNode, DeleteNode, MoveNode, SetProp, RemoveProp, SetModifier,
RemoveModifier, RenameId, ReplaceRaw, ContractEdit`.

Sessions submit ops with the version they built on; the server validates
against the generated catalog, applies atomically, bumps the version, and
broadcasts deltas (SSE) to subscribers. Undo/redo = server-side inverse ops
per session. Batches are transactions: all-or-nothing, one version bump, one
undo entry.

**External-edit rebase:** file-watch → re-ingest → diff against the
last-written-back snapshot → synthetic ops replayed by identity. True
conflicts: file wins + portal notice.

## 3. Ingest (Kotlin → Document)

Headless recognizer inside portal-server using the embeddable Kotlin PSI
parser (headless in-process compiler use is already proven by
`portal-schema-codegen`'s FIR parsing). Walks
`@Composable fun XScreen(b: XBindings)`: catalog-known call expressions →
Widgets (named args → props; `b.field` → Bind; `b::action` / `{ b.action() }`
→ Action; `Modifier.x().y()` chains → modifiers; trailing lambda → children);
the sibling `XBindings` interface → contract. Everything else → RawCode.
No name resolution needed — widget names come from the generated catalog.

## 4. Write-back (Document → Kotlin)

Span-surgical and debounced (~400 ms): only dirty widget-call expressions are
regenerated (canonical formatting *inside* a call; file formatting, comments,
imports (add-only managed block), and RawCode untouched), spliced at recorded
spans with offset shifting. Atomic temp+rename. Stale spans → full re-ingest,
rebase, retry. If spans can't be resolved safely the writer refuses and
surfaces the problem — it never guesses.

**Bindings interface = managed block:** write-back updates/removes only
members the Document owns (referenced by binds/actions); hand-added members
are preserved verbatim (RawCode-equivalent treatment).

## 5. The Portal Compose subset

Exactly the schema-codegen grammar: 60+ widgets, 19+ modifiers, literal prop
kinds, binds, actions, children lambdas — plus extracted local `@Composable`
helper functions that themselves conform (document fragments). Everything
else is RawCode. `RawCode` is explicitly a sanctioned escape hatch: inline
logic in a screen file is portal-invisible but prod-real. (Policy evolution
from V1's strict interface-only boundary — deliberate.)

## 6. Agent-native surface (`portal-mcp`)

For human editors the UI constrains input; **for agents the op validator is
the UI** — rejections must be precise and steering
("Slider has no prop 'label'; closest: 'position'").

| Tool | Purpose |
|---|---|
| `get_catalog` | Generated widget/modifier/prop schema as JSON — grounds generation, kills hallucinated APIs. |
| `get_document(screen)` | Semantic tree with readable identity paths, contract, binding state. |
| `apply_ops(baseVersion, ops[])` | Atomic validated transaction; structured errors. |
| `validate` / `publish_screen` | Compile + contract failures as structured agent-readable feedback. |
| `screenshot(surface)` | Wasm preview or connected dev device — visual verification. |
| `get_action_log` / `get_state` | Event firings + current bindings values — behavioral verification. |
| Resource: usage guide + subset grammar | Agent self-orientation. |

Every agent edit is an attributed op in the log; every publish traces to a
document version — the audit story strengthens as human keystrokes disappear.

---

# Part II — The end-to-end app platform

## 7. App project model

A portal project **is a real Gradle guest project in git**:

```
myapp/
├── screens/    portal-editable canonical .kt (subset + RawCode)  ← Document syncs here
├── logic/      presenters, repos, @KeliverApi interfaces, @Serializable models (never portal-parsed)
├── contract/   Bindings interfaces (managed blocks)
└── host-sdk/   typed Zipline service interfaces the native shells implement
```

Version control = git, natively: review real Kotlin, branch, blame, revert.
(Branch-per-workspace portal UX and PR-preview environments: V3 roadmap.)

## 8. Logic architecture (maximal guest)

`Repo → @Composable Presenter(deps): XBindings → XScreen(b)` — the Bindings
interface **is** the Model+events of keliver's proven Style B UDF; the
presenter produces it. Guest-owned and OTA-shipped: presenters, repositories,
HTTP (`keliver-http` / `@KeliverApi` codegen), caching, business rules, and
the **data layer**:

- **`HostSqlDriver`** (new keliver service, keliver-http's shape):
  `execute(sql, args) → Rows` — one dumb host driver per platform (real
  SQLite on Android/iOS; sql.js/in-memory in the browser). Single-object
  serializable returns (the U1 `List<@Serializable>` bind-hang is dodged the
  same way keliver-http does).
- Guest owns schema, typed queries/DAOs, and migrations as pure Kotlin —
  **new tables and queries ship OTA in the signed bundle.**

Host-owned: only what can't be shared — auth/tokens, push, sensors, platform
UI hooks — as versioned typed services in `host-sdk/`.

## 9. Progressive-fidelity preview

| Tier | Runs | Latency | Default for |
|---|---|---|---|
| **Mock** | Interpreter + mock bindings + action console + state inspector | instant | designers, visual edits |
| **Live Presenter** | Real presenters/repos compiled to wasm; browser-fetch HTTP; sql.js-backed HostSqlDriver | compile-on-idle (seconds) | developers & agents verifying behavior |
| **Connected Device** | Real Android/iOS via the dev runtime | live | final verification |

Live Presenter is first-class: it lands the moment its dependencies exist
(M8, directly after the app project model M6 and the browser SQL driver M7),
not as a distant stretch goal.

## 10. Dev runtime — the Overlay Model

The **compiled guest is always primary** on dev devices. Visual edits render
immediately through a temporary interpreter overlay on the active screen
(~1 s, today's draft poll). In parallel, canonical Kotlin is continuously
compiled (debounced/coalesced, ~2 s idle) and hot-swapped via the existing
`serveDevelopmentZipline --continuous` push (3–10 s, no reinstall — already
runtime-verified on Android and iOS). **Versioned catch-up:** each bundle
embeds the document version it was compiled from; the device discards the
overlay only when `bundleVersion ≥ overlayVersion`. Logic edits bypass the
overlay entirely. One continuous loop, no mode switching. Built in two steps:
dev-router hybrid first, versioned auto-catch-up second.

## 11. Publish & prod (unchanged, simplified)

Publish compiles **the canonical project** (screens + logic + contract) →
`.zipline` → Ed25519 sign → versioned bundle store → compat gating → prod
hosts verify. The V1 `generated/` projection module is retired. Prod
rendering is native Compose widgets on the host; only presenter-shaped calls
cross the QuickJS boundary (the production Cash App model) — "native speed"
is a property of the architecture, not an aspiration.

---

## Milestones

Each is independently shippable; V1 keeps working throughout. Execution =
one implementation plan per milestone (spec → plan → build → verify, as in
V1), so this spec is the map, not a single plan's scope.

| # | Milestone | Delivers |
|---|---|---|
| M1 | Document + ops engine | Handles/identity, op log, versioning, SSE, server-side undo; portal UI emits ops; dual-written `.kt` labeled "generated until M4" |
| M2 | `portal-mcp` | Agent surface over the op engine (catalog-grounded validation, transactions, screenshots) |
| M3 | Ingest | Headless PSI recognizer + file-watch; any editor/agent edit goes live everywhere; `.kt` becomes truth |
| M4 | Surgical write-back | Span edits, managed imports/interface blocks, rebase; header marker removed — full bidirectionality |
| M5 | RawCode + fragments | Islands end-to-end; extracted conforming composables |
| M6 | App project model | screens/logic/contract/host-sdk layout; publish compiles the canonical project; presenter-Bindings unification |
| M7 | Data layer | `HostSqlDriver` service (Android/iOS/browser impls) + guest data-layer pattern + OTA schema/query proof |
| M8 | Live Presenter preview | Wasm compile-on-idle of project logic; three-tier preview complete |
| M9 | Overlay dev runtime | Dev router + continuous compile + versioned catch-up on Android & iOS |
| M10 | IntelliJ plugin (optional) | Keystroke sync, id gutter, unsupported-construct highlighting |

## Testing

- **Round-trip corpus:** `.kt` → Document → write-back → byte-identical
  outside touched spans; property test: random op sequences → write-back →
  re-ingest → Document equality.
- **Recognizer fuzz:** unknown constructs always land in RawCode; code is
  never lost.
- **Op validator:** table-driven cases incl. agent-error-message quality.
- **Data layer:** same guest repo test suite green against sql.js (browser),
  Android SQLite, iOS SQLite drivers.
- **Overlay catch-up:** stale-bundle race test (edit during compile window).
- V1 gates (kitchen sink, staleness, publish, tamper) remain.

## Out of scope (V3 seams, named deliberately)

Real-time multi-user editing (OT/CRDT on the op log), branch-per-workspace
portal UX + PR preview environments, declarative data sources (bind a field
to a keliver-http endpoint from the portal UI), design tokens/theming system,
component marketplace.
