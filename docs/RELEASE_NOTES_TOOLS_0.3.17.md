# keliver-portal-tools 0.3.17 — release notes

**Status: RELEASED** 2026-11-01 (09:52:28Z), by `PORTAL_TOOLS_RELEASE.md`, under the
owner's 2026-10-27 handover. Tag `portal-tools-v0.3.17` (annotated, `df6e93c40`) →
`7ea8940c383ce3b759575f6bb83e26d2c4e8b207`. Not marked "Latest" (`--latest=false`).

What is in it since 0.3.16 (`65346c63d`):
- #186 (M4: the guest frame profile);
- #188 and #191 (M5: the memory profile, iOS and Android);
- #190 (guest marks cannot take the hosts' own line names on Android);
- #192 (whole console lines on iOS).

#182 records the 0.3.16 release. #183–#185, #187, #189 and #193 move the reference app, its
harness, the tools device verifier (not in the bundle) and the records.

**The libraries stay 0.4.1.** Nothing published to Maven Central changed
since 0.4.1. Every change here is in the scaffolders, their templates and
the docs; the relay is unchanged. New apps get `dev.keliver:*:0.4.1`, as
with 0.3.16. **A host scaffolded earlier gets none of it until it is
scaffolded again.** Everything below but the iOS console change (#192, every
build) is in debug and benchmark (iOS: Debug and Benchmark) builds only; a
store build compiles none of it.

## New: the guest frame profile (M4, #186)

Next to the timing marks, one `frames` line per window of up to 60 frames,
or 1 s after the window's first sample:

```
frames <nanos> n=12 empty=3 lists=10 changes=84 guest=850/2100/2400 lead=700/1900/2200 sink=40/90/120 apply=300/900/1100 queue=60/400/700
```

Each span is p50/p95/max in microseconds:
- `guest`: the guest's `sendFrame` call;
- `lead`: from the frame's start to its change list reaching the host;
- `sink`: the host's `sendChanges`;
- `apply`: the apply on the main thread;
- `queue`: the wait for the main thread.

The output goes where the marks go (logcat tag `KeliverTiming`, the iOS console, `timing.log`).
Composition apart from encoding inside `guest` needs a library change (a later 0.4.x).
`DEVICE_HOST.md`, "The guest frame profile".

## New: the memory profile (M5, #188 iOS, #191 Android)

One `memory` line at each of a screen's points (`open`, `ui`, `runtime`, `code`, `content`)
records:
- iOS: the process's footprint;
- Android: rss, and the Java and native heaps;
- both: QuickJS's heap.

A **memory cycle** opens a second screen over the shown one, closes it and samples `closed`
after collecting the heaps:
- iOS adds the Kotlin heap there;
- Android adds `pss` and `graphics`.

Growth from one cycle's `closed` to the next is what a screen did not give back.

Run a cycle:
- iOS: post the Darwin notification `<bundle id>.keliver.memory-cycle`
  (`xcrun simctl spawn <udid> notifyutil -p <name>`, or `xcrun devicectl device notification
  post` on a phone).
