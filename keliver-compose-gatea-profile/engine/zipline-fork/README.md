# Zipline on QuickJS 2026 (NF2 steps 1, 1b, 2 and 3, and the reviews' Lows)

Keliver's fork of Zipline, kept as a patch set: Zipline **1.28.0** (commit `f6cd1536`) with its
vendored QuickJS 2021-03-27 replaced by Bellard's **QuickJS 2026-06-04** (SHA-256
`b376e839…70ad2a`), which brings BigInt, so Kotlin's `Long` can be compiled as a JS BigInt
(`-Xes-long-as-bigint`). Why, and what it measured: `docs/GUEST_PROFILE.md`, "The engine, measured"
and "NF2: the Zipline fork, step 1". Nothing here is published; no repository is created.

## Licences

- **Zipline** is © Square, Inc. and Block, Inc., under the **Apache License 2.0**
  (`LICENSE` at the root of the checkout). `patches/zipline-1.28.0.patch` modifies it; every file it
  modifies carries "Modified by the Keliver fork of Zipline, 2026" (section 4(b)), and the new
  test file is Apache 2.0 too.
- **QuickJS** is © Fabrice Bellard and Charlie Gordon, under the **MIT licence** (`LICENSE` in the
  tarball, and the notice at the top of each source file, kept verbatim). `patches/quickjs-2026-06-04-asuintn.patch` modifies
  `quickjs.c` and marks its change "Keliver fork (NF2)".

Anything published from this patch set (step 4) carries both licences.

## The patches

- `patches/quickjs-2026-06-04-asuintn.patch` (applied to the engine directory): a general
  `BigInt.asUintN`/`asIntN`. The release returns the argument unchanged when it has no more limbs
  than the width (`BigInt.asUintN(64, -1n)` gives `-1n`), and drops the sign limb when the width is a
  multiple of the limb size (`BigInt.asUintN(64, 2n**64n + 2n**63n)` gives `-(2n**63n)`). The fix
  covers every width, short and multi-limb values, and 32- and 64-bit limbs. It replaces the
  measurement-only `../patches/quickjs-2026-06-04-asuintn64.patch`.
