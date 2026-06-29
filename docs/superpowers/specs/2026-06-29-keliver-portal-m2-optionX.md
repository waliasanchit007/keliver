# Portal M2 — Option X: faithful keliver-material device app (design)

> Branch: `spike/keliver-web` (konduit). Approved 2026-06-29.

## Goal

Render the portal's keliver-material `WidgetNode` tree **faithfully on a real
Android device** (same widgets as the web preview), updating live as the portal
edits — via Treehouse/Zipline (the only on-device path; keliver-material guest
composables have no android target).

## Approach

A NEW minimal Treehouse app `konduit/portal-device/` (its own gradle build,
templated on `konduit/sample/`), on **keliver-material 0.2.0 from Maven Central**.

```
 portal(browser) ─edit→ relay :8077
                           ▲ OkHttp GET (host service polls)
 Android app (Pixel_9):
   host: TreehouseAppFactory + KeliverMaterialHostProtocol.Factory
         + ComposeUiKeliverMaterialWidgetSystem  ← PUBLISHED host renderers (no hand-writing)
         + RealHostTreeProvider (OkHttp GET http://10.0.2.2:8077/tree)
   guest (JS/Zipline): TreehouseUi.Show() = RenderNode(deserializeTree(HostTreeProvider.getTreeJson()))
         KeliverMaterialProtocolWidgetSystemFactory + keliver-material-compose (StyledBox/StyledText/…)
         + copied WidgetNode + deserializeTree + RenderNode
```

## Modules (templated/copied from sample, ≈6)

- `shared` — `PortalAppService` + `HostTreeProvider : ZiplineService { fun getTreeJson(): String }`.
- `shared-protocol-guest` / `shared-protocol-host` — keliver codegen plugins, `redwoodSchema.source`
  pointed at the **published** `dev.keliver:keliver-material-schema:0.2.0` (LINCHPIN: verify a fresh
  build can codegen against a published-artifact schema; if not → build in konduit root which already
  has `web-spike-protocol-guest`/`-host` generating these).
- `guest` (JS/Zipline) — `RenderNode` + `WidgetNode` + `deserializeTree` (copied, pure Kotlin) + the
  `TreehouseUi` polling `HostTreeProvider`.
- `host-android` — `MainActivity` (factory + `KeliverMaterialHostProtocol.Factory` +
  `ComposeUiKeliverMaterialWidgetSystem`) + `RealHostTreeProvider` + `DevConfig.MANIFEST_URL`.
- (`host-compose` only if a shared mount is needed; android can mount directly.)

## Run / verify

`:guest:serveDevelopmentZipline --continuous` (serves the bundle on :8080) +
`:host-android:installDebug` → launch on Pixel_9 → edit text in the browser portal →
the emulator's native screen updates within a poll. Relay (`konduit :portal-relay:run`)
+ portal (`:8096`) already running.

## Scope / risk

Large, intricate Treehouse wiring (Zipline plugin, manifest signing, dev-server,
emulator). The two early gates: (1) codegen vs published keliver-material schema;
(2) the host widget system + guest lifecycle wiring. Build incrementally; verify the
emulator step supervised.

## Out of scope

Event round-trip from device; iOS; WebSocket push; production hosting.

## Done when

Editing a property in the browser portal updates the Pixel_9 emulator's native
keliver-material render within a poll interval.
