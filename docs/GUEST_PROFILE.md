# Guest profile: where a guest navigation's time goes

Measured 2026-11-16 on a Mac (Apple silicon), without a device. Prototype code only; no library
changed. This is a measurement, not a plan.

On a low-end Android phone a guest navigation costs 0.3–1.1 s. On "back" to a 16-row list window,
about 570 ms is Compose itself in QuickJS and about 390 ms is recording the changes
([THREE_WAY_BENCHMARK.md](THREE_WAY_BENCHMARK.md), "Inherent vs fixable"). Nobody had looked inside
those two numbers. This document does.

## Answers in brief

- **The harness reproduces the phone in ratio, for "back" better than for "open".** Zipline's own
  QuickJS on this Mac runs "back" in 78 ms (16 rows) and 51 ms (10 rows). The A12 measured 964 and
  636 ms (frame → composed) for the same work, so for "back" the phone is **12–13×** slower at both
  window sizes. For "open" it is **13–15×** (353 / 23.7 and 253 / 19.7 ms), and the window change
  moves "open" less here than on the phone (see the table). The widget and property counts match the
  phone's.
- **GC is not the problem.** Raising QuickJS's GC threshold from 256 KiB to 4, 16, 64 MiB or 1 GiB
  changed nothing (79.2 → 79.1–79.7 ms). One full GC of this heap costs 1.2–3.1 ms. This is a null
  result for this harness's heap; the phone's live heap was not measured.
