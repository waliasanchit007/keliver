# keliver-portal-tools 0.3.13 — release notes

**Status: CANDIDATE — not released.** Tag and publish only after the gates in
[`PORTAL_TOOLS_RELEASE.md`](PORTAL_TOOLS_RELEASE.md) pass.

| | |
|---|---|
| tag | `portal-tools-v0.3.13` → *(the verified candidate's `VERSION.json` `sourceCommit`; not tagged)* |
| release | *(none yet)* |
| zip asset | `keliver-portal-tools-0.3.13.zip` *(the retained, verified CI artifact; not uploaded)* |
| `.sha256` asset | *(not uploaded)* |

What is in it since 0.3.12 (`8139a55be`): PRs #140 and #141 (the editor),
#142 (bundled scaffolders), #143 (U38), #145 (live device updates), #146
(U28), #147 (MCP `device_screenshot`), #148 (`keliver-portal` ports) and #149
(a relay file-descriptor leak). #138, #139, #144 and #151 record releases
and evidence and change nothing in the bundle (#151 is libraries 0.4.1).

**New apps get the 0.4.1 libraries** (`dev.keliver:*:0.4.1`, on Maven Central
since 2026-10-27): `keliver-init`, `keliver-new-editor.sh`,
`keliver-new-device-target.sh` and the production and iOS host scaffolders
default to 0.4.1. 0.4.1 changes only the editor libraries (`portal-editor`,
`portal-core`, `keliver-material-composeui`), additively; the guest, host
and widget code, the Zipline wire format and compiled guests are 0.4.0's.
The bundle's own generic editor carries all of the editor work below; a
**per-app editor** (`keliver-new-editor.sh`) gets it from `portal-editor`
0.4.1. An existing app keeps the version its `keliver.portal.json` names in
`appRuntime.keliverVersion`, and its per-app editor keeps that version's
editor against this relay, which stays compatible with it; moving it is your
change to make.

## New: a debug phone picks up a publish while the app is open

`keliver.updates=live` in the production host (the scaffolded Android host,
standalone or `--embed`) applies a publish to a screen that is on show,
without a resume or restart. It is the **default for debug builds**; a
release build never listens, and `live` in a release build means
`on-resume`.

- The relay serves `GET /bundles/events` (server-sent events): one event when
  a device connects and one after each successful publish, each naming the
  newest bundle version. It holds at most 32 listeners; a slow or stalled
  device never delays a publish.
- The host listens only while a Keliver screen is shown, on a debuggable
  build, and on each event runs the same check as on-resume: the update is
  applied only after its signature, index sequence and rollback floor are
  verified. A bundle server that does not serve the stream (a static HTTPS
  host, `keliver-publish`'s output) is detected once; the host then says so in
  its log and updates on resume.
- The editor's top bar says how many phones are listening
  ("On devices: v7 · 1 phone live").

The reference app's CI drives this on an emulator (LV1–LV3): a static server
falls back to on-resume; a publish while the app is open is applied in the
same process; leaving the screen closes the stream.

## New in the editor (#140, #141)

The bundle's generic editor is the one you work in now:

- a click selects the widget or component instance under it, with hover and
  selection boxes that say what they are; tall screens scroll; zoom with
  − / + / Fit, ⌘/Ctrl + wheel, pinch and keys;
- components open in place (double-click or Enter) and edits go to the
  component's own file; a change to its signature is refused;
- typed property controls, including named dropdowns generated from the
  schemas and colour pickers;
- undo and redo across documents, in the relay's order (`/history`), with
  write-back undone byte for byte;
- the outline keeps its scroll position; a top bar that says what each action
  does and which bundle devices have; a palette preview drawn by the canvas;
  a compact publish card with the log behind a button; Publish asks once;
- the generic editor carries no sample personas or flows, and Live says when
  an editor has nothing to run;
- new screen files are written compactly; the compiled-version stamp never
  goes back, and a component gets none.

## Fixed

- **The bundled screen and component scaffolders wrote into the bundle**
  (#142). Run from the bundle's `bin/`, `keliver-new-screen.sh` and
  `keliver-new-component.sh` took the bundle as the app and used Keliver's own
  package. They now take the app from the working directory (or
  `KELIVER_APP_DIR`), refuse to run without a `keliver.portal.json`, and take
  the package from the app's own files.
- **A browser could keep running an old editor** (KNOWN_BUGS U38, #143).
  `keliver-portal` serves the editor with `Cache-Control: no-store` and ignores
  conditional requests, so a browser that had the generic editor open on the
  port always loads the app's own editor after it.
- **Two apps' editors on the same port** (#148). `keliver-portal` took the
  editor's port as 8096 whatever the app's relay port was. It now reads
  `editorPort` from `keliver.portal.json` (default: the relay port + 19), the
  editor URL it prints carries `?relay=<port>` when the relay is not on 8077,
  and the editor's server adds it to a URL opened without it (a bookmark could
  otherwise open one app's editor on another app's relay). `port` and
  `editorPort` must be port numbers and differ; a port conflict names which
  port is taken. An `httpRecording.allowedOrigins` entry must name the app's
  own editor port.
- **The generic hosts in production mode logged a false error** (KNOWN_BUGS
  U28, #146): they no longer offer Treehouse an empty manifest URL before the
  real one is known. The generic hosts are built from source only; the
  bundle's host is development-only.
- **MCP `device_screenshot` could not find adb** (#147) outside one macOS
  path. It now looks in `ANDROID_HOME`, `ANDROID_SDK_ROOT`, the default SDK
  paths on macOS and Linux, then `PATH`, and a failure is reported as a tool
  error.
- **The relay leaked a connection per dropped editor** (#149): a
  `/doc-events` listener whose connection broke was removed but its exchange
  was never closed.
- **A reopened screen drew twice** in the production host template (#141):
  a screen waiting for an on-resume update now draws once, with the new
  bundle.

## Candidate verification

*(to be filled from the verified CI build, as for 0.3.12)*

| | |
|---|---|
| Source commit | |
| Zip | `keliver-portal-tools-0.3.13.zip`, sha256 |
| `VERSION.json` | tools 0.3.13, Maven dependency 0.4.1 |
| Bundled host APK | sha256 , no `assets/portal_ed25519.pub` |
| Build | run , retained artifact |
| Device job | run |
