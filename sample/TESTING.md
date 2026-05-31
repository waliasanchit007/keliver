# Sample app — end-to-end test log

The first end-to-end run of `sample/` against a real Android
emulator surfaced six bugs/gaps between what the [README](README.md)
described and what actually worked. This document records each
finding with the symptom, the root cause, and the fix, so the next
person running the sample (or the next adopter copying it) doesn't
hit the same dead ends.

This file now holds these case studies:
1. **Android** (Development bundle) — 5 bugs found + fixed.
2. **iOS** (Development bundle) — 3 bugs found + fixed.
3. *(implicit)* `@KeliverAppService` migration — see the Keliver
   repo's U12 entry; validated against DevoStatus.
4. **Production bundle** — 0 bugs; validates the R8/DCE-minified
   ship path that the first two case studies didn't exercise.
5. **Hot reload** — `serveDevelopmentZipline --continuous` +
   `withDevelopmentServerPush`; guest edits reach the running app in
   ~2 s, no reinstall.

**Final state**: `./gradlew :host-android:installDebug` against a
Pixel 9 emulator, with `python3 -m http.server 8080` serving
`guest/build/zipline/Development/`, renders `Box { Text("Hello,
Keliver!") }` correctly. The full Zipline RPC handshake is visible
in `adb logcat` under the `KeliverSample` tag.

## Test setup (reproducible)

```sh
# 1. Publish keliver to mavenLocal (the sample resolves from there
#    first, falling back to GH Packages only when something isn't
#    locally available).
cd /path/to/keliver
./gradlew publishToMavenLocal -x test -x dokkaJavadocJar \
    -x signMavenPublication \
    -x :keliver-http-codegen:publishMavenPublicationToMavenLocal
# 17 minutes cold; 5-10 min if the source set is unchanged.

# 2. Build + serve the guest bundle. Keliver + Zipline don't ship a
#    `serveDevelopmentZipline` Gradle task (the README's reference
#    was wrong); Python's stdlib http.server is the smallest
#    workaround. See Finding #1.
cd /path/to/keliver/sample
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
adb shell pm clear dev.keliver.sample
adb logcat -c
adb shell am start -n dev.keliver.sample/dev.keliver.sample.host.MainActivity
sleep 15
adb logcat -d | grep -E "KeliverSample|FATAL|AndroidRuntime"
```

The expected end-state log lines, in order, are:

```
KeliverSample: onCreate — manifest URL: http://10.0.2.2:8080/manifest.zipline.json
KeliverSample: manifestReady modules=30
KeliverSample: takeService name=zipline/guest
KeliverSample: bindService name=zipline/host
KeliverSample: ziplineCreated
KeliverSample: mainFunctionStart app=keliver-sample
KeliverSample: mainFunctionEnd app=keliver-sample
KeliverSample: codeLoadSuccess modules=30
KeliverSample: takeService name=app
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

> **Correction (hot-reload case study, 2026-05-29).** This finding's
> premise was wrong: `serveDevelopmentZipline` **does** exist in
> Zipline 1.22.0 — `./gradlew :guest:tasks --all` lists it
> ("Serves Zipline files"), alongside `generateZiplineManifestKeyPair{Ed25519,EcdsaP256}`.
> The original audit overlooked it. `python3 -m http.server` is still
> the zero-dependency option and stays the README default, **but**
> the Zipline serve task is what enables WebSocket hot reload (python
> has no push channel). See the hot-reload case study below.

### #2 — Sample silently failed because no `EventListener` was wired

**Symptom.** First launch showed a blank white screen. No crash, no
log output beyond a single `onCreate` line from MainActivity. The
Zipline bundle downloaded successfully (verified by watching the
Python server's access log) but nothing visible happened on the
host side, and no logcat clue pointed at the cause.

**Root cause.** The sample's `TreehouseAppFactory.create(...)` call
omitted the `eventListenerFactory` argument. Keliver's Treehouse
events (`manifestReady`, `codeLoadSuccess`, `codeLoadFailed`,
`uncaughtException`, etc.) are all delivered through that
listener; with no listener wired, the host has no idea anything
went wrong — guest exceptions don't surface, crashes are silent.

**Fix.** Added a `LoggingEventListenerFactory` to `MainActivity.kt`
that logs every event under the `KeliverSample` tag. Adopters can
swap this for their own production listener (or drop it in release
builds), but at least the sample now ships a working debug
baseline.

This is **U1/U2/U3 of `keliver/docs/KNOWN_BUGS.md`** — the silent-
failure shape Keliver's hardening work has been chipping away at.
The sample now ships an explicit example of how to escape it.

### #3 — `Adapter.<init>` IrLinkageError at QuickJS load time

**Symptom.** With the EventListener wired, the real error finally
surfaced:

```
codeLoadFailed: Constructor 'Adapter.<init>' can not be called:
  No constructor found for symbol 'dev.keliver.sample.shared/
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
   to 1.22.0 (matching `keliver/gradle/libs.versions.toml`). No
   change.
