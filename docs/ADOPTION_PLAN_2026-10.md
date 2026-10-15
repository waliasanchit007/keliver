# Plan: what the 2026-10 adoption proof asks Keliver to build

[`ADOPTION_RETRO_2026-10.md`](ADOPTION_RETRO_2026-10.md) records what putting Keliver into two
existing production apps cost. [`ROADMAP.md`](ROADMAP.md#adoption-findings-2026-10--pick-work-from-here-first)
orders the findings as items 1–7. This document plans items 2–7. Item 1, the widget gaps, is
PR #105 (library 0.3.4). Sections below use the ROADMAP's item numbers.

The rule for all of it: **what an adopter had to discover by review becomes the default.** Every
serious defect in the proof was in host glue that each adopter writes for themselves.

The proof ran on debug builds that were never shipped, so nothing here was measured on a release
build or a user's device. The delivery plan's W7 (real devices, an adopter) stays open throughout.

## Item 2. Startup and memory (measure first)

**Evidence.**
- On Android (debug builds), native content took 1.5 s on a cold open against 1.1 s for the
  cached web page. Reopens were equal (0.6 s).
- On an iPhone 13, about 1 s of a warm open is the guest's own work between frames.
- The iOS first open adds about 76 MB.
- The default `publishTask` builds Development bundles: 4.3 MB against 2.4 MB, and slower to
  evaluate.

**Steps.**
1. M0: **timing marks in both host templates**, debug builds only. The proof added them by hand.
   - Marks: `open` (the screen was asked for), `code` (the bundle's code loaded), `first-frame`
     (the first guest frame applied), `content` (the guest says its data is shown).
   - Sources: `code` from the Treehouse `EventListener`'s `codeLoadSuccess`. `first-frame` from a
     new host hook called when a change list is applied to the screen's view: `EventListener` has
     no frame callback, so M0 adds one. `content` from a guest-side `mark(name)`.
   - Each mark is taken one frame after the host applies the change (Android: two
     `Choreographer` callbacks; iOS: the next `CADisplayLink` tick), so it counts what the
     user can see.
   - Output: Android logs one `KeliverTiming` logcat line per mark and iOS exposes
     `Keliver.onTrace(name, epochMillis)`; both are also handed to a debug-only file sink that
     the snapshot runner (item 5) reads.
   - Steps 2–4 and item 7 use these marks.
2. M1: **Production bundles by default.** `publishTask` defaults to the Production executable,
   and Development sits behind a flag. This is a behaviour change, so it gets a release note.
3. M2: **release-build numbers.** The proof's harness (cold, warm, reopen, footprint), run on
   release builds of the reference app, with M0's marks.
4. M3: **start the code load at host start**, not at the first screen. Today a prewarm does only
   the bundle lookup. Measure the cold-open gain with M2's harness.
5. M4: **a guest frame profile**: time per frame in composition, protocol encoding and host
   apply, from M0's change-list hook plus the `EventListener`'s `callStart` and `callEnd`.
6. M5: **an iOS memory breakdown**: the Compose Multiplatform runtime vs QuickJS vs the guest,
   and whether a second open leaks.

Decide on optimisations only after M2–M5.

## Item 3. Brownfield host kit (builds on W2 `--embed`)

**Evidence.**
- `keliver-new-production-host.sh --embed` and `keliver-new-ios-host.sh --embed` gave a working
  start.
- The app glue then took ≈ 900 lines on Android and ≈ 990 lines of Swift on iOS.
- Both copies needed the same review fixes: a fallback to the replaced page, a request allow-list,
  an authenticated client pinned to one origin, user-safe failure text, and safe external links.
- Android also needed:
  - a permission on an exported debug receiver;
  - honouring a re-delivered intent's new route;
  - an `https` release origin;
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
| Status, public on both | Android's `KeliverHostState` gains typed `Loading` and `Unavailable(reason)`, next to `Message` and `Running`, where `reason` is W6's category. iOS makes its `HostState` public with the same shape, delivered on the main thread. The app hides its own chrome while `Running`. | iOS (Android inferred it from `Message`) |
| `onUnavailable(reason, route)` | Called when the host cannot show a screen: `no-bundle`, `refused`, `not-loaded`, or the code failed mid-screen. The app opens what the screen replaced, with the original route. This is not W5's `FellBack`: there, the host still runs the cached last-good bundle. | Both apps' screen controllers |
| Release message policy | Debug builds keep the host's diagnostic text. Release builds show "Loading…" or a generic failure line, which the app can override. | Both hosts |
| `RequestPolicy` | Three rules, checked before any network: an exact `(method, path)` allow-list; one pinned origin (scheme, host, port) that must be `https` in release builds; and a per-build switch for state-changing calls. It is applied by the `HostHttpProvider` adapter (item 4). | Both apps' bridge delegates |
| `ExternalLinkPolicy` | `https` opens in an in-app browser and `intent://` is handled only as a browser would. Everything else is refused. | Android |
| Routes and close | `open(route)` takes the typed route from D8, filtered through a query-key allow-list, and honours a re-delivered intent's new route (`onNewIntent`) on Android. `closeScreen()` pops or dismisses, whichever way the screen was shown. | Both apps |
| Debug-only surfaces | Debug receivers and launch switches are unexported, or need a signature permission. A release build has none of them. | Android |
| Kotlin-gap recipe | The host is its own Gradle build that produces an AAR, with a generated table of the dependency floor it imposes (Compose, material3, OkHttp, Coil). Before writing anything, the scaffolder prints that floor next to the app's resolved versions. | Android (by trial) |

**Steps.**
1. K1: the Android embed template (the first seven pieces), with self-tests.
2. K2: the same on iOS.
3. K3: the dependency-floor report in the scaffolder, with a consumer-smoke fixture on an older
   Compose.
4. K4: a device proof in the reference app: the unavailable, refusal and route cases on the CI
   emulator and the iOS simulator.

**Done when** a second embed reaches "safe to put behind a flag" with no review finding in these
areas. Measure it on the next adoption: glue lines per app, and review findings.

## Item 4. Bridge: HostHttp for data, generated actions (D13)

**Evidence.**
- One 11-call `ZiplineService` contract was copied byte for byte into three repositories.
- Delegates were hand-written in Kotlin (Android) and in Swift through a Kotlin adapter (iOS).
- The allow-list existed only in the two delegates.
- Most of the 11 calls were data reads that sent the app's authenticated request and returned
  the body. They were per-endpoint because the app's authenticated client is not reachable
  through `HostHttp`.

**Direction (D13).** Data goes through the narrow generic transport, not a per-endpoint
contract. The guest owns endpoints, types and parsing. Only actions (open a route, close, share,
analytics) need a contract.

**What ships.**
1. **A `HostHttpProvider` adapter over the app's own authenticated client.**
   - Android: an OkHttp `Call.Factory`. iOS: a `URLSession` plus a header callback.
   - It is wrapped in item 3's `RequestPolicy`, so the allow-list and the pinned origin are
     enforced once, in Keliver code.
   - This replaces the data calls, and it also makes item 5's recorded data and D7 preview
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
   - The signed manifest carries `bridge: {name, min}`, the oldest contract version the bundle
     can talk to.
   - A host declares the version it implements, and the lookup refuses a bundle whose `min` is
     newer, exactly as `widgetVersion` is gated today.

**Steps.**
1. B1: the `HostHttpProvider` adapter with `RequestPolicy`, on Android, tested against a local
   server (allowed, refused, wrong origin, redirect).
2. B2: the same on iOS.
3. B3: action generation with a golden-file test, on both platforms.
4. B4: the contract version in the manifest and in the host gate (W4's host-version gate is the
   template).
5. B5: migrate the reference app.

## Item 5. Device state catalogue and snapshot runner

**Evidence.**
- iOS was verified with improvised parts: a fixtures-only `?demo=` route parameter, debug
  launch-environment switches, and an in-app hook writing PNGs.
- The test data was a hand-written fake API with a fixed delay. The app's requests went through
  its own bridge, so the relay's HTTP record/replay, which works at the `HostHttp` layer, could
  not be used.

**What ships.**
- **Named states**, declared next to a screen in the guest:
  `@NamedState("review") fun review() = NamedState(route = ItemDetail(id = "sample"), fixtures = "review")`.
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
1. S1: `@NamedState` in the guest API, with ingest support and an editor list (states preview
   with their fixture set as the persona's `httpFixtureSet`).
2. S2: the embedded fixture replay provider (debug only), with a release-build self-test that it
   is absent.
3. S3: the Android runner, plus a CI emulator job that publishes the PNGs and timings as
   artifacts.
4. S4: the iOS runner on the simulator in CI (`simctl`).

## Item 6. Dev loop

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
item 2, because they are a day or less each and every later step uses them.

1. Item 1: #105, released as library 0.3.4 after its device render.
2. Item 2, M0–M2, and item 6's first four rows, alongside.
3. Item 2, M3–M5.
4. Items 3 and 4 together (K1–K2 with B1–B2), because `RequestPolicy` is shared.
5. Item 5 (S1–S3), then K3–K4, B3–B5 and S4.
6. Item 7, and item 6's device proxy.

Each step gets an independent review and green CI.
