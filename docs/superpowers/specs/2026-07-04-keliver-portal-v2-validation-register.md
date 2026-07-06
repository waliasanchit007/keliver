# Portal V2 — Engineering Validation Register

**Date:** 2026-07-04
**Status:** Architecture FROZEN (per `2026-07-04-keliver-portal-v2-bidirectional-design.md`
+ design-review amendments). This register eliminates implementation unknowns
before/alongside the milestone work. No redesign here — risk classification only.

## Design-review amendments folded into the frozen architecture

1. Write-back primitive = **PSI subtree replacement** on a fresh parse (spans demoted
   to lookup hints; no offset-shifting state machine).
2. Subset gains **Condition(bind)** and **Repeat(itemsBind)** semantic nodes in M5
   (`if (b.x) {…}` / `b.items.forEach {…}` — lists + conditionals are portal-editable).
3. Op positions are **sibling-anchored** (`after: Handle?`, not integer index) + ops carry
   an **envelope** (session, timestamp, batch label).
4. **Capability gating**: bundles declare required host services (name+version) in publish
   metadata; `/bundles/latest` gates on them like `widgetVersion`.
5. Guest data API = **SQLDelight-over-Zipline** (typed queries OTA) atop the dumb
   `HostSqlDriver` wire (execute/executeBatch/transaction).
6. portal-mcp adds `apply_ops(dryRun)`, `list_screens`/`find_usages`, and (post-M8)
   `execute_action` behavioral verification.
7. Overlay runtime clarified: ONE Zipline instance/bundle — interpreter is a library
   inside the dev guest; "overlay" is a routing decision with versioned catch-up.
8. RawCode nodes get a display-only `kindHint` (condition-ish/loop-ish/effect-ish).

## Classification

### ✅ Proven (evidence in this repo)
- Browser/wasm execution (V1 verified end-to-end; 4.4 MB gz measured)
- Runtime interchange + generated interpreter (web/Android/iOS)
- Publish pipeline: compile→sign→version→gate→tamper-reject (P4 runtime-verified)
- Zipline hot-reload push (`--continuous`, Android + iOS)
- Suspend host services over Zipline (keliver-http on-device; U1 dodge known)
- Headless in-process compiler use (portal-schema-codegen FIR parsing)
- Op engine mechanics, SSE, MCP surface (standard tech + V1 EditTree seed)
- Capability gating (same mechanism as verified widgetVersion gating)

### 🟡 Assumed (validate inside owning milestone)
- Identity reconciliation quality (property harness + diff corpus in M3;
  blast radius = UX-only)
- Condition/Repeat recognizer patterns (gated on S1; corpus in M5)
- RawCode byte-preservation (success criterion of S1/S2)
- Overlay router + versioned catch-up (components proven; latency via S0)
- One-JVM portal-server (PSI + SSE + ProcessBuilder gradle; classpath isolation
  watch item in S1)
- Memory envelope (modules=40 loads fine; PSI JVM = dev-tool-acceptable; monitor)
- File-watch latency (JDK WatchService on macOS = ~2s polling — use a native-events
  watcher lib; verify in S0)

### 🔬 Needs Spike

**S0 — Measurement pass (~½ day, first).** Warm incremental guest JS compile time
(the 3–10s overlay assumption), native file-watcher lib latency, parse-time baseline.

**S1 — Headless PSI ingest (1–2d).**
Assumption: KtFile PSI from source in plain JVM, recognizer walk without resolution,
<500ms warm. Fail modes: KotlinCoreEnvironment setup outside IDEA; embeddable version
pinning; import-alias ambiguity. Prototype: standalone main parses a realistic
CheckoutScreen.kt → recognized tree + verbatim RawCode spans. Success: correct tree,
byte-exact raw spans, <500ms warm, runs in portal-server JVM. Fallbacks (impl-only):
Analysis-API standalone, or FIR (already proven in-repo).

**S2 — PSI subtree write-back (1–2d, after S1).**
Assumption: KtPsiFactory node replacement serializes byte-identical outside the touched
node, headlessly (no CodeStyleManager dependence). Prototype: replace one Button call;
then 10-file round-trip corpus (comments, odd formatting, RawCode) + random-op property
test (ops → write → re-ingest → Document equality). Fallback (impl-only): PSI locates
node boundaries, text spliced at FRESH offsets (no stale-span machinery).

