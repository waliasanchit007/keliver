# Keliver — Roadmap & improvement backlog

Ordered by (impact on the adoption thesis) × (how soon it bites). Every item lists
its evidence — nothing here is speculative. **Status: 2026-10-27.** "Current
priorities" (below the adoption findings) is the ranked list to pick work from;
"Adoption findings (2026-10)" carries a status line per item. The rest was written
2026-09-23, after tools
0.3.5 and the first reference app whose development route uses only published
artifacts (`reference/inventory`, `docs/REFERENCE_APP.md`; its production host
still comes from Keliver source). The P0–P3 sections below are
the 2026-07-27 backlog and its history; after the adoption findings, **"Current
priorities" is the part to pick work from.** `CURRENT_STATE.md` opens with a status-at-a-glance that
separates what is shipped, what was demonstrated and on what, what is broken,
and which evidence can no longer be re-run.

**There is no external adopter.** Everything below that says "verified" was
verified by us. Since 2026-10 one proof was run on production apps we did **not**
write (still by us, never shipped): its findings are the next section, and they
come before "Current priorities".

## Adoption findings (2026-10) — pick work from here first

From the brownfield product proof in
[`ADOPTION_RETRO_2026-10.md`](ADOPTION_RETRO_2026-10.md): one WebView feature
(13 screens) rebuilt as a Keliver guest inside two existing production apps, with
first real-device timings, memory and app-size numbers. Ordered by what a second
adopter would hit first. Items 2–7 are planned step by step in
[`ADOPTION_PLAN_2026-10.md`](ADOPTION_PLAN_2026-10.md); item 6's small fixes
run alongside item 2.

1. **Library 0.3.4: the widget gaps every host had to patch.** Both hosts carry
   the same 206-line override file. Each fix with a test:
   - `BottomSheet` opens fully expanded (`skipPartiallyExpanded`) on the theme's
     surface, and slides out when the guest closes it;
   - `Theme` starts from the ambient `MaterialTheme.colorScheme`, not
     `lightColorScheme()` (unset roles came out Material purple);
   - text fields take a content alignment;
   - lazy lists and pagers key their items by widget, as #103 did for
     `ComposeWidgetChildren` (merged, unreleased);
   - a `ScrollableColumn` with unbounded height is caught at ingest, or rendered
     bounded, instead of crashing on device.
   **Status 2026-10-27: done.** #103 and #105 are released in libraries 0.3.4
   (2026-10-16); see CHANGELOG 0.3.4.
2. **Startup and memory.** On Android (debug builds) the native screen reached
   content in 1.5 s cold against 1.1 s for the cached web page it replaced (equal
   on a reopen, 0.6 s); on an iPhone 13 a warm open takes ≈ 1.5 s, ≈ 1 s of it
   the guest's own work between frames. The iOS first open adds ≈ 76 MB of
   footprint, runtime start included (Android native adds ≈ 10 MB, the web page
   ≈ 165 MB). Start loading code when the host starts, not when the first
   screen opens; profile the guest's frame work in QuickJS; find the iOS
   memory. Make Production bundles the default `publishTask` (Development is
   the default today: 4.3 MB against 2.4 MB, and slower to evaluate).
   **Status 2026-10-27:**
   - **done:** M0 (#116: timing marks, a benchmark build type), M1 (#110:
     Production bundles by default) and M2 on Android (#119: release-build
     start-up numbers on the reference app); iOS M2 is deferred (it needs a
     signing team);
   - **merged 2026-10-31:** M3 (#179: the code load starts as soon as the
     lookup answers; on a Galaxy A12 no gain for a screen opened at launch,
     the later-screen case not measured yet; released in tools 0.3.16);
   - **open:** M4 (a guest frame profile) and M5 (the iOS memory breakdown).
     See ADOPTION_PLAN item 2.