- Android: `adb shell am broadcast -a dev.keliver.debug.MEMORY_CYCLE -p <applicationId>`
  (only a sender holding `DUMP`, as adb's shell does, is heard).

On Android, a minified `benchmark` build reads QuickJS only because R8 keeps
`app.cash.zipline.MemoryUsage`, which QuickJS builds through JNI. The standalone host's
`proguard-rules.pro` and the embedded host's consumer rules do this. An app minifying with
rules of its own needs that line too. `DEVICE_HOST.md`, "The memory profile".

First numbers, from CI (KiB / 1024; debug, benchmark in brackets):
- **iOS simulator** (#188): 21.6 MiB footprint at `open` (17.8); QuickJS's heap 4.6 MiB at
  `code` (4678 KiB: 93 before any code, +4585 with it); 45.8 MiB (40.7) at the first cycle's
  close.
- **Android emulator** (#191): rss 181.9 MiB at `open` (112.8); QuickJS's heap 4.6 MiB at
  `code`, as on iOS; `pss` 110.3 MiB (39.2) at the first cycle's close.

From one cycle to the next, QuickJS grew a few KiB on both. The two hosts count different
things (footprint and `pss`), so their numbers do not compare. No phone numbers yet.

## Changed: guest marks cannot take the hosts' own line names (#190)

A guest mark named `memory` or `frames` is ignored on Android too, as on iOS since #188: it
would read as one of the host's own lines.

## Fixed: whole console lines on iOS (#192)

Kotlin/Native's `println` writes a line's text and its newline separately, so a line printed
on another thread could land in between: one CI run read a `memory` line and a `KeliverHost:`
line as one. Every line the iOS host prints now goes through `ConsoleLine.kt`, as one
`write(2)` under a lock. A library's own `println` (Zipline's, the runtime's) still can
interleave.

## Verification

| what | identity |
|---|---|
| source commit | `7ea8940c383ce3b759575f6bb83e26d2c4e8b207` (the candidate commit on PR #194; `VERSION.json`'s `sourceCommit`, `sourceDirtyFiles` 0) |
| zip | `keliver-portal-tools-0.3.17.zip`, sha256 `2c6e69d64d142e4282f341d1e5879dd21c689c915265f56c2582782b4ad8b785`, 92,132,756 bytes |
| `.sha256` file | 98 bytes, sha256 `10d36890e5460c41048116679fd982861d52bfb7724bd8d45497b54905e81d16` |
| `VERSION.json` | toolsVersion 0.3.17, mavenDependencyVersion 0.4.1 |
| device host APK | `host/keliver-device-host-0.3.17.apk`, sha256 `9f12b1fc4d621a336de7b4f2e400fe866410bea4b511f1e43067a8822778b6e8`; no `assets/portal_ed25519.pub` |
| candidate build | `portal-tools.yml` run `41291720452` (bundle job `124804166537`) at `7ea8940c3`; retained artifact `13078906556` (artifact zip `e1bad49d…`) |
| packaged checks in that build | adopter acceptance 25/0 (with the U21 identity gate), foreign-relay refusal 6/0, store identity 11/0, device-host hygiene 10/0, store recovery 144/0, production-host scaffolder 84/0 (from `scripts/`) and 74/0 (from the package), iOS host 75/0 and 75/0, publish target 51/0 and 33/0, screen and component scaffolders 9/0, `keliver-run-android` 22/0, the embed floor 11/0, `keliver-publish` 41/0, key permissions 63/0 |
| device job | `portal-tools.yml` run `41294342437` (job `124811933096`), dispatched from `release/tools-0.3.17` at `c52668d86` (differs from the candidate only in these notes) against the artifact above, APK sha256 pinned: the device verifier 19/0 and its packaged acceptance 28/0, on the CI emulator |
| checked locally | the artifact downloaded through `gh api …/actions/artifacts/13078906556/zip`: both sha256s, `VERSION.json`, no key in the APK, and the shipped templates carry #191's `KeliverTiming.mark(name)`, both `MemoryUsage` keep rules and #192's `consoleLock` with no `println` |
| tag push | ran only `portal-tools.yml`'s read-only rebuild (`41294843724`), never attached; step 3 found no workflow at `7ea8940c3` that can write a release |
| stored assets | release `430489073`: the zip (asset `697925066`) and the `.sha256` (asset `697925065`), server digests and sizes equal to the files above |
| draft download-back | worked: sha256 `2c6e69d6…`, 92,132,756 bytes |
| public download | worked from the releasing machine after publishing: the zip `2c6e69d6…`, 92,132,756 bytes, and the public `.sha256` names the same hash |
| public download, GitHub-hosted runners | the reference app's move to 0.3.17 (PR #195): the public-download step of reference-app `41295173387` (ubuntu) and ios-host `41295173576` (macos) fetched `2c6e69d6…`; the hand runs `41295181893` (device 201/0) and `41295184572` (`ios.sh` 183/0) ran the hosts from it |

Not verified for this release (the published hosts ran M4 and M5 in CI through #195, row above):
- either profile on a phone: M4 and M5 ran only on the CI emulator and simulator;
- a leak bound: none is set;
- iOS's frame profile after a tap: the iOS harness has no tap step, so its T3 sees one empty
  launch frame.
