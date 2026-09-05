# keliver — agent & engineer context

**What this is:** keliver (fork of Cash App Redwood, ns `dev.keliver`, Maven
Central `dev.keliver:*:0.3.2`) — write screens once in Kotlin, ship them OTA as
compiled signed Zipline bundles, render natively on Android/iOS/web, and edit
them visually in a web portal that round-trips to the .kt files in git.

**Read before working:**
- `docs/DECISIONS.md` — finalized architecture decisions (D1–D15). Don't relitigate.
- `docs/ROADMAP.md` — prioritized backlog with evidence. Pick work from here.
- `docs/SCREEN_ARCHITECTURE.md` — Style B (Screen/Presenter/Bindings) in detail.
- `docs/PORTAL_USAGE.md`, `docs/KNOWN_BUGS.md`.
- Reference adoption: **there is currently no external adopter.** `stashfin-sdui`
  (the app every historical "Stashfin" gate below was recorded against) was lost
  with the previous machine and was never pushed. Treat those gates as evidence
  that the path worked, not as something you can re-run. The in-repo
  [`sample/`](sample) is the only runnable reference today.

**Environment:** `JAVA_HOME=$(/usr/libexec/java_home -v 17)` for every gradle
command. `gh` resolves to upstream cashapp/redwood — use
`GH_REPO=waliasanchit007/keliver`. CI runs on the self-hosted `keliver-mac`
runner. `git-lfs` is required before cloning — `*.png` is LFS-tracked, and a
clone without it fails checkout partway.

**Key modules:** `keliver-material*` (76-widget lib + schema), `portal-relay`
(:8077 server — ingest/write-back/publish; needs `PORTAL_REPO=<app repo>` env),
`portal-ingest` (PSI recognizer/reconciler), `portal-core`/`portal-document`
(tree + ops engine), `portal-render` (shared RenderNode), `portal-editor` (the
REUSABLE editor shell — `runPortalEditor(entry: AppPreviewEntry)`; a consumer
app ships a per-app editor by depending on it + supplying its own AppPreviewEntry),
`web-spike` (konduit's OWN thin editor executable = shell + AppLibPreview → :8096),
`portal-schema-codegen` (schemas → catalog/exporter/RenderNode — regenerate when
schemas change, CI has a staleness guard).

**Dev loop (current):** relay `PORTAL_REPO=$PWD ./gradlew :portal-relay:run`
(:8077; boot-scans screens+components, watches logic/, debounce-rebuilds the
preview editor and promotes ON SUCCESS to `build/portal-editor-live`). Serve
the editor with `python3 -m http.server 8096` FROM `build/portal-editor-live`
(the promoted last-known-good — NOT web-spike/build/dist directly; serving
from the wrong dir = white screen, app .wasm 404s). `GET /preview-build` =
build status. Config paths in keliver.portal.json point at
portal-app-lib/src/commonMain (js+wasm since P3-12). **CACHE TRAP: the editor
loader `web-spike.js` has a CONSTANT filename, so after a rebuild the browser
keeps running the OLD cached loader (and its baked-in stale wasm hash) — your
fix appears to "not work". The relay's promote() now stamps `?v=<millis>` onto
the script ref (a625d4233), but a relay predating that fix won't; either
restart the relay, or hard-reload / bump the `?v=` in
build/portal-editor-live/index.html, and confirm via read_network_requests
that the NEW hashed .wasm loaded.**

**Session-earned gotchas:** a new schema widget/modifier MUST also be listed in
the @Schema members annotation or codegen silently ignores it; Zipline has no
wasm target — guest presenters must take capability interfaces (e.g. sqldelight
SqlDriver, which HAS wasm since 2.x), Zipline adapters live at the device edge;
PSI tree mutation is fragile at brace anchors — prefer offset-based string
surgery from one parse (see ContractWriteBack); generated-file behavior changes
go through portal-schema-codegen + generatePortalCode + its tests.

**Gates before "done":** `apiCheck` + affected module tests + (portal changes)
ingest round-trip on a real screen + (widget changes) device render. The
universal screen bar is D14 in DECISIONS.md: compile → 0 RawCode ingest → device
screenshot → surgical write-back round-trip.

**Releases:** bump `KELIVER_VERSION` in RedwoodBuildPlugin.kt, tag `vX.Y.Z`,
`gh workflow run publish-maven-central.yml -f ref=vX.Y.Z` (guards tag==const).
Pre-gate locally: `publishToMavenLocal -PkeliverVersion=X.Y.Z
-DRELEASE_SIGNING_ENABLED=false` + `apiCheck`.
