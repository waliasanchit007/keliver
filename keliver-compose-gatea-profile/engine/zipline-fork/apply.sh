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
#                             (e.g. iosSimulatorArm64Test; default jvmTest); fails if any test failed
#   apply.sh publish <work> <maven repo dir>   publish the forked artifacts (zipline-jvm, zipline-bytecode,
#                             zipline-android, zipline-android-ndk and, on a Mac with Xcode, the iOS
#                             klibs zipline-iosarm64 and zipline-iossimulatorarm64, each checked to
#                             carry the engine's bitcode) as 1.28.0-q20260604 to a scratch Maven
#                             repository (never ~/.m2)
#   apply.sh diff    <work>   maintainers: regenerate patches/ from an edited checkout
#   apply.sh all     <work>   clone, engine, jni, qjs, check-bigint (no Android SDK needed)
set -euo pipefail
shopt -s nullglob
here="$(cd "$(dirname "$0")" && pwd)"
cmd="${1:?usage: apply.sh clone|engine|jni|qjs|check-bigint|android|qjs-android|check-bigint-device|ios|check-bigint-zipline|test|publish|diff|all <work dir> [...]}"
work="${2:?work dir}"
mkdir -p "$work"
work="$(cd "$work" && pwd)"
zl="$work/zipline"

ZIPLINE_TAG=1.28.0
ZIPLINE_COMMIT=f6cd1536e25d37d18ed157db26a1ac136c3f4196
QJS_VERSION=2026-06-04
QJS_SHA256=b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a
FORK_VERSION=1.28.0-q20260604
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
  for p in "$here"/patches/quickjs-*.patch; do patch -s -d "$q" -p1 < "$p"; echo "applied $(basename "$p")"; done
  for p in "$here"/patches/zipline-*.patch; do git -C "$zl" apply --whitespace=nowarn "$p"; echo "applied $(basename "$p")"; done
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
    "-PziplineForkNdkVersion=$NDK_VERSION" "$@")
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
  local t fail=0
  for t in "$@"; do
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
' "$zl/zipline/build/test-results/$t" || fail=1
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
  all) do_clone; do_engine; do_jni; do_qjs; do_check_bigint ;;
  *) echo "unknown: $cmd" >&2; exit 2 ;;
esac
