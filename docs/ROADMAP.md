# Keliver — Roadmap & improvement backlog

Ordered by (impact on the adoption thesis) × (how soon it bites). Every item lists
its evidence — nothing here is speculative. Status: 2026-07-23, after the
Stashfin tri-platform loop, Project Components C1-C4, real-presenter preview,
and the complete flow-authoring/preview loop. See `CURRENT_STATE.md` for the
factual handoff snapshot.

## P0 — Write-back trust (the thesis-critical gate) — ✅ DONE 2026-07-12

The 100-screen review's top finding: if portal diffs are noisy, senior engineers
reject them, the portal becomes designer-only, and the two-way thesis dies socially.

1. ✅ **Surgical-edit indent/format churn** — FIXED: prop edits replace only the
   changed arguments' VALUE expressions (byte-exact for all untouched formatting,
   including hand spacing and one-line style); prop add/remove falls back to a
   whole-list replace re-indented to the call's depth (single-line preserved);
   inserts land at sibling depth with proper leading whitespace. Byte-idempotency
   (edit + undo == original bytes) is unit-tested (WriteBackTest, 7 green) and was
   live-verified on stashfin ProfileScreen/ProfileLiteScreen.
2. ✅ **/undo** — re-diagnosed: undo always wrote the file. The observed failure
   was a SESSION mismatch (undo stacks are per-session; `/undo` without an
   `X-Portal-Session` header matching the ops envelope hits an empty stack and
   returns ok:false inside HTTP 200). Live-verified working. Follow-up UX trap
   (small): default undo to the last-writing session or return 4xx on empty stack.
3. ✅ **packageName** — FIXED: writeKotlin (and the Compiled_ sibling) derive the
   package from the EXISTING file; the constructor default is only a fallback.
   Severity confirmed in the wild: before the fix, a full-export fallback
   regenerated stashfin's ProfileScreen into dev.keliver.portalpublished.screens
   and broke the guest compile. Follow-up: log when the non-surgical fallback
   fires so full regens are observable.

## P1 — Grammar & recognizer gaps (each found by porting a real screen)

4. ✅ **Literal action args** (DONE 2026-07-12): `{ b.open("ROUTE") }` / `{ b.pick(3) }`
   are grammar — the recognizer keeps the arg's SOURCE text (also matches the
   `{ _ -> ... }` exporter form), the exporter already emitted verbatim, and
   collectContract types the param from the literal (String/Int/Double/Boolean).
   Round-trip tested (RecognizerTest.literalArgActionsRoundTrip) + live-verified
   ingesting a probe screen in stashfin-sdui (0 RawCode).
5. ✅ **Field-aware Repeat mock defaults** (DONE 2026-07-16): unmocked item binds
   preview as realistic values — icon props get VALID icon names (never ⓘ),
   image/url fields a picsum URL (AsyncImage renders), amount/date/phone/email
   plausible values, else humanized "Title 1". Editor hints still override.
   ItemMockTest 4/4.
6. ✅ **Icon set** (DONE 2026-07-16): material-icons-extended added; the curated
   map grew to ~100 names (QrCode/ContentCopy/AccountBalance/CreditCard/Payments/
   Receipt/Fingerprint/Logout/History/Verified/…). Kept curated — the name map
   defeats DCE per icon, bulk-import would bloat every bundle. Consumers get the
   new names after a host-lib republish + app rebuild.
7. ✅ **Editor canvas: ListItem width** (code-complete 2026-07-16, canvas visual
   check pending — browser tooling was down): ComposeUiListItem now applies
   fillMaxWidth() — M3 ListItem wraps content under loose constraints, which the
   editor canvas exposes; devices were unaffected (parents imposed width) and
   remain so. Editor dist rebuilt; refresh :8096 to confirm.

## P2 — Relay/tooling ergonomics (each cost real session time)

8. ✅ **Relay boot scan** (DONE 2026-07-16): every screens-dir .kt is ingested at
   boot — no more invisible screens / touch-doesn't-fire dance. Live-verified.