3. **Brownfield host kit, on top of W2's `--embed`.** `--embed` gave a working
   start; each host then needed ≈ 900 lines of glue, and every serious defect
   (no web fallback, a bypassable request block, an authenticated client that
   would follow any URL, developer text shown to users) was in that glue, found
   only by review, and fixed twice. Ship: a fallback hook (show the page the
   screen replaced), a status API, user-safe loading and failure states, routes
   and close, a request allow-list hook, and a recipe for an app on an older
   Kotlin (a separate host build plus an AAR) with the dependency floor it forces
   (the proof's app moved to Compose 1.8.2, material3 1.3.2 and OkHttp 5.1,
   touching 17 unrelated files).
4. **Bridge generator.** One contract, hand-copied byte-identical into three
   repositories, with hand-written Kotlin and Swift delegates. Its one generic
   `request` call duplicated `HostHttp` because the embed did not wire the app's
   authenticated client into it: wire it, behind the request allow-list (D13),
   and generate only the action calls.
5. **Device state catalogue.** iOS was checked with `?demo=` routes, launch
   environment switches and an in-app snapshot hook, all improvised; the test
   data was a hand-written fake while the relay already records and replays
   HTTP. Ship named states, an on-device snapshot runner, and recorded data a
   device can use.
6. **Dev loop.** The first publish after another Gradle build fails with a
   Kotlin/JS "Internal compiler error" and the retry works (cause not diagnosed;
   keep the full Gradle output); the relay reads `keliver.portal.json` only at
   start; a new bundle needs an app restart; an iPhone needs a tunnel because of
   the Local Network prompt, and the debug bundle origin is compiled in.
   **Status 2026-10-27:**
   - **done:**
     - #108: the full Gradle output of every publish is kept;
     - #115 (U34): a publish is built once more after a compiler crash;
     - #109: publish reads `keliver.portal.json` each time;
     - #111: a debug host takes its bundle server at launch;
     - #114: debug hosts check for updates on resume, with a debug check-now hook;
     - #141: a screen opened while a resume update is applied waits for it,
       and is drawn once.
   - **open:**
     - the device proxy;
     - a debug host that picks up a publish **while it is open**, with no
       reopen. It would reuse #114's in-place update, triggered by a publish
       event from the relay; signed bundles only, debug builds only.
7. **Bundle delivery.** A download is one request per module, three in flight,
   so it is latency-bound; a one-line guest change re-downloaded the
   stdlib module too. Measure more parallelism or an archive, and why stdlib
   changed.
