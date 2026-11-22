#!/bin/bash
# NF2: Zipline 1.28.0 on Bellard's QuickJS 2026-06-04 with BigInt, kept as a patch set (step 1: the
# JVM; step 2: Android; step 3: iOS). See README.md next to this script. Nothing here pushes anything; `publish`
# writes only to the scratch Maven repository it is given.
#
#   apply.sh clone   <work>   clone Zipline at tag 1.28.0 into <work>/zipline (checks the commit)
#   apply.sh engine  <work>   download and verify QuickJS 2026-06-04, replace zipline/native/quickjs,
#                             apply patches/quickjs-*.patch to the engine and patches/zipline-*.patch
#   apply.sh jni     <work> [--warnings]   build the JVM JNI library for this Mac with clang (Zipline's
#                             CI uses zig; the flags follow zipline/build.zig) into
#                             zipline/src/jvmMain/resources/jni; --warnings builds with -Wall, not -w
#   apply.sh qjs     <work>   build the patched engine's command-line qjs twice: 64-bit BigInt limbs and
#                             32-bit limbs (as on a 32-bit host such as armeabi-v7a), for check-bigint
#   apply.sh check-bigint <work>   run ../bigint-ops.js in Node and both qjs builds; fail on any difference
#   apply.sh android <work>   build the Android AAR (zipline-android-ndk: Zipline's CMake and release
#                             flags, -fwrapv) for arm64-v8a, armeabi-v7a, x86_64 and x86 with the NDK, and
#                             check each ABI's libquickjs.so is in it, built with those flags, and (64-bit
#                             ABIs) every LOAD segment aligned for 16 KB pages (llvm-readelf from the NDK)
#   apply.sh qjs-android <work>   after android: build android/run-js.c on the fork's engine and on the
#                             unpatched release with the NDK, per ABI, with the AAR's compile line for
#                             quickjs.c (its target and every flag; for check-bigint-device)
#   apply.sh check-bigint-device <work> [serial]   run ../bigint-ops.js on a device or emulator over adb
#                             (serial, else ANDROID_SERIAL) for every ABI it supports; on a 32-bit ABI
#                             this exercises NaN-boxed values (JS_NAN_BOXING) and 32-bit limbs; both
#                             builds must start, and the fork's must exit 0
#   apply.sh ios     <work>   step 3: build the iOS targets (iosArm64, iosSimulatorArm64) with Zipline's own
#                             build: cklib's QuickJS bitcode (checks each compile line has Zipline's flags
#                             and -fwrapv), the patched nativeMain and quickjs.def, and the test executables
#   apply.sh check-bigint-zipline <work> [gradle args]   run ../bigint-ops.js through Zipline's own engine
#                             (BigIntOpsTest; default :zipline:jvmTest and :zipline:iosSimulatorArm64Test)
#                             and fail on any difference from Node
#   apply.sh test    <work> [gradle args]   Zipline's own build, e.g. :zipline:jvmTest,
#                             :zipline:connectedAndroidDeviceTest (its device tests, on ANDROID_SERIAL) or
#                             :zipline:iosSimulatorArm64Test (on a simulator the Kotlin plugin boots)
#   apply.sh results <work> [test task...]   sum the JUnit XML a test run left in zipline/build/test-results
#                             (e.g. iosSimulatorArm64Test; default jvmTest; <module>/<task> for another
#                             module, e.g. zipline-bytecode/test); fails if any test failed
#   apply.sh publish <work> <maven repo dir>   publish the forked artifacts (zipline-jvm, zipline-bytecode,
#                             zipline-android, zipline-android-ndk and, on a Mac with Xcode, the iOS
#                             klibs zipline-iosarm64 and zipline-iossimulatorarm64, each checked to
#                             carry the engine's bitcode) as 1.28.0-q20260604 to a scratch Maven
#                             repository (never ~/.m2)
#   apply.sh jni-all <work>   step 4: the JVM JNI library for every host upstream ships (Linux, macOS and
#                             Windows; arm64 and x86-64) with zig $ZIG_VERSION, as upstream's release does
#                             (zig on PATH at that version, else downloaded and checked into <work>/tools);
#                             replaces what `jni` built
#   apply.sh jni-from <work> <tested repo>   step 4, instead of jni-all: the JNI libraries from the
#                             zipline-jvm jar in a repository `release` wrote (the fork job's, which
#                             the JVM tests ran with on every host), into the source tree
#   apply.sh release <work> <empty maven repo dir> [--central <tested repo>]   step 4, after engine and
#                             jni-all (or jni-from): every Zipline artifact as
#                             dev.keliver.zipline:<upstream name>:1.28.0-keliver.1 (a Mac with Xcode),
#                             to the directory, checked by check-release.py and listed with sizes; with
#                             --central, signed, checked, its native libraries compared with the tested
#                             repository's (publish-checks.py natives: identical sha256s), then to Maven
#                             Central in review mode (the credentials as ORG_GRADLE_PROJECT_*
#                             variables) by a second invocation that may sign nothing again, keeping
#                             the bundle it uploaded as <work>/central-bundle.zip. CI only, by hand.
#                             No build cache. The POMs point at ZIPLINE_FORK_SOURCE_REF (the release
#                             tag; default main)
#   apply.sh sha1-manifest <work> <repo> <manifest out>   after release --central: the uploaded bundle
#                             is the checked directory <repo>, file for file (publish-checks.py sha1;
#                             maven-metadata.xml aside), its sha1 manifest, and the spot-check list
#                             as a Markdown table on stdout
#   apply.sh check-natives <work> <tested repo> <repo>   publish-checks.py natives on two repositories
#                             `release` wrote: every native library identical (sha256)
#   apply.sh check-release <work> <repo> [signed]   check-release.py again on what release wrote
#   apply.sh check-capabilities <work> <repo>   after release: a Gradle consumer (capability-check/)
#                             resolves the fork's modules alone, and fails, with a capability conflict,
#                             on the fork's and upstream's together
#   apply.sh check-upload-gate <work>   after clone: vanniktech's plugin, at the version Zipline's
#                             build uses, writes no bundle and opens no connection when a build that
#                             already ran its Maven Central publication then fails; and (the
#                             control) writes the bundle and connects when it does not. Every
#                             connection goes to a local listener, never to Central (upload-gate-check/)
#   apply.sh jvm-test-dist <work>   after jni-all: Zipline's JVM tests as a runnable distribution
#                             (zipline/build/install/jvmTest, bin/zipline-test), for every host
#   apply.sh diff    <work>   maintainers: regenerate patches/ from an edited checkout
#   apply.sh all     <work>   clone, engine, jni, qjs, check-bigint (no Android SDK needed)
set -euo pipefail
shopt -s nullglob
here="$(cd "$(dirname "$0")" && pwd)"
cmd="${1:?usage: apply.sh clone|engine|jni|jni-all|jni-from|qjs|check-bigint|android|qjs-android|check-bigint-device|ios|check-bigint-zipline|test|results|publish|release|check-release|check-capabilities|check-upload-gate|check-natives|sha1-manifest|jvm-test-dist|diff|all <work dir> [...]}"
work="${2:?work dir}"
mkdir -p "$work"
work="$(cd "$work" && pwd)"
zl="$work/zipline"