- `patches/zipline-1.28.0.patch` (applied to the Zipline checkout):
  - `zipline/native`: the QuickJS names that changed. `JS_AddIntrinsic*` and
    `JS_SetPropertyFunctionList` now return `int`, and their results are checked (on iOS too).
    Inbound `call` and `disconnect` throw an `IllegalStateException` for an unexpected result
    (upstream returned an uninitialised value once the `assert` was compiled out), and `disconnect`
    frees its result (upstream leaked it when it was an object); both step 2. Strings may be
    ropes (`JS_TAG_STRING_ROPE`, new in 2026): the JNI layer's tag tests for strings would have
    returned `null` (`evaluate`) or an uninitialised value (an inbound call) for a long
    concatenation. A BigInt crosses to the host as `java.lang.Long` when it is in the signed
    64-bit range; a wider one is unsupported, like any other unconvertible value.
  - `zipline/src/nativeMain`, `nativeInterop` (iOS): the same string and BigInt cases. Compiled and
    tested on the iOS simulator in step 3 (unchanged from step 1: they compiled as written). Step 3's
    review fix: the inbound channel (`InboundCallChannel.kt`) matches the JNI layer. It checks the
    result's tag, throws `IllegalStateException` for a wrong type, frees the result, the argument,
    the atom, the receiver and the global object on every path (upstream threw a JavaScript
    exception or a cast failure before freeing any of them, and QuickJS, built on iOS with its
    asserts, aborts in `JS_FreeRuntime` when an object is left), and `disconnect` frees its argument
    (upstream leaked one string per call).
  - `QuickJsException` (JNI): QuickJS 2026 writes `file.js:8:3` (line and column) in stack
    frames; the parser keeps file and line, so Java stack traces read as before. `Utf8Test`'s iOS
    expectation gains the column, which iOS passes through.
  - `zipline-bytecode` (step 1b; Zipline's compiler uses it to apply source maps and strip line
    numbers) reads and writes QuickJS 2026's bytecode, version 5, and no longer version 1: the
    tags after `BC_TAG_ARRAY` moved, BigInt constants (`BC_TAG_BIG_INT`: the shortest two's
    complement, little-endian, the same bytes on 32- and 64-bit limbs), template objects, object
    references, the function record's `var_ref_count`, the variable record's `var_ref_idx` and
    `has_scope`, the closure record's `closure_type`, the function's source text in its debug
    record, and the line table: the function's line and column in a header, then a column delta
    with every entry. The built-in atom table is 2026's (243 atoms; a test checks it against
    `quickjs-atom.h`); a name is never written as one of its symbols, so a file's own string
    `"Symbol.iterator"` stays a string, and a built-in symbol read where a name belongs is refused
    with a message (QuickJS's parser never writes one there; 1.28.0 read it as its text). Source
    maps: a function's Kotlin file is the file of the segment covering its own position (its
    `function` keyword, which Kotlin maps to the declaration), else, as 1.28.0 chose it for every
    function, its first mapped line's first file (wrong when that line starts with code inlined
    from another file, such as `require`). Each position takes the Kotlin line and column of the segment covering its column
    when that segment is in the function's file, else of its line's first segment in that file,
    so two statements on one JavaScript line keep their own Kotlin positions (1.28.0 mapped by
    line only). A segment in another file is never used: a line of `Preconditions.kt` would be
    printed under the function's file name. A position with no segment in the function's file on
    its line takes the previous position's Kotlin line and column, the **nearest preceding Kotlin
    position: an approximation**, so a call on an unmapped line shows the line before it (1.28.0
    printed the JavaScript line under the Kotlin file name). A one-field segment counts as
    unmapped (1.28.0 gave it the previous segment's source and line). Stripping
    line numbers drops each function's source text too. Three 1.28.0 bugs fixed on the way: 8-bit
    strings are Latin-1, not UTF-8 (`café` read as `caf�`), the flag properties read inverted,
    and the line table's op byte was read as signed (a large pc step decoded wrong).
    Tests: Zipline's, adapted to version 5, plus BigInt, template, class/closure,
    run-after-rewrite, per-column (including a position exactly on a segment's first column),
    inlined-file and unmapped-position mapping cases, and `RealBundleTest`, which takes real
    Kotlin/JS bundles (`-PziplineBytecodeCorpus=<dir>`) through a byte-identical round trip, the
    source map and stripping, then loads them in dependency order. Its source-map check computes
    the mapping rule a second time from the parsed segments and compares every function's file,
    header and recorded position (line and column): that shows the rule was applied everywhere,
    not that the rule's Kotlin positions are right. The stack traces in
    `ApplySourceMapToBytecodeTest` and `ZiplineCompilerTest`, against hand-checked Kotlin lines,
    are the independent check.
  - `compile` (JNI `Context::compile`, iOS `QuickJs.compile`; step 1b) leaves out each function's
    source text, which QuickJS 2026 writes into the bytecode and 2021 did not: it was 83% of the
    three-way guest's bytecode (10.3 of 12.5 MB). It sets `JS_STRIP_SOURCE` only while compiling;
    `Function.prototype.toString` of a loaded function reads `[native code]`, as on 1.28.0.
    `evaluate` compiles through `compile` on every host (1.28.0 has no separate eval on JNI either),
    so an evaluated function reads `[native code]` too
    (`QuickJs2026Test.evaluateLeavesOutFunctionSource`, step 3: run on the JVM and the iOS
    simulator; it is in Android's device tests too, which step 3 did not run).
  - Build files: `dtoa.c` (new in QuickJS 2025-04) in `build.zig`; `-fwrapv` in `build.zig`, the
    Android `CMakeLists.txt` and cklib's flags (QuickJS 2026's Makefile builds with it).
  - `QuickJs2026Test` (host tests, JVM and iOS): the version, the compiler accepting BigInt, the
    general `asUintN` and its RangeError at the size limit, BigInt → `Long` and its range, rope
    strings through `evaluate` and an inbound call, and (1b) bytecode without source text.
  - `BigIntOpsTest` (step 3; host tests, JVM and iOS): `../bigint-ops.js` through Zipline's own
    engine, compared with Node's output line by line, when `apply.sh check-bigint-zipline` has put
    the script and Node's output in the checkout's `build/keliver-bigint/` (otherwise it does
    nothing, as on Android, which has no checkout to read).
  - `QuickJs2026InboundChannelTest` (host tests, JVM, Android and iOS; step 2's
    `QuickJs2026JniTest`, moved in step 3's review fix so iOS runs it too): an inbound `call` or
    `disconnect` with the wrong result type throws `IllegalStateException`; 1,000 `call`s or
    `disconnect`s returning an object leave no objects behind, and 1,000 `disconnect`s no strings;
    100 of each that throw in JavaScript leave neither, and the engine closes cleanly. (The wrong
    result is freed before the exception reaches the caller: freeing an object runs finalizers, and a
    `FinalizationRegistry` callback can call the host.)
  - "Modified by the Keliver fork of Zipline, 2026" in every file it changes.

## Rebuilding it

`bash scripts/zipline-fork.sh <command> <work dir>` (from the repository root, JDK 17; needs git,
curl, clang, make and Node):

| Command | What it does |
|---|---|
| `clone` | clones Zipline at the tag into `<work>/zipline` and checks the commit |
| `engine` | downloads QuickJS 2026-06-04 into `<work>/dl`, checks its SHA-256 (and deletes a tarball that fails, so the next run downloads it again), replaces `zipline/native/quickjs` (Zipline's `update_quickjs.sh` list plus `dtoa.[ch]`), applies both patches |
| `jni [--warnings]` | builds the JVM JNI library for this Mac with clang into `zipline/src/jvmMain/resources/jni/<arch>` (Zipline's CI uses zig; same sources, `-Os`, `-fwrapv`); `--warnings` builds with `-Wall` instead of `-w` |
| `qjs` | builds the patched engine's `qjs` with 64-bit and with 32-bit BigInt limbs (`-U__SIZEOF_INT128__`, as on a 32-bit host), and the unpatched release both ways as the control |
| `check-bigint` | runs `../bigint-ops.js` in Node and in the four `qjs` builds: the patched ones must match Node on every line, the unpatched ones must not |
| `android` | step 2: builds the Android AAR (`:zipline-android-ndk:assembleRelease`: Zipline's CMake and release flags, plus `-fwrapv`) for `arm64-v8a`, `armeabi-v7a`, `x86_64` and `x86` with an installed NDK (`KELIVER_NDK_VERSION`, default 28.0.12674087), and checks each ABI's `libquickjs.so` is in it, its `quickjs.c` was built with those flags (`-Os -fomit-frame-pointer -g0 -DNDEBUG -fvisibility=hidden -fstrict-aliasing -fwrapv`), and (with the NDK's `llvm-readelf -lW`) every LOAD segment is aligned for 16 KB pages (0x4000; required of `arm64-v8a` and `x86_64`); prints each size against 1.28.0's AAR from Maven Central (checked against its `.sha1`) |
| `qjs-android` | step 2, after `android`: builds `android/run-js.c` (a minimal runner with `print`, that reports `JS_NAN_BOXING` and the limb size) on the fork's engine and on the unpatched release, per ABI, with the NDK and the AAR's own compile line for `quickjs.c` (its target and every flag; executable instead of shared) |
| `check-bigint-device [serial]` | step 2: runs `../bigint-ops.js` over adb on every ABI the device supports, both builds; both must start (the runner's header), the patched one must match Node and exit 0, the unpatched one must not match. The device directory is deleted on any exit. On a 32-bit ABI this is the check for NaN-boxed values (`JS_NAN_BOXING`), which no Mac build can exercise |
| `ios` | step 3 (a Mac with Xcode): builds the iOS targets with Zipline's own build, `iosArm64` and `iosSimulatorArm64`: cklib's QuickJS bitcode (rerun, and checked: one clang call per target, `-target arm64-apple-ios15.0` and `…-simulator`, with Zipline's cklib flags, `-fwrapv` and `dtoa.c`; cklib's own `-O3 -Wall -Wextra -Werror`), the patched `nativeMain` and `quickjs.def` through cinterop, and both test executables (the phone's is linked, not run) |
| `check-bigint-zipline [gradle args]` | step 3: runs `../bigint-ops.js` through Zipline's own engine (`BigIntOpsTest`) on the JVM and the iOS simulator by default (or the test tasks given), and fails unless each host's output matches Node's on every line |
| `test` | runs Zipline's Gradle build in the checkout with the given tasks, e.g. `:zipline:jvmTest`, `:zipline:connectedAndroidDeviceTest` (Zipline's device tests: the host and JNI tests on the device in `ANDROID_SERIAL`) or `:zipline:iosSimulatorArm64Test` and `:zipline:iosSimulatorArm64ReleaseTest` (the host tests on the simulator the Kotlin plugin's test task starts, debug and optimised); add `-PziplineForkLongAsBigInt` to link the JavaScript the tests load with `Long` as BigInt (`zipline-build.init.gradle`) |
| `results [test task...]` | sums the JUnit XML a test run left (`zipline/build/test-results/<task>`, default `jvmTest`): classes, run, passed, skipped, failed, and the failed tests' names; fails if any failed |
| `publish <repo>` | publishes the forked `zipline-jvm`, `zipline-bytecode`, `zipline-android` and `zipline-android-ndk`, and on a Mac with Xcode `zipline-iosarm64` and `zipline-iossimulatorarm64`, as `1.28.0-q20260604` to a scratch Maven repository (never `~/.m2`) |
| `diff` | for maintainers: regenerates `patches/` from an edited checkout |
| `all` | `clone`, `engine`, `jni`, `qjs`, `check-bigint` (no Android SDK needed) |

`zipline-build.init.gradle` (Zipline's own build, used by `test` and `publish`) also gives
Zipline's device tests the testing JavaScript its JVM tests load: 1.28.0 wires it in only for a
`release` build type, which the device-test variant does not match, and without it most device
tests fail with "file not found: kotlin-kotlin-stdlib.js". For iOS (step 3) it points cklib at the
Kotlin/Native distribution in `KONAN_DATA_DIR` when that is set: cklib 0.3.5 reads
`~/.konan/kotlin-native-prebuilt-<os>-<arch>-<kotlin>` whatever `KONAN_DATA_DIR` says, and fails with
a `NullPointerException` in `AppleConfigurablesImpl` when the distribution is not there. cklib also
compiles with its own clang, which it downloads once into `~/.cklib` (`apple-llvm-20200714`), as
upstream's build does.

`use-fork.init.gradle` points a Keliver build at the published fork without changing it: only the
modules the fork changes are replaced, `zipline-jvm` and `zipline-bytecode` (in the build classpath
too, so the Zipline Gradle plugin compiles bundles with the new engine and rewrites their bytecode
with the ported module), for an Android host, `zipline-android` and `zipline-android-ndk`, and, for
an iOS host, `zipline-iosarm64` and `zipline-iossimulatorarm64` (reached through the `available-at`
of Zipline's root module, which the rule follows). An `iosX64` (Intel simulator) build would keep
1.28.0's engine, which cannot load the bundles the fork's compiler writes, so the script fails the
build when `zipline-iosx64` resolves with the fork in use:

```
./gradlew -I keliver-compose-gatea-profile/engine/zipline-fork/use-fork.init.gradle \
  -PziplineForkRepo=<repo> --no-daemon :keliver-compose-gatea-profile:profileQuickJs [-PprofileBigInt]
```

Source maps stay on, as Keliver builds (step 1 had to turn them off until `zipline-bytecode` was
ported; `-PziplineForkNoSourceMaps` still does). `-PziplineForkRepo=none` keeps Zipline 1.28.0: the
baseline to measure the fork against. `-PziplineForkLongAsBigInt` links every Kotlin/JS executable
(a guest bundle) with `Long` as BigInt, found through each JavaScript target's binaries (any
target name; not wasm, not tests); it needs the fork, names the links it changed, and warns when
the build runs none. Add `-PziplineBytecodeCorpus=<dir of .js and .js.map>` to
`apply.sh test <work> :zipline-bytecode:test` to run `RealBundleTest` on a real bundle, e.g.
`keliver-compose-gatea-profile/build/compileSync/js/main/productionExecutable/kotlin` after
`:keliver-compose-gatea-profile:compileProductionExecutableKotlinJs -Pkeliver.guestProfile`.

## Not yet

iOS on a phone (step 3 ran the simulator only), compiling each bundle for both engines, the manifest
naming the engine, hosts advertising theirs, the transition, and the upstream offer. The plan and estimates
are in `docs/GUEST_PROFILE.md`.