8. **The editor on a real app (found 2026-10-16).** One screen of the product proof was
   changed through the web editor end to end: 0 RawCode on 13 screens, a one-line
   surgical diff, a signed Production bundle from the Publish button (81 s cold,
   30 s warm) and the change on the phone after a relaunch. What was rough:
   - escapes and `$` changed a text's meaning on write-back: **U33, fixed**;
   - the tools bundle's generic editor draws every project component as an
     "opaque" grey placeholder (its name overlaps the placeholder label), so a
     component-built screen is mostly grey boxes; `keliver-new-editor.sh` (a
     per-app editor) is the answer, and the editor should say so. *K5: the
     label overlap is fixed, and a component forwarding `modifier` to its root
     (a common cause of "opaque") is now grammar;*
   - the canvas ignores `Theme` (a Slider draws in default lavender where the
     device shows the app's colour), and the Bold box is unchecked for
     `weight = 700`. *K5: a per-app editor's `screenWrapper`/`hostWrapper` put
     the app's Theme and host scheme around the canvas; Bold follows `weight`;
     see docs/EDITOR_FIDELITY.md;*
   - the persona and flow pickers list the generic editor's own sample app
     ("signed-out", "field-researcher", "FieldNotes"), not the adopter's;
   - Undo is per keystroke, so undoing a retyped text steps through every
     intermediate state;
   - `Compiled_<screen>.kt` changes on every edit and restarts its number after a
     relay restart, so the stamp the guide says to commit churns in git;
   - the publish dialog is titled "Exported Kotlin" and names only the active
     screen's file.
   **Status 2026-10-27.**
   - **Released in tools 0.3.12:** the generic editor carries no sample app's
     adapters (2150b4fa6).
   - **Released in tools 0.3.13** (merged after tools 0.3.12):
     - the generic editor carries no sample app's personas or flows either,
       and Publish asks before it runs, and runs once at a time (47f9b3b9b);
     - text and number edits are written once, on Enter or blur (#140), so an
       undo steps back a whole edit;
     - the compiled-version stamp never goes back after a relay restart
       (d51a3a155);
     - Publish reports in a compact card naming the bundle version (#141).
   - **Still true:** the stamp changes on every edit.
   - **The editor program after this finding** (feedback rounds 1–5 by a
     user-role agent, seven independent reviews, a real-input E2E suite in CI):
     - typed property controls (E8–E13, E27, E28, E48);
     - K1–K7 grammar (layout modifiers, design tokens, conditions, templates
       and lists, compound conditions, Int arithmetic);
     - canvas fidelity (K5) and the compiled component preview (#132);
     - #140: selection, components, properties, cross-document undo, write-back
       that compacts new files and keeps undo byte-exact;
     - #141: the outline keeps its place, a top bar that says what each action
       does and which bundle devices get, a palette preview drawn by the canvas,
       and a compact publish card.
   - The whole loop was checked on a physical Android phone: editor edit,
     write-back, signed bundle, device render.
   - **Not yet released.** #140 and #141 reach the tools bundle's generic
     editor with the next tools release. A per-app editor compiles against the
     published `dev.keliver:portal-editor` (`keliver-new-editor.sh`), so it
     gets them only with a libraries release too.
9. **▶ Live for an adopter's own presenters — deferred (decided 2026-10-26).**
   On the product proof, Live had nothing to run: the tools bundle's generic
   editor has no presenters, and the button now says so ("not in this editor").
   Making it run the app's presenters was planned and not started:
   - **What it would take.** A per-app editor (`keliver-new-editor.sh`) with
     the app's logic compiled for wasmJs. Today each screen also needs a
     hand-written `PreviewFrame` mapper (values and dispatch), and on that app
     it would have been 13 mappers over about 150 fields and 60 actions. The
     plan replaced them with **generated live adapters**: one typed
     `xLive { … }` helper per Bindings interface, emitted by PSI the way
     `CompiledAdapterGenerator` emits compiled-component adapters. It also
     needed:
     - `keliver-nav` with a wasmJs target (it has none);
     - the serialization plugin in the scaffolded editor;
     - in the app, a plain capability interface in front of its
       Zipline-typed bridge, and named presenter functions where routes built
       their bindings inline.
   - **Why it is deferred.** About 9–12 hours, half of it changes to the
     adopter's app, and the result would show the app's own fixtures in the
     canvas. A phone in debug mode already shows those fixtures with full
     fidelity, so the dev loop (item 6, a device that picks up a publish while
     open) comes first.
   - **When to pick it up.** When Live inside the editor matters to an
     adopter. Build the generated adapters first and prove them on
     `reference/inventory` by replacing its hand-written mapper, then on an
     adopter's app.

## P0 — Write-back trust (the thesis-critical gate) — ✅ DONE 2026-07-12

The 100-screen review's top finding: if portal diffs are noisy, senior engineers
reject them, the portal becomes designer-only, and the two-way thesis dies socially.

1. ✅ **Surgical-edit indent/format churn** — FIXED: prop edits replace only the
   changed arguments' VALUE expressions (byte-exact for all untouched formatting,
   including hand spacing and one-line style); prop add/remove falls back to a
   whole-list replace re-indented to the call's depth (single-line preserved);
   inserts land at sibling depth with proper leading whitespace. Byte-idempotency
   (edit + undo == original bytes) is unit-tested (WriteBackTest, 7 green) and was
   live-verified on the adopter app's ProfileScreen/ProfileLiteScreen.
2. ✅ **/undo** — re-diagnosed: undo always wrote the file. The observed failure
   was a SESSION mismatch (undo stacks are per-session; `/undo` without an
   `X-Portal-Session` header matching the ops envelope hits an empty stack and
   returns ok:false inside HTTP 200). Live-verified working. Follow-up UX trap
   (small): default undo to the last-writing session or return 4xx on empty stack.
3. ✅ **packageName** — FIXED: writeKotlin (and the Compiled_ sibling) derive the
   package from the EXISTING file; the constructor default is only a fallback.
   Severity confirmed in the wild: before the fix, a full-export fallback
   regenerated the adopter app's ProfileScreen into dev.keliver.portalpublished.screens
   and broke the guest compile. Follow-up: log when the non-surgical fallback
   fires so full regens are observable.

## P1 — Grammar & recognizer gaps (each found by porting a real screen)

4. ✅ **Literal action args** (DONE 2026-07-12): `{ b.open("ROUTE") }` / `{ b.pick(3) }`
   are grammar — the recognizer keeps the arg's SOURCE text (also matches the
   `{ _ -> ... }` exporter form), the exporter already emitted verbatim, and
   collectContract types the param from the literal (String/Int/Double/Boolean).
   Round-trip tested (RecognizerTest.literalArgActionsRoundTrip) + live-verified
   ingesting a probe screen in the first adopter app (0 RawCode).
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
   **2026-09-23:** the canvas check was never recorded, and the published 0.3.3
   editor still draws content-width rows (`REFERENCE_APP.md` finding 7). The
   "devices were unaffected" half has no device screenshot behind it for the
   reference app either.

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
    exporter bug. Live-verified on the adopter app's ProfileScreen: insert Repeat over
    a new field → guest build GREEN with 2 markers → undo → byte-identical.
    Follow-up: the publish verifier should reject bundles whose contract still
    carries TODO(portal) markers (drafts must not ship to prod).
10b. ✅ (mitigated 2026-07-16) **serve dies on compile error** — the adopter app's
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
    Settings = 3 SectionCard + 4 MenuRow, zero RawCode; the adopter app's Profile = five
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
    PROMOTED dist). External-app enablement is complete through a
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
16. ✅ **Capability vocabulary + personas + recorded HTTP** — COMPLETE
    2026-07-27.
    The publishable
    `keliver-capabilities` module provides versioned HostAuth/HostFlags/
    HostAnalytics contracts and fixtures; apps own named `PreviewPersona`
    catalogs, and the editor live-switches them with cold-start isolation and
    fidelity reporting. `HostHttp@1` now replays app-owned, privacy-linted
    fixture sets through a deterministic per-Live-session relay with no network
    fallback; the real Settings presenter was live-verified against a checked-in
    profile response. H2 adds an explicit loopback-only record mode with
    server-owned HTTPS upstream IDs, per-process authorization, environment-only
    auth injection, validated-and-pinned public DNS results, disabled redirects/
    proxies/cookies/retries, bounded text responses, redaction before return/
    hash/log/write, body-free audit, and candidate-only atomic files. Promotion
    remains a deliberate human-reviewed git action; replay never falls through
    to the network.
17. **@PortalComponent catalog codegen** — annotation → palette entry with typed
    props/thumbnail; the consumer app's design system becomes the palette.
18. **Presenter lint pack** — @Composable-presenter footguns (state in companions,
    LaunchedEffect misuse, shared state in presenters) enforced mechanically.

## Current priorities

**Ranked 2026-10-27**, by user impact, correctness, security, then dependency
order. Every item comes from the adoption findings above, `KNOWN_BUGS.md`, or
the 2026-10-27 documentation audit. No security or data-loss defect is open
(U35/U36 fixed in tools 0.3.11, U27/U29 in 0.3.6, U30 in 0.3.9).

1. **Ship what is merged: tools 0.3.13.** Adopters get none of the editor
   program yet (#140, #141), nor the production-host fix for a screen drawn
   twice after a resume update. Gate: `scripts/build-portal-tools.sh`, the
   adopter acceptance, the identity check, device verify, then the release
   procedure in `PORTAL_TOOLS_RELEASE.md`. It carries items 2–4 and 6.
   **Status 2026-10-27: released** (tools 0.3.13, tag → `a5ab6e2b2`, zip
   `5d1dd0ef…`; `RELEASE_NOTES_TOOLS_0.3.13.md`).
2. **A debug host picks up a publish while it is open** (adoption item 6's
   open row; owner's request 2026-10-26). The relay announces each stored
   bundle on `GET /bundles/events`; a debug host listens while a screen is on
   show and runs its ordinary, verified update check. Android first, then iOS.
   **Status 2026-10-27:** Android released in tools 0.3.13 (#145) and checked
   on a physical phone (a Galaxy A12, from the published 0.3.13 zip: a publish
   applied in the open app ≈ 1 s after the event, in the same process); iOS
   merged (#150, simulator LV1–LV3 green) and released in tools 0.3.14
   (2026-10-28). **2026-10-29:** checked on a physical iPhone (an iPhone 13 on
   the releasing machine's Wi-Fi, a debug host scaffolded from the published
   0.3.15 zip: a publish applied in the open app as soon as it returned).
   **2026-10-30 (#176):** a debug host whose first code load failed stayed
   unloaded until restarted, on the A12 and the iPhone 13; now its next update
   check loads it again in the same process (both templates; earlier revisions
   checked on both phones, the merged one by LV5 in CI). Released in tools 0.3.16 (2026-10-31).
3. **The bundled `keliver-new-screen.sh` and `keliver-new-component.sh` write
   into the tools bundle, not the app** (found by the 2026-10-27 audit): run
   from the zip's `bin/`, they resolve the app as the script's parent. Every
   adopter who follows `PORTAL_TOOLS_README.md` to add a screen hits it.
   **Status 2026-10-27:** fixed, merged (#142).
4. **U38: the browser keeps running a cached editor** after switching to an
   app's own one. Small; serve the editor with `Cache-Control: no-cache`.
   **Status 2026-10-27:** fixed, merged (#143: `no-store`); no browser re-run yet.
5. **A libraries release (0.4.1)** so per-app editors get #140/#141: the
   published `portal-editor`, `portal-core`, `keliver-material-composeui`
   changed. **Status 2026-10-27: released** (`v0.4.1` → `c5a7e8716`, on Maven
   Central; tools 0.3.13 scaffolds it).
6. **U28: a false `codeLoadFailed` on every production-host start.** It sends
   developers chasing a failure that did not happen. **Status 2026-10-27:**
   fixed on both generic hosts, merged (#146); not run on a device.
7. **Brownfield host kit** (adoption item 3): the glue each embed needed (a
   fallback hook, a status API, user-safe states, a request allow-list hook).
   The proof's serious defects were all in that glue.
   **Status 2026-10-28:** K1 on Android (#155, #158, #159, #161) and K2 on
   iOS (#160, #162) complete in code and **released in tools 0.3.14**;
   `screen-failed` on iOS merged (#165, released in tools 0.3.15). K4, the device
   proof, ran on the CI emulator and simulator against the templates after
   0.3.14 (#165: routes, close, `screen-failed`, the app's fallback), not
   against the 0.3.14 bytes. **2026-10-29:** the same checks passed against
   the published 0.3.15 hosts (#171's hand runs: X8–X10 on the emulator,
   I6–I8 on the simulator). **2026-10-29 (#174):** the Android host also on a phone: X6 and X8–X10
   passed on a Galaxy A12 with the published 0.3.15 host; not yet on an iPhone. K3, the
   dependency-floor report, merged (#168): `--embed`
   prints the module's floor beside the app's declared versions before it
   writes, checked in CI on an app on older libraries. Released in tools
   0.3.15.
8. **Bridge generator / HostHttp through the app's client** (adoption item 4).
   **Status 2026-10-29:** B1 merged (#169): on Android,
   guests' HostHttp goes through the app's own authenticated client behind the
   `RequestPolicy`; released in tools 0.3.15. **2026-10-31:** B2 merged
   (#178): on iOS, a header callback adds the app's credentials, behind the
   same policy, with B1's rules (20 simulator tests); released in tools
   0.3.16. B3 to B5
   not started.
9. **Start-up** (adoption item 2): M3 released (#179, tools 0.3.16, 2026-10-31); then M4
   (guest frame profile) and M5 (iOS memory).
10. **Device state catalogue and snapshot runner** (adoption item 5); bundle
    delivery (item 7); the device proxy (item 6's last row).
11. **W7, the physical-device proof** (a real-certificate HTTPS server, a
    release-signed APK and IPA, an iPhone): needs the owner's hardware and
    signing team.
12. U19, U20 (editor re-render and frame rate), then the depth candidates.

### Priorities of 2026-09-23 (superseded)

Refreshed 2026-09-23 from the reference-app run (`docs/REFERENCE_APP.md`), the
0.3.5 release, and the open defects. Status 2026-10-27: items 1–4 are done
(tools 0.3.6–0.3.9: scaffolded Android and iOS production hosts, scaffolded
publishing with Production bundles by default, U27/U29, #77's scaffolded-host
fix, U30); item 6's physical Android device is done (S25 Ultra, tools 0.3.10;
a Galaxy A12 in 2026-10), an iPhone run of the reference app is not; items 5,
7 (in part), 8 and 9 remain and are folded into the list above.

1. **An adopter cannot ship to production without a Keliver checkout.** The
   first reference app, developed only from the published tools and Maven
   Central, reached production OTA only by building `portal-device-android` from
   the release's source commit. The bundle's `host/README.md` sends adopters to
   `sample/host-android`, which renders the *sample's* widget schema, not
   keliver-material. No host that renders an adopter's screens with signature
   verification is published. **Decided 2026-09-30: a scaffolded host app**
   (`docs/PRODUCTION_HOST_FEASIBILITY.md`, option B). **Shipped in tools 0.3.6:**
   `keliver-new-production-host.sh` writes `host-android/`, production-only, on
   Maven Central only; the reference app's P1–P7 pass on it, P7 being an
   offline start from Zipline's verified cache (CI run `37505534432`). **iOS (shipped in tools 0.3.7):**
   `keliver-new-ios-host.sh` writes `host-ios/` with the same guarantees, and iOS P1, P2 and P4–P7 pass on a
   simulator in CI (`ios-host.yml` run `37597390396`). What remains: physical devices over HTTPS, and
   release-signed builds. The plan of record is `docs/DELIVERY_PLAN.md`.
2. **Publishing was not scaffolded.** `POST /publish` runs `publishTask`, which
   defaults to Keliver's own `:portal-published-guest:…`; a scaffolded app had
   no `publishTask`/`publishOutput` and no signing block, so publish failed, and
   the adopter guide did not mention either. **Shipped in tools 0.3.6 (#86):**
   `keliver-new-publish-target.sh` writes both settings and the signing block,
   and the relay now refuses to store a bundle whose manifest does not verify
   against the store's key (portal-tools `37513071473`; reference-app
   `37513078292`, P1–P7 on scaffolded signing). Left: the production (minified)
   bundle variant. The reference app shows the
   minimum that works (`reference/inventory/app/build.gradle`, bottom). Small,
   and it unblocks item 1's "does it work" question for every adopter.
3. **The relay writes the private signing key world-readable** (KNOWN_BUGS U27:
   `0644` under a default umask, measured on macOS and Linux; `ensureKeys()`
   uses `File.writeText`). **Fixed in tools 0.3.6** (with U29, half a key pair
   silently regenerated): new keys are owner-only from creation, and an existing
   exposed key is reported and refused for publishing but never changed.
   Alongside it, U28: the production host logs a
   false `codeLoadFailed` on every start.
4. **#77 — the iOS host's key generation is not hardened like Android's.**
   Reproduced, then **fixed** (tools 0.3.6): `generatePortalKey` empties its
   directory without following links, fails loudly on anything it cannot
   delete, checks the result and runs every time. Measured on a warm debug
   simulator framework: the planted source no longer reaches the klib or the
   framework, and the key constant is unchanged
   (`superpowers/evidence/issue-77/`). Release and `iosArm64` not measured; the
   missing `devOnlyHost` short-circuit on iOS is unchanged. The review found the
   same deletion flaw in Android's `syncPortalKey` (U30, open).
5. **U19 (live-preview re-render; cause unresolved) and U20 (editor frame rate;
   measured, not assessed)** — open. The reference app's Live preview passed its
   21 checks (14 of them canvas taps), which read the canvas and the State
   Inspector; its Bindings panel lagged one update in every capture
   (`docs/REFERENCE_APP.md` finding 9). Evidence about this app, not a
   resolution of U19.
6. **Device coverage is one emulator.** Every device result for 0.3.5 and the
   reference app is Android API 33 x86_64 on CI. No physical Android device, no
   arm64 execution, and no iOS run of the reference app.
7. **Adopter ergonomics found by the reference app** — each small, each real:
   the device scaffolder wires exactly one screen and presenter (a navigating
   app hand-edits `device/Main.kt`); the first `editor/` build that
   `keliver-portal` triggers took 5 minutes on a fresh Gradle home, with no
   progress shown; editor-canvas ListItems render at content width.
8. **An external adopter.** Not an engineering task and not something to do
   from here, but it is the thesis. Items 1–2 are what a first adopter would
   hit first.
9. Depth candidates, **after** 1–3: presenter/FlowScope linting, transparent
   local composables, component metadata/thumbnails. K2b API convergence remains
   a separately versioned decision.

### Completed priorities (2026-07)

1. ✅ **Bounded preview hardening and tri-platform verification** — complete
   2026-07-23; evidence and the pre-change baseline are in `CURRENT_STATE.md`.
2. ✅ **Project Components v2 — one required content slot** — complete and tri-platform
   verified 2026-07-23; evidence is in `CURRENT_STATE.md` and item 11c.
3. ✅ **Editor/flow distribution productization** — DONE + LOCALLY PROVEN
   2026-07-24 (f1a85a36a, b8697b7b6, first adopter app d8ebe46; design + release
   checklist in docs/superpowers/specs/2026-07-24-editor-distribution-design.md).
   The 7 artifacts a consumer editor needs (portal-core/-document/-render/-flow/
   -editor + the renamed keliver-material-protocol-{host,guest}-web) are now
   publishable: explicit API, committed apiDump, apiCheck green. PROOF: the adopter app
   DELETED its composite build and its editor compiled, built a wasm dist, and
   ran in-browser against dev.keliver:*:0.3.1-SNAPSHOT from mavenLocal with the
   real presenter + slotted SectionCard. The Maven Central push is IRREVERSIBLE
   and remains user-triggered — checklist in the design doc.
4. ✅ **Preview↔device semantic parity gate (K1)** — COMPLETE 2026-07-26.
   Shared Kotlin fixtures + checked-in trees bridge the JVM recognizer to Wasm
   compiled/interpreted `WidgetValue` comparison in Chrome. Coverage includes
   control flow, components/slots/nesting, modifiers, repeat cardinality, and
   action arguments. The gate found and fixed a false component-cycle diagnosis
   for wrapper reuse from slot content.
5. ✅ **Current layout contract (K2a)** — COMPLETE 2026-07-26 without API
   changes or deprecations. `docs/LAYOUT_CONTRACT.md` distinguishes container
   Constraint, Yoga child layout, ordered universal modifiers, and the two
   widget-specific `fillWidth` properties. Direct Compose/Yoga measurement tests
   pin Wrap/Fill, boundedness/precedence, stretch, and modifier order.
6. ✅ **Named personas and capability fixtures** — COMPLETE 2026-07-27.
   Apps declare typed, named fixtures; the editor persists selection, exposes
   fixture fidelity, and cold-starts real presenters/flows on change. The
   dogfood settings presenter and persona-scoped Field Notes flow were
   live-verified on web; Android and iOS guest paths were rebuilt and rendered.
7. ✅ **Deterministic replay + secure capture (#16)** — COMPLETE 2026-07-27.
   `HostHttp@1`, app-owned fixture catalogs, canonical matching, duplicate
   sequencing, session isolation, expiry/privacy validation, fail-closed relay
   endpoints, editor fidelity, and Android/iOS host adapters are delivered.
   The Field Researcher persona visibly rendered `Maya Chen · recorded API`
   through the real presenter. H2's opt-in recorder writes only redacted
   candidates and cannot promote them or become a replay fallback.
8. ✅ **Explicit runtime-version metadata (K4)** — COMPLETE 2026-07-27.
   The running editor reports its build-embedded Keliver/widget versions and
   compares them with the app-owned `keliver.portal.json` declaration from the
   relay. Match, SemVer skew, widget mismatch, missing, and unavailable states
   are explicit in mock and Live fidelity; no Gradle parsing is involved.
9. ✅ **Scripted tri-platform layout evidence (K3)** — COMPLETE 2026-07-27.
   One recognized kitchen-sink tree now has committed web, Pixel 9, and iPhone
   16 Pro evidence plus a machine-readable manifest. The repeatable runner owns
   builds, services, device state, guest-load/error checks, screenshot/content
   validation, and relay-state restoration. Delivery fixed iOS safe-area
   handling and hardened Wasm/headless evidence capture.

## P4 — Platform debt (tracked, not urgent)

19. movableContent quarantined tests (KNOWN_BUGS U14).
20. U1: suspend Zipline fn returning `List<@Serializable>` hangs at bind — fix or
    document permanently in the shapes plugin lint.
21. @Modifier lambdas broken on JS (widget @Property events only).
22. WebSocket code-push for the web target (dev experience only; runtime is local).
23. ~~keliver-portal launcher: fold the first adopter app's `scripts/dev.sh` improvements
    back into the bundled launcher (health-wait, stop, editor auto-build).~~
    **Done 2026-09-05.** The first adopter app was gone, so the behaviour was
    re-derived from `scripts/keliver-dev.sh` rather than ported. `keliver-portal`
    now health-waits on `/devstate` (failing fast with the server log instead of
    a blind `sleep 3`), gains `stop`/`status` subcommands backed by per-app-dir
    run state, refuses to double-start, builds and serves the app's own
    `editor/` when present (falling back to the bundled editor on build
    failure), and supports `--rerun-tasks` / `--no-editor-build`. Also fixed a
    pre-existing 100%-CPU busy-spin in **both** `keliver-portal` and
    `keliver-dev.sh`: `while true; do wait || break; done` never breaks once the
    last child exits, because `wait` then returns 0 immediately.
    **This closes the M0 "deterministic preview" milestone** — live-preview
    issues 1–5 were closed by the 2026-07-23 consolidation pass.

## Recently completed (context for readers)

- 2026-10 (newest first):
  - the editor program #140/#141 (adoption item 8's status);
  - tools 0.3.10–0.3.12 and libraries 0.4.0 (the grammar and canvas work,
    with the compiled component preview);
  - adoption items 1, 2 (M0–M2) and 6 (most rows): see their status lines;
  - libraries 0.3.4.

- 0.3.1 on Maven Central (2026-07-27), including the five publishable `portal-*`
  editor artifacts — consumer editors no longer need a Keliver checkout.
- 0.3.0 on Maven Central; keliver-init scaffolder; portal tools bundle (R1–R6).
- First adopter app: real app on Android + iOS + web from one guest source;
  Profile ported to Style B with 0 RawCode; hot reload verified ~15–25s (S1–S6).
- Application-scale architecture review: presenter/bindings seam holds; missing
  scopes (flow, transparent components, capability breadth) identified → P3 above.