ZIPLINE_TAG=1.28.0
ZIPLINE_COMMIT=f6cd1536e25d37d18ed157db26a1ac136c3f4196
QJS_VERSION=2026-06-04
QJS_SHA256=b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a
# `publish` (a scratch repository for use-fork.init.gradle: only the changed modules, under
# app.cash.zipline) and `release` (step 4: every Zipline artifact, under Keliver's own group).
FORK_VERSION=1.28.0-q20260604
# The release's coordinates: the one place they are written (zipline-build.init.gradle gets the
# group as -PziplineForkRelease; use-release.init.gradle and the workflow read both from here).
RELEASE_GROUP=dev.keliver.zipline
RELEASE_VERSION=1.28.0-keliver.1
# The version of vanniktech's maven-publish plugin Zipline's build uploads with. What `release
# --central` relies on from it was read from this version's bytecode (the fork's README, "What the
# upload relies on"): the bundle zip is written right before the upload and never deleted, and a
# failed build writes no zip and uploads nothing. `release` refuses another version.
VANNIKTECH_VERSION=0.36.0
# The git ref the POMs and NOTICE point at: the release tag in CI (zipline-fork.yml), else main.
SOURCE_REF="${ZIPLINE_FORK_SOURCE_REF:-main}"
# patches/: one for the engine, one for Zipline.
PATCH_COUNT=2
# The JVM engine's JNI libraries for every host Zipline ships (zipline/build.zig: Linux, macOS and
# Windows, arm64 and x86-64), built as upstream's release workflow builds them, with this zig.
ZIG_VERSION=0.16.0
# shellcheck disable=SC2034 # read by name in zig_bin
ZIG_SHA256_aarch64_macos=b23d70deaa879b5c2d486ed3316f7eaa53e84acf6fc9cc747de152450d401489
ZIG_SHA256_x86_64_macos=0387557ed1877bc6a2e1802c8391953baddba76081876301c522f52977b52ba7
ZIG_SHA256_x86_64_linux=70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00
ZIG_SHA256_aarch64_linux=ea4b09bfb22ec6f6c6ceac57ab63efb6b46e17ab08d21f69f3a48b38e1534f17
# Android: an installed NDK (AGP would otherwise download its default into the shared SDK), and the
# ABIs zipline-android-ndk builds (its abiFilters). qjs-android takes the target (and Zipline's
# minSdk) from the AAR's own compile line.
NDK_VERSION="${KELIVER_NDK_VERSION:-28.0.12674087}"
ANDROID_ABIS="arm64-v8a armeabi-v7a x86_64 x86"
# The engine files Zipline vendors (its update_quickjs.sh), plus dtoa.[ch], new in QuickJS 2025-04.
QJS_FILES="cutils.c cutils.h dtoa.c dtoa.h libregexp-opcode.h libregexp.c libregexp.h libunicode-table.h
libunicode.c libunicode.h list.h quickjs-atom.h quickjs-opcode.h quickjs.c quickjs.h VERSION"

do_clone() {
  rm -rf "${zl:?}"
  git clone --quiet -c advice.detachedHead=false --depth 1 --branch "$ZIPLINE_TAG" \
    https://github.com/cashapp/zipline.git "$zl"
  got="$(git -C "$zl" rev-parse HEAD)"
  [ "$got" = "$ZIPLINE_COMMIT" ] || { echo "zipline $ZIPLINE_TAG is $got, expected $ZIPLINE_COMMIT" >&2; exit 1; }
  echo "zipline $ZIPLINE_TAG at $got"
}

engine_sources() { # leaves the verified, extracted tarball in $work/src/quickjs-$QJS_VERSION
  mkdir -p "$work/dl" "$work/src"
  local tb="$work/dl/quickjs-$QJS_VERSION.tar.xz"
  [ -f "$tb" ] || curl -fsS -m 300 -o "$tb" "https://bellard.org/quickjs/quickjs-$QJS_VERSION.tar.xz"
  got="$(shasum -a 256 "$tb" | awk '{print $1}')"
  # A tarball that fails the check is deleted, so the next run downloads it again.
  [ "$got" = "$QJS_SHA256" ] || { rm -f "$tb"; echo "quickjs-$QJS_VERSION.tar.xz: unexpected SHA-256 $got (deleted)" >&2; exit 1; }
  rm -rf "${work:?}/src/quickjs-$QJS_VERSION"
  tar -xJf "$tb" -C "$work/src"
}

do_engine() {
  [ "$(git -C "$zl" rev-parse HEAD)" = "$ZIPLINE_COMMIT" ] || { echo "run clone first" >&2; exit 1; }
  git -C "$zl" diff --quiet HEAD && [ -z "$(git -C "$zl" status --porcelain)" ] \
    || { echo "$zl has changes; run clone again for a clean tree" >&2; exit 1; }
  engine_sources
  local q="$zl/zipline/native/quickjs"
  rm -f "$q"/*
  for f in $QJS_FILES; do cp "$work/src/quickjs-$QJS_VERSION/$f" "$q/$f"; done
  # Exactly the patches the fork is (nullglob would skip a renamed one silently).
  local n=0
  for p in "$here"/patches/quickjs-*.patch; do patch -s -d "$q" -p1 < "$p"; echo "applied $(basename "$p")"; n=$((n + 1)); done
  for p in "$here"/patches/zipline-*.patch; do git -C "$zl" apply --whitespace=nowarn "$p"; echo "applied $(basename "$p")"; n=$((n + 1)); done
  [ "$n" = "$PATCH_COUNT" ] || { echo "engine: applied $n patches, expected $PATCH_COUNT (patches/)" >&2; exit 1; }
  echo "engine: QuickJS $(cat "$q/VERSION") in $q"
}

do_jni() {
  local warn="${3:-}"
  local n="$zl/zipline/native" arch out
  arch="$(uname -m)"; [ "$arch" = arm64 ] && arch=aarch64
  out="$zl/zipline/src/jvmMain/resources/jni/$arch"
  local obj="$work/build/jni-$arch"
  rm -rf "$obj"; mkdir -p "$obj" "$out"
  local java_home="${JAVA_HOME:?JAVA_HOME (JDK 17)}"
  local v; v="$(tr -d '\r\n' < "$n/quickjs/VERSION")"
  # zipline/build.zig: ReleaseSmall, -std=gnu99 / -std=c++11, CONFIG_VERSION, the bundled JNI headers.
  # QuickJS 2026's Makefile adds -fwrapv (signed overflow wraps) and -D_GNU_SOURCE; keep both.
  local cflags=(-Os -fPIC -fwrapv -D_GNU_SOURCE -DNDEBUG "-DCONFIG_VERSION=\"$v\""
    -I"$n/include/share" -I"$n/include/unix")
  # -w by default; --warnings shows -Wall's (QuickJS's Makefile level), for a review after changing
  # the engine or the JNI layer. Reviewed for step 1: docs/GUEST_PROFILE.md, "NF2".
  if [ "$warn" = --warnings ]; then cflags+=(-Wall); else cflags+=(-w); fi
  local c
  for c in common/context-no-eval.c common/finalization-registry.c common/global-gc.c \
      quickjs/cutils.c quickjs/dtoa.c quickjs/libregexp.c quickjs/libunicode.c quickjs/quickjs.c; do
    clang -std=gnu99 "${cflags[@]}" -c "$n/$c" -o "$obj/$(basename "$c" .c).o"
  done
  for c in Context.cpp ExceptionThrowers.cpp InboundCallChannel.cpp OutboundCallChannel.cpp quickjs-jni.cpp; do
    clang++ -std=c++11 "${cflags[@]}" -c "$n/$c" -o "$obj/$(basename "$c" .cpp).o"
  done
  clang++ -dynamiclib -o "$out/libquickjs.dylib" "$obj"/*.o
  echo "jni: $out/libquickjs.dylib ($(wc -c < "$out/libquickjs.dylib" | tr -d ' ') bytes, QuickJS $v)"
}

do_qjs() { # the forked engine's command-line qjs, from the same tarball and engine patches
  engine_sources
  local s="$work/src/quickjs-$QJS_VERSION"
  # The unpatched release too, as the control for check-bigint.
  rm -rf "${work:?}/src/unpatched"; cp -R "$s" "$work/src/unpatched"
  for p in "$here"/patches/quickjs-*.patch; do patch -s -d "$s" -p1 < "$p"; done
  local name extra src limbs
  for name in qjs64 qjs32limb qjs64-unpatched qjs32limb-unpatched; do
    # 32-bit limbs on this 64-bit Mac: quickjs.h picks 64-bit limbs only when __SIZEOF_INT128__ is defined.
    case "$name" in *32limb*) extra="-U__SIZEOF_INT128__" ;; *) extra="" ;; esac
    case "$name" in *unpatched) src="$work/src/unpatched" ;; *) src="$s" ;; esac
    limbs="$(printf '#include "quickjs.h"\nlimbs JS_LIMB_BITS\n' | clang -E $extra -I"$src" -x c - | awk '$1=="limbs"{print $2}')"
    rm -rf "${work:?}/build/$name"; cp -R "$src" "$work/build/$name"
    (cd "$work/build/$name" && make -j8 qjs CONFIG_LTO= CFLAGS_OPT="\$(CFLAGS) -O2 $extra" > build.log 2>&1) \
      || { tail -30 "$work/build/$name/build.log" >&2; exit 1; }
    echo "built $work/build/$name/qjs ($limbs-bit BigInt limbs)"
  done
}

do_check_bigint() {
  local ops="$here/../bigint-ops.js" out="$work/bigint-check"
  mkdir -p "$out"
  node "$ops" > "$out/node.txt"
  local name fail=0 lines
  lines="$(wc -l < "$out/node.txt" | tr -d ' ')"
  for name in qjs64 qjs32limb qjs64-unpatched qjs32limb-unpatched; do
    # The unpatched release may not finish every line (it can loop); give each run a bound.
    "$work/build/$name/qjs" "$ops" > "$out/$name.txt" 2>&1 &
    local pid=$! t=0
    while kill -0 "$pid" 2>/dev/null && [ $t -lt 60 ]; do sleep 1; t=$((t + 1)); done
    kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true
    if diff -u "$out/node.txt" "$out/$name.txt" > "$out/$name.diff"; then
      echo "check-bigint: $name matches Node on all $lines lines"
      case "$name" in *unpatched) echo "check-bigint: the control did not fail; the check is too weak" >&2; fail=1 ;; esac
    else
      echo "check-bigint: $name differs from Node on $(grep -c '^-[^-]' "$out/$name.diff") of $lines lines ($out/$name.diff)"
      case "$name" in *unpatched) ;; *) fail=1 ;; esac
    fi
  done
  return $fail
}

zipline_gradle() { # Zipline's own wrapper; an installed NDK instead of AGP's download (zipline-build.init.gradle)
  (cd "$zl" && ./gradlew --console=plain --no-daemon -I "$here/zipline-build.init.gradle" \
    "-PziplineForkNdkVersion=$NDK_VERSION" "-PziplineForkSourceRef=$SOURCE_REF" "$@")
}

sdk_dir() { echo "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"; }

ndk_bin() { # the NDK's LLVM toolchain for this host
  local d host
  d="$(sdk_dir)/ndk/$NDK_VERSION"
  [ -d "$d" ] || { echo "NDK $NDK_VERSION not found in $(sdk_dir)/ndk (set KELIVER_NDK_VERSION or ANDROID_HOME)" >&2; exit 1; }
  # The NDK ships x86_64 host tools only (Rosetta on Apple silicon).
  case "$(uname -s)" in Darwin) host=darwin-x86_64 ;; *) host=linux-x86_64 ;; esac
  echo "$d/toolchains/llvm/prebuilt/$host/bin"
}

aar_compile_line() { # <abi>: the AAR's compile line for quickjs.c, one argument per NUL-terminated field
  local cc
  cc="$(find "$zl/zipline-android-ndk/.cxx" -path "*/release/$1/compile_commands.json" | head -1)"
  [ -n "$cc" ] || { echo "$1: no release compile_commands.json under $zl/zipline-android-ndk/.cxx (run android first)" >&2; return 1; }
  python3 -I -c '
import json, shlex, sys
for e in json.load(open(sys.argv[1])):
    if e["file"].endswith("native/quickjs/quickjs.c"):
        sys.stdout.write("".join(a + "\0" for a in shlex.split(e["command"])))
        sys.exit(0)
sys.exit(sys.argv[1] + ": no compile line for quickjs.c")
' "$cc"
}

