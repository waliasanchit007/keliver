# Keliver current state

**Snapshot date:** 2026-07-23
**Repository baseline:** `94519abf5` (`#13` flow preview complete and live-verified)

This document is the factual handoff point before the next implementation
milestone. It describes what is present and what has been recorded as verified;
it does not replace the architectural decisions in `DECISIONS.md` or the two
forward-looking roadmaps.

## Product state

Keliver is a public, pre-1.0 Compose Multiplatform SDUI framework. The current
published line is `dev.keliver:*:0.3.0` on Maven Central. Guest UI is compiled
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

The reference adoption is `~/StudioProjects/stashfin-sdui`. It has recorded
Android, iOS, and web verification against a real application integration.

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
- Stashfin owns a standalone editor composite build that compiles its real
  Profile presenter without copying it.

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

`portal-editor`, `portal-core`, `portal-document`, `portal-render`,
`portal-flow`, and the web protocol modules are not currently configured as a
coherent published artifact graph. Consumer-owned live editors therefore use a
Keliver composite build. Publishing the editor is a productization milestone,
not only a version bump.

## Known open correctness and developer-loop issues

The current roadmap records these live-preview fast-follows:

1. Component-expanded bindings can miss the first live frame until a later
   recomposition.
2. Canvas event payloads can arrive as a null argument.
3. Presenter composition failures are not caught by the existing dispatch
   error guard.
4. The State Inspector can lag one frame and retain values after a screen
   switch.
5. Some consumer wasm distributions require `--rerun-tasks` before webpack
   produces a fresh output.
6. The bundled launcher has not fully absorbed the health-wait, supervision,
   and editor auto-build behavior proven in the Stashfin development script.

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

1. **Current-loop correctness and documentation consolidation.** These are
   known defects and contradictions in already-delivered behavior.
2. **Editor/flow distribution productization.** External per-app editors should
   not require a Keliver checkout or composite build.
3. **Named personas and capability fixtures.** Real presenters need
   reproducible auth/flag/domain states in preview.
4. **Recorded HTTP replay.** Valuable for real API-backed screens, but separate
   from personas because request matching, redaction, privacy, and fixture
   lifecycle need their own design.
5. **Presenter/FlowScope linting.** Becomes more important as real presenter
   adoption scales.
6. **Transparent local composables and component metadata polish.** Useful
   authoring depth, but lower priority than the gaps above.

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
