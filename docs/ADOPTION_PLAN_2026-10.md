# Plan: what the 2026-10 adoption proof asks Keliver to build

[`ADOPTION_RETRO_2026-10.md`](ADOPTION_RETRO_2026-10.md) records what putting Keliver into two
existing production apps cost. [`ROADMAP.md`](ROADMAP.md#adoption-findings-2026-10--pick-work-from-here-first)
orders the findings as items 1–7. This document plans items 2–7. Item 1, the widget gaps, is
PR #105 (library 0.3.4). Sections below use the ROADMAP's item numbers.

The rule for all of it: **what an adopter had to discover by review becomes the default.** Every
serious defect in the proof was in host glue that each adopter writes for themselves.

**Status (2026-10-29).** Item 1 is done (libraries 0.3.4). Item 2: M0 (#116),
M1 (#110) and M2 on Android (#119) are done, M2 on iOS is deferred (it needs a
signing team), M3–M5 are open. Item 3: K1 and K2 are released (tools 0.3.14),
K4 ran on the CI emulator and simulator (#165), K3 is open; items 4, 5 and 7
are not started. Item 6: rows 1 (#108, #115), 2 (#109), 3 (#114) and 4
(#111) are done, and so is a debug host that picks up a publish while it is
open (Android #145, released in tools 0.3.13 and checked on a physical phone;
iOS #150, released in tools 0.3.14); the device proxy is open (`ROADMAP.md`, "Current
priorities"). Each item below carries its own
status line. **Naming:** this plan's K1–K4 (item 3) and B1–B5 (item 4) are its
own step numbers; they are not the portal grammar's K1–K7 (#120–#134) nor W8's
K1–K8 checks.

The proof ran on debug builds that were never shipped, so nothing here was measured on a release
build or a user's device. The delivery plan's W7 (real devices, an adopter) stays open throughout.

## Item 2. Startup and memory (measure first)

**Status 2026-10-27:** M0, M1, M2 (Android) done; M2 (iOS) deferred; M3–M5 open.

**Evidence.**
- On Android (debug builds), native content took 1.5 s on a cold open against 1.1 s for the
  cached web page. Reopens were equal (0.6 s).
- On an iPhone 13, about 1 s of a warm open is the guest's own work between frames.
- The iOS first open adds about 76 MB.
- The default `publishTask` builds Development bundles: 4.3 MB against 2.4 MB, and slower to
  evaluate.

**Steps.**
1. M0: **timing marks in both host templates.** The proof added them by hand.
   - **Which builds:** debug builds, and a benchmark build with release settings that is never
     shipped (Android: a `benchmark` build type, minified, not debuggable, profileable; iOS: a
     Release-based `Benchmark` configuration). The store build compiles none of it.
   - **Marks:** `open` (the screen was asked for), `code` (the bundle's code loaded),
     `first-frame` (the first guest frame applied), `content` (the guest says its data is
     shown).
   - **Sources:**
     - `code` from the Treehouse `EventListener`'s `codeLoadSuccess`.
     - `first-frame` from a new host hook called when a change list is applied to the screen's
       view. `EventListener` has no frame callback, so M0 adds one.
     - `content` from the guest's `mark(name)`, which calls a small `KeliverTrace` service that
       only debug and benchmark hosts bind. Where it is not bound, `mark` does nothing.
   - **What is visible:** `first-frame` and `content` are taken one frame after the host applies
     the change (Android: two `Choreographer` callbacks; iOS: the next `CADisplayLink` tick), so
     they count what the user can see. `open` and `code` are taken when they happen.
   - **Timestamps are monotonic:** Android `SystemClock.elapsedRealtimeNanos()`, iOS
     `CACurrentMediaTime()`.
   - **Output:** Android logs one `KeliverTiming` logcat line per mark, and iOS exposes
     `Keliver.onTrace(name, nanos)`. Both also go to a file sink that the snapshot runner
     (item 5) reads.
   - Steps 2–4 and item 7 use these marks.
2. M1: **Production bundles by default.** `publishTask` defaults to the Production executable,
   and Development sits behind a flag. This is a behaviour change, so it gets a release note.
3. M2: **release-build numbers.** The proof's harness (cold, warm, reopen, footprint), run on
   the reference app's benchmark build (M0), which has release settings.
4. M3: **start the code load at host start**, not at the first screen. Today the host's
   `start()` at app launch only looks up the bundle. Measure the cold-open gain with M2's harness.
5. M4: **a guest frame profile**: time per frame in composition, protocol encoding and host
   apply. The host side comes from M0's change-list hook. `callStart` and `callEnd` time a whole
   guest call, so splitting composition from encoding needs spans inside the guest's runtime,
   reported through M0's `KeliverTrace` service.
6. M5: **an iOS memory breakdown**: the Compose Multiplatform runtime vs QuickJS vs the guest,
   and whether a second open leaks.

Decide on optimisations only after M2–M5.

## Item 3. Brownfield host kit (builds on W2 `--embed`)

**Status 2026-10-29:** K1 (Android) and K2 (iOS) complete in code (#155, #158–#162) and released in tools 0.3.14; `screen-failed` on iOS merged (#165). K4 ran on the CI emulator and simulator (#165), not yet on a phone. K3 (the dependency-floor report) merged (#168), for tools 0.3.15.
- **Merged:** the typed status, `onUnavailable` and the release text policy (#155; `attempt`,
  #158); `RequestPolicy` (in `OkHttpHostHttp`; its `https` rule comes from the app's
  `allowCleartext`, and a release build's settings refuse an http API base) and
  `ExternalLinkPolicy`, with JVM tests in the scaffolded host (#159); iOS's public
  `KeliverHostState`, `onUnavailable` per screen and release text policy (#160); the
  `screen-failed` outcome, the debug-surface audit, routes and close on Android, with one
  shared `KeliverRoute` and `KeliverScreenHost` in a fixed package (#161).
- **Merged:** `RequestPolicy`, routes and close on iOS (#162). All of it is in tools 0.3.14.
  `screen-failed` on iOS (#165), for the next tools release.
- **K4 on CI** (#165): the reference embed apps, through their deep links: a route reaching the
  guest with only the allowed keys, close, `screen-failed` and another key's bundle, each
  putting the app's own view in place (emulator X6, X8–X10; simulator I5–I8).
- **K3** (#168, merged 2026-10-29): `keliver-embed-floor.sh` resolves the `--embed` template's
  `releaseRuntimeClasspath` in a throwaway app, and `build-portal-tools.sh` ships the result as
  `templates/production-host-embed/dependency-floor.json`. `--embed` prints that floor beside the
  existing app's declared versions (direct coordinates, a version catalog, simple variables) before
  writing anything, and marks each declared version that Gradle will raise. CI's
  `keliver-embed-floor-check.sh` runs it on an app on older libraries
  (`scripts/fixtures/embed-older-compose`: Compose BOM 2024.06.00, OkHttp 4.12.0, …). It checks the
  report, builds the app, and compares what Gradle resolved.
- **Not yet:** K4 on a phone; a `RequestPolicy` refusal on a
  device (its rules are covered by JVM tests on both hosts).

**Evidence.**
- `keliver-new-production-host.sh --embed` and `keliver-new-ios-host.sh --embed` gave a working
  start.
- The app glue then took ≈ 900 lines on Android and ≈ 990 lines of Swift on iOS.
- Both copies needed the same review fixes: a fallback to the replaced page, a request allow-list,
  an authenticated client pinned to one origin, and user-safe failure text.
- One host's release bundle origin was left on `http://`.
- Android also needed:
  - safe external links (`intent://` opened without the browser's rules);
  - a permission on an exported debug receiver;
  - honouring a re-delivered intent's new route;
  - a separate host build, because the app was on an older Kotlin.

**What already exists.**
- **Android:** `KeliverHost` publishes `state` (`Message` or `Running`), `events` (W5:
  `Applied`, `Failed`, `FellBack`) and `reports` (W6: one record per outcome, with a fixed
  `reason` category).
- **iOS:** `Keliver.onReport` and `Keliver.onUpdateEvent` are public, but its `HostState` is
  private.

The kit builds on these. It does not add a second status channel.

**What ships.** Additions to both embed templates and their self-tests, not a new artifact:

| Piece | What it does | Where it was hand-written in the proof |
|---|---|---|
| Status, public on both | Android's `KeliverHostState` gains typed `Loading` and `Unavailable(outcome, reason)`, next to `Message` and `Running`. `outcome` is W6's outcome (`no-bundle`, `refused`, `not-loaded`, plus a new `screen-failed`); `reason` is W6's fixed category. iOS makes its `HostState` public with the same shape, delivered on the main thread. The app hides its own chrome while `Running`. | iOS (Android inferred it from `Message`) |
| `onUnavailable(outcome, reason, route)` | Called when the host cannot show a screen: `no-bundle`, `refused`, `not-loaded`, or the new `screen-failed` (the code failed or was unloaded while a screen showed it; W6 reports it too). The app opens what the screen replaced, with the original route. This is not W5's `FellBack`: there, the host still runs the cached last-good bundle. | Both apps' screen controllers |
| Release message policy | Debug builds keep the host's diagnostic text. Release builds show "Loading…" or a generic failure line, which the app can override. | Both hosts |
| `RequestPolicy` | Three rules, checked before any network: an exact `(method, path)` allow-list; one pinned origin (scheme, host, port) that must be `https` in release builds; and a per-build switch for state-changing calls. K1 and K2 define it; item 4's B1 and B2 apply it in the `HostHttp` adapters. | Both apps' bridge delegates |
| `ExternalLinkPolicy` | `https` opens in an in-app browser and `intent://` is handled only as a browser would. Everything else is refused. | Android |
| Routes and close | `open(route)` takes the typed route from D8, filtered through a query-key allow-list, and honours a re-delivered intent's new route (`onNewIntent`) on Android. `closeScreen()` pops or dismisses, whichever way the screen was shown. | Both apps |
| Debug-only surfaces | Debug receivers and launch switches are unexported, or need a signature permission. A release build has none of them. | Android |
| Kotlin-gap recipe | The host is its own Gradle build that produces an AAR, with a generated table of the dependency floor it imposes (Compose, material3, OkHttp, Coil). Before writing anything, the scaffolder prints that floor next to the app's resolved versions. | Android (by trial) |

**Steps.**
1. K1: the Android embed template (the first seven pieces), with self-tests.
2. K2: the five pieces that apply on iOS (all but `ExternalLinkPolicy` and the Android-only
   debug surfaces).
3. K3: the dependency-floor report in the scaffolder, with a consumer-smoke fixture on an older
   Compose.
4. K4: a device proof in the reference app: the unavailable, refusal and route cases on the CI
   emulator and the iOS simulator.

**Done when** a second embed reaches "safe to put behind a flag" with no review finding in these
areas. Measure it on the next adoption: glue lines per app, and review findings.

## Item 4. Bridge: HostHttp for data, generated actions (D13)

**Status 2026-10-29:** B1 merged (#169), for tools 0.3.15. `KeliverConfig.httpClient` sends guests' HostHttp through the app's own authenticated `OkHttpClient`, behind the `RequestPolicy`. A config with the app's client and no policy is refused. The client is used for nothing else. The URL going out is checked again after the app's interceptors and on retries. A guest's credential and path-override headers are dropped, and the response's `Set-Cookie` is not returned to the guest. JVM tests run against a local server: allowed, refused, wrong origin, redirect, an interceptor or authenticator sending the request elsewhere, guest credentials, the session cookie, and no policy. A new PR CI job builds the scaffolded host and runs its JVM tests. B2–B5 not started.

**Evidence.**
- One 11-call `ZiplineService` contract was copied byte for byte into three repositories.
- Delegates were hand-written in Kotlin (Android) and in Swift through a Kotlin adapter (iOS).
- The allow-list existed only in the two delegates.
- The 11 calls were context, request, web-for-result, HTML-for-result, open URL, track, haptic,
  toast, share, download and close. All of the data went through the one generic `request` call,
  and the rest are actions.
- `request` duplicated `HostHttp` because the embed did not wire the app's authenticated client
  into it. Android's `OkHttpHostHttp` already takes any `OkHttpClient`, pinned to one base, with
  no redirects and `..` refused. iOS's `NSURLSessionHostHttp` has no way to add the app's auth
  headers.

**Direction (D13).** Data goes through the narrow generic transport, not a per-endpoint
contract. The guest owns endpoints, types and parsing. Only actions (open a route, close, share,
analytics) need a contract.

**What ships.**
1. **`HostHttp` over the app's own authenticated client.**
   - Android: the embed wires the app's `OkHttpClient` into the existing `OkHttpHostHttp`.
   - iOS: `NSURLSessionHostHttp` gains a header callback, so the app can add its auth headers.
   - Both apply item 3's `RequestPolicy`, so the allow-list and the pinned origin are enforced
     once, in Keliver code.
   - This replaces the bridge's `request` call, and it also makes item 5's recorded data and D7 preview
     substitutes work for them, because both already exist at the `HostHttp` layer.
2. **Generated action bridges.**
   - From an annotated Kotlin interface in a contract module that the guest and both hosts
     depend on, a Gradle task generates:
     - the guest-side `ZiplineService`;
     - an Android delegate interface;
     - a non-suspend, callback-style Kotlin interface for iOS.
   - Kotlin/Native already exports that interface to Swift as an Objective-C protocol, so the
     generator writes only the suspend-to-callback adapter, and Swift implements the protocol
     directly. The hand-written Kotlin adapter goes away.
   - Return types follow D13's rule: no `List<@Serializable>`; wrap it in one type.
3. **The contract version in the compatibility gate (D2).**
   - A bundle declares `bridge: {name, min}`, the oldest contract version it can talk to, and a
     host declares the version it implements.
   - **Lookup:** `bridge.min` goes in the bundle index entry, as `widgetVersion` does today, so
     the lookup skips a bundle the host cannot serve. The index entry is not signed.
   - **Load:** the same value is in the signed manifest's metadata, and the host checks it again
     before loading, the way it checks the sequence floor today. A bundle whose `min` is newer,
     or whose `name` is not the host's contract, is refused.

**Steps.**
1. B1: `RequestPolicy` in `OkHttpHostHttp` and the app-client wiring in the Android embed, tested
   against a local server (allowed, refused, wrong origin, redirect).
2. B2: the header callback and `RequestPolicy` in `NSURLSessionHostHttp`, with the same tests.
3. B3: action generation with a golden-file test, on both platforms.
4. B4: the contract version in the index entry and the signed manifest, and both host checks.
5. B5: migrate the reference app.

## Item 5. Device state catalogue and snapshot runner

**Status 2026-10-27:** not started.

**Evidence.**
- iOS was verified with improvised parts: a fixtures-only `?demo=` route parameter, debug
  launch-environment switches, and an in-app hook writing PNGs.
- The test data was a hand-written fake API with a fixed delay. The app's requests went through
  its own bridge, so the relay's HTTP record/replay, which works at the `HostHttp` layer, could
  not be used.

**What ships.**
- **Named states**, declared next to a screen in the guest:
  `@ScreenState("review") fun review() = NamedState(route = ItemDetail(id = "sample"), fixtures = "review")`.
  - A state is a typed route (D8) plus an HTTP fixture set. The presenter runs for real, and
    only the `HostHttp` capability gets the fixture substitute (D7).
  - The portal lists the states, the editor previews them, and a debug device can open them.
- **Recorded data a device can use, with nothing recorded on the device.**
  - The relay's existing secure capture records the fixture sets:
    - it is off by default and turned on with `PORTAL_HTTP_RECORD=1`;
    - it accepts loopback callers only, with a per-process token read from the portal store;
    - it injects the upstream's `authEnv` credential inside the relay;
    - it redacts before writing a candidate under `.candidates/`;
    - a person reviews the candidate and promotes it as a git change.
  - A debug build of the host embeds the reviewed fixture sets as data. A debug-only
    `HostHttpProvider` replays them in-process: an exact match or a 424-style miss, and never an
    outbound call.
  - It must match exactly as the relay does: normalised fields, duplicates in file order, and
    `reuse`. That matcher is JVM-only today, in `portal-relay`'s `HttpReplay.kt`. It moves to a
    multiplatform module that the relay and both hosts share, so a device can't drift from
    preview.
  - So a device needs no token, no relay route and no tunnel to the relay. Release builds
    contain neither the fixtures nor the replay provider, and a self-test checks that.
- **A snapshot runner** in both host templates, debug builds only, started by launch arguments.
  - It opens each named state, waits until the guest is idle, writes a PNG, and records item 2's
    M0 marks and the memory footprint.
  - **Idle** means three things for two consecutive frames: no pending guest frame, no change
    list applied (M0's hook), and no `HostHttp` request in flight. If that doesn't happen within 10 s, the state
    fails with the marks so far.
  - On Android the runner is driven through `adb shell am start` extras, and PNGs come back
    with `adb pull`.
  - On the iOS simulator it uses `xcrun simctl launch` with `SIMCTL_CHILD_*` environment and
    `simctl get_app_container`. On a device it uses `devicectl` launch environment and an
    app-container copy.
  - No UI-automation driver is needed.

**Steps.**
1. S1: `@ScreenState` in the guest API, with ingest support and an editor list (states preview
   with their fixture set as the persona's `httpFixtureSet`).
2. S2: the shared multiplatform fixture matcher, then the embedded replay provider (debug only),
   with a release-build self-test that it is absent.
3. S3: the Android runner, plus a CI emulator job that publishes the PNGs and timings as
   artifacts.
4. S4: the iOS runner on the simulator in CI (`simctl`).

## Item 6. Dev loop

**Status 2026-10-29:** rows 1–4 done (#108, #115, #109, #114, #111; tools 0.3.10). A debug host that applies a publish while it is open is done: the relay's `GET /bundles/events` and `keliver.updates=live` on Android (#145, tools 0.3.13; checked on a Galaxy A12 on 2026-10-27), `UPDATES = "live"` on iOS (#150, tools 0.3.14, simulator only). The device end in one command: `keliver-run-android.sh` (#166, merged, for the next tools release; from the 2026-10-27 phone run's ≈ 12 commands; checked on the CI emulator, not yet on a phone). Open: the device proxy.

**Evidence.**
- The first publish after another Gradle build failed with a Kotlin/JS internal compiler error.
  The retry worked.
- The relay reads `keliver.portal.json` only at start.
- A new bundle needed an app restart (next-launch updates).
- An iPhone could not reach the relay without a tunnel (Local Network permission), and the debug
  bundle origin is compiled in.

| Fix | Size |
|---|---|
| Keep the full Gradle output of a failed publish (a file, linked from the response). Then reproduce the first-publish failure and fix it or work around it. | small, then unknown |
| Reload `keliver.portal.json` on change (or per publish) | small |
| Debug hosts default to `on-resume` updates (W5 already implements it), plus a debug "check now" hook (unexported or permission-guarded, see item 3) | small |
| A debug host takes its bundle origin at launch (environment variable or launch argument), not only at compile time | small, both templates |
| `keliver-portal --device-proxy`: the bundle proxy the proof hand-wrote, built in, with the tunnel left to the developer. It serves `GET` on `/bundles/*` only, and never forwards the relay's write, publish or `/http-record*` routes. | medium |

Production bundles by default (the proof's fifth dev-loop finding) is item 2's M1.

## Item 7. Bundle delivery

**Status 2026-10-27:** not started.

**Evidence.**
- A download is one request per module, three in flight, so it is latency-bound.
- A one-line guest change re-downloaded the stdlib module too.

**Steps.**
1. D1: find out why the stdlib module's hash changed for a guest-only change. If it isn't
   deterministic, fix the build.
2. D2: with item 2's M0 marks and M2's harness, measure more parallelism and a single archive
   against today, cold and on update.

## Order

The work follows the ROADMAP's order, with one exception: item 6's small fixes run alongside
item 2, because each is small and every later step uses them.

1. Item 1: #105, released as library 0.3.4 after its device render.
2. Item 2, M0–M2, and item 6's first four rows, alongside.
3. Item 2, M3–M5.
4. Items 3 and 4 together (K1–K4 with B1–B5), because `RequestPolicy` is shared.
5. Item 5 (S1–S4).
6. Item 7, and item 6's device proxy.

Each step gets an independent review and green CI.
