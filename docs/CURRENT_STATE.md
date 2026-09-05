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
