# Portal M2 — Live Device Sync (design)

> Branch: `spike/keliver-web`. Builds on the portal (A render, B export, C shell,
> rich editor). Target: Android emulator. Approved 2026-06-29.

## Goal

Edit the tree in the browser portal → it reflects **live on a real Android device**,
no app rebuild — the headline cross-platform payoff.

## Architecture

The device path **re-engages Treehouse/Zipline** (the established keliver-on-mobile
path; the web work skipped Zipline by running the guest in-process, but on Android
the guest runs as a Zipline JS bundle in the host's QuickJS).

```
 portal (browser)        relay (small JVM HTTP)      Android app (emulator)
 edit tree ─serialize─► POST /tree ──────────► host polls GET /tree
                                                 │ hands tree JSON to the guest
                                                 ▼ (Zipline host→guest service)
                                        runtime Treehouse guest (JS):
                                          Show() { RenderNode(deserializeTree(json)) }
                                                 │ protocol over Zipline
                                                 ▼ native Compose host → screen
```

## Components & build order (device-independent parts first)

### 1. Serializable tree (`portal-core`) — no device needed

`WidgetNode.props` is `Map<String, Any?>`; decode must preserve exact Kotlin types
(the render getters do `as? Int` / `as? Double` / `as? List<Int>` / `as? List<Float>`).
So use a **type-tagged codec**: each value → a 1-key JSON object —
`Int`→`{"i":n}`, `Double`→`{"d":x}`, `Boolean`→`{"b":t}`, `String`→`{"s":"…"}`,
`List<Int>`→`{"li":[…]}`, `List<Float>`→`{"lf":[…]}`. `WidgetNode` →
`{"type","id","props":{name:tagged},"children":[…]}`.

API: `fun serializeTree(node: WidgetNode): String`, `fun deserializeTree(json: String): WidgetNode`.
Add `kotlinx-serialization-json` to `portal-core`. **Verify:** a round-trip unit
check (`deserializeTree(serializeTree(sampleTree(3)))` renders/export-equals) — JVM,
no device.

### 2. Move `RenderNode` into a shared guest-capable module — no device needed

`RenderNode` is in `web-spike` (wasm only). Move it to a module that targets both
**wasmJs** (web) and the **Treehouse-guest JS** target, so the same interpreter runs
on web and on-device. It only uses keliver-material guest composables (JS-capable).
Candidate: a new `portal-render` module (Compose guest target) or fold into an
existing guest module. **Verify:** web-spike still compiles + renders (re-run the
editor build); the module compiles for the guest JS target.

### 3. Relay server (small JVM) — no device needed

A tiny HTTP service: in-memory `currentTree: String`; `POST /tree` stores it,
`GET /tree` returns it. CORS-open for the browser. **Verify:** `curl` POST then GET
round-trips; the portal (browser) can POST to it.

### 4. Runtime Treehouse guest — JS compile, no device

A generic guest (mirroring `sample/guest`): `Show()` renders
`RenderNode(deserializeTree(currentTreeJson))`, where the host supplies the current
tree via a Zipline **host→guest service** (e.g. `HostTreeProvider` bound as `"tree"`,
returning the latest JSON); the guest re-reads on change. **Verify:** the guest
module compiles to its Zipline JS bundle.

### 5. Android host wiring + on-device verify — **needs the emulator**

Extend `sample/host-android`: load the runtime guest, **poll** `GET /tree` from the
relay, push each new tree into the guest via the `HostTreeProvider` service, and
trigger a re-render. **Verify (emulator):** boot the Pixel AVD; edit a property in
the browser portal → the emulator screen updates within a poll interval.

## Transport: poll for the MVP

The host **polls** `GET /tree` (like gate-4's web hot-reload) — simplest, proven
pattern. WebSocket push is a later refinement once the loop works.

## Out of scope (YAGNI)

WebSocket push; iOS (Android first); auth/multi-session on the relay; partial/diff
sync (push the whole tree); event round-trip from device back to portal; production
hosting of the relay.

## Done when

Editing a property in the browser portal updates a running Android emulator's native
Compose screen within a poll interval — no app rebuild — with the same `WidgetNode`
tree + `RenderNode` interpreter the web preview uses.
