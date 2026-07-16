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
5. **Repeat mock hints surfacing**: preview shows `{item.title} 1..3` — wire the
   existing item-mock-hints so section lists preview with realistic data by default.
6. **Icon set**: curated ~55 names is too small for real apps (QrCode, ContentCopy,
   AccountBalance all missing → ⓘ). Either extend the map or add an icon-font/URL
   fallback prop.
7. **Editor canvas: ListItem width** doesn't fill its card in preview (renders
   correctly on devices) — canvas-host sizing bug, cosmetic but visible in demos.

## P2 — Relay/tooling ergonomics (each cost real session time)

8. **Relay boot scan**: no initial ingest — screens created while the relay is down
   never appear; macOS watcher also ignores `touch`. Scan screensDir at boot.
9. **Config over env**: `PORTAL_REPO` env is undiscoverable; accept a CLI arg /
   read `keliver.portal.json` upward from cwd. Also: stale store mirrors
   (`~/.keliver-portal/<project>/*.json`) shadow deleted screens — reconcile
   against screensDir at boot.
10. **Host/guest widget-version handshake in DEV** — a stale host lib produced a
    blank screen + cryptic `Unknown widget ID N` (node-id desync after a silently
    skipped unknown widget). Dev loader should log the missing widget TAG and
    surface a visible "host lacks widget X" error. (Prod is already gated; dev isn't.)

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
