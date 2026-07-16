# Keliver — Roadmap & improvement backlog

Ordered by (impact on the adoption thesis) × (how soon it bites). Every item lists
its evidence — nothing here is speculative. Status: 2026-07-12, after the Stashfin
tri-platform loop + Profile Style-B port.

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

11. **Canvas click-to-select** (design locked): `SelectionTag(handle)` modifier →
    ComposeUi host impl does onGloballyPositioned → SelectionRegistry → editor
    overlay + breadcrumb. Guest-side measurement is impossible; the host modifier
    is the way.
12. **Per-app live-presenter preview**: compile the consumer app's screens+logic
    (stashfin-sdui pattern: portal-app-lib extraction) to the browser so ▶ Live
    runs the app's REAL presenters against PreviewCapabilities.
13. **Typed Route contracts + derived nav graph + flow preview** — sealed Route
    per feature, recognizer derives the graph, preview actually navigates
    (Login→OTP→Dashboard with a persona). Biggest authoring-experience win.
14. **FlowScope presenter** — name the pattern (parent presenter composing screen
    presenters, owning flow-lifetime draft state), scaffold + document it.
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
