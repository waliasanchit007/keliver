# Gate B results (MS3)

Gate B of [`PLAN_2026-11.md`](PLAN_2026-11.md) §8, part by part. The design is
[`COMPOSE_GENERATOR_DESIGN.md`](COMPOSE_GENERATOR_DESIGN.md); Gate A's verdict is
[`GATE_A_VERDICT.md`](GATE_A_VERDICT.md). Each part adds its own section; the verdict comes when the
parts are in.

**Verdict: confirmed by the owner, 2026-11-24.** The generated API is built for real (PD2, WS2), and
D6 and D9 are amended in [`DECISIONS.md`](DECISIONS.md).

## Phone checkpoint (iOS)

Part c on an iPhone 13: the same comparison and first-frame timing as #243's simulator run
(native 122 ms against generated guest 211 ms, median of 7 warm-cache launches; 0 differing pixels
on orders, card and item), on a phone.

**Status: measured, 2026-11-14 (evening).** `scripts/keliver-gateb-ios-device.sh --runs 10` on an
iPhone 13 (iOS 26.6.2), release-optimised Kotlin/Native (`linkGateBReleaseExecutableIosArm64`, the
simulator number's build type too), the guest bundle (2,042,101 bytes of modules) served over the
Wi-Fi network from this Mac.

**Pixels: 0 differing pixels** between native and the generated guest on orders, card and item
(1170 × 2532 each, the app's in-app capture of its Compose content, not a composited screenshot);
the control (orders against card, native) differs by 671,090 pixels, so the comparison sees a real
difference. The simulator (#243): 0 / 0 / 0, control 690,027.

**First frame** (ms from `main()` to the second frame after the root was first composed; 10
process-cold launches per route; native and guest alternating):

| Route | iPhone 13: median | min | max | Simulator (#243, median of 7) |
|---|---|---|---|---|
| native | **84** | 79 | 90 | 122 |
| guest, warm Zipline cache (manifest over Wi-Fi, modules from the cache) | **226** | 213 | 253 | 211 |
| guest − native | **+142** | | | +89 |
| guest, empty cache (every module downloaded over Wi-Fi, as a fresh install does) | **905** | 854 | 1,288 | not measured |

Where the warm guest's time goes (medians, ms from `main()`): the manifest is fetched and verified at
53 (48–56), the code is loaded (`codeLoadSuccess`) at 131 (127–172), the first widget is created at
180 (175–220), the first frame at 226. With an empty cache: manifest 97, code loaded 810, first
widget 868, first frame 905: downloading the ~2 MB of modules is about 700 ms of it on this
network.

Reading them:

- **The phone is faster than the simulator for native (84 against 122 ms) and not for the guest
  (226 against 211 ms), so the gap widens from +89 to +142 ms.** The network is a small part of the
  warm gap: the manifest is verified 53 ms after `main()` over Wi-Fi (62 ms over loopback in #246's
  simulator run). Most of it is loading the cached modules into QuickJS (about 80 ms between the
  manifest and `codeLoadSuccess`) and the guest's first composition and first change batch (about
  50 ms to the first widget). That split is inferred from the milestones, not profiled.
- **An empty cache costs about 0.9 s to the first frame** on this network: the first launch after an
  install or an update whose modules are not yet cached. A bundle shipped in the app (as the Android
  benchmark's) or fetched ahead of the screen would avoid it; not measured here.
- Pixel identity holds on a phone, as on the simulator and the JVM.

**Recommendation: unchanged.** The generated group renders identically on the phone, so part c's
pixel bar is met there too. The first-frame gap is the cost of the guest route (QuickJS loading and a
first guest composition), not of the generated API; it is what the owner decides is acceptable. On
this phone it is +142 ms warm and about +820 ms with an empty cache, against +89 ms on the
simulator. (Gate A's "first frame no slower" compared the generated guest against today's guest,
not against native, and is not re-measured here.)

**Phone state:** 50% battery, unplugged, thermal state nominal, Low Power Mode off, at the first and
the last launch of each route (native, guest, guest with an empty cache).

**Owner gates:** both met and cleared during the run. Gate 1 (the developer certificate not yet
trusted on the phone) at the first native launch, passed on the second check 5 minutes later; gate 2
(Local Network not yet allowed for the app) at the first guest launch, passed on the second check
5 minutes later. Gate 0 (an Apple account in Xcode) was met before the run: the profile it fetched
lasts 7 days (until 2026-11-21). The script uninstalled its app at the end, and the phone's apps were
as before the run.

**The first attempt that day failed at step 3** (signing): the generated wrapper Xcode project's
`main.m` calls `UIApplicationMain`, but the target did not link UIKit (the project enables no clang
modules, so `#import <UIKit/UIKit.h>` does not auto-link it), and the wrapper's link failed with
"Undefined symbols … `_UIApplicationMain`". Xcode had already fetched a valid profile for the bundle
id by then, so the second attempt found it, skipped the wrapper and ran to the end. The wrapper now
links UIKit (`OTHER_LDFLAGS = -framework UIKit`); proved by building the generated project for the
simulator without signing (it failed with the same undefined symbol before the fix and links UIKit
after). The device signing path with the fix has not been run.

Earlier on 2026-11-14 (PR #246) the run had stopped at signing: the 2026-11-02 run's (PR #212)
profile had expired on 2026-11-05 and Xcode had no Apple account signed in ("No Accounts"); the
script waited 45 minutes and installed nothing. On that branch the simulator script, re-run
through the now-shared report (`--runs 3`, iPhone Air simulator, iOS 26.4, release), still gave 0
differing pixels on orders, card and item (control 690027), and native 125 ms against guest 215 ms;
the fix round's final `--capture` run gave native 122 ms against guest 200 ms.

**How to run it** (`scripts/keliver-gateb-ios-device.sh`, the device counterpart of
`keliver-gateb-ios-sim.sh`; `--help` has the details):

```bash
scripts/keliver-gateb-ios-device.sh --device <CoreDevice identifier> --bundle-id <an App ID on the team> \
  --team-file <file setting DEVELOPMENT_TEAM> [--known-profile <an earlier build's embedded.mobileprovision>] \
  --out <dir> [--runs 10]
```

Owner prerequisites, in the order the script meets them:
- **(0)** Xcode → Settings → Accounts: the team's Apple account is signed in. A personal team's
  development profile lasts 7 days, so this recurs.
- **(1)** On the phone, the developer certificate is trusted (Settings → General → VPN & Device
  Management).
- **(2)** Local Network is allowed for the app when the phone asks.

The script waits on each gate for up to `--gate-wait` minutes (default 45), re-checking every 5
minutes. Once the install has begun, it uninstalls its app whatever happens, an install that
failed partway included. It lists the phone's apps at the start and again just before installing,
and refuses if the bundle id is already there. It stops only its own app's process: one whose
executable is inside that bundle id's install location, else through `devicectl`'s
`--terminate-existing` for the bundle id.

Method, as built:
- **Build.** Release-optimised Kotlin/Native (`linkGateBReleaseExecutableIosArm64`, the same
  build type as the simulator number), unless `--debug`. The app is a plain Kotlin/Native app with
  an ATS exception, not a Keliver Release host (whose `checkReleaseUrls` refuses http), so a release
  binary can load the bundle over http from this Mac's Wi-Fi address.
- **Signing.** A minimal wrapper Xcode project (generated in the run's directory) is built with
  `-allowProvisioningUpdates` for the team and bundle id. That fetches the development profile and
  gives the entitlements, and the app is signed with that profile's certificate. The script refuses
  to start the fetch unless a profile for that team's App ID has existed, on this Mac or through
  `--known-profile` (`--allow-new-app-id` to override). That is not proof the App ID still exists:
  if it was removed since, the fetch registers it again. Automatic signing may also create an Apple
  Development certificate on the team when none is in this Mac's keychain. Devices are never
  registered: the script does not pass `-allowProvisioningDeviceRegistration`, so the phone must
  already be on the team.
- **Pixels.** `devicectl` has no screenshot, so with `GATEB_CAPTURE` the app records its window's
  Compose content into a `GraphicsLayer` and writes it as a PNG into its Documents, 2 s after the
  first frame. The script copies it out (`devicectl device copy from`) and compares native against
  guest with the simulator's comparison (`scripts/keliver-gateb-report.py`, now shared): 0 differing
  pixels expected, and the orders-vs-card control must differ. These are Skia renderings of the
  Compose content, with no status bar. They are not composited screenshots.
- **Timing.** The same probe as the simulator. There are two series of N process-cold launches
  (terminate, 1 s, launch; default 10):
  - native and guest alternating with a warm Zipline cache: the manifest over Wi-Fi, the modules
    from the local cache;
  - `guest-empty-cache`: each launch follows a `clear-cache` launch that deletes
    `Library/Caches/gateb`, so the guest downloads every module as a fresh install does. #243 asked
    for this series.
- **Phone state.** After each first frame the app logs the battery level, the charging state, the
  thermal state and Low Power Mode. The report lists them for the first and last launch of each
  route: native, guest (warm) and guest-empty-cache.
- **Network share.** The report's guest rows include when the manifest was fetched and verified.
  On the phone the manifest comes over Wi-Fi (on the simulator, over loopback), so that row
  separates the network's part of the guest−native gap from loading and rendering.
- **Logs.** The script reads the app's lines from `devicectl --console`. The app also appends them
  to `Documents/gateb-<tag>.log` (one file per launch, named by the launch's `GATEB_TAG`). If the
  console carries none of them, the script copies that file out and reads it from then on.

## Gate B-e results: animation, timed, and app size with groups

What it answers: the design's §10 animation rows (host-side `AnimatedVisibility` and
`animateContentSize`; `animate*AsState` as host-side *animated references* against the guest's
frame clock), its risks 7 (animated values on the host against the guest's frame clock) and 8
(renderers that reference Material 3 keep R8 from removing it), and §14's "Gate B measures app size
and decides". How it is built: design §22.

**Recommendation: host references for `animate*AsState`.** On the emulator both forms draw host
frames equally fast, but the guest's frame clock delivered about 29 new values a second to a host
drawing about 55 frames a second, so a guest-side animation advances on about every other frame,
and it sends ~330 changes a second over the bridge against ~20 for host references. Host references
are also pixel-identical to native Compose frame by frame (JVM). Keep `Animatable` on the guest's
frame clock as the escape hatch (it is what a `finishedListener` or per-frame guest logic needs),
flagged by a lint check, as the design proposed.

### What was built (prototype only, `-PkeliverComposePrototype`)

| Piece | Generated or hand-written | Where |
|---|---|---|
| `AnimatedVisibility` (plain, `ColumnScope.`, `RowScope.`), `Modifier.animateContentSize` (both overloads), `offset`, `rotate`, `scale` | **Generated** from Compose's API (lock level 5). Generator changes: a spec can name an extension receiver (`AnimatedVisibility@ColumnScope(…)`), a scoped overload's renderer calls the plain one with the scoped defaults, and a generic Compose type (`FiniteAnimationSpec<IntSize>`) maps to one wire class | `gatea.json`, `keliver-compose-generator` |
| Which parameters take an animated reference (`alpha`, `size`, `width`, `height`, `padding(all)`, `background(color)`, `offset`, `rotate`, `scale`), the stubs' `ExtraAnimated` element, the renderers' `animate*AsState` wrapping | **Generated**, from a config list (`animated`) | same |
| `animateFloatAsState`, `animateDpAsState`, `animateColorAsState` (animated references); `tween`/`spring`/`snap`, easings; `EnterTransition`/`ExitTransition` and their factories; `IntSize`/`IntOffset` mirrors | **Hand-written** mirrors and codecs (design §6: codecs are written once). Compose's `animate*AsState` are generic state functions, not widgets or modifiers, so the generator has nothing to emit for them; their tagging (below) is runtime code | `keliver-compose-values` (`dev.keliver.compose.animation*`) |
| The `ExtraAnimated` modifier (tag 500), the guest helper the stubs call, the host's `animated()` wrappers and transition converters | **Hand-written** runtime (one each, like the shimmer extra) | `keliver-compose-gatea-schema/Extras.kt`, `…-compose/internal/Animated.kt`, `…-composeui/Animation.kt` |
| `Animatable` (Float, Dp; Color in Oklab) with `animateTo`, `snapTo`, `stop` on `withFrameNanos` | **Hand-written**: the guest-side alternative; Compose's `Animatable` is not usable in the guest (it takes the real `ui-unit` and `ui-graphics` types) | `keliver-compose-values` |

How an animated reference crosses: the guest's `animate*AsState` returns the target *tagged* (a
`Float`/`Dp` whose bits are the target's plus a count; a `Color` with the count in packing bits no
component read uses), the stub recognises the tag, sends the exact target plus an
`ExtraAnimated(param, {id, spec, label})` element, and the renderer wraps that argument in the real
`animate*AsState`, in a movable group keyed by the id (a modifier inserted ahead of it does not
reset it). Arithmetic on a tagged value gives a plain literal; a host that predates the element
ignores it and shows the target without animation.

**What a guest read of an animated value is (decision 2).** Not the target. `AnimatedReferencesTest`
(JVM, JS, wasm) pins each of these as it is today:

- The tagged Float is the *count*-th representable float **away from zero**. Call sites that share a
  target take counts 1, 2, 3…, so the error grows with them: 1,000 call sites animating to `1f`
  read up to `1f` + 1,000 ULPs ≈ 1.00012.
- A target of `0f` reads as `Float.MIN_VALUE` (1.4e-45): `alpha > 0f` is true and `alpha == 0f` is
  false for as long as it is the target. `-0f` reads as `-Float.MIN_VALUE`.
- A negative target reads slightly more negative: a `-90f` rotation gives `r >= -90f` false, and
  `floor` of a tagged `-3f` is `-4`.
- `==` against the target is false for Float, Dp **and Color** (`c == Color.Red`, `when (c)`).
  Colour component reads (`red`, `alpha`, `argb`) are exact.
- Untaggable targets are returned as they are, with no animation: NaN, ±Infinity, ±`MAX_VALUE`
  (and any target whose tag would be non-finite or change sign), `Dp.Unspecified`, `Dp.Infinity`,
  `Color.Unspecified`.
- False positives: a plain value equal to a live tag is taken as animated (one ULP from a live
  target); on Kotlin/JS a whole interval of doubles rounds onto the tag. Unlikely in practice.

The alternative the owner chooses between is a **read log**: `animate*AsState` returns the exact
target through a `State` whose `value` getter appends `(ref, value)` to a short log; each generated
stub matches its arguments against the log by exact equality and clears it. Guest reads become
exact and same-source survives, and `1f - alpha` stays a plain literal as now. Its costs: it depends
on evaluation order (a `.value` read early and kept while other reads happen would miss its stub),
and a plain value equal to a just-read target is taken as animated within that call. Not built.

### Method

1. **JVM, deterministic (CI):** `SameSourceTest` gains the animation sample
   (`samples/{guest,native}/AnimatedPanel.kt`, only imports differ, checked by `onlyImportsDiffer`).
   Native Compose and the guest (through the real protocol, as JSON, into the generated renderers)
   render side by side on the test's clock, which the test drives by hand.
   - `hostAnimationMatchesNativeFrameByFrame`: `animateFloatAsState`, `animateDpAsState`,
     `animateColorAsState` (400 ms linear tweens), `ColumnScope.AnimatedVisibility` and
     `animateContentSize` (springs); captured at the start, 16, 96, 192, 304, 400 ms after the
     toggle and at the end. The guest's clock is sent the test clock's own frame times, so a
     guest-clock animation would advance and send batches;
     `theBridgeAssertionCatchesAGuestClockAnimation` runs the same schedule on an `Animatable`
     panel and asserts that it does.
   - `aModifierInsertedAheadOfAnAnimatedOneKeepsItsAnimation`: padding inserted ahead of an
     animated `alpha` 160 ms into its tween; compared at 192, 240, 304, 400 ms and the end.
   - `guestFrameClockAnimationReachesNativeEndState`: two `Animatable`s in `LaunchedEffect`s, the
     guest's clock sent the same frame times as the host's.
2. **Emulator, Macrobenchmark:** the sample (`sample/`, built like an adopter's app against the
   prototype published to a scratch Maven repository as `0.0.0-gateb`) with
   `-PkeliverComposePrototype` gets `GatebActivity`, a debug/benchmark-only route. Its screen,
   `sample/gateb-guest/…/AnimationBench.kt`, is plain Compose written once: 12 boxes whose alpha,
   size and colour animate back and forth every 700 ms (600 ms linear tweens), plus an
   `AnimatedVisibility` and an `animateContentSize`. Four forms: `host` (guest, `animate*AsState` →
   host references), `guest` (guest, `Animatable` on the guest's frame clock), and the same file
   compiled as native Compose in the host (`native-host`, `native-guest`; the copy is written by a
   Gradle task that rewrites the imports). The guest bundle (production build, signed with the
   sample's demo key) ships in the APK's assets. `GatebAnimationBenchmark` (FrameTimingMetric,
   `benchmark` build type, 5 iterations × 5 s of animation per form, after the route has drawn and
   settled 1 s). Jank = frames with a positive `frameOverrunMs`. Bridge traffic: the route counts
   `sendChanges` calls and the changes in them (a Zipline `EventListener.callStart`) and logs both
   once a second; read from the same `benchmark` APK for 15 s per form.
3. **App size:** the sample's release build (R8, `proguard-android-optimize.txt`), on the prototype
   stack, without the route (A) and with it (B, `-PkeliverGatebInRelease`: the activity, the
   generated renderers, their root protocol, keliver-material's renderers that root needs, the
   native copy and the bundle in assets). R8's mapping (kept classes) and `-printusage` (removed
   classes) count what stays of Material 3.

Device: one Android emulator (`google_apis`, API 35, 720 × 1600), on a shared development machine.
**An emulator is not a phone**: the numbers below are for comparing the forms with each other on
one device, not absolute frame times.

### Results

**JVM (deterministic):**

| Test | Result |
|---|---|
| Host references, `AnimatedVisibility`, `animateContentSize` against native, frame by frame | **0 differing pixels** at all 7 captured frames (start, 16, 96, 192, 304, 400 ms, end). **0 change batches** crossed while the host animated; the reference itself travelled once. The same schedule on a guest-clock animation sends batches (asserted) |
| A modifier inserted ahead of an animated one mid-animation | **0 differing pixels** at every frame after the insertion. Before the movable group per element, the host's `animateFloatAsState` was recreated and the alpha snapped to its target (6,400 differing pixels, the whole box) |
| Guest frame clock against native `Animatable` | 0 differing pixels at the start, at two mid frames and at the end, all asserted (linear tweens, the guest's clock fed the host's frame times). **25 change batches** for a 400 ms animation at 16 ms frames: one per frame |

**Emulator, frame timing** (two runs of the whole benchmark; per run, the median frame count over
5 iterations of 5 s, and percentiles over all frames of the 5 iterations):

| Form | Run | Frames / 5 s | Frame CPU p50 | p90 | p99 | Overrun p50 | p90 | p99 | Janky frames |
|---|---|---|---|---|---|---|---|---|---|
| host references (guest) | 1 | 273 | 3.7 ms | 4.1 ms | 4.8 ms | −9.8 ms | −8.3 ms | −4.8 ms | 0 of 1,363 |
| | 2 | 272 | 3.7 ms | 4.1 ms | 4.9 ms | −9.7 ms | −8.1 ms | −5.1 ms | 0 of 1,358 |
| guest frame clock (guest) | 1 | 285 | 3.7 ms | 4.4 ms | 5.6 ms | −10.1 ms | −7.5 ms | −3.8 ms | 0 of 1,423 |
| | 2 | 283 | 3.4 ms | 4.0 ms | 4.8 ms | −10.5 ms | −8.6 ms | −4.3 ms | 0 of 1,410 |
| native, `animate*AsState` | 1 | 272 | 3.7 ms | 4.2 ms | 5.1 ms | −9.7 ms | −8.2 ms | −4.9 ms | 0 of 1,362 |
| | 2 | 273 | 3.8 ms | 4.3 ms | 5.3 ms | −9.7 ms | −8.0 ms | −4.9 ms | 0 of 1,362 |
| native, `Animatable` | 1 | 273 | 3.8 ms | 4.2 ms | 4.9 ms | −9.7 ms | −8.2 ms | −5.0 ms | 0 of 1,366 |
| | 2 | 273 | 3.8 ms | 4.2 ms | 5.1 ms | −9.7 ms | −8.3 ms | −4.9 ms | 0 of 1,365 |

**Emulator, bridge traffic** (the same `benchmark` APK, 15 s per form, once-a-second readings):

| Form | Change batches / s | Changes / s |
|---|---|---|
| host references | 1.4 (one per toggle of the 700 ms period) | ~20 |
| guest frame clock | 28.5 (range 27.5–29.5) | ~330 (310–345) |

Reading the two together: the host's own frames cost the same in every form (the differences
between forms are smaller than between the two runs of one form: the guest frame clock's p90/p99
were the highest in run 1 and the lowest in run 2; it does draw ~4% more frames in both runs, the
extra recompositions its changes cause), and no form drops a frame on this device. What differs is
how often the animated values change: host references and native Compose compute a new value for
every host frame (~55 a second here); the guest frame clock produced ~29, so its animation moves on
about every other displayed frame, which FrameTimingMetric does not see because the host still
draws on time. The guest also pays ~330 property and modifier changes a second through QuickJS,
serialization and the bridge for 12 animated boxes, where host references send one batch per
change of target.

**App size** (release, R8; prototype stack):

| | A: without the route | B: with the route | Difference |
|---|---|---|---|
| APK | 10,003,950 B | 11,796,563 B | +1,792,613 B (+17.9%) |
| `classes.dex` | 2,555,808 B | 3,970,848 B | +1,415,040 B (+55%) |
| Kept classes (mapping) | 3,621 | 5,533 | +1,912 |
| … of which `dev.keliver.compose.gatea.*` (generated group) | 0 | 370 | |
| … `dev.keliver.material.*` (legacy renderers the group's root includes) | 0 | 92 | |
| … `androidx.compose.material3.*` | 0 | 448 | |
| … `androidx.compose.foundation.*`, `…animation.*` | 627 | 1,012 | +385 |
| … `coil3.*` | 0 | 170 | |
| The guest bundle in assets (uncompressed) | — | 1,948 KB | (the rest of the APK difference, compressed) |

**Risk 8 (does R8 keep all of Material 3?): no.** R8 kept 448 of material3's 2,166 classes and
removed 1,718 (79%). The renderers reference only the Compose functions the group generates, and R8
removes the rest as for any app. Of the kept classes, 28 belong to widget families the generated
group does not have (DatePicker, TimePicker, NavigationBar, SearchBar, Slider, bottom sheets,
carousel): they come from keliver-material's legacy renderers, which the generated group's root
schema includes as a dependency (§19's "one group, its own root schema"). **What a group split
would save:** with R8 on, little for the generated group itself; the measurable saving is not
shipping the legacy keliver-material renderers (92 classes here plus the Material 3 and Coil code
only they reach) in an app that uses only generated widgets, which needs the host factory to accept
absent groups (§14). Coil (170 classes) is pulled in by both `AsyncImage` renderers and would be a
natural optional group.

### Caveats

- **Emulator only, one session, a shared machine** (a CI job and other builds were running at
  times). Relative numbers only. The phone checkpoint (below) repeated the benchmark and the bridge
  counter on a low-end phone: the guest's rate was much further from the display rate there.
- **FrameTimingMetric times only the host's UI thread.** It does not see the guest's QuickJS thread,
  where the guest frame clock's real cost (recomposition, serialization) is paid.
- The bridge counter covers `sendChanges` calls into the host (Zipline's `callStart`), not the
  host's `sendFrame` calls into the guest; on the guest frame clock those run every frame too.
- The guest's 29 values a second is measured from batches, so it is an upper bound on distinct
  frames of the animation (each batch carries all 12 boxes' changes for one guest frame).
- The sample's prototype mode moves its stack to main's (Kotlin 2.4.20, Zipline 1.28, CMP 1.12.2,
  compileSdk 37) but stays on AGP 8.13 and Gradle 9.0, so androidx Compose 1.12's "AGP 9.1 or
  higher" AAR-metadata check is switched off in prototype builds, and JDK 17 is used where the
  sample asks for 11. The default sample build is unchanged.
- Size B includes the legacy keliver-material renderers because the generated group's root needs
  them today; a build with the generated renderers alone was not possible without the host-factory
  change above. B's dex also includes the route's native copy of the screen (small).
- The guest-side `Animatable`'s tween and spring are written to match Compose's curves; the JVM test
  shows identical pixels for linear tweens at the frames it samples, not for every easing.
- Animated references on **widget** parameters (a `Text`'s colour) are supported by the generator
  but no widget is configured yet; only the modifiers listed above take one.
- Not mirrored: `keyframes`, `repeatable`, `infiniteRepeatable`, `rememberInfiniteTransition`,
  `AnimatedContent`, `Crossfade`, `updateTransition`, `animateItem`'s specs (still dropped),
  transitions' lambda parameters, `finishedListener` (an `ERROR` overload).

## Phone checkpoint (Android): Gate B-e on a low-end phone

What it answers: whether #244's emulator reading of the animation forms holds on a real low-end
phone, and what the guest's frame clock delivers there. Phone: a low-end Android phone (Galaxy A12,
Android 12), 60 Hz display, on wireless adb, 2026-11-14 (run 1 and the bridge counter in #245; run
2 and the cold start that evening).

**Recommendation: unchanged, and stronger.** Host references for `animate*AsState`. On this phone
the guest's frame clock delivered **about 3 new values a second** (emulator: about 29) while the host
drew 32–53 frames a second, so a guest-clock animation visibly steps rather than moves. Host
references sent the same ~19 changes a second as on the emulator (one batch per change of target).
Host references cost somewhat more per host frame than native on this phone: frame CPU p50 27.2
against 19–20 ms in the first run (phone warm and charging) but 20.6 against 18.9 ms in the second
(cooler, on battery), and the longest overrun p99 tail in both runs. They still win on what the guest
delivers. A cold start to the guest's first content takes **2.58 s against 0.92 s** for the same
screen compiled in (median of 10 each; below). The escape hatch (`Animatable` on
the guest's frame clock) should be flagged more strongly than the lint check the design proposed
(not built yet): on low-end phones it is not an animation.

### Method

The same `benchmark` build of the sample's prototype route as #244 (prototype republished from
main to a scratch repository as `0.0.0-gateb`), with three prototype-only additions:

- `GatebPhone` records the phone's battery level, charging state, temperature, power-saving mode,
  thermal status and refresh rate next to each measurement, and skips a measurement below a battery
  level (`gatebMinBattery`: 50% for run 1, 30% for run 2 and the cold start).
- `GatebBridgeCounter` is #244's bridge counter as an instrumented test: per form it opens the
  route, waits 2 s, then for 15 s reads the route's once-a-second bridge log and the host's frames
  over the same window (`dumpsys gfxinfo`, reset at the start of the window). It runs without
  Macrobenchmark's compilation step, so its code ran as installed (JIT).
- `GatebStartupBenchmark` (StartupTimingMetric, `StartupMode.COLD`, `host` against `native-host`,
  10 iterations each) with `reportFullyDrawn` on the route's first content. The guest route reports
  inside a frame callback once its first change batch has been applied, and the benchmark keeps
  tracing 1 s past the label: without both, Macrobenchmark failed on this phone with "No RT frame
  slice associated with UI thread frame slice ends after reportFullyDrawn" (on the first iteration
  as #245 left it; after 4 with a report followed by an invalidate).

The phone was locked (secure lock screen), left charging in run 1 and on battery in run 2, so every measurement wakes the screen
first and opens the route with the `overLockScreen` extra: only then does the route keep the screen
on and draw over the lock screen (`setShowWhenLocked`; it does not unlock the phone). This is
benchmark-only; never in a real host: it would show guest content on a locked phone. The lock
screen behind the route is not drawn while the route covers it.

### Results

**Bridge traffic and the guest's rate** (15 s per form, once-a-second readings, medians with the
range; phone at 75%, charging, 33.6–34.2 °C, thermal status 0, no power saving):

| Form | Change batches / s (guest frame clock: = new guest values / s) | Changes / s | Host frames / s (same window) | Emulator (#244): batches / s, changes / s |
|---|---|---|---|---|
| host references | 1.3 (1.3–1.4) | 18.8 (18.3–19.3) | 53.1 | 1.4, ~20 |
| guest frame clock | **2.8 (2.3–3.1)** | 35.5 (29.3–38.7) | 32.1 | **28.5**, ~330 |
| native, `animate*AsState` | — | — | 42.5 (first form after install; 83% of its frames janky) | — |
| native, `Animatable` | — | — | 55.5 | — |

**Frame timing** (FrameTimingMetric, 5 iterations × 5 s per form; median frame count over the
iterations, percentiles over all frames of the 5 iterations, as Macrobenchmark reports them). Two
complete runs: run 1 in #245, run 2 in a later session (2026-11-14, evening). The emulator
counterpart is #244's run 1 (run 2 was within a few tenths of a millisecond of it). Janky frames
(a positive `frameOverrunMs`) are counted from the per-frame JSON, which exists for run 2 only; the
frames whose CPU time alone exceeds 16.7 ms are beside them:

| Form | Run | Frames / 5 s | Frame CPU p50 | p90 | p99 | Overrun p50 | p90 | p99 | Janky frames (overrun > 0) | CPU > 16.7 ms | Emulator: frames, CPU p50 / p90 / p99, overrun p50 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| host references (guest) | 1 | 233 | 27.2 ms | 36.4 ms | 82.6 ms | 12.6 ms | 35.4 ms | 136.3 ms | not saved | not saved | 273, 3.7 / 4.1 / 4.8 ms, −9.8 ms |
| | 2 | 274 | 20.6 ms | 31.6 ms | 59.7 ms | 5.4 ms | 26.8 ms | 88.4 ms | 1,165 of 1,209 (96%) | 1,080 (89%) | |
| guest frame clock (guest) | 1 | 153 | 21.2 ms | 28.9 ms | 66.0 ms | 7.8 ms | 20.6 ms | 60.3 ms | not saved | not saved | 285, 3.7 / 4.4 / 5.6 ms, −10.1 ms |
| | 2 | 159 | 16.8 ms | 27.4 ms | 46.4 ms | 3.6 ms | 20.0 ms | 41.7 ms | 759 of 796 (95%) | 400 (50%) | |
| native, `animate*AsState` | 1 | 190 | 20.4 ms | 37.4 ms | 64.9 ms | 5.3 ms | 42.9 ms | 96.6 ms | not saved | not saved | 272, 3.7 / 4.2 / 5.1 ms, −9.7 ms |
| | 2 | 162 | 18.9 ms | 34.9 ms | 52.8 ms | 4.2 ms | 38.1 ms | 73.8 ms | 767 of 797 (96%) | 707 (89%) | |
| native, `Animatable` | 1 | 218 | 19.0 ms | 36.3 ms | 59.3 ms | 4.1 ms | 37.2 ms | 81.7 ms | not saved | not saved | 273, 3.8 / 4.2 / 4.9 ms, −9.7 ms |
| | 2 | 151 | 18.9 ms | 33.4 ms | 50.0 ms | 4.1 ms | 33.3 ms | 71.6 ms | 786 of 813 (97%) | 672 (83%) | |

Phone state. Run 1: the state file was not retrieved (below); the readings about 10 minutes before,
in an earlier pass whose results were lost, were 77–79%, charging, 33.1–33.3 °C, thermal status 0
rising to 1 ("light"), no power saving. Run 2: 87% → 86%, on battery (not charging), 26.3 °C,
thermal status 0, no power saving, 60 Hz, at each form's start (the first form's line was not kept
by the state file; `dumpsys battery` just before the run read 87%, unplugged, 26.3 °C).

**Cold start** (StartupTimingMetric, `StartupMode.COLD`, 10 iterations per form; ms; with 10
samples p99 is effectively the maximum). Phone: 83%, on battery, 26.7 °C, thermal status 0, no power
saving (`dumpsys battery` before the run read 83%, unplugged, 26.7 °C):

| Form | Time to full display (first content): median | p90 | p99 | min | Time to initial display: median | p90 | p99 |
|---|---|---|---|---|---|---|---|
| native (`native-host`, the screen compiled in) | **919** | 1,018 | 1,043 | 903 | 919 | 1,018 | 1,043 |
| guest (`host`, the manifest from the APK's assets, the code from Zipline's cache) | **2,581** | 2,812 | 2,813 | 2,404 | 823 | 858 | 903 |
| guest − native | **+1,662** | | | | −96 | | |

The guest's initial display is most likely an empty surface (the activity's first frame; inferred
from its timing, not captured), so it comes slightly
earlier than native's, whose first frame already has the content. Its full display is the frame
after the first change batch has been applied: reading the manifest from the APK's assets (no
network), loading the ~1.9 MB bundle's modules (1,948 KB in assets, see the APK table above) into
QuickJS from Zipline's cache, and the guest's first composition. The median is a warm-cache start:
only each run's first iteration read the modules from the APK's assets (and wrote the cache). Where the 1.66 s goes: see
[Where the A12's cold start goes](#where-the-a12s-cold-start-goes) below. The same phone with a
list screen that calls an API, as native, guest **and a web page**, plus navigation and API-call
timings: [THREE_WAY_BENCHMARK.md](THREE_WAY_BENCHMARK.md).
The emulator has no cold-start number (#244 did not measure one). On the iPhone 13 the same gap
from `main()` was +142 ms warm and about +820 ms with an empty cache (section above); the two are
not the same measurement (Android's cold start includes the process and the activity, and this
bundle is a different screen), so compare their sizes only roughly.

Reading them together:

- **The guest's frame clock is the finding.** About 3 guest frames a second against 28.5 on the
  emulator, which suggests each guest frame (recomposition of 12 animated boxes in QuickJS,
  serialization, the bridge) takes roughly a third of a second on this phone (not profiled on the
  QuickJS thread). The host's UI thread is not what limits it: in the same window the host drew 32
  frames a second (the bridge counter cannot see the guest's QuickJS thread, and why the host draws
  32 frames a second while only 2.8 new values arrive was not investigated). FrameTimingMetric does
  not show this (the guest form even draws the *fewest* host frames, 153 per 5 s, perhaps because
  there is less on the host to animate; not measured), so the bridge counter is the measurement that
  matters for this form.
- **Host references cost somewhat more per host frame than native on this phone.** Every form
  overruns the 16.7 ms frame budget at the median on this phone, native Compose included: 95–97% of
  frames have a positive overrun in every form in run 2, so the janky-frame count does not separate
  the forms here. The screen (12 boxes animating alpha, size and colour at once, plus
  `AnimatedVisibility` and `animateContentSize`) is heavy for the device. Host references drew the
  most frames in both runs (233 and 274 per 5 s). Their frame CPU p50 was about 7 ms above native's
  in run 1 (27.2 against 19–20 ms) and 1.7 ms above in run 2 (20.6 against 18.9 ms); run 1's phone
  was warm and charging, run 2's cooler and on battery, so the size of the gap depends on the
  phone's state. Their overrun p99 tail was the longest in both runs (136 and 88 ms, against 72–97
  for native). Two runs still do not say how much of that is the generated renderers and how much
  is the guest's QuickJS thread competing for this phone's CPU while the host animates; the native
  forms' own spread between runs (151–218 frames) is of the same order.
- **Cold start is the largest phone-side cost of the guest route**: 2.58 s to the guest's first
  content against 0.92 s native (+1.66 s), with the code already in Zipline's cache and no network
  (the first start after an install or an update costs more: see the next section). It is a cost of the guest route
  (loading and starting the guest), not of the generated API: Gate A's "first frame no slower"
  compared the generated guest with today's guest, and this compares a guest with native.
- The bridge counter's host frame rates (53 host references, 56 native `Animatable`) are higher
  than the benchmark's (47, 44 frames a second): different windows, and the counter ran without
  Macrobenchmark's compilation and process restarts per iteration. Its native `animate*AsState`
  reading came first after the install and is a warm-up figure.

### Where the A12's cold start goes

The question: on a low-end Android phone (Galaxy A12, Android 12) the guest reaches its first content
1.66 s after native (#247). What is in those 1.66 s, and what would remove each part?

**Method.** First the 20 Perfetto traces of #247's run, read with Perfetto's trace processor:
thread states and slices between process start and the frame that ends time to full display. Then
prototype-only trace sections in `GatebActivity` (prefix `kg:`; `src/gateb`, only in
`-PkeliverComposePrototype` builds), written from Treehouse's `EventListener` hooks: the code load,
each download, the manifest's verification, Zipline's creation, each module's load into QuickJS,
the initializer, `main`, the first 40 bridge calls, and the moment the first change batch has been
handed to the host. Then the same cold start (`GatebStartupBenchmark`, `StartupMode.COLD`, 10
iterations per form, built and run as #247 did) twice on the phone, on battery, 26.7–27.0 °C,
thermal status 0, 81% falling to 78%. Session 1 measured `host` and `native-host`; session 2 the
same two plus three experiments. No slowdown from the markers is visible, though this compares
sessions (there was no A/B run with the markers off): `host` 2,514 and 2,518 ms against #247's
2,581, native 945 and 927 against 919, so the gap is +1,569 and +1,591.

**What the existing traces already showed.**
- The app is AOT-compiled with `speed-profile` (Compose's baseline profiles). Both forms get the
  same compilation; JIT work during the guest's start is small (about 40 ms, mostly
  kotlinx.serialization's JSON lexer and Zipline's Ed25519 arithmetic, so those are not in the
  profile). No GC in the window. Class loading about 80 ms.
- The Treehouse (QuickJS) thread runs for 1.35 s of CPU between 0.73 s and 2.4 s. Another
  0.3 s runs on the Default dispatcher's threads. The main thread is idle for most of that time.
- Only iteration 0 read the whole bundle from the APK's assets (52 files) and wrote Zipline's
  cache. Iterations 1–9 read only the manifest from assets and the 51 modules from the cache, so
  #247's median is a start with a warm code cache. Iteration 0, with an empty cache, was among the
  slowest guest starts in every run: 2.78–3.25 s, +0.36 to +0.87 s over its form's median (+0.36
  and +0.41 for `host`; +0.87 for `earlyStart`, which was session 2's first test after the
  install). That is the start every user gets once after an install or an OTA update, measured here
  from assets; over a network the download comes on top.

**The breakdown** (session 2, medians of 10, ms; each phase is the difference of the medians of its
two boundaries, and they add up to the median time to full display. Session 1's are within 15 ms
of these):

| Phase (guest, `host`) | Boundaries | ms | CPU on the QuickJS thread | Share of the +1,591 |
|---|---|---|---|---|
| Process, application, activity, until the first composition starts the code load | 0 → Treehouse thread's first run | 732 | | shared with native |
| Loader start: cache database open, cached manifest read and verified, then rejected as not fresh | → manifest read from assets | 169 | 107 | 11% |
| Manifest from assets, parsed, signature verified | → `manifestVerified` | 76 | 111 (with the next row) | 5% |
| QuickJS created | → `ziplineCreated` | 47 | | 3% |
| 51 modules from the cache into QuickJS | → last `moduleLoadEnd` | 502 | 366 (evaluation 300) | 32% |
| `main`, the app service, lifecycle, `launchHost` | → `ZiplineTreehouseUi.start` | 131 | 85 | 8% |
| **The guest's first composition, in QuickJS** | → first `sendChanges` | **606** | **593** | **38%** |
| First change batch encoded, crossed, decoded | → `sendChanges` returns | 37 | 37 | 2% |
| Host applies the changes, composes, lays out, draws | → time to full display | 218 | (main thread 98) | 14% |
| **guest total** | | **2,518** | | |
| native total − 732 (arithmetic, not a phase) | 927 − 732 | 195 | | −12% |

The 732 ms is subtracted from both forms arithmetically; it is not a shared phase boundary. Native's
first frame starts at 599 ms and takes 324 ms on the main thread, content included; the guest's
first frame (empty) starts at 624 ms and takes 196 ms, so it spans the 732 ms point.

**What dominates.** Two things of similar size, both CPU on one thread:
- **QuickJS running guest code: up to about 0.9 s.** An upper bound: the first composition's
  593 ms of CPU includes the host-side bridge calls it makes during that phase (ART code on the
  same thread), which were not separated out. The guest's first composition (606 ms, 98% of it
  running) and evaluating the 51 modules' top-level code (300 ms). The biggest modules are Compose
  runtime 78 ms, the generated group's guest protocol 35, the Kotlin stdlib 34, the screen's own
  module 23, kotlinx.serialization core and JSON 21 each, coroutines 14; the other 44 take under
  12 ms each. The bundle is already QuickJS bytecode (Zipline compiles it at build time), so none
  of this is parsing. Later guest frames of the same screen take 100–350 ms each in the trace, so
  the first composition is not much of an outlier on this phone. On the iPhone 13 the whole guest
  start to the first widget was 180 ms.
- **The loader around it: about 0.5 s.** Loader start and the rejected cache attempt (169), the
  manifest (76), QuickJS creation (47), about 200 ms of per-module overhead in the module phase
  (51 cache lookups, file reads and hash checks, about 4 ms each, partly waiting on an IO thread),
  and `main` through `launchHost` (131).
- The host's own work after the first batch arrives (218 ms) is close to what native spends on its
  first frame, so it is not where the gap is.

**Experiments** (session 2, labelled; prototype-only route extras, each 10 iterations):

| Form | Time to full display, median | vs `host` | What changed |
|---|---|---|---|
| `host` | 2,518 | | |
| `host` + `earlyStart`: `app.start()` in `onCreate`, not on the first composition | 2,373 | −145 | the code load starts at 577 ms instead of 732 |
| `host` + `freshCache`: a `FreshnessChecker` that accepts the cached code | 2,419 | −99 | no manifest fetch before loading from the cache; the manifest and QuickJS-creation phases' CPU 111 → 34 ms |
| both | 2,289 | −229 | |
| `native-host` | 927 | | |

Both experiments only move or skip loader work. Neither touches the 0.9 s that QuickJS spends.

**Options, ranked by what they could remove from the ~1.6 s** (estimates unless marked measured):

1. **Make the guest's first composition cheaper (up to ~0.6 s; size unknown).** This is the
   largest phase, and no loader change touches it. The next step is to profile it from inside the
   guest: how much is one-time Compose and Kotlin/JS initialization, and how much is this screen
   (12 boxes, each animating alpha, size and colour at once)? `KeliverTrace.composed()` already
   marks where a composition ends. A first frame that starts the animations after it is drawn
   would cut this screen's share. QuickJS has no JIT, and Zipline has no other engine, so a faster
   engine is not an option. Cost: a guest-side profile (hours), then work that depends on what it
   finds.
2. **Fewer, smaller modules (~0.1–0.3 s).** One or a few `.zipline` files instead of 51 would remove
   most of the ~200 ms of per-module overhead. Dead-code elimination, or lazily initialized
   top-level properties, would cut the 300 ms of evaluation (not checked: what this production
   build already does). Cost: a Kotlin/JS and Zipline build change. A bundle in fewer modules
   loses per-module caching, so any change downloads it again. Not in Keliver today.
   An alternative that keeps the modules: overlap the cache reads and hash checks with QuickJS
   evaluating the previous module, rather than merging modules (inference: not checked in
   Zipline's loader, which may already do part of this).
3. **Start the code load earlier (measured −145 ms from `onCreate`; more from `Application`).**
   Keliver's production-host template starts at process start (`HostApp.onCreate` calls
   `KeliverHost.start()`), but its `run()` first looks up the bundle in the index over the network
   (`lookupBundle`, bounded by `LOOKUP_BUDGET_MILLIS`, 10 s) and only then starts Treehouse with
   `DefaultFreshnessCheckerNotFresh`, which fetches the manifest over the network too. So a
   scaffolded host waits for two serial network round trips before any cached code loads, and on
   a real network it is likely *slower* than this route (assets, no network), not faster (not
   measured). The fix there is ordering: start the cached load in parallel with the lookup, and
   switch to the looked-up bundle if it differs. Treehouse's `Content.preload()` does not help this
   start: it starts the guest's content before the view is bound only if the code is already
   loaded (`TreehouseAppContent.preload`), and here the view is bound at about 0.63–0.73 s while
   the code is ready at about 1.5 s, so there is nothing to overlap. It helps only when the code is already
   warm, for example a second screen.
4. **Load from the cache without network round trips first (measured −99 ms here; −229 with 3).**
   From assets the manifest is cheap. Over a phone network the saving also includes the manifest's
   round trip and, in the production-host template, the index lookup's (option 3), which can be
   removed or overlapped with a cache start. Zipline supports it through `FreshnessChecker`.
   Normal starts in the production-host template use `DefaultFreshnessCheckerNotFresh` (network
   first), and `AcceptCachedBundle` only for rollback and fall-back. Cost: a policy decision,
   because a start would show the previous code until the next start picks up an update.
5. **A baseline profile for the host's loader path (~50–150 ms, unmeasured).** About 330 ms of CPU
   on the QuickJS thread is ART code, not QuickJS: the loader, sqlite, the manifest's
   serialization and Ed25519 verification, the bridge. The JIT compiling parts of it during start
   shows that they are not in the profile. Keliver ships no baseline profiles today. A library
   profile in `keliver-treehouse-host` would reach every host. Cost: a profile generator (a
   Macrobenchmark module) and keeping it current.
6. **Host apply and draw (little to gain).** 218 ms, comparable to native's own first frame.

Separately, **the first start after an install or an OTA update** is its own line item, outside
this median: measured +0.36 to +0.87 s over the warm-cache start with the bundle read from assets
(see above), and more over a network, where the modules are downloaded. Every user hits it once
per update. Option 2's fewer modules would make it larger per change (the whole bundle again).

None of the measured options goes below about 1.35 s over native (2,289 against 927). Most of the
rest is QuickJS executing guest code on this phone's CPU.

**Unverified.** The guest's first composition is not split further (no guest-side marks). The
per-module overhead is inferred from the gaps between module loads (CPU and waiting were not
attributed per module). Option 2's saving and option 5's are estimates. The experiments are
single sessions of 10 iterations on one phone. Starting from `Application.onCreate` was not
measured on this route, and neither was the production-host template's start over a network.
The 0.9 s attributed to QuickJS is an upper bound (it includes host-side bridge calls).

### Caveats

- **Run 1's per-frame data is lost.** In #245 a first full pass completed on the phone but its
  outputs were overwritten by the next pass before they were saved; the second pass completed but
  the wireless adb connection dropped while its outputs were being pulled, so only Macrobenchmark's
  per-test summaries (frame count min/median/max; CPU and overrun P50/P90/P95/P99) were retrieved,
  not the per-frame JSON (hence no janky-frame count for run 1) or the phone-state file. Run 2 and
  the cold start were measured in a later session, with every output saved.
- The two runs were made in different phone states: run 1 charging (75–79%; inferred from the
  readings around it, since its state file was lost) and warm (33–34 °C, thermal status up to
  "light"); run 2 and the cold start on battery (87–83%) and cool (26.3–26.7 °C, thermal status 0).
- One bridge-counter pass (15 s per form), in run 1's session.
- The cold start is the benchmark build (R8, release-shaped) under Macrobenchmark's default
  compilation, with the bundle in the APK's assets: no network, unlike the iPhone's.
- Wireless adb, a secure lock screen behind the route (not drawn while covered), a 60 Hz display.
  At 90/120 Hz the gap between the guest's rate and the display's would be larger.
- The A12's numbers are for this phone class; the emulator's were relative only.
