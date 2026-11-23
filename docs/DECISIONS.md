# Keliver — Decision Register

Finalized decisions with rationale. Each is **settled** — reopen only with new
evidence, in a PR against this file. Newest architectural review: 2026-07-12
(application-scale assessment against the first adopter app). Latest amendment:
2026-11-24 (D6 and D9, after Gate B).

## Runtime & distribution

**D1. Guest code is compiled Kotlin, not interpreted JSON.**
Screens + logic compile to Kotlin/JS and run in Zipline (QuickJS) on device.
Rationale: logic executes identically on Android/iOS/web; no per-platform
reimplementation; no interpreter capability ceiling. The WidgetNode/RenderNode
interpreter exists ONLY as the portal's authoring/preview overlay — production is
always compiled code (SOTA decision, P4).

**D2. Production bundles are compiled, Ed25519-SIGNED, versioned, compat-gated.**
`/publish` → gradle compile → sign → versioned store; hosts read the static
`bundles/index.json` (W3, tools 0.3.8 on; any static server, or the relay),
falling back to `/bundles/latest?widgetVersion=W&caps=…` on a relay from tools
0.3.7 or earlier. A bundle never reaches a host that lacks
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
*Amended 2026-11-24 (Gate B), for the Compose-generated guest API and the editor's
ED0:* for a generated group (`dev.keliver.compose.*`), the grammar also reads
Compose-shaped code. It reads **Compose values from a fixed table** (units such as
`16.dp`, colours and theme references such as `MaterialTheme.colorScheme.primary`,
shapes, text styles, alignments and arrangements, padding values, icons), matched by
their spelling, not by their resolved type or import. It reads a **`Modifier` chain as
an ordered list** of generated modifiers. A call is a generated widget only when the
file's imports name it. In a file that imports a guest name, a call is RawCode with its
reason when its resolution is not certain: two star imports that both give it, a
`dev.keliver.compose.*` name the catalog does not generate, Compose's own API, or a
declaration of the same file that shadows it. Any other callee keeps today's reading,
so legacy widgets and project components still mix with generated ones. The table
grows by entries, never by evaluating Kotlin. Today's schemas ingest as before. The
core is unchanged: logic executes, it is not parsed, and unrecognized code stays an
explicit RawCode node. Evidence: Gate A ([`GATE_A_VERDICT.md`](GATE_A_VERDICT.md)),
Gate B ([`GATE_B_RESULTS.md`](GATE_B_RESULTS.md)) and the ED0 spike (#239,
[`ED0_SPIKE.md`](ED0_SPIKE.md): Gate A's sample ingests with 0 RawCode; 16 write-back
round trips are byte-stable). Agreed by the owner with Gate B's verdict, 2026-11-24.

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
document nodes while the wrapper stays master-linked. For hand-written components,
optional/defaulted slots and multiple named slots remain deferred rather than being
inferred ambiguously.
*Amended 2026-11-24 (Gate B), for the Compose-generated guest API and the editor's
ED0:* a generated widget may have Material 3's **optional and several named slots**.
This needs a change to the document model's shape ([`ED0_SPIKE.md`](ED0_SPIKE.md) §2):
a Compose node stays a widget node, and a node whose widget has exactly one required
slot keeps today's shape (the slot's statements are its children). Any other slot
layout has one `slot:<name>` child per slot the call passes, in source order; an
absent optional slot has none. Hand-written components keep the one-required-slot
rule above. The core is unchanged: the grammar owns composition and layout. Evidence
and the owner's agreement: as D6's amendment.

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

**D15. Docs layout:** platform decisions/roadmap here (this repository's `docs/`); each
consumer app repo carries its own CLAUDE.md + ARCHITECTURE.md + WORKFLOW.md so
agents and new engineers onboard without oral history. `reference/inventory` is the
runnable reference today (the first adopter app, which this decision was
written for, was lost on 2026-09-05; amended 2026-10-27).