9. ✅ **Config discovery + store reconcile** (DONE 2026-07-16): repo resolves
   PORTAL_REPO env → nearest ancestor of cwd with keliver.portal.json → cwd,
   with a startup banner naming the source; positional args fail loudly (they
   can't be honored — top-level init order). Stale ~store/default/*.json mirrors
   are retired at boot when their .kt is gone. Live-verified (GhostScreen probe).
10. ✅ **Unknown-widget diagnostic** (DONE 2026-07-16): HostProtocolAdapter now
    logs the missing widget TAG at create-skip time and the eventual node lookup
    fails with "Widget tag X is unknown to this host — host older than guest;
    update the host library or gate widgetVersion" instead of the cryptic
    "Unknown widget ID N". (Ships to consumers with the next host-lib publish.)

## P2b — New findings (2026-07-16, from the user's live editor session)

10a. ✅ **Contract write-back** (DONE 2026-07-16): every tree write now runs
    ContractWriteBack.ensure — new binds/actions ADD defaulted, TODO(portal)-
    marked interface members (`val items: List<Item> get() = emptyList()`,
    `fun open(value: String) {}`) so presenters keep compiling and devices
    render the draft; marked members ops un-require are REMOVED (undo is
    byte-clean); hand-written members are never touched; item interfaces are
    managed only when referenced by managed members (hand-named item types are
    not duplicated). The contract-mismatch full-regen bail in WriteBack is
    gone. Fixed en route: collectContract leaked item-scoped binds as invalid
    dotted interface members (`val item.label: String`) — latent PUBLISH-path
    exporter bug. Live-verified on stashfin ProfileScreen: insert Repeat over
    a new field → guest build GREEN with 2 markers → undo → byte-identical.
    Follow-up: the publish verifier should reject bundles whose contract still
    carries TODO(portal) markers (drafts must not ship to prod).
10b. ✅ (mitigated 2026-07-16) **serve dies on compile error** — stashfin's
    dev.sh now supervises and restarts it (5s backoff). Root-cause fix in the
    zipline serve task's continuous-mode failure handling still open (P4-ish).

## P3 — Application-scale features (from the 100-screen review; build in this order)

11. ✅ **Canvas click-to-select** (code-complete 2026-07-16, commit 388bd592f;
    in-browser visual check pending — browser tooling was down): portal-internal
    SelectionTag(handle) modifier (in RenderNode, hidden from palette/exporter) →
    ComposeUi applier reports bounds into SelectionRegistry → DOM chrome
    hit-tests clicks (dpr-aware, innermost-wins), syncs the outline selection,
    draws selection/hover overlays; 🎯 toggle switches select/interact. Repeat
    rows select their template. Verify at :8096, then extend with a breadcrumb +
    double-click-into-Repeat as polish.
11b. ✅ **Project components (molecules)** DONE 2026-07-16 (C1–C4):
    components/ dir (configurable componentsDir), signature-derived specs (no
    annotations), master/instance, transparent expansion, opaque/cycle
    placeholders. C1 recognizer+registry+relay /components (component-first
    boot, dependent re-ingest); C2 export+surgical write-back for instances
    (byte-idempotent) + contract typing; C3 editor palette + prop panel +
    preview expansion + instance click-to-select (Chrome-verified); C4
    keliver-new-component scaffold (bundled in portal-tools) + docs + keliver
    dogfood (settings.kt: 3 SectionHeader + 4 MenuRow, 0 RawCode, ~60% shorter,
    renders in editor). Gates: 65 portal tests, codegen staleness, apiCheck,
    guest compile all green. Leaf model completed by 11c; detach-instance +
    double-click-into-definition remain later interactions.
11c. ✅ **Project Components v2 — one required content slot** COMPLETE + TRI-PLATFORM
    VERIFIED 2026-07-23: `@Composable () -> Unit` signature discriminator;
    ComponentSlotSpec + reserved Slot marker; exactly-once definition call-site
    validation; instance child recognition; transparent expansion with nested
    component recursion; independent slot-child selection handles; trailing-
    lambda export (including required empty slots); surgical insertion into an
    empty slot; editor container palette/outline/drop/props behavior; relay
    `/components` slots; `keliver-new-component --slot`. Dogfood: canonical
    Settings = 3 SectionCard + 4 MenuRow, zero RawCode; Stashfin Profile = five
    repeated white wrappers replaced by SectionCard, zero RawCode. Gates: focused
    and broad API/codegen/core/document/render tests green (1,453 actions),
    editor real-presenter + palette insert/undo verified with byte-identical
    source restoration, signed Android and iOS bundles both loaded 41 modules
    and visibly rendered the slotted cards. Multiple named slots are deferred.
12. ✅ **Per-app live-presenter preview** (A+B code-complete 2026-07-16,
    fc6d357fa): explicit AppPreviewEntry contract (portal-render) — app logic
    compiles INTO the preview binary (wasm has no dynamic linking; the
    refreshless-iframe split is the v2 increment); portal-app-lib js+wasm with
    compiler-enforced capability purity (presenters take SqlDriver, Zipline
    adapter at the device edge — device bundle unchanged); LiveEngine composes
    the REAL presenter keyed by screen, values through PreviewBindings.mocks
    (Repeat rows + component expansion light up free); M8's hardcoded
    reference presenter DELETED; relay rebuild loop (debounce, single-flight,
    stale-reject, promote-on-success last-known-good) + /preview-build; editor
    build chip + state-preserving auto-reload. PreviewBuilderTest 3/3.
    **ALL FOUR IN-CANVAS LIVE GATES VERIFIED IN-BROWSER 2026-07-19** (agent-
    driven, Claude_Browser): `main`→▶Live shows real "Compiled + SIGNED Kotlin
    from the portal" (not the {text} mock); ⚡buyTapped climbs ×1→×2 "persisted
    in SQLite via OTA data layer" + action-console logs; `feed`→addNote adds a
    real "Note #1" row (empty-state → "1 note"); `settings` renders "Live
    Presenter" through the MenuRow component. Three real bugs found+fixed+
    verified while gating (bb4fc40ed, 905567267, a625d4233): (1) switchProject
    didn't refetch the per-project component registry → components became opaque
    placeholders after any project switch; (2) renderBindings/renderInspector
    called collectContract WITHOUT the registry → binds through component
    instances (subtitle=b.name) dropped from the panel/mocks; (3) the relay's
    promote didn't cache-bust the constant-named web-spike.js → browsers ran a
    stale editor+wasm across rebuilds (silently defeated promote-on-success —
    cost a full detour). NOTE: :8096 must serve build/portal-editor-live (the
    PROMOTED dist). Stashfin external-app enablement is complete through a
    composite build; publishing the editor's full transitive module graph is a
    separate productization milestone. **2026-07-23 hardening:** live binding
    keys are pre-seeded before the first presenter frame, canvas event payloads
    and repeated-row action args reach presenters, the inspector updates from
    applied frames and clears on screen changes, presenter recomposition runs
    in an isolated supervised scope, and incomplete webpack distributions are
    rejected/retried before promotion. **FULL GATE GREEN 2026-07-23:** broad
    tests/API/codegen checks passed; the production Wasm editor passed live
    presenter, action-arg, flow-state, graph, and deep-link checks; Android
    Pixel 9 and iPhone 16 Pro simulators both loaded the correct
    `./portal-device-guest.js` manifest (`codeLoadSuccess modules=44`) and
    visibly rendered the OTA Field Notes screen.
13. ✅ **Typed Route contracts + derived nav graph + flow preview — COMPLETE
    2026-07-23.** Design decisions are recorded in the spec §0:
    v1 routes = string tokens via a `flow{}` DSL, sealed Routes deferred to F4;
    separate appFlowEntry; back-stack; derived edges matching literal action
    args OR action names).
    ✅ **F1 DONE + LIVE-VERIFIED** (a171f3d00): :portal-flow micro-lib (FlowSpec
    + flow{} DSL, jvm+js+wasm, zero deps — apps compile it, relay parses it);
    FlowRecognizer (PSI) 3/3 tests on the Login→OTP→Dashboard fixture;
    portal-core deriveFlowEdges (primitives-only); relay flowsDir + boot scan +
    watch + GET /flow — served the derived graph `feed --openNote--> detail`
    from the real recognized trees.
    ✅ **F2 DONE + LIVE-VERIFIED 2026-07-21** (43ffa5c3c): flow "FieldNotes" →
    ▶ Live followed to feed → addNote (real FeedPresenter) → tap note NAVIGATED
    to detail (real DetailPresenter) → "< Back" popped to feed with the note
    still present (flow-lifetime SQLite survived). Full round-trip green. Engine:
    LiveEngine flow mode keyed by FLOW (state survives screen swaps),
    onFlowScreen→flowFollow chrome follow, Flow select next to ▶ Live,
    runPortalEditor(entry, flows=null). Dogfood: AppLibFlows (FieldNotes:
    feed→openNote→detail→back, real presenters, flow-lifetime PreviewSqlHost).
    Dist c3051a99 built+promoted+cache-busted; served JS verified to reference
    it. GATE SCRIPT when browser returns: select flow "FieldNotes" → ▶ Live on
    any screen (follows to feed) → addNote → ⚡ openNote → DETAIL renders the
    real note via DetailPresenter → back → feed intact (flow state survived).
    ✅ **F3 DONE + LIVE-VERIFIED 2026-07-21** (d2f4649ce): ⛓ Nav-graph overlay
    (clickable node chips — start ▶, current ringed — + "from —key→ to" edge
    rows over /flow); clicking a node opens that screen. Verified: graph showed
    feed/detail + feed —openNote→ detail; clicking "detail" loaded it.
    📄 Spec: docs/superpowers/specs/2026-07-19-flow-preview-design.md.
    ✅ **F4a DONE + LIVE-VERIFIED 2026-07-23** (efb72c8d9): the nav-graph's ▶
    per node starts a Live walkthrough THERE (start-state override) — verified
    ▶ on 'detail' launched Live on detail. F4b (typed sealed-Route params) +
    F4c (capability personas) SCOPED-OUT: string tokens are functionally
    complete (params flow via the stack arg); personas → #16 (PreviewEnv.
    flowStart is the seam). **#13 COMPLETE — flow declare → derived graph →
    navigate/deep-link in preview → browse as a graph. The core flow-authoring
    loop is live.**
