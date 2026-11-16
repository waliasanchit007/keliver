#!/bin/bash
# Cross-compiles the QuickJS command-line engine (`qjs`) for Android arm64-v8a, for the phone run of
# docs/GUEST_PROFILE.md "The engine, measured" (NF0 in docs/PLAN_2026-11.md).
# Usage: build-engines-android.sh <work dir> <out dir>
#   <work dir>  sources: downloads/verifies/patches through build-engines.sh (same pinned SHA-256s)
#   <out dir>   gets <out dir>/<build>/qjs for q21-Os, q26fix-Os, q26fix-O2, plus FLAGS.txt
# Env: ANDROID_NDK (default $ANDROID_HOME/ndk/28.0.12674087), ANDROID_API (default 26).
#
# Flags: Zipline 1.28's Android release build (zipline-android-ndk/build.gradle.kts and its
# CMakeLists.txt: CMake MinSizeRel, cFlags -fstrict-aliasing, -g0 -Os -fomit-frame-pointer -DNDEBUG
# -fvisibility=hidden, C99 with GNU extensions, linked with -z max-page-size=16384) on top of the
# NDK toolchain's own defaults (build/cmake/flags.cmake). The 2026 builds keep the Makefile's
# -fwrapv (signed overflow must wrap); its -O2 variant swaps only -Os for -O2. Both versions get
# the Makefiles' -D_GNU_SOURCE and CONFIG_VERSION (needed by the qjs front end, which Zipline does
# not compile). CONFIG_BIGNUM is off, as in Zipline. -funsigned-char is left out: it is the
# default on arm64. Linking: dynamic, against the device's Bionic only (libc, libm, libdl), as
# Zipline's libquickjs.so is; so malloc is the system allocator (Scudo on Android 11+).
# repl.c (the REPL's bytecode, unused by run.js but linked by qjs) is generated with a host qjsc
# built from the same sources.
set -euo pipefail
work="${1:?usage: build-engines-android.sh <work dir> <out dir>}"
out="${2:?usage: build-engines-android.sh <work dir> <out dir>}"
here="$(cd "$(dirname "$0")" && pwd)"
ndk="${ANDROID_NDK:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/28.0.12674087}"
api="${ANDROID_API:-26}"
bin="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin"
[ -d "$bin" ] || bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
cc="$bin/clang"
[ -x "$cc" ] || { echo "no NDK clang under $ndk (set ANDROID_NDK)" >&2; exit 1; }
readelf="$bin/llvm-readelf"

KELIVER_ENGINE_SOURCES_ONLY=1 "$here/build-engines.sh" "$work"
mkdir -p "$work/android" "$out"

# The NDK's CMake defaults for an arm64 release executable (flags.cmake), then Zipline's.
ndk_cflags="-DANDROID -fdata-sections -ffunction-sections -funwind-tables -fstack-protector-strong \
-no-canonical-prefixes -D_FORTIFY_SOURCE=2 -Wformat -Werror=format-security -fPIE"
zipline_cflags="-std=gnu99 -fstrict-aliasing -g0 -fomit-frame-pointer -DNDEBUG -fvisibility=hidden"
ldflags="-pie -Wl,--build-id=sha1 -Wl,--no-rosegment -Wl,--gc-sections -Wl,--no-undefined \
-Wl,-z,max-page-size=16384 -Qunused-arguments"

host_repl() { # <source dir suffix>: repl.c from a host build of the same sources
  local h="$work/android/host-$1"
  if [ ! -f "$h/repl.c" ]; then
    rm -rf "$h"; cp -R "$work/src/quickjs-$1" "$h"
    (cd "$h" && make -j8 repl.c CONFIG_LTO= CONFIG_BIGNUM= > host.log 2>&1) || { tail -20 "$h/host.log" >&2; exit 1; }
  fi
  echo "$h/repl.c"
}

build() { # <name> <source dir suffix> <optimisation> <extra flags>
  local name="$1" src="$work/src/quickjs-$2" opt="$3" extra="$4" b="$work/android/$1"
  local repl; repl="$(host_repl "$2")"
  rm -rf "$b"; cp -R "$src" "$b"; cp "$repl" "$b/repl.c"
  local files="qjs.c repl.c quickjs.c libregexp.c libunicode.c cutils.c quickjs-libc.c"
  [ -f "$b/dtoa.c" ] && files="$files dtoa.c"
  local version; version="$(cat "$b/VERSION")"
  local flags="--target=aarch64-linux-android$api $ndk_cflags $zipline_cflags $opt $extra -D_GNU_SOURCE"
  local objs=""
  for f in $files; do
    # shellcheck disable=SC2086
    (cd "$b" && "$cc" $flags "-DCONFIG_VERSION=\"$version\"" -c "$f" -o "${f%.c}.android.o") >> "$b/android.log" 2>&1 \
      || { tail -30 "$b/android.log" >&2; exit 1; }
    objs="$objs ${f%.c}.android.o"
  done
  # shellcheck disable=SC2086
  (cd "$b" && "$cc" --target=aarch64-linux-android$api $ldflags -o qjs $objs -lm -ldl) >> "$b/android.log" 2>&1 \
    || { tail -30 "$b/android.log" >&2; exit 1; }
  mkdir -p "$out/$name"; cp "$b/qjs" "$out/$name/qjs"
  local needed; needed="$("$readelf" -d "$out/$name/qjs" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ' ')"
  for n in $needed; do case "$n" in libc.so|libm.so|libdl.so) ;; *) echo "$name links $n (only Bionic allowed)" >&2; exit 1;; esac; done
  {
    echo "$name: QuickJS $version ($2), NDK $(basename "$ndk"), API $api"
    echo "  cflags: $flags -DCONFIG_VERSION=\"$version\""
    echo "  ldflags: $ldflags -lm -ldl; NEEDED: $needed"
    echo "  size: $(wc -c < "$out/$name/qjs" | tr -d ' ') bytes; sha256 $(shasum -a 256 "$out/$name/qjs" | awk '{print $1}')"
  } >> "$out/FLAGS.txt"
  echo "built $name ($(wc -c < "$out/$name/qjs" | tr -d ' ') bytes)"
}

: > "$out/FLAGS.txt"
build q21-Os 2021-03-27 -Os ""
build q26fix-Os 2026-06-04-fix -Os -fwrapv
build q26fix-O2 2026-06-04-fix -O2 -fwrapv
cat "$out/FLAGS.txt"
