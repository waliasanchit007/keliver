# Plan: what the 2026-10 adoption proof asks Keliver to build

[`ADOPTION_RETRO_2026-10.md`](ADOPTION_RETRO_2026-10.md) records what putting Keliver into two
existing production apps cost. [`ROADMAP.md`](ROADMAP.md#adoption-findings-2026-10--pick-work-from-here-first)
orders the findings. This document is the plan for the four pieces of work that are bigger than
a library fix: a brownfield host kit, a bridge generator, a device state catalogue, and the dev
loop. (Item 1, the widget gaps, is PR #105; startup and memory is measurement work first, at the
end.)

The rule for all of it: **what an adopter had to discover by review becomes the default.** Every
serious defect in the proof was in host glue that each adopter writes for themselves.

## 1. Brownfield host kit (builds on W2 `--embed`)

**Evidence.** `keliver-new-production-host.sh --embed` and `keliver-new-ios-host.sh --embed`
gave a working start. Each app then needed ≈ 900 lines of glue, and both copies needed the same
five review fixes: a fallback to the replaced page, a request allow-list, an authenticated client
pinned to one origin, user-safe failure text, and safe external links. Android also needed a
separate host build because the app was on an older Kotlin.

**What ships.** Additions to both embed templates (and their self-tests), not a new artifact:

| Piece | What it does | Where it was hand-written in the proof |
|---|---|---|
| `onFallback(reason)` | Called whenever the host cannot show a screen: no bundle, refused, not loaded, or failed mid-screen. The app opens what the screen replaced, with the original arguments. | Both apps' screen controllers |
| `status()` + `onStatus` | `loading`, `running`, `no-bundle`, `refused`, `not-loaded`, on the main thread. The app hides its own chrome while running. | iOS only (Android inferred it) |
| Release message policy | Debug builds keep the host's diagnostic text; release builds show "Loading…" or a generic failure line, overridable by the app. | Both hosts |
| `RequestPolicy` | An exact `(method, path)` allow-list, one pinned origin (scheme, host, port), and a per-build switch for state-changing calls. Checked before any network. Used by HostHttp and offered to app bridges. | Both apps' bridge delegates |
| `ExternalLinkPolicy` | `https` in an in-app browser, `intent://` only as a browser would, everything else refused. | Android |
| Route + close | `open(route)` with a query-key allow-list, and `closeScreen()` that pops or dismisses whichever way the screen was shown. | Both apps |
| Kotlin-gap recipe | The host as its own Gradle build producing an AAR, with a generated table of the dependency floor it imposes (Compose, material3, OkHttp, Coil). The scaffolder prints that floor next to the app's resolved versions before writing anything. | Android (by trial) |

**Steps.**
1. K1: Android embed template (pieces 1–6) with self-tests.
2. K2: the same on iOS.
3. K3: the dependency-floor report in the scaffolder, with a consumer-smoke fixture on an older Compose.
4. K4: a device proof in the reference app (fallback, refusal and route cases on the CI emulator and the iOS simulator).

Each step gets an independent review and green CI.

**Done when** a second embed reaches "safe to put behind a flag" with no review finding in
these six areas. Measure it on the next adoption: glue lines per app, and review findings.

## 2. Bridge generator

**Evidence.** One 11-call `ZiplineService` contract was copied byte for byte into three
repositories. Delegates were hand-written in Kotlin (Android) and Swift through a Kotlin adapter
(iOS). The allow-list existed only in the two delegates.

**What ships.** A contract module the guest and both hosts depend on, from which a Gradle task
generates:
- an Android delegate interface;
- an iOS Objective-C-friendly protocol with a Swift-facing adapter (no hand-written adapter);
- the `RequestPolicy` from annotations on the contract (`@Allow("GET", "/path")`,
  `@StateChanging`).

The contract version is part of the bundle's compatibility gate (D2), next to `widgetVersion`. A
bundle never reaches a host built against an older contract.

**Steps.**
1. B1: annotate and generate on Android with a golden-file test.
2. B2: iOS generation with a simulator test.
3. B3: the contract version in the manifest and in the host gate (W4's host-version gate is the template).
4. B4: migrate the reference app.

## 3. Device state catalogue and snapshot runner

**Evidence.** iOS was verified with improvised parts: a fixtures-only `?demo=` route parameter,
debug launch-environment switches, and an in-app hook writing PNGs. The test data was a
hand-written fake API with a fixed delay, because record/replay is built on HostHttp, and the
app's requests went through its own bridge (to reuse the app's authenticated client).

**What ships.**
- **Named states** declared next to a screen, in the guest:
  `@State("review") fun reviewState() = …`. The state is a route plus the presenter inputs.
  The portal lists them, the editor previews them, and a device can open them.
- **A snapshot runner** in both host templates (debug builds only), started by launch arguments.
  It opens each named state, waits for a frame after the guest goes idle, writes a PNG, and
  records timing marks: open, shell, data, content, and the memory footprint, as measured by
  hand in the proof. On Android it is driven through adb; on iOS through `devicectl` launch
  environment and app-container copy. No UI-automation driver is needed.
- **Record/replay for bridge requests.** The relay already records and replays HostHttp. Expose
  that cassette to an app's own request bridge, through a small interceptor on the generated
  delegate (§2), so test data is recorded once from a real session (redacted, as `HttpReplay`
  already does) and replayed in debug runs.

**Steps.**
1. S1: `@State` in the guest API with ingest support and an editor list.
2. S2: the Android runner, plus a CI emulator job that publishes the PNGs and timings as artifacts.
3. S3: the iOS runner on the simulator in CI.
4. S4: record/replay through the bridge interceptor.

## 4. Dev loop

**Evidence.**
- The first publish after another Gradle build failed with a Kotlin/JS internal compiler error;
  the retry worked.
- The relay reads `keliver.portal.json` only at start.
- The default `publishTask` builds Development bundles: 4.3 MB against 2.4 MB, and slower to
  evaluate.
- A new bundle needed an app restart (next-launch updates).
- An iPhone could not reach the relay without a tunnel (Local Network permission), and the debug
  bundle origin is compiled in.

| Fix | Size |
|---|---|
| Keep the full Gradle output of a failed publish (a file, linked from the response), then reproduce and fix or work around the first-publish failure | small, then unknown |
| Reload `keliver.portal.json` on change (or per publish) | small |
| Production bundles by default; Development behind a flag | small, but a behaviour change: release note |
| Debug hosts default to `on-resume` updates (W5 already implements it), and a debug "check now" hook | small |
| A debug host takes its bundle origin at launch (environment variable or launch argument), not only at compile time | small, both templates |
| `keliver-portal --device-proxy`: the GET-only `/bundles/*` proxy the proof hand-wrote, built in, with the tunnel left to the developer | medium |

## 5. Startup and memory (measure first)

**Evidence.**
- On Android (debug builds), native content took 1.5 s on a cold open against 1.1 s for the cached web page. Reopens were equal.
- On an iPhone 13, about 1 s of a warm open is the guest's own work between frames.
- The iOS first open adds about 76 MB.

**Plan.**
1. Release-build numbers: the same harness as the proof, on release builds.
2. Start the Zipline load at host start, not at first screen. Measure the cold-open gain.
3. A guest frame profile: time spent in composition, protocol encoding and host apply, per frame, from the trace hooks the proof added.
4. An iOS memory breakdown: the Compose Multiplatform runtime vs QuickJS vs the guest, and whether a second open leaks.

Only then decide on optimisations.

## Order

1. #105 (library 0.3.4).
2. Dev-loop small fixes (§4, the first five rows): cheap, and every later step uses them.
3. Brownfield kit K1–K2 and the bridge generator B1–B2, together: `RequestPolicy` is shared.
4. State catalogue S1–S2.
5. Measurement §5.1–5.2.
6. The rest.

The delivery plan's W7 (real devices, an adopter) stays open throughout: this proof used debug
builds of apps we control.