do_android() { # the AAR, from Zipline's own build (zipline-android-ndk's CMake and release flags)
  zipline_gradle :zipline-android-ndk:assembleRelease
  local aar="$zl/zipline-android-ndk/build/outputs/aar/zipline-android-ndk-release.aar"
  local bin; bin="$(ndk_bin)"
  local listing abi size line flag fail=0 so aligns a min tmp base base_listing base_size
  listing="$(unzip -l "$aar")"
  # The baseline for the sizes: 1.28.0's AAR from Maven Central, checked against its .sha1.
  local central="https://repo1.maven.org/maven2/app/cash/zipline/zipline-android-ndk/$ZIPLINE_TAG"
  base="$work/dl/zipline-android-ndk-$ZIPLINE_TAG.aar"
  mkdir -p "$work/dl"
  if [ ! -f "$base" ]; then
    curl -fsS -m 300 -o "$base.tmp" "$central/zipline-android-ndk-$ZIPLINE_TAG.aar" \
      && [ "$(curl -fsS -m 60 "$central/zipline-android-ndk-$ZIPLINE_TAG.aar.sha1" | awk '{print $1}')" \
           = "$(shasum -a 1 "$base.tmp" | awk '{print $1}')" ] \
      && mv "$base.tmp" "$base" || { rm -f "$base.tmp"; echo "android: NOTE 1.28.0's AAR not downloaded or failed its .sha1; no size comparison"; }
  fi
  base_listing=""; [ -f "$base" ] && base_listing="$(unzip -l "$base")"
  tmp="$(mktemp -d)"
  unzip -q -o "$aar" 'jni/*/libquickjs.so' -d "$tmp"
  for abi in $ANDROID_ABIS; do
    size="$(awk -v f="jni/$abi/libquickjs.so" '$4 == f {print $1}' <<<"$listing")"
    [ -n "$size" ] || { echo "android: $aar has no jni/$abi/libquickjs.so" >&2; fail=1; continue; }
    # The engine's own compile line for this ABI: Zipline's release flags, and -fwrapv (QuickJS 2026's
    # Makefile builds with it; the patch adds it to the CMakeLists).
    line="$(aar_compile_line "$abi" | tr '\0' ' ')" || true
    [ -n "$line" ] || { echo "android: $abi: no compile line for quickjs.c" >&2; fail=1; continue; }
    for flag in -fwrapv -Os -DNDEBUG -fvisibility=hidden -fstrict-aliasing -fomit-frame-pointer -g0 \
        "-DCONFIG_VERSION=\"$QJS_VERSION\""; do
      grep -qF -- " $flag " <<<" $line " || { echo "android: $abi: quickjs.c not built with $flag" >&2; fail=1; }
    done
    # 16 KB pages (Android 15+ devices with 16 KB pages, and Play, require it of 64-bit libraries):
    # every LOAD segment of the library in the AAR aligned to at least 0x4000. Zipline's
    # CMakeLists links with -Wl,-z,max-page-size=16384; this checks the result, not the flag.
    so="$tmp/jni/$abi/libquickjs.so"
    aligns="$("$bin/llvm-readelf" -lW "$so" | awk '$1 == "LOAD" {print $NF}' | sort -u | tr '\n' ' ')"
    min=""
    for a in $aligns; do
      if [ -z "$min" ] || [ $((a)) -lt $((min)) ]; then min="$a"; fi
    done
    if [ -z "$min" ]; then
      echo "android: $abi: no LOAD segments in libquickjs.so" >&2; fail=1
    elif [ $((min)) -lt $((0x4000)) ]; then
      case "$abi" in
        arm64-v8a|x86_64) echo "android: $abi: libquickjs.so LOAD alignment $aligns, below 16 KB (0x4000)" >&2; fail=1 ;;
        *) echo "android: $abi: NOTE libquickjs.so LOAD alignment $aligns, below 16 KB (no 32-bit device has 16 KB pages)" ;;
      esac
    fi
    base_size="$(awk -v f="jni/$abi/libquickjs.so" '$4 == f {print $1}' <<<"$base_listing")"
    [ -n "$base_size" ] && base_size=", $ZIPLINE_TAG's $base_size ($(awk -v a="$size" -v b="$base_size" 'BEGIN {printf "%+.1f%%", (a - b) * 100 / b}'))"
    echo "android: $abi libquickjs.so $size bytes$base_size, LOAD align ${aligns% } (quickjs.c: -Os -DNDEBUG -fwrapv)"
  done
  rm -rf "$tmp"
  [ $fail = 0 ] && echo "android: $aar"
  return $fail
}

