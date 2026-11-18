#!/bin/bash
# NF2: Zipline 1.28.0 on Bellard's QuickJS 2026-06-04 with BigInt, kept as a patch set (step 1: the
# JVM; step 2: Android). See README.md next to this script. Nothing here pushes anything; `publish`
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
#                             check each ABI's libquickjs.so is in it, built with those flags
#   apply.sh qjs-android <work>   build android/run-js.c on the fork's engine and on the unpatched
#                             release with the NDK, per ABI, with the AAR's flags (for check-bigint-device)
#   apply.sh check-bigint-device <work> [serial]   run ../bigint-ops.js on a device or emulator over adb
#                             (serial, else ANDROID_SERIAL) for every ABI it supports; on a 32-bit ABI
#                             this exercises NaN-boxed values (JS_NAN_BOXING) and 32-bit limbs
#   apply.sh test    <work> [gradle args]   Zipline's own build, e.g. :zipline:jvmTest, or
#                             :zipline:connectedAndroidDeviceTest (its device tests, on ANDROID_SERIAL)
#   apply.sh publish <work> <maven repo dir>   publish the forked JVM and Android artifacts (zipline-jvm,
#                             zipline-android, zipline-android-ndk) as 1.28.0-q20260604 to a scratch
#                             Maven repository (never ~/.m2)
#   apply.sh diff    <work>   maintainers: regenerate patches/ from an edited checkout
#   apply.sh all     <work>   clone, engine, jni, qjs, check-bigint (no Android SDK needed)
set -euo pipefail
shopt -s nullglob
here="$(cd "$(dirname "$0")" && pwd)"
cmd="${1:?usage: apply.sh clone|engine|jni|qjs|check-bigint|android|qjs-android|check-bigint-device|test|publish|diff|all <work dir> [...]}"
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
# ABIs zipline-android-ndk builds (its abiFilters). minSdk is Zipline's.
NDK_VERSION="${KELIVER_NDK_VERSION:-28.0.12674087}"
ANDROID_ABIS="arm64-v8a armeabi-v7a x86_64 x86"
ANDROID_MIN_SDK=23
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

abi_target() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android$ANDROID_MIN_SDK" ;;
    armeabi-v7a) echo "armv7a-linux-androideabi$ANDROID_MIN_SDK" ;;
    x86_64) echo "x86_64-linux-android$ANDROID_MIN_SDK" ;;
    x86) echo "i686-linux-android$ANDROID_MIN_SDK" ;;
  esac
}

do_android() { # the AAR, from Zipline's own build (zipline-android-ndk's CMake and release flags)
  zipline_gradle :zipline-android-ndk:assembleRelease
  local aar="$zl/zipline-android-ndk/build/outputs/aar/zipline-android-ndk-release.aar"
  local listing abi size cc line flag fail=0
  listing="$(unzip -l "$aar")"
  for abi in $ANDROID_ABIS; do
    size="$(awk -v f="jni/$abi/libquickjs.so" '$4 == f {print $1}' <<<"$listing")"
    [ -n "$size" ] || { echo "android: $aar has no jni/$abi/libquickjs.so" >&2; fail=1; continue; }
    # The engine's own compile line for this ABI: Zipline's release flags, and -fwrapv (QuickJS 2026's
    # Makefile builds with it; the patch adds it to the CMakeLists).
    cc="$(find "$zl/zipline-android-ndk/.cxx" -path "*/release/$abi/compile_commands.json" | head -1)"
    line="$(grep '"command"' "$cc" | grep 'native/quickjs/quickjs\.c' || true)"
    [ -n "$line" ] || { echo "android: $abi: no compile line for quickjs.c in $cc" >&2; fail=1; continue; }
    for flag in -fwrapv -Os -DNDEBUG -fvisibility=hidden -fstrict-aliasing "$QJS_VERSION"; do
      grep -qF -- "$flag" <<<"$line" || { echo "android: $abi: quickjs.c not built with $flag" >&2; fail=1; }
    done
    echo "android: $abi libquickjs.so $size bytes (quickjs.c: -Os -DNDEBUG -fwrapv)"
  done
  [ $fail = 0 ] && echo "android: $aar"
  return $fail
}

do_qjs_android() { # android/run-js.c on the fork's engine (as in the AAR) and on the unpatched release
  local bin; bin="$(ndk_bin)"
  engine_sources # a fresh, unpatched $work/src/quickjs-$QJS_VERSION: the control
  local patched="$zl/zipline/native/quickjs" unpatched="$work/src/quickjs-$QJS_VERSION"
  [ -f "$patched/dtoa.c" ] || { echo "run engine first" >&2; exit 1; }
  local v; v="$(tr -d '\r\n' < "$patched/VERSION")"
  # zipline-android-ndk's release flags (its build.gradle.kts and CMakeLists.txt), as an executable.
  local flags=(-std=gnu99 -fstrict-aliasing -g0 -Os -fomit-frame-pointer -DNDEBUG -fwrapv -fPIE -pie -w
    "-DCONFIG_VERSION=\"$v\"")
  local abi src name out
  for abi in $ANDROID_ABIS; do
    for name in run-js run-js-unpatched; do
      case "$name" in *unpatched) src="$unpatched" ;; *) src="$patched" ;; esac
      out="$work/build/android/$abi/$name"
      mkdir -p "$(dirname "$out")"
      "$bin/clang" --target="$(abi_target "$abi")" "${flags[@]}" -I"$src" "$here/android/run-js.c" \
        "$src/cutils.c" "$src/dtoa.c" "$src/libregexp.c" "$src/libunicode.c" "$src/quickjs.c" -lm -o "$out"
    done
    echo "qjs-android: $abi: $work/build/android/$abi/{run-js,run-js-unpatched}"
  done
}