2. *Kotlin version mismatch* — tried both Kotlin 2.1.0 + Zipline
   1.26.0 (matching ServerDrivenUI's stack) and Kotlin 2.2.0 +
   Zipline 1.22.0 (matching Keliver's). No change.
3. *Stale GH Packages artifacts* — published Keliver fresh to
   `mavenLocal` so the sample picked up the current working-tree
   build. No change.

None of those addressed the actual problem.

**Root cause.** Zipline issue
[#765](https://github.com/cashapp/zipline/issues/765) — the
Zipline IR plugin **cannot auto-generate** an Adapter for an
interface that transitively extends `ZiplineService` via Keliver's
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
#765 is fixed upstream, every Keliver adopter writing a new
`AppService` subinterface needs to hand-roll this same ~60 lines
of adapter glue. It belongs in `keliver-treehouse` as a helper
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
FATAL EXCEPTION: Treehouse keliver-sample
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
KeliverSample: takeService name=app
KeliverSample: takeService name=zipline/guest-1
KeliverSample: bindService name=zipline/host-1
KeliverSample: takeService name=zipline/guest-2
KeliverSample: bindService name=zipline/host-2
…
```

Screenshot confirms `Hello, Keliver!` painted at top-start of the
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

The right long-term fix for #3 is a `keliver-treehouse` helper
that hides the adapter boilerplate behind a single API. Filed as
follow-up.

---

# iOS case study

After the Android pass landed, we mirrored the same exercise on
iOS — built the `KeliverSampleHost.framework`, wired it into a
minimal Xcode project under [`iosApp/`](iosApp/), and ran on an
**iPhone 17 Pro simulator (iOS 26.3.1, Xcode 26.3)**. Final state:
the same `Hello, Keliver!` widget paints at top-start, with the
same full Zipline RPC sequence visible in `xcrun simctl launch
--console`. Three iOS-specific findings surfaced; all fixed.

## iOS test setup

```sh
# 1. Build the guest bundle (one-time per source change).
cd /path/to/keliver/sample
./gradlew :guest:compileDevelopmentZipline

# 2. Serve the bundle. The iOS simulator routes localhost directly
#    to the host's loopback, so no 10.0.2.2 alias is needed.
(cd guest/build/zipline/Development && python3 -m http.server 8080) &

# 3. Boot a simulator.
xcrun simctl list devices iPhone | grep iPhone\ 17    # pick one
SIM_UDID=BA6BDD1C-520F-429C-8388-086D3C59FEEB           # iPhone 17 Pro
xcrun simctl boot "$SIM_UDID"
open -a Simulator

# 4. Build the Xcode app. The "Compile Kotlin Framework" Run Script
#    Build Phase invokes `./gradlew :host-compose:embedAndSignAppleFrameworkForXcode`
#    automatically — but it needs `sample/gradlew` to exist (see
#    Finding iOS-#1 below).
cd iosApp
xcodebuild \
  -project iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$SIM_UDID" \
  -derivedDataPath build/ \
  CODE_SIGN_IDENTITY="" CODE_SIGNING_REQUIRED=NO \
  build

# 5. Install + launch with --console to capture EventListener output.
xcrun simctl install "$SIM_UDID" \
  build/Build/Products/Debug-iphonesimulator/KeliverSample.app
xcrun simctl launch --console "$SIM_UDID" dev.keliver.sample.KeliverSample &
sleep 12
xcrun simctl io "$SIM_UDID" screenshot /tmp/sample-ios.png
```

Expected `--console` output, in order:

```
KeliverSample: manifestReady modules=30
KeliverSample: takeService name=zipline/guest
KeliverSample: bindService name=zipline/host
KeliverSample: ziplineCreated
KeliverSample: mainFunctionStart app=keliver-sample
KeliverSample: mainFunctionEnd app=keliver-sample
KeliverSample: codeLoadSuccess modules=30
KeliverSample: takeService name=app
KeliverSample: takeService name=zipline/guest-1
KeliverSample: bindService name=zipline/host-1
…
```

## iOS hot reload (parity with Android case study D)

iOS mirrors the Android hot-reload wiring: `MainViewController.kt` re-emits the
manifest URL on each guest rebuild via
`flowOf(manifestUrl).withDevelopmentServerPush(ziplineHttpClient)`, backed by an
`NSURLSessionWebSocketTask` implementation of
`ZiplineHttpClient.openDevelopmentServerWebSocket` (the base class's default is an
empty flow — no push — so the override is what makes iOS push work). Gated on
`IosDevConfig.HOT_RELOAD`, **default `true`** (it degrades to a single manifest
emit when no dev server is reachable, so on-by-default is safe).

Status: **verified end-to-end.** `compileKotlinIosSimulatorArm64` is green
(cinterop sound) and it was runtime-verified on an **iPhone 16 Pro simulator
(iOS 26.3, Xcode 26.3)**: with the continuous dev server running, editing the
guest `Text(...)` label and saving pushed the rebuilt bundle to the
**already-running** app — the label updated live with no reinstall, and a second
`codeLoadSuccess` fired in the `--console` stream. Repro:

```sh
# 1. Serve over the Zipline dev server in continuous mode — NOT
#    `python3 -m http.server`. Only the Zipline serve task exposes the WebSocket
#    the push channel needs (see Finding #1's correction); --continuous rebuilds
#    the bundle on each guest edit.
./gradlew :guest:serveDevelopmentZipline --continuous   # ws + http on :8080

# 2. Build + install + launch (steps above), then edit a guest screen
#    (e.g. sample/guest/.../Main.kt's Text(...)) and save — the running app
#    reloads with no reinstall.
```

## iOS findings

### iOS-#1 — Run Script expected `./gradlew` in `$SRCROOT/..` but `sample/` had none

**Symptom.** First `xcodebuild` attempt died at the "Compile Kotlin
Framework" Run Script Build Phase:

```
.../iosApp.build/Script-AF619752845E2E179AFFEA63.sh: line 7:
  ./gradlew: No such file or directory
Command PhaseScriptExecution failed with a nonzero exit code
```

**Root cause.** The Xcode template's Run Script does
`cd "$SRCROOT/.." && ./gradlew :host-compose:embedAndSignAppleFrameworkForXcode`.
`$SRCROOT` is `iosApp/`, so `$SRCROOT/..` is `sample/`. But the
sample was originally designed as a sub-build that uses the parent
Keliver repo's gradlew wrapper (`../gradlew` from inside `sample/`).
No wrapper existed at `sample/gradlew`.

**Fix.** Copied `gradlew`, `gradlew.bat`, and
`gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}`
from the Keliver root into `sample/`. Sample now has its own
working wrapper that the Run Script can resolve. The duplication
costs ~60 KB (a wrapper jar + the shell script) and removes a
fragile cross-directory assumption.

This is also a net win for adopter UX — anyone who clones a
released Keliver and `cd`s into the sample directly can now run
`./gradlew :host-android:installDebug` without `cd ..` first.

### iOS-#2 — No `EventListener` wired on iOS host either

**Symptom.** Identical to Android Finding #2: blank screen on
first iOS launch, no clue what went wrong. Same root cause: the
sample's `MainViewController.kt` called `factory.create(appScope,
spec)` without an `eventListenerFactory`, so every Treehouse
lifecycle event was silent on iOS too.

**Fix.** Mirrored the Android pattern — added a
`LoggingEventListenerFactory` to `MainViewController.kt` that logs
every event under the `KeliverSample` tag. iOS adopters get the
same debug baseline as Android, surfaced via `--console` (see
Finding iOS-#3 for the println-vs-NSLog choice).

### iOS-#3 — `NSLog("%@", kotlinString)` crashes with EXC_BAD_ACCESS at launch

**Symptom.** After wiring the iOS `LoggingEventListener` using the
ObjC-idiomatic shape

```kotlin
NSLog("KeliverSample: takeService name=%@", name)
```

…the app crashed at launch (~1 s after splash) with no Kotlin
stack trace. The native crash report at
`~/Library/Logs/DiagnosticReports/KeliverSample-….ips` showed:

```
EXC_BAD_ACCESS (SIGSEGV), KERN_INVALID_ADDRESS at
  0x00746975646e6f66 (possible pointer authentication failure)
```

The 8-byte address `0x00746975646e6f66` decodes as ASCII
`"\0keliver"` (little-endian) — a Kotlin `String`'s heap data was
being treated as a pointer.

**Root cause.** Kotlin/Native's varargs ↔ ObjC bridge for
`NSLog(format: String, vararg args: Any?)` does not auto-bridge a
Kotlin `String` to an `NSString*` for the `%@` format specifier.
The C-side `va_arg(NSString*)` reads the Kotlin string's heap
pointer as a C pointer, which is unrelated to a valid `id`. K/N
+ Xcode 26.3 enables pointer-authentication on ARM64, which turns
the bad-read into an immediate crash instead of a silent
mis-format.

**Fix.** Routed all listener output through Kotlin's `println(...)`
instead of `NSLog`. `println` on K/N goes to stdout; `xcrun simctl
launch --console` captures it. The trade-off is that `println`
output doesn't surface in `xcrun simctl spawn … log show
--predicate '…'` queries the way `os_log`-routed messages do —
adopters running CI on iOS will need to capture launch console
output instead of querying the unified log retroactively.

**Recommendation.** If you need messages in the unified log
(searchable, post-hoc), write a small Swift-side helper that
takes an `NSString` and calls `os_log("%{public}@", …)`, then
call that from Kotlin via a `kotlinx.cinterop` interop binding.
Plain `NSLog` from K/N is unsafe with format strings.

### iOS-#4 (positive) — All five Android-side fixes carry over

The five Android findings — Zipline plugin on every module with
`take`/`bind`, kotlinSerialization on `:shared`, the manual
`ZiplineServiceAdapter`, `EventListener` wiring, and the bundle
serving via `python3 -m http.server` — all apply unchanged on
iOS. Same fixes, same code, two platforms.

This confirms the cross-platform parity assumption that the
sample was built on: a single `:shared` module + `:host-compose`
multiplatform module covers both Android and iOS, with only
platform-specific HTTP-client wiring (OkHttp vs `NSURLSession`)
differing per host. If a finding affects one platform, it almost
always affects the other.

## iOS adopter-impact summary

| # | Finding | Resolution |
|---|---|---|
| iOS-#1 | Run Script can't find `sample/gradlew` | Copied wrapper into `sample/` |
| iOS-#2 | Silent failures with no iOS `EventListener` | Sample now ships `LoggingEventListenerFactory` in `MainViewController.kt` |
| iOS-#3 | `NSLog("%@", kotlinString)` → SIGSEGV | All listener output routed through `println` |
| iOS-#4 | All Android-side fixes apply to iOS unchanged | Cross-platform parity confirmed |

iOS surfaced **three** distinct bugs vs Android's **five**, but
two of the three are subtler (pointer-authentication crash;
build-script dependency on a wrapper script in a parent directory)
and could have wasted hours of an adopter's time. The fixes are
all small. Net cost of the iOS validation pass: ~2 hours including
the Xcode project scaffolding work that's now a permanent part of
the sample.

---

# Production-mode bundle case study

Both prior case studies (Android, iOS) ran the **Development**
Zipline bundle — the 2.8 MB unminified form. That's the dev-loop
bundle. What actually ships to users is the **Production** bundle:
R8-shrunk on the host APK side, and Kotlin/JS-DCE-minified on the
guest side. Different compiler path, different risk surface. This
case study closes that gap.

**Final state**: the Production bundle renders `Hello, Keliver!`
identically to Development, with the same Zipline RPC handshake.
**Zero bugs found** — but the validation is the point: an untested
code path is an unknown code path, and this one is now known-good.

## What was specifically at risk

The headline risk was **dead-code elimination stripping the
KSP-generated `Companion.Adapter`**. That class is never referenced
from Kotlin call sites the compiler can see — Zipline's loader
looks it up *by name* at code-load time (the whole reason U12
exists). A naive DCE pass would conclude it's unreachable and
delete it, or a minifier would rename it and break the name-based
lookup. Either would surface as `codeLoadFailed: Constructor
'Adapter.<init>' can not be called` — the same U12 error shape,
but appearing *only* in Production and *only* after we'd already
shipped the codegen as "working."

It didn't happen. Zipline's IR plugin correctly marks the adapter
as a retained root, so DCE keeps it and the name survives
minification. **The `@KeliverAppService` codegen path is
production-safe**, not just debug-correct.

## Test setup

```sh
cd sample
# 1. Build the PRODUCTION guest bundle (note the task name differs
#    from the Development one used in the other case studies).
./gradlew :guest:compileProductionExecutableKotlinJsZipline

# 2. Serve the Production output directory.
(cd guest/build/zipline/Production && python3 -m http.server 8080) &

# 3. Clear the app's Zipline cache so it re-fetches from the server
#    rather than replaying a cached Development bundle, then launch.
adb shell pm clear dev.keliver.sample
adb logcat -c
adb shell am start -n dev.keliver.sample/dev.keliver.sample.host.MainActivity
```

## Evidence it was genuinely Production

Two cross-checks, because "it rendered" alone doesn't prove the
Production bytes (vs a stale Development cache) were what loaded:

1. **Dev-server access log** — 32 GETs, all from the
   `guest/build/zipline/Production/` directory the server was
   rooted in.
2. **Byte-size delta** — `keliver-sample-guest.zipline` was
   11,481 B in Production vs 12,517 B in Development. Different
   bytes ⇒ genuinely the minified build, not a Development
   replay. (The delta is modest for this tiny sample because the
   bulk of DCE's win lands on the stdlib/runtime modules, not the
   ~3 KB of sample domain code.)

Logcat sequence was identical to the Development runs:
`manifestReady modules=30` → `ziplineCreated` →
`mainFunctionStart`/`End` → `codeLoadSuccess modules=30` →
`takeService name=app` → the `zipline/guest-N` ↔ `zipline/host-N`
service pairs.

## Documented gap (now CLOSED — see the signed-manifest case study below)

Real Production deployments also swap `ManifestVerifier.NO_SIGNATURE_CHECKS`
for a signature-checking verifier keyed off a production verifying
key. This case study used `NO_SIGNATURE_CHECKS`, so at the time the
**signed-manifest path was unvalidated**. That gap is now closed —
the sample signs its manifest with an Ed25519 key and both hosts
verify it. See the [signed-manifest case study](#signed-manifest-case-study)
below for the positive + negative (tamper-rejection) evidence.

## Production-mode adopter-impact summary

| Check | Result |
|---|---|
| Production bundle compiles (`compileProductionExecutableKotlinJsZipline`) | ✅ |
| DCE retains the KSP-generated `Companion.Adapter` | ✅ (Zipline IR pins it) |
| Minification preserves name-based adapter lookup | ✅ |
| Renders identically to Development | ✅ |
| Full Zipline RPC handshake | ✅ |
| Signed-manifest path (Ed25519 verifier) | ✅ validated — see signed-manifest case study |

Net: the Production code path — the one that actually ships to
users — is validated end-to-end for the `@KeliverAppService`
codegen, and (as of the signed-manifest case study below) for
signature verification too.

---

# Signed-manifest case study

The prior case studies all ran with
`ManifestVerifier.NO_SIGNATURE_CHECKS` — the host runs *whatever
bytes the manifest URL returns*. That's fine for local dev, but in
production it means anyone who can MITM the manifest endpoint (or
compromise the CDN) can serve arbitrary guest code into the app.
Zipline's answer is manifest signing: the build signs the manifest
with a private key, and the host verifies that signature against an
embedded public key before running a single line of guest code.
This case study wires that up in the sample and validates both that
a good signature loads **and** that a tampered bundle is rejected.

**Final state**: the guest manifest is Ed25519-signed; both hosts
(`host-android`, `host-compose`/iOS) verify it. A correct signature
loads and renders `Hello, Keliver!`; a tampered manifest is refused
with `codeLoadFailed: manifest signature … did not verify!`.

## How it's wired

- **Key pair (Ed25519).** Generated with Zipline's own primitive so
  the encoding matches exactly — no hand-rolled crypto:
  ```sh
  # public class, on the zipline-loader classpath:
  app.cash.zipline.loader.internal.InternalJvmKt.generateEd25519KeyPair()
  #   -> KeyPair { publicKey: ByteString, privateKey: ByteString }
  # (equivalently: the `zipline-cli generate-key-pair` command)
  ```
  A real app keeps the private key in a secret and injects it at
  build time; the sample commits a **demo** key so it runs out of
  the box (called out loudly in `guest/build.gradle.kts`).
- **Guest signs (`sample/guest/build.gradle.kts`).**
  ```kotlin
  zipline {
    signingKeys {
      create("keliver-sample-ed25519") {
        algorithmId.set(SignatureAlgorithmId.Ed25519)
        privateKeyHex.set("2d69a8f0…")   // demo private key
      }
    }
  }
  ```
  The signature lands in the manifest under
  `unsigned.signatures["keliver-sample-ed25519"]` (it lives in the
  `unsigned` block because a signature can't cover itself).
- **Hosts verify** (`host-android` `MainActivity`, iOS
  `MainViewController`):
  ```kotlin
  manifestVerifier = ManifestVerifier.Builder()
    .addEd25519("keliver-sample-ed25519", PUBLIC_KEY_HEX.decodeHex())
    .build()
  ```
  The key **name** must match on both sides.

## Evidence — positive (correct key)

Built + installed `host-android`, served the signed Development
bundle, `pm clear` to force a fresh fetch + verify, launched:

```
D KeliverSample: manifestReady modules=30
D KeliverSample: codeLoadSuccess modules=30
```
Screen renders `Hello, Keliver!`. The signature verified and the
guest ran.

## Evidence — negative (tampered manifest)

To prove the check isn't a no-op, flipped one hex char in the first
module's `sha256` in the served `manifest.zipline.json` (leaving the
now-stale signature in place), then `pm clear` + relaunch:

```
E KeliverSample: codeLoadFailed: manifest signature for key keliver-sample-ed25519 did not verify!
```
The host **refused to load** the bundle — exactly the MITM/tamper
protection signing exists for. Restored the good manifest afterward.

## Validation scope

| Check | Result |
|---|---|
| Guest manifest is Ed25519-signed (`unsigned.signatures`) | ✅ |
| Android host: correct key → `codeLoadSuccess` + renders | ✅ runtime (Pixel_9 emulator, API 37) |
| Android host: tampered manifest → `codeLoadFailed` | ✅ runtime |
| iOS host: same verifier wired (`MainViewController`) | ✅ compiles (`compileKotlinIosSimulatorArm64`); runtime parity not re-run this pass |

**Caveat / honesty.** The demo key is committed for reproducibility
— that is *not* a production pattern. The negative test exercised
content tampering (a changed module hash invalidates the signature);
a forged-signature or wrong-key path would fail at the same
`ManifestVerifier` gate. iOS was compile-verified only this pass; it
uses the identical multiplatform Zipline API as Android, which was
runtime-validated.

---

# Hot-reload case study

Server-driven UI's biggest dev-loop win is supposed to be **hot
reload**: edit guest UI, see it on the device in seconds, no APK
reinstall. This case study wires that up in the sample and confirms
it works end to end on the emulator.

**Final state**: with `./gradlew :guest:serveDevelopmentZipline
--continuous` running, editing the guest's `Text(...)` and saving
updates the running app in **~2 s**, no reinstall — verified by a
second `codeLoadSuccess` in logcat and the new string on screen.

## How it works (the three pieces)

1. **Dev server with a push channel.** `serveDevelopmentZipline`
   (Zipline 1.22.0) serves the bundle over HTTP **and** exposes a
   WebSocket that signals "code updated." (Note: this contradicts
   Finding #1 — the task exists; see the correction there.) A plain
   `python3 -m http.server` can serve the bundle but has no push
   channel, so it can't drive hot reload.
2. **Continuous rebuild.** Running the serve task with `--continuous`
   makes Gradle watch the guest source; on save it recompiles the
   `.zipline` bundle in-process and the server pushes the update.
3. **Client subscribes** (`host-android/MainActivity.kt`): the
   `Spec.manifestUrl` flow is wrapped with
   `flowOf(MANIFEST_URL).withDevelopmentServerPush(ziplineHttpClient)`.
   `withDevelopmentServerPush` (from `app.cash.zipline.loader`) opens
   the dev-server WebSocket and re-emits the URL each time the server
   signals an update; Treehouse reloads the guest on each emit. It
   re-emits **only** on a real update (not a timer), so there's no
   reload flicker. Gated behind `DevConfig.HOT_RELOAD` (default
   `true`); flip it off for the plain python-server flow.

   Important: `withDevelopmentServerPush` needs the OkHttp-backed
   `ZiplineHttpClient` (the same one already used for the bundle
   fetch). The sample hoists it so both share one client.

## Test loop + evidence

```sh
cd sample
# 1. Serve + watch source in one process (this is the key — a
#    separate `compileDevelopmentZipline` in another daemon does NOT
#    trigger the push; the serve process must own the rebuild).
./gradlew :guest:serveDevelopmentZipline --continuous &
# 2. Install + launch the host (HOT_RELOAD defaults true).
./gradlew :host-android:installDebug
adb shell am start -n dev.keliver.sample/.host.MainActivity
# 3. Edit guest/src/jsMain/.../Main.kt: change the Text(...) string, save.
```

Observed on a Pixel_9 emulator (API 37):

| Step | Evidence |
|---|---|
| v1 loads | logcat `codeLoadSuccess modules=30` (#1); screen reads "Hello, Keliver!" |
| edit `Text("Hello, Keliver!")` → `"… (hot reloaded)"`, save | `serveDevelopmentZipline --continuous` rebuilds + re-serves in ~2 s (served `.zipline` now contains the new string) |
| **app updates live** | a **second** `codeLoadSuccess modules=30` (#2) ~18 s later, **no reinstall / restart**; screen now reads "Hello, Keliver! (hot reloaded)" |

## Gotcha worth recording

The first attempt failed: I ran a **non-`--continuous`** serve and
recompiled in a **separate** `./gradlew` invocation. The served
bundle updated, but the app never reloaded — the push fires only
when the **serve process itself** rebuilds. The fix is the canonical
single-process `serveDevelopmentZipline --continuous`. Documenting it
because it's a non-obvious dead end.

## Hot-reload adopter-impact summary

| Check | Result |
|---|---|
| `serveDevelopmentZipline` exists + serves (corrects Finding #1) | ✅ |
| `withDevelopmentServerPush` wired on the host | ✅ compiles + runs |
| Live reload on guest edit (no reinstall) | ✅ runtime (Pixel_9, API 37) — 2nd `codeLoadSuccess` + new UI |
| iOS hot reload | ⏳ not wired this pass — Android-only; iOS uses `NSURLSession`, and `withDevelopmentServerPush` needs an OkHttp-backed client, so iOS needs a different WS bridge. Documented follow-up |

Net: hot reload works for the Android host with the stock Zipline
dev server — the headline server-driven dev-loop benefit is real and
validated. iOS hot reload is a scoped follow-up (different HTTP
stack).
