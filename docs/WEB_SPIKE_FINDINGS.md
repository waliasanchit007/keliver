# keliver on the web — spike findings & production plan

> Branch: `spike/keliver-web` (not merged to `main`). This documents a
> proof-of-concept that ran keliver's UI in a browser, end to end, and what it
> would take to make it production-grade. Everything below was verified with
> real pixels in Chrome, not inferred.

## TL;DR

The dream — **"author UI once in Kotlin; ship it live to web + Android + iOS
without an app-store release; eventually drive it from a drag-drop web portal"**
— had three scary unknowns. All three are now **answered yes, with working
code**:

1. **Can keliver's widgets render on the web at all?** Yes. The *same*
   `keliver-material-composeui` renderers compile to Compose-for-Web (Wasm) and
   draw the same widgets as Android/iOS.
2. **Can the keliver protocol cross to the web** (so a host renders UI it never
   compiled against)? Yes. Guest emits serialized `ProtocolChange`s → host
   deserializes + renders, in the browser.
3. **Can we get mobile-style "push Kotlin → live" + hot-reload on the web?**
   Yes. A generic web host fetches the UI over the network and hot-reloads when
   the guest changes — no refresh, no host rebuild.

What remains is **known engineering** (bundle size, WebSocket push, target-group
hygiene, pushed-UI safety), not research. None of the remaining items are
unknowns.

---

## The four gates (what was proven, and how)

### Gate 1 — keliver widgets render on the web

The whole keliver foundation was *already* JS-portable (`Common` /
`CommonWithAndroid` groups declare a `js()` target). **Only the Compose-renderer
group (`ToolkitComposeUi`) lacked a web target.** Adding one line —
`js().browser()` — made `keliver-material-composeui` compile for the browser on
the first try; the full `wasmJs` path needed a small, bounded cascade:

- 4 target-group edits to add `wasmJs { browser() }` (`ToolkitComposeUi`,
  `Common`, `CommonWithAndroid`, `ToolkitAllWithoutAndroid`).
- `withWasmJs()` on `keliver-compose`'s custom `nonJs` source-set group, so Wasm
  uses the plain-Kotlin `PlatformList`/`PlatformSet`/`RedwoodComposition` actuals
  instead of the JS-array-interop ones.
- ~5 trivial `wasmJs` actuals (`density`, `insets`, `RedwoodContent`, plus no-op
  `leak-detector` `Gc`/`WeakReference`).
- `WASM_EXCLUDED = {keliver-console, keliver-storage, keliver-http}` — three
  Treehouse data-service modules that depend on Zipline/Ktor (no Wasm variant)
  and aren't on the render path.

**Result:** the `web-spike` Compose-for-Web app rendered a real keliver screen
(multi-stop gradients, `RichText` gradient text, animating `AnimatedBorder`) in
Chrome — same Kotlin guest code as the Android/iOS screens.

### Gate 2 — the protocol round-trip, in the browser

We skip Zipline/Treehouse entirely on web and use the protocol layer directly
(`keliver-protocol`, `-guest`, `-host` are all `Common`, so they got Wasm for
free). Two generated codegen modules — `web-spike-protocol-{guest,host}` —
produced `KeliverMaterialProtocolWidgetSystemFactory` and
`KeliverMaterialHostProtocol`, compiled for Wasm.

Round-trip (all in the browser):

```
DefaultGuestProtocolAdapter + TestRedwoodComposition.setContentAndSnapshot { Screen() }
  -> guestAdapter.takeChanges() : List<Change>
  -> SnapshotChangeList -> JSON  (the wire format)
  -> UiChange.fromProtocol(hostProtocol, …)
  -> HostProtocolAdapter.sendChanges(…)
  -> ComposeWidgetChildren.Render()
```

**Console-proven:** `126 changes -> 7130 chars of protocol JSON`, zero errors. A
host that never compiled against the screen rendered it.

### Gate 3 — OTA: the host fetches the UI over the network

Split into two builds:

- **Guest build** (`web-spike-guest-compiler`, plain JVM): composes the screen
  and writes `screen.json` (the serialized protocol).
- **Host build** (`web-spike`, Wasm): a *generic* host — only
  `keliver-material-composeui` + `protocol-host`, **no guest-screen modules**. It
  `fetch`es `screen.json` at runtime and renders it.

Editing the guest and regenerating `screen.json` changed the rendered web UI
**with the host binary unchanged** — true server-driven UI on the web.

### Gate 4 — hot-reload on the web

The host watches `screen.json` (poll every 800 ms) and, on change, rebuilds the
widget tree from the new snapshot and re-renders. **Verified live with no browser
interaction:** edited the guest (blue "UPI Lite" → orange "5% cashback"),
regenerated `screen.json`, and the open page repainted in ~1 s — same URL, no
refresh, no rebuild (`updates=1` → `updates=2`). This is the mobile
`serveDevelopmentZipline` loop, on the web.

---

## The architecture that emerged

```
            AUTHOR ONCE (Kotlin)                    RENDER EVERYWHERE
        ┌────────────────────────┐
        │  guest screen (@Composable, keliver-material)
        └───────────┬────────────┘
                    │ compile / compose
                    ▼
         serialized protocol  (SnapshotChangeList JSON — the wire)
                    │
    ┌───────────────┼───────────────────────────┐
    ▼               ▼                            ▼
 Android         iOS                          WEB (new)
 QuickJS +     QuickJS +                  browser JS engine +
 Compose       Compose                    Compose-for-Web (Wasm),
 host          host                       generic protocol host
                                          fetch(screen.json) + hot-reload
```

