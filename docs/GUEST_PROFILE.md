# Guest profile: where a guest navigation's time goes

Measured 2026-11-16 on a Mac (Apple silicon), without a device. Prototype code only; no library
changed. This is a measurement, not a plan.

On a low-end Android phone a guest navigation costs 0.3–1.1 s. On "back" to a 16-row list window,
about 570 ms is Compose itself in QuickJS and about 390 ms is recording the changes
([THREE_WAY_BENCHMARK.md](THREE_WAY_BENCHMARK.md), "Inherent vs fixable"). Nobody had looked inside
those two numbers. This document does.

## Answers in brief

- **The harness reproduces the phone in ratio.** Zipline's own QuickJS on this Mac runs "back" in
  80 ms (16 rows) and 50 ms (10 rows). The A12 measured 964 and 636 ms for the same work, so the
  phone is about **12×** slower, and the ratio holds across window sizes and steps. The widget and
  property counts match the phone's.
- **GC is not the problem.** Raising QuickJS's GC threshold from 256 KiB to 4, 16, 64 MiB or 1 GiB
  changed nothing (79.2 → 79.1–79.7 ms). One full GC of this heap costs about 2 ms.
- **Recording is about 45% of "back"** in QuickJS (the phone's timers said 41%).
- **Emulated `Long` is the biggest single cost we found: about 38% of the guest's work** in the
  interpreter profile. It has two sources:
  - kotlinx.serialization re-parses every JSON number with 64-bit `Long` arithmetic (24%);
  - androidx.collection's hash maps, which Compose's runtime uses, keep their control bytes in a
    `LongArray` (13%).
- **Cheap code fixes, measured in QuickJS on "back":**
  - a direct property encoder saves 15–17%;
  - modifier encoding, bounded with a cache, saves a further 12–14%;
  - looking up serializers once per class instead of per widget saves about 6%;
  - a 10-row window instead of 16 rows saves 34% (the phone measured 32%).
- **The engine itself:**
  - Zipline 1.28 runs **QuickJS 2021-03-27**.
  - Android builds it at `-Os` with no LTO. The JVM build measured here is `ReleaseSmall`, so it is
    comparable.
  - Its bundle compiler rejects BigInt, so Kotlin's `Long`-as-BigInt cannot be used today. That
    option cut "back" by 35% in jitless V8.
  - A newer QuickJS is claimed to be 1.3–1.5× faster, but it changes the bytecode format and needs a
    Zipline fork.

## What was built

A prototype module, `keliver-compose-gatea-profile`. It is in the build only with
`-Pkeliver.guestProfile`, never published, and not in CI's graph. It is one Kotlin/JS executable
that composes the three-way benchmark's screens headlessly. The screens (`ThreewayScreens.kt`) and
data are copied in unchanged from `sample/threeway-guest`.

| Part | Real, copied or stubbed |
|---|---|
| JetBrains Compose runtime (Recomposer, slot table, snapshots), keliver-compose's `RedwoodComposition` and node applier | **real** |
| The generated Gate A guest API (`Text`, `Row`, `LazyColumn`, …), its generated protocol widgets, the lazy window (`LazyDsl.kt`), the mirror types and their codecs, kotlinx.serialization | **real** (project dependencies) |
| The protocol recorder | **copy**: `ProfileAdapter.kt` is treehouse-guest's `FastGuestProtocolAdapter` (internal), plus counters, the encoding variants and click tracking |
| Zipline's bridge | **stub**: a batch is `JSON.stringify`'d (timed separately, excluded from step times) and dropped |
| Frame clock, coroutine dispatcher | **stub**: a `BroadcastFrameClock` ticked by the driver, and a queue the driver drains |
| Data | **stub**: 20 items in memory. No request, no parsing |
| Clicks | **real path**: a protocol `Event` (tag 1000, handle 0) on the widget that carries the clickable, as a host sends it |

A step runs from the click to the batch. The driver does 3 warm-up pairs, then 30 "open a detail,
go back" pairs (rows 0–4 in turn), and reports medians.

Per step it records: time, widgets created, property and modifier changes, modifier elements,
structural changes, and batch bytes. It also records a hash of the batch, which proves that every
encoding variant sends byte-identical batches.

The counts match the phone's split. "Back" at 16 rows creates 145 widgets and 145 modifiers, as on
the phone. It records 1,242 properties against the phone's 1,111, because the phone's counter left
out Boolean and UInt properties.

The three ways to run it:

| Run | How | What it is good for |
|---|---|---|
| (a) Node, `--jitless --cpu-prof` | `:keliver-compose-gatea-profile:jsNodeProductionRun` (the Kotlin plugin's Node) | The call-tree shape. V8's interpreter is not QuickJS, so the shares are indicative |
| (b) Zipline's QuickJS on the JVM | `profileQuickJs`: loads the production `.zipline` bytecode the Zipline plugin compiles, module by module, through `Zipline.create` and `loadJsModule` | The phone's engine (QuickJS 2021-03-27, Zipline 1.28.0) on a faster CPU. Ratios matter, not milliseconds |
| (c) Upstream QuickJS, Hermes CLI | **not done** | This session's shell could not run downloaded binaries. Section "The engine's core" uses published numbers instead |

## The profile (where the time goes)

**Setup.** Node `--jitless --cpu-prof` (100 µs sampling), 200 open/back pairs, 16-row window, as
shipped. 80,275 samples. Shares are of the guest's own work. The harness's batch stringify and
hashing (10%) and Node's loader (2%) are removed first.

Every sample is counted once, by the first rule that matches:
1. recording;
2. serializer lookup;
3. otherwise, the first module up the stack that is not the Kotlin stdlib.

Stdlib time therefore belongs to its caller.

| Where | Share of guest work | Notes |
|---|---|---|
| Compose runtime (slot table, groups, recomposer, applier) | **52%** | includes androidx.collection |
| … of which emulated `Long` inside androidx.collection | 13% | `IntObjectMap.get`, `MutableIntSet.remove`: the hash tables' metadata is a `LongArray`, which Kotlin/JS emulates |
| Recording: property values | **23%** | `appendPropertyChange` → `encodeToDynamic` |
| Recording: modifiers | **14%** | `appendModifierChange` → `encodeToDynamic` per element |
| Recording: structure (create/add/remove), emit | 0.3% | |
| … of recording: kotlinx's `JsonPrimitive` serializer re-parsing number strings with `Long` | **24%** (of all guest work) | Every mirror codec builds a `JsonPrimitive`, whose number is a string. `encodeToDynamic` then calls `toLongOrNull()` on it, digit by digit in emulated 64-bit arithmetic |
| Serializer lookups in widget constructors | 5% | per instance, uncached on JS |
| Generated stubs, protocol widgets, mirror types, other Keliver runtime | 5% | `splitModifierEvents` is 0.1% |
| GC (V8's), coroutines | 0.6% | |

Overlapping views, inclusive, as a share of all busy time:
- emulated `Long` 33%;
- everything under the lazy list's window (the rows) 29%;
- `encodeToDynamic` 32%;
- the mirror codecs' `toJson` 3.6%.

**The phone's two numbers, read through this:**
- The **390 ms of recording** is mostly kotlinx.serialization doing generic work twice, and above
  all re-parsing numbers through emulated `Long`.
- The **570 ms of "Compose itself"** is mostly the Compose runtime proper. About a quarter of it is
  emulated 64-bit arithmetic inside androidx.collection, and a tenth is serializer lookups and
  Keliver's stubs.

Caveat: these shares come from V8's interpreter on a desktop CPU. QuickJS's per-operation costs
differ. The QuickJS differential below puts recording at 45–47% of "back" (here 37% plus 5% of
lookups), so the two views agree within about 10 points.

## The GC experiment

Zipline's QuickJS on the JVM, as shipped. Each cell is the median of 30 iterations after 3 warm-up
pairs, in ms. The threshold is set with `zipline.quickJs.gcThreshold` before the bundle loads.

| Window, step | 256 KiB (Zipline's default) | 4 MiB | 16 MiB | 64 MiB | 1 GiB |
|---|---|---|---|---|---|
| 16 rows, back | 79.2 | 79.7 | 79.5 | 79.1 | 79.7 |
| 16 rows, open | 24.0 | 23.9 | 24.0 | 24.0 | 24.1 |
| 10 rows, back | 50.1 | — | 51.1 | — | — |

- One explicit full GC of this heap (6–9 MB, 13–22 k objects) costs **1.4–2.9 ms**.
- "Back" grows the heap by about 0.4–0.7 MB.
- **GC share: under 3%** (inferred: at most one full GC per "back", 2.2 of 80 ms; the threshold
  sweep shows no difference at all).

Why QuickJS does not suffer here (verified in its source; see the engine section):
- Reference counting frees most garbage at once.
- The cycle collector runs only when malloc'd bytes pass the threshold.
- After each collection the threshold re-bases to 1.5× the live heap, so 256 KiB applies only to
  the first collection.

A host can set the threshold on both platforms today, from Keliver's `EventListener.ziplineCreated`
(`zipline.quickJs.gcThreshold`, `@EngineApi`), before any code loads. For navigation there is
nothing to gain. Cold start was not measured.

## The suspects, measured

QuickJS on the JVM, as above. Medians in ms; % is against "as shipped" in the same run. Every
variant's batches are byte-identical to as shipped (the hash check).

| Variant (what it does) | 16 rows: back | 16 rows: open | 10 rows: back | 10 rows: open |
|---|---|---|---|---|
| As shipped | 80.9 | 24.4 | 50.1 | 19.5 |
| **Direct property encoding**: String, Int, Float and Double pushed as they are; a mirror type's `JsonCodec.toJson` element turned into JS values by a small walk instead of a second `encodeToDynamic` pass | 67.5 (−17%) | 21.7 (−11%) | 42.6 (−15%) | 17.2 (−12%) |
| … plus **modifier elements cached by equality** (a bound for a direct modifier encoder, not a design) | 57.5 (−29%) | 20.7 (−15%) | 35.6 (−29%) | 15.7 (−19%) |
| **Serializers once per class** (codegen patch: a companion object instead of a per-instance lookup), as shipped otherwise | 74.7 vs 79.2 (−6%) | 23.2 vs 24.0 (−3%) | — | — |
| **10-row window instead of 16** (`lazy-window-10.patch`) | 79.2 → 52.6 (−34%; the phone measured −32%) | 24.0 → 20.5 (−15%; the phone −23%) | | |
| No recording at all (values not encoded; the floor for any encoder) | 43.1 (−47%) | 18.2 (−25%) | 27.5 (−45%) | 13.7 (−30%) |

- **Direct property encoding** (suspect 1, properties): removes about a third of the recording.
  - It is cheap because it skips `JsonPrimitive`'s string round trip and the `Long` parse.
  - A generator-emitted encoder that never builds a `JsonElement` should do a little better. Not
    built.
- **Modifiers** (suspect 1, modifiers): cost as much again.
  - The cache bound says a direct modifier encoder would save another 12–14% of "back".
  - Its generated serializers write Dp and the other mirror types through the same `JsonPrimitive`
    path.
- **Serializers once per class** (suspect 3): about 4.5 ms on "back" at 16 rows. The phone's
  equivalent is about 50 ms (×12, inferred).
- **Together** (direct properties, direct modifiers by the cache bound, serializers once), at 10
  rows:
  - QuickJS: "back" goes from 50 to about 33 ms, −35%.
  - Times 12, the phone's "frame → composed" would go from 636 to about 400 ms (inferred, not
    measured).
- **`Long` as BigInt** (Kotlin's `-Xes-long-as-bigint`, a compiler flag; not a suspect in the list):
  - Jitless V8, 16 rows: open 12.1 → 6.9 ms, back 42.2 → 27.3 ms (−35%), batches identical.
  - **It cannot run on Zipline 1.28.** Its bundle compiler (QuickJS 2021-03-27, built without
    `CONFIG_BIGNUM`) fails with `invalid number literal` (verified). Whether a newer QuickJS's
    BigInt is fast enough to pay is unknown.
- **Not done:** suspect 9, stable row content in the lazy window. "Back" composes every row new, so
  it cannot gain there. It would gain on a refresh or scroll.
  - The harness has a `reload` step (off by default).
  - In the harness, reload re-creates all 81 row widgets of a 10-row window. The phone's split for
    the same step recorded one property. This is not explained (see Unverified), so no number from
    it is used.
- **Not done:** emulated `Long` in Color and TextUnit compares (suspect 5) on its own. The profile
  puts mirror types' own `Long` work at about 0.1%. The `Long` that costs is kotlinx's number parse
  and androidx.collection.

## The engine's core

Can anything be done in the engine itself? Sources are below. "Verified" means read in a source;
"inferred" means our estimate.

**What runs today (verified):**
- **Version.** Zipline 1.28.0 vendors **Bellard's QuickJS 2021-03-27** (`zipline/native/quickjs/VERSION`
  at the tag), with local patches (no-eval context, FinalizationRegistry, global GC, a PR #196
  fix). It is not QuickJS-NG. Zipline has not updated QuickJS since.
- **Android.** Release build: `-Os -g0 -fomit-frame-pointer -DNDEBUG -fvisibility=hidden`,
  `CMAKE_BUILD_TYPE=MinSizeRel`, `-fstrict-aliasing`. No LTO, no PGO
  (`zipline-android-ndk/build.gradle.kts` and `CMakeLists.txt`).
- **iOS.** Built with touchlab's cklib, whose default flags include `-O3` (cklib `main`; the
  version Zipline pins was not checked).
- **The JVM host measured here.** `zipline/build.zig` builds at `ReleaseSmall`, so it is
  size-optimised like Android.
- **In `quickjs.c`:**
  - `DIRECT_DISPATCH 1` (computed goto) on every target except Emscripten;
  - `CONFIG_BIGNUM` off (no BigInt);
  - no `DUMP_*` or `FORCE_GC_AT_MALLOC` switches;
  - an interrupt poll every 10,000 operations (negligible).
- **The QuickJs API.**
  - `gcThreshold`, `memoryLimit`, `maxStackSize`, `memoryUsage` and `gc()` exist on JNI (Android,
    JVM) and native (iOS) actuals.
  - `Zipline.create` sets only `maxStackSize` (6 MiB). The GC threshold stays at QuickJS's 256 KiB
    (confirmed here: it reads back as 262,144).
  - Keliver's host can reach them in `EventListener.ziplineCreated` (`keliver-treehouse-host`,
    `EventListener.kt`).
- **Bytecode is tied to the engine build.** The reader rejects other versions (`invalid version`,
  seen here when loading a `.zipline` file without unwrapping it). Zipline's maintainers say an
  upgrade needs the bundles compiled for both engines and served by host version (zipline #1715,
  #1684, both open).

| # | Option | Expected gain on this workload | Cost (engineer-weeks, inferred) | Risk | Both platforms? |
|---|---|---|---|---|---|
| 1 | **Code shape, not engine**: direct property and modifier encoders from the generator; serializers once per class; window from the viewport | measured in QuickJS: "back" −35% at 10 rows (−29% encoders, −6% serializers); −34% more from 16→10 rows | 2–4 | Low. The wire format can stay byte-identical (shown here) | Yes |
| 2 | **Remove emulated `Long` from the hot paths**: no `JsonPrimitive` numbers on the encode path (part of 1); a JS-friendly hash map under Compose's runtime, either a patched androidx.collection or one with Int metadata on JS | up to about 13% of Compose's share (androidx.collection's part) on top of 1 | 2–6 (inferred; patching androidx.collection means carrying a fork of it) | Medium | Yes |
| 3 | **GC threshold, idle GC** | measured: **none** for navigation (< 3%). Cold start unmeasured | < 0.5 | Low | Yes (API exists) |
| 4 | **Rebuild Zipline's QuickJS for Android at `-O2`/`-O3` + LTO**, optionally with Bellard's newer small-block allocator | 5–25% from flags (inferred; no published QuickJS flag numbers); about +10% from the allocator (Bellard: 11% on bench-v8) | 1–3 (fork Zipline's native module and AAR; same bytecode) | Low–medium: we maintain a fork | Android (iOS is already `-O3`; the allocator helps both) |
| 5 | **Newer QuickJS (Bellard 2026-06)**, which also brings BigInt so `Long` can be a BigInt | 1.3–1.5× on bench-v8 (inferred from published scores; 2021 vs 2025 never measured side by side). BigInt: −35% in V8, unknown in QuickJS | 4–8 (fork Zipline, port `zipline-bytecode`, re-apply patches, serve bundles by engine version, re-sign) | Medium | Yes |
| 6 | **Hermes** (bytecode interpreter; a JSI port of Zipline's bridge) | about 1.0–1.5× against today's QuickJS, about equal to option 5. Published benchmarks are mixed (2026: +16% on Linux, −30% on macOS arm64) | 8–16 | High: bytecode tied to engine version, C++ interop from Kotlin/Native, binary size, leaving Zipline upstream | Yes |
| 7 | **Kotlin/Wasm (WasmGC) on an interpreter** (wasmtime's Pulley supports GC and exception handling; WAMR's GC is incomplete and fails Kotlin's output) | unknown; maybe 2–5× (inferred, unproven for GC-heavy code) | 20–40+ (Compose publishes no wasm-wasi runtime; new bridge; Pulley host on both platforms) | Very high. Apple 4.7 names only HTML5/JS mini apps, so downloaded Wasm is greyer than JS | In principle |

Ranked by gain per week: 1, then 2 and 4, then 5. Options 6 and 7 do not pay for this workload
given 5.

**Store rules (verified):**
- Play's Device and Network Abuse policy exempts code that runs in a VM or interpreter with
  indirect access to Android APIs.
- Apple's guideline 2.5.2 forbids downloaded code that changes the app's features. Guideline 4.7
  allows HTML5/JS mini apps.
- This applies to any interpreter, today's QuickJS included.

**Sources.** Zipline at tag 1.28.0: `zipline/native/quickjs/VERSION`,
`zipline-android-ndk/build.gradle.kts`, `zipline/build.gradle.kts`, `zipline/build.zig`,
`zipline/src/hostMain/.../QuickJs.kt`, `Zipline.kt`. Issues
[#1684](https://github.com/cashapp/zipline/issues/1684) and
[#1715](https://github.com/cashapp/zipline/issues/1715).
[bellard.org/quickjs](https://bellard.org/quickjs/) and its Changelog.
[quickjs-ng#1172](https://github.com/quickjs-ng/quickjs/issues/1172).
[js-engine-benchmark](https://github.com/ahaoboy/js-engine-benchmark).
[Hermes features](https://github.com/facebook/hermes/blob/main/doc/Features.md).
[Kotlin/Wasm configuration](https://kotlinlang.org/docs/wasm-configuration.html).
[WAMR build doc](https://github.com/bytecodealliance/wasm-micro-runtime/blob/main/doc/build_wamr.md)
and [#3599](https://github.com/bytecodealliance/wasm-micro-runtime/issues/3599).
[wasmtime tiers](https://docs.wasmtime.dev/stability-tiers.html).
[App Store guidelines](https://developer.apple.com/app-store/review/guidelines/).
[Play policy](https://support.google.com/googleplay/android-developer/answer/16559646).

## How to rerun it

```
# from the repository root, JDK 17 (as every Gradle command here)
# (b) Zipline's QuickJS: modes kotlinx (as shipped), direct, modcache, none; GC thresholds in MiB
./gradlew -Pkeliver.guestProfile :keliver-compose-gatea-profile:profileQuickJs \
  "-PprofileArgs=modes=kotlinx+direct+modcache+none,gc=default+4+16+64,iterations=30,warmup=3,label=run1" \
  -PprofileOut=<scratch>/out/quickjs            # prints one "QJS …" line per run; JSON lines in <label>.jsonl
# (a) Node: timings (add -PprofileCpu for a .cpuprofile in <profileOut>/cpu), then the summary
./gradlew -Pkeliver.guestProfile :keliver-compose-gatea-profile:jsNodeProductionRun -PprofileJitless \
  "-PprofileArgs=modes=kotlinx,iterations=200,warmup=3" -PprofileCpu -PprofileOut=<scratch>/out/cpu
./gradlew -Pkeliver.guestProfile :keliver-compose-gatea-profile:profileSummary -PcpuProfile=<file.cpuprofile>
# variants that need a library patch (apply, run, then `git checkout` the library):
#   10-row window:        sample/threeway-guest/experiments/lazy-window-10.patch
#   serializers once:     keliver-compose-gatea-profile/experiments/serializers-once.patch (codegen)
#   Long as BigInt:       -PprofileBigInt (Node only; Zipline's compiler rejects it)
```

## Unverified

- **The ×12 phone ratio** comes from two window sizes and two steps of one screen pair. Another
  screen could differ.
- **No phone run of any variant.** The phone estimates are QuickJS-on-Mac numbers × 12.
- **Interpreter shares.** The shares come from V8's interpreter (Ignition, `--jitless`), not
  QuickJS. They agree with QuickJS's differential within about 10 points, and no QuickJS sampling
  profiler was used.
- **iOS.** Not measured. Its QuickJS is built at `-O3` (cklib default, pinned version not checked),
  so ratios may differ from the `-Os` Android build and the `ReleaseSmall` JVM build measured here.
- **The modifier saving** is a bound from caching encoded elements, not from a direct encoder.
- **Reload.** In the harness, a reload with equal data re-creates every row. The phone's split for
  the same step recorded one property. Unexplained; a harness difference (the request does not
  suspend here) is the likely cause.
- **The engine options' gains** are not measured here: compiler flags, a newer QuickJS, Hermes,
  Wasm. They come from published benchmarks unlike this workload, and the network/shell here could
  not run other engines (part 2c skipped).
- **BigInt** on a newer QuickJS: its speed for 64-bit values is unknown.
- **Cold start's GC share** was not measured.