do_qjs_android() { # android/run-js.c on the fork's engine (as in the AAR) and on the unpatched release
  local bin; bin="$(ndk_bin)"
  engine_sources # a fresh, unpatched $work/src/quickjs-$QJS_VERSION: the control
  local patched="$zl/zipline/native/quickjs" unpatched="$work/src/quickjs-$QJS_VERSION"
  [ -f "$patched/dtoa.c" ] || { echo "run engine first" >&2; exit 1; }
  local abi src name out a skip
  for abi in $ANDROID_ABIS; do
    # The AAR's own compile line for quickjs.c on this ABI (run `android` first): its target, sysroot
    # and every flag (Zipline's release flags, the NDK's defaults such as -mthumb on armeabi-v7a and
    # -fstack-protector-strong, and -fwrapv), as an executable: without the compiler, the source,
    # -o/-c, -fPIC and the shared library's -Dquickjs_EXPORTS; with -fPIE -pie, and -w.
    local flags=() line=()
    while IFS= read -r -d '' a; do line+=("$a"); done < <(aar_compile_line "$abi")
    [ ${#line[@]} -gt 0 ] || { echo "qjs-android: $abi: no AAR compile line (run android first)" >&2; exit 1; }
    skip=1 # the compiler
    for a in "${line[@]}"; do
      if [ $skip = 1 ]; then skip=0; continue; fi
      case "$a" in
        -o|-c) skip=1 ;;
        -fPIC|-Dquickjs_EXPORTS) ;;
        *) flags+=("$a") ;;
      esac
    done
    for name in run-js run-js-unpatched; do
      case "$name" in *unpatched) src="$unpatched" ;; *) src="$patched" ;; esac
      out="$work/build/android/$abi/$name"
      mkdir -p "$(dirname "$out")"
      "$bin/clang" "${flags[@]}" -fPIE -pie -w -I"$src" "$here/android/run-js.c" \
        "$src/cutils.c" "$src/dtoa.c" "$src/libregexp.c" "$src/libunicode.c" "$src/quickjs.c" -lm -o "$out"
    done
    echo "qjs-android: $abi: $work/build/android/$abi/{run-js,run-js-unpatched} (${flags[*]})"
  done
}

# check-bigint-device's device and directory, global for the EXIT trap that deletes the directory.
dev_adb=()
dev_dir=/data/local/tmp/keliver-nf2-bigint
dev_cleanup() { [ ${#dev_adb[@]} -gt 0 ] && "${dev_adb[@]}" shell "rm -rf $dev_dir" >/dev/null 2>&1 || true; }

do_check_bigint_device() {
  local serial="${3:-${ANDROID_SERIAL:-}}"
  [ -n "$serial" ] || { echo "check-bigint-device: give a serial (or set ANDROID_SERIAL)" >&2; exit 2; }
  local adb_bin; adb_bin="$(sdk_dir)/platform-tools/adb"; [ -x "$adb_bin" ] || adb_bin=adb
  dev_adb=("$adb_bin" -s "$serial")
  local adb=("${dev_adb[@]}") dir="$dev_dir"
  local ops="$here/../bigint-ops.js" out="$work/bigint-check/device-$serial"
  mkdir -p "$out"
  node "$ops" > "$out/node.txt"
  local lines abis abi name fail=0 ran=0 ran32=0 header rc
  lines="$(wc -l < "$out/node.txt" | tr -d ' ')"
  abis="$("${adb[@]}" shell getprop ro.product.cpu.abilist | tr -d '\r')"
  echo "check-bigint-device: $serial supports $abis"
  trap dev_cleanup EXIT
  "${adb[@]}" shell "rm -rf $dir && mkdir -p $dir" >/dev/null
  "${adb[@]}" push "$ops" "$dir/bigint-ops.js" >/dev/null # a failed push stops the script
  for abi in $ANDROID_ABIS; do
    case ",$abis," in *",$abi,"*) ;; *) echo "check-bigint-device: $abi: not supported by $serial, not run"; continue ;; esac
    for name in run-js run-js-unpatched; do
      [ -f "$work/build/android/$abi/$name" ] || { echo "run qjs-android first" >&2; exit 1; }
      "${adb[@]}" push "$work/build/android/$abi/$name" "$dir/$abi-$name" >/dev/null
      # The unpatched release may loop on some lines (as on the Mac): bound each run. The exit status
      # goes to a file (an adb without the shell protocol does not return it).
      "${adb[@]}" shell "cd $dir && chmod 755 $abi-$name && timeout 120 ./$abi-$name bigint-ops.js 2> $abi-$name.err; echo \$? > $abi-$name.rc" \
        > "$out/$abi-$name.txt" || true
      header="$("${adb[@]}" shell cat "$dir/$abi-$name.err" 2>/dev/null | tr -d '\r' | head -1)" || true
      rc="$("${adb[@]}" shell cat "$dir/$abi-$name.rc" 2>/dev/null | tr -d '\r\n')" || true
      tr -d '\r' < "$out/$abi-$name.txt" > "$out/$abi-$name.tmp" && mv "$out/$abi-$name.tmp" "$out/$abi-$name.txt"
      # Both builds must have started (the header run-js.c prints first), or the control proves nothing.
      case "$header" in
        run-js:*) ;;
        *) echo "check-bigint-device: $abi $name did not start (exit ${rc:-?}: ${header:-no output})" >&2; fail=1; continue ;;
      esac
      if diff -u "$out/node.txt" "$out/$abi-$name.txt" > "$out/$abi-$name.diff"; then
        echo "check-bigint-device: $abi $name matches Node on all $lines lines, exit $rc ($header)"
        case "$name" in *unpatched) echo "check-bigint-device: the control did not fail; the check is too weak" >&2; fail=1 ;; esac
      else
        echo "check-bigint-device: $abi $name differs from Node on $(grep -c '^-[^-]' "$out/$abi-$name.diff") of $lines lines, exit $rc ($header)"
        case "$name" in *unpatched) ;; *) fail=1 ;; esac
      fi
      # The fork's build must also exit cleanly (an exception after the last line would not show in the diff).
      case "$name" in *unpatched) ;; *) [ "$rc" = 0 ] || { echo "check-bigint-device: $abi $name exited ${rc:-?}" >&2; fail=1; } ;; esac
    done
    ran=$((ran + 1))
    case "$abi" in armeabi-v7a|x86) ran32=$((ran32 + 1)) ;; esac
  done
  dev_cleanup
  trap - EXIT
  [ $ran -gt 0 ] || { echo "check-bigint-device: no ABI ran" >&2; fail=1; }
  [ $ran32 -gt 0 ] || echo "check-bigint-device: NOTE no 32-bit ABI on $serial: JS_NAN_BOXING not exercised (needs an armeabi-v7a or x86 image or device)"
  return $fail
}

IOS_TARGETS="IosArm64 IosSimulatorArm64"

