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
- **The engine itself** (measured on this Mac; see "The engine, measured"):
  - Zipline 1.28 runs **QuickJS 2021-03-27**, built at `-Os` on Android.
  - **Compiler flags do nothing here.** The same 2021 engine at `-O2`, `-O3` and `-O3` with LTO ran
    "back" in 78–79 ms, like `-Os`. On the A12 only the 2026 engine's `-O2` was compared with
    `-Os`: 2–5% faster.
  - **The newest QuickJS (2026-06-04) is 1.14–1.20× faster** on the same bundle.
  - **With Kotlin's `Long` as a real BigInt on that engine, "back" is about 2.1× faster and "open"
    about 2.5× faster** (77 → 37 ms and 23 → 9 ms, against Zipline's engine), with identical
    batches. BigInt shrinks both halves: Compose itself (recording off) drops from 41 to 27 ms, and
    recording from about 36 to 10 ms. This needs a fix to that QuickJS release: its `BigInt.asUintN(64, negative)` returns the
    negative value, and without the fix the first composition never returns.
  - **On the A12 the same holds** (2026-11-16, Zipline's `-Os` flags): "back" 1,012 → 506 ms
    (2.0×) and "open" 310 → 130 ms (2.4×), with every batch matching the Mac's.
  - Zipline cannot load bytecode from another engine version (version 1 as Zipline builds it, 5 in
    2026), so adopting it means a Zipline fork and serving bundles by engine version.

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
  at the tag). The engine source carries one patch (a PR #196 fix); the no-eval context,
  FinalizationRegistry and global GC are in Zipline's own native layer ("The engine, measured").
  It is not QuickJS-NG. Zipline's main branch after 1.28.0 was not checked for a newer
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
| 4 | **Rebuild Zipline's QuickJS for Android at `-O2`/`-O3` + LTO** | **none on this Mac**: 78–79 ms at every level ("The engine, measured") | **measured** on an Apple M4 Pro; an in-order Cortex-A53 may differ (not measured) | 1–3 (fork Zipline's native module and AAR; same bytecode) | Low–medium: we maintain a fork | Android (iOS is built with cklib's default `-O3`, but the cklib version Zipline pins was not checked) |
| 5 | **Newer QuickJS (Bellard 2026-06-04)**, which also brings BigInt so `Long` can be a BigInt | engine alone 1.14–1.20×; **with `Long` as BigInt: "back" about 2.1×, "open" about 2.5×** (77 → 37 ms, 23 → 9 ms) | **measured** on this Mac, same bundle, identical batches; needs an engine fix (`asUintN`; the local one covers 64-bit builds only) | 4–8 (fork Zipline's native module and its bytecode compiler, re-apply patches, fix `asUintN`, serve bundles by engine version, re-sign) | Medium: a fork, and two bytecode versions in the field during a transition | Yes |
| 6 | **Hermes** (bytecode interpreter; a JSI port of Zipline's bridge) | about 1.0–1.5× against today's QuickJS, about equal to option 5 | **sourced**, mixed (2026: +16% on Linux, −30% on macOS arm64) | 8–16 | High: bytecode tied to engine version, C++ interop from Kotlin/Native, binary size, leaving Zipline upstream | Yes |
| 7 | **Kotlin/Wasm (WasmGC) on an interpreter** (wasmtime's Pulley supports GC and exception handling; WAMR's GC is incomplete and fails Kotlin's output) | unknown; maybe 2–5× | **estimate**, unproven for GC-heavy code | 20–40+ (Compose publishes no wasm-wasi runtime; new bridge; Pulley host on both platforms) | Very high. Apple 4.7 names only HTML5/JS mini apps, so downloaded Wasm is greyer than JS | In principle |

Ranked, after the measurements in "The engine, measured": 5 is the largest single gain. With
BigInt, option 2 and the encoder parts of option 1 add little (about 1–3 ms of 37). The 10-row
window is independent and still applies. Option 1 remains the cheapest step on today's engine
(2–4 weeks, no fork); option 5 is a 4–8 week fork with two bytecode versions in the field during a
transition. Option 4 gives nothing on this Mac. Options 6 and 7 do not pay for this workload
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

## The engine, measured

Measured 2026-11-16 on the same Mac (Apple M4 Pro, Apple clang 21). Same workload as the rest of
this document: the as-shipped bundle (16-row window), 3 warm-up iterations, then 30 of open and
back. Each figure is the mean of three rounds' medians; the build order was reversed in round 2,
which limits drift between engines. A phone benchmark session ran on the same machine (load average
2.1–3.1); round-to-round differences were 0–3 ms.

**How.** Bellard's QuickJS 2021-03-27 (the version Zipline vendors) and 2026-06-04 (the newest
release) are built from source as the command-line `qjs`
(`keliver-compose-gatea-profile/engine/build-engines.sh`, which pins both tarballs' SHA-256). They
run the Kotlin/JS production executable (the same 46 modules Node runs) through a small CommonJS
loader (`engine/run.js`), with the JVM harness's steps, warm-up, item rotation and median rule.
Differences from Zipline:
- `qjs` parses JavaScript source where Zipline loads precompiled bytecode, so load times are not
  comparable;
- the builds use the Makefiles' flags: `-g`, assertions on (no `-DNDEBUG`), and in 2026 `-fwrapv`.
  Zipline's Android build is `-Os -DNDEBUG`. Assertions cost both versions alike, so the ratios
  hold, and absolute times are slightly high;
- timing is `Date.now()` around each step (the 2021 `qjs` has no finer clock): medians are good to
  about 1 ms, so a ratio on a 9 ms step is good to about ±10%.

To check the route: Zipline's own engine, through the JVM harness in two separate runs (not
overlapping the `qjs` runs), gave "back" 76.8 and 76.9 ms and "open" 23.4 and 23.2 ms; the 2021
`-Os` build under `qjs` gave 78.7 and 23.7 ms, 2% slower. In every run the first, last open and
last back batch had the same hash and byte count as the JVM harness's runs of the same recording
mode (which checks all 67 steps against each other).

**Compiler flags and engine version** (as shipped, kotlinx recording; ms; ratios against Zipline's
engine: back 76.9, open 23.3, recording off 41.1):

| Engine build | Back | Open | Back, recording off | Back vs Zipline | Recording off vs Zipline |
|---|---|---|---|---|---|
| 2021-03-27 `-Os` | 78.7 | 23.7 | 41.7 | 0.98× | 0.99× |
| 2021-03-27 `-O2` | 79.0 | 24.0 | 41.7 | 0.97× | 0.99× |
| 2021-03-27 `-O3` | 78.3 | 24.0 | 41.8 | 0.98× | 0.98× |
| 2021-03-27 `-O3` + LTO | 78.5 | 24.0 | 41.3 | 0.98× | 0.99× |
| 2026-06-04 `-Os` | 67.3 | 20.0 | 36.0 | 1.14× | 1.14× |
| 2026-06-04 `-O2` | 64.0 | 19.0 | 34.7 | 1.20× | 1.19× |
| 2026-06-04 `-O3` | 65.3 | 19.3 | 35.5 | 1.18× | 1.16× |
| 2026-06-04 `-O3` + LTO | 66.5 | 19.7 | 35.3 | 1.16× | 1.16× |

- Flags change nothing for the 2021 engine on this CPU.
- The 2026 engine is 1.14–1.20× faster as shipped and 1.14–1.19× with recording off. The
  differences between its flag levels are within about 3 ms.

**`Long` as a real BigInt.** The 2026 engine has BigInt, so the bundle can be compiled with
Kotlin's `-Xes-long-as-bigint` (`-PprofileBigInt`). On the unmodified 2026-06-04 release the first
composition never returns (`engine/probe.js`: still running after 30 s; with the fix below the same
bundle finishes it in 39 ms, and it runs unmodified in Node, with and without its JIT, with the same
first batch).
- **The cause (read in the source and confirmed by a check):** `js_bigint_asUintN` returns its
  argument unchanged when it is a short BigInt and the width is 64 or more, so
  `BigInt.asUintN(64, -1n)` gives `-1n` instead of 2^64 − 1. A check of 19 64-bit operations
  (`engine/bigint-ops.js`) against Node found three differences, all from this call.
- **Why the composition hangs (inferred, not located):** Kotlin compiles `Long.ushr` to
  `BigInt.asUintN(64, x) >> n` (kotlin-kotlin-stdlib), and androidx.collection, under Compose's
  runtime, uses `ushr` on its hash tables' metadata words. A wrong `ushr` there would make a probe
  loop never end.
- **The local fix** (`engine/patches/quickjs-2026-06-04-asuintn64.patch`, one guarded line) handles
  a negative short BigInt at width 64, which covers Kotlin's `ushr` on a 64-bit build. With it, all
  19 operations match Node and the workload runs with identical batches. It does **not** fix other
  widths, the multi-limb path, or 32-bit builds (where a 64-bit value is two limbs, as on an
  `armeabi-v7a` host): a fork must fix `asUintN` in general. Upstream has not been told yet.

| Engine and bundle | Recording | Back | Open | Back vs today |
|---|---|---|---|---|
| 2021 `-Os`, as shipped (today) | kotlinx | 80.0 | 24.3 | — |
| | direct properties | 67.3 | 22.0 | −16% |
| | direct + modifier cache | 54.7 | 19.8 | −32% |
| | off | 42.0 | 18.0 | −48% |
| 2026 (fixed) `-O2`, as shipped | kotlinx | 64.0 | 19.0 | −20% |
| | direct + modifier cache | 44.0 | 15.3 | −45% |
| | off | 34.3 | 14.0 | −57% |
| **2026 (fixed) `-O2`, `Long` as BigInt** | **kotlinx** | **37.0** | **9.0** | **−54%** |
| | direct properties | 35.7 | 9.0 | −55% |
| | direct + modifier cache | 34.0 | 8.3 | −58% |
| | off | 27.0 | 7.3 | −66% |
| 2026 (fixed) `-Os`, `Long` as BigInt | kotlinx | 40.5 | 10.0 | −49% |

("Today" here is this table's own 2021 run. Against Zipline's engine in the JVM harness, the BigInt
row is 76.9 → 37.0 for "back" and 23.3 → 9.0 for "open".)

What this says:
- **The engine plus BigInt roughly halves "back" and cuts "open" by about 60%**, with no change to
  Keliver's guest code. It needs the engine fork, and Zipline's compiler to accept BigInt.
- **BigInt shrinks both halves of the work.** Compose itself (recording off): 42 → 27 ms.
  Recording (as shipped minus off): 38 → 10 ms. Recording still costs about 10 of the 37 ms.
- **On top of BigInt, the direct encoders save only 1–3 ms** (37 → 34–36 ms): most of what they
  saved on today's engine was emulated `Long`. The 10-row window is a different kind of saving (less
  Compose work) and was not combined with BigInt here.
- On the phone (measured 2026-11-16, below, "On the phone"): "back" 1,012 → 506 ms and "open" 310 →
  130 ms at Zipline's `-Os`, before the 10-row window, a retained back stack or pre-composition.

**What adopting it would take** (read in Zipline's public repository at the 1.28.0 tag; the issue
texts and the file history were fetched 2026-11-16):
- Zipline vendors the engine in `zipline/native/quickjs`. Of its 13 files, 12 are byte-identical to
  upstream 2021-03-27, and `quickjs.c` carries one 6-line patch (a double-to-int64 bound, upstream
  PR #196), which the 2026-06-04 release already contains. `quickjs.c` was last changed in 2023-11.
- Zipline's own additions (a no-eval context, `globalThis.gc`, FinalizationRegistry) live in its
  native layer (`zipline/native`, `zipline/native/common`) and use only the public `quickjs.h`. It
  uses 86 QuickJS names: `engine/api-compare.py` reads 83 of them, and the three `JS_Throw*Error`
  declarations it cannot parse were compared by hand (identical in both). In the 2026 header 74 are
  declared identically. The 12 that changed are all compatible with code that uses the names and
  ignores the results, as Zipline's does:
  - nine `JS_AddIntrinsic*` functions and `JS_SetPropertyFunctionList` now return `int` instead of
    `void` (a fork should check the results);
  - `JS_NewString` is now `static inline`;
  - the value of `JS_TAG_FLOAT64` changed (7 → 8; Zipline uses the name, never the number).

  Nothing was compiled.
- **The bytecode format changed**: version 1 as Zipline builds the 2021 engine (without
  `CONFIG_BIGNUM`), 5 in 2026-06-04. Zipline compiles bundles to bytecode at build time, and a host
  on the old engine cannot load new bytecode. A Zipline collaborator wrote on 2025-10-29: "We plan
  to update, but it's not so simple as the bytecode is incompatible which means you need a mechanism
  in place to compile to the new and old version, as well as a way to deliver the new version only
  to new clients" (zipline #1715, closed that day; #1192 "Evaluate new QuickJS release", open since
  2023-12).
- So: a fork of Zipline's native module and bytecode compiler (or an upstream contribution), a
  general `asUintN` fix (to be reported upstream), `-fwrapv` kept, Zipline's compiler accepting
  BigInt, bundles compiled for both engines during a transition, and the index naming which engine
  a host has. Not checked: Zipline's conversion of JS values to host objects has no BigInt case, so
  any `Long` that crosses the bridge as a BigInt needs a look. Estimate 4–8 engineer-weeks, as in the table.

**How to run it on a phone** (NF0; arm64-v8a, Android 8+). The same three builds and matrix as
the Mac (2021 `-Os` as shipped; 2026 fixed `-Os` and `-O2`, as shipped and `Long` as BigInt; four
recording modes; 3 rounds, round 2 reversed; 3 + 30 iterations; the same median rule), 60 runs.
- **Build:** `engine/build-engines-android.sh` cross-compiles `qjs` with the NDK (default
  28.0.12674087, API 26) from the same pinned tarballs and patches. The flags are Zipline's Android
  release build (`-g0 -Os -fomit-frame-pointer -DNDEBUG -fvisibility=hidden -fstrict-aliasing` on
  the NDK's CMake defaults), the 2026 builds keep the Makefile's `-fwrapv`, and the `-O2` build
  swaps only the optimisation level. It links dynamically against the device's Bionic (libc, libm,
  libdl) only, as Zipline's `libquickjs.so` does, so the allocator is the system's. Two differences
  remain: `qjs` is a PIE executable where Zipline builds a `-fPIC` shared library, and the NDK's
  link-only warning flags (`--no-undefined-version`, `--fatal-warnings`) are left out; neither
  changes the generated code. The exact command lines go to `FLAGS.txt` next to the binaries.
- **Run:** `engine/run-device.sh` (or `scripts/engine-android.sh device`) copies the engines,
  `run.js` and both bundles to `/data/local/tmp/keliver-engine`. Its driver runs on the device
  under `setsid nohup`, so a dropped wireless adb connection does not stop it. Before and after
  each round it records battery level, temperature and charging state, the thermal status and
  whether the screen was awake. `check` fails if any run's first, last open or last back batch
  differs from the Mac's `combine.jsonl`, if a run is missing, or if the device was not awake.
- On the arm64 emulator, all 60 runs matched the Mac's batches in two runs (about 50 s a round).
  Expect about 10 minutes a round on the A12.

```
bash scripts/engine-android.sh build <work dir> <engines dir>     # downloads, verifies, cross-compiles
bash scripts/engine-android.sh device devices                      # find the phone's serial
export ANDROID_SERIAL=<serial>                               # or pass -s <serial> to every command
bash scripts/engine-android.sh device push <engines dir> <as-shipped bundle dir> <BigInt bundle dir>
bash scripts/engine-android.sh device start --stay-awake           # screen stays on while plugged in
bash scripts/engine-android.sh device wait                         # or: status, now and then
bash scripts/engine-android.sh device pull <new local dir>
bash scripts/engine-android.sh device check <local dir> <Mac combine.jsonl>
bash scripts/engine-android.sh device cleanup                      # removes /data/local/tmp/keliver-engine
# rehearsal first, if wanted: device emulator-start <arm64 AVD> ... device emulator-stop <pid>
```

`--stay-awake` changes a phone setting (stay awake while charging) and restores it at `cleanup`.
The A12 run below did not use it. It kept the screen on by leaving the threeway prototype's
activity in the foreground instead (`--ez overLockScreen true` sets the keep-screen-on flag).

**On the phone: the Galaxy A12, 2026-11-16, 18:49–19:18.** The phone has eight Cortex-A53 cores
and runs Android 12. All 60 runs completed, and every run's first, last open and last back batch
matched the Mac's. The phone was on its charger, at 71% → 85%. Its battery temperature was 37.0 °C
at the start and 30.9 °C at the end, and the thermal status was 0 throughout. Rounds were 30 s
apart, and the screen was awake in every round. Milliseconds are the mean of the three rounds'
medians; round to round, "back" varied by at most 42 ms.

| Engine build | Bundle | Recording | Back | Open | Back vs today | Open vs today |
|---|---|---|---|---|---|---|
| 2021 `-Os` (today's engine) | as shipped | kotlinx | 1,011.5 | 309.7 | — | — |
| | | direct properties | 858.0 | 279.3 | −15% | −10% |
| | | direct + modifier cache | 708.3 | 257.7 | −30% | −17% |
| | | off | 527.8 | 229.7 | −48% | −26% |
| 2026 (fixed) `-Os` | as shipped | kotlinx | 844.8 | 256.5 | −16% | −17% |
| 2026 (fixed) `-O2` | as shipped | kotlinx | 818.3 | 245.7 | −19% | −21% |
| **2026 (fixed) `-Os`** | **`Long` as BigInt** | **kotlinx** | **505.8** | **129.7** | **−50%** | **−58%** |
| | | direct properties | 493.3 | 126.5 | −51% | −59% |
| | | direct + modifier cache | 456.7 | 119.7 | −55% | −61% |
| | | off | 346.2 | 101.3 | −66% | −67% |
| 2026 (fixed) `-O2` | `Long` as BigInt | kotlinx | 494.0 | 123.0 | −51% | −60% |

What the phone adds to the Mac's result:
- **It holds on the phone.** With Zipline's own Android flags (`-Os`), the newer engine plus BigInt
  takes "back" from 1,012 to 506 ms (2.0×) and "open" from 310 to 130 ms (2.4×). On the Mac the same
  two `qjs` builds give 2.0× and 2.4× (2.1× and 2.5× against Zipline's engine, at `-O2`).
- **The Mac-to-phone ratio stays the same for the new engine.** "Back" is 12.6× the Mac's on the
  2021 engine and 12.5× on the 2026 BigInt build; "open" is 12.7× and 13.0×. For "back" the Mac
  harness can keep standing in for the phone in ratio. For "open" the Mac's 10 ms median is good to
  about ±5%, and "open" has not tracked the phone before (Unverified).
- **Compiler flags still matter little.** `-O2` beats `-Os` by 2–5% on the phone, against a binary
  about 200 KB larger. Only the 2021 engine's `-Os` was built for the phone, so its other flag levels
  are still Mac-only.
- **BigInt shrinks both halves on the phone too.** Compose itself (recording off) goes from 528 to
  346 ms, and recording (kotlinx minus off) from 484 to 160 ms. On top of BigInt, the direct encoders
  and the modifier cache save 49 ms (−10%), about the same share as on the Mac (−12% at `-Os`, −8% at `-O2`).
- **Still not native.** 506 ms for "back" is the guest's own work alone, with no host and no bridge.
  NF1's retained back stack, which removes "back"'s composition entirely, remains necessary for the
  50 ms target. The guest's own "open" work alone is 130 ms, already over the ~100 ms end-to-end
  target before host and bridge time, so NF3's pre-composition and window size are needed.
- `qjs` parses source where Zipline loads bytecode, so the load column (1.0–1.3 s on the phone) is
  not Zipline's start-up time. Cold start is NF0's separate measurement.

## NF3: the open path, measured (Mac)

2026-11-17, Zipline's QuickJS on the Mac (`profileQuickJs`), kotlinx encoding, 3 warm-up and 30
measured pairs, medians of the JVM's wall time per step, in ms. One run per row, so differences under
about 1 ms are noise. The host's viewport report is now part of the harness (`viewport=10`: a host
reports rows 0–9 after laying out a new list, as the A12 shows about 10 rows); it is timed as its own
step, after the frame it follows.

| Configuration | Press | Open (click) | Back | After back: the host's report |
|---|---|---|---|---|
| As shipped, no report (the earlier harness) | – | 23.81 | 79.16 | – |
| As shipped, with the report, fixed first window (`sized=0`) | – | 28.84 | 83.04 | 32.5 |
| As shipped, viewport-sized first window | – | 28.06 | **62.68** | 50.5 |
| Prepared stack, depth 0, no press | – | 30.98 | 64.45 | 50.5 |
| Prepared stack, depth 0, press | 15.03 | **21.97** | 64.07 | 50.0 |
| Retained (`retain=1`) | – | 15.51 | 2.56 | – |
| Prepared stack, depth 1, no press | – | 15.93 | 2.75 | – |
| Prepared stack, depth 1, press | 14.73 | **6.88** | 2.57 | – |

- **The viewport-sized first window cuts "back" by 25%** (83.0 → 62.7 ms; 145 → 109 widgets
  created: 11 rows instead of 15). It moves work rather than removing it: the rows it leaves out
  are composed after the host's first report (32.5 → 50.5 ms), when the list is already on screen.
  Back plus that report is about level: 115.5 → 113.2 ms. What improves is the step that blocks
  the frame showing the list. The harness's rows all have one height and it reports exactly 10, so
  −25% is the best case. Under `KeliverNavHost` the hint is per back-stack entry (the entry id is
  part of the list's place in the composition), so going back to an entry benefits and a new push
  of the same screen does not (from the code; not run).
  With the report, "open" is 28–29 ms in both, because the list it disposes has grown to 24 rows
  either way (the report's own preload, unchanged).
- **A press moves the detail's whole composition before the click.** With the list retained under
  the detail (depth 1), the click's step drops from 15.9 to 6.9 ms (−57%); 68% of the open's guest
  work runs between touch-down and the click. The click then sends 154 bytes (two frame modifiers)
  instead of 17.6 KB and creates nothing. What is left is recomposition that sends nothing (the
  app's navigation counter changes at the click, so its content lambda and effects run again).
- Without retention (depth 0) the click still disposes the list: 22.0 ms remain of 31.0 (−29%
  against the prepared stack without a press; −22% against the shipped 28.06). The press adds
  15.0 ms, so the open's total guest work rises 32% (37.0 against 28.06).
- **Without a press the prepared stack costs 10% more than shipped** (open 30.98 against 28.06):
  its frame's wrappers run even at depth 0. A host that never sends press composes what it does
  today, a little slower.
- A cancelled press costs its composition (14 ms) and 2 ms to drop it.
- **The click's step alone overstates the gain on the phone.** On the A12 the press's composition
  outlasts the finger, which stays down about 100 ms, and the click is queued behind it. An
  estimate, scaling the Mac by 12–15× ("open" has not tracked the phone before; the host's own
  work, including building the hidden native tree, is not counted). From touch-down to the click's
  guest work done:

  | | Press | Click | Done after touch-down | Without NF3 |
  |---|---|---|---|---|
  | Depth 1 (list retained) | 14.73 → 175–220 | 6.88 → 85–105 | ≈ 260–325 | 15.93 → 190–240, done ≈ 290–340 |
  | Depth 0 | 15.03 → 180–225 | 21.97 → 265–330 | ≈ 445–555 | shipped 28.06 → 335–420, done ≈ 435–520 |

  Depth 1 gains about 15–30 ms (more at 12×, less at 15×), not the click step's 100+. Depth 0 is
  slower than shipping without NF3, by about 5–35 ms. Pre-composition pays on the A12 only with a
  faster engine (NF2), so the press finishes while the finger is still down, or with less work left
  at the click: at depth 1 that is the app's navigation counter, which re-runs the content lambda
  and effects at the click. Not measured on a device: the host has no touch-down event yet.

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
# NF1's retained back stack (the guest's `retain`): add retain=1 to profileArgs (QuickJS or Node)
#   (2026-11-17, Zipline QuickJS on the Mac, profileQuickJs, guest only, 30 iterations:
#    back 81.05 -> 2.44 ms median, 145 -> 0 creates, 94,817 -> 104 bytes. The host's work for a back
#    (applying the batch, showing the list again) is not measured. Open 24.41 -> 15.28 ms is a side
#    effect of the list no longer being disposed, not an improvement to opening.)
# NF3 ("NF3: the open path, measured"): add to profileArgs (QuickJS; Node takes the same)
#   viewport=10              the host's viewport report (rows 0-9) after each new list, its own step
#   sized=0                  the fixed first window (before NF3); the viewport-sized one is the default
#   prepared=0|1             the three-way app on keliver-nav's KeliverPreparedStack at that depth
#   press=1                  (with prepared) a touch-down step before each open, and 10 cancelled presses
#   one QJS NF3 line per run: openGuest, backGuest, press, viewportGuest, cancelledPressGuest, cancelGuest
# variants that need a library patch (apply, run, then `git checkout` the library):
#   10-row window:        sample/threeway-guest/experiments/lazy-window-10.patch
#   serializers once:     keliver-compose-gatea-profile/experiments/serializers-once.patch (codegen)
#   Long as BigInt:       -PprofileBigInt (Node, or the 2026 qjs below; Zipline's compiler rejects it)
# (c) other QuickJS builds ("The engine, measured"): build the engines, then run the bundle's JS in each
keliver-compose-gatea-profile/engine/build-engines.sh <scratch>/engines        # downloads, patches, builds
./gradlew -Pkeliver.guestProfile :keliver-compose-gatea-profile:compileProductionExecutableKotlinJs
#   (add -PprofileBigInt for the BigInt bundle) and copy
#   keliver-compose-gatea-profile/build/compileSync/js/main/productionExecutable/kotlin/*.js to <bundle dir>
<scratch>/engines/build/q26fix-O2/qjs --std keliver-compose-gatea-profile/engine/run.js \
  <bundle dir> kotlinx 3 30 <label>                                            # modes: kotlinx, direct, modcache, none
<scratch>/engines/build/q26fix-O2/qjs keliver-compose-gatea-profile/engine/bigint-ops.js   # compare with: node bigint-ops.js
<scratch>/engines/build/q26-O2/qjs --std keliver-compose-gatea-profile/engine/probe.js <BigInt bundle dir>  # hangs at "first"
python3 -I keliver-compose-gatea-profile/engine/api-compare.py <zipline/native at 1.28.0> \
  <2021 quickjs.h> <2026 quickjs.h> <out.txt>                                  # the QuickJS names Zipline uses
```

## Unverified

- **The phone ratio** comes from two window sizes and two steps of one screen pair: 12–13× for
  "back", 13–15× for "open". Another screen could differ.
- **"Open" does not track the phone.** The 16→10-row window cut the phone's "open" (frame →
  composed) by 28%, but this harness's by 17% (first round: 14%). "Back" matches (−34% and −35%).
  Not explained, so nothing about "open" should be scaled to the phone.
- **Phone runs: the engines only.** The engine matrix ran on the A12 under `qjs` ("On the
  phone"). The other variants (the 10-row window, serializers once, GC thresholds) were not run
  there; their phone estimates are QuickJS-on-Mac numbers × 12–13.
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
- **The engine measurements are from a Mac and the A12.** The A12 run (an in-order Cortex-A53, the
  NDK's clang) confirms the newer engine's gain and the 12–13× ratio. It has one round set, on the
  charger. The 2021 engine's `-O2`/`-O3`/LTO builds were not run on the phone. Hermes and Wasm are
  still not measured (their rows are published numbers).
- **`qjs` is not Zipline.** It parses source where Zipline loads bytecode (load times not
  comparable), and it is built with the Makefiles' flags (`-g`, assertions on). Its 2021 `-Os`
  numbers are 2–3% slower than Zipline's engine in the JVM harness. The route checks the 2021
  engine only; a 2026 Zipline fork could behave differently.
- **The `asUintN` fix is local and minimal** (a negative short BigInt at width 64). Other widths,
  the multi-limb path and 32-bit builds still have the bug; upstream has not been told yet.
- **The hang's location** is inferred from the code (`ushr` in androidx.collection's hash tables),
  not observed.
- **The API comparison** reads declarations; nothing was compiled against the 2026 header.
- **Cold start's GC share** was not measured.
