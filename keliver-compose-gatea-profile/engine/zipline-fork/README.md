# Zipline on QuickJS 2026 (NF2 steps 1 and 2)

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
    frees its result (upstream leaked it when it was an object). `compile` strips function source
    text (`JS_STRIP_SOURCE`, JNI and iOS): QuickJS 2026 writes it into bytecode by default, 2021
    never did, and with it a bundle is about 8× larger. Strings may be
    ropes (`JS_TAG_STRING_ROPE`, new in 2026): the JNI layer's tag tests for strings would have
    returned `null` (`evaluate`) or an uninitialised value (an inbound call) for a long
    concatenation. A BigInt crosses to the host as `java.lang.Long` when it is in the signed
    64-bit range; a wider one is unsupported, like any other unconvertible value.
  - `zipline/src/nativeMain`, `nativeInterop` (iOS): the same string and BigInt cases. **Not
    compiled in step 1.**
  - `QuickJsException` (JNI): QuickJS 2026 writes `file.js:8:3` (line and column) in stack
    frames; the parser keeps file and line, so Java stack traces read as before. `Utf8Test`'s iOS
    expectation gains the column, which iOS passes through.
  - Not ported: `zipline-bytecode` (Zipline's compiler uses it to apply source maps and strip line
    numbers) reads and writes bytecode version 1 only; 2026 writes 5. `use-fork.init.gradle` turns
    the production link's source maps off until it is ported.
  - Build files: `dtoa.c` (new in QuickJS 2025-04) in `build.zig`; `-fwrapv` in `build.zig`, the
    Android `CMakeLists.txt` and cklib's flags (QuickJS 2026's Makefile builds with it).
  - `QuickJs2026Test` (host tests, JVM and iOS): the version, the compiler accepting BigInt, the
    general `asUintN` and its RangeError at the size limit, BigInt → `Long` and its range, rope
    strings through `evaluate` and an inbound call, compiled bytecode without source text.
  - `QuickJs2026JniTest` (JNI tests, JVM and Android): an inbound `call` or `disconnect` with the
    wrong result type throws, and 1,000 `disconnect`s returning an object leave no objects behind.
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
| `android` | step 2: builds the Android AAR (`:zipline-android-ndk:assembleRelease`: Zipline's CMake and release flags, plus `-fwrapv`) for `arm64-v8a`, `armeabi-v7a`, `x86_64` and `x86` with an installed NDK (`KELIVER_NDK_VERSION`, default 28.0.12674087), and checks each ABI's `libquickjs.so` is in it and its `quickjs.c` was built with those flags |
| `qjs-android` | step 2: builds `android/run-js.c` (a minimal runner with `print`, that reports `JS_NAN_BOXING` and the limb size) on the fork's engine and on the unpatched release, per ABI, with the NDK and the AAR's flags |
| `check-bigint-device [serial]` | step 2: runs `../bigint-ops.js` over adb on every ABI the device supports, both builds; the patched one must match Node, the unpatched one must not. On a 32-bit ABI this is the check for NaN-boxed values (`JS_NAN_BOXING`), which no Mac build can exercise |
| `test` | runs Zipline's Gradle build in the checkout with the given tasks, e.g. `:zipline:jvmTest`, or `:zipline:connectedAndroidDeviceTest` (Zipline's device tests: the host and JNI tests on the device in `ANDROID_SERIAL`); add `-PziplineForkLongAsBigInt` to link the JavaScript the tests load with `Long` as BigInt (`zipline-build.init.gradle`) |
| `publish <repo>` | publishes the forked `zipline-jvm`, `zipline-android` and `zipline-android-ndk` as `1.28.0-q20260604` to a scratch Maven repository (never `~/.m2`) |
| `diff` | for maintainers: regenerates `patches/` from an edited checkout |
| `all` | `clone`, `engine`, `jni`, `qjs`, `check-bigint` (no Android SDK needed) |

`zipline-build.init.gradle` (Zipline's own build, used by `test` and `publish`) also gives
Zipline's device tests the testing JavaScript its JVM tests load: 1.28.0 wires it in only for a
`release` build type, which the device-test variant does not match, and without it most device
tests fail with "file not found: kotlin-kotlin-stdlib.js".

`use-fork.init.gradle` points a Keliver build at the published fork without changing it: only the
modules with the engine in them are replaced, `zipline-jvm` (in the build classpath too, so the
Zipline Gradle plugin compiles bundles with the new engine) and, for an Android host,
`zipline-android` and `zipline-android-ndk`:

```
./gradlew -I keliver-compose-gatea-profile/engine/zipline-fork/use-fork.init.gradle \
  -PziplineForkRepo=<repo> --no-daemon :keliver-compose-gatea-profile:profileQuickJs [-PprofileBigInt]
```

It also turns the production link's source maps off (`zipline-bytecode` cannot rewrite the new
bytecode yet). `-PziplineForkRepo=none` keeps Zipline 1.28.0 and only turns them off: the
baseline to measure the fork against. `-PziplineForkLongAsBigInt` links every Kotlin/JS
executable (a guest bundle) with `Long` as BigInt; it needs the fork.

## Not yet

iOS (cklib and Kotlin/Native), compiling each bundle for both engines, the manifest naming the
engine, hosts advertising theirs, the transition, and the upstream offer. The plan and estimates
are in `docs/GUEST_PROFILE.md`.