do_check_bigint_device() {
  local serial="${3:-${ANDROID_SERIAL:-}}"
  [ -n "$serial" ] || { echo "check-bigint-device: give a serial (or set ANDROID_SERIAL)" >&2; exit 2; }
  local adb_bin; adb_bin="$(sdk_dir)/platform-tools/adb"; [ -x "$adb_bin" ] || adb_bin=adb
  local adb=("$adb_bin" -s "$serial") dir=/data/local/tmp/keliver-nf2-bigint
  local ops="$here/../bigint-ops.js" out="$work/bigint-check/device-$serial"
  mkdir -p "$out"
  node "$ops" > "$out/node.txt"
  local lines abis abi name fail=0 ran=0 ran32=0 header
  lines="$(wc -l < "$out/node.txt" | tr -d ' ')"
  abis="$("${adb[@]}" shell getprop ro.product.cpu.abilist | tr -d '\r')"
  echo "check-bigint-device: $serial supports $abis"
  "${adb[@]}" shell "rm -rf $dir && mkdir -p $dir" >/dev/null
  "${adb[@]}" push "$ops" "$dir/bigint-ops.js" >/dev/null 2>&1
  for abi in $ANDROID_ABIS; do
    case ",$abis," in *",$abi,"*) ;; *) echo "check-bigint-device: $abi: not supported by $serial, not run"; continue ;; esac
    for name in run-js run-js-unpatched; do
      [ -f "$work/build/android/$abi/$name" ] || { echo "run qjs-android first" >&2; exit 1; }
      "${adb[@]}" push "$work/build/android/$abi/$name" "$dir/$abi-$name" >/dev/null 2>&1
      # The unpatched release may loop on some lines (as on the Mac): bound each run.
      "${adb[@]}" shell "cd $dir && chmod 755 $abi-$name && timeout 120 ./$abi-$name bigint-ops.js 2> $abi-$name.err" \
        > "$out/$abi-$name.txt" || true
      header="$("${adb[@]}" shell cat "$dir/$abi-$name.err" | tr -d '\r' | head -1)"
      tr -d '\r' < "$out/$abi-$name.txt" > "$out/$abi-$name.tmp" && mv "$out/$abi-$name.tmp" "$out/$abi-$name.txt"
      if diff -u "$out/node.txt" "$out/$abi-$name.txt" > "$out/$abi-$name.diff"; then
        echo "check-bigint-device: $abi $name matches Node on all $lines lines ($header)"
        case "$name" in *unpatched) echo "check-bigint-device: the control did not fail; the check is too weak" >&2; fail=1 ;; esac
      else
        echo "check-bigint-device: $abi $name differs from Node on $(grep -c '^-[^-]' "$out/$abi-$name.diff") of $lines lines ($header)"
        case "$name" in *unpatched) ;; *) fail=1 ;; esac
      fi
    done
    ran=$((ran + 1))
    case "$abi" in armeabi-v7a|x86) ran32=$((ran32 + 1)) ;; esac
  done
  "${adb[@]}" shell "rm -rf $dir" >/dev/null
  [ $ran -gt 0 ] || { echo "check-bigint-device: no ABI ran" >&2; fail=1; }
  [ $ran32 -gt 0 ] || echo "check-bigint-device: NOTE no 32-bit ABI on $serial: JS_NAN_BOXING not exercised (needs an armeabi-v7a or x86 image or device)"
  return $fail
}

do_test() {
  shift 2 || true
  zipline_gradle "$@"
}

do_publish() {
  local repo="${3:?maven repo dir}"
  mkdir -p "$repo"; repo="$(cd "$repo" && pwd)"
  case "$repo" in "$HOME/.m2"*) echo "refusing ~/.m2" >&2; exit 2 ;; esac
  # JVM (the engine the Gradle plugin compiles bundles with) and Android (the JNI classes and the AAR
  # with libquickjs.so). Every other Zipline module stays 1.28.0 (use-fork.init.gradle).
  zipline_gradle -PVERSION_NAME="$FORK_VERSION" "-Dmaven.repo.local=$repo" -PRELEASE_SIGNING_ENABLED=false \
    :zipline:publishJvmPublicationToMavenLocal :zipline:publishAndroidPublicationToMavenLocal \
    :zipline-android-ndk:publishMavenPublicationToMavenLocal
  find "$repo/app/cash/zipline" \( -name "*.jar" -o -name "*.aar" \) | sort
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
  test) do_test "$@" ;;
  publish) do_publish "$@" ;;
  all) do_clone; do_engine; do_jni; do_qjs; do_check_bigint ;;
  *) echo "unknown: $cmd" >&2; exit 2 ;;
esac