- **Recording is 45–47% of "back"** in QuickJS (the phone's timers said 41%).
- **Emulated `Long` is the biggest single cost we found: about 38% of the guest's work in V8's
  interpreter (`--jitless`; not measured in QuickJS).** It has two sources:
  - kotlinx.serialization re-parses every JSON number with 64-bit `Long` arithmetic (24%);
  - androidx.collection's hash maps, which Compose's runtime uses, keep their control bytes in a
    `LongArray` (12%; 13% counting Compose runtime's own `Long`).
- **Cheap code fixes, in QuickJS on "back":**
  - a direct property encoder saves 15–17% (measured);
  - modifier encoding saves a further 13–16%, measured with a cache of encoded elements as a
    stand-in for a direct modifier encoder (not a design, and not a strict bound either way);
  - looking up serializers once per class instead of per widget saves 3–5 ms, 5–7% (measured in a
    separate build, so against a baseline from a different run);
  - the three together, run together once: **−36% at both 10 and 16 rows** (50.9 → 32.4 ms and
    78.2 → 50.2 ms), with the same two caveats (the modifier part is the cache stand-in; separate
    build). The earlier sum of separate measurements estimated −35%;
  - a 10-row window instead of 16 rows saves 35% (the phone measured 34%, frame → composed).
- **The engine itself:**
  - Zipline 1.28 runs **QuickJS 2021-03-27**.
  - Android builds it at `-Os` with no LTO. The JVM build measured here is `ReleaseSmall`, so it is
    comparable.
  - Its bundle compiler rejects BigInt, so Kotlin's `Long`-as-BigInt cannot be used today. That
    option cut "back" by 33% in jitless V8 (not measurable in QuickJS).
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
| Zipline's bridge | **stub**: a batch is `JSON.stringify`'d and hashed, then dropped. Node's step times exclude the stringify; QuickJS's tables do not (see "The suspects, measured") |
| Frame clock, coroutine dispatcher | **stub**: a `BroadcastFrameClock` ticked by the driver, and a queue the driver drains |
| Data | **stub**: 20 items in memory. No request, no parsing |
| Clicks | **real path**: a protocol `Event` (tag 1000, handle 0) on the widget that carries the clickable, as a host sends it |

A step runs from the click to the batch. The driver does 3 warm-up pairs, then 30 "open a detail,
go back" pairs (rows 0–4 in turn), and reports medians.

Per step it records: time, widgets created, property and modifier changes, modifier elements,
structural changes, and batch bytes. It also records a 32-bit hash of the step's batches. The
QuickJS harness compares every step's hash and byte count (first composition, warm-up included)
across the encoding modes of one run, and fails if any step differs. In every run below, all 67
steps matched. That is strong evidence the variants send the same bytes, but a hash, not a byte
comparison. The no-recording mode sends structure only, so it is not compared.

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
shipped. 80,275 samples. Shares are of the guest's own work (87.5% of the samples). Removed first:
the harness's batch stringify and hashing (9.0%), its driver (1.4%), and Node's loader and
`(program)` (2.1%).

Every sample is counted once, by the first rule that matches:
1. recording;
2. serializer lookup;
3. otherwise, the first module up the stack that is not the Kotlin stdlib.

Stdlib time therefore belongs to its caller.

| Where | Share of guest work | Notes |
|---|---|---|
| Compose runtime (slot table, groups, recomposer, applier) | **52%** | includes androidx.collection |
| … of which emulated `Long` inside androidx.collection | 12% (13% with Compose runtime's own `Long`) | `IntObjectMap.get`, `MutableIntSet.remove`: the hash tables' metadata is a `LongArray`, which Kotlin/JS emulates |
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
- The **570 ms of "Compose itself"** is mostly the Compose runtime proper. Of the non-recording
  time (63% of guest work), about a fifth is emulated 64-bit arithmetic: 19% inside
  androidx.collection, 21% counting Compose runtime's own `Long`. About a sixth (16%) is serializer lookups and
  Keliver's stubs.

Caveat: these shares come from V8's interpreter on a desktop CPU, and none of them was reproduced
in QuickJS. QuickJS's per-operation costs differ. The partition above puts recording at 37% (the
lookups are separate, and the no-recording mode below still does them, so 37% is the like-for-like
figure). Measured as a differential instead (as shipped minus no recording, "back", 16 rows),
recording is 44% in jitless V8 (41.6 → 23.1 ms) and 45–47% in QuickJS. So the two engines agree
when measured the same way. The 7-point gap between V8's partition and V8's differential is not
explained.

## The GC experiment

Zipline's QuickJS on the JVM, as shipped. Each cell is the median of 30 iterations after 3 warm-up
pairs, in ms. The threshold is set with `zipline.quickJs.gcThreshold` before the bundle loads.

| Window, step | 256 KiB (Zipline's default) | 4 MiB | 16 MiB | 64 MiB | 1 GiB |
|---|---|---|---|---|---|
| 16 rows, back | 79.2 | 79.7 | 79.5 | 79.1 | 79.7 |
| 16 rows, open | 24.0 | 23.9 | 24.0 | 24.0 | 24.1 |
| 10 rows, back | 50.1 | — | 51.1 | — | — |

This table is from the first round, before the class-name check left the measured path (see "The
suspects, measured"). It is not rerun: what it compares, GC on against GC off, does not depend on
that check.

- Before the explicit GC the heap holds 5.9–9.4 MB and 17–41 k objects; after it, 5.1 MB and
  13.6–13.7 k objects. The GC itself takes **1.2–3.1 ms** (both rounds' files).
- "Back" grows the heap by about 0.4–0.7 MB.
- At 16 MiB and above no cycle collection fired during the measured loop, so the sweep really
  compares GC on with GC off.
- **GC share: under 4%** (inferred: at most one full GC per "back", up to 3.1 of about 80 ms; the
  threshold sweep shows no difference at all).
- This is a null result **for this heap**: about 5 MB live, with no Zipline/treehouse runtime and
  no app services. The phone's live heap was not measured.

Why QuickJS does not suffer here (verified in its source; see the engine section):
- Reference counting frees most garbage at once.
- The cycle collector runs only when malloc'd bytes pass the threshold.
- After each collection the threshold re-bases to 1.5× the live heap, so 256 KiB applies only to
  the first collection.

A host can set the threshold on both platforms today, from Keliver's `EventListener.ziplineCreated`
(`zipline.quickJs.gcThreshold`, `@EngineApi`), before any code loads. For navigation there is
nothing to gain. Cold start was not measured.

## The suspects, measured

QuickJS on the JVM, as above. Medians of 30 steps, in ms (review round 1's rerun). % is against
"as shipped" in the same run, except where a row says it needs a separate build.

The times are JVM wall-clock around each call. They include the harness's `JSON.stringify` and
hash: 2–3 ms on the recording modes, 0–1 ms with no recording. The in-guest times that exclude
them (`openGuestMedianMs`, `backGuestMedianMs` in the `.jsonl`) step in whole milliseconds,
because QuickJS has only `Date.now()`. They move the percentages by 1–2 points at most.

Batches: in every run, every step's hash and byte count matched across the recording modes (the
automatic check described in "What was built").

| Variant (what it does) | 16 rows: back | 16 rows: open | 10 rows: back | 10 rows: open |
|---|---|---|---|---|
| As shipped | 78.2 | 23.7 | 50.9 | 19.7 |
| **Direct property encoding**: String, Int, Float and Double pushed as they are; a mirror type's `JsonCodec.toJson` element turned into JS values by a small walk instead of a second `encodeToDynamic` pass | 66.6 (−15%) | 21.5 (−9%) | 42.4 (−17%) | 17.2 (−13%) |
| … plus **modifier elements cached by equality** (a stand-in for a direct modifier encoder, not a design) | 54.3 (−31%) | 19.6 (−17%) | 36.0 (−29%) | 15.8 (−20%) |
| **Serializers once per class** (codegen patch: a companion object instead of a per-instance lookup), as shipped otherwise. *Separate build; against the as-shipped row above* | 74.1 (−5%) | 22.9 (−3%) | 47.6 (−7%) | 18.8 (−5%) |
| **All three together**: direct properties, modifier cache, serializers once. *Separate build; against the as-shipped row above* | **50.2 (−36%)** | 18.9 (−20%) | **32.4 (−36%)** | 14.7 (−25%) |
| **10-row window instead of 16** (`lazy-window-10.patch`) | 78.2 → 50.9 (−35%; the phone, frame → composed, −34%) | 23.7 → 19.7 (−17%; the phone −28%: **not reproduced**) | | |
| No recording at all (values not encoded; the floor for any encoder) | 41.6 (−47%) | 17.6 (−25%) | 27.9 (−45%) | 14.1 (−29%) |

The first round (before review) measured as shipped at 80.9 / 24.4 / 50.1 / 19.5 ms, with a
class-name check on every modifier element that the real recorder does not have. That check is now
two interface checks. The tables moved by no more than run-to-run noise (about 2 ms): as shipped
−2.7 ms at 16 rows, +0.8 ms at 10 rows.

- **Direct property encoding** (suspect 1, properties): removes about a third of the recording.
  - It is cheap because it skips `JsonPrimitive`'s string round trip and the `Long` parse.
  - A generator-emitted encoder that never builds a `JsonElement` should do a little better. Not
    built.
- **Modifiers** (suspect 1, modifiers): cost as much again.
  - The cache stand-in suggests a direct modifier encoder would save another 13–16% of "back".
    It is an estimate, and not a strict bound in either direction: a cache hit still pays
    `hashCode`/`equals` per element, and a real encoder still builds each JS object.
  - Its generated serializers write Dp and the other mirror types through the same `JsonPrimitive`
    path.
- **Serializers once per class** (suspect 3): 3.3–4.7 ms on "back". The effect is the same in every
  mode and both window sizes measured across two rounds: 4.0 / 4.5 / 4.7 ms in the first round's
  modes at 16 rows, and 4.1 (16 rows) and 3.3–3.6 ms (10 rows) in this round. Each comparison is
  against a different build, and run-to-run noise is about 1.7 ms. The phone's equivalent would be
  about 40–60 ms (×12–13, inferred). The patch is **not a drop-in fix**: it calls the global
  `serializer()` and drops `guestAdapter.json.serializersModule`, so contextual serializers would
  break. A real version would cache per `Json` instance and class.
- **Together** (direct properties, the modifier cache stand-in, serializers once), **run together
  once**:
  - QuickJS, 10 rows: "back" 50.9 → 32.4 ms, −36% (16 rows: 78.2 → 50.2 ms, −36%). Against a
    baseline from a different build. The modifier third is the cache stand-in, not an encoder.
  - Before this run, the doc summed separate measurements to estimate −35% (−15% direct properties
    + −14% modifier cache, both at 10 rows, + about −6% serializers measured separately at 16 rows).
    The combined run agrees with that estimate.
  - Scaled by the "back" ratio (12–13×), the phone's frame → composed would go from 636 to about
    400 ms. That is inferred, not measured on a phone.
- **`Long` as BigInt** (Kotlin's `-Xes-long-as-bigint`, a compiler flag; not a suspect in the list):
  - Jitless V8, 16 rows, rerun in review round 1 with 100 pairs: open 12.1 → 7.3 ms, back 41.6 →
    27.9 ms (−33%). The first round measured 12.1 → 6.9 and 42.2 → 27.3 ms (−35%); that output was
    not kept. The last "back" batch hashes the same (and has the same 96,635 bytes) with and
    without the flag, compared by hand. The batches of the other steps were not compared (separate
    builds).
  - **It cannot run on Zipline 1.28.** Its bundle compiler (QuickJS 2021-03-27, built without
    `CONFIG_BIGNUM`) fails with `QuickJsException: invalid number literal`. Rerun and kept in
    review round 1. Whether a newer QuickJS's BigInt is fast enough to pay is unknown.
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
  fix). It is not QuickJS-NG. Zipline's main branch after 1.28.0 was not checked for a newer
  engine.
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

How each gain was arrived at:
- **measured**: in this harness;
- **sourced**: a published number, for other workloads;
- **estimate**: our guess.

Every cost is an estimate.

| # | Option | Expected gain on this workload | Basis of the gain | Cost (engineer-weeks, estimate) | Risk | Both platforms? |
|---|---|---|---|---|---|---|
| 1 | **Code shape, not engine**: direct property and modifier encoders from the generator; serializers once per class; window from the viewport | "back" −36% at 10 and 16 rows with the three fixes run together, the modifier encoder stood in for by a cache; −35% more from 16→10 rows | **measured** in QuickJS (combined run against a different build's baseline; the modifier part is a stand-in, not a design) | 2–4 | Low. The wire format can stay byte-identical (hash-checked here) | Yes |
| 2 | **Remove emulated `Long` from the hot paths**: no `JsonPrimitive` numbers on the encode path (part of 1); a JS-friendly hash map under Compose's runtime, either a patched androidx.collection or one with Int metadata on JS | up to about 12–13% of guest work in V8's interpreter (androidx.collection's part, with or without Compose runtime's own `Long`) on top of 1 | **estimate** from V8's profile; not measured in QuickJS | 2–6 (patching androidx.collection means carrying a fork of it) | Medium | Yes |
| 3 | **GC threshold, idle GC** | **none** for navigation (< 4%). Cold start unmeasured | **measured** (this harness's heap only) | < 0.5 | Low | Yes (API exists) |
| 4 | **Rebuild Zipline's QuickJS for Android at `-O2`/`-O3` + LTO**, optionally with Bellard's newer small-block allocator | 5–25% from flags; about +10% from the allocator | flags: **estimate** (no published QuickJS flag numbers); allocator: **sourced** (Bellard: 11% on bench-v8) | 1–3 (fork Zipline's native module and AAR; same bytecode) | Low–medium: we maintain a fork | Android (iOS is built with cklib's default `-O3`, but the cklib version Zipline pins was not checked; the allocator helps both) |
| 5 | **Newer QuickJS (Bellard 2026-06)**, which also brings BigInt so `Long` can be a BigInt | 1.3–1.5× on bench-v8. BigInt: −33% in jitless V8, unknown in QuickJS | **sourced** published scores, combined by us (2021 vs 2025 never measured side by side); BigInt **measured** in V8 only | 4–8 (fork Zipline, port `zipline-bytecode`, re-apply patches, serve bundles by engine version, re-sign) | Medium | Yes |
| 6 | **Hermes** (bytecode interpreter; a JSI port of Zipline's bridge) | about 1.0–1.5× against today's QuickJS, about equal to option 5 | **sourced**, mixed (2026: +16% on Linux, −30% on macOS arm64) | 8–16 | High: bytecode tied to engine version, C++ interop from Kotlin/Native, binary size, leaving Zipline upstream | Yes |
| 7 | **Kotlin/Wasm (WasmGC) on an interpreter** (wasmtime's Pulley supports GC and exception handling; WAMR's GC is incomplete and fails Kotlin's output) | unknown; maybe 2–5× | **estimate**, unproven for GC-heavy code | 20–40+ (Compose publishes no wasm-wasi runtime; new bridge; Pulley host on both platforms) | Very high. Apple 4.7 names only HTML5/JS mini apps, so downloaded Wasm is greyer than JS | In principle |

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
  -PprofileOut=<scratch>/out/quickjs            # prints one "QJS …" line per run and a "QJS batches" line;
                                                # JSON lines in <label>.jsonl; fails if any step's batch differs
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

- **The phone ratio** comes from two window sizes and two steps of one screen pair: 12–13× for
  "back", 13–15× for "open". Another screen could differ.
- **"Open" does not track the phone.** The 16→10-row window cut the phone's "open" (frame →
  composed) by 28%, but this harness's by 17% (first round: 14%). "Back" matches (−34% and −35%).
  Not explained, so nothing about "open" should be scaled to the phone.
- **No phone run of any variant.** The phone estimates are QuickJS-on-Mac numbers × 12–13.
- **Interpreter shares.** The shares come from V8's interpreter (Ignition, `--jitless`), not
  QuickJS, and no QuickJS sampling profiler was used. **The `Long` shares (38%, 24%, 12–13%) were
  not reproduced in QuickJS.** The direct-encoder savings are consistent with them, but they don't
  isolate `Long`. Measured the same way, as a differential, recording is 44% in V8 and 45–47% in
  QuickJS.
- **GC.** A null result for this harness's heap (about 5 MB live, no Zipline/treehouse runtime or
  app services). The phone's live heap was not measured.
- **iOS.** Not measured. Its QuickJS is built at `-O3` (cklib default, pinned version not checked),
  so ratios may differ from the `-Os` Android build and the `ReleaseSmall` JVM build measured here.
- **The modifier saving** comes from a cache of encoded elements standing in for a direct encoder.
  It is not a design, and not a strict bound in either direction.
- **The combined and serializers-once runs** need a separate build (a codegen patch), so their
  baseline comes from a different run. Run-to-run noise is about 1.7 ms. The serializers-once patch
  drops `serializersModule` and is not a drop-in fix.
- **Batch identity** is checked by a 32-bit hash and the byte count per step, not by comparing
  bytes.
- **Raw output.** Review round 1's QuickJS runs, its Node jitless runs (`Long` and BigInt), the
  BigInt bundle-compiler failure and the profile summary are kept in the author's scratch
  directory, not in the repository. The first round's Node timings and BigInt run were **not
  kept**; the figures above come from the rerun.
- **Reload.** In the harness, a reload with equal data re-creates every row. The phone's split for
  the same step recorded one property. Unexplained; a harness difference (the request does not
  suspend here) is the likely cause.
- **The engine options' gains** are not measured here: compiler flags, a newer QuickJS, Hermes,
  Wasm. They come from published benchmarks unlike this workload, and the network/shell here could
  not run other engines (part 2c skipped).
- **BigInt** on a newer QuickJS: its speed for 64-bit values is unknown.
- **Cold start's GC share** was not measured.
