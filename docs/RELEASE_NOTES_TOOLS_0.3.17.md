# keliver-portal-tools 0.3.17 — release notes

**Status: CANDIDATE**, by `PORTAL_TOOLS_RELEASE.md`. Identities are filled in
as each step of the procedure passes.

What is in it since 0.3.16 (`65346c63d`):
- #186 (M4: the guest frame profile);
- #188 and #191 (M5: the memory profile, iOS and Android);
- #190 (guest marks cannot take the hosts' own line names on Android);
- #192 (whole console lines on iOS).

#182–#185, #187, #189 and #193 move the reference app, its harness, the tools device verifier
and the records.

**The libraries stay 0.4.1.** Nothing published to Maven Central changed
since 0.4.1. Every change here is in the scaffolders' host templates and
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
- **iOS simulator** (#188): 21.6 MiB footprint at `open` (17.8); QuickJS 4.6 MiB once the
  code is loaded; 45.8 MiB (40.7) at the first cycle's close.
- **Android emulator** (#191): rss 181.9 MiB at `open` (112.8); QuickJS 4.6 MiB; `pss`
  110.3 MiB (39.2) at the first cycle's close.

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

(to be filled in: source commit, zip sha256 and size, `VERSION.json`, APK,
build run and artifact, device job, the packaged checks, the stored asset's
digest, the public download check)

Not verified for this release:
- either profile on a phone: M4 and M5 ran only on the CI emulator and simulator;
- a leak bound: none is set;
- iOS's frame profile after a tap: the iOS harness has no tap step, so its T3 sees one empty
  launch frame.
