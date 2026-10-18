# keliver-portal-tools 0.3.10 — release notes

**Status: RELEASED 2026-10-17** (candidate 3), on the owner's explicit go.

| | |
|---|---|
| tag | `portal-tools-v0.3.10` (annotated, object `cdf80822`) → **`78bcfa0ad64bf4321699654d99a48647c14fac83`** |
| release | [portal-tools-v0.3.10](https://github.com/waliasanchit007/keliver/releases/tag/portal-tools-v0.3.10), published 2026-10-17T05:43:07Z, `--latest=false` |
| zip asset | `keliver-portal-tools-0.3.10.zip`, 90,653,818 bytes, sha256 **`0ba5c07b88c869084ee80870682d577aabc8ce83edcdb9caf19b4493db0e87bf`**, uploaded from the retained artifact `12087402781` of build run 38996395407 |
| `.sha256` asset | 98 bytes, sha256 `acf5e839e02919cde6c6d99c57ce8a65576218fd23364dc9e56f1b66ac7bad59` |
| tag push | ran only `portal-tools.yml` (run 39007100251, the read-only rebuild; its artifact is not attached) |

How the stored asset was checked: the draft download-back stalled at 0 bytes
(the releasing machine's network blocks the release-asset host, as for 0.3.5
and 0.3.8), so before publishing, both stored assets' server `digest` and
`size` were compared with the verified files (GitHub's record of the stored
bytes, not a download), with the tag re-checked at `78bcfa0ad`. The public
download was checked from GitHub-hosted runners on 2026-10-17 (PR #118): each
dispatch downloaded the public asset and checked it against the release's own
`.sha256`, the pinned hash `0ba5c07b…` and `VERSION.json`'s source commit
`78bcfa0ad`, then ran the route scaffolded from that zip, green: Android
`reference-app.yml` 39015039884 (prepare 34/0, device 171/0) and iOS
`ios-host.yml` 39030961195 (self-test 71/0, `ios.sh` 159/0).

**New apps get the 0.3.4 libraries** (`dev.keliver:*:0.3.4`, on Maven Central
since 2026-10-16): `keliver-init` writes 0.3.4, and so does every scaffolder
when it has nothing else to go by. An existing app keeps its version: the host
scaffolders, `keliver-new-device-target.sh` and `keliver-new-editor.sh` all
wire the version the app's `keliver.portal.json` names in
`appRuntime.keliverVersion` (`KELIVER_VERSION=<v>` overrides it for the last
two). Tools 0.3.9 and earlier are unchanged.

## Moving an app to the 0.3.4 libraries

Change these together: every `dev.keliver:*` coordinate in the app's
`build.gradle` (and an editor module's, if you made one),
`appRuntime.keliverVersion` in `keliver.portal.json`, and the host's
dependency (`host-android` and `host-ios` take it from `keliver.portal.json`
when scaffolded; a host you already have names it in its own `build.gradle`).
See the library CHANGELOG's [0.3.4]. Two things to know:

- `TextField` and `OutlinedTextField` gained an `align` parameter. That is a
  binary break for compiled callers (recompile) and a source break for a host
  with its OWN implementation of either widget, which must add `align(Int)`.
  The scaffolded hosts implement neither, so they are unaffected.
- Treehouse hosts already installed on 0.3.3 (every scaffolded host is one)
  accept a bundle from a 0.3.4 guest: the unknown property is reported and the
  text aligns to the start. A host that uses `HostProtocol` directly throws on
  it unless it sets a lenient mismatch handler.

**This bundle's editor and development host are built with 0.3.4.** An app
still on 0.3.3 sees the editor's "Keliver version skew" warning, and the
inspector offers `TextField`/`OutlinedTextField` "Align", which the app's
0.3.3 build will not compile. Leave it alone until the app moves.

## Behaviour changes

- **Production bundles by default for new publish targets.**
  `keliver-new-publish-target.sh` writes
  `:compileProductionExecutableKotlinJsZipline` / `build/zipline/Production`
  (minified: in the 2026-10 adoption proof 2.36 MB raw against 4.28 MB, and
  faster to evaluate). `--development` writes the old Development pair. Rerunning
  the script on an app that already has either pair never changes it. To move an
  existing app to Production, set `publishTask` to
  `:compileProductionExecutableKotlinJsZipline` and `publishOutput` to
  `build/zipline/Production` in `keliver.portal.json`; the relay takes them at
  the next publish.
- **Debug hosts check for updates on resume.** In this release's templates,
  `keliver.updates` (Android, `host-android/gradle.properties`; the embedded
  library's `keliver.properties`) and `UPDATES` (iOS `HostConfig.kt`) are empty
  by default: `on-resume` in a debug build, `next-launch` in every other build. A
  value there applies to every build. Hosts scaffolded by 0.3.9 and earlier wrote
  `next-launch` and keep it. Their `build.gradle` does not accept an empty
  value (and an old iOS `MainViewController.kt` would treat it as next-launch):
  either take this
  release's files first (see "Upgrading a host") and then empty the setting, or
  set `on-resume` explicitly. An embedded Android host checks on resume only if
  the app calls `KeliverHost.resumed()` from its activity's `onResume`
  (`keliver.properties` says so).
- **The relay reads `keliver.portal.json` at every publish**: a changed
  `publishTask` or `publishOutput` takes effect without a restart, and other
  changed settings are named in the response as needing one. A
  `keliver.portal.json` that is missing or does not parse now refuses the
  publish, instead of the relay using what it read at start.
- **Bundle server URLs are checked more strictly.** On Android,
  `keliver.bundleServer` with a user (`https://user@host`) is refused at build
  time, and the host refuses at run time a server with a user, query or
  fragment (it appends its own paths); the iOS host refuses a launch-time
  server with a query or a fragment.

## New

- **"Check for an update now", in debug builds**, not held to the 30 s between
  on-resume checks: `adb shell am broadcast -a dev.keliver.debug.CHECK_FOR_UPDATE
  -p <applicationId>` on Android (a receiver registered at run time and guarded
  by the `DUMP` permission, which adb's shell has and ordinary apps do not), the
  Darwin notification `<bundle id>.keliver.check-for-update` on iOS. Release
  builds have neither.
- **A debug host takes its bundle server at launch**, so a new tunnel URL needs
  no rebuild: `files/keliver/bundle-server` (Android, written with `run-as`),
  the `KELIVER_BUNDLE_SERVER` launch environment variable (iOS). Every bundle
  still verifies against the embedded key, and the rollback floor holds.
- **Timing marks** (the host README's "Timing marks"; `host/README.md` in this
  bundle): `process` (Android), `open`, `code`, `first-frame` and `content` (the
  guest's `KeliverTiming.mark("content")`), on monotonic clocks, to logcat / the
  console / `Keliver.onTrace` (Swift: `Keliver.shared.onTrace`) and a file in the app's container. Only
  debug builds and a new **benchmark** build compile them: Android's `benchmark`
  build type (release settings, minified with R8, profileable, debug-signed) and
  iOS's `Benchmark` configuration (Release settings), never shipped. A store
  build compiles none of it. `keliver-new-device-target.sh` writes the guest's
  `device/KeliverTiming.kt`, from `templates/guest/KeliverTiming.kt`.

## The relay

- **The full Gradle output of every publish** is kept in
  `<store>/publish-logs/` (owner-only; the newest 20), and the response names
  the file. The response itself still carries the last 30 lines.
- **A publish whose build fails with a Kotlin "Internal compiler error" or
  "Backend Internal error" is built once more** (KNOWN_BUGS U34: the first
  publish after another Gradle build sometimes failed that way; the cause is not
  known). Any other failure is reported at once.

## Fixed

- **U33: editing a text with an escape or a `$`** changed what the screen says
  (a `\n` became a literal backslash and `n`; a typed `$` became a template).
  Fixed in this bundle's editor and relay; the bundled editor's text field is
  now multi-line.
  An app's own editor (`keliver-new-editor.sh`, `dev.keliver:portal-editor`)
  gets the fix with the next library release. **Behaviour change:** a widget
  whose literal argument is a template or a concatenation is now RawCode in the
  editor; format the text in the presenter and bind it.
- The iOS host registered its foreground observer again after a start that
  found no bundle, so each return to the foreground checked twice.

## Upgrading a host

Host sources are yours. To take the update default, the check-now hook and the
timing marks, copy the changed files from this bundle's `templates/`,
replacing the `@@…@@` placeholders with your values (or scaffold a fresh host
beside yours and compare; the scaffolders fill them in):

- **Android, standalone** (`templates/production-host/`): `KeliverHost.kt`,
  `KeliverScreen.kt`, `build.gradle`, `src/timing/`, `src/notiming/`,
  `proguard-rules.pro`.
- **Android, embedded**: `KeliverHost.kt`, `KeliverScreen.kt`, `src/timing/`
  and `src/notiming/` from `templates/production-host/` (not `HostApp.kt` or
  `MainActivity.kt`, which the library leaves out), and `build.gradle` and
  `keliver.properties` from `templates/production-host-embed/`.
- **iOS** (`templates/ios-host/`): `MainViewController.kt`, `HostConfig.kt`'s
  `UPDATES` line, `build.gradle`, `src/timing/`, `src/notiming/`, and in the
  Xcode project the `Benchmark` configuration and `KELIVER_TIMING = YES` in
  the Debug configuration (without it a Debug build has no marks). An embedded
  iOS host: step 5 of `templates/ios-host-embed/EMBED.md`.
- **The guest:** copy `templates/guest/KeliverTiming.kt` to
  `src/jsMain/kotlin/device/KeliverTiming.kt`, with `@@PACKAGE@@` replaced by
  the package of your `device/Main.kt` minus `.device`.
- Rerun `keliver-new-publish-target.sh` only to upgrade a signing block from
  before 0.3.9; it is current since then.

## Candidate verification

**Candidate 3** (the one to tag, if approved):

| | |
|---|---|
| Source commit | `78bcfa0ad64bf4321699654d99a48647c14fac83` |
| Zip | `keliver-portal-tools-0.3.10.zip`, sha256 `0ba5c07b88c869084ee80870682d577aabc8ce83edcdb9caf19b4493db0e87bf` |
| Bundled host APK | sha256 `48b004cedc39af7c3f42764ca802241a543a95691888b88343878d696d1b0083`, no `assets/portal_ed25519.pub` |
| Build | run 38996395407, retained artifact `12087402781` (`keliver-portal-tools-0.3.10-78bcfa0ad…`, expires 2026-11-16) |
| Device job | run 38997931981 (dispatched from `release/portal-tools-0.3.10`, pinned to the build's APK): 19/0 and 28/0, on GitHub's emulator |

Checked locally, on the downloaded artifact (2026-10-17):

- its sha256 equals the build run's; `VERSION.json`: tools 0.3.10, source
  commit as above, 0 dirty files, Maven dependency 0.3.4; the APK's sha256
  equals the device run's and it carries no portal key;
- the 61 files under `bin/` and `templates/` are byte-identical to the source
  commit's `scripts/` and `scripts/templates/`;
- `keliver-adopter-acceptance.sh`: 25/0; `keliver-acceptance-identity-check.sh`: 6/0;
- step 3 at the source commit: a `portal-tools-v0.3.10` tag runs only
  `portal-tools.yml`, which has no write permission; `publish.yml` takes `v*`
  only, `ci.yml` ignores tags, `pages.yml` runs on `main` only, the rest are
  dispatch- or pull-request-only.

- `reference/inventory/ci/ios.sh` on the zip (`KELIVER_CANDIDATE_SHA256` set,
  every host and app scaffolded by the zip's own `bin/`), on this Mac's iOS
  simulator: 158/0, P1-P7, W3/W4, W2, W5, M0 (T1 Debug, T2 Benchmark) and W8.

- `keliver-device-verify.sh` on a phone, a Samsung S25 Ultra (SM-S938B) over
  wireless adb, 2026-10-17: the install, both production refusals (U22, cold
  6/6 and warm 6/6, nothing requested or loaded) and the development route
  after a refusal (4/4) passed: 18/18. Its packaged adopter acceptance with
  `--serial` passed 27 of 28: the one failure, "the device does not show the
  edit", is the bundled host's documented emulator-only development loop
  (`DEVICE_HOST.md`: it reaches the machine at `10.0.2.2`; on the phone,
  `codeLoadFailed: failed to connect to /10.0.2.2 (port 8080)`). The same
  script passed in full on GitHub's emulator (the device job above). The
  phone's Play Protect asked before each install; it was allowed by hand. Two
  earlier attempts on the phone were not runs of the candidate's checks: one
  was refused by Play Protect, one met port 8080 held by a Gradle daemon left
  from this Mac's `ios.sh` run (the check refuses a port it does not own).

Candidates 1 (`7ab383110`, build 38995449572) and 2 (`555150a9f`, build
38996130488) are superseded by the review fixes: never tag or attach them.
