# keliver-portal-tools 0.3.10 — release notes

**Status: CANDIDATE.** Not tagged or published yet. The verification is
recorded below as it happens.

**The scaffolders now wire the 0.3.4 libraries** (`dev.keliver:*:0.3.4`, on
Maven Central since 2026-10-16): `keliver-init`, `keliver-new-device-target.sh`,
`keliver-new-editor.sh`, and the host scaffolders' fallback when an app's
`keliver.portal.json` names no version. An existing app keeps the version its
`appRuntime.keliverVersion` names; see the library CHANGELOG's [0.3.4] for what
moving changes (a binary-incompatible `align` parameter on `TextField` and
`OutlinedTextField`, among the widget fixes). Tools 0.3.9 and earlier are
unchanged.

## Behaviour changes

- **Production bundles by default.** `keliver-new-publish-target.sh` writes
  `:compileProductionExecutableKotlinJsZipline` / `build/zipline/Production`
  (minified: in the 2026-10 adoption proof 2.36 MB raw against 4.28 MB, and
  faster to evaluate). `--development` writes the old Development pair. An app
  that already has either pair keeps it, so rerunning the script to upgrade a
  signing block changes no bundle.
- **Debug hosts check for updates on resume.** `keliver.updates` (Android) and
  `UPDATES` (iOS `HostConfig.kt`) are empty by default: `on-resume` in a debug
  build, `next-launch` in every other build. A value there applies to every
  build. Hosts scaffolded by 0.3.9 and earlier wrote `next-launch` and keep it;
  empty the setting to get the new default.

## New

- **"Check for an update now", in debug builds**, not held to the 30 s between
  on-resume checks: `adb shell am broadcast -a dev.keliver.debug.CHECK_FOR_UPDATE
  -p <applicationId>` on Android (a receiver registered at run time, reachable
  only from adb's shell), the Darwin notification
  `<bundle id>.keliver.check-for-update` on iOS. Release builds have neither.
- **A debug host takes its bundle server at launch**, so a new tunnel URL needs
  no rebuild: `files/keliver/bundle-server` (Android, written with `run-as`),
  the `KELIVER_BUNDLE_SERVER` launch environment variable (iOS). Every bundle
  still verifies against the embedded key, and the rollback floor holds.
- **Timing marks** (`DEVICE_HOST.md`, "Timing marks"): `process` (Android),
  `open`, `code`, `first-frame` and `content` (the guest's
  `KeliverTiming.mark("content")`), on monotonic clocks, to logcat / the console
  / `Keliver.shared.onTrace` and a file in the app's container. Only debug
  builds and a new **benchmark** build compile them: Android's `benchmark` build
  type (release settings, minified with R8, profileable, debug-signed) and iOS's
  `Benchmark` configuration (Release settings), never shipped. A store build
  compiles none of it. `keliver-new-device-target.sh` writes
  the guest's `device/KeliverTiming.kt`.

## The relay

- **The full Gradle output of every publish** is kept in
  `<store>/publish-logs/` (owner-only; the newest 20), and the response names
  the file. The response itself still carries the last 30 lines.
- **`keliver.portal.json` is read at every publish**: a changed `publishTask` or
  `publishOutput` takes effect without a restart. Other changed settings are
  named in the response as needing one.
- **A publish whose build fails with a Kotlin "Internal compiler error" is built
  once more** (KNOWN_BUGS U34: the first publish after another Gradle build
  sometimes failed that way; the cause is not known). Any other failure is
  reported at once.

## Fixed

- **U33: editing a text with an escape or a `$`** changed what the screen says
  (a `\n` became a literal backslash and `n`; a typed `$` became a template).
  The editor's text field is now multi-line. **Behaviour change:** a widget
  whose literal argument is a template or a concatenation is now RawCode in the
  editor; format the text in the presenter and bind it.
- The iOS host registered its foreground observer again after a start that
  found no bundle, so each return to the foreground checked twice.

## Upgrading an app

- Rerun `keliver-new-publish-target.sh` only if you want the Production pair;
  your signing block is current since 0.3.9.
- Host sources are yours: to take the update default, the check-now hook and
  the timing marks, copy the changed files from this bundle's `templates/`
  (`KeliverHost.kt`, `KeliverScreen.kt`, `build.gradle`, `src/timing/`,
  `src/notiming/`, `proguard-rules.pro`; iOS: `MainViewController.kt`,
  `HostConfig.kt`'s `UPDATES` line, `build.gradle`, `src/timing/`,
  `src/notiming/` and the `Benchmark` configuration), or scaffold a fresh host
  beside yours and compare.

## Candidate verification

To be recorded: the build run and its retained artifact, the device run, the
local check of the artifact, the acceptance and identity checks, the device
verify on a phone, `ios.sh` on the zip, and the step-3 workflow check.