14. **FlowScope presenter** — name the pattern (parent presenter composing screen
    presenters, owning flow-lifetime draft state), scaffold + document it.
    FOLDED into #13's design (§3.2).
15. **Transparent components** — recognizer descends into local @Composable calls
    whose bodies are grammar (sections: `Profile ▸ OffersSection`). One feature
    buys feature composition.
16. **Capability vocabulary + personas + recorded HTTP** — HostAuth/HostFlags/
    HostAnalytics interfaces + named capability-state fixtures ("logged-in,
    KYC-pending") + HAR record/replay at the relay proxy.
17. **@PortalComponent catalog codegen** — annotation → palette entry with typed
    props/thumbnail; the consumer app's design system becomes the palette.
18. **Presenter lint pack** — @Composable-presenter footguns (state in companions,
    LaunchedEffect misuse, shared state in presenters) enforced mechanically.

## Current priorities

1. ✅ **Bounded preview hardening and tri-platform verification** — complete
   2026-07-23; evidence and the pre-change baseline are in `CURRENT_STATE.md`.
2. ✅ **Project Components v2 — one required content slot** — complete and tri-platform
   verified 2026-07-23; evidence is in `CURRENT_STATE.md` and item 11c.
3. ✅ **Editor/flow distribution productization** — DONE + LOCALLY PROVEN
   2026-07-24 (f1a85a36a, b8697b7b6, stashfin d8ebe46; design + release
   checklist in docs/superpowers/specs/2026-07-24-editor-distribution-design.md).
   The 7 artifacts a consumer editor needs (portal-core/-document/-render/-flow/
   -editor + the renamed keliver-material-protocol-{host,guest}-web) are now
   publishable: explicit API, committed apiDump, apiCheck green. PROOF: stashfin
   DELETED its composite build and its editor compiled, built a wasm dist, and
   ran in-browser against dev.keliver:*:0.3.1-SNAPSHOT from mavenLocal with the
   real presenter + slotted SectionCard. The Maven Central push is IRREVERSIBLE
   and remains user-triggered — checklist in the design doc.
4. **Named personas and capability fixtures**, then **recorded HTTP replay** as
   a separate design: auth/flags/domain state can use `PreviewEnv.flowStart` as
   the start-state seam, while HTTP replay additionally needs matching,
   redaction, privacy, and fixture lifecycle rules.
5. Presenter/FlowScope linting, transparent local composables, and component
   metadata/thumbnails follow after those adoption-critical gaps.

## P4 — Platform debt (tracked, not urgent)

19. movableContent quarantined tests (KNOWN_BUGS U14).
20. U1: suspend Zipline fn returning `List<@Serializable>` hangs at bind — fix or
    document permanently in the shapes plugin lint.
21. @Modifier lambdas broken on JS (widget @Property events only).
22. WebSocket code-push for the web target (dev experience only; runtime is local).
23. keliver-portal launcher: fold the stashfin `scripts/dev.sh` improvements back
    into the bundled launcher (health-wait, stop, editor auto-build).

## Recently completed (context for readers)

- 0.3.0 on Maven Central; keliver-init scaffolder; portal tools bundle (R1–R6).
- Stashfin adoption: real app on Android + iOS + web from one guest source;
  Profile ported to Style B with 0 RawCode; hot reload verified ~15–25s (S1–S6).
- Application-scale architecture review: presenter/bindings seam holds; missing
  scopes (flow, transparent components, capability breadth) identified → P3 above.