The key realisation: **the web is just another render target sharing the same
widgets and the same protocol.** The "portal" you described falls out of this —
a thin generic host renders Kotlin-authored UI served as data, and a drag-drop
tool would simply *generate the guest* and trigger a regenerate. The rendering,
transport, OTA, and hot-reload underneath all work today.

---

## Spike-grade shortcuts → what production needs

| # | Shortcut taken in the spike | Production work | Risk |
|---|---|---|---|
| 1 | Broad `wasmJs` added to shared groups (`Common`, …) + a `WASM_EXCLUDED` denylist | A proper opt-in `WasmJs` **target modifier** (like the existing `JsTests`), applied per render-chain module — no shared-group edits, no denylist | Low — mechanical, pattern already exists |
| 2 | `~30 MB` **unminified** dev bundle (skiko Wasm ≈ 22 MB + app ≈ 8 MB) | Production webpack (`wasmJsBrowserProductionExecutableDistribution`): minify + DCE. Skiko itself is the fixed floor (~a few MB gzipped). Decide **Wasm-canvas vs an HTML/DOM widget binding** (lighter, SEO-friendly, but ≈ re-implementing the renderers) | Medium — size is the main UX concern; needs a deliberate canvas-vs-DOM call |
| 3 | **Polling** `screen.json` every 800 ms | Real **WebSocket push** — mobile already does this (`serveDevelopmentZipline` + the iOS `NSURLSessionWebSocket` bridge). Reuse that server; host opens a socket, re-fetches on push | Low — the server + protocol exist |
| 4 | **Synchronous XHR** via `js()` to fetch | Async `fetch` (add `kotlinx-browser` or bridge the JS `Promise` with coroutines `await`) so the UI thread never blocks | Low |
| 5 | Guest UI produced by a **JVM** tool (`guest-compiler`) writing `screen.json` | Choose the production source of the protocol: (a) keep server/JVM-produced (clean, cache-friendly), or (b) run the guest **in the browser** (the guest already compiles to Wasm via `protocol-guest`) and bridge guest↔host. (a) is simpler and recommended | Low for (a); (b) has the Kotlin/JS↔Wasm interop nuance |
| 6 | Rendered **static** UI; no events wired | Wire the **event reverse-channel** (`UiEventSink` → guest), so taps/inputs round-trip like mobile. Not exercised on web yet | Medium — needs design + verification |
| 7 | `AsyncImage`/Coil **not exercised** on Wasm | Verify Coil-3 network image loading on `wasmJs` (Coil 3.3 has a wasm target; the network fetcher needs checking) | Low–Medium |
| 8 | No safety around **pushed UI** | Versioning + validation + rollback + staged rollout for served `screen.json`. Wire format is stable within a release line; honor `widgetVersion`/schema compat | Medium — required before prod traffic |
| 9 | `keliver-http/storage/console` excluded from Wasm | If web screens need host data services, add **web-native** equivalents (fetch-based http, `localStorage`-based storage) — these are Treehouse/Zipline-bound today | Medium — only if those features are needed on web |

---

## Recommended production roadmap

**Phase A — make the web target first-class (no spike hacks).**
1. Replace the broad group edits with a `WasmJs` opt-in target modifier; apply it
   to the render-chain modules. Delete `WASM_EXCLUDED`.
2. Move the duplicated `wasmJs` actuals into shared `webMain` source sets (js +
   wasmJs) so they aren't duplicated.
3. Add `apiDump` baselines for the new Wasm variants; wire a `compileKotlinWasmJs`
   CI check so web never silently breaks.

**Phase B — productionize the host.**
4. Production webpack build; measure gzipped size; make the canvas-vs-DOM call.
5. WebSocket push (reuse the dev-server/hot-reload channel); async fetch.
6. Wire the event reverse-channel; verify a tappable screen end to end on web.
7. Verify `AsyncImage` on Wasm.

**Phase C — safety + delivery.**
8. Served-UI versioning, validation, rollback, staged rollout.
9. A small `keliver-web-host` library (the generic host wrapped as a reusable
   artifact) + USAGE docs, so an app embeds web SDUI in a few lines.

**Phase D — the portal (the actual product).**
10. A drag-drop web app that emits the guest (generated Kotlin, or a composition
    it drives) and triggers regenerate. Everything beneath it (render, protocol,
    OTA, hot-reload) is done.

---

## Files this spike added (all under `spike/keliver-web`)

- `build-support/.../RedwoodBuildPlugin.kt` — `js`/`wasmJs` targets on the render
  groups; `WASM_EXCLUDED`.
- `keliver-compose/build.gradle` — `withWasmJs()` on the `nonJs` group.
- `keliver-runtime`, `keliver-composeui`, `keliver-leak-detector` — `wasmJs`
  actuals.
- `web-spike` — the Compose-for-Web host (gate 1 → 4).
- `web-spike-protocol-guest` / `web-spike-protocol-host` — generated
  `KeliverMaterial` protocol adapters for the web.
- `web-spike-guest-compiler` — JVM tool that writes `screen.json`.

## Bottom line

keliver is a **strong** base for the cross-platform-live-UI + portal vision, and
the web — the part I was least sure about — works. The remaining path is a
roadmap, not a research project.
