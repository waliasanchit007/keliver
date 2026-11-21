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
- **The memory that grows per open/back cycle is garbage, not a leak** (2026-11-17, "Memory growth
  per navigation"). 98.4 KB per cycle as shipped, 29.6 KB with retain. A forced collection
  reclaims all of it after 100 cycles, and QuickJS's own collector does too: every 38 cycles as
  shipped, every 116 with retain. The A12's 10-cycle run stopped before the point the Mac's rule
  predicts for the phone (as shipped, cycle 11–20; with retain, about 70 or later).
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
| 5 | **Newer QuickJS (Bellard 2026-06-04)**, which also brings BigInt so `Long` can be a BigInt | engine alone 1.14–1.20×; **with `Long` as BigInt: "back" about 2.1×, "open" about 2.5×** (77 → 37 ms, 23 → 9 ms) | **measured** on this Mac, same bundle, identical batches; needs an engine fix (`asUintN`; the local one covers 64-bit builds only) | 4–8 (fork Zipline's native module and its bytecode compiler, re-apply patches, fix `asUintN`, serve bundles by engine version, re-sign); 6–9.5 left after NF2's step 1 | Medium: a fork, and two bytecode versions in the field during a transition | Yes |
| 6 | **Hermes** (bytecode interpreter; a JSI port of Zipline's bridge) | about 1.0–1.5× against today's QuickJS, about equal to option 5 | **sourced**, mixed (2026: +16% on Linux, −30% on macOS arm64) | 8–16 | High: bytecode tied to engine version, C++ interop from Kotlin/Native, binary size, leaving Zipline upstream | Yes |
| 7 | **Kotlin/Wasm (WasmGC) on an interpreter** (wasmtime's Pulley supports GC and exception handling; WAMR's GC is incomplete and fails Kotlin's output) | unknown; maybe 2–5× | **estimate**, unproven for GC-heavy code | 20–40+ (Compose publishes no wasm-wasi runtime; new bridge; Pulley host on both platforms) | Very high. Apple 4.7 names only HTML5/JS mini apps, so downloaded Wasm is greyer than JS | In principle |

Ranked, after the measurements in "The engine, measured": 5 is the largest single gain. With
BigInt, option 2 and the encoder parts of option 1 add little (about 1–3 ms of 37). The 10-row
window is independent and still applies. Option 1 remains the cheapest step on today's engine
(2–4 weeks, no fork); option 5 is a 4–8 week fork with two bytecode versions in the field during a
transition (chosen as NF2; 6–9.5 weeks remain after its step 1, "NF2: the Zipline fork, step 1"). Option 4 gives nothing on this Mac. Options 6 and 7 do not pay for this workload
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
  `armeabi-v7a` host): a fork must fix `asUintN` in general. Upstream has not been told yet. (The
  fork's general fix, checked on both limb sizes: "NF2: the Zipline fork, step 1".)

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
  a host has. Zipline's conversion of JS values to host objects has no BigInt case (checked and
  added in "NF2: the Zipline fork, step 1"; the bridge itself carries JSON strings). Estimated here
  at 4–8 engineer-weeks, as in the table; after step 1 the remaining work is 6–9.5 weeks, because
  `zipline-bytecode` (step 1b) was not in this estimate ("NF2: the Zipline fork, step 1").

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

## NF2: the Zipline fork, step 1

The owner decided on 2026-11-17 to fork Zipline for NF2 (`docs/PLAN_2026-11.md` section 10). Step 1,
done on the Mac without a device: Zipline 1.28.0 on QuickJS 2026-06-04, with the JVM engine, its
tests and the bytecode compiler working, kept in this repository as a patch set
(`keliver-compose-gatea-profile/engine/zipline-fork/`, its README, `scripts/zipline-fork.sh`). No
repository was created and nothing was published.

**What the fork changes** (two patches on the tag, applied by `apply.sh engine` after it checks the
commit and the tarball's SHA-256):
- **The engine.** `zipline/native/quickjs` is QuickJS 2026-06-04: Zipline's own list of files plus
  `dtoa.c`/`dtoa.h`, new in 2025-04. `-fwrapv` is added to every build (zig for the JVM, the NDK's
  CMake, cklib for iOS), as QuickJS's Makefile builds it.
- **A general `asUintN`.** The release has two faults, not one: it returns a negative argument
  unchanged when the value has no more limbs than the width (`BigInt.asUintN(64, -1n)` is `-1n`),
  and it drops the sign limb when the width is a multiple of the limb size, so a positive result
  reads as negative (`BigInt.asUintN(64, 2n**64n + 2n**63n)` is `-(2n**63n)`). The fix builds the
  result from the two's-complement limbs for every width, short and multi-limb values, and 32- and
  64-bit limbs. `asIntN` was already right.
- **Zipline's native layer**, for the 12 names that changed and one that "The engine, measured"
  did not see:
  - the nine `JS_AddIntrinsic*` and `JS_SetPropertyFunctionList` return `int` now, and their
    results are checked, on JNI and on iOS (`JS_NewString` being inline and `JS_TAG_FLOAT64`'s new
    value need nothing);
  - **strings may be ropes.** 2026 adds `JS_TAG_STRING_ROPE`: a long concatenation stays a rope
    until something flattens it. Zipline tests the tag for `JS_TAG_STRING` in three places. As
    shipped on the new engine, `evaluate` would return `null` for such a string and, worse, an
    inbound call whose JavaScript returns one would hand the JVM an uninitialised value (the
    `assert` that guards it is compiled out in release builds). Fixed on JNI and on iOS;
  - **BigInt to the host.** Zipline's JS-to-host conversion had no BigInt case, so a BigInt result
    was `null`. A BigInt in the signed 64-bit range now becomes a `Long`; a wider one stays
    unsupported, as any other value the conversion cannot represent, with no truncation (the
    range check is one `JS_StrictEq` against the value read back; if the engine runs out of memory
    on the way, its pending exception is dropped and the value is unsupported). This only
    concerns values `evaluate` and `execute` return: Zipline's bridge carries JSON strings, and
    kotlinx.serialization writes a BigInt `Long` as digits, as before;
  - **no uninitialised result.** Zipline's inbound `call` and `disconnect` left their result
    unset for an unexpected tag, behind an `assert` that release builds compile out (found by the
    `-Wall` build below); they now return `null` and `false` there.
- **Notices.** Every modified Zipline file carries "Modified by the Keliver fork of Zipline, 2026"
  (Apache 2.0, section 4(b)); the README states both licences (Zipline: Apache 2.0; QuickJS: MIT).
- **The compiler.** Zipline compiles bundles by running QuickJS on the JVM
  (`QuickJs.compile` in the Gradle plugin), so "the compiler rejects BigInt" was the 2021 parser
  rejecting `1n`. Nothing in Zipline's Kotlin checks for BigInt; on the new engine it compiles.
- Zipline keeps its own `FinalizationRegistry` and its no-eval context; the 2026 engine has a
  native `FinalizationRegistry` (`JS_AddIntrinsicWeakRef`), which the fork does not install, so the
  guest sees what it saw before.

**Checked:**
- **`asUintN` against Node.** `engine/bigint-ops.js` now also runs `asUintN` and `asIntN` at every
  width from 1 to 128 and at 129, 160, 192, 255–257 and 1000, over 40 values (short and multi-limb
  on both limb sizes, positive and negative, at and around 2^31, 2^32, 2^53, 2^63, 2^64, 2^127,
  2^128), and three lines at QuickJS's size limit (2^20 bits: the widest negative `asUintN` that
  fits, and widths of 2^20 and 2^30, where a non-negative value is returned as it is): 292 lines,
  about 10,800 results. A negative value at a width of 2^20 or more is a RangeError in QuickJS but
  not in Node (whose limit is 2^30 bits), so `QuickJs2026Test` checks that one on the fork.
  `apply.sh qjs` builds the patched engine twice, with
  64-bit limbs and with 32-bit limbs (`-U__SIZEOF_INT128__`, the limb size of a 32-bit host such as
  `armeabi-v7a`; the build prints which it got), and the unpatched release both ways as the
  control. **Both patched builds match Node on all 292 lines. The unpatched release differs on 76
  lines with 64-bit limbs and on 108 with 32-bit limbs**, all of them `asUintN` (and the three of
  the original 19 lines that use it); `asIntN` matches everywhere. This is 32-bit limbs, not a
  32-bit build: `armeabi-v7a` also uses NaN-boxed values (`JS_NAN_BOXING`), which only a 32-bit
  emulator in step 2 will exercise.
- **Zipline's JVM tests** on the new engine (`:zipline:jvmTest`, 48 classes: the engine,
  compiling, the call channels, services, flows, finalization, interrupts, UTF-8): **265 run, 263
  pass, 2 skipped** (skipped upstream too), including the fork's 8 (`QuickJs2026Test`). **Again
  with `Long` as BigInt** (`apply.sh test <work> :zipline:jvmTest -PziplineForkLongAsBigInt`: the
  JavaScript the tests load, Zipline's own and kotlinx.serialization's included, linked with
  `-Xes-long-as-bigint`, so its bridge code checks `typeof x === 'bigint'`): the same 265, 263 and
  2. That run is the bridge test for BigInt `Long`s: `EncodingTest` sends `Long.MIN_VALUE`,
  `Long.MAX_VALUE` and every value from 2^53 − 2 to 2^53 + 1, both signs, across the bridge and
  back. Both runs are from a fresh `apply.sh all`. The first run of step 1 had 5 failures, all one change: QuickJS
  2026 writes a column into every stack frame (`file.js:8:3`), and Zipline's JNI frame parser took
  `file.js:8:3` as the file name. The fork parses file, line and column and keeps file and line, so
  Java stack traces read as before; on iOS, where Zipline passes the engine's text through, the
  host test now expects the column. Not run: `zipline-loader`, the Gradle plugin's and
  `zipline-cli`'s tests, Android and iOS.
- **A second thing in Zipline reads bytecode: `zipline-bytecode`.** Zipline's compiler rewrites the
  bytecode itself when a module has a source map (Kotlin's source lines into the bytecode) or when
  line numbers are stripped, and `zipline-bytecode` reads and writes only the 2021 format
  (`BC_VERSION = 1`). On the new engine both paths fail ("unexpected version"). Porting it to
  version 5 is a step of its own (below). For the measurement, `use-fork.init.gradle` turns off
  the production link's source maps, which only removes debug information; the baseline is
  measured that way too (`-PziplineForkRepo=none`: Zipline 1.28.0, source maps off).
- **The guest profile harness on the fork**, four runs back to back in one session on the same
  Mac (M4 Pro), after the review fixes, one run each, 3 warm-up and 30 measured pairs, medians in
  ms. `use-fork.init.gradle` swaps only `zipline-jvm` (in the build's classpath too, so the
  Zipline Gradle plugin compiles the bundle with the forked engine) and turns the source maps off;
  the ratios are against the second row, built the same way:

  | Engine (through Zipline) | Bundle | Back | Open | Back, recording off | Back vs 1.28.0 | Open vs 1.28.0 |
  |---|---|---|---|---|---|---|
  | 1.28.0, QuickJS 2021-03-27, source maps on (as Keliver builds) | as shipped | 79.88 | 23.92 | 42.68 | — | — |
  | 1.28.0, QuickJS 2021-03-27, source maps off | as shipped | 78.93 | 23.78 | 42.60 | — | — |
  | fork, QuickJS 2026-06-04 | as shipped | 68.92 | 20.35 | 38.13 | 1.15× | 1.17× |
  | **fork, QuickJS 2026-06-04** | **`Long` as BigInt** | **39.69** | **9.66** | 28.80 | **1.99×** | **2.46×** |

  Source maps barely move the baseline (1.2% on "back", 0.6% on "open", one run each), so the
  first measurement's ratios, against a baseline with source maps on (82.30 and 24.86 ms; fork
  71.12 and 21.03, BigInt 41.42 and 10.19: 1.16×/1.18× and 1.99×/2.44×), stand. Every run's batch
  hash is the same (637745288 as shipped, 394047653 with recording off), and the
  harness prints which engine it loaded. This is the measurement "The engine, measured" made with
  `qjs` (2.0× and 2.4× at `-Os` on the Mac; 2.1× and 2.5× at `-O2`), now through Zipline's own
  bytecode, compiler and JNI host: the gain survives the real path. The fork's JVM library is built
  at `-Os` like Zipline's (zig's `ReleaseSmall`). The machine was shared, so read the ratios, not
  the absolute times.
- **The JNI library's warnings** (`apply.sh jni <work> --warnings`, `-Wall`, QuickJS's Makefile
  level; the normal build is `-w`): 18, none in code the fork changed. Six unused variables in
  QuickJS itself, ten `mismatched-tags` in Zipline's `Context.h` (`class` against `struct`, which
  matters only to the Microsoft C++ ABI) and two `c99-designator` from QuickJS's `JS_CFUNC_DEF`
  used from C++, all as upstream. `-Wsometimes-uninitialized` found the inbound-call result above,
  now fixed. With `-Wextra`, 164 more are `sign-compare` in QuickJS's own code.

