# keliver-portal-tools 0.3.13 — release notes

**Status: RELEASED 2026-10-27**, by `PORTAL_TOOLS_RELEASE.md`.

| | |
|---|---|
| tag | `portal-tools-v0.3.13` → `a5ab6e2b2bcaf51cb1e09a4504eac6a6869aff0e` |
| zip asset | `keliver-portal-tools-0.3.13.zip`, sha256 `5d1dd0efc19aaae1377676c7a043892a7a7155a2a4167f30171499c59cbcb323` (92,036,638 bytes): the retained, verified CI artifact |
| `.sha256` asset | the zip's sha256 and file name |

What is in it since 0.3.12 (`8139a55be`): PRs #140 and #141 (the editor),
#142 (bundled scaffolders), #143 (U38), #145 (live device updates), #146
(U28), #147 (MCP `device_screenshot`), #148 (`keliver-portal` ports) and #149
(a relay file-descriptor leak). #138 and #139 record releases and evidence.
#144 rewrote documentation the bundle ships (`README.md`, `host/README.md`);
#151 is libraries 0.4.1, whose version the bundle's generic editor now
carries.

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
  ("On devices: v7 · <time> · 1 phone live").
- iOS hosts from this release stay on-resume in a debug build; live updates
  for the iOS host follow in the next release.

The reference app's CI drives this on an emulator (LV1–LV3): a static server
falls back to on-resume; a publish while the app is open is applied in the
same process; sending the app to the background closes the stream.

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
  a compact publish card with the log behind a button; Publish asks before it
  runs, and runs once at a time; device-frame presets that resize and rotate
  in place; a toolbar that compacts on narrow windows;
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
  port loads the app's own editor on its next load. (Checked with `curl`; the
  headless-browser reproduction from 0.3.12 has not been re-run.)
- **Two apps' editors on the same port** (#148). `keliver-portal` took the
  editor's port as 8096 whatever the app's relay port was. It now reads
  `editorPort` from `keliver.portal.json` (default: the relay port + 19), the
  editor URL it prints carries `?relay=<port>` when the relay is not on 8077,
  and the editor's server adds it to a URL opened without it (a bookmark could
  otherwise open one app's editor on another app's relay). `port` and
  `editorPort` must be port numbers and differ; a port conflict names which
  port is taken; `keliver-portal stop` works even with a broken config. An
  `httpRecording.allowedOrigins` entry must name the app's own editor port.
  `keliver-new-publish-target.sh` prints the app's own port and no longer asks
  for a restart.
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

## Verification

All of it against one set of bytes: the retained artifact of build run
`40444075230`, which was then attached to the release.

| | |
|---|---|
| Source commit | `a5ab6e2b2bcaf51cb1e09a4504eac6a6869aff0e` (PR #152's head; `sourceDirtyFiles` 0) |
| Zip | `keliver-portal-tools-0.3.13.zip`, sha256 `5d1dd0efc19aaae1377676c7a043892a7a7155a2a4167f30171499c59cbcb323`, 92,036,638 bytes |
| `VERSION.json` | tools 0.3.13, Maven dependency 0.4.1 |
| Bundled host APK | sha256 `04e5e58c757c9a573fe793e48d6d99a57e819f1aa03d25f5e1c839428ba1cb63`, no `assets/portal_ed25519.pub` |
| Build | `portal-tools.yml` run `40444075230`; retained artifact `12708283293` (digest `sha256:09cc598750af3cedbdad3a499b930fb3a5beb526469bb034ea9f151274daec5b`) |
| Device job | run `40446935093` (an emulator on GitHub-hosted Ubuntu; the verifier from the candidate's own branch): the APK's sha256 checked against the pin; device verify 28/0; production refusal and the development route 19/0 |

The build run's packaged checks: unit tests 134/0; the store resolver 32/0
and 73/0; packaging hygiene (U22) 10/0; the **packaged adopter acceptance**,
with the U21 identity gate, 25/0; the foreign-relay refusal 6/0; the store
identity contract 11/0; the production-host scaffolder 68/0 (and 62/0 from
the package); the iOS host scaffolder 65/0 (and 65/0); the publish
scaffolder 51/0 (and 33/0); the screen and component scaffolders 9/0;
`keliver-publish` 41/0; key permissions 63/0; store recovery 145/0.

The artifact was downloaded and checked on the releasing machine before the
tag: the artifact's sha256 equals GitHub's digest, and the zip, `VERSION.json`
and APK are as above. PR #152's CI on the same commit: Build, both editor E2E
suites, reference-app and ios-host green.

**How the stored asset was checked.** The draft's assets were compared with
the verified files by GitHub's server-side record (`digest`, `size`): the zip
`sha256:5d1dd0ef…`, 92,036,638 bytes, and the `.sha256` file
`sha256:b9d37762…`, 98 bytes, both `uploaded`; the tag was re-checked at
`a5ab6e2b2` and its push ran only `portal-tools.yml`'s read-only rebuild
(`40458612267`, never attached). Downloading the draft asset back, and the
public URL after publishing, were blocked by the releasing machine's network
(an 879-byte "Bad Gateway" page from a proxy, four tries), so — as for 0.3.5
— the public download is checked from GitHub-hosted runners instead, by the
reference app's move to 0.3.13 (#154: the first step of `reference-app.yml`
and `ios-host.yml` checks the public zip against the release's `.sha256` and
the pinned hash). Published 2026-10-27T06:12:08Z with `--latest=false`.

Not verified for this release: a physical phone, and a browser re-run of
U38's reproduction.