do_ios() { # step 3: the iOS targets, from Zipline's own build (cklib and Kotlin/Native)
  [ -f "$zl/zipline/native/quickjs/dtoa.c" ] || { echo "run engine first" >&2; exit 1; }
  local log="$work/build/ios.log" t lc fail=0 tasks=() flag n
  mkdir -p "$work/build"
  # cklib's QuickJS bitcode (rerun, so the compile lines are in the log), the patched nativeMain and
  # quickjs.def through cinterop, and the test executables linked for the phone and the simulator.
  for t in $IOS_TARGETS; do
    lc="$(tr '[:upper:]' '[:lower:]' <<<"${t:0:1}")${t:1}"
    tasks+=(":zipline:${lc}Quickjs" --rerun ":zipline:compileKotlin$t" ":zipline:linkDebugTest$t")
  done
  zipline_gradle --info "${tasks[@]}" > "$log" 2>&1 || { grep -E '^e: |error:|What went wrong' -A3 "$log" | head -40 >&2; echo "ios: build failed ($log)" >&2; exit 1; }
  # cklib compiles the engine in one clang call per target (-target arm64-apple-ios<min> for the
  # phone, ...-simulator for the simulator): Zipline's flags (its cklib compilerArgs) and -fwrapv.
  local line target
  for t in device simulator; do
    case "$t" in
      device) target='-target arm64-apple-ios[0-9.]+ ' ;;
      simulator) target='-target arm64-apple-ios[0-9.]+-simulator ' ;;
    esac
    line="$(grep -E "Command: .*/clang -c .*native/quickjs/quickjs\.c" "$log" | grep -E -- "$target" | head -1)" || true
    [ -n "$line" ] || { echo "ios: $t: no compile line for quickjs.c in $log" >&2; fail=1; continue; }
    for flag in -fwrapv -DKONAN_MI_MALLOC=1 "-DCONFIG_VERSION=\"$QJS_VERSION\"" -ftls-model=initial-exec -D_Float16=short \
        native/quickjs/dtoa.c; do
      grep -qF -- "$flag" <<<"$line" || { echo "ios: $t: quickjs.c not built with $flag" >&2; fail=1; }
    done
    echo "ios: $t ($(grep -oE -- "$target" <<<"$line" | cut -d' ' -f2)): QuickJS built with Zipline's cklib flags and -fwrapv, $(grep -oE -- ' -O[0-9sz]? ' <<<"$line" | tr -d ' ' | sort -u | tr '\n' ' ')(dtoa.c included)"
  done
  for t in $IOS_TARGETS; do
    lc="$(tr '[:upper:]' '[:lower:]' <<<"${t:0:1}")${t:1}"
    local kexe="$zl/zipline/build/bin/$lc/debugTest/test.kexe"
    [ -f "$kexe" ] || { echo "ios: no $kexe" >&2; fail=1; continue; }
    echo "ios: $lc: test executable $(wc -c < "$kexe" | tr -d ' ') bytes ($(file -b "$kexe" | cut -d, -f1))"
  done
  [ $fail = 0 ] && echo "ios: the fork's iOS targets build ($log)"
  return $fail
}

do_check_bigint_zipline() { # ../bigint-ops.js through Zipline's own engine (BigIntOpsTest), per host
  shift 2 || true
  local dir="$zl/build/keliver-bigint" lines fail=0 name ran=0
  rm -rf "$dir"; mkdir -p "$dir"
  cp "$here/../bigint-ops.js" "$dir/bigint-ops.js"
  node "$here/../bigint-ops.js" > "$dir/node.txt"
  lines="$(wc -l < "$dir/node.txt" | tr -d ' ')"
  # Default: the JVM and the iOS simulator. Give other test tasks (and -PziplineForkLongAsBigInt) after.
  [ $# -gt 0 ] || set -- :zipline:jvmTest :zipline:iosSimulatorArm64Test
  # Each test task reruns (the script and Node's output are read at run time, not task inputs).
  local args=() a
  for a in "$@"; do
    args+=("$a")
    case "$a" in :*Test) args+=(--tests app.cash.zipline.BigIntOpsTest --rerun) ;; esac
  done
  zipline_gradle "${args[@]}" || fail=1
  for name in zipline-jni zipline-native; do
    [ -f "$dir/$name.txt" ] || continue
    ran=$((ran + 1))
    if diff -u "$dir/node.txt" "$dir/$name.txt" > "$dir/$name.diff"; then
      echo "check-bigint-zipline: $name matches Node on all $lines lines"
    else
      echo "check-bigint-zipline: $name differs from Node on $(grep -c '^-[^-]' "$dir/$name.diff") of $lines lines ($dir/$name.diff)" >&2
      fail=1
    fi
  done
  [ $ran -gt 0 ] || { echo "check-bigint-zipline: BigIntOpsTest wrote no output (did it run?)" >&2; fail=1; }
  return $fail
}

do_test() {
  shift 2 || true
  zipline_gradle "$@"
}

do_results() { # <test task>...: the JUnit XML a test run left, summed (the count of record)
  shift 2 || true
  [ $# -gt 0 ] || set -- jvmTest
  local t d fail=0
  for t in "$@"; do
    # <module>/<task> for another module; a bare task is :zipline's.
    if [ "${t#*/}" != "$t" ]; then d="$zl/${t%%/*}/build/test-results/${t#*/}"; else d="$zl/zipline/build/test-results/$t"; fi
    python3 -I -c '
import glob, sys, xml.etree.ElementTree as ET
d = sys.argv[1]
files = sorted(glob.glob(d + "/*.xml"))
if not files:
    sys.exit("results: no JUnit XML in " + d)
n = t = s = f = e = 0
bad = []
for p in files:
    su = ET.parse(p).getroot()
    n += 1
    t += int(su.get("tests", 0)); s += int(su.get("skipped", 0))
    f += int(su.get("failures", 0)); e += int(su.get("errors", 0))
    for tc in su.iter("testcase"):
        for k in ("failure", "error"):
            if tc.find(k) is not None:
                bad.append("%s.%s" % (tc.get("classname"), tc.get("name")))
print("results: %s: %d classes, %d run, %d passed, %d skipped, %d failed" % (d.split("/")[-1], n, t, t - s - f - e, s, f + e))
for b in bad:
    print("results:   failed: " + b)
sys.exit(1 if f + e else 0)
' "$d" || fail=1
  done
  return $fail
}

do_publish() {
  local repo="${3:?maven repo dir}"
  mkdir -p "$repo"; repo="$(cd "$repo" && pwd)"
  case "$repo" in "$HOME/.m2"*) echo "refusing ~/.m2" >&2; exit 2 ;; esac
  # zipline-jvm (the engine the Gradle plugin compiles bundles with), zipline-bytecode (step 1b: the
  # compiler's source maps and line stripping read and write version 5) and, for Android hosts (step
  # 2), zipline-android (its JNI classes) and zipline-android-ndk (the AAR with libquickjs.so). Every
  # other Zipline module stays 1.28.0; use-fork.init.gradle substitutes these four. On a Mac with
  # Xcode (step 3), also zipline-iosarm64 and zipline-iossimulatorarm64: the klibs, which carry the
  # engine's bitcode from cklib.
  local ios=()
  if [ "$(uname -s)" = Darwin ] && xcrun --sdk iphoneos --show-sdk-path > /dev/null 2>&1; then
    ios=(:zipline:publishIosArm64PublicationToMavenLocal :zipline:publishIosSimulatorArm64PublicationToMavenLocal)
  else
    echo "publish: NOTE no Xcode iOS SDK here; the iOS klibs are not published"
  fi
  zipline_gradle -PVERSION_NAME="$FORK_VERSION" "-Dmaven.repo.local=$repo" -PRELEASE_SIGNING_ENABLED=false \
    :zipline:publishJvmPublicationToMavenLocal :zipline-bytecode:publishMavenPublicationToMavenLocal \
    :zipline:publishAndroidPublicationToMavenLocal :zipline-android-ndk:publishMavenPublicationToMavenLocal \
    ${ios[@]+"${ios[@]}"}
  find "$repo/app/cash/zipline" \( -name "*.jar" -o -name "*.aar" -o -name "*.klib" \) | sort
  # A klib without the engine's bitcode would link against nothing: check each one carries it.
  # The listing is captured first: `unzip | grep -q` under pipefail fails when grep exits on the
  # first match and unzip gets SIGPIPE.
  local k bitcode
  for k in $(find "$repo/app/cash/zipline" -name "zipline-ios*-$FORK_VERSION.klib" | sort); do
    bitcode="$(unzip -Z1 "$k" | grep -E 'native/.*quickjs.*\.bc$' || true)"
    [ -n "$bitcode" ] || { echo "publish: $(basename "$k") has no QuickJS bitcode" >&2; return 1; }
    echo "publish: $(basename "$k"): $(tr '\n' ' ' <<<"$bitcode")"
  done
}

