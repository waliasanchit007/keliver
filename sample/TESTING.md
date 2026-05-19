# Sample app — end-to-end test log

The first end-to-end run of `sample/` against a real Android
emulator surfaced six bugs/gaps between what the [README](README.md)
described and what actually worked. This document records each
finding with the symptom, the root cause, and the fix, so the next
person running the sample (or the next adopter copying it) doesn't
hit the same dead ends.

**Final state**: `./gradlew :host-android:installDebug` against a
Pixel 9 emulator, with `python3 -m http.server 8080` serving
`guest/build/zipline/Development/`, renders `Box { Text("Hello,
Konduit!") }` correctly. The full Zipline RPC handshake is visible
in `adb logcat` under the `KonduitSample` tag.

## Test setup (reproducible)

```sh
# 1. Publish konduit to mavenLocal (the sample resolves from there
#    first, falling back to GH Packages only when something isn't
#    locally available).
cd /path/to/konduit
./gradlew publishToMavenLocal -x test -x dokkaJavadocJar \
    -x signMavenPublication \
    -x :konduit-http-codegen:publishMavenPublicationToMavenLocal
# 17 minutes cold; 5-10 min if the source set is unchanged.

# 2. Build + serve the guest bundle. Konduit + Zipline don't ship a
#    `serveDevelopmentZipline` Gradle task (the README's reference
#    was wrong); Python's stdlib http.server is the smallest
#    workaround. See Finding #1.
cd /path/to/konduit/sample
./gradlew :guest:compileDevelopmentZipline
(cd guest/build/zipline/Development && python3 -m http.server 8080) &

# 3. Start a Pixel emulator if one isn't already running.
~/Library/Android/sdk/emulator/emulator -list-avds
nohup ~/Library/Android/sdk/emulator/emulator -avd <AVD_NAME> \
    -no-snapshot-load -no-audio -no-boot-anim \
    > /tmp/emulator.log 2>&1 & disown
until adb shell getprop sys.boot_completed 2>/dev/null | grep -q 1; do sleep 5; done

# 4. Install + launch.
./gradlew :host-android:installDebug
adb shell pm clear dev.konduit.sample
adb logcat -c
adb shell am start -n dev.konduit.sample/dev.konduit.sample.host.MainActivity
sleep 15
adb logcat -d | grep -E "KonduitSample|FATAL|AndroidRuntime"
```

The expected end-state log lines, in order, are:

```
KonduitSample: onCreate — manifest URL: http://10.0.2.2:8080/manifest.zipline.json
KonduitSample: manifestReady modules=30
KonduitSample: takeService name=zipline/guest
KonduitSample: bindService name=zipline/host
KonduitSample: ziplineCreated
KonduitSample: mainFunctionStart app=konduit-sample
KonduitSample: mainFunctionEnd app=konduit-sample
KonduitSample: codeLoadSuccess modules=30
KonduitSample: takeService name=app
…
```

If you see `codeLoadSuccess` followed by `takeService name=app`,
the host has the guest's `SampleAppService` proxy and the widget
is on its way to the screen.

## Findings

### #1 — README referenced a Gradle task that doesn't exist

**Symptom.** The original README told adopters to run
`./gradlew :guest:serveDevelopmentZipline` to host the bundle.
`./gradlew tasks --all` in `:guest` returns no such task — only
`jsBrowserDevelopmentRun` (webpack-dev-server) and the build-only
`compileDevelopmentZipline`. Adopters following the README would
have nothing serving the manifest on `:8080`.

**Root cause.** The README was written from memory. Newer Zipline
Gradle plugin versions don't ship a serve task — the assumption is
that adopters integrate with their own dev-server tooling.

**Fix.** Updated `README.md` to use `python3 -m http.server 8080`
from `guest/build/zipline/Development/`. ServerDrivenUI runs its
own bespoke `:dev-server` Ktor module for the same purpose; that's
overkill for the sample.

