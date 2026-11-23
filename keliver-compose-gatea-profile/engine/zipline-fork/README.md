# Zipline on QuickJS 2026 (NF2 steps 1, 1b, 2 and 3, and the reviews' Lows)

Keliver's fork of Zipline, kept as a patch set: Zipline **1.28.0** (commit `f6cd1536`) with its
vendored QuickJS 2021-03-27 replaced by Bellard's **QuickJS 2026-06-04** (SHA-256
`b376e839…70ad2a`), which brings BigInt, so Kotlin's `Long` can be compiled as a JS BigInt
(`-Xes-long-as-bigint`). Why, and what it measured: `docs/GUEST_PROFILE.md`, "The engine, measured"
and "NF2: the Zipline fork, step 1". Step 4 builds it for Maven Central as
`dev.keliver.zipline:<upstream artifact name>:1.28.0-keliver.1` ("Publishing the fork" below); nothing
is published yet.

## Licences

- **Zipline** is © Square, Inc. and Block, Inc., under the **Apache License 2.0**
  (`LICENSE` at the root of the checkout). `patches/zipline-1.28.0.patch` modifies it; every file it
  modifies carries "Modified by the Keliver fork of Zipline, 2026" (section 4(b)), and the new
  test file is Apache 2.0 too.
- **QuickJS** is © Fabrice Bellard and Charlie Gordon, under the **MIT licence** (`LICENSE` in the
  tarball, and the notice at the top of each source file, kept verbatim). `patches/quickjs-2026-06-04-backports.patch` modifies
  `quickjs.c` and `libunicode.c` and marks each change "Keliver fork (NF2)".

