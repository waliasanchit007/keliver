# Zipline on QuickJS 2026 (NF2 steps 1 and 1b)

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
    Inbound `call` and `disconnect` no longer return an uninitialised value for an unexpected tag
    when the `assert` is compiled out. Strings may be
    ropes (`JS_TAG_STRING_ROPE`, new in 2026): the JNI layer's tag tests for strings would have
    returned `null` (`evaluate`) or an uninitialised value (an inbound call) for a long
    concatenation. A BigInt crosses to the host as `java.lang.Long` when it is in the signed
    64-bit range; a wider one is unsupported, like any other unconvertible value.
  - `zipline/src/nativeMain`, `nativeInterop` (iOS): the same string and BigInt cases. **Not
    compiled in step 1.**
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
    `quickjs-atom.h`). Source maps map each entry's line as 1.28.0 did (the line's first segment
    gives the Kotlin file, line and column). Two 1.28.0 bugs fixed on the way: 8-bit strings are
    Latin-1, not UTF-8 (`café` read as `caf�`), and the flag properties read inverted.
    Tests: Zipline's, adapted to version 5, plus BigInt, template, class/closure and
    run-after-rewrite cases, and `RealBundleTest`, which takes real Kotlin/JS bundles
    (`-PziplineBytecodeCorpus=<dir>`) through a byte-identical round trip, the source map (checked
    instruction by instruction) and stripping, then loads them in dependency order.
  - `compile` (JNI `Context::compile`, iOS `QuickJs.compile`; step 1b) leaves out each function's
    source text, which QuickJS 2026 writes into the bytecode and 2021 did not: it was 83% of the
    three-way guest's bytecode (10.3 of 12.5 MB). It sets `JS_STRIP_SOURCE` only while compiling;
    `Function.prototype.toString` of a loaded function reads `[native code]`, as on 1.28.0.
  - Build files: `dtoa.c` (new in QuickJS 2025-04) in `build.zig`; `-fwrapv` in `build.zig`, the
    Android `CMakeLists.txt` and cklib's flags (QuickJS 2026's Makefile builds with it).
  - `QuickJs2026Test` (host tests, JVM and iOS): the version, the compiler accepting BigInt, the
    general `asUintN` and its RangeError at the size limit, BigInt → `Long` and its range, rope
    strings through `evaluate` and an inbound call, and (1b) bytecode without source text.
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
| `test` | runs Zipline's Gradle build in the checkout with the given tasks, e.g. `:zipline:jvmTest`; add `-PziplineForkLongAsBigInt` to link the JavaScript the tests load with `Long` as BigInt (`zipline-build.init.gradle`) |
| `publish <repo>` | publishes the forked `zipline-jvm` and `zipline-bytecode` as `1.28.0-q20260604` to a scratch Maven repository (never `~/.m2`) |
| `diff` | for maintainers: regenerates `patches/` from an edited checkout |
| `all` | `clone`, `engine`, `jni`, `qjs`, `check-bigint` |

`use-fork.init.gradle` points a Keliver build at the published fork without changing it (only
`zipline-jvm` and `zipline-bytecode` are replaced, in the build classpath too, so the Zipline Gradle
plugin compiles bundles with the new engine and rewrites their bytecode with the ported module):

```
./gradlew -I keliver-compose-gatea-profile/engine/zipline-fork/use-fork.init.gradle \
  -PziplineForkRepo=<repo> --no-daemon :keliver-compose-gatea-profile:profileQuickJs [-PprofileBigInt]
```

Source maps stay on, as Keliver builds (step 1 had to turn them off until `zipline-bytecode` was
ported; `-PziplineForkNoSourceMaps` still does). `-PziplineForkRepo=none` keeps Zipline 1.28.0: the
baseline to measure the fork against. Add `-PziplineBytecodeCorpus=<dir of .js and .js.map>` to
`apply.sh test <work> :zipline-bytecode:test` to run `RealBundleTest` on a real bundle, e.g.
`keliver-compose-gatea-profile/build/compileSync/js/main/productionExecutable/kotlin` after
`:keliver-compose-gatea-profile:compileProductionExecutableKotlinJs -Pkeliver.guestProfile`.

## Not in step 1

Android (the NDK AAR), iOS (cklib and Kotlin/Native), compiling each bundle for both engines,
the manifest naming the engine, hosts advertising theirs, the transition, and the upstream offer.
The plan and estimates are in `docs/GUEST_PROFILE.md`.
