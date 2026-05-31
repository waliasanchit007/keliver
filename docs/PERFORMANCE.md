# Konduit performance — baseline measurements

> **Reading guide.** This document is for adopters trying to predict
> how much code, time, and memory Konduit will cost them. It tracks
> four metric categories — **artifact size**, **build time**,
> **runtime latency**, and **memory footprint** — with as much
> reproducibility as we can deliver, plus methodology for the metrics
> that still need device-level work.
>
> Numbers are point-in-time. Each release tag re-runs the
> reproducible measurements and updates the table; the cold-start /
> warm-mount / update-latency rows are device-dependent and queued
> for the perf workstream's Phase 2.

All baseline numbers below come from the `sample/` reference app
(`Box { Text("Hello, Konduit!") }`) built against
`1.0.0-caliclan.4-SNAPSHOT`. Re-running every measurement is a
one-liner away — see ["How to reproduce"](#how-to-reproduce).

---

## Artifact sizes — *measured*

### Android APK

| Variant | Size | Notes |
|---|---|---|
| Debug (unsigned) | **13.4 MB** | Full Compose runtime + Konduit + Zipline (incl. QuickJS native libs for arm64 + x86_64) |
| Release (R8, unsigned) | **10.8 MB** | -19% from debug — R8 trims compose + Konduit reflection paths |

The dominant size contributor is **`libquickjs.so`** (the JS engine
that runs the guest bundle) plus Compose runtime. Konduit's own
host-side code adds ~250 KB; the rest is dependencies.

### Zipline guest bundle

The guest bundle is what the host downloads at runtime. Two
configurations matter:

| Variant | Total | Konduit + sample own-code | Kotlin runtime |
|---|---|---|---|
| Development | **2.8 MB** | ~80 KB | 1.0 MB (stdlib + coroutines + serialization + Compose) |
| Production | **732 KB** | **12 KB** | 532 KB |

The "Konduit + sample own-code" column is the cost of *all*
Konduit guest modules combined with the sample's domain code —
**12 KB in production mode**. Effectively every byte beyond that
is the Kotlin/JS standard runtime that the JS engine needs anyway.

Per-module breakdown of the Production bundle (largest first):

| Module | Bytes | Role |
|---|---:|---|
| `kotlin-kotlin-stdlib` | 208,000 | Kotlin stdlib |
| `kotlinx-serialization-core` | 124,000 | Wire-format codec |
| `kotlinx-coroutines-core` | 120,000 | `suspend` machinery |
| `kotlinx-serialization-json` | 80,000 | JSON impl |
| `zipline-root-zipline` | 80,000 | Zipline runtime |
| `konduit-treehouse-guest` | 3,734 | Lifecycle + protocol glue |
| `konduit-sample-guest` | 3,272 | The sample's own `main()` |
| `konduit-sample-shared-protocol-guest` | 1,031 | Codegen output |
| `konduit-konduit-runtime` | 760 | Konduit Compose binding |
| `konduit-konduit-compose` | 635 | Compose-Konduit bridge |
| All other `konduit-*` | <500 each | Individual schemas / widgets |

The takeaway: **once you've shipped Konduit at all, adding new
widgets or screens costs single-digit KB per addition.** The
runtime cost is fixed, the per-feature cost is small.

### iOS framework

| Variant | Size | Notes |
|---|---|---|
| `KonduitSampleHost.framework` (debug, simulator-arm64) | **187 MB** | Static framework, debug symbols, full Kotlin Native stdlib |

iOS frameworks are large in debug because:
1. They embed the entire Kotlin Native stdlib (since they're `isStatic = true`)
2. Debug symbols are unstripped
3. Compose Multiplatform's iOS runtime is hefty

A `linkReleaseFrameworkIosArm64` build on a real device target
typically lands ~25-35 MB, but the exact number depends on what
schema widgets and host services your project pulls in. iOS
size measurements should be re-run per-release with a representative
schema.

---

## Build time — *measured*

| Step | Cold (no cache) | Warm (re-run) |
|---|---|---|
| `:guest:compileKotlinJs` | ~7 s | <1 s |
| `:guest:compileDevelopmentZipline` | ~3 s (after JS compile) | <1 s |
| `:host-compose:compileKotlinIosSimulatorArm64` | ~35 s | <1 s |
| `:host-compose:linkDebugFrameworkIosSimulatorArm64` | ~110 s | <30 s |
| `:host-android:assembleDebug` (full) | ~160 s | ~10 s |

Measured on `Sanchits-MacBook-Pro-3.local` (Apple Silicon, JDK 17,
Gradle 9.0.0). Cold means after `./gradlew --stop` + deleted `build/`
directories; warm means consecutive runs without touching source.

The iOS framework link dominates wall time for cross-platform
adopters. If you're shipping only Android, the meaningful number is
the 160 s full Android cold build — small enough to live with for
day-to-day work.

---

## Runtime latency — *planned, methodology below*

These are the three numbers that matter most to UX but require
device-level instrumentation to measure honestly. Methodology and
target SLAs are committed here so the measurement work is bounded;
the actual baselines land in a follow-up perf-workstream PR.

### Cold start: process launch → first widget rendered

**What it measures.** Wall time from `MainActivity.onCreate` to
the moment `TreehouseContent` finishes its first composition pass
displaying the guest's tree. Excludes the warm-cache case where
the Zipline bundle is already on disk.

**How to measure (Android).** Use AndroidX
[Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview):

```kotlin
@LargeTest
@RunWith(AndroidJUnit4::class)
class ColdStartBenchmark {
  @get:Rule val rule = MacrobenchmarkRule()

  @Test fun coldStart() = rule.measureRepeated(
    packageName = "dev.keliver.sample",
    metrics = listOf(StartupTimingMetric()),
    iterations = 5,
    startupMode = StartupMode.COLD,
  ) { pressHome(); startActivityAndWait() }
}
```

**Target SLA (proposed).** P50 ≤ 800 ms on a Pixel 6 with a fresh
zipline cache, P95 ≤ 1500 ms. Caveat: the network fetch of the
manifest dominates the cold case — adopters who embed the manifest
in assets will see substantially lower numbers.

**How to measure (iOS).** Wrap the `MainViewController()` call site
in `mach_absolute_time()` deltas and post the result via
`os_signpost`. Capture in Instruments → Logging.

### Warm mount: tab switch → guest tree renders

**What it measures.** Time from a host-level state change (the
adopter swaps which `TreehouseApp` is mounted, or which
`ZiplineTreehouseUi` the `contentSource` returns) until the new
tree paints. The Zipline runtime is already alive; this is the
"is my screen change fast?" metric.

**How to measure.** Compose `Snapshot` observer counting
recompositions, paired with `Choreographer.FrameCallback` to capture
the frame deadline the new tree first met. Land both as a
test-only fixture under `konduit-benchmarks/` so they can run in
CI on emulators.

**Target SLA (proposed).** P50 ≤ 100 ms, P95 ≤ 250 ms — matching
upstream Compose tab-switch budgets.

### Update latency: host mutation → widget reflects it

**What it measures.** Bidirectional path. Two sub-metrics:

- **Host → guest**: a host calls into a bound `HostXxxProvider`,
  the guest's `@Composable` reacts via `collectAsState`, the
  rendered widget updates. End-to-end time.
- **Guest → host**: a guest `@Composable` updates its state
  (`var x by mutableStateOf(...)`), the protocol message ships
  across Zipline, the host renderer recomposes.

**How to measure.** Same instrumentation pattern as warm mount —
Compose snapshot observer + frame timing — but driven from a
test-only "tick" host service that posts a state change every N
seconds and records the latency until the matching widget updates.

**Target SLA (proposed).** P50 ≤ 16 ms (single frame at 60 Hz),
P95 ≤ 50 ms. Anything slower is user-perceptible jank.

---

## Memory footprint — *planned*

**What to measure.** Three points on the curve:

1. **Idle heap.** RSS + Java heap immediately after `MainActivity`
   finishes onCreate, no further user input.
2. **Per-screen delta.** Heap delta after navigating to / mounting
   one fresh `TreehouseContent` (the marginal cost of an active
   guest tree).
3. **Zipline cache size.** Disk-level — the configured 50 MB
   max in `TreehouseAppFactory` is a ceiling; what does steady-state
   look like for a real app's bundle?

**How to measure.** `Runtime.getRuntime().totalMemory()` /
`Debug.MemoryInfo()` snapshots on Android, paired with profiler
deltas. iOS: `task_info()` for resident set size, plus the
`MetricKit` framework if available.

**Target SLA (proposed).** Idle overhead ≤ 25 MB beyond what an
equivalent native-Compose-only app would use; per-screen delta
≤ 5 MB.

---

## Comparison baselines

Two reference points belong in any final perf report:

1. **Native Compose-only.** The same `Box { Text("Hello, Konduit!") }`
   rendered without the Konduit guest layer — pure Android Compose
   + iOS Compose Multiplatform. This isolates the Konduit overhead
   from the underlying Compose runtime cost. Implementation: a
   sibling `:host-android-native` module in `sample/` that omits
   the `:guest` dependency.

2. **Upstream Cash App Redwood 0.18.0.** Konduit forks from Redwood
   0.18.0; running the same widget through upstream Redwood
   demonstrates whether Konduit has introduced perf regressions.
   Implementation: a `redwood-018-baseline/` sibling project
   pinned to that version (no Konduit). Practical caveat — the
   namespaces diverge significantly so the comparison needs a
   schema port. Defer until adopter demand surfaces.

Both comparisons run with **the same methodology** as the
Konduit-side measurements above, so the deltas are apples-to-apples.

---

## How to reproduce

All "measured" sections above run from a clean clone of this repo:

```sh
git clone https://github.com/waliasanchit007/konduit && cd konduit/sample
# Set up GH Packages auth — see sample/README.md
./gradlew --stop
rm -rf */build .gradle

# Artifact sizes
./gradlew :host-android:assembleDebug :host-android:assembleRelease
./gradlew :guest:compileDevelopmentZipline :guest:compileProductionExecutableKotlinJsZipline
./gradlew :host-compose:linkDebugFrameworkIosSimulatorArm64

# Build times
./gradlew --stop  # ensure cold daemon
time ./gradlew :host-android:assembleDebug
./gradlew :host-android:assembleDebug  # second run = warm
```

Cold-start / warm-mount / update-latency / memory-footprint
reproductions require the device-level instrumentation
(macrobenchmark, Instruments) described in their respective
methodology sections above. The benchmarking fixtures themselves
are queued for `konduit-benchmarks/`.

---

## Roadmap

This document is **measured + planned**, never speculative. The
status of each metric:

| Metric | Status |
|---|---|
| Android APK size (debug + release) | **measured** (above) |
| Zipline bundle size (Development + Production) | **measured** |
| iOS framework size (debug) | **measured** |
| Build time (cold + warm) | **measured** |
| Cold start latency | planned — Phase 2 |
| Warm mount latency | planned — Phase 2 |
| Update latency | planned — Phase 2 |
| Idle memory footprint | planned — Phase 2 |
| Native-Compose comparison | planned — Phase 3 |
| Redwood 0.18.0 comparison | planned — Phase 3 (adopter-demand-gated) |

**Phase 2 scaffolding (now landed).** A working
[`sample/benchmarks/`](../sample/benchmarks/) Gradle module with
AndroidX Macrobenchmark `ColdStartBenchmark` + `warmStartup`
fixtures, a `benchmark` build type on `:host-android` configured
to satisfy Macrobenchmark's safety checks (`isDebuggable = false`,
debug-key signed, `<profileable android:shell="true">` in the
manifest, `androidx.profileinstaller` baked in), and a matching
`benchmark` build type on the `:benchmarks` module itself with
`matchingFallbacks += "release"` so the project-dep resolver finds
`:host-compose`'s release variant. Run with:

```sh
./gradlew :benchmarks:connectedBenchmarkAndroidTest
```

**Phase 2 known limitation — emulator framestats flakiness.**
Running the cold-start fixture against an Android emulator (any
recent API level) trips Macrobenchmark's `Unable to confirm
activity launch completion` check — the emulator's
`dumpsys gfxinfo … framestats` table is slow to populate after
activity launch, and Macrobenchmark times out before the first
frame's stats land. Same symptom on bare Pixel emulator AVDs as
on the higher-API ones. The fixture is correct (the sample DOES
launch + render — verified independently by running
`adb shell am start …` + `screencap`), only the activity-launch
detector is unreliable on emulator.

**Recommended adopter workflow.** Run benchmarks against a real
device (USB-attached or `adb connect`). Emulator runs are useful
for verifying the harness compiles + installs, but the actual
numbers should always be measured on hardware. The
`testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"`
in `:benchmarks/build.gradle.kts` lets emulator runs at least
*attempt* the measurement, but the framestats issue is upstream
and not fixable in Konduit's harness.

**Phase 3** is the comparison work — native-Compose-only and
upstream Redwood 0.18.0 baselines — and depends on Phase 2 numbers
being captured on a hardware reference device first. Adopter-demand
gated.