Anything built from this patch set (step 4) carries both licences: every POM keeps Zipline's
Apache 2.0 licence and adds QuickJS's MIT licence, and every jar, AAR and klib carries
`META-INF/<module>/LICENSE-QuickJS.txt` (the MIT notice at the top of `quickjs.c`, verbatim: the
engine is in the JVM jar's JNI libraries, the AAR's `.so` files and the klibs' bitcode; `<module>`
is the Gradle project, so `zipline-jvm`'s is `META-INF/zipline/…`, and no two of the fork's jars in
one Android app share the path, which Android's packaging would refuse) and `META-INF/NOTICE`
(Zipline, Apache 2.0, modified by Keliver contributors; where the patch set is), all from
`zipline-build.init.gradle` and checked by `check-release.py`. The fork's own new files
(`check-release.py`, `capability-check/`, the CI workflow) are © Keliver contributors, Apache 2.0.

## The patches

- `patches/quickjs-2026-06-04-backports.patch` (applied to the engine directory): fixes from
  Bellard's QuickJS after the 2026-06-04 release (github.com/bellard/quickjs, triaged up to
  `e585640ffa`, 2026-11-10), each marked with its upstream commit. None changes the bytecode
  format, the opcodes or the built-in atoms. BigInt, which Kotlin's `Long` compiles to:
  - `c17f7fc1db` (#557): `BigInt.asUintN`/`asIntN`. The release returns the argument unchanged
    when it has no more limbs than the width (`BigInt.asUintN(64, -1n)` gives `-1n`), and keeps the
    sign limb when the width is a multiple of the limb size (`BigInt.asUintN(64, 2n**64n + 2n**63n)`
    gives `-(2n**63n)`). Upstream's code, verbatim; it replaces the fork's own fix (and the
    measurement-only `../patches/quickjs-2026-06-04-asuintn64.patch`). One difference from that
    fix: `BigInt.asUintN(2 ** 20, -1n)` returns a value one limb over QuickJS's 2^20-bit cap (as
    Node does), on which an operation either gives the right answer or is a RangeError; the
    fork's fix threw at once.
  - `ef5c6e356c`: `--` on a BigInt that is not short, with 64-bit limbs, added 2^32 - 1
    (`let a = -(2n**63n); a--` gave `-9223372032559808513n`).
  - `7852f3a80f` (#583): a negative BigInt and a negative float of another magnitude compared
    the wrong way round (`-10n < -1.5` was `false`).
  - `0d9e58551a`: `JS_ToBigInt64` with 32-bit limbs did not sign-extend a one-limb value
    (unreachable with canonical BigInts; taken for parity).

  Memory safety and leaks on paths Zipline uses: `cd97c2b` (#529, a use after free in
  `build_backtrace` when it runs out of memory), `f68db0e` (a bytecode object reference to an
  object still being read was a NULL dereference), `79f58c1` (`JS_ReadBigInt` leaked on truncated
  bytecode), `88130c0` (#598, a template object read from bytecode was not sealed as the parser
  seals it), `552d5eb` (#587, `JS_ToCStringLen2` leaked on failure), `c530305` (#590, the
  `JS_ValueToAtom` hunk: a built-in symbol's reference leaked), `0943061` (two hunks: a double
  free in `Promise.withResolvers` when a property cannot be defined, and a leak in
  `JS_AddIntrinsicTypedArrays`), `813636e` (#606, a detached or transferred `ArrayBuffer`'s free
  function ran again when it was finalized) and, in `libunicode.c`, `817509a`
  (`unicode_script` looped forever after an allocation failure). Every upstream commit in the
  range, taken or not and why, and the hunks of `0943061`: [`BACKPORTS.md`](BACKPORTS.md).

  The fork's own, not in upstream: four size guards where a script-controlled count made
  `sizeof(X) * n` wrap `size_t` on a 32-bit host and a table filled past its end (a Proxy
  `ownKeys` result, which is also capped at 2^27 keys on every host as a RangeError;
  `toReversed` and its siblings on an array-like; a String object's keys; a typed array sort's
  index table). The sites, and those checked and left alone: BACKPORTS.md, "Keliver-only fixes".
  `size-guards.js` runs the array-like case under a memory limit; every build must print
  `size-guards.expected` (`check-bigint` on the Mac; `check-bigint32` on a 32-bit Linux build,
  every fork workflow run, where the guard itself gives it and the unpatched control must not;
  `check-bigint-device` on each ABI, where only a 32-bit ABI exercises the guard).
  `../bigint-ops.js` covers the BigInt fixes against Node (`check-bigint`,
  `check-bigint-zipline`); `QuickJs2026Test` covers them through Zipline.
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
    general `asUintN` and its behaviour at the size limit, the other backported BigInt fixes
    (`bigIntUpstreamFixes`), the success paths of the functions whose error paths were patched
    (`backportedErrorPathsKeepTheirSuccessPaths`), BigInt → `Long` and its range, rope
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
  - `zipline-gradle-plugin` (step 4): the plugin added `app.cash.zipline:zipline-cli:<version>` to
    every build that applies it, a fixed group. It now names the CLI in the group it was built in
    (`BuildConfig.ziplineGroup`), so the fork's plugin runs the fork's compiler. Upstream's build is
    unchanged by it (its group is still `app.cash.zipline`).
  - `gradle/libs.versions.toml`: **Kotlin 2.4.21**, not 1.28.0's 2.4.20, so the fork's compiler
    plugin, Gradle plugin and runtime klibs are built with the Kotlin Keliver builds with. 2.4.20's
    Kotlin/JS production builds no longer minify member names
    ([KT-89449](https://youtrack.jetbrains.com/issue/KT-89449), fixed in 2.4.21), and the
    release is immutable once published. Which app Kotlin works with which plugin build:
    "Kotlin compatibility" below.
  - "Modified by the Keliver fork of Zipline, 2026" in every file it changes.

## Rebuilding it

`bash scripts/zipline-fork.sh <command> <work dir>` (from the repository root, JDK 17; needs git,
curl, clang, make and Node):

| Command | What it does |
|---|---|
| `clone` | clones Zipline at the tag into `<work>/zipline` and checks the commit |
| `engine` | downloads QuickJS 2026-06-04 into `<work>/dl`, checks its SHA-256 (and deletes a tarball that fails, so the next run downloads it again), replaces `zipline/native/quickjs` (Zipline's `update_quickjs.sh` list plus `dtoa.[ch]`), applies both patches |
| `jni [--warnings]` | builds the JVM JNI library for this Mac with clang into `zipline/src/jvmMain/resources/jni/<arch>` (Zipline's CI uses zig; same sources, `-Os`, `-fwrapv`); `--warnings` builds with `-Wall` instead of `-w` |
| `qjs` | builds the patched engine's `qjs` with 64-bit and with 32-bit BigInt limbs (`-U__SIZEOF_INT128__`, as on a 32-bit host), and the unpatched release both ways as the control; and `android/run-js.c` on the patched engine for this Mac (`run-js`, the runner `check-bigint-device` uses) |
| `check-bigint` | runs `../bigint-ops.js` in Node and in the four `qjs` builds: the patched ones must match Node on every line and exit 0, the unpatched ones must not match. Then `size-guards.js` in all five (the four and `run-js`) under a 64 MiB memory limit, each case in its own process: each must print the case's `size-guards.expected` lines (out of memory; on this 64-bit host from the limit, so the unpatched ones too), and the patched ones must exit 0 |
| `qjs32host` | Linux x86-64 with gcc-multilib (the workflow's `host32` job): builds `qjs` for a 32-bit host with QuickJS's own `CONFIG_M32` (`gcc -m32 -msse2 -mfpmath=sse`: a 4-byte `size_t`, NaN-boxed values, 32-bit limbs), the patched engine and the unpatched release; every file is compiled with `-include size32.h`, a static assert that `size_t` is 4 bytes, the compile line is checked for it, a test program prints `sizeof(size_t)`, and both executables must be ELF 32-bit |
| `check-bigint32` | after `qjs32host`: `../bigint-ops.js` in Node and both builds (the patched one must match on every line and exit 0, the unpatched one must not match), then `size-guards.js` under the 64 MiB limit, each case (`toReversed`, `toSorted`, `toSpliced`, `with`) in its own process: on each, the patched build must print the case's `size-guards.expected` lines and exit 0 (on a 32-bit host only the guard gives that), the unpatched one must not (a crash or a timeout counts as not), so the control proves every line, not only the first |
| `android` | step 2: builds the Android AAR (`:zipline-android-ndk:assembleRelease`: Zipline's CMake and release flags, plus `-fwrapv`) for `arm64-v8a`, `armeabi-v7a`, `x86_64` and `x86` with an installed NDK (`KELIVER_NDK_VERSION`, default 28.0.12674087), and checks each ABI's `libquickjs.so` is in it, its `quickjs.c` was built with those flags (`-Os -fomit-frame-pointer -g0 -DNDEBUG -fvisibility=hidden -fstrict-aliasing -fwrapv`), and (with the NDK's `llvm-readelf -lW`) every LOAD segment is aligned for 16 KB pages (0x4000; required of `arm64-v8a` and `x86_64`); prints each size against 1.28.0's AAR from Maven Central (checked against its `.sha1`) |
| `qjs-android` | step 2, after `android`: builds `android/run-js.c` (a minimal runner with `print`, that reports `JS_NAN_BOXING` and the limb size) on the fork's engine and on the unpatched release, per ABI, with the NDK and the AAR's own compile line for `quickjs.c` (its target and every flag; executable instead of shared) |
| `check-bigint-device [serial]` | step 2: runs `../bigint-ops.js` over adb on every ABI the device supports, both builds; both must start (the runner's header), the patched one must match Node and exit 0, the unpatched one must not match. The device directory is deleted on any exit. On a 32-bit ABI this is the check for NaN-boxed values (`JS_NAN_BOXING`), which no Mac build can exercise. Then `size-guards.js` (`run-js --memory-limit`), each case in its own process: the patched build must print the case's `size-guards.expected` lines on every ABI and exit 0; on a 32-bit ABI, where only the size guard gives those lines, the unpatched one must not, on any case (BACKPORTS.md, "Where they run") |
| `ios` | step 3 (a Mac with Xcode): builds the iOS targets with Zipline's own build, `iosArm64` and `iosSimulatorArm64`: cklib's QuickJS bitcode (rerun, and checked: one clang call per target, `-target arm64-apple-ios15.0` and `…-simulator`, with Zipline's cklib flags, `-fwrapv` and `dtoa.c`; cklib's own `-O3 -Wall -Wextra -Werror`), the patched `nativeMain` and `quickjs.def` through cinterop, and both test executables (the phone's is linked, not run) |
| `check-bigint-zipline [gradle args]` | step 3: runs `../bigint-ops.js` through Zipline's own engine (`BigIntOpsTest`) on the JVM and the iOS simulator by default (or the test tasks given), and fails unless each host's output matches Node's on every line |
| `test` | runs Zipline's Gradle build in the checkout with the given tasks, e.g. `:zipline:jvmTest`, `:zipline:connectedAndroidDeviceTest` (Zipline's device tests: the host and JNI tests on the device in `ANDROID_SERIAL`) or `:zipline:iosSimulatorArm64Test` and `:zipline:iosSimulatorArm64ReleaseTest` (the host tests on the simulator the Kotlin plugin's test task starts, debug and optimised); add `-PziplineForkLongAsBigInt` to link the JavaScript the tests load with `Long` as BigInt (`zipline-build.init.gradle`) |
| `results [test task...]` | sums the JUnit XML a test run left (`zipline/build/test-results/<task>`, default `jvmTest`): classes, run, passed, skipped, failed, and the failed tests' names; fails if any failed |
| `jni-all` | step 4: the JVM's JNI library for every host upstream ships (Linux, macOS and Windows; arm64 and x86-64: six libraries) with zig 0.16.0, as upstream's release workflow builds them (`zipline/build.zig`, which the patch gives `dtoa.c` and `-fwrapv`). Uses a `zig` of that version on `PATH`, else downloads the pinned tarball into `<work>/tools` and checks its SHA-256. Replaces what `jni` built; each library must carry QuickJS's version string |
| `jni-from <tested repo>` | step 4, instead of `jni-all` (the workflow's `publish` job): the six JNI libraries from the `zipline-jvm` jar in a directory `release` wrote (the `fork` job's, whose libraries `jvm-matrix` ran on every host), into the source tree, not rebuilt |
| `release <repo> [--central <tested repo>]` | step 4, after `engine` and `jni-all` (or `jni-from`), on a Mac with Xcode: every publication of every Zipline module as `dev.keliver.zipline:<upstream name>:1.28.0-keliver.1`, to an empty directory, without the build cache, then `check-release.py` (below) and a listing with sizes. The POMs point at `ZIPLINE_FORK_SOURCE_REF` (the release tag; default `main`). With `--central` (the CI workflow only, by hand), the directory is signed and checked with its signatures, its native libraries must be byte for byte the tested directory's (`publish-checks.py natives`), then `publishToMavenCentral` from the same, up-to-date build, in review mode, with `-PziplineForkUploadOnly`: a signing task that would run again fails that build before anything is uploaded. The bundle vanniktech's plugin uploaded (`<zipline>/build/publish/*.zip`) is kept as `<work>/central-bundle.zip`; if that invocation fails, it says whether a bundle was written (the upload was attempted). Refuses a Zipline checkout that uses a vanniktech plugin other than `VANNIKTECH_VERSION` ("What the upload relies on") |
| `check-release <repo> [signed]` | `check-release.py` again on a directory `release` wrote |
| `check-natives <tested repo> <repo>` | `publish-checks.py natives`: every native library (JNI libraries in `zipline-jvm`, `libquickjs.so` in the AAR, QuickJS bitcode in the klibs) identical by sha256 in two directories `release` wrote (built in the same work directory: the linuxX64 bitcode records it) |
| `sha1-manifest <repo> <out>` | after `release --central`, `publish-checks.py sha1`: fails unless `<work>/central-bundle.zip` (what Central received) and the checked directory `<repo>` hold the same files byte for byte (sha1), `maven-metadata.xml` aside (the plugin leaves it out of the bundle and Central writes its own; a second invocation also rewrites its timestamp), each file matching its `.sha1` in the bundle; writes the bundle's files' sha1s (signatures included, checksum files not listed) to `<out>`, and the spot-check list ("Publishing the fork") as a Markdown table |
| `check-capabilities <repo>` | after `release`: `capability-check/`, a plain Gradle consumer, resolves the fork's modules alone (each at the release version, no `app.cash.zipline` module in the graph) and fails unless the fork's runtime or Gradle plugin together with upstream's is refused with a capability conflict |
| `check-upload-gate` | after `clone`: `upload-gate-check/`, a one-project build on the vanniktech plugin version Zipline's build uses, run with Zipline's wrapper. It publishes to Maven Central's staging, then fails a task, and must write no bundle zip and open no connection. The control run doesn't fail a task, and must write the zip and connect. Every connection goes to a local listener, never to Central ("What the upload relies on") |
| `jvm-test-dist` | after `jni-all`: Zipline's JVM tests as a runnable distribution (`zipline/build/install/jvmTest`, `bin/zipline-test` and, for Windows, `bin/zipline-test.bat`; both must be there), which the workflow runs on every host the jar has an engine for |
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

## Publishing the fork (step 4)

Built and checked, **not published**. The owner's decisions (2026-11-22): group
`dev.keliver.zipline`, upstream's artifact names, version `1.28.0-keliver.1`, and one engine,
`quickjs-2026` (no app ever shipped on QuickJS 2021, so nothing publishes the old one).

**What is published: all of Zipline**, the same 54 artifacts upstream publishes at 1.28.0 (checked
against Maven Central's listing), so the Gradle module metadata never points at a variant that is
not there: `zipline` (JVM, Android, JS, iOS, macOS, tvOS and Linux), `zipline-android-ndk` (the
Android engine), `zipline-loader`, `zipline-cryptography`, `zipline-profiler` (each for the same
targets), `zipline-bytecode`, `zipline-cli`, `zipline-api-validator`, `zipline-kotlin-plugin`,
`zipline-gradle-plugin` and the plugin marker. Keliver and its scaffolded apps resolve `zipline`
and `zipline-loader` (and their platform artifacts) and the Gradle plugin, which brings the
compiler plugin and the CLI (with `zipline-bytecode` and `zipline-api-validator`); the rest is
published so the set is complete. `zipline-testing` and `zipline-loader-testing` are not published
upstream and are not here.

**How.** Zipline's own build, unchanged apart from the patch, driven by
`zipline-build.init.gradle` with `-PziplineForkRelease=<group>` (`apply.sh` passes `RELEASE_GROUP`;
the group and version are written once, in `apply.sh`): every project's group becomes
`dev.keliver.zipline`; every POM keeps Zipline's Apache 2.0 licence and Cash App as its authors and
adds QuickJS's MIT licence, Keliver as the fork's developer, and this repository at the release
tag as its URL and SCM; every variant in the Gradle module metadata declares upstream's capability
`app.cash.zipline:<name>` beside its own, so Gradle **refuses a classpath that mixes the fork with
upstream Zipline** (for example an app still on `dev.keliver:*:0.4.x`, which brings
`app.cash.zipline:zipline:1.28.0`) with a capability conflict (Gradle 9.8: "Cannot select module
because of conflict with app.cash.zipline:zipline-jvm:1.28.0 … Both provide capability
'app.cash.zipline:zipline-jvm:…'"), instead
of putting two copies of `app.cash.zipline`'s classes on it (on the JVM the first copy would win,
possibly upstream's 2021 engine). Maven, which reads POMs only, does not see capabilities, and a
build that resolves the conflict with `selectHighestVersion()` gets upstream's, since Gradle orders
`1.28.0-keliver.1` below `1.28.0`. The Gradle plugin also declares the id `dev.keliver.zipline`, and
only that id's marker is published (a marker's group is its id; `app.cash.zipline` is not a
namespace we can publish to).
The jar keeps the id `app.cash.zipline` as well, so a build that puts the plugin on its build
classpath (Keliver's own, `apply plugin: 'app.cash.zipline'`) needs no change; a build that
resolves it by id from a plugin repository (the scaffolded apps' `plugins { id … version … }`)
uses `dev.keliver.zipline`. The JVM engine is built for every host upstream ships (`jni-all`,
zig 0.16.0 as upstream's release workflow).

```
bash scripts/zipline-fork.sh all <work>          # clone, engine, patches, check-bigint
bash scripts/zipline-fork.sh jni-all <work>      # six JNI libraries with zig
bash scripts/zipline-fork.sh release <work> <empty repo dir>
```

`check-release.py` fails the release unless every file is under `dev.keliver.zipline` at
`1.28.0-keliver.1`; nothing names `app.cash.zipline` as a Maven group (POMs, Gradle module
metadata) except as a capability, and every variant with files declares both capabilities; the
artifact names are exactly upstream's; every POM has a name and a description, names both
licences, Keliver's developer and the SCM, and points at the source ref (url and scm tag); Maven
Central's file rules hold (an artifact whose packaging is not `pom` has its main file, sources and
javadoc jars; every file has `.md5` and `.sha1`, and on a signed release `.asc`); every jar, AAR
and klib carries the two notices, once each, the licence under exactly its Gradle project's name
(a platform artifact's project is the root module whose metadata points at it); every main jar
with an upstream counterpart (13: `zipline-jvm`, the Gradle and Kotlin compiler plugins, the
loader, the CLI, `zipline-bytecode`, ...) has classes no newer a class-file version than upstream's
jar of the same name (the release is built on JDK 21, and a build applying the plugin may run
Gradle on 17; all are Java 11, as upstream's); the JVM jar
has upstream's JNI entries and the Android AAR its
four ABIs with each library QuickJS 2026 (the atom `BigInt64Array`, which Zipline's 2021 build
never has; upstream's own libraries are the control); every native klib of `zipline` carries the
engine's bitcode; and the Gradle plugin declares both ids, names no `app.cash.zipline` coordinate,
and its `BuildConfig` constants are the fork's: `KOTLIN_PLUGIN_GROUP` and `ziplineGroup`
`dev.keliver.zipline`, `KOTLIN_PLUGIN_VERSION` and `ziplineVersion` `1.28.0-keliver.1` (the
compiler plugin and the CLI every build applying the plugin resolves). It then lists every
artifact with its files' sizes. 2026-11-22, on this Mac: 54 artifacts, 1,825 files, 32.6 MB; 53
Gradle module files with 152 variants; 200 jars, AARs and klibs; `zipline-jvm` 2.7 MB (six
engines, 776–960 KB each), `zipline-android-ndk`'s AAR 3.0 MB, each `zipline` klib 2.1 MB.

`use-release.init.gradle` points a Keliver build at such a repository without changing it, for
trying a release before it is on Maven Central: every `app.cash.zipline` module becomes the fork's
module of the same name. With `-PziplineReleaseVerify` it also fails the build when an
`app.cash.zipline` module was requested by anything but the build's own projects: by one of the
fork's modules, or in a configuration the fork's Gradle plugin fills (`ziplineCli`, the Kotlin
compiler plugin classpaths). Substitution alone would hide such a leak:

```
./gradlew -I keliver-compose-gatea-profile/engine/zipline-fork/use-release.init.gradle \
  -PziplineReleaseRepo=<repo dir> -PziplineReleaseVerify --no-daemon :keliver-bridge-fixture:jvmTest
```

**CI**: `.github/workflows/zipline-fork.yml` runs on pull requests that touch this directory (not
for Markdown alone) and by hand. The `fork` job (macOS): `all`, `jni-all`, Zipline's host tests on
the JVM and the iOS simulator, the same again with the test JavaScript linked with `Long` as BigInt
(`-PziplineForkLongAsBigInt`), `check-bigint-zipline` on the JVM, `android`, `release` to a
directory (kept as the run's artifact), `check-capabilities` and `check-upload-gate`, and
`jvm-test-dist`. Before all of that, in seconds, `checks_test.py`: unit tests of
`publish-checks.py` (`natives` and `sha1`) and of `check-release.py`'s licence-path rule, on
synthetic repositories (`python3 -I keliver-compose-gatea-profile/engine/zipline-fork/checks_test.py`). The
`jvm-matrix` job runs that distribution on the six runners upstream's `build.yaml` uses
(`macos-15-intel`, `macos-15`, `ubuntu-24.04`, `ubuntu-24.04-arm`, `windows-2025`,
`windows-11-arm`), so each JNI library in `zipline-jvm` is loaded and tested on its own host
(`bin/zipline-test` in bash, `bin\zipline-test.bat` in cmd on Windows). It runs whenever `fork`
passed, pull requests included. The `host32` job (Linux, minutes, beside `fork`): `qjs32host`
and `check-bigint32`, the fork's 32-bit size guards and BigInt on a real 32-bit build; `publish`
needs it too. Zipline's Android device tests do not run in CI (GitHub's macOS arm64 runners have
no emulator).

**Publishing** (the owner's decision, 2026-11-22: review mode for the first release; this is the
third release line, beside the libraries and the tools bundle, CLAUDE.md "Releases"):

1. Tag the commit on `main`: `zipline-fork-v<RELEASE_VERSION>` (`zipline-fork-v1.28.0-keliver.1`;
   it matches neither `v*` nor `portal-tools-v*`, so no other workflow fires).
2. Dispatch `zipline-fork.yml` from `main` with that tag, `publish` ticked and the version typed
   out. The `guard` job refuses a run not from `main`, a tag that is not
   `zipline-fork-v<RELEASE_VERSION>` (as `apply.sh` has it at the tag) or not on `main`'s history,
   a mistyped version or missing secrets (the ones `publish-maven-central.yml` uses), and resolves
   the tag to its commit once: every later job checks out that commit, so a tag moved during the
   run is never built. `fork`, `host32` and `jvm-matrix` then check it; `publish` builds it again on a fresh
   runner without the build cache, taking the JNI libraries from `fork`'s directory (`jni-from`:
   the ones `jvm-matrix` ran on every host, not rebuilt), signs it into a directory, checks it
   again (signatures included), requires every native library to be byte for byte `fork`'s
   (`publish-checks.py natives`: the six JNI libraries, the AAR's four `.so` files and the klibs'
   bitcode, by sha256; anything else stops the job before the upload; both jobs build in the same
   directory, because the linuxX64 klib's bitcode records it: on 2026-11-22 two local builds in
   different directories differed in that one entry, and a rebuild in the same directory matched
   all 20) and uploads it to Central in
   **review mode**: Zipline's own `automaticRelease = true` is overridden
   (`zipline-build.init.gradle`, which also stops the build if the task it disables is no longer
   there), so the deployment is validated and then held. The upload is a second Gradle invocation
   (`publishToMavenCentral`) that may not sign anything: every signing task must be UP-TO-DATE, so
   the files and the `.asc` signatures it sends are the ones checked (a signature carries its
   time, so a signing task that ran again would send new ones; one that would run fails the build,
   and vanniktech's plugin uploads only after a successful build). The plugin uploads one zip of its
   staging repository, which leaves out `maven-metadata.xml` (Central writes its own); the job
   keeps that zip, and `sha1-manifest` fails unless it holds exactly the checked directory's files,
   byte for byte (sha1), `maven-metadata.xml` aside. Measured on 2026-11-22 on this Mac with a
   throwaway key, junk credentials and the upload blocked (its connection refused): all 55 signing
   tasks were UP-TO-DATE in the upload invocation. The bundle it wrote held 1,866 entries: 311
   files, their 311 `.asc` signatures (Gradle writes no checksum files for those), the 1,244
   checksum files and no `maven-metadata.xml`. Every entry was byte for byte the signed directory's.
   The guard's own negative control: with its action added in the upload invocation only, every
   signing task was out of date, and the build stopped before the upload. That a stopped build
   uploads nothing rests on the plugin's bytecode, and `check-upload-gate` shows it on every
   `fork` run ("What the upload relies on", below).
3. **Drop unless the `publish` job is fully green.** Its last step compares what was uploaded with
   what was checked, after the upload: a red step there, or any failure after the upload, leaves a
   deployment in the Portal that nothing vouches for. If the upload step itself fails, the summary
   says **the upload may have happened: Drop any deployment of this version**. If the bundle was
   written, the upload was attempted, and the job keeps the bundle as
   `zipline-fork-failed-upload-<tag>`. Then, in the Central Portal (Deployments),
   check the deployment before anything goes live: it lists `dev.keliver.zipline` and is VALIDATED,
   not PUBLISHED. The run's summary has the sha1 of every file in the uploaded bundle (also the
   artifact `zipline-fork-sha1-<tag>`, with the bundle itself, and the signed directory is
   `zipline-fork-repo-central-<tag>`; kept 90 days). The manifest lists each file and each `.asc`
   once, not the `.md5`/`.sha1`/`.sha256`/`.sha512` files beside them; the summary gives both
   counts. Compare the Portal's file list with the manifest, and at least these files' sha1s (the
   spot-check list, also a table in the summary), each with its `.asc`, which should match too:
   - `zipline-jvm/<v>/zipline-jvm-<v>.jar` (the six JVM engines)
   - `zipline-android-ndk/<v>/zipline-android-ndk-<v>.aar` (the Android engine)
   - `zipline-iosarm64/<v>/zipline-iosarm64-<v>.klib` (the iPhone engine)
   - `zipline-gradle-plugin/<v>/zipline-gradle-plugin-<v>.jar` (what resolves the compiler plugin and the CLI)
   - `zipline/<v>/zipline-<v>.module` (the multiplatform module's metadata and capabilities)

   Then press **Publish**, or **Drop** to abandon it. Maven Central has no undo; a fix after that
   is `1.28.0-keliver.2`.

### What the upload relies on

The upload uses vanniktech's maven-publish plugin, at the version Zipline 1.28.0's build pins:
0.36.0. `apply.sh` records it as `VANNIKTECH_VERSION`, and `release` and `check-upload-gate`
refuse a Zipline checkout whose version catalog names another. On 2026-11-22 its behaviour was
read with `javap -c` from `gradle-maven-publish-plugin-0.36.0.jar` (`MavenCentralBuildService`,
`EndOfBuildAction`, `PrepareMavenCentralPublishingTask`) and `central-portal-0.36.0.jar`
(`SonatypeCentralPortal`) (review 4 of PR #289, N-1 and N-2):

- **Registration.** Each project's `prepareMavenCentralPublishing` task registers the project with
  the plugin's build service. That queues two actions: an `Upload`, which never runs after a
  failure, and a `Drop`, which runs only after a failure and only for a deployment the build
  created.
- **A failed build.** The service listens to every task's finish event, and any failed task marks
  the build failed. When the service closes at the end of a failed build, it runs only the
  after-failure actions. So it writes no zip and uploads nothing. The `Drop` has no deployment to
  drop, and the Portal client is created lazily, so no connection is opened either.
- **A successful build.** The service creates `<root build dir>/publish/<name>-<millis>.zip` (it
  fails if the file exists). It writes every staged file into it except `maven-metadata*`, closes
  it, and only then uploads it, USER_MANAGED in review mode. Review mode adds no validate action,
  so there's no wait for validation.
- **The zip is never deleted.** Nothing in either class deletes, moves or renames it, on any path.
- **An upload error fails the build.** The error isn't caught on the success path, so the build
  fails ("Failed to stop service 'maven-central-build-service'") with the zip left behind.

For the `publish` job, this means that if the upload step fails, either the upload was never
reached (no zip), or it was attempted (a zip, which the job keeps). An attempt that failed may
still have left a deployment in the Portal, for example when the Portal accepted the bundle but
its response was lost. That's why the summary says to Drop any deployment. A copy from inside the
build isn't needed, because the plugin never removes its zip.

`check-upload-gate` (`upload-gate-check/`) shows the plugin's part. On 2026-11-22 it ran on this
Mac with Zipline's own Gradle 9.8.0:

- **Failing run.** `prepareMavenCentralPublishing` and the publication ran, then a task failed. The
  build failed, with no zip under `build/publish` and no connection at the listener.
- **Control run.** The zip was written, the listener saw `CONNECT central.sonatype.com:443`, the
  upload failed (the listener closes the connection), the build failed with "Failed to stop
  service 'maven-central-build-service' … unexpected end of stream", and the zip was still there.

The `fork` job runs it on every run. It doesn't run Zipline's own build under the Sign guard. That
case depends on the same rule (any failed task stops the upload) and on step 2's local run, where
the build stopped before the upload.

## Not yet

Publishing (a manual run of the workflow), then Keliver on the fork, in this order, because an app
must never get the fork and upstream together (the capability conflict above would stop its build):
the fork published → Keliver's version catalog and libraries at 0.5.0 on `dev.keliver.zipline`,
with Long as BigInt by default in Keliver's build-support and templates (the owner, 2026-11-22: not
in the fork's plugin, which keeps upstream's behaviour) → the scaffolders write the fork's
coordinates only for an app whose `appRuntime.keliverVersion` is 0.5.0 or later (one engine,
`quickjs-2026`) → then a tools release. Also iOS on a phone (step 3 ran the simulator only), and
the upstream offer. The plan and estimates are in `docs/GUEST_PROFILE.md`.
