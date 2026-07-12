# keliver — agent & engineer context

**What this is:** keliver (fork of Cash App Redwood, ns `dev.keliver`, Maven
Central `dev.keliver:*:0.3.0`) — write screens once in Kotlin, ship them OTA as
compiled signed Zipline bundles, render natively on Android/iOS/web, and edit
them visually in a web portal that round-trips to the .kt files in git.

**Read before working:**
- `docs/DECISIONS.md` — finalized architecture decisions (D1–D15). Don't relitigate.
- `docs/ROADMAP.md` — prioritized backlog with evidence. Pick work from here.
- `docs/SCREEN_ARCHITECTURE.md` — Style B (Screen/Presenter/Bindings) in detail.
- `docs/PORTAL_USAGE.md`, `docs/KNOWN_BUGS.md`.
- Reference adoption (real app, patterns to copy): `~/StudioProjects/stashfin-sdui`
  — its CLAUDE.md/docs mirror this from the consumer side.

**Environment:** `JAVA_HOME=$(/usr/libexec/java_home -v 17)` for every gradle
command. `gh` resolves to upstream cashapp/redwood — use
`GH_REPO=waliasanchot007/keliver` (repo: waliasanchit007/keliver). CI runs on the
self-hosted `keliver-mac` runner.

**Key modules:** `keliver-material*` (60-widget lib + schema), `portal-relay`
(:8077 server — ingest/write-back/publish; needs `PORTAL_REPO=<app repo>` env),
`portal-ingest` (PSI recognizer/reconciler), `portal-core`/`portal-document`
(tree + ops engine), `portal-render` (shared RenderNode), `web-spike` (wasm
editor → :8096), `portal-schema-codegen` (schemas → catalog/exporter/RenderNode
— regenerate when schemas change, CI has a staleness guard).

**Gates before "done":** `apiCheck` + affected module tests + (portal changes)
ingest round-trip on a real screen + (widget changes) device render. The
universal screen bar is D14 in DECISIONS.md: compile → 0 RawCode ingest → device
screenshot → surgical write-back round-trip.

**Releases:** bump `KELIVER_VERSION` in RedwoodBuildPlugin.kt, tag `vX.Y.Z`,
`gh workflow run publish-maven-central.yml -f ref=vX.Y.Z` (guards tag==const).
Pre-gate locally: `publishToMavenLocal -PkeliverVersion=X.Y.Z
-DRELEASE_SIGNING_ENABLED=false` + `apiCheck`.