**S3 — SQLDelight-over-Zipline (1–2d, parallel).**
Assumption: SQLDelight 2.x generated common code runs in the Zipline JS guest over a
custom suspending SqlDriver bridging HostSqlDriver; rows cross as one serializable
payload (U1-safe); latency fine; transactions map. Prototype: one table+query, custom
driver → fake host on JVM SQLite, end-to-end in a Zipline guest. Fallback (impl-only):
hand-rolled typed helpers over the same wire — the wire IS the frozen contract.

**S4 — Live Presenter wasm loop (2d, parallel; uses S0 numbers).**
Assumption: project logic compiles to wasm on-idle and (re)loads into the running
preview; loop = seconds. Watch: wasmJs link times; dynamic module reload; SQLDelight has
no wasmJs driver today (browser tier may use in-memory fake — decide from data).
Prototype: Workouts-style presenter as wasm module driving a Bindings screen; measure
edit→result. Success: ≤15s cold / ≤8s warm. Fallback (impl-only): Tier-2 presenters run
JVM-side in portal-server, state streamed to preview (tier UX survives).

### ⏳ Deferred
Multi-user/CRDT; branch-workspaces + PR previews; IntelliJ plugin (M10); declarative
data sources (V3); scale beyond local-first single team; QuickJS ceiling (monitor).

## Risk register (high → low)
1. S2 write-back fidelity (only file-corrupting risk; dignified fallback)
2. S4 live-presenter loop latency (tier value at stake)
3. S1 headless PSI environment (gates M3–M5; two fallbacks)
4. S3 SQL driver impedance (wire survives failure)
5. Incremental compile latency (S0; overlay UX promise)
6. Identity reconciliation (UX-grade)
7. File-watcher latency (lib choice)
8. PSI-in-server classpath isolation
9. Memory growth (monitor)

## Spike roadmap
```
Week 1: S0 (½d) → S1 (1–2d) → S2 (1–2d)   ← critical path
        S3 (1–2d) parallel
        S4 (2d)   parallel
M1 (Document+ops) + M2 (portal-mcp) are spike-independent → start immediately.
```

## Architecture freeze checklist
| Subsystem | Status |
|---|---|
| Document model + identity + ops (M1) | ✅ Ready |
| portal-mcp (M2) | ✅ Ready |
| Publish changes + capability gating | ✅ Ready |
| Interchange/interpreter/preview | ✅ Ready (unchanged) |
| Kotlin ingest (M3) | 🔬 S1 first |
| Write-back (M4) | 🔬 S2 first |
| Condition/Repeat + RawCode (M5) | 🔬 gated S1/S2 (design ✅) |
| App project model (M6) | ✅ Ready |
| Data layer (M7) | 🔬 S3 first (wire frozen) |
| Live Presenter (M8) | 🔬 S4 first (tier UX frozen) |
| Overlay runtime (M9) | 🟡 S0 then ready |
| IntelliJ plugin (M10) | ⏳ Deferred |

Nothing on the spike list can force an architectural change — every failure mode lands
on a named implementation fallback.

---

## SPIKE RESULTS (2026-07-04 — ALL PASS, architecture confirmed frozen)

- **S0:** warm incremental guest zipline compile 8.2–10s (band holds; --continuous
  shaves ~2s gradle overhead). Watcher: io.methvin:directory-watcher:0.19.0 (JDK
  WatchService on macOS = 2s polling, rejected). Parser env must be resident.
- **S1 PASS:** headless PSI ingest — env boot 357ms once, cold 138ms, **warm 1ms**
  (budget 500ms). Recognizer extracts widgets/args/binds/actions/modifiers/children;
  unknown statements RAW byte-exact. Gotchas: embeddable shades com.intellij.* →
  org.jetbrains.kotlin.com.intellij.*; body via `bodyExpression as KtBlockExpression`.
