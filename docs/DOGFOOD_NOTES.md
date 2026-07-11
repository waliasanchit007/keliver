# Dogfood: Field Notes — frictions & wins

> **Status update (Phase 2, 2026-07-11):** frictions **#1 and #2 are FIXED** and
> device-verified against bundle v8 — see "Phase 2 resolution" at the bottom.

The first real app built under the **"no escape hatches"** rule: every UI node in
`portal-app-lib/src/jsMain/kotlin/screens/feed.kt` is portal-recognized (verified
by `RecognizerTest.recognizesDogfoodFeedScreenWithNoRawCode` — zero `RawCode`).
It renders natively on Android from a **published, Ed25519-signed** bundle (v7),
persists across restarts via the OTA SQLite data layer, and gates on the
`HostSqlDriver@1` capability. Screens/logic:

- `screens/feed.kt` — `FeedScreen` (StyledBox → ScrollableColumn → title, count,
  Add-note, `Condition` empty-state, `Repeat` feed with per-item binds, Clear all).
- `logic/FeedPresenter.kt` + `logic/NotesStore.kt` — hand-owned presenter + typed
  SQLite queries returning `List<Note>`.

## What worked cleanly (no friction)

- **P1-B per-item Repeat binding** — `note.title/body/time` rendered real SQLite
  rows natively; the headline feature carried the whole app.
- **M5 Condition** — the empty-state is a plain `if (b.isEmpty) { … }`, recognized
  and rendered.
- **M7 data layer** — arbitrary typed queries (`SELECT … List<Note>`, `COUNT(*)`,
  `DELETE`) shipped OTA in the signed bundle; survived a force-stop + relaunch.
- **M8 capability gating** — v7 (requires `HostSqlDriver@1`) is served only to
  SQL-capable hosts; `latest?caps=` without SQL falls back to an older bundle.
- **P4 publish → sign → verify** — real Ed25519 verification on device (the host
  embedded the relay's pubkey; not the `NO_SIGNATURE_CHECKS` fallback).
- **Full ingest round-trip** — the screen ingests with zero `RawCode`; the
  contract the recognizer extracts is exactly what the presenter implements.

## Frictions found (ranked — the Phase 2 backlog)

### 1. Text input can't be portal-authored (highest impact)
Parameterized events like `onValueChange = { b.onDraftChange(it) }` aren't in the
recognizer grammar — it only accepts **zero-arg** actions (`{ b.action() }`,
`b::action`). And an unrecognized prop value turns the **entire widget** into
`RawCode` (`Recognizer.kt:88`), so a `TextField` wired to capture typed text drops
out of the portal completely. This forced "Add note" to **auto-generate** notes
instead of letting the user type them — a notes app that can't take text input.

**Fix:** teach the recognizer/exporter single-arg action lambdas. Add a
`PropValue.Action` that carries the parameter name (`onValueChange` → `{ b.onX(it) }`),
recognize `{ b.method(it) }`, and emit it back verbatim. This unblocks every
form/input screen.

### 2. No navigation / multi-screen routing primitive
The catalog has `NavigationBar`/`NavigationRail` as *visual* widgets, but there's
no route/backstack concept and `PublishedEntry` renders exactly one screen. A
list→detail app must fake it with a `Condition` + selection state — and per-item
selection needs an item-carrying action, which is blocked by #1.

**Fix:** a `Navigator` primitive (a screen stack the host owns) plus per-item
action arguments so a row can navigate with its own id.

### 3. Per-item Repeat preview is a single static template row
In the browser/overlay preview, `Condition` and `Repeat` render their children
**once** (`GeneratedRenderNode.kt:95`) against `PreviewBindings` mocks, so per-item
binds show literally as `{note.title}`, not real rows. Only the compiled device
path runs the real `forEach`. Preview fidelity for lists is therefore structural,
not data-driven.

**Fix:** a preview mock **list** for the Repeat item scope so the interpreter can
render N sample rows with mocked `note.*` fields. (Previously filed follow-up.)

### 4. The portal is blind to item interfaces
`interface Note { … }` lives beside the screen, but the recognizer's `Contract`
only reads the `*Bindings` interface (fields + actions). The portal can compile
the app (M6 canonical) but can't surface or edit the per-item shape.

**Fix:** recognize sibling item interfaces into the contract so the portal can
show/edit `Note.title/body/time` the way it shows screen bindings.

## Bottom line
The compiled+signed+persisted+gated pipeline is solid and the per-item list
feature works on-device. The gap between "renders a feed" and "a person can
actually use it" is **input** (#1) and **navigation** (#2) — those two are the
right Phase 2 headline, discovered by building rather than guessing.

---

## Phase 2 resolution (2026-07-11, bundle v8)

**#1 FIXED — single-arg action events.** `PropValue.Action` gained `arg`
(`"it"` = event payload, `"item.field"` = item-scoped data). The recognizer
parses `{ b.onDraftChange(it) }` and `{ b.openNote(note.id) }`; the generated
exporter emits them back byte-identically, generates typed signatures
(`fun onDraftChange(value: String)` — param type from the generated
`eventParamType` map, extracted from the schema FIR), and item-scoped action
args feed the item interface (`Note.id`). Device-proof: typed into the
portal-authored `TextField` on the Pixel_9 prod host (signed v8), the typed
title became a card, the field cleared.

**#2 FIXED (minimal-by-design) — list→detail navigation.** No new portal
primitive: the portal authors screens (`feed.kt`, `detail.kt`), the app owns
how they connect — `PublishedEntry` keeps a hand-owned route state and the
item-carrying `openNote(note.id)` drives it. Device-proof: tapped a card →
the detail screen loaded that row by id → "< Back" returned; the note
survived a force-stop + relaunch. A formal Navigator primitive stays deferred
until dogfooding demands one (deep links, tabs, state restoration).

**#4 PARTIALLY FIXED** — item interfaces now include action-arg fields
(`id`), and `Contract.actionParams` carries typed action signatures both
directions. The portal still doesn't *edit* item interfaces.

**#3 still open** — per-item Repeat preview remains a single template row in
the browser/overlay interpreter; the compiled device path runs the real
`forEach`. Fix = a preview mock list for the Repeat item scope.

Grammar note for app authors: events accept exactly three shapes —
`{ b.action() }`, `{ b.action(it) }`, and `{ b.action(item.field) }` (inside
that item's `Repeat`). Anything else makes the widget `RawCode` on purpose.
