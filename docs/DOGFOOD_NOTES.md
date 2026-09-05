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

**#3 FIXED (2026-07-11 slice 2)** — the interpreter now renders **N mock rows**
per Repeat: the items field's mock is the row count (default 3), item-field
mocks hold `|`-separated per-row values (clamped; unmocked shows `{note.title}
N`), and a Condition mocked `false` hides its branch. Verified: unit tests
(`resolveItemRow`) + real pixels via the device dev-overlay (3 numbered Card
rows on the Pixel_9). The compiled path still runs the real `forEach` — mocks
are preview-only.

Grammar note for app authors: events accept exactly three shapes —
`{ b.action() }`, `{ b.action(it) }`, and `{ b.action(item.field) }` (inside
that item's `Repeat`). Anything else makes the widget `RawCode` on purpose.

---

# Dogfood 2: the zero-checkout adopter path (2026-09-05)

The first run of the adopter path **as an adopter actually experiences it**:
the published `keliver-portal-tools-0.3.1.zip` from the GitHub release, no
Keliver checkout, artifacts resolved from Maven Central only, and a fresh
`GRADLE_USER_HOME` so nothing came from a warm cache.

This mattered because the path had never been verified this way. The one
recorded verification (*Post-snapshot: editor distribution productization*,
2026-07-24) used **locally published `0.3.1-SNAPSHOT` artifacts** inside
`stashfin-sdui` — a project that no longer exists. Central was never the
resolution source, and `mavenLocal` on this machine already held
`dev.keliver:*:0.3.1` from a CI run, so a careless test would have passed
against local artifacts and proven nothing. Verified first that
`keliver-init`'s generated `settings.gradle` contains **no** `mavenLocal()`
— it is injected only under `KELIVER_USE_MAVEN_LOCAL=1`.

## What worked with zero intervention

- `keliver-init Acme` — scaffolded first try.
- Cold `./gradlew compileKotlinJs` against Central — **BUILD SUCCESSFUL in
  1m 11s**, including the Gradle distribution download.
- `keliver-new-editor.sh Acme <logicDir>` — scaffolded first try.

**The core claim holds: an adopter can go from a released zip to compiling
Kotlin screens against Maven Central with no Keliver checkout.**

## Friction 1 — the two scaffolders disagree about where contracts live (FIXED)

`keliver-new-editor.sh Acme src/jsMain/kotlin/logic` produced an editor that
could not compile:

```
e: HomePresenter.kt:4:13 Unresolved reference 'screens'.
e: HomePresenter.kt:9:22 Unresolved reference 'HomeScreenBindings'.
e: HomePresenter.kt:10:3 'subtitle' overrides nothing.
```

Cause: a presenter's return type is its screen's `Bindings` interface, and
`keliver-init` declares that interface in `screens/home.kt` — next to the
widget-using composable. The editor scaffolder wired only `logic/` as a
`kotlin.srcDir`, so `acme.screens.*` was off the compile path. Adding
`screens/` alone is still not enough: those files import
`dev.keliver.material.compose.*` / `layout.compose.*`, which `portal-editor`
does not bring in.

So two scaffolders shipped in the same bundle produced a combination that
does not build. This is the kind of defect that only appears when someone
runs the whole path end to end rather than each half in isolation.

**Fixed in `scripts/keliver-new-editor.sh`:** it now takes any number of
source dirs, auto-adds a sibling `screens/` when a `logic/` dir is passed
(announcing it), and emits the `keliver-material-compose` /
`keliver-layout-compose` dependencies whenever app source is compiled in.
Verified by deleting `editor/` and regenerating: the scaffolded project
builds **unmodified** and produces a real 15 MB Wasm distribution.

## Friction 2 — Kotlin/Wasm behind a TLS-inspecting proxy reports a lie

On a corporate network (Netskope here), the editor build fails at
`:kotlinWasmNpmInstall` with:

```
error Couldn't find package "@js-joda/core@3.2.0" ... on the "npm" registry.
error Couldn't find package "format-util@^1.0.5" ... on the "npm" registry.
```

Those packages exist. The real error is `SELF_SIGNED_CERT_IN_CHAIN` — yarn
reports a TLS rejection as a missing package. `NODE_EXTRA_CA_CERTS` pointed
at the **full chain** (not just the leaf CA) fixes it. Any adopter on a
corporate network hits this, and the message sends them hunting a dependency
problem that does not exist. Documented in `CI_RUNNER_SETUP.md`.

Not a Keliver defect, but it is squarely in the adopter's path, so it is
Keliver's problem to warn about.

## Intervention count

Two, both real: one framework defect (friction 1), one environment trap the
docs did not cover (friction 2). Neither would have been caught by testing
`keliver-init` and `keliver-new-editor.sh` separately.

**Caveat on this number.** I am not a fair proxy for an outside developer — I
already knew the codebase and set the proxy CA reflexively before the first
build. A real M2 participant would have stalled on friction 2 with no idea
why. Treat two as a floor.

## Not yet covered

The path was exercised as far as *a built editor distribution*. Still
unverified from a pure adopter position: running `keliver-portal` against the
app, editing a screen in the browser, and the surgical write-back round-trip
to `.kt`. Note also that the published `keliver-portal-tools-0.3.1.zip` was
built from `c1e9546`, so it ships the **old** launcher — without the
health-wait, `stop`/`status`, or the busy-spin fix. Adopters get those only
in the next release.

---

# Dogfood 3: the portal loop, from an adopter's position (2026-09-05)

Continues Dogfood 2 past "a built editor distribution" into the part the
product thesis actually rests on: run the portal against a scaffolded app,
edit a screen, and confirm the write-back round-trip. Run with the shipped
`keliver-portal-tools-0.3.2` bundle from the GitHub release. (Artifacts came
from mavenLocal because the 0.3.2 Central publish was still in preflight;
Dogfood 2 already proved Central resolution, and this run is about the loop.)

## The headline: the round-trip works, and it is genuinely surgical

`POST /ops` with a single `SetProp` on the title's literal produced exactly
this diff in `screens/home.kt`:

```diff
-      text = "Loopy",
+      text = "Loopy Renamed",
```

One line. Comments, imports, formatting, and the hand-owned
`HomeScreenBindings` interface all untouched. Then editing the `.kt` by hand
(`fontSize = 28` → `32`) was ingested back into the document within seconds,
at version 3, with the earlier API-driven change preserved.

**This is the first time the bidirectional loop has been verified from
outside the Keliver repo.** It is the strongest evidence the project has.

The semantic tree also delivers what the M4 thesis needs. The document
distinguishes, in queryable form:

```
StyledText handle=2  text -> PropValue.Lit  "Loopy"
StyledText handle=3  text -> PropValue.Bind field="subtitle"
```

Literal versus bound, visible to an agent and invisible in a screenshot —
falsification case F2 is directly observable in the shipped artifact.

## Five first-run defects, all M2-blocking

Every one of these is what a recruited developer sees in their first minute.

### 1. The portal opens on an empty screen

`ensureDefaults()` (`Relay.kt:97`) unconditionally sets the active screen to
`main` on first boot, while `keliver-init` scaffolds `home.kt`. The adopter's
first view is a blank canvas with a bare `Column`, their real screen
unselected, and the screen dropdown showing no selection. Their work is
present and reachable — but nothing says so.

### 2. The preview build is guaranteed to fail

`previewBuildTask` / `previewDist` default to `:web-spike:…`, which is
**Keliver's own dogfood module**. `keliver-init` writes only `screensDir` and
`port`, so every adopter inherits those defaults and the relay reports:

```
Cannot locate tasks that match ':web-spike:wasmJsBrowserDistribution'
as project 'web-spike' not found in root project 'loopy'.
```

A red "✗ preview build failed" chip sits in the toolbar from the first
second. The keys are documented in `PORTAL_USAGE.md`; the scaffolder just
never writes them.

### 3. The cache-bust is hardcoded to Keliver's own artifact name

`PreviewDistributionRunner.kt:47` stamps `?v=<millis>` onto `web-spike.js`.
`keliver-new-editor.sh` generates `<app>-editor.js`. So for every adopter who
*does* configure their own editor, the cache-bust silently matches nothing —
reviving the CACHE TRAP that `CLAUDE.md` documents at length ("your fix
appears to 'not work'"), permanently, and only for consumers.

This is the sharpest example of the pattern in this repo: the fix was made
and verified against Keliver's own module, and is structurally inapplicable
to everyone else.

### 4. `/projects` lists the relay's internal state as projects

`Relay.kt:488` returns every directory under the relay root:

```
["bundles","default","keys","kotlin"]
```

`bundles/`, `keys/` and `kotlin/` are relay storage, not projects — and
`keys/` holds `ed25519.priv`, the project signing key. They appear in the
adopter's project dropdown as selectable, and selecting one would create
screen JSON inside the key directory.

### 5. `appRuntime` is never scaffolded

`keliver-init` omits it, so the editor shows "App runtime not declared …
version match is unknown" on first run. Cosmetic next to the others, but it
lands in the same first impression.

## Also noted

`POST /active` reads query params and silently defaults a missing `screen` to
`main`, so a wrong-shaped call returns `204` while setting the opposite of
what was asked. `GET /doc` likewise defaults to `main` from its own query
param and ignores the active screen entirely — the two endpoints are
unrelated despite appearing to be about the same thing.

## Verdict

The engine is sound and the round-trip is real. What is broken is everything
between an adopter and that engine: they land on a blank screen, with a red
failure chip, and a project dropdown offering the signing-key directory.
None of it touches the runtime; all of it is first-run wiring, and all of it
is invisible from inside the Keliver repo, where `web-spike` exists, `main`
is the real screen, and the dogfood app is the only consumer.

**Fix 1, 2 and 4 before putting a person on M2.** 3 matters as soon as they
scaffold their own editor.