- **S2 PASS (#1 risk retired):** PSI subtree mutation headless works with the ktlint
  recipe — rootArea EPs (treeCopyHandler, psi.treeChangePreprocessor,
  smartPointerAnchorProvider, jvm.elementProvider) + pass-through PomModel service
  (TreeAspect). Replace/insert/delete byte-identical outside touched nodes; idempotent.
- **S3 PASS:** SQLDelight 2.1.0 runtime + async-extensions on Kotlin/JS with a custom
  suspending SqlDriver over the single-payload wire — typed insert+select green on
  ChromeHeadless. M7: transactions→executeBatch; gradle plugin = standard usage.
- **S4 PASS (latency note):** presenter logic + JSON state/action channel runs in wasm
  (ChromeHeadless 1/1). Warm incremental wasm dist 8.7–9.1s — marginal vs the 8s warm
  target with ~2s gradle overhead included; within the 15s budget; --continuous or a
  resident build service closes the gap. Side-by-side bundle loading = M8 integration
  (JVM-side execution fallback stands if needed).

**Freeze checklist update: every 🔬 subsystem → ✅ Ready for implementation.**
Spike code: portal-schema-codegen/src/main/kotlin/.../spike/ (tasks runPsiSpike,
runWriteBackSpike), :portal-sql-spike:jsTest, :portal-presenter-spike:wasmJsTest.

## MILESTONE LOG
- **M1 SHIPPED (`5d64de87a`):** portal-document (model+ops+inverses+projection, 10 tests),
  DocumentService (409/422, per-session undo/redo, SSE, draft projection, .kt dual-write),
  editor = op client (handleIds projection kept all panels unchanged). Browser-verified.
- **M2 SHIPPED:** /ops dryRun + portal-mcp stdio server (10 tools: get_catalog/get_guide/
  list_projects/list_screens/get_document/apply_ops(dryRun)/undo/redo/find_usages/
  device_screenshot). Scripted agent session verified end to end (catalog 60w/19m,
  steering dryRun error, insert v1, find_usages by handle, undo reverts).
- **M3 SHIPPED:** portal-ingest (resident PsiEnv + catalog-grounded Recognizer +
  handle-preserving Reconciler; 3/3 tests incl. THE exporter round-trip gate) +
  fsevents watcher in portal-server (300ms debounce, self-write suppression,
  acceptExternal = new baseline: no undo entry, no .kt rewrite). VERIFIED live:
  sed the .kt -> ingest v(N+1) -> /doc + device /tree + OPEN EDITOR updated with
  NO reload (SSE + reconnect + 5s version-poll fallback). .kt = source of truth.
- **M4 SHIPPED:** surgical PSI write-back — NodeEmitter (single-node canonical text via
  export+PSI-lift) + WriteBack.merge (re-parse file, shape-match parsed↔target, replace
  only changed argument lists / insert-delete only changed child statements; reorder or
  contract change → null → full-export fallback). DocumentService.writeKotlin tries merge
  first. 4 tests (comment+RawCode preserved on prop edit, insert/delete surgical,
  merged output re-ingests to target). E2E verified: hand comment SURVIVED a /ops prop
  edit (paddingDp=40 applied surgically) — comment survival proves the PSI path ran.
- **M5 SHIPPED:** Condition/Repeat semantic nodes — lists + conditionals are portal-EDITABLE
  (not RawCode). Reserved widget types handled by hand-written branches in the emitter
  templates (regeneration-safe): catalog (synthetic "Logic" specs), interpreter (passthrough
  Column preview), exporter (real `if (b.field) {}` / `b.items.forEach { item -> }` + typed
  contract fields Boolean/List<String>). Recognizer parses KtIfExpression (no-else) →
  Condition and `b.x.forEach { item -> }` → Repeat; else → RawCode. WriteBack handles logic
  nodes (props change → full regen, else recurse the if/forEach block). 9/9 ingest tests
  (incl. recognize + export round-trip). E2E: a .kt with if/forEach ingested to editable
  Condition/Repeat, shown in the editor outline + rendered in preview (palette now 62).
- **M6 SHIPPED:** app project model — canonical screens live IN the guest Gradle module
  (portal-published-guest/src/jsMain/kotlin/screens/<screen>.kt, git-versioned; file name =
  screen id, fn = <Screen>Screen), logic/ + PublishedEntry hand-owned (presenter =
  @Composable fun MainPresenter(): MainScreenBindings, Style-B unification), portal-server
  maps project "default" → the repo screens dir (legacy ~/.keliver-portal/kotlin fallback),
  watches BOTH, docFor bootstraps FILE-FIRST (recognize the .kt incl. contract; draft-lift
  fallback), publish compiles the canonical project AS-IS (export step + generated/ dir
  DELETED; meta srcHash = canonical source hash). VERIFIED: portal op → ONE-LINE surgical
  git diff (cornerRadiusDp 12→24) with hand comment intact; repo-file sed → live doc +
  device /tree; publish → signed v3 bundle containing the fresh source + presenter.
- **M7 SHIPPED:** data layer over the S3-proven wire. New `portal-sql` module (jvm+js, zipline
  IR plugin) — HostSqlDriver : ZiplineService (execute + executeBatch/transaction, single-
  payload SqlRows = U1-safe) + guest PortalSqlDriver (SQLDelight driver) + FakeSqlHost. Android
  host binds AndroidSqlHost (real SQLite, one DB file). App logic/: TapStore (guest-owned
  schema+queries, ships OTA) + MainPresenter persists taps. Capability gating: capabilities.txt
  beside screens/ -> publish meta "capabilities":["HostSqlDriver@1"]; /bundles/latest?...&caps=
  gates (host without cap -> older v3, host with cap -> v4). VERIFIED: portal-sql jvmTest 2/2
  (typed query + batch tx); gating BOTH ways by curl; guest bundle links (needed the zipline
  plugin on the service-defining module — else 'unbound HostSqlDriver.Companion.Adapter');
  Android host + all guests compile. Device-runtime persistence folded into the M9 device pass.
- **M8 SHIPPED (user reframe: capability-driven fidelity, not mock-vs-real modes):** the
  browser preview runs REAL logic and auto-substitutes a preview impl per host capability
  (HostSqlDriver@1 -> in-memory SQLite); capabilities without a preview impl are STUBBED and
  flagged reduced-fidelity. Fidelity is EMERGENT from the app's capability graph. New:
  /capabilities endpoint (reads capabilities.txt), PreviewCapabilities registry + PreviewSqlHost,
  LivePresenter (runs the real data path -> writes PreviewBindings.mocks), Fidelity panel +
  State Inspector + ▶ Live toggle + per-action ⚡ DOM triggers. VERIFIED in Chrome: Live ->
  "✅ Full fidelity · HostSqlDriver@1 preview impl", bound canvas text = presenter output;
  ⚡ buyTapped -> real INSERT into in-memory SQL -> recount -> canvas rebinds to
  "buyTapped ×1 — persisted in SQLite (preview impl)" + console logs. Convergence note:
  running arbitrary per-project presenter Kotlin (vs the reference presenter) = the per-app
  preview build; the capability-fidelity MODEL is the project-agnostic M8 deliverable.
- **M9 SHIPPED + EMULATOR-VERIFIED:** overlay dev runtime. Extracted the app's screens/ +
  logic/ + PublishedEntry into a shared library portal-app-lib (breaks the published<->device
  interface cycle). The dev guest becomes the OVERLAY runtime: compiled screen (real
  MainPresenter + SQLite) primary; when the active screen's live doc version > the bundle's
  baked COMPILED_VERSION, overlay the interpreter (RenderNode over the live tree) with a badge;
  rebuilding the bundle bakes the new version and the router auto-discards the overlay (versioned
  catch-up). One Zipline instance. Server /devstate exposes the live version; writeKotlin bakes
  Compiled_<screen>.kt. Verified on Pixel_9 (modules=43): compiled MainScreen -> edit -> live
  overlay v1 (compiled v0) -> rebuild -> caught up to compiled. Folds in the M7 device-persistence
  check (compiled path uses on-device SQLite). Regression clean (M6 publish v5 + M7 gating). iOS
  parity: IosSqlHost bound + portal-sql iOS targets; host framework compiles (runtime follows the
  shared bundle). M10 (IntelliJ plugin) = the one deferred item.

## FINAL STATUS: V2 M1–M9 COMPLETE. Full comprehensive sweep GREEN (all portal modules:
## tests + jvm/js/wasm/android/ios compiles + both zipline bundles). M10 deferred by design.