### #2 — Sample silently failed because no `EventListener` was wired

**Symptom.** First launch showed a blank white screen. No crash, no
log output beyond a single `onCreate` line from MainActivity. The
Zipline bundle downloaded successfully (verified by watching the
Python server's access log) but nothing visible happened on the
host side, and no logcat clue pointed at the cause.

**Root cause.** The sample's `TreehouseAppFactory.create(...)` call
omitted the `eventListenerFactory` argument. Konduit's Treehouse
events (`manifestReady`, `codeLoadSuccess`, `codeLoadFailed`,
`uncaughtException`, etc.) are all delivered through that
listener; with no listener wired, the host has no idea anything
went wrong — guest exceptions don't surface, crashes are silent.

**Fix.** Added a `LoggingEventListenerFactory` to `MainActivity.kt`
that logs every event under the `KonduitSample` tag. Adopters can
swap this for their own production listener (or drop it in release
builds), but at least the sample now ships a working debug
baseline.

This is **U1/U2/U3 of `konduit/docs/KNOWN_BUGS.md`** — the silent-
failure shape Konduit's hardening work has been chipping away at.
The sample now ships an explicit example of how to escape it.

### #3 — `Adapter.<init>` IrLinkageError at QuickJS load time

**Symptom.** With the EventListener wired, the real error finally
surfaced:

```
codeLoadFailed: Constructor 'Adapter.<init>' can not be called:
  No constructor found for symbol 'dev.konduit.sample.shared/
    SampleAppService.Companion.Adapter.<init>|<init>(
      kotlin.collections.List<kotlinx.serialization.KSerializer<*>>;
      kotlin.String){}[0]'
```

The guest bundle was loaded fine, all 30 `.zipline` modules
downloaded and parsed by QuickJS, but at the point Zipline tried to
hook up the `SampleAppService` adapter the IR linker complained
that no matching constructor existed.

**False leads tried before finding the real fix:**

1. *Zipline version mismatch* — bumped the sample from 1.26.0 down
   to 1.22.0 (matching `konduit/gradle/libs.versions.toml`). No
   change.
2. *Kotlin version mismatch* — tried both Kotlin 2.1.0 + Zipline
   1.26.0 (matching ServerDrivenUI's stack) and Kotlin 2.2.0 +
   Zipline 1.22.0 (matching Konduit's). No change.
3. *Stale GH Packages artifacts* — published Konduit fresh to
   `mavenLocal` so the sample picked up the current working-tree
   build. No change.

None of those addressed the actual problem.

**Root cause.** Zipline issue
[#765](https://github.com/cashapp/zipline/issues/765) — the
Zipline IR plugin **cannot auto-generate** an Adapter for an
interface that transitively extends `ZiplineService` via Konduit's
`AppService`. The class gets emitted with the wrong constructor
shape, the linker rejects it.

`AppService.kt` even calls this out in a comment ("Note that due
to a Zipline limitation it's necessary for implementing classes to
declare a direct dependency on `ZiplineService`"), but it doesn't
mention that the interface side needs a manual workaround too.

**Fix.** Copied ServerDrivenUI's pattern: a companion-object
`Adapter` class on `SampleAppService` that extends a hand-written
`ManualSampleAppServiceAdapter` providing the body. The manual
adapter touches three `INVISIBLE_REFERENCE` Zipline internals
(`OutboundCallHandler`, `OutboundService`, `ReturningZiplineFunction`,
`ZiplineServiceAdapter`) which the file silences via a top-level
`@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", ...)`.

This is significant adopter-onboarding friction. Until Zipline
#765 is fixed upstream, every Konduit adopter writing a new
`AppService` subinterface needs to hand-roll this same ~60 lines
of adapter glue. It belongs in `konduit-treehouse` as a helper
(future work).

### #4 — `:shared` was missing the Zipline + kotlinSerialization plugins

**Symptom.** Same `IrLinkageError` as #3, just exposed earlier in
the diagnosis: even with the manual adapter, the Zipline IR
transform on `SampleAppService.kt` needs the Zipline plugin applied
to the module that owns the interface.

**Root cause.** The sample's `:shared/build.gradle.kts` originally
only applied `kotlinMultiplatform`. ServerDrivenUI's
`:shared/build.gradle.kts` applies *three* plugins:
`kotlinMultiplatform`, `zipline`, and `kotlinSerialization`.

The Zipline plugin transforms `take`/`bind` call sites AND scans
for ZiplineService interfaces. The serialization plugin emits
`@Serializable` companion `.serializer()` lookups, which the
generated adapter calls for every method's param/return types.

**Fix.** Added both plugins to `:shared/build.gradle.kts`.

### #5 — `:host-android` and `:host-compose` were also missing the Zipline plugin

**Symptom.** After fixing #3 and #4, `codeLoadSuccess` finally
appeared in the log, but the app crashed immediately after:

```
FATAL EXCEPTION: Treehouse konduit-sample
java.lang.IllegalStateException: unexpected call to Zipline.take:
  is the Zipline plugin configured?
    at app.cash.zipline.Zipline.take(Zipline.kt:122)
    at MainActivity$onCreate$spec$1.create(MainActivity.kt:95)
```

**Root cause.** The Zipline IR plugin must be applied to **every
Gradle module that contains a `zipline.take<T>` or `zipline.bind<T>`
call**, not just the module that defines the service interface. The
plugin transforms each call site into a code path that uses the
correct generated/manual adapter; without it, you get a stub that
throws the message above.

In the sample, `zipline.take("app")` lives in
`host-android/MainActivity.kt`. The same call also lives in
`host-compose/MainViewController.kt` for the iOS shell. Neither
module had the Zipline plugin applied.

**Fix.** Added `alias(libs.plugins.zipline)` to both
`host-android/build.gradle.kts` and `host-compose/build.gradle.kts`.
ServerDrivenUI applies it to both `androidApp` and `composeApp`
for the same reason — same error shape if either is missing.

### #6 — Final state (positive)

After fixes #2 through #5 landed, the sample renders correctly.
Logcat shows the full Zipline RPC handshake — host takes `app`,
then guest-1/2/3/4 ↔ host-1/2/3/4/5 service pairs as the protocol
streams widget mutations across the boundary:

```
KonduitSample: takeService name=app
KonduitSample: takeService name=zipline/guest-1
KonduitSample: bindService name=zipline/host-1
KonduitSample: takeService name=zipline/guest-2
KonduitSample: bindService name=zipline/host-2
…
```

Screenshot confirms `Hello, Konduit!` painted at top-start of the
host's `Box`. The sample is now a true end-to-end working
reference.

## Net adopter-impact summary

A first-time adopter who took the original sample and followed the
original README would have hit *all five* dead ends in sequence.
Each one has a now-fixed, now-documented root cause. The biggest
single lift is #3 (Zipline #765 workaround) — that's structural
and won't go away until Zipline upstream fixes the IR plugin. The
other four are README + boilerplate gaps that this commit closes.

Status going forward:

| # | Finding | Resolution |
|---|---|---|
| 1 | `serveDevelopmentZipline` task doesn't exist | README updated to use `python3 -m http.server` |
| 2 | Silent failures with no `EventListener` | Sample now ships `LoggingEventListenerFactory` |
| 3 | `Adapter.<init>` IrLinkageError (Zipline #765) | Manual adapter + companion object workaround |
| 4 | `:shared` missing Zipline + serialization plugins | Both added to `:shared/build.gradle.kts` |
| 5 | Host modules missing Zipline plugin | Added to `:host-android` + `:host-compose` |

The right long-term fix for #3 is a `konduit-treehouse` helper
that hides the adapter boilerplate behind a single API. Filed as
follow-up.
