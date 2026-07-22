# Keliver — Decision Register

Finalized decisions with rationale. Each is **settled** — reopen only with new
evidence, in a PR against this file. Newest architectural review: 2026-07-12
(application-scale assessment against the Stashfin adoption).

## Runtime & distribution

**D1. Guest code is compiled Kotlin, not interpreted JSON.**
Screens + logic compile to Kotlin/JS and run in Zipline (QuickJS) on device.
Rationale: logic executes identically on Android/iOS/web; no per-platform
reimplementation; no interpreter capability ceiling. The WidgetNode/RenderNode
interpreter exists ONLY as the portal's authoring/preview overlay — production is
always compiled code (SOTA decision, P4).

**D2. Production bundles are compiled, Ed25519-SIGNED, versioned, compat-gated.**
`/publish` → gradle compile → sign → versioned store; hosts fetch
`/bundles/latest?widgetVersion=W&caps=…`. A bundle never reaches a host that lacks
its widget version or capabilities. Tamper → codeLoadFailed. (P4, M7.)

**D3. Deployment unit = feature bundle** (10–25 screens), not screen, not app.
Per-feature blast radius, rollout %, and squad ownership. Guest "app-scope" is
therefore bundle-scope — anything spanning bundles must be a host capability.

## Authoring & the portal

**D4. The .kt files in the app repo are the single source of truth.**
The portal ingests Kotlin (PSI recognizer) and writes back surgically; git is the
merge/review/history model. No design database. (V2 M3/M4.)
Corollary: **byte-stable, minimal-diff write-back is a trust-critical release
gate**, not a nice-to-have — noisy diffs kill engineer adoption.

**D5. Style B is the screen unit: Screen(bindings) + Presenter + derived contract.**
The bindings interface is the machine-checkable seam serving the portal (mocks),
tests (fakes), and codegen simultaneously. Style A (inline) is a demo escape
hatch, not for production screens.

**D6. Execute logic, don't parse it.**
The grammar covers UI structure only (widgets, literal/bind props, Repeat,
Condition, the three event shapes). Complex Kotlin is never parsed into the
grammar — it runs: on devices always, in the browser via the live-presenter tier.
Unrecognized code becomes an explicit RawCode node (loud, red in preview) rather
than a wrong rendering. **Keep the grammar small**; variety belongs in components.

**D7. Preview fidelity is capability-scoped (M8).**
Repositories/presenters always run REAL in preview; only the narrow capability
layer (http, sql, …) gets preview substitutes. Fidelity is emergent from the
app's capability graph and reported honestly. Cost scales O(capabilities), never
O(screens). Per-dependency Real/Preview/Mock matrices are rejected.

**D8. Navigation: portal-aware as DATA, not as an authoring surface.**
Route graphs get derived from typed route contracts + navigator call sites and
displayed/previewed; routes are edited in Kotlin. Cross-feature nav goes through
the host's existing deep-link vocabulary; intra-flow nav is guest state.

**D9. Design systems integrate as metadata (component catalog), not migration.**
Hand-written composables register into the palette with typed props (transparent =
grammar inside, portal can descend; opaque = props-only). The grammar owns
composition/layout forever — if layout goes opaque, the visual-editing thesis dies.
Components may expose one required trailing `@Composable () -> Unit` content
slot, invoked once inside a grammar container; its children remain ordinary
document nodes while the wrapper stays master-linked. Optional/defaulted slots
and multiple named slots remain deferred rather than being inferred ambiguously.

## App architecture (from the 2026-07-12 application-scale review)

**D10. State placement:** spans/outlives a bundle → host capability; within a
bundle → guest app-scope built in `main()`; within a screen → presenter. Flows
(multi-screen drafts) get a flow-scope presenter composing screen presenters.

**D11. DI = single composition root per bundle:** `createAppGraph(capabilities)`.
Production, preview, and tests differ only in the capability set. Manual DI;
kotlin-inject optional; never Hilt/Dagger guest-side (JVM-only).

**D12. Migration sequencing for real apps:** one screen behind a flag first, then
whole FLOWS (half-migrated flows multiply native↔keliver boundary crossings);
phase-one guests consume capabilities ADAPTING existing native repositories —
never two sources of truth for one entity.

**D13. Host bridge pattern:** narrow generic transports bound once
(`HostHttpProvider`, actions, domain data providers); guests own endpoints,
types, parsing. Suspend services must not return `List<@Serializable>` (U1 hang)
— wrap in a single type.

## Meta

**D14. Verification bar for any screen/portal work:** compile green → ingest with
0 RawCode (for grammar screens) → device render screenshot → surgical write-back
round-trip. Claims without these four are not "done".

**D15. Docs layout:** platform decisions/roadmap here (`konduit/docs`); each
consumer app repo carries its own CLAUDE.md + ARCHITECTURE.md + WORKFLOW.md so
agents and new engineers onboard without oral history. stashfin-sdui is the
reference adoption.