zig_bin() { # zig $ZIG_VERSION: $ZIG or the one on PATH when it is that version, else downloaded (checked)
  local z="${ZIG:-}" os arch var sha d tb got
  [ -n "$z" ] || z="$(command -v zig || true)"
  if [ -n "$z" ] && [ "$("$z" version 2>/dev/null)" = "$ZIG_VERSION" ]; then echo "$z"; return 0; fi
  case "$(uname -s)" in Darwin) os=macos ;; Linux) os=linux ;; *) echo "zig: no pinned zig for $(uname -s)" >&2; return 1 ;; esac
  arch="$(uname -m)"; [ "$arch" = arm64 ] && arch=aarch64
  var="ZIG_SHA256_${arch}_${os}"; sha="${!var:-}"
  [ -n "$sha" ] || { echo "zig: no pinned zig for $arch-$os" >&2; return 1; }
  d="$work/tools/zig-$arch-$os-$ZIG_VERSION"
  if [ ! -x "$d/zig" ]; then
    mkdir -p "$work/dl" "$work/tools"
    tb="$work/dl/zig-$arch-$os-$ZIG_VERSION.tar.xz"
    [ -f "$tb" ] || curl -fsS -m 900 -o "$tb" "https://ziglang.org/download/$ZIG_VERSION/zig-$arch-$os-$ZIG_VERSION.tar.xz" >&2
    got="$(shasum -a 256 "$tb" | awk '{print $1}')"
    [ "$got" = "$sha" ] || { rm -f "$tb"; echo "zig: $tb: unexpected SHA-256 $got (deleted)" >&2; return 1; }
    rm -rf "$d"; tar -xJf "$tb" -C "$work/tools"
  fi
  echo "$d/zig"
}

do_jni_all() { # step 4: the JNI library for every host upstream ships, as upstream's release builds them
  [ -f "$zl/zipline/native/quickjs/dtoa.c" ] || { echo "run engine first" >&2; exit 1; }
  local z out="$zl/zipline/src/jvmMain/resources/jni" f n=0
  z="$(zig_bin)" || exit 1
  # `jni` (clang, this Mac only) may have left a library here; the release carries zig's set only.
  rm -rf "$out"
  (cd "$zl/zipline" && ZIG_GLOBAL_CACHE_DIR="$work/build/zig-global-cache" ZIG_LOCAL_CACHE_DIR="$work/build/zig-cache" \
    "$z" build -p src/jvmMain/resources/jni)
  while IFS= read -r f; do
    # Each library is the fork's engine: QuickJS's version string is compiled into it.
    LC_ALL=C grep -q -a -F "$QJS_VERSION" "$f" || { echo "jni-all: ${f#"$out"/} has no QuickJS $QJS_VERSION" >&2; exit 1; }
    echo "jni-all: ${f#"$out"/} $(wc -c < "$f" | tr -d ' ') bytes (QuickJS $QJS_VERSION; $(file -b "$f" | cut -d, -f1-2))"
    n=$((n + 1))
  done < <(find "$out" -type f \( -name '*.so' -o -name '*.dylib' -o -name '*.dll' \) | sort)
  [ $n -gt 0 ] || { echo "jni-all: zig built no library" >&2; exit 1; }
}

do_jni_from() { # step 4: the JNI libraries the tests ran with (a tested release's zipline-jvm jar), not rebuilt
  local tested="${3:?jni-from <work> <tested repo>}" jar out="$zl/zipline/src/jvmMain/resources/jni" f n=0
  [ -f "$zl/zipline/native/quickjs/dtoa.c" ] || { echo "run engine first" >&2; exit 1; }
  jar="$tested/${RELEASE_GROUP//.//}/zipline-jvm/$RELEASE_VERSION/zipline-jvm-$RELEASE_VERSION.jar"
  [ -f "$jar" ] || { echo "jni-from: no $jar" >&2; exit 1; }
  # Exactly the jar's jni/ entries (what `jni` may have left here goes first).
  rm -rf "$out"
  unzip -q "$jar" 'jni/*' -d "$zl/zipline/src/jvmMain/resources"
  while IFS= read -r f; do
    LC_ALL=C grep -q -a -F "$QJS_VERSION" "$f" || { echo "jni-from: ${f#"$out"/} has no QuickJS $QJS_VERSION" >&2; exit 1; }
    echo "jni-from: ${f#"$out"/} $(wc -c < "$f" | tr -d ' ') bytes, sha256 $(shasum -a 256 "$f" | cut -c1-16)... (from the tested zipline-jvm jar)"
    n=$((n + 1))
  done < <(find "$out" -type f \( -name '*.so' -o -name '*.dylib' -o -name '*.dll' \) | sort)
  [ $n = 6 ] || { echo "jni-from: $n libraries in $jar, expected 6" >&2; exit 1; }
}

do_sha1_manifest() { # <repo> <out>: the uploaded bundle against the checked directory, its manifest, the spot-check list
  local repo="${3:?sha1-manifest <work> <checked repo> <manifest out>}" out="${4:?manifest out}"
  [ -f "$work/central-bundle.zip" ] || { echo "sha1-manifest: no $work/central-bundle.zip (release --central keeps it)" >&2; exit 1; }
  python3 -I "$here/publish-checks.py" sha1 "$work/central-bundle.zip" "$repo" "$RELEASE_GROUP" "$RELEASE_VERSION" "$out"
}

central_file() { # <artifact> <file name>: upstream's file at $ZIPLINE_TAG into $work/dl, checked against its .sha1
  local url="https://repo1.maven.org/maven2/app/cash/zipline/$1/$ZIPLINE_TAG/$2" f="$work/dl/$2"
  mkdir -p "$work/dl"
  [ -f "$f" ] && return 0
  curl -fsS -m 600 -o "$f.tmp" "$url"
  [ "$(curl -fsS -m 60 "$url.sha1" | awk '{print $1}')" = "$(shasum -a 1 "$f.tmp" | awk '{print $1}')" ] \
    || { rm -f "$f.tmp"; echo "$2 from Maven Central failed its .sha1" >&2; exit 1; }
  mv "$f.tmp" "$f"
}

upstream_central() { # into $work/dl: the names upstream publishes at $ZIPLINE_TAG, its zipline-jvm jar, AAR and plugin jar
  local base="https://repo1.maven.org/maven2/app/cash/zipline" a
  mkdir -p "$work/dl"
  if [ ! -s "$work/dl/upstream-$ZIPLINE_TAG-artifacts.txt" ]; then
    : > "$work/dl/upstream-artifacts.tmp"
    # Central's directory listing names every artifact the group ever had; keep those at the tag.
    for a in $(curl -fsS -m 120 "$base/" | grep -oE 'href="[A-Za-z0-9._-]+/"' | sed -E 's/href="(.*)\/"/\1/' | sort -u); do
      if curl -fsS -m 60 -o /dev/null -I "$base/$a/$ZIPLINE_TAG/$a-$ZIPLINE_TAG.pom"; then echo "$a" >> "$work/dl/upstream-artifacts.tmp"; fi
    done
    [ -s "$work/dl/upstream-artifacts.tmp" ] || { echo "release: no upstream artifact list from $base" >&2; exit 1; }
    mv "$work/dl/upstream-artifacts.tmp" "$work/dl/upstream-$ZIPLINE_TAG-artifacts.txt"
  fi
  central_file zipline-jvm "zipline-jvm-$ZIPLINE_TAG.jar"
  central_file zipline-android-ndk "zipline-android-ndk-$ZIPLINE_TAG.aar"
  central_file zipline-gradle-plugin "zipline-gradle-plugin-$ZIPLINE_TAG.jar"
}

