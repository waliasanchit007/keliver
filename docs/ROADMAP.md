# Keliver — Roadmap & improvement backlog

Ordered by (impact on the adoption thesis) × (how soon it bites). Every item lists
its evidence — nothing here is speculative. Status: 2026-07-12, after the Stashfin
tri-platform loop + Profile Style-B port.

## P0 — Write-back trust (the thesis-critical gate)

The 100-screen review's top finding: if portal diffs are noisy, senior engineers
reject them, the portal becomes designer-only, and the two-way thesis dies socially.
Target: **byte-stable round-trip** (ingest → write → re-ingest → identical file) as
a CI gate over a corpus of real screens.

1. **Surgical-edit re-indent bug** — a prop edit rewrites the call at column 2
   instead of the original depth (seen live on ProfileLiteScreen). NodeEmitter
   splice must inherit the target's indentation.
2. **/undo doesn't rewrite the .kt** — it reverts the document only; the watcher
   then re-ingests the file value, so undo appears ineffective. Undo must run the
   same write-back path as ops.
3. **writeKotlin hardcodes `dev.keliver.portalpublished.screens`** for in-project
   full regeneration — a foreign-package repo (com.stashfin.*) would get a broken
   file on the regen path. Make packageName config/ingest-derived.

## P1 — Grammar & recognizer gaps (each found by porting a real screen)

4. **Literal action args**: `{ b.open("ROUTE") }` silently RawCodes (found on the
   Profile App-Update row). Support literal `Action.arg` — commonest idiom in
   ported native code.
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
