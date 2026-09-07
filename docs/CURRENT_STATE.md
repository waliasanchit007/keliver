# Keliver current state

**Base snapshot date:** 2026-07-23
**Last updated:** 2026-09-05 (see the `Post-snapshot:` sections)
**Repository baseline:** `94519abf5` (`#13` flow preview complete and live-verified)

This document is the factual handoff point before the next implementation
milestone. It describes what is present and what has been recorded as verified;
it does not replace the architectural decisions in `DECISIONS.md` or the two
forward-looking roadmaps.

> **How to read this document.** Everything above the first `## Post-snapshot`
> heading is the 2026-07-23 base snapshot. **Ten `Post-snapshot:` sections
> follow it, running through 2026-07-27, and several of them close items the
> base snapshot lists as open.** Reading only the base snapshot will overstate
> what is outstanding — in particular all six live-preview issues and three of
> the six product gaps below are already resolved. Where that happens
> the base section now carries a status banner; trust the banner and the dated
> section it points to.

## Product state

Keliver is a public, pre-1.0 Compose Multiplatform SDUI framework. The current
published line is `dev.keliver:*:0.3.1` on Maven Central. Guest UI is compiled
Kotlin/JS executed by Zipline and rendered as native Compose widgets on Android
and iOS. Production bundles are versioned, Ed25519-signed, widget/capability
gated, and verified by hosts before execution.

The visual portal is bidirectional:

- Kotlin files in the consumer repository are canonical.
- The portal recognizes a deliberately small UI grammar into `UiDocument`.
- Browser, IDE, and MCP edits converge on the same document.
- Portal edits write back surgically; untouched source remains byte-stable.
- Unsupported Kotlin is preserved as explicit `RawCode` rather than silently
  misinterpreted.
- Development devices use a live interpreter overlay while canonical Kotlin
  recompiles; production always executes compiled code.

The reference adoption **was** `stashfin-sdui`, which recorded Android, iOS, and
web verification against a real application integration. It was lost with the
2026-09-05 machine failure and never pushed; **there is currently no external
adopter.** Its recorded gates stand as evidence that the integration worked, but
they cannot be re-run — see *Post-snapshot: machine loss and restart*.

## Delivered portal capabilities

### Bidirectional authoring and delivery

- Semantic document and operation engine with stable handles, transactions,
  validation, SSE updates, and per-session undo/redo.
- PSI recognition and reconciliation of Kotlin screen files.
- Byte-minimal surgical write-back with package and contract preservation.
- Managed `TODO(portal)` contract members for draft bindings, plus publish-time
  rejection while those markers remain.
- Boot scan, native file watching, project configuration discovery, stale-store
  reconciliation, and actionable unknown-widget diagnostics.
- Compile, sign, store, compatibility-gate, and serve production bundles.
- MCP operations over the same validated document engine.

### Widget and grammar surface

- Approximately 76 Material/layout widgets in the schema and approximately 64
  exposed through the portal catalog.
- Universal visual modifiers, curated Material icon vocabulary, Condition,
  Repeat, scalar/list props, literal/bound props, and zero/single-argument
  events.
- Field-aware Repeat mock rows and item-scoped binds/actions.
- Canvas click-to-select with selection/interact modes.

### Project components

Project Components C1-C4 and Components v2 single-slot support are delivered:

- App-owned composables live under a configurable `componentsDir`.
- Component signatures derive portal prop/event specifications.
- Definitions use the existing grammar and expand transparently in preview;
  unsupported definitions remain opaque with explicit placeholders.
- Screens recognize component calls as single master-linked instances.
- Component instance export, contract derivation, and surgical write-back are
  integrated with the existing pipeline.
- The editor provides a Project components palette and signature-driven prop
  panel.
- Direct/indirect component cycles are guarded.
- `keliver-new-component` scaffolds definitions.
- A single `@Composable () -> Unit` parameter defines an editable trailing
  required content slot; definitions invoke it once inside a grammar container
  at the expansion point. Optional/defaulted slots remain opaque.
- Slot children retain their own outline/canvas handles while private wrapper
  internals select the component instance.
- Palette insertion, drag/drop, export, empty-slot emission, and surgical
  child write-back support slotted components.
- `keliver-new-component --slot` scaffolds container definitions.
- Stashfin adopted `MenuRow`, `SectionHeader`, and `SectionCard`; Profile's five
  repeated white-card wrappers are now one slotted design-system component.

Multiple named slots, detach-instance, and open-definition interactions remain
later work.

### Real-presenter preview

- `AppPreviewEntry` is the explicit per-application seam that compiles the
  consumer's real presenters into its wasm editor executable.
- `portal-editor` is a reusable shell; `web-spike` is Keliver's thin dogfood
  executable.
- SQL-backed presenters run against the generic in-memory preview SQL driver.
- The relay watches logic, debounces and serializes rebuilds, rejects stale
  results, and promotes only successful editor distributions.
- Failed builds retain the last-known-good preview.
- Stashfin owned a standalone editor composite build that compiled its real
  Profile presenter without copying it. That project no longer exists (2026-09-05);
  the pattern is recorded, the build is not recoverable.

Recorded live gates include real presenter values, state-changing actions,
SQLite-backed list updates, and rendering through project components.

### Flow authoring and preview

Roadmap item #13 is complete:

- Apps declare flows with the small `flow {}` DSL.
- The relay recognizes declarations and derives navigation edges from screen
  actions.
- The editor can run a flow through real presenters while preserving
  flow-lifetime state.
- The navigation graph is visible and clickable.
- A walkthrough can start from any graph node through `PreviewEnv.flowStart`.
- The Field Notes dogfood flow was recorded live as
  `feed -> detail -> back`, preserving SQLite state.

Typed sealed-route contracts were deliberately deferred because string route
tokens plus the stack argument complete the demonstrated v1 flow loop.
Capability personas were separated into their own future milestone.

## Current module boundaries

- Runtime and protocol: `keliver-runtime`, `keliver-protocol*`,
  `keliver-treehouse*`, `keliver-widget*`.
- Widget catalog and renderers: `keliver-material*`, `keliver-layout*`,
  `keliver-lazylayout*`, `keliver-ui-basic*`.
- Document and code pipeline: `portal-core`, `portal-document`,
  `portal-ingest`, `portal-schema-codegen`.
- Relay and agent surface: `portal-relay`, `portal-mcp`.
- Preview/editor: `portal-render`, `portal-editor`, `web-spike`, and the
  generated web protocol modules.
- App-facing flow/data seams: `portal-flow`, `portal-sql`.
- Dogfood application: `portal-app-lib` plus device host modules.

> **Superseded 2026-07-24 / 2026-07-27.** The paragraph below described the
> 2026-07-23 base snapshot. `portal-editor`, `portal-core`, `portal-document`,
> `portal-render` and `portal-flow` **are now published on Maven Central at
> `0.3.1`** (verified 2026-09-05), so a consumer-owned live editor no longer
> needs a Keliver checkout or composite build.

~~`portal-editor`, `portal-core`, `portal-document`, `portal-render`,
`portal-flow`, and the web protocol modules are not currently configured as a
coherent published artifact graph. Consumer-owned live editors therefore use a
Keliver composite build. Publishing the editor is a productization milestone,
not only a version bump.~~