**The remaining steps** (estimates in engineer-weeks). They add up to about 6–9.5 weeks after
step 1, above option 5's 4–8: `zipline-bytecode` was not in that estimate.

| Step | What | Estimate |
|---|---|---|
| 1b. `zipline-bytecode` | Port Zipline's bytecode reader and writer to version 5 (new tags, BigInt constants, the column table), so source maps and line stripping work again; its round-trip tests on the new engine's output. **Done 2026-11-18** (next section) | 1–1.5 |
| 2. Android | Build the AAR (`zipline-android-ndk`, the NDK's CMake; the patch already adds `-fwrapv`) for the four ABIs, run Zipline's device tests on the emulator, then the A12 matrix through Zipline itself, not `qjs`. **Done on the emulator 2026-11-18** ("NF2 step 2 (Android)"); 32-bit and the A12 remain | 0.5–1 |
| 3. iOS | cklib and Kotlin/Native: compile the patched `nativeMain` and `quickjs.def` (not compiled in step 1; `JS_NewString` is now `static inline`, which cinterop must wrap), run the host tests on a simulator, then the iPhone once its signing exists. **Done on the simulator 2026-11-18** ("NF2 step 3 (iOS)"); the iPhone remains | 0.5–1.5 |
| 4. Publishing the fork | Our own coordinates (not `app.cash.zipline`) from this patch set: JVM, Android, iOS, and the JS library unchanged; a CI job that runs `apply.sh all` and the tests | 0.5–1 |
| 5. Dual bytecode | The Gradle plugin compiles each bundle twice, once per engine (bytecode version 1 and 5), from the same JS; the BigInt build (`-Xes-long-as-bigint`) only for the new engine, since the old one cannot parse it | 1–1.5 |
| 6. Manifest and index | The signed manifest names the engine (QuickJS version and bytecode version) for every module, so a bundle cannot be loaded on the wrong engine even if served by mistake; publishing writes one manifest per engine | 0.5–1 |
| 7. Host advertisement and selection | A host sends its engine version when it fetches the index, and the server returns the manifest for that engine only; a host refuses a manifest for another engine before downloading code. The engine becomes one more thing D2's compat gate checks, beside the widget version and capabilities, so a host never receives bytecode it cannot load. Old hosts, which send nothing, get the old engine's bundle | 1 |
| 8. Transition | Both bundles published for every release while old hosts are in the field; a dashboard count of hosts per engine; retiring the old bundle when that count reaches the floor the owner sets | 0.5, then upkeep |
| 9. Upstream | Offer the `asUintN` fix to Bellard (a public post: open item 4 in section 10 of the plan), and the engine update, rope and BigInt cases to Zipline (#1192, #1715), so the fork can shrink back to a version pin | 0.5, then their timeline |

## NF2 step 1b: `zipline-bytecode` on QuickJS 2026's bytecode

2026-11-18, Mac and JVM only, in the same patch set (`apply.sh`, README). Zipline's compiler
(`zipline-cli`, which the Gradle plugin runs) reads the bytecode QuickJS wrote, rewrites its debug
information and writes it back in two cases: a module with a `.js.map` gets Kotlin's files and
lines, and production builds may strip line numbers. `zipline-bytecode` now reads and writes
version 5 only (2021's version 1 is not kept: the fork's engine never writes it, and step 5 compiles
the old engine's bundles with upstream 1.28.0).

**What changed in the format** (from `JS_WriteObject`/`JS_ReadObject` in quickjs.c): the tags after
`BC_TAG_ARRAY` moved (BigInt is 10, functions 12, object references 19); BigInt constants
(`BC_TAG_BIG_INT`: the shortest two's-complement form, little-endian, so the bytes are the same on
32- and 64-bit limbs); the function record gained `var_ref_count`, the variable record `var_ref_idx`
and `has_scope`, the closure record a 3-bit `closure_type` in place of two flags; the debug record
lost its separate line number and gained the function's source text; and the line table now starts
with the function's line and column and has a column delta in every entry. The 243 built-in atoms
are 2026's (a test compares them with `quickjs-atom.h`). Source maps map each entry by its
JavaScript line, as 1.28.0 did (the line's first segment gives the Kotlin file, line and column).
Two 1.28.0 bugs went on the way: 8-bit strings are Latin-1, not UTF-8, and the function flags read
inverted.

**The source text.** QuickJS 2026 writes every function's source text into its bytecode (for
`Function.prototype.toString`); 2021 did not. Each nested function keeps its own copy, so in the
three-way guest it was **10.3 MB of 12.5 MB of bytecode (83%)**. Step 1's bundles carried it. The
fork's `compile` (JNI and iOS) now sets `JS_STRIP_SOURCE` while it compiles, as 2021 behaved: a
loaded function's `toString()` reads `[native code]`, lines and columns stay.

**Checked:**
- **Zipline's tests on the fork**, from a fresh `apply.sh all`: `:zipline:jvmTest` 266 run, 264
  pass, 2 skipped (as upstream; one new: compiled bytecode has no source text);
  `:zipline-bytecode:test` 42, all pass (Zipline's, adapted to version 5: the line table's golden
  bytes, the stack traces; and new: BigInt constants and their encoding, template objects, classes
  with private fields and methods, closures, generators and async functions run after a round trip,
  the atom table, a module with BigInts through a source map, and `RealBundleTest` below);
  `:zipline-cli:test` 12, all pass. Two expectations changed with the engine, both
  improvements: the frame that throws keeps its Kotlin line (`goBoom1(throwException.kt:12)`; 1.28.0
  lost it), and the evaluated script has a line (`<eval>(?:1)`).
- **Real bundles** (`RealBundleTest`, `-PziplineBytecodeCorpus`): the three-way guest's 47 production
  modules with their source maps, as Keliver builds them: 9,573 functions, every one read and written
  back byte for byte; with the source maps applied, 9,026 functions get a Kotlin file and every
  instruction with a line entry was checked against the source map (the line `find_line_num` gives
  at that pc is the source map's line for the JavaScript line); stripped, no function keeps a line
  table; then the bundle loads in dependency order as compiled, with source maps and stripped, every
  module without error. Again with `Long` as BigInt: the same 47 modules, 9,575 functions, 471
  BigInt constants, all byte for byte, 9,027 functions mapped and checked, every module loaded.
- **The guest profile with source maps on** (step 1 had to turn them off), one run each, back to
  back, 3 warm-up and 30 measured pairs, medians in ms, the same Mac. `use-fork.init.gradle` now swaps
  `zipline-bytecode` too:

  | Engine (through Zipline), source maps on | Bundle | Back | Open | Back, recording off | Load | Heap after load | `.zipline` files |
  |---|---|---|---|---|---|---|---|
  | 1.28.0, QuickJS 2021-03-27 | as shipped | 78.21 | 23.53 | 41.49 | 25.02 | 5.11 MB | 1,767 KB |
  | step 1's fork (source text in the bytecode; source maps off) | as shipped | 66.71 | 19.54 | 36.13 | 21.57 | 11.13 MB | 12,215 KB |
  | fork, step 1b | as shipped | 66.57 | 19.55 | 36.04 | 17.39 | 4.90 MB | 1,881 KB |
  | **fork, step 1b** | **`Long` as BigInt** | **39.21** | **9.58** | 28.29 | 17.69 | 4.87 MB | 1,857 KB |

  Against 1.28.0: 1.17× on "back" and 1.20× on "open" as shipped; **1.99× and 2.46× with `Long` as
  BigInt**, the ratios step 1 measured with source maps off. Leaving out the source text took the
  bundle from 6.9× 1.28.0's size to 1.06× (the 47 files with the manifest, apparent size) and the
  heap after loading from 2.2× to 0.96×; loading is not slower than on 1.28.0 (one run each).
  Every run's batch hash is the same (637745288 as shipped, 394047653 with recording off). The
  BigInt bundle is 1% smaller than the plain one; it has 471 BigInt constants.

**Not checked:** Android and iOS (steps 2 and 3, since done: the iOS `compile` change compiles and its test passes on the simulator); a stack
trace from a real Kotlin crash in the three-way guest on a device; whether any guest code calls
`Function.prototype.toString` (Kotlin/JS does not; 1.28.0 gave the same `[native code]`).

## NF2 step 2 (Android)

2026-11-18, on the Mac and the arm64 emulator (`keliver_a12_api35`, API 35); no phone. The same
patch set as steps 1 and 1b, extended (`keliver-compose-gatea-profile/engine/zipline-fork/`, its
README): `apply.sh android`, `qjs-android`, `check-bigint-device`, Android in `publish`, Android in
`use-fork.init.gradle`, and the device tests' JavaScript in `zipline-build.init.gradle`. Every result
below is from one fresh `apply.sh all` of the combined patch set (1b's and this step's). Nothing
was published outside a scratch Maven repository.

**The AAR.** `apply.sh android` builds `zipline-android-ndk` with Zipline's own CMake and release
flags (`-Os -fomit-frame-pointer -DNDEBUG -fvisibility=hidden -fstrict-aliasing`, `MinSizeRel`,
16 KB pages) plus the patch's `-fwrapv`, NDK 28.0.12674087, for the four ABIs Zipline ships
(`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), and checks in each ABI's compile commands that
`quickjs.c` got those flags (all of them, `-fomit-frame-pointer` and `-g0` included), and with the
NDK's `llvm-readelf -lW` that every LOAD segment of each ABI's `libquickjs.so` in the AAR is aligned
for 16 KB pages: 0x4000 on all four ABIs (required of the 64-bit ones). `dtoa.c` comes in through
the CMake glob. `libquickjs.so` (Zipline keeps its symbols in the AAR): arm64-v8a 2,618,752 bytes,
armeabi-v7a 1,903,772, x86_64 2,464,728, x86 2,096,868, **7.6–9.4% above 1.28.0's** (2,432,744,
1,766,772, 2,284,096, 1,916,408: Maven Central's `zipline-android-ndk-1.28.0.aar`, checked against
its `.sha1`, which `apply.sh android` downloads and compares with).

**Two native fixes** (the step 1 review's Lows), with `QuickJs2026JniTest` (JNI tests: JVM and
Android, 4 tests; moved to the host tests as `QuickJs2026InboundChannelTest` in step 3, so iOS runs
them too):
- **An unexpected inbound result throws.** Inbound `call` (not a string) and `disconnect` (not a
  boolean) throw `IllegalStateException` naming the JS tag. Upstream had an `assert`, compiled out
  of release builds; step 1 returned `null` and `false`, which the Kotlin side (a non-null
  `String`) would have trusted. The unexpected result is freed before the exception is thrown:
  freeing an object runs finalizers at once, and a `FinalizationRegistry` callback runs guest code
  that may call the host over JNI, which must not happen with a Java exception pending.
- **`disconnect` frees its result.** Upstream never freed it, so an object result leaked: 1,000
  disconnects returning an object leave fewer than 10 objects after a GC; without the fix, 2,000
  (the object and its array, each time; measured by the earlier session on its checkout).

(The source-text strip in `compile`, which this step had also found, is step 1b's; the combined
patch carries 1b's version.)

**Checked:**
- **Zipline's device tests** (`apply.sh test <work> :zipline:connectedAndroidDeviceTest`, on the
  emulator): 47 classes, **266 run, 264 pass, 2 skipped** (upstream's), 0 failed, among them
  `QuickJs2026Test` (9) and `QuickJs2026JniTest` (4); again with `-PziplineForkLongAsBigInt` (the
  device test's `kotlin-kotlin-stdlib.js` has `BigInt.asIntN(64`): 266 run, 264 pass, 2 skipped, 0
  failed. Both runs' JUnit XML: `<testsuites tests="266" failures="0" errors="0" skipped="2">`
  (Gradle's console says "Starting 266 tests" and then "Finished 268": it counts the 2 skipped
  twice; the XML is the count of record). Without
  the `zipline-build.init.gradle` wiring, 1.28.0's build gives the device tests no testing JS
  (it is added only for a `release` build type) and most fail with "file not found". These runs
  predate the review's fixes (below): the device has not run the reordered inbound-result path.
- **Zipline's JVM tests** on the same checkout: `:zipline:jvmTest` 49 classes, **270 run, 268 pass,
  2 skipped**, plain and with `Long` as BigInt; `:zipline-bytecode:test` 42 (1 skipped:
  `RealBundleTest` without a corpus); `:zipline-cli:test` 12. `check-bigint` (Mac): both patched
  `qjs` builds match Node on all 292 lines, the unpatched ones differ on 76 and 108.
- **BigInt on the device** (`qjs-android`, `check-bigint-device`): `android/run-js.c` on the fork's
  engine, built with the NDK, matches Node on all 292 lines of `bigint-ops.js` on arm64-v8a (it
  reports `JS_NAN_BOXING no`, 64-bit limbs); the unpatched release differs on 76. That run used
  Zipline's release flags plus `-fwrapv`; `qjs-android` now takes the AAR's whole compile line for
  `quickjs.c` (its target, `-mthumb` on armeabi-v7a, `-fstack-protector-strong`,
  `-D_FORTIFY_SOURCE=2`, `-ffunction-sections` and the rest), and those builds have not yet run on
  a device.
- **After the review** (2026-11-18, a fresh `apply.sh all` of the regenerated patch set, no
  device): `check-bigint` as above; `apply.sh android` with every flag present and every LOAD
  segment at 0x4000 on all four ABIs; `qjs-android` with the AAR's compile line; `:zipline:jvmTest`
  49 classes, 270 run, 268 pass, 2 skipped, 0 failed, among them `QuickJs2026JniTest` (4), which
  covers both unexpected-result paths. `check-bigint-device` now requires the runner's header from
  both builds (the control proves nothing if it never ran), fails the fork's build on a non-zero
  exit, and deletes its device directory on any exit.
- **The three-way guest on the fork** (sample, `-PkeliverComposePrototype`, the benchmark build,
  installed under its own application id): built with `use-fork.init.gradle` (source maps on, as
  Keliver builds since 1b) against the scratch repository, the APK carries the fork's
  `libquickjs.so` for all four ABIs. The guest loads its 54 modules from assets, renders the list
  and the detail screen, and the nav series ran open and back 14 times each, three builds in
  rotation, twice, no crash. **Relative timings only** (an emulator on a shared Mac; medians of 12
  after 2 warm-up, the guest's part, `composed`, then to `drawn`):

  | Build (round 1 / round 2) | Open, composed | Open, drawn | Back, composed | Back, drawn |
  |---|---|---|---|---|
  | Zipline 1.28.0 | 48.5 / 46 | 68.5 / 61.5 | 73 / 75 | 90 / 93 |
  | fork | 42.5 / 43.5 | 52 / 51 | 62 / 64 | 74 / 74 |
  | **fork, `Long` as BigInt** | **27.5 / 25.5** | **34 / 34.5** | **37 / 37** | **57 / 57** |

  The guest's part is about 1.8× faster on "open" and 2.0× on "back" with `Long` as BigInt, in line
  with the Mac (2.46× and 1.99×). APK: 25,245,655 bytes on 1.28.0, 26,023,851 on the fork (+3.1%),
  26,007,687 with BigInt (the three APKs, kept in the step's scratch directory); the `.zipline`
  files 3,918,636, 4,168,065 and 4,121,237 bytes (reported by the session that built them; not in
  its logs, and not re-measured since: treat them as unverified).

**Not checked:**
- **32-bit (`JS_NAN_BOXING`).** The only image on this Mac is arm64 API 35, whose ABI list is
  `arm64-v8a` alone; the Apple-silicon emulator runs no x86 images and no 32-bit ARM ones. The
  32-bit runners are built (`<work>/build/android/{armeabi-v7a,x86}/run-js`), and
  `check-bigint-device` runs them on any device whose `ro.product.cpu.abilist` names `armeabi-v7a`
  or `x86` (many arm64 phones still run `armeabi-v7a` too; or a 32-bit phone, or an x86 image on an
  x86_64 Linux host with KVM). Zipline's device tests on such a device
  (`ANDROID_SERIAL=<serial> apply.sh test <work> :zipline:connectedAndroidDeviceTest`) would cover
  the JNI layer on 32-bit too. Until then 32-bit is covered only by the Mac's 32-bit-limb `qjs`,
  which has no NaN boxing.
- The A12 matrix through Zipline (the plan's step 2 says "then the A12 matrix"): a phone run at the
  next batched checkpoint. Absolute timings: none here.

## NF2 step 3 (iOS)

2026-11-18, on the Mac and the iOS simulator (the one the Kotlin plugin's simulator test tasks start
in their standalone mode; no `simctl` command was run by hand); no iPhone. The same patch set,
extended (`keliver-compose-gatea-profile/engine/zipline-fork/`, its README): `apply.sh ios`,
`check-bigint-zipline` and `results`, iOS in `publish` and `use-fork.init.gradle`, and two fixes in
`zipline-build.init.gradle`. Every result below is from one fresh `apply.sh all` of the regenerated
patch set. Nothing was published outside a scratch Maven repository.

**The build.** Zipline builds QuickJS for Kotlin/Native with cklib 0.3.5: one clang call per target
compiles the engine to LLVM bitcode, which goes into the klib (`default/targets/<target>/native/quickjs.bc`)
and is linked into the app. `apply.sh ios` builds `iosArm64` and `iosSimulatorArm64` and checks each
compile line: `-target arm64-apple-ios15.0` and `arm64-apple-ios15.0-simulator`, Zipline's cklib
flags (`-DKONAN_MI_MALLOC=1`, `-ftls-model=initial-exec`, `-D_Float16=short`, the version) with the
patch's `-fwrapv`, and `dtoa.c` (new in 2025-04; cklib takes every file in the engine directory).
cklib adds `-O3 -Wall -Wextra -Werror` on its own (upstream's iOS build is `-O3`, not the `-Os` of the
JVM and Android libraries): QuickJS 2026 compiles under `-Werror` with Zipline's `-Wno-` list, no
warnings. The patched `nativeMain` and `quickjs.def`, never compiled before this step, **compiled as
written**: the 12 renamed or retyped names, `JS_NewString` now `static inline` (cinterop wraps it),
the rope and BigInt cases, the checked `JS_SetPropertyFunctionList`, `JS_STRIP_SOURCE` in `compile`.
The phone's test executable links too (arm64 Mach-O); it was not run.

**Two build fixes** (`zipline-build.init.gradle`, Zipline's own build only):
- cklib reads the Kotlin/Native distribution from `~/.konan/kotlin-native-prebuilt-<os>-<arch>-<kotlin>`
  whatever `KONAN_DATA_DIR` says, and fails with a `NullPointerException` in
  `AppleConfigurablesImpl` when it is not there. With `KONAN_DATA_DIR` set, the init script points
  cklib at the distribution the Kotlin plugin installed there. (cklib's clang, `apple-llvm-20200714`,
  stays in `~/.cklib`, where cklib downloads it once, as upstream.)
- Zipline's Kotlin/Native test tasks read the testing JavaScript at run time and do not declare it,
  so a run with `-PziplineForkLongAsBigInt` after a plain one was UP-TO-DATE and reported the plain
  results (the first BigInt run here did exactly that). The JS and the flag are now inputs of those
  tasks. (The JVM's test task already sees the JS as a resource; Android's device tests always run.)

**Function source text: no difference between the hosts** (the step 1b review's Low 5). The review
read the JVM's `evaluate` as keeping the source text while iOS's left it out. In 1.28.0 both
`evaluate`s are `compile` then `execute` (there is no separate eval on JNI), so both leave it out;
`QuickJs2026Test.evaluateLeavesOutFunctionSource` (a host test: it ran on the JVM and the iOS
simulator; it is in Android's device tests too, which step 3 did not run) checks it: an evaluated
function's `toString()` reads `[native code]`, as one loaded from a bundle does.

**The inbound channel on iOS** (the step 3 review's Medium; upstream code, not a regression). Step 2
fixed the JNI layer's inbound `call` and `disconnect`; the Kotlin/Native one
(`nativeMain/InboundCallChannel.kt`) still converted the result before freeing anything. A JavaScript
exception (a throw, an interrupt, out of memory) or a wrong result type threw with the result, the
argument, the atom, the receiver and the global object still held, and `disconnect` never freed its
argument. iOS builds QuickJS with its asserts (cklib passes no `-DNDEBUG`), so the engine's next
`close()` aborted the app in `JS_FreeRuntime` (`assert(list_empty(&rt->gc_obj_list))`). The fix
mirrors the JNI layer: the tag is checked, a wrong type throws `IllegalStateException`, and every
value is freed in a `finally`, the result first. Step 2's `QuickJs2026JniTest` moved to the host
tests as `QuickJs2026InboundChannelTest` (7 tests: its 4, plus 1,000 `call`s returning an object,
1,000 `disconnect`s counted by strings, and 100 `call`s and `disconnect`s that throw in JavaScript),
so the JVM and iOS run it (and Android's device tests, not run here). Against upstream's iOS
channel the first of them killed the simulator test process: `Assertion failed:
(list_empty(&rt->gc_obj_list)), function JS_FreeRuntime, file quickjs.c, line 2464`, signal 6.

**Checked:**
- **After the review fix** (2026-11-18, the same checkout, then a fresh `apply.sh all` of the
  regenerated patch set): `:zipline:iosSimulatorArm64Test` and `:zipline:iosSimulatorArm64ReleaseTest`
  32 classes, **180 run, 180 pass**, 0 skipped, 0 failed each; `:zipline:jvmTest` 50 classes, **275
  run, 273 pass, 2 skipped**, 0 failed; again with `Long` as BigInt (the testing JavaScript's
  stdlib has `BigInt.asIntN(64`; all three test tasks ran, none UP-TO-DATE): the same, 180 of 180 on
  the simulator in debug and release, 275 run, 273 pass, 2 skipped on the JVM. The fresh
  checkout (`apply.sh all`: both patched `qjs` builds match Node on 292 lines, the unpatched ones
  differ on 76 and 108): `:zipline:iosSimulatorArm64Test` 180 of 180, `:zipline:jvmTest` 275 run,
  273 pass, 2 skipped. The counts below are from before the fix.
- **Zipline's host tests on the simulator** (`apply.sh test <work> :zipline:iosSimulatorArm64Test
  :zipline:iosSimulatorArm64ReleaseTest`; `apply.sh results`, the JUnit XML): 31 classes, **173 run,
  173 pass**, 0 skipped, 0 failed, in the debug build and in the optimised release build Zipline also
  tests (cklib compiles QuickJS at `-O3` for both, so the engine's code is the same in both; the
  release build differs in the Kotlin code and the final link); among them `QuickJs2026Test` (10:
  the version, BigInt in the compiler, `asUintN` and its size limit, BigInt → `Long` and its range, ropes through `evaluate` and
  an inbound call, no source text from `compile` and from `evaluate`), `EncodingTest`, `Utf8Test`
  (the column iOS passes through) and the call channels. **Again with `Long` as BigInt**
  (`-PziplineForkLongAsBigInt`: the testing JavaScript's `kotlin-kotlin-stdlib.js` has
  `BigInt.asIntN(64`): 173 of 173, debug and release.
- **`bigint-ops.js` through Zipline's own engine** (`apply.sh check-bigint-zipline`, new
  `BigIntOpsTest`): the script that checks `asUintN`/`asIntN` at every width and the 64-bit
  operations Kotlin's BigInt `Long` uses, run inside Zipline's `QuickJs` on the JVM and on the iOS
  simulator, **matches Node on all 292 lines on both**. Before this, only the command-line `qjs` and
  Android's `run-js` had run it; iOS uses 64-bit limbs and no NaN boxing, like the Mac. (The
  unpatched release is the control in `check-bigint`, where it differs on 76 lines; there is no
  unpatched iOS build.)
- **Zipline's JVM tests** on the same checkout: `:zipline:jvmTest` 50 classes, **272 run, 270 pass,
  2 skipped** (upstream's), 0 failed, plain and with `Long` as BigInt; the two new tests are the
  `evaluate` one and `BigIntOpsTest` (which compares only when `check-bigint-zipline` has put its
  files in place, and does nothing otherwise). `apply.sh all`: both patched `qjs` builds match Node
  on 292 lines, the unpatched ones differ on 76 and 108.
- **Published and consumed.** `apply.sh publish` now adds `zipline-iosarm64` and
  `zipline-iossimulatorarm64` (each klib checked to carry `quickjs.bc`). With `use-fork.init.gradle`
  the repository's iOS host prototype (Gate B, `keliver-compose-gatea-sample-ios`) resolves
  `zipline-iossimulatorarm64:1.28.0 -> 1.28.0-q20260604` (`dependencyInsight`; the rule follows the
  `available-at` of Zipline's root module), and its app links for the simulator and the phone with
  the fork's klibs (the link lines name them), its guest bundle compiled by the fork's compiler
  (`compileProductionExecutableKotlinJsZipline`). Not installed or run.

**Not checked:**
- **The iPhone.** No run on a phone (its signing is an owner gate). The phone's test executable and
  the Gate B app link for `iosArm64`; nothing has executed the fork's engine on an A-series device.
- **The Gate B app on the simulator with the fork**: linked, not launched; no first-frame or
  open/back timing on iOS through the fork.
- `iosX64` (Intel simulators): not built; an `iosX64` build would keep 1.28.0's engine, so
  `use-fork.init.gradle` warns when `zipline-iosx64` resolves and fails an `iosX64` link
  (`-PziplineForkAllowIosX64` lets it through). The other Kotlin/Native targets Zipline has (macOS, tvOS, Linux) were not
  built.
- Sizes: the klibs and the linked apps were not compared with 1.28.0's.

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

  | | Press | Click | Done after touch-down | Without NF3¹ |
  |---|---|---|---|---|
  | Depth 1 (list retained) | 14.73 → 175–220 | 6.88 → 83–103 | ≈ 260–325 | 15.93 → 190–240, done ≈ 290–340 |
  | Depth 0 | 15.03 → 180–225 | 21.97 → 265–330 | ≈ 445–555 | shipped 28.06 → 335–420, done ≈ 435–520 |

  ¹ The two rows compare against different baselines: depth 1 against the prepared stack at depth 1
  without a press (15.93; the list stays retained, as with `retain=1`'s 15.51), depth 0 against what ships today
  (28.06). "Done" in this column is the click's guest work after the finger's ~100 ms.

  Depth 1 gains about 15–30 ms (more at 12×, less at 15×), not the click step's 100+. Depth 0 is
  slower than shipping without NF3, by about 5–35 ms. Pre-composition pays on the A12 only with a
  faster engine (NF2), so the press finishes while the finger is still down, or with less work left
  at the click: at depth 1 that is the app's navigation counter, which re-runs the content lambda
  and effects at the click. Not measured on a device: the host has no touch-down event yet.

## NF3: the host's press signal (Mac)

2026-11-21. The press above was the driver calling `KeliverNavPreparation.prepare` itself; the host had
no touch-down event. Now it has one in the gate-A prototype:
- **Gate A's `ExtraPress` (modifier tag 501, an extra like `ExtraAnimated`)** carries two
  modifier-event handles. The host's renderer (`applyExtraPress`) watches the pointer without
  consuming anything and sends `onPress` on touch-down and `onCancel` at the end of every gesture that
  sent a press: from the final pass of the up, after the clickable has sent its click in the main
  pass, so a tap reaches the guest as press, click, cancel (the cancel finds the screen pushed and
  drops nothing). So a press that did not open its screen is always dropped: the finger left the
  element or a scroll took the gesture (cancelled at once), a button inside the row took the click, a
  long press, a double click, a disabled clickable, a click that does not navigate, a gesture the
  system cancelled, the element leaving the tree. Both go on the click's event path (the widget's
  modifier event).
- **Inside a scroll container the host waits** (review of #278; the owner's decision): the press is
  sent once the finger has stayed within half the touch slop for 50 ms (`PressSignalTiming`, a
  host-side default a host can change through `LocalPressSignalTiming`), and never when the finger
  moved further first or the touch-down stopped a moving list (the container was scrolling at the
  down). Outside scroll containers it stays immediate. The generated renderers mark the containers
  (`gatea.json`'s `scrollContainers`: `verticalScroll`, `horizontalScroll`, `LazyColumn`, `LazyRow`
  add `pressScrollContainer(state, enabled)`), and the press renderer looks for the nearest enabled
  one above it. A fling that starts on a row costs the guest nothing; a tap shorter than 50 ms sends
  the click only (composed at the click, as before). Not tuned on a phone yet: compare how long taps
  last with how long a fling takes to pass the touch slop on the A12.
- **keliver-nav remembers what it prepared:** `prepareOnPress` cancels the route that was pressed,
  even if the element's route changed during the press, and cancels it itself when the element
  leaves the composition while the finger is down (a refreshed list, a replaced row): the host's
  cancel would then go to a node the guest no longer has and be dropped.
- **D2:** a host declares the host feature `keliver.press@1`; the guest sends the modifier only to a
  host that does, so a host without it (any host today) gets the click only, and the screen is
  composed at the click as before.
- **keliver-nav:** `Modifier.prepareOnPress(controller, route)` (or `(preparation, key)`) adds the
  schema's press modifier through the app's `LocalKeliverNavPress`. `KeliverNavController.prepare`
  and `cancelPreparation` compose the route under `KeliverNavHost` (retention depth 1 or more), and
  `navigate` to it pushes the prepared entry. `KeliverPreparedStack` now runs on the retained stack's
  own path; on a host container (`ExtraRetained`, which shows its last entry) the prepared entry goes
  first, so the host never shows it or goes back to it, and the click moves it to the top (a move,
  nothing created).

Tests: `PressSignalTest` (gate A's host and guest over the real protocol, JVM; each checks the
events the guest received, in order: press, click, cancel for a tap that opens the screen, which the
click shows without composing or creating anything; press, cancel and the composition disposed for a
finger that moves off, a disabled click, a system-cancelled gesture; press, click, cancel for a click
that does not navigate and press, the inner button's click, cancel for a button inside the row, both
dropping the preparation; a host back during a press; in a scroll container, a held tap sends the
press after the delay and a fling that starts on the row sends nothing; a host without the feature →
no modifier on the wire, click only), `PressHostTest` (the host's renderer alone with Compose's own
clickables: the order around a long press and a double click, an element removed mid-press, the
clickable's interactions (its ripple) the same with and without the signal, in and out of a scroll
container, the delay and its tuning, a tap that stops a fling), `KeliverNavPressTest` (JVM and JS:
also the cancel after a navigating click, the pressed route cancelled after the row changed, a row
removed mid-press), the generator's test (the containers are marked), and the profile's
`ProfileRunTest` (the press step sends the row's event; with `press=1` the open step sends the
click and then the cancel, as the host does).

**Numbers.** Zipline's QuickJS 2021 on the Mac (`profileQuickJs`, Zipline 1.28.0 as shipped; the
fork was not available to this run), kotlinx encoding, `viewport=10`, 3 warm-up and 30 measured
pairs, medians of the guest's step in ms, one run per row (differences under about 1 ms are noise).
The press step is the host's press event now, not a direct call.

| Configuration | Press | Open (click) | Back | Cancelled press / its cancel |
|---|---|---|---|---|
| As shipped | – | 28.00 | 61.50 | – |
| Prepared stack, depth 1, no press (click only) | – | 17.00 | 3.00 | – |
| Prepared stack, depth 1, press | 16.00 | **9.00** | 4.00 | 16.50 / 2.00 |
| Prepared stack, depth 0, no press (click only) | – | 34.00 | 67.00 | – |
| Prepared stack, depth 0, press | 17.00 | **25.00** | 67.50 | 17.00 / 2.00 |

- **Press → show against click only:** with the list retained (depth 1) the click's step falls from
  17.0 to 9.0 ms (−47%) and creates nothing (24 widgets at the press, 0 at the click); depth 0, 34.0 →
  25.0 (−26%; −11% against shipped 28.0). The press itself (16–17 ms) runs while the finger is down.
- **Against #265's direct call** (table above): press 14.7 → 16.0, click 6.9 → 9.0 (depth 1); click
  only 15.9 → 17.0 (depth 1), 31.0 → 34.0 (depth 0). Each row is one run, so part of it is noise; the
  rest is what the signal adds: every row's chain carries `ExtraPress` and its two handles, `prepareOnPress`
  runs per row, and `KeliverPreparedStack` now goes through the retained stack's path. Not split
  further.
- A cancelled press costs its composition (16.5–17 ms) and 2 ms to drop it, as before.
- **On a phone** (scaling as in "NF3: the open path" above, 12–15×, not measured): at depth 1 the
  press is about 190–240 ms, longer than the finger's ~100 ms, so the click waits for it; the gain is
  then what the click no longer does (17 → 9 ms of guest work, about 95–120 ms on the A12), less the
  part of the press still running when the finger lifts. Only with NF2's engine (about 2× on "open")
  does the press finish while the finger is down.

**Not run:** the fork with `Long` as BigInt (`use-fork.init.gradle`; no fork repository was at hand in
this session), Node, a device (the threeway Android host does not declare the feature yet), iOS
(the press renderer is common Compose code, compiled for iOS but not exercised there).

## Memory growth per navigation

Measured 2026-11-17 on the Mac, Zipline's QuickJS (`profileQuickJs`, `cycles=…`), no device.

**The question.** On the A12 (THREE_WAY_BENCHMARK.md, "NF1 gate, part 1"), QuickJS's used memory
grew after every open/back cycle, about 218 KB and 1,250 objects as shipped and about 55 KB and 330
with retain, over 10 cycles, and was never seen to come back. Garbage or a leak?

**Method.** `cycles=N` loads the bundle, shows the list, then runs N open/back cycles. After each
open and each back it reads QuickJS's whole `memoryUsage` (every field of `JS_ComputeMemoryUsage`)
and the recorder's own counters (`counts`: live widgets, pending removals, the driver's clickable
list). `gcEvery=K` forces a full collection (`quickJs.gc()`, QuickJS's `JS_RunGC`) every K cycles
and reads again; `gcEvery=0` leaves collection to QuickJS. `probeEvals=K` adds K empty
`evaluate` calls per cycle, a control for the harness's own calls. One JSON line per reading.

**Numbers** (used memory and objects; "open" and "back" readings are after that step):

| Run | Growth per cycle, no collection | After a forced collection | Automatic collection |
|---|---|---|---|
| As shipped, 200 cycles, `gcEvery=0` | 98.4 KB, 628 objects ("open" readings, cycles 10→45) | — | inside the "back" step of cycles 7, 45, 83, 121, 160 and 198 (38 apart), at about 11.5 MB allocated (9.8 MB used); after it, 5.54 MB at "open" and 6.15–6.24 MB at "back" |
| As shipped, 100 cycles, `gcEvery=10` | same | 6,138,836 B and 19,462 objects at cycles 20–100 (±104 B, ±1 object) | — |
| retain=1, 200 cycles, `gcEvery=0` | 29.6 KB, 201 objects ("back" readings, cycles 40→140) | — | inside the "open" step of cycles 31 and 147 (116 apart), peaking at 9.67 MB used (11.55 MB allocated) |
| retain=1, 100 cycles, `gcEvery=10` | same | 6,255,780 B and 19,650 objects at cycles 20–100, unchanged | — |

**Verdict: garbage, not a leak** (in this harness). All of the per-cycle growth is reclaimed by a
full collection in both modes: after-collection memory is flat from cycle 20 to 100 (±104 B; with
retain it first grows about 88 KB between cycles 0 and 10, a warm-up, then stays flat). QuickJS's own
collector reclaims it too, in a saw-tooth. It is cyclic garbage: reference counting frees the
disposed screen's acyclic objects at once (each open drops the heap by about 0.6 MB as the list
goes), and what is left over is reference cycles, which only the cycle collector frees. QuickJS
runs that collector on allocation once the *allocated* size (`memoryAllocatedSize`, not used
memory) exceeds a threshold, and after each of its own collections sets the next threshold to 1.5×
the allocated size. That fits both modes: as shipped, 7.46–7.65 MB allocated after a collection
gives about 11.2–11.5 MB, and it fired inside cycle 45's back step after back 44 read 11.46 MB
allocated; with retain, 7.65 × 1.5 = 11.48 MB, and it fired at 11.55 MB. (Stated on used memory the
rule fits worse: 9.41 MB predicted against 9.67 MB observed.) A forced `JS_RunGC` does not reset
the threshold; only QuickJS's own collections do.

Why the A12 never saw it come back: its run had 10 cycles and no collection, which only says the
threshold in force was above about 9.1 MB used. The first collection does not come a full headroom
after the start: the threshold in force then is set by a collection during load (on the Mac the
first one came at cycle 7 as shipped and 31 with retain, not 38 and 116). So by the same rule the
phone, as shipped (218 KB per cycle), would collect somewhere from cycle 11 to about 20; with retain
(55 KB per cycle on a heap of about 8.1 MB), after about 70 cycles or more. That is a prediction; the
phone run that would show it has not been made. The `gcThreshold` reading of 262,144 does not
contradict it: Zipline's getter returns the value that was set, not QuickJS's live threshold. On the
Mac the getter also read 262,144 throughout, while QuickJS collected at about 11.5 MB allocated.

**Two things that did grow, both in the harness, neither in the guest:**
- **The driver's clickable list** (`ProfileAdapter.clickables`) kept every clickable widget ever
  recorded, 18 per cycle. After 100 cycles that was about 289 B per cycle left after a collection:
  +26 KB, 1,620 array elements, object count flat. **Fixed**: `click` drops removed widgets first.
  Test: `ProfileRunTest.navigationDoesNotGrowTheRecordersState` (20 cycles, both modes; the
  recorder's widgets, pending removals and clickables are the same as after one cycle). The
  "after a forced collection" numbers above are with the fix.
- **`binaryObjectsCount`** rises by exactly one per `evaluate` call (4.1 per cycle; 14.1 with
  `probeEvals=10`), and `binaryObjectsSize` by about 80 B each, while used memory stays flat. It
  counts something of the harness's own `evaluate` calls, not live guest memory. A host calls the
  guest through Zipline's bridge, not `evaluate`.

**What the harness does not cover.** The phone's growth is about 2× the harness's (218 vs 98.4 KB;
55 vs 29.6 KB with retain). The most likely cause is the code the harness stubs: Zipline's bridge
and service calls, treehouse's guest runtime, the real frame clock and the data request. The
evidence is that objects per cycle double too (1,250 vs 628), which argues against allocator or
pointer-size effects; nothing measured confirms it, and it was not checked that the phone ran the
same screen with the same 20 items. That stubbed code could hold something the harness cannot see. The phone's own growth is not settled until a phone run goes
past the collection point.

**Follow-up.** No leak to fix. Two things are worth a phone run:
- **Run 30+ cycles as shipped with `x=mem`** and see the drop. If the heap still only grows past
  20 cycles, the phone has something the harness does not. (With retain the predicted collection is
  after about 70 cycles, so a retain run needs 100+ cycles before "only grows" means anything.)
- **Measure what a collection costs on the phone.** A full collection costs 1.2–3.1 ms here, so
  roughly 15–40 ms on the A12 if the usual 12–13× holds (that ratio is for the guest's compute and
  was not measured for collections, which are memory-bound). QuickJS runs it inside whatever
  allocation crosses the threshold, so one navigation in every ~20 (as shipped; ~70+ with retain,
  predicted) pays it. If that lands in a
  navigation's frame budget, the host could force a collection when idle (`quickJs.gc()` on the
  Zipline thread) instead. That is a design choice, not measured here.

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
#   press=1                  (with prepared) a touch-down step before each open, and 10 cancelled presses;
#                            since the host's press signal, the step sends the row's ExtraPress event
#                            (handle 1; cancel 2) on a guest that sees the feature keliver.press@1
#   one QJS NF3 line per run: openGuest, backGuest, press, viewportGuest, cancelledPressGuest, cancelGuest
# memory growth per navigation ("Memory growth per navigation"): one JSON line per reading in
#   <label>-<mode>.jsonl, a "QJS_MEM" line each on stdout; gcEvery=0 leaves collection to QuickJS
./gradlew -Pkeliver.guestProfile :keliver-compose-gatea-profile:profileQuickJs \
  "-PprofileArgs=cycles=100,gcEvery=10,label=growth" -PprofileOut=<scratch>/out   # add retain=1, probeEvals=10
# variants that need a library patch (apply, run, then `git checkout` the library):
#   10-row window:        sample/threeway-guest/experiments/lazy-window-10.patch
#   serializers once:     keliver-compose-gatea-profile/experiments/serializers-once.patch (codegen)
#   Long as BigInt:       -PprofileBigInt (Node, the 2026 qjs below, or profileQuickJs on NF2's fork)
# (b2) NF2's Zipline fork ("NF2: the Zipline fork, step 1"): build it, publish it to a scratch repo, run
bash scripts/zipline-fork.sh all <work>     # clone the tag, verify, patch, JNI library, qjs, asUintN check
bash scripts/zipline-fork.sh test <work> :zipline:jvmTest [-PziplineForkLongAsBigInt]   # the second: Long as BigInt
bash scripts/zipline-fork.sh jni <work> --warnings          # the -Wall review build
# step 1b: the bytecode module's tests; RealBundleTest on a real bundle (after the production link below)
bash scripts/zipline-fork.sh test <work> :zipline-bytecode:test :zipline-cli:test \
  -PziplineBytecodeCorpus=$PWD/keliver-compose-gatea-profile/build/compileSync/js/main/productionExecutable/kotlin
bash scripts/zipline-fork.sh publish <work> <work>/m2   # zipline-jvm, zipline-bytecode, (step 2) Android: needs the NDK, (step 3) iOS on a Mac
# step 2 ("NF2 step 2 (Android)"): the AAR, the device tests and the BigInt check on a device
bash scripts/zipline-fork.sh android <work>                 # four ABIs, flags checked
ANDROID_SERIAL=<serial> bash scripts/zipline-fork.sh test <work> :zipline:connectedAndroidDeviceTest [-PziplineForkLongAsBigInt]
bash scripts/zipline-fork.sh qjs-android <work>
bash scripts/zipline-fork.sh check-bigint-device <work> <serial>   # every ABI the device runs; 32-bit = NaN boxing
# step 3 ("NF2 step 3 (iOS)"): the iOS targets, the host tests on a simulator, the BigInt check through Zipline
bash scripts/zipline-fork.sh ios <work>                     # iosArm64 + iosSimulatorArm64, cklib flags checked
bash scripts/zipline-fork.sh test <work> :zipline:iosSimulatorArm64Test :zipline:iosSimulatorArm64ReleaseTest [-PziplineForkLongAsBigInt]
bash scripts/zipline-fork.sh results <work> iosSimulatorArm64Test iosSimulatorArm64ReleaseTest   # the JUnit XML, summed
bash scripts/zipline-fork.sh check-bigint-zipline <work>    # bigint-ops.js through Zipline on the JVM and the simulator
# the iOS host prototype (Gate B) against the fork (after publish, which adds the iOS klibs on a Mac)
./gradlew --no-daemon -I keliver-compose-gatea-profile/engine/zipline-fork/use-fork.init.gradle \
  -PziplineForkRepo=<work>/m2 -Pkeliver.gatebIos :keliver-compose-gatea-sample-ios:linkGateBDebugExecutableIosSimulatorArm64
# the threeway sample on the fork (from sample/, after the prototype publish in docs/THREE_WAY_BENCHMARK.md)
./gradlew --no-daemon -I ../keliver-compose-gatea-profile/engine/zipline-fork/use-fork.init.gradle \
  -PziplineForkRepo=<work>/m2 -PziplineForkLongAsBigInt -Dmaven.repo.local=<prototype m2> \
  -PkeliverComposePrototype :host-android:assembleBenchmark       # -PziplineForkRepo=none: the baseline
# the baseline (-PziplineForkRepo=none: 1.28.0), then the fork; [-PprofileBigInt] on the fork. Source maps
# are on since step 1b (-PziplineForkNoSourceMaps turns them off, as step 1 measured)
./gradlew --no-daemon -I keliver-compose-gatea-profile/engine/zipline-fork/use-fork.init.gradle \
  -PziplineForkRepo=<work>/m2 -Pkeliver.guestProfile :keliver-compose-gatea-profile:profileQuickJs \
  "-PprofileArgs=modes=kotlinx+none,iterations=30,warmup=3,label=fork" -PprofileOut=<scratch>/out [-PprofileBigInt]
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
- **Memory growth per navigation** was settled in this harness only (garbage, reclaimed). The
  phone's growth is about 2× the harness's and was never followed past its collection point. A
  collection's cost on the phone is an estimate (Mac × 12–13).