check_release() { # <repo> [signed]: check-release.py on what release published there (and the listing)
  upstream_central
  # Upstream's main jar for every artifact the release has a main jar of, beside the others in
  # $work/dl, for check-release.py's class-file versions (review 3 of PR #289, L-b).
  local j a
  for j in "$1/${RELEASE_GROUP//.//}"/*/"$RELEASE_VERSION"/*-"$RELEASE_VERSION".jar; do
    a="$(basename "$(dirname "$(dirname "$j")")")"
    [ "$(basename "$j")" = "$a-$RELEASE_VERSION.jar" ] || continue
    grep -qxF "$a" "$work/dl/upstream-$ZIPLINE_TAG-artifacts.txt" || continue
    central_file "$a" "$a-$ZIPLINE_TAG.jar"
  done
  python3 -I "$here/check-release.py" "$1" "$RELEASE_GROUP" "$RELEASE_VERSION" "$ZIPLINE_TAG" \
    "$work/dl/upstream-$ZIPLINE_TAG-artifacts.txt" "$work/dl/zipline-jvm-$ZIPLINE_TAG.jar" \
    "$work/dl/zipline-android-ndk-$ZIPLINE_TAG.aar" "$work/dl/zipline-gradle-plugin-$ZIPLINE_TAG.jar" \
    "$SOURCE_REF" "${2:-unsigned}"
}

do_check_capabilities() { # <repo>: a pure fork classpath resolves; one mixed with upstream fails
  local repo="${3:?check-capabilities <work> <repo release wrote>}" d="$work/capability-check"
  repo="$(cd "$repo" && pwd)"
  # A copy of the check project in the work dir, so Gradle's files stay out of the repository.
  rm -rf "$d"; mkdir -p "$d"; cp "$here"/capability-check/*.gradle "$d/"
  # Zipline's own wrapper (its Gradle version); no init script: the check is a plain consumer.
  (cd "$d" && "$zl/gradlew" --console=plain --no-daemon -p "$d" "-PziplineReleaseRepo=$repo" \
    "-PziplineReleaseGroup=$RELEASE_GROUP" "-PziplineReleaseVersion=$RELEASE_VERSION" \
    "-PziplineUpstreamVersion=$ZIPLINE_TAG" checkCapabilities)
}

vanniktech_pinned() { # Zipline's build uses $VANNIKTECH_VERSION, the version whose behaviour the upload relies on
  local toml="$zl/gradle/libs.versions.toml"
  [ -f "$toml" ] || { echo "no $toml (run clone first)" >&2; exit 1; }
  grep -qF "module = \"com.vanniktech:gradle-maven-publish-plugin\", version = \"$VANNIKTECH_VERSION\"" "$toml" \
    || { echo "Zipline's build does not use vanniktech's maven-publish plugin $VANNIKTECH_VERSION ($toml): what the upload" \
              "relies on was read from that version; read the new one's MavenCentralBuildService, run check-upload-gate," \
              "then change VANNIKTECH_VERSION" >&2; exit 1; }
}

do_check_upload_gate() { # review 4 of PR #289, N-2: upload-gate-check/, against a local listener
  vanniktech_pinned
  local d="$work/upload-gate-check" port_file log out listener port n
  rm -rf "$d"; mkdir -p "$d"; cp "$here"/upload-gate-check/*.gradle "$d/"
  port_file="$d/listener.port"; log="$d/listener.log"; : > "$log"
  # The listener: records each connection's request line, then closes it (so the upload fails).
  python3 -I -c '
import os, socket, sys
s = socket.socket()
s.bind(("127.0.0.1", 0))
s.listen(16)
with open(sys.argv[1] + ".tmp", "w") as f:
    f.write(str(s.getsockname()[1]))
os.rename(sys.argv[1] + ".tmp", sys.argv[1])
while True:
    c, _ = s.accept()
    c.settimeout(5)
    try:
        line = c.recv(4096).split(b"\r\n", 1)[0].decode("latin-1") or "(empty)"
    except OSError:
        line = "(no request line)"
    with open(sys.argv[2], "a") as f:
        f.write(line + "\n")
    c.close()
' "$port_file" "$log" &
  listener=$!
  # shellcheck disable=SC2064 # expand now: the PID is known here
  trap "kill $listener 2>/dev/null || true" EXIT
  n=0; while [ ! -s "$port_file" ] && [ $n -lt 50 ]; do sleep 0.1; n=$((n + 1)); done
  [ -s "$port_file" ] || { echo "check-upload-gate: the listener did not start" >&2; exit 1; }
  port="$(cat "$port_file")"
  local fail=0 status zips run
  for run in failing control; do
    rm -rf "$d/build" "$d/.gradle"; : > "$log"
    local fail_arg=(); [ $run = failing ] && fail_arg=(-PuploadGateFail)
    status=0
    out="$(cd "$d" && "$zl/gradlew" --console=plain --no-daemon --no-build-cache --no-configuration-cache -p "$d" \
      -PmavenCentralUsername=upload-gate-check -PmavenCentralPassword=not-a-secret "-PuploadGateProxyPort=$port" \
      "-PvanniktechVersion=$VANNIKTECH_VERSION" ${fail_arg[@]+"${fail_arg[@]}"} publishToMavenCentral boom 2>&1)" || status=$?
    echo "$out" > "$d/$run.log"
    zips="$( (find "$d/build/publish" -name '*.zip' 2>/dev/null || true) | wc -l | tr -d ' ')"
    # The publication ran (the plugin registered the project for the upload) in both runs.
    if ! grep -q '^> Task :publishMavenPublicationToMavenCentralRepository$' <<<"$out"; then
      echo "check-upload-gate: $run: the Maven Central publication did not run ($d/$run.log)" >&2; fail=1; continue
    fi
    if [ $run = failing ]; then
      if [ $status = 0 ] || [ "$zips" != 0 ] || [ -s "$log" ]; then
        echo "check-upload-gate: failing: exit $status, $zips bundle zip(s), connections: $(tr '\n' ' ' < "$log")" \
          "(expected a failed build, no zip, no connection; $d/$run.log)" >&2; fail=1
      else
        echo "check-upload-gate: failing: the build failed after the publication ran; no bundle zip, no connection"
      fi
    else
      if [ "$zips" != 1 ] || ! grep -q '^CONNECT central\.sonatype\.com:443 ' "$log"; then
        echo "check-upload-gate: control: exit $status, $zips bundle zip(s), connections: $(tr '\n' ' ' < "$log")" \
          "(expected one zip and a CONNECT to central.sonatype.com:443, at the listener; $d/$run.log)" >&2; fail=1
      else
        echo "check-upload-gate: control: the bundle zip was written ($(find "$d/build/publish" -name '*.zip' -exec basename {} \;))," \
          "the plugin connected ($(head -1 "$log"), to the listener), the upload failed (exit $status), and the zip is still there"
      fi
    fi
  done
  [ $fail = 0 ] || exit 1
  echo "check-upload-gate: OK (vanniktech's maven-publish plugin $VANNIKTECH_VERSION)"
}

do_jvm_test_dist() { # step 4: Zipline's JVM tests as a distribution, run on every host (zipline-fork.yml)
  [ -n "$(find "$zl/zipline/src/jvmMain/resources/jni" -name '*.dll' 2>/dev/null)" ] \
    || { echo "jvm-test-dist: run jni-all first (the distribution carries every host's engine)" >&2; exit 1; }
  zipline_gradle :zipline:installJvmTestDistribution
  local dist="$zl/zipline/build/install/jvmTest"
  [ -x "$dist/bin/zipline-test" ] || { echo "jvm-test-dist: no $dist/bin/zipline-test" >&2; exit 1; }
  # The Windows legs of jvm-matrix run the .bat (review 3 of PR #289, L-c): fail here, not after fork.
  [ -f "$dist/bin/zipline-test.bat" ] || { echo "jvm-test-dist: no $dist/bin/zipline-test.bat (the Windows launcher)" >&2; exit 1; }
  echo "jvm-test-dist: $dist (run bin/zipline-test, or bin\\zipline-test.bat on Windows, on each host)"
}

do_release() { # step 4: every Zipline artifact as $RELEASE_GROUP:<name>:$RELEASE_VERSION
  local repo="${3:?release <work> <empty maven repo dir> [--central <tested repo>]}" central="${4:-}" tested="${5:-}"
  [ -z "$central" ] || [ "$central" = --central ] || { echo "release: unknown argument $central" >&2; exit 2; }
  if [ "$central" = --central ]; then
    # What goes to Central must carry the native libraries the tests ran with (review 2 of PR #289, N3).
    [ -n "$tested" ] && [ -d "$tested" ] || { echo "release --central: give the tested repository (the fork job's) after --central" >&2; exit 2; }
    tested="$(cd "$tested" && pwd)"
  fi
  [ -f "$zl/zipline/native/quickjs/dtoa.c" ] || { echo "run engine first" >&2; exit 1; }
  [ -n "$(find "$zl/zipline/src/jvmMain/resources/jni" -name '*.dll' 2>/dev/null)" ] \
    || { echo "release: run jni-all first (zipline-jvm carries every host's engine, built with zig)" >&2; exit 1; }
  # The Apple klibs come from a Mac with Xcode only; a release without them would be incomplete.
  if [ "$(uname -s)" != Darwin ] || ! xcrun --sdk iphoneos --show-sdk-path > /dev/null 2>&1; then
    echo "release: needs a Mac with Xcode (the Apple targets' klibs)" >&2; exit 1
  fi
  # The upload invocation's marker: present only once it has started (the workflow's failure step
  # reads it, with the kept bundle, to say what may have reached the Central Portal).
  rm -f "$work/central-upload.started" "$work/central-bundle.zip"
  vanniktech_pinned
  mkdir -p "$repo"; repo="$(cd "$repo" && pwd)"
  case "$repo" in "$HOME/.m2"*) echo "refusing ~/.m2" >&2; exit 2 ;; esac
  [ -z "$(ls -A "$repo")" ] || { echo "release: $repo is not empty" >&2; exit 2; }
  upstream_central
  # No build cache: every output of a release is built in this checkout, never taken from an
  # earlier run's cache entry (Zipline's gradle.properties turns the cache on).
  local props=("-PziplineForkRelease=$RELEASE_GROUP" "-PVERSION_NAME=$RELEASE_VERSION" --no-build-cache)
  local v signed=unsigned
  if [ "$central" = --central ]; then
    # The credentials as Gradle properties (ORG_GRADLE_PROJECT_*, as publish-maven-central.yml
    # passes them). With the signing key set, Zipline's build signs every publication, the
    # directory's too, so what is checked below is what Central gets, signatures included.
    for v in mavenCentralUsername mavenCentralPassword signingInMemoryKey signingInMemoryKeyPassword; do
      v="ORG_GRADLE_PROJECT_$v"
      [ -n "${!v:-}" ] || { echo "release --central: $v is not set" >&2; exit 2; }
    done
    signed=signed
  fi
  # Every publication of every module (the full set upstream publishes), to the directory first.
  zipline_gradle "${props[@]}" "-PziplineForkReleaseRepo=$repo" publishAllPublicationsToForkReleaseRepository
  check_release "$repo" "$signed"
  [ "$central" = --central ] || return 0
  python3 -I "$here/publish-checks.py" natives "$tested" "$repo" "$RELEASE_GROUP" "$RELEASE_VERSION"
  # Maven Central only after the same build's outputs passed the check above, in review mode
  # (zipline-build.init.gradle): the deployment waits in the Central Portal until someone compares
  # it with the checked directory and presses Publish (the fork's README, "Publishing the fork").
  # -PziplineForkUploadOnly (review 3 of PR #289, M1): every signing task must be UP-TO-DATE, so the
  # files and signatures uploaded are the ones checked; one that would run fails the build first.
  # vanniktech's plugin (0.36.0, read from its bytecode; the README, "What the upload relies on"),
  # when the build service closes after a successful build: zips its staging repositories into
  # <zipline>/build/publish/<name>-<millis>.zip, then uploads that zip, and never deletes it. After
  # a failed build it writes no zip and opens no connection (`check-upload-gate` shows both). So a
  # new zip means the upload was attempted, whether or not it succeeded; a copy is kept as
  # $work/central-bundle.zip, which `sha1-manifest` compares with the checked directory and lists
  # for the Portal (and the workflow keeps it after a failure too).
  local marker="$work/central-upload.started" bundles status=0
  : > "$marker"
  zipline_gradle "${props[@]}" -PziplineForkUploadOnly publishToMavenCentral || status=$?
  bundles="$(find "$zl/build/publish" -name '*.zip' -newer "$marker" 2>/dev/null || true)"
  if [ -n "$bundles" ] && [ "$(wc -l <<<"$bundles")" -eq 1 ]; then
    cp "$bundles" "$work/central-bundle.zip"
    echo "release: the bundle for Maven Central: $bundles ($(wc -c < "$bundles" | tr -d ' ') bytes), kept as $work/central-bundle.zip"
  else
    echo "release: expected one new bundle zip under $zl/build/publish, found: ${bundles:-none}" >&2
    [ $status != 0 ] || status=1
  fi
  if [ $status != 0 ]; then
    if [ -n "$bundles" ]; then
      echo "release: the upload invocation failed (exit $status) AFTER writing a bundle, so the upload was attempted" \
        "and may have reached the Central Portal: Drop any deployment of $RELEASE_VERSION there" >&2
    else
      echo "release: the upload invocation failed (exit $status) without writing a bundle: by vanniktech $VANNIKTECH_VERSION's" \
        "bytecode nothing was uploaded; still check the Central Portal's Deployments and Drop any of $RELEASE_VERSION" >&2
    fi
    exit "$status"
  fi
}

do_diff() { # maintainers: regenerate patches/ from an edited clone (after `engine` and editing)
  local s="$work/src/quickjs-$QJS_VERSION" q="$zl/zipline/native/quickjs" out="$here/patches"
  engine_sources
  mkdir -p "$out"
  # The engine: every file against the pristine tarball, as one -p1 patch for the quickjs dir.
  local f tmp; tmp="$(mktemp -d)"
  mkdir -p "$tmp/a" "$tmp/b"
  for f in $QJS_FILES; do cp "$s/$f" "$tmp/a/$f"; cp "$q/$f" "$tmp/b/$f"; done
  (cd "$tmp" && diff -ru a b > "$out/quickjs-$QJS_VERSION-asuintn.patch") || true
  rm -rf "$tmp"
  # Zipline: everything else, against the tag. New files (tests) need git's intent-to-add.
  git -C "$zl" add -N .
  git -C "$zl" diff --binary HEAD -- . ':(exclude)zipline/native/quickjs' \
    ':(exclude)zipline/src/jvmMain/resources/jni' > "$out/zipline-1.28.0.patch"
  git -C "$zl" reset -q
  wc -l "$out"/*.patch
}

case "$cmd" in
  clone) do_clone ;;
  diff) do_diff ;;
  engine) do_engine ;;
  jni) do_jni "$@" ;;
  qjs) do_qjs ;;
  check-bigint) do_check_bigint ;;
  android) do_android ;;
  qjs-android) do_qjs_android ;;
  check-bigint-device) do_check_bigint_device "$@" ;;
  ios) do_ios ;;
  check-bigint-zipline) do_check_bigint_zipline "$@" ;;
  test) do_test "$@" ;;
  results) do_results "$@" ;;
  publish) do_publish "$@" ;;
  jni-all) do_jni_all ;;
  jni-from) do_jni_from "$@" ;;
  sha1-manifest) do_sha1_manifest "$@" ;;
  check-natives) python3 -I "$here/publish-checks.py" natives "${3:?check-natives <work> <tested repo> <repo>}" \
    "${4:?check-natives <work> <tested repo> <repo>}" "$RELEASE_GROUP" "$RELEASE_VERSION" ;;
  release) do_release "$@" ;;
  check-release) check_release "${3:?check-release <work> <repo release wrote> [signed]}" "${4:-unsigned}" ;;
  check-capabilities) do_check_capabilities "$@" ;;
  check-upload-gate) do_check_upload_gate ;;
  jvm-test-dist) do_jvm_test_dist ;;
  all) do_clone; do_engine; do_jni; do_qjs; do_check_bigint ;;
  *) echo "unknown: $cmd" >&2; exit 2 ;;
esac