## Known open correctness and developer-loop issues

> **Status — ALL SIX ARE RESOLVED.** Items 1–5 were closed by the bounded
> consolidation pass recorded below in
> [Post-snapshot consolidation result — 2026-07-23](#post-snapshot-consolidation-result--2026-07-23),
> with acceptance evidence; item 6 was closed on 2026-09-05 (ROADMAP item 23).
> **The M0 "deterministic preview" milestone is complete.** The list is kept for
> provenance — read it with this banner, not on its own.

The roadmap recorded these live-preview fast-follows as of the 2026-07-23
snapshot:

1. ~~Component-expanded bindings can miss the first live frame until a later
   recomposition.~~ **Resolved.**
2. ~~Canvas event payloads can arrive as a null argument.~~ **Resolved.**
3. ~~Presenter composition failures are not caught by the existing dispatch
   error guard.~~ **Resolved.**
4. ~~The State Inspector can lag one frame and retain values after a screen
   switch.~~ **Resolved.**
5. ~~Some consumer wasm distributions require `--rerun-tasks` before webpack
   produces a fresh output.~~ **Resolved.**
6. ~~The bundled launcher has not fully absorbed the health-wait, supervision,
   and editor auto-build behavior proven in the Stashfin development script.~~
   **Resolved 2026-09-05** (ROADMAP item 23) — re-derived from
   `scripts/keliver-dev.sh`, since the Stashfin script was lost. See
   *Post-snapshot: launcher supervision* below. **With this, all six are
   closed.**

Upstream/runtime constraints remain tracked in `KNOWN_BUGS.md`, notably silent
Zipline service-shape failures and dispatcher requirements. Most have Keliver
guards or documented workarounds; `movableContentOf` reuse remains quarantined
platform debt.

## Documentation state

The repository currently has three roadmap layers:

- `ROADMAP.md`: framework/library and adopter-facing releases.
- `docs/ROADMAP.md`: portal/application-scale development.
- `PUBLIC_LAUNCH_ROADMAP.md`: historical public-launch checklist.

They are not fully synchronized. Several documents still describe Maven
Central/public visibility as pending, label completed portal work as in
progress, or retain obsolete next-session instructions. Documentation
reconciliation is part of the next consolidation milestone.

## Evidence-backed remaining product gaps

> **Status — gaps 2, 3 and 4 are CLOSED** by the dated `Post-snapshot:` sections
> below. **Gaps 1, 5 and 6 remain open.**

1. **OPEN — Current-loop correctness and documentation consolidation.** These
   are known defects and contradictions in already-delivered behavior. The
   correctness half is done (live-preview items 1–5 above); the documentation
   half was partially addressed on 2026-09-05.
2. ~~**Editor/flow distribution productization.**~~ **Closed 2026-07-24** —
   see *Post-snapshot: editor distribution productization*.
3. ~~**Named personas and capability fixtures.**~~ **Closed 2026-07-27** — see
   *Post-snapshot: capability vocabulary and named personas*.
4. ~~**Recorded HTTP replay.**~~ **Closed 2026-07-27** — see *Post-snapshot:
   deterministic recorded HTTP replay (H1)* and *secure HTTP recording (H2)*.
5. **OPEN — Presenter/FlowScope linting.** Becomes more important as real
   presenter adoption scales.
6. **OPEN — Transparent local composables and component metadata polish.**
   Useful authoring depth, but lower priority than the gaps above.

## Next milestone decision

Before adding another authoring feature, complete a bounded consolidation and
hardening pass:

- fix the known live-preview correctness issues;
- make editor builds deterministic;
- reconcile roadmap, release, migration, and portal-usage documentation;
- re-run the web, Android, and iOS end-to-end loops.

At this checkpoint, if that pass remained green, the next major feature was single-content-slot
Project Components v2, dogfooded as Stashfin `SectionCard`.

## Post-snapshot consolidation result — 2026-07-23

The bounded pass above is complete and green. It resolved the first five
live-preview/build issues recorded in this snapshot:

- preview binding keys are present before the first presenter frame;
- canvas event payloads and concrete repeated-row action arguments reach the
  presenter;
- a failing consumer composition is isolated from the editor shell;
- the State Inspector updates on applied frames and clears across screen
  changes;
- an incomplete web distribution is rejected and rebuilt once with
  `--rerun-tasks` before it can replace the last-known-good preview.

The documentation layers were reconciled around the shipped 0.3.0 release,
the completed flow loop, and the actual editor distribution boundary. The
launcher supervision/health-work remains a separate P4 developer-experience
item.

Acceptance evidence:

- `./gradlew test apiCheck :portal-schema-codegen:checkPortalCode` — green
  (1,966 actionable tasks);
- production Wasm editor — live presenter input/state, repeated-row action
  values, `feed -> detail -> back` state retention, graph navigation, and
  walk-from-here all verified in-browser;
- Android Pixel 9 emulator — `codeLoadSuccess modules=44`, with the OTA Field
  Notes document visible and confirmed through the accessibility tree;
- iPhone 16 Pro simulator — `codeLoadSuccess modules=44`, with the same OTA
  Field Notes document visible.

Native testing also exposed an environment-level false positive: another
project can occupy port 8080 with a valid but incompatible Zipline manifest.
Device acceptance must therefore assert that the manifest `mainModuleId` is
`./portal-device-guest.js`, not merely that the port is healthy.

At the close of the hardening pass, the next major milestone remained Project
Components v2 with one content slot.
The hardening pass did not uncover evidence that should move personas, HTTP
replay, or editor artifact distribution ahead of that concrete dogfood gap.

## Components v2 result — 2026-07-23

The single-content-slot milestone is complete and green. `@Composable` lambda
parameters are distinct from events, definitions carry a reserved Slot marker,
instances own ordinary document children, preview expansion splices them at the
marker, and export/write-back reproduce the trailing lambda. Invalid multiple,
optional/defaulted, or wrapperless slots and missing/duplicated invocation sites
fail loudly as opaque components rather than changing source semantics.

Acceptance evidence:

- focused recognition, expansion, export, and empty-slot surgical write-back
  tests pass; broad API/codegen/core/document/render gates pass (1,453 actions);
- the canonical Settings dogfood compiles for JS and Wasm, exposes three
  SectionCard instances, and ingests with zero RawCode;
- Stashfin Profile compiles and ingests with zero RawCode after replacing five
  repeated wrappers with SectionCard;
- the production Stashfin editor labels SectionCard as a container, preserves
  child selection (`SectionCard #18` / `MenuRow #19`), runs the real presenter,
  and supports palette insert + undo into the slot with byte-identical source
  restoration;
- Android Pixel 9 and iPhone 16 Pro loaded the signed Stashfin guest
  (`codeLoadSuccess modules=41`) and visibly rendered the slotted cards.

This closes the design-system container gap. The next recommended milestone is
editor/flow distribution productization: publish a coherent supported artifact
graph so the already-proven consumer editor no longer depends on a Keliver
checkout. Personas/capability fixtures remain the next large application-depth
arc after that adoption-friction removal.


## Post-snapshot: editor distribution productization — 2026-07-24

Consumer-owned editors no longer require a Keliver source checkout or composite
build. Seven modules became publishable artifacts (portal-core, portal-document,
portal-render, portal-flow, portal-editor, and the renamed
keliver-material-protocol-host-web / -guest-web), each with explicit API mode, a
committed API dump, and passing binary-compatibility checks.

`portal-editor` exposes a deliberately small supported surface: `runPortalEditor`
plus the capability-preview types a per-app entry builds against
(`PreviewCapabilities`, `PreviewSqlHost`, `PreviewSqlDriver`, `CapStatus`). All
editor chrome is internal.

Verified by removing the composite build from `stashfin-sdui/editor`: it
compiles, produces a full wasm distribution, and runs in a browser against
locally published `dev.keliver:*:0.3.1-SNAPSHOT` artifacts, rendering the real
ProfilePresenter and the slotted SectionCard component.

Publication to Maven Central is intentionally NOT performed here; it is
irreversible and remains a user-triggered release step. The checklist lives in
`docs/superpowers/specs/2026-07-24-editor-distribution-design.md`.

## Post-snapshot: preview/device semantic parity gate — 2026-07-26

K1 of the UI-consistency plan is complete. Shared Kotlin fixtures now compile
as the production/device semantic path while the JVM recognizer locks those
same sources to checked-in canonical trees; Wasm Chrome tests deserialize the
goldens through `RenderNode` and compare full generated `WidgetValue` trees.

Coverage includes `Repeat` zero/multiple rows, `Condition` true/false, leaf and
slotted components, nested components, layout constraints, ordered universal
modifiers, and an integrated screen. Separate callback tests verify
zero-argument, literal, repeated-item, and callback-payload action arguments.

The first full gate found and fixed a real preview-only defect: component
expansion incorrectly inherited a slot wrapper into the definition-cycle stack,
so a finite child whose body reused that wrapper rendered a false cycle chip.
A focused unit test now pins the corrected call-site semantics.

This is a semantic tree gate, not visual platform evidence. The next bounded
milestone is K2a: document and unit-test the current sizing/layout contract
without deprecating APIs. Scripted Android/iOS/web layout evidence remains K3.

## Post-snapshot: sizing and layout contract — 2026-07-26

K2a is complete without changing or deprecating an API. The supported contract
now distinguishes container `Constraint`, Yoga/parent-scoped child layout,
ordered universal modifiers, legacy `StyledBox.fillWidth`, and semantic
`AsyncImage.fillWidth`.

Direct measurement tests exercise the real Compose/Yoga host rather than only
value trees: `Wrap` versus bounded `Fill`, `Row`/`Column` and layout `Box`,
incoming fixed/fill modifier interactions, cross-axis stretch, and
outer-to-inner size/fill/padding order. Widget tests additionally pin legacy
`StyledBox.fillWidth` and image fill-over-fixed-width precedence. The exhaustive
pre-existing Paparazzi layout matrix remains in place.

The next product-depth milestone is named personas/capability fixtures. K3
scripted Android/iOS/web layout evidence remains the next consistency-lane
milestone and a release gate; K2b API convergence remains deferred.

## Post-snapshot: capability vocabulary and named personas — 2026-07-27

The persona half of roadmap item #16 is complete. The new publishable
`keliver-capabilities` module defines versioned `HostAuth`, `HostFlags`, and
`HostAnalytics` contracts plus deterministic fixture implementations.
`AppPreviewEntry` now exposes an app-owned `PreviewPersona` catalog, and
`PreviewEnv.persona` supplies the selected persona to both screen and flow
previews.

The reusable editor shell validates the catalog, persists the selected ID,
shows a persona selector and fixture details, and treats persona-backed
capabilities as full-fidelity implementations. Switching persona while Live is
active rekeys and cold-starts the presenter or flow with a fresh app capability
graph. The Field Notes SQL driver is also keyed by persona, preventing mutable
state from crossing persona boundaries while preserving state during navigation
inside one flow.

The dogfood app declares `signed-out`, `field-researcher`, and `kyc-pending`.
Its real `SettingsPresenter` consumes the typed auth/flag/analytics fixtures:
the browser verified `Maya Chen · beta`, `Signed out`, and `Ari Patel` as the
personas changed, and verified that the live open action recorded analytics.

Mechanical and runtime evidence:

- capability, portal-render, and presenter tests passed on JS and Wasm;
- the editor and dogfood Wasm distributions compiled successfully;
- the fresh web distribution was exercised live through a dedicated relay;
- Android loaded 45 OTA modules and rendered Field Notes in a Pixel 9 emulator;
  and
- the iOS simulator framework/app build installed, launched, and rendered the
  same Field Notes guest path on an iPhone 16 Pro simulator.

Recorded HTTP/HAR replay remains deliberately separate: its next design must
settle matching, redaction, privacy, fixture lifecycle, misses, and proxy trust
boundaries. K3 scripted visual evidence remains independent consistency work;
K2b API convergence remains deferred.

## Post-snapshot: explicit runtime-version metadata — 2026-07-27

K4 of the UI-consistency plan is complete. The editor now derives its own
Keliver version from the build-generated `guestRedwoodVersion` embedded in the
running Wasm binary and uses the same centralized widget version as its
`ProtocolRedwoodComposition`.

The app/device target is not guessed from Gradle. `keliver.portal.json` owns an
explicit `appRuntime` declaration, and the relay exposes it through
`GET /runtime-metadata`. Older configs remain loadable with null metadata, but
the editor reports that state as undeclared rather than implying compatibility.

The Preview fidelity panel shows both Keliver and widget versions in mock and
Live modes. Exact match, Keliver SemVer skew, widget-protocol mismatch,
undeclared metadata, and unavailable relay metadata have distinct statuses.
Capability fidelity remains separate from runtime compatibility.

Verification evidence:

- relay tests cover config parsing, validation, absence, and the wire shape;
- six Wasm browser tests cover parsing plus match/skew/mismatch precedence;
- editor API check and a fresh dogfood development distribution passed;
- a live matching relay showed `0.3.1-SNAPSHOT`/widgets v1 on both sides in
  mock and Live modes;
- a temporary `0.3.0` relay produced the intended Keliver-skew warning without
  changing the committed app target; and
- Android loaded 45 OTA modules on Pixel 9, while Android and iPhone 16 Pro
  simulator captures both visibly rendered the Field Notes guest.

The next consistency milestone is K3's scripted, committed tri-platform layout
evidence. At K4 delivery the next major product-depth arc was recorded HTTP
replay; the following section records its bounded H1 delivery.

## Post-snapshot: deterministic recorded HTTP replay (H1) — 2026-07-27

The deterministic-replay half of roadmap item #16 is complete. A Zipline-free
`HostHttp@1` request/response contract now lives in `keliver-capabilities`;
`keliver-http` adapts the existing device-edge `HostHttpProvider` rather than
creating endpoint-specific host services. Browser preview and both native hosts
therefore bind one generic transport while endpoint and DTO ownership remains
in app Kotlin.

The relay loads app-owned fixture sets from the confined
`httpFixturesDir`, validates size, expiry, paths, headers, query/body privacy,
and matches canonical requests without any outbound-network fallback.
Duplicate requests are sequenced per bounded, expiring Live session; reusable
terminal responses support idempotent reads. `GET /http-fixtures` exposes only
catalog metadata, and `POST /http-replay` returns stable 400/404/410/413/422/424
failures rather than fabricating responses.

The Field Researcher persona selects `field-researcher.json`. Its real
`SettingsPresenter` calls `ProfileSummaryApi(HostHttp)` and the production Wasm
editor visibly rendered `Maya Chen · recorded API`. Preview fidelity reported
`HostHttp@1 — replay: field-researcher (1 exchanges)` and Full fidelity. A
controlled unmatched request returned the stable `replay_miss` 424 response;
Wasm tests pin the corresponding reduced-fidelity state.

Mechanical and runtime evidence:

- capability and HTTP JVM tests, relay tests, render/editor Wasm tests, and
  app-lib JS/Wasm tests passed;
- capability, HTTP, render, and editor API checks passed;
- the production Wasm distribution built and was exercised against a fresh
  relay;
- the Android debug APK installed on Pixel 9, loaded 45 OTA modules, and
  rendered Field Notes;
- the Kotlin iOS simulator framework linked, the SwiftUI host built and
  installed on iPhone 16 Pro, and rendered the same guest path; and
- implementation is commit `e472c9ffd`, following design commit `0ba6d0b27`.

At the H1 checkpoint, secure recording remained deliberately open. It is now
delivered by the following H2 checkpoint. K3 scripted visual evidence remains
the next independent consistency milestone.

## Post-snapshot: secure HTTP recording (H2) — 2026-07-27

Roadmap item #16 is now complete. Recording is inert by default and becomes
available only when both `PORTAL_HTTP_RECORD=1` and a non-empty reviewed
`httpRecording` config are present. The relay then rotates an owner-readable
per-process token; only loopback callers with that token and an allowed Origin
can open or use an in-memory recording session.

Callers select a server-owned upstream ID rather than supplying a URL. Each
configured target is HTTPS with a DNS hostname. The relay resolves every
address, rejects the whole result if any address is non-global, and gives the
validated immutable list to OkHttp while retaining normal TLS hostname
verification. Redirects, proxies, cookies, authenticators, and retries are
disabled. Caller auth/cookies are never forwarded; optional upstream auth comes
only from the configured environment variable.

Raw bounded text is redacted in memory before it can be returned, hashed,
logged, or written. Sensitive query and recursive JSON values become
`"<redacted>"`; sensitive and hop-by-hop headers are dropped. Replay normalizes
the corresponding runtime values, so reviewed fixtures match real
secret-bearing requests without storing the secret. Audit lines contain only
safe identifiers, a path hash, outcome/status, byte counts, and a duration
bucket.

Every successful exchange atomically rewrites a privacy-linted file under
`<httpFixturesDir>/.candidates/`. Neither the relay endpoints nor the CLI can
promote or overwrite the reviewed `<fixtureSet>.json`; a developer must inspect
and copy the candidate through a normal git change. Replay misses still never
contact an upstream.

Evidence:

- focused relay tests cover disabled/stale-token behavior, token rotation,
  loopback and Origin checks, startup auth failure, unsafe paths and addresses,
  DNS fail-closed behavior, injected auth isolation, recursive redaction,
  body-free audit, candidate validation, manual promotion, and replay with
  different runtime secrets;
- a hermetic injected transport completed record → candidate → explicit copy →
  deterministic replay without making a network call;
- a live relay on a temporary repository returned 201 for the authorized CLI,
  a clean repo-relative candidate path, 404 for wrong token and Origin, 204 for
  an allowed preflight, and 409 for a controlled empty close;
- the H1/editor/app browser, web compilation, Android APK, and iOS simulator
  framework regression matrix passed (`881 actionable tasks`);
- design is commit `c1a492c83`; implementation is `0b45f7d0e`, with live-found
  canonical-path and complete-audit fixes in `bb7d40f1d` and `736951450`.

## Post-snapshot: scripted tri-platform layout evidence (K3) — 2026-07-27

K3 of the UI-consistency plan is complete. The portal now owns one
literal-only, zero-`RawCode` `layout_evidence.kt` kitchen sink covering fill
versus wrap, row alignment, nested layout and modifier order, ellipsis versus
wrapping, fixed image geometry, and an explicit final clipping marker.

Web evidence mode uses the real editor initialization and relay tree while
hiding editor chrome. Android and iOS use the same development guest/tree
through an evidence-only overlay path that does not change normal compiled-first
behavior. The iOS host now consumes safe-drawing insets, fixing the pre-existing
case where app content rendered under the Dynamic Island.

`scripts/keliver-capture-layout-evidence.sh` builds the relay/editor/guest and
both native hosts, owns deterministic platform settings, requires guest
`codeLoadSuccess`, rejects post-load exceptions and wrong-app captures, validates
PNG structure plus four visual sentinels, records full metadata and hashes, and
restores the previous active screen. The final accepted artifacts are:

- `docs/superpowers/evidence/k3/0.3.1-SNAPSHOT/layout-evidence-web.png`;
- `docs/superpowers/evidence/k3/0.3.1-SNAPSHOT/layout-evidence-android.png`;
- `docs/superpowers/evidence/k3/0.3.1-SNAPSHOT/layout-evidence-ios.png`; and
- `docs/superpowers/evidence/k3/0.3.1-SNAPSHOT/manifest.json`.

The final run used Chrome 150 at 402×874 CSS pixels, Android API 37 on the
Pixel_9 AVD, and iOS 18.2 on iPhone 16 Pro. Human review confirmed the labeled
geometry agrees across all hosts; native font metrics and system chrome remain
intentional platform differences. K1, K2a, K3, and K4 now form a complete
semantic/layout/evidence/version consistency lane. K2b remains deferred to a
versioned API-convergence decision.

## Post-snapshot: machine loss and restart — 2026-09-05

The development machine died. The repository survived on GitHub through
`c1e9546` (`chore(release): prepare 0.3.1`, 2026-07-27); nothing unpushed
survived. This section records what that costs and what the plan is now. It
supersedes nothing in `DECISIONS.md` — D1–D15 stand.

**Lost.** `stashfin-sdui`, the reference adoption every "Stashfin" gate in the
sections above was recorded against, together with `MenuRow` / `SectionHeader` /
`SectionCard` and the standalone editor composite build. **There is currently no
external adopter.** Those gates remain valid evidence that the path worked; they
are not re-runnable. The in-repo `sample/` is the only runnable reference.

**Survived.** The GPG key and the four Maven Central secrets are GitHub repo
secrets, so CI can still publish. The Ed25519 bundle keypair regenerates in
`Relay.kt#ensureKeys()`, and no surviving host pins the old public key.

**Mined, not revived.** `github.com/waliasanchit007/ServerDrivenUI` (Caliclan,
tip `konduit-main` @ 2026-05-19) pins `1.0.0-caliclan.4-SNAPSHOT` from
mavenLocal, predates the `dev.keliver` rename, predates the portal, and its
screens predate D5 Style B. Take its docs and schema/widget definitions; do not
migrate it — that costs about as much as a fresh adopter and drags along an
architecture predating half of `DECISIONS.md`.

**Prior agent work is recoverable in-repo:** `docs/superpowers/plans/` (20
execution plans, 2026-06-10 → 2026-07-12), `docs/superpowers/specs/` (20 design
specs, → 2026-07-27), and `docs/superpowers/evidence/` (the k3 captures and the
Android/iOS gallery screenshots). Read plans in date order for how the portal
was built, specs for the reasoning, `DECISIONS.md` for settled conclusions.

### Fresh-machine bring-up, verified 2026-09-05

- `git-lfs` must be installed **before** cloning — `*.png` is LFS-tracked and a
  clone without it fails checkout partway through.
- JDK 17 is required and is usually not the default JVM; export
  `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- Cold `./gradlew :guest:compileDevelopmentExecutableKotlinJs` in `sample/`:
  **BUILD SUCCESSFUL in 6m 55s**, 43 tasks, no errors. The newcomer build path
  works on a clean machine.

### Corrected milestone sequence

Single-threaded. Each phase earns the right to start the next.

**Positioning:** *AI can write mobile code. Keliver lets it see whether the code
actually worked.* Cross-platform is enabling technology, not the headline.
**Wedge:** teams with large existing native Android + iOS apps; adopt one
feature, then five, no rewrite (this is D12). **Primary metric:** time from
intent → verified running change. **Secondary:** author-intervention count —
how many times another developer needs the framework author to ship a normal
feature. Target zero.

- **M0 — Deterministic preview. COMPLETE (2026-09-05).** Live-preview issues 1–5
  were closed by the 2026-07-23 consolidation pass — the original plan for this
  milestone assumed all six were open, which reading only the base snapshot of
  this document will suggest. Issue 6, the bundled launcher, was closed on
  2026-09-05; see *Post-snapshot: launcher supervision*.
  **Test-design constraint:** the defects in this class are *temporal* — they
  resolve on a later recomposition. A harness that acts, waits for convergence,
  then asserts agreement will pass while every one of these bugs is present,
  because it measures a steady state that was never broken. Assert on the frame
  the action lands and the frame after a screen switch, bounded by frames, not
  by a settle.
- **M1 — Newcomer path.** Version drift, the portal's absence from onboarding,
  and the stale reference-adoption pointers. Addressed 2026-09-05.
- **M2 — One developer who isn't you.** Not a team: one person in a sandbox
  building a real feature without the author touching the implementation. Run
  the intervention counter and classify every question — "how do I" = missing
  docs, "I don't understand" = bad abstraction, "this seems complicated" = DX
  problem, "I can't do" = capability gap. **M2 does not depend on M3**, and with
  M0 nearly closed it is now the cheapest remaining experiment.
- **M3 — Production survivability minimum.** Source-mapped Kotlin guest stack
  traces, crash reporting, staged rollout percentages, kill switch,
  last-known-good rollback. Not an enterprise control plane.
- **M4 — One closed AI verification loop.** Narrow: read Kotlin → read semantic
  tree → edit → render → interact → read resulting semantics → compare to
  requirement → fix → return a verified diff. **Define the falsification set
  before building it:** pick five tasks where a semantics-driven agent should
  win and a screenshot-driven one should fail — bindings that render but don't
  fire, state that looks right and isn't, a component prop silently ignored. If
  a screenshot agent gets four of five, the thesis is weaker than it looks.

### Decision gate

Not "is Keliver a company" but "has it earned the right to become one." Four
proofs: **technical** (a reasonably complex feature builds without architectural
contortions), **UX** (someone besides the author materially prefers the
workflow), **AI** (an agent performs a meaningful mobile UI task and verifies
itself better than the screenshot baseline), and **demand** (someone asks "can I
use this?" — better, "can my team?"; best, "how much?"). Two or three serious
developers using it repeatedly beats 5,000 stars. Cloud infrastructure, team,
pricing, multi-tenant and enterprise governance come only after this gate.

### Open items this plan does not yet cover

- **Distribution.** Every developer in the sequence above is hand-recruited.
  Hand-recruited people are polite and motivated; they do not produce demand
  signal. Nothing here puts Keliver in front of strangers who owe it nothing.
  The AI-verification framing is genuinely interesting to mobile engineers right
  now and writing it up is cheap — it needs an owner and a slot.
- **Stashfin IP boundary.** `stashfin-sdui` was built while employed there, so
  the question is partly retrospective rather than cleanly prospective. Worth
  real legal advice before Keliver is public-facing enough that someone else
  asks first. Keliver should stay viable independently of Stashfin governance
  approval either way.

### Freeze list

Not until a pilot proves need: more widgets, a DOM renderer, arbitrary animation
systems, every native capability, total navigation replacement, multi-bundle
federation, a giant visual designer, complete arbitrary-Kotlin round-tripping,
every DI framework, every database abstraction. Target 80% elegantly + 20% via
native capability/component escape hatches (D6); a production app decides which
of the remaining 20% is worth solving.

The question to ask is no longer "what should Keliver support next?" It is:
**what is the smallest experiment that can falsify our belief that Keliver is a
dramatically better way to build mobile apps?**

## Post-snapshot: launcher supervision — 2026-09-05

The last open live-preview/developer-loop item (#6 above, ROADMAP item 23) is
closed. **The M0 "deterministic preview" milestone is now complete.**

The reference implementation was `stashfin-sdui/scripts/dev.sh`, which was lost
with the machine, so the behaviour was re-derived from the in-repo
`scripts/keliver-dev.sh` instead of ported.

`scripts/keliver-portal` (the launcher that ships in `keliver-portal-tools`) now:

- **Health-waits** on `GET /devstate` instead of a blind `sleep 3`, and fails
  fast — if the relay exits during startup the launcher prints the tail of the
  server log and returns non-zero. Previously it printed working URLs for a
  server that had already died.
- **Stops properly.** `keliver-portal stop [app-dir]` and
  `keliver-portal status [app-dir]` work from any shell, backed by per-app run
  state under `$TMPDIR/keliver-portal/<hash of app dir>`. Shutdown kills the
  tracked process subtree with TERM then KILL, and is idempotent.
- **Never kills a stranger's port.** The old cleanup ran
  `lsof -ti :PORT | xargs kill` on both ports, which would kill any unrelated
  process holding them — the same class of collision already recorded in this
  document, where another project occupied 8080 with a valid but incompatible
  Zipline manifest. Ports not owned by this app dir are now reported, not killed,
  and startup refuses to run rather than fighting for an occupied port.
- **Auto-builds the app's editor.** If `<app>/editor` exists (from
  `keliver-new-editor.sh`) it is built and served, so the preview runs the app's
  real presenters; a failed build falls back to the bundled generic editor and
  says so, mirroring the relay's existing last-known-good promotion.
  `--rerun-tasks` forces a clean rebuild (webpack can otherwise serve a stale
  distribution — the same trap as live-preview issue 5); `--no-editor-build`
  skips it.

**Defect found and fixed while doing this.** Both `keliver-portal` and
`keliver-dev.sh` ended with `while true; do wait || break; done`. Once the last
child has exited, `wait` returns 0 immediately because there is nothing left to
wait for, so `|| break` never fires and the loop spins at **100% CPU
indefinitely**. It reproduces whenever the portal is stopped out-of-band or both
services crash. Measured at 99% CPU before the fix; both scripts now poll their
tracked pids and exit when none remain.

Acceptance evidence — the launcher was exercised against a synthetic bundle
covering each path: `status` when down (exit 1) and up (exit 0); `stop` with
nothing running; a relay that crashes on boot (**failed in 1s with the log tail
and exit 1**, versus the old blind sleep reporting success); a relay with a slow
boot (health-wait held until it answered); double-start refusal; out-of-band
`stop` from a second shell freeing both ports and letting the launcher exit
cleanly instead of spinning; app-editor build success (the app's distribution
served, banner reporting "your real presenters"); and app-editor build failure
(warned with the compiler error, fell back to the bundled editor, portal still
came up).

## Post-snapshot: adopter clearance, 0.3.3, and CI reversal — 2026-09-06

**Published line is now `dev.keliver:*:0.3.3` on Maven Central**, verified by
resolving and compiling a clean `keliver-init` scaffold from Central alone.
`0.3.2` was tagged but **never published**: its release preflight was killed
at a 60-minute CI ceiling before the upload step. Its GitHub release is
retitled accordingly; `dev.keliver:*:0.3.2` does not resolve.

### The adopter path was exercised end to end for the first time

Previously the zero-checkout path had only been verified against *locally
published* snapshots inside `stashfin-sdui`, which no longer exists. Running
it as an adopter actually experiences it — released zip, Central only, no
Keliver checkout — established two things.

**The engine works.** `keliver-init` → cold `compileKotlinJs` against Central
succeeds. The bidirectional round-trip is real and surgical: a `SetProp` over
`/ops` changed exactly one line of `screens/home.kt`, leaving comments,
imports and the hand-owned Bindings interface untouched, and a subsequent
hand edit to the `.kt` was ingested back with the earlier change preserved.

**Everything between an adopter and that engine was broken.** Nine defects,
none in the runtime, all invisible from inside this repo — where `web-spike`
exists, `main` is the real screen, and the dogfood app is the only consumer.
Full detail in `DOGFOOD_NOTES.md` (Dogfood 2 and 3). The worst: opening the
editor wrote `main.kt` and `Compiled_main.kt` into the adopter's own source
tree, declaring `dev.keliver.portalpublished.screens` — this repo's
namespace, in a package that does not match its directory and does not
compile.

Also fixed: the editor scaffolder generated a project that could not compile
(`logic/` on the source path without the `screens/` that declares the
Bindings contracts); the preview build failed by construction for every
consumer; the promote() cache-bust only matched this repo's own loader name;
and `/projects` listed the relay's storage — including the signing-key
directory — as selectable projects.

### CI returned to GitHub-hosted runners

The self-hosted runner was rebuilt, then retired the same day. Its
justification — the 10x macOS minute multiplier **on private repos** — expired
when the repo went public, while its costs did not: four distinct
manifestations of a TLS-inspecting corporate proxy, an unset `ANDROID_HOME`,
81 GB of disk pressure, fork PRs executing on a personal machine, and finally
an unreachable release CDN that blocked a release outright because CI had
nowhere else to run. `CI_RUNNER` is now `macos-latest`; the runner is
installed but stopped. Workflow timeouts were raised to 60/120 for the slower
hosted runners. Fork-PR approval is `all_external_contributors`.

### M4 falsification set: run 1 was inconclusive

The pre-registered experiment was run early and **did not validate the
instrument**. Two of five cases turned out not to be valid tests at all — F1's
proposed mechanism was rejected (RawCode preserves the source, so the control
is not unwired), and F3 cannot be planted at adopter level (a Kotlin compile
error). No case was shown to discriminate, and no baseline agent was run.

An initial write-up called F4 a strong result and proposed rebuilding the set
around it; that was withdrawn under review as a null result being converted
into a narrower thesis. Artifacts are archived in
`superpowers/evidence/m4-run1/`. Run 2 requires a behavioural
failure-and-fix check per case, neutral fixtures without answer labels, and
scoring independent of any agent's self-report. See the design spec.

**Nothing here supports a claim about where semantic access wins.** The
thesis under test — the incremental benefit of semantic access over source
plus build/run/screenshots — remains untested.

### Where the milestones stand

- **M0** complete.
- **M1** complete.
- **M2** unblocked and not started: the recruiting ask is drafted
  (`writing/2026-09-05-m2-recruiting-ask.md`) and its release gate has passed.
  Still **no external adopter**, so decision-gate proofs 2 and 4 remain
  unevidenced.
- **M3** deliberately not started.
- **M4** blocked on a set that can decide something.

## Post-snapshot: adopter isolation fixes and the M4 discovery study — 2026-09-07

### Two adopter defects closed

Both were found by running the portal against apps scaffolded by
`keliver-init` from outside this checkout, and both were reproduced before
being fixed. Commit `a3b9651ad`; evidence in
`superpowers/evidence/adopter-store-and-guide/`.

**One document store was shared by every app on the machine** (`U17`).
`storeDir()` defaulted to `~/.keliver-portal`, so with two apps running, app B
listed app A's screens and opening one wrote `<screen>.kt` plus
`Compiled_<screen>.kt` into app B's source tree; starting app B also deleted
app A's documents. A store now belongs to exactly one repo
(`~/.keliver-portal/apps/<slug>-<hash>` by default), is never inside the app's
source tree, persists across restarts, and refuses to serve a second repo.
`PORTAL_STORE` is now honoured — it used to be silently ignored, which reads
as isolation and is not.

The mechanism that actually created the file is closed too: `GET /doc` for a
screen the app does not have is now 404 rather than minting a document whose
backing `.kt` gets materialised. That is the relay half of `U16`.

**`get_guide` returned "guide not found" for every adopter** (`U18`). It read
`<PORTAL_REPO>/docs/PORTAL_USAGE.md`, a path only this repository has. The
guide now ships inside the MCP package, copied at build time so it cannot
drift; an app's own copy still wins.

Verified from the `0.3.3-local` candidate bundle with two freshly scaffolded
apps outside the repository. Regression: 2 failures before / 9 passes after in
the two-app script, plus 10 unit tests.

### M4: what the runs support

Three separate things, deliberately kept apart.

1. **Case 2 (paired comparison)** — one wrong-field-binding fixture, baseline
   vs semantic. Both produced the identical correct fix, both independently
   scored PASS. The semantic participant never opened the channel.
2. **Forced-use diagnostic** (separately labelled, not part of the comparison)
   — required to diagnose through the portal tools, a participant did so.
   That shows the queries **expose useful binding information**. It does
   **not** show that using them improves outcomes.
3. **Discovery study** — three arms with three repetitions each, on the same
   one fixture. **Nine runs, not nine independent defect cases.** All nine
   produced the expected fix; none invoked a portal tool, including three runs
   with the tools listed upfront.

An earlier write-up claimed the listed arm settled *why* the deferred
participants declined. **Withdrawn**: those are self-reports, consistent with
their traces but unable to establish another participant's decision process.
The distinction stays open.

Output provenance was audited rather than assumed: 3 outputs retained
directly, 6 reconstructed from captured `Edit` calls with the replay validated
against the three that survived, 0 unestablished. Every output was executed by
the scorer individually — the earlier single shared score is gone.

**Still true: nothing here supports a claim about where semantic access wins.**

### Recorded, not built: a round-trip consistency test

A possible separate test — **not** an M4 case, and not constructed — is
source/document divergence: a screen whose `.kt` and whose live portal document
disagree, e.g. after an editor write-back or a stale `Compiled_*` artifact.
That belongs with the round-trip gate (D14), not with M4's comparison.
**M4's inclusion criteria are unchanged**: they must not require baseline
failure or a predicted semantic win.

### Where the milestones stand

Unchanged: **M0** and **M1** complete, **M2** unblocked with still no external
adopter, **M3** not started, **M4** still without a set that decides anything.

## Post-snapshot: store contract, concurrency and upgrade path — 2026-09-07

Relocating the document store to a per-app directory (U17) fixed isolation and
broke four things that had quietly depended on the old location. Commit
`546164fc2`; evidence in `superpowers/evidence/adopter-store-and-guide/`.

### One contract, and it is checked

`portal-published-guest` signed with `~/.keliver-portal/keys`, both device
hosts embedded the public key from there, and `keliver-record-http.sh`
resolved the old default — while the relay generated the keys somewhere else.
Reproduced with disposable state: the relay minted an identity in the per-app
store and the publisher then emitted an **unsigned** bundle.

`scripts/keliver-store-path.sh` is now the single resolver (`PORTAL_STORE` →
`keliver.portal.json` `store` → the pointer the relay writes to
`.gradle/keliver-store-path` → `~/.keliver-portal/apps/<slug>-<hash>`), used by
the root build for all three Gradle modules and by the recording client.
`StoreContractTest` asserts the shell mirror agrees with
`PortalConfig.storeDir()`.

**Signing is verified, not asserted.** The same scenario now produces a bundle
signed `portal-ed25519` that verifies against the store's public key through
Zipline's own `ManifestVerifier` with signature checks on; a tampered manifest
is rejected, so the check is not vacuous. An unsigned bundle fails the test.

### Startup is safe under a race

`claimStoreFor` was `exists()` then `writeText()` — a 16-thread test showed
**16 of 16** simultaneous claimants winning an unowned store. `CREATE_NEW`
makes acquisition atomic. At process level exactly one relay survives, the
loser refuses with the conflict error and changes neither the owner marker nor
any source file, and the legitimate owner still restarts.

### Upgrade is announced, never guessed

The relay reports what a legacy `~/.keliver-portal` still holds — signing
identity, bundles, documents — and points at
`scripts/keliver-adopt-legacy-store.sh`, which copies per file into one
**named** app. Nothing is moved, deleted, overwritten without `--force`, or
attributed to a repo on its own, because a shared global store cannot be
attributed. 14 checks against disposable fixtures.

### Test isolation is enforced before startup

`scripts/keliver-test-isolation-guard.sh` refuses to launch a test relay unless
the JVM's effective `user.home` **and** the resolved store are inside the
disposable root, and rejects anything under the real `~/.keliver-portal`.
Setting `HOME` is not enough — the JVM takes `user.home` from the passwd entry
— and that is exactly how the real store was written to earlier.

The earlier incident is restated as **partially repaired**: signing keys and
bundles were never touched, the one document my run created was deleted, and
the **active screen selection was overwritten and is not recoverable**. No
further restoration has been attempted.

### Verified, and not

Executed: relay unit suite (34), MCP unit suite (12), `apiCheck`, legacy
compatibility 14/14, store integration 9/9, two-app contamination 9/9,
signed-bundle verification 2/2, and the bundled scripts exercised from a fresh
`0.3.3-local` package outside the repository.

Established only by inspection: nothing in this block's behaviour claims —
each was executed. **Platform coverage is macOS only.** The
`user.home`-versus-`HOME` divergence is macOS-specific in its details; Linux
and CI behaviour of the resolver, the guard and atomic acquisition is inferred
from the code and has not been run.

### Milestones

Unchanged: **M0**, **M1** complete; **M2** unblocked with no external adopter;
**M3** not started; **M4** still without a set that decides anything. No M4
runs were performed in this block.

### Follow-up: adopters' git working trees — 2026-09-07

The store pointer fixed the key-location mismatch and left a smaller problem
behind: `keliver-init` shipped no `.gitignore`, so a scaffolded app's first
`git status` after using the portal showed `?? .gradle/`, and `?? build/` after
a build. Fixed in `b890abca0`.

`scripts/keliver-adopter-tree-check.sh` now tests the property this project has
broken three times — the portal writing into someone else's repository
(`main.kt` from a parameterless `/doc`, `feed.kt` from the shared store, the
pointer) — by requiring a scaffolded, committed app to have an empty
`git status` after an ordinary portal run and a build. Before: 5 failures.
After: 8/0, and 8/0 again through the bundled scaffolder and relay.

Apps scaffolded by an older `keliver-init` are unaffected by the fix and will
still show `.gradle/` as untracked.

## Post-snapshot: the adopter guide, executed from a package — 2026-09-07

Commit `4a3935701`. Evidence in
`superpowers/evidence/adopter-guide-acceptance/`.

### The guide an adopter actually gets

`get_guide` was fixed for availability earlier but shipped the **contributor**
guide, which tells an adopter to run Keliver's own dev script and edit
`portal-app-lib/` — neither exists in their app. The package now carries
`docs/PORTAL_ADOPTER_GUIDE.md`, written only against packaged commands and
covering: prerequisites and scaffolding, who owns `screens/` versus `logic/`,
MCP setup and tool discovery, inspecting a screen, one supported `apply_ops`
edit with its resulting source diff, building and running on a device, preview
mocks versus runtime values, and store ownership including the
never-automatic legacy route. `PORTAL_USAGE.md` is now marked contributor-only.
The app-local `docs/PORTAL_USAGE.md` override still wins, so a team can ship
its own conventions to agents.

`GuideTest` fails if the bundled text names a repo-only path or drops the
preview-versus-runtime warning.

### Executed, not asserted

Candidate package
`sha256 210a7abb4106914833745f97c152a8cce2b695c8c45facf384236121fe42e8a0`,
a disposable app outside this checkout, guide commands followed literally:
scaffold → start → `get_guide` → `get_document` → `apply_ops` dry run → commit
→ one-line source diff → compile → device target → host install → serve →
**the edited title observed on the emulator** → stop → restart → the edit
still in both the document and the source. **20/20**
(`scripts/keliver-adopter-acceptance.sh`).

Ownership was checked by source fingerprint rather than `git status`: the
screen changed, the device target and the generated `Compiled_home.kt` stamp
appeared as documented, and `logic/HomePresenter.kt` was byte-identical.

### Fixed on this route

`keliver-portal`'s port check used `lsof -ti ":$p"`, matching client sockets in
`TIME_WAIT`, so `stop` followed by `start` refused with "port already in use"
while nothing was listening — the same over-broad match that once killed an
emulator here. Now listener-only, with a stop/start cycle in
`keliver-adopter-tree-check.sh`.

Five guide errors were corrected from what the run did: JDK 17-**or-later**
(17 and 21 verified), the `Compiled_<screen>.kt` stamp, version numbering
restarting after a portal restart, the build files the device scaffolder edits,
and the fixed editor port that prevents two portals running at once.

### Candidate versus published

Everything above is **locally built candidate behaviour**. The published
`dev.keliver:*:0.3.3` on Maven Central contains none of it — not the adopter
guide, not the store contract, not the port fix. Nothing has been released.

### Limits

macOS only; one emulator; one app. The device route is emulator-specific
(`10.0.2.2`) and a physical device was not exercised. The per-app editor
(`keliver-new-editor.sh`) is documented but was not built or run, so the
"preview with your real presenters" path is **described, not verified**.

### Milestones

Unchanged: **M0**, **M1** complete; **M2** unblocked with no external adopter;
**M3** not started; **M4** untouched this block — no participant runs.

## Post-snapshot: safe acceptance entry point, and the real-presenter preview — 2026-09-07

Commits `24708c550` (safety) and `1b2e3eaef` (preview route). Evidence in
`superpowers/evidence/adopter-preview-route/`.

### The acceptance script no longer deletes what it is given

`keliver-adopter-acceptance.sh` began with `rm -rf "$1"` — **before** the
isolation guard ran, so a reused or mistyped path was erased by the very script
whose guard exists to prevent that. The argument is now a *parent*;
`keliver_make_run_dir` creates a uniquely named directory beneath it and nothing
the caller supplied is ever removed. Cleanup stops only processes that
invocation started, and a foreign process on port 8080 is reported rather than
killed.

`keliver-acceptance-safety-check.sh` is the regression (8/8): it reproduces the
old destruction with a stub inside a throwaway directory, then requires sentinel
files to survive a refused run and a run that fails after unpacking, each run to
get its own directory, the guard to precede relay startup, and no blanket port
kills to remain. The same two-line change went to the three sibling checks.

### The real-presenter preview is now a documented, executed route

`keliver-new-editor.sh` scaffolds an editor whose `screens` map is entirely
commented out, and the editor opens in **mock mode** regardless — so the guide's
one-line "scaffold the app's own editor" implied a working preview that did not
exist. The guide now covers all five steps, including registering the screen
under its portal name, mapping contract fields to values, routing actions back
into the bindings, and pressing ▶ Live.

Executed from the package on a disposable app with the app's own stateful
presenter: mock mode drew the binding as `{tally}`; after ▶ Live the canvas read
`0 tallied` **from the presenter**; tapping the preview button moved it to
`1 tallied` with the console logging `⚡ add → real presenter`. Nine lines of
wiring, no parallel mock logic.

### A real defect the comparison found — U19

The same app on the device accumulates `0 → 1 → 2 → 3` across taps; the preview
returns `1` every time, though all three taps dispatched. Preview state held in
`remember` is discarded between dispatches. **Not root-caused** — that means
changing the live preview engine, which was out of scope. The guide now says the
live preview is trustworthy for wiring and a single transition, and that
multi-step behaviour belongs on the device.

### Distribution channels

Two channels, and they are not the same thing:

* **Tools bundle** (`keliver-portal-tools`, built locally, **not published
  anywhere**): the adopter guide and `get_guide` contents, `keliver-init` and
  its `.gitignore`, `keliver-portal` and its port fix, the store resolver,
  the legacy-adopt route, the recording client, the device scripts.
* **Maven Central** (`dev.keliver:*`): the runtime and editor artifacts. The
  preview route resolves `portal-editor`, `portal-render`, `portal-core` and
  `portal-document` at **0.3.3 from Central** — verified HTTP 200 — so it needs
  no local candidate artifacts. The store-ownership and `/doc` 404 changes live
  in `portal-relay`/`portal-mcp`, which ship in the **bundle**, not in the
  published Maven artifacts an app compiles against.

So "not in published Maven 0.3.3" is the wrong description of most pending work:
it is undistributed **tooling**, awaiting a bundle release, not a Maven release.

### Limits

macOS only. One app, one presenter, one emulator. U19 is reproduced but not
diagnosed. The preview was driven through a browser; no automated regression
covers the Live-mode transition.

### Milestones

Unchanged: **M0**, **M1** complete; **M2** unblocked, still no external adopter;
**M3** not started; **M4** untouched — no participant runs this block.

## Post-snapshot: U19 root-caused and fixed — 2026-09-08

Commits `1db77c27c` (fix + regression + evidence) and `d175d2c52` (API dump).
Detail in `superpowers/evidence/adopter-preview-route/U19-ROOT-CAUSE.md`.

### The earlier causal claim was wrong

U19 was recorded as "preview state discarded between dispatches". **It was
not.** State accumulated correctly throughout; the canvas was stale. That
wording is withdrawn from the bug register and the adopter guide.

Instrumenting presenter identity, per-action state, and each frame showed one
composition for the whole session, both dispatch routes reaching the same live
presenter, and state moving `0 → 1 → 2` while the inspector still read
`0 tallied`. An unrelated document edit then made the canvas jump to
`2 tallied`. That ruled out presenter reset, stale callbacks and registration
mistakes, and left a stale display.

### Root cause

`EditorShell` runs the live-preview guest composition on its own
`BroadcastFrameClock`, ticked from the host's frames. The coupling is
one-directional: a presenter write invalidates the **guest** recomposer, which
waits for a frame that an idle host never schedules.

`HostWakeSignal` supplies the missing link — a state the host composition
reads, bumped by `LiveEngine.dispatch`, which both the canvas and the State
Inspector routes already funnel through.

### Verified

`LivePreviewDispatchTest` drives the real preview path and requires
`0 → 1 → 2 → 3`; before the fix it fails with
`[0 tallied, 0 tallied, 0 tallied, 0 tallied]`. A second test holds the intended
resets (screen change, persona change, stop/restart).

From a fresh external app against a candidate `portal-editor` — published to a
disposable file repo, shadowing Central for that one coordinate, the real
`~/.m2` untouched — the browser preview stepped `0 → 1 → 2 → 3` through real
clicks, and the same unchanged presenter on the device did the same.

One passing fixture is **not** a runtime-correctness guarantee. The preview runs
the real presenter; it does not certify an app's behaviour.

### Release scope — which channel carries what

| change | channel | state |
|---|---|---|
| U19 preview fix | **Maven** `dev.keliver:portal-editor` | built and verified locally; **unreleased** |
| adopter guide, `get_guide` contents | tools bundle | unreleased |
| `keliver-init` `.gitignore`, `keliver-portal` port fix | tools bundle | unreleased |
| store ownership, `/doc` 404, legacy adopt route | tools bundle (`portal-relay`/`portal-mcp` binaries) | unreleased |

**Until a Maven release, every adopter whose editor resolves
`portal-editor:0.3.3` from Central still has the U19 defect** — the preview
stops updating after the first action. A tools-bundle release does not fix it.

### Limits

macOS only, one app, one presenter, Chrome. The editor's DOM panels update one
host frame behind an action, so a read taken immediately after a click can show
the previous value; canvas and inspector agree once a frame has run.

### Milestones

Unchanged: **M0**, **M1** complete; **M2** unblocked, no external adopter;
**M3** not started; **M4** untouched — no participant runs.
