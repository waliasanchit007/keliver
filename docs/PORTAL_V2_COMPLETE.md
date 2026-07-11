# Keliver Portal V2 — Complete

A state-of-the-art internal server-driven-UI platform where the **portal UI, any
code editor, and AI agents all edit the same screen**, it renders live on web +
Android + iOS, and production ships **compiled, signed Kotlin**. Built on the
V1 portal (schema-codegen'd widgets, publish/sign/version pipeline).

All work is on branch `spike/keliver-web`. Merge to `main` is a human decision.

## The loop

```
        EDIT (any of)                         SHIP
 Portal UI  ─┐                          Publish → compile the canonical
 IDE / vim  ─┼─► one UiDocument ──►     project → Ed25519 sign → versioned,
 AI (MCP)   ─┘   (Kotlin-in-git truth)  capability-gated bundle → prod devices
                     │                        (verify signature)
        ┌────────────┼───────────────┐
   web preview   dev devices     .kt file (surgical
   (capability-  (overlay:        write-back — comments
    fidelity)     compiled +       & RawCode preserved)
                  live overlay)
```

## Milestones (all done + verified)

| M | What | Verified by |
|---|---|---|
| **M1** | Live `UiDocument` + handle-based op engine, SSE, server-side undo | keystroke → op → `/doc` + device + `.kt` (Chrome) |
| **M2** | `portal-mcp` — agent surface (catalog-grounded, dry-run, find_usages) | scripted stdio agent session |
| **M3** | `.kt` = source of truth (file-watch → PSI recognize → reconcile) | edit file in vim → editor live, no reload |
| **M4** | Surgical PSI write-back | hand comment survives a prop edit |
| **M5** | Condition/Repeat semantic nodes | `if`/`forEach` ⇄ editable tree nodes |
| **M6** | App project in git; publish compiles canonical Kotlin | portal op = one-line git diff |
| **M7** | OTA data layer (`HostSqlDriver` + SQLDelight) + capability gating | typed queries; gate serves older bundle to hosts missing the cap |
| **M8** | Capability-driven live preview (fidelity emerges from the dep graph) | real logic runs against substituted SQL; Full-fidelity panel |
| **M9** | Overlay dev runtime (compiled primary + interpreter overlay, versioned catch-up) | Pixel_9: compiled → edit → overlay → rebuild → caught up |
| M10 | IntelliJ plugin | deferred (design §9) — the ingest path already makes any editor first-class |

## Module map (V2 additions)

| Module | Role |
|---|---|
| `portal-document` | `UiDocument`, `DocNode`, `DocOp`, apply/invert engine, projection (jvm+js+wasm) |
| `portal-ingest` | headless PSI recognizer + reconciler + surgical write-back |
| `portal-mcp` | stdio MCP server over the op engine |
| `portal-sql` | `HostSqlDriver` wire + SQLDelight guest driver (jvm+js+ios) |
| `portal-app-lib` | the app's canonical `screens/` + `logic/` + entry (shared by both guests) |
| `portal-relay` | the local-first portal-server (docs, ops, SSE, ingest, publish, gating, `/devstate`) |
| `portal-device-guest` | the overlay dev guest (compiled + interpreter, versioned catch-up) |

## Run it — one command

```bash
scripts/keliver-dev.sh            # server (:8077) + editor (:8096) + device bundle (:8080)
scripts/keliver-dev.sh --android  # …and install + launch the Android host
```

It builds what's needed, prints the URLs, and Ctrl-C stops everything. Open the
editor URL, or edit `portal-app-lib/src/jsMain/kotlin/screens/*.kt` directly, or
drive it from an AI agent:

```bash
PORTAL_REPO=$PWD portal-mcp/build/install/portal-mcp/bin/portal-mcp   # stdio MCP
```

See `PORTAL_USAGE.md` for the day-to-day workflow.

## Known follow-ups (documented, non-blocking)

- ~~Per-item data binding inside `Repeat`~~ — DONE (P1-B), plus single-arg action
  events and item-carrying actions (P2): `{ b.onX(it) }` / `{ b.onX(item.field) }`
  round-trip, typed contracts, list→detail proven in the Field Notes dogfood.
- Running arbitrary per-project presenter Kotlin in the browser preview (vs the reference
  presenter's behavior) = the per-app preview build; the capability-fidelity *model* ships now.
- Surgical write-back falls back to full-file regen on reorders/contract changes (safe, not silent).
- ~~iOS runtime re-verification~~ — DONE (2026-07-12): the signed Field Notes v2
  bundle loads + renders on the iPhone 16 Pro sim with real Ed25519 verification.
  Found & fixed en route: the iOS host didn't declare `HostSqlDriver@1` to
  `/bundles/latest`, so the gate served it a stale bundle.
- Real sqlite3 iOS `HostSqlDriver` (Android uses real SQLite; iOS is in-memory for dev).
- M10 IntelliJ plugin (keystroke-granularity sync; the file-ingest path already covers editors).
