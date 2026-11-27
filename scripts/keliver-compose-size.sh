#!/usr/bin/env bash
#
# keliver-compose-size — what the generated API's product route adds to an app (docs/MS4_PLAN.md,
# MS4-03: Q11's baseline; C9 measures the candidate the same way).
#
#   scripts/keliver-compose-size.sh [--ios device|simulator|none] [--out FILE] [--skip-build]
#
# Builds, from this checkout, with -Pkeliver.composeSize (nothing is published):
#  - the guest bundle (keliver-compose-size-guest: the same-source samples on the generated root,
#    production JS, signed with a scratch key);
#  - three release builds, minified by R8 (keliver-compose-size-android), each as a universal APK
#    (all four ABIs) and an arm64-v8a APK (what a phone gets from an app bundle): nativeOnly (the
#    samples in native Compose: the baseline), product (plus the guest route on the generated root:
#    Treehouse, the root's host protocol, core's renderers, the bundle in assets; no legacy, no
#    Coil) and productCoil (plus the optional coil group);
#  - three release executables of a Kotlin-only iOS app (keliver-compose-size-ios), the same three
#    routes, linked for a phone (--ios device: iosArm64, unsigned; the default) or the simulator.
# --skip-build reports on the outputs of an earlier run.
# Then reports, as Markdown on stdout (and into --out FILE): each APK, its dex, its arm64-v8a
# native libraries (as packaged, and the bytes a stripped copy keeps) and its assets; the bundle's
# files, uncompressed and gzipped; each iOS executable, as linked and stripped of symbols (a copy,
# `xcrun strip -S -x`, as an App Store build ships it). Sizes are bytes; differences are against
# nativeOnly / Native.
#
# Needs: JDK 17 for Gradle, the Android SDK, and for --ios Xcode's command-line tools.
set -euo pipefail
NAME=keliver-compose-size
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
fail() { echo "$NAME: $*" >&2; exit 1; }

IOS=device
OUT=
BUILD=1
while [ $# -gt 0 ]; do
  case "$1" in
    --ios) IOS="${2:?}"; shift 2 ;;
    --out) OUT="${2:?}"; shift 2 ;;
    --skip-build) BUILD=; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) fail "unknown argument $1" ;;
  esac
done
case "$IOS" in
  device) IOS_TARGET=IosArm64; IOS_DIR=iosArm64 ;;
  simulator) IOS_TARGET=IosSimulatorArm64; IOS_DIR=iosSimulatorArm64 ;;
  none) IOS_TARGET=; IOS_DIR= ;;
  *) fail "--ios is device, simulator or none" ;;
esac

# First letter upper case (macOS's bash 3.2 has no ${x^}).
cap() { echo "$(echo "${1:0:1}" | tr '[:lower:]' '[:upper:]')${1:1}"; }

ROUTES=(nativeOnly product productCoil)
IOS_ROUTES=(Native Product ProductCoil)
TASKS=(:keliver-compose-size-guest:compileProductionExecutableKotlinJsZipline)
for r in "${ROUTES[@]}"; do TASKS+=(":keliver-compose-size-android:assemble$(cap "$r")Release"); done
if [ -n "$IOS_TARGET" ]; then
  for r in "${IOS_ROUTES[@]}"; do TASKS+=(":keliver-compose-size-ios:link${r}ReleaseExecutable${IOS_TARGET}"); done
fi

if [ -n "$BUILD" ]; then
  echo "$NAME: building ${TASKS[*]}" >&2
  ( cd "$ROOT" && ./gradlew --console=plain -q -Pkeliver.composeSize -DRELEASE_SIGNING_ENABLED=false "${TASKS[@]}" ) >&2
fi

size() { if [ -f "$1" ]; then wc -c < "$1" | tr -d ' '; else echo 0; fi; }
# The total size of the regular files under a directory (0 when it does not exist).
dirsize() {
  local total=0 f
  [ -d "$1" ] || { echo 0; return; }
  while IFS= read -r -d '' f; do total=$(( total + $(size "$f") )); done < <(find "$1" -type f -print0)
  echo "$total"
}
gzsize() {
  local total=0 f
  while IFS= read -r -d '' f; do total=$(( total + $(gzip -9 -c "$f" | wc -c | tr -d ' ') )); done < <(find "$1" -type f -print0)
  echo "$total"
}
delta() { local d=$(( $1 - $2 )); if [ "$d" -ge 0 ]; then echo "+$d"; else echo "$d"; fi; }
# The bytes a stripped copy of a 64-bit little-endian ELF library keeps, near enough: the end of its
# last allocated section with file content. Strip removes what comes after (.comment, .debug_*,
# .symtab, .strtab); the section headers it rewrites are a few kilobytes. Without an NDK, AGP
# cannot strip and packages libraries with their debug information.
elf_kept() {
  local f=$1 shoff shnum i base type flags off sz end max=0
  # EI_CLASS 2 (64-bit), EI_DATA 1 (little-endian).
  [ "$(od -A n -t u1 -j 4 -N 2 "$f" | tr -d ' \n')" = 21 ] || fail "$f is not a 64-bit little-endian ELF file"
  shoff=$(od -A n -t u8 -j 40 -N 8 "$f" | tr -d ' ')
  shnum=$(od -A n -t u2 -j 60 -N 2 "$f" | tr -d ' ')
  for (( i = 0; i < shnum; i++ )); do
    base=$(( shoff + i * 64 ))
    type=$(od -A n -t u4 -j $(( base + 4 )) -N 4 "$f" | tr -d ' ')
    flags=$(od -A n -t u8 -j $(( base + 8 )) -N 8 "$f" | tr -d ' ')
    read -r off sz < <(od -A n -t u8 -j $(( base + 24 )) -N 16 "$f")
    # SHF_ALLOC, and not SHT_NOBITS (.bss has no bytes in the file).
    if (( (flags & 2) != 0 && type != 8 )); then
      end=$(( off + sz ))
      (( end > max )) && max=$end
    fi
  done
  echo "$max"
}
# elf_kept over every library under a directory.
dir_elf_kept() {
  local total=0 f
  [ -d "$1" ] || { echo 0; return; }
  while IFS= read -r -d '' f; do total=$(( total + $(elf_kept "$f") )); done < <(find "$1" -type f -name '*.so' -print0)
  echo "$total"
}

ANDROID="$ROOT/keliver-compose-size-android/build"
GUEST="$ROOT/keliver-compose-size-guest/build/zipline/Production"
[ -d "$GUEST" ] || fail "no bundle at $GUEST"

report() {
  echo "## Product route size ($(git -C "$ROOT" rev-parse --short HEAD))"
  echo
  echo "### Android (release, R8)"
  echo
  echo "Native libraries are stored uncompressed (minSdk 23), so a library's bytes are its bytes in"
  echo "the APK. \"arm64 libs, stripped\" is what a stripped copy keeps; the last column is the arm64"
  echo "APK's difference with the libraries stripped (an estimate when AGP could not strip them)."
  echo
  echo "| Variant | universal APK | Δ | arm64-v8a APK | Δ | dex | Δ dex | arm64 libs, packaged | arm64 libs, stripped | assets | Δ arm64 APK, stripped |"
  echo "|---|---|---|---|---|---|---|---|---|---|---|"
  local base_apk=0 base_arm=0 base_dex=0 base_debug=0 r out apk arm dex libdir libs kept debug assets
  for r in "${ROUTES[@]}"; do
    out="$ANDROID/outputs/apk/$r/release"
    apk=$(size "$out/keliver-compose-size-android-$r-universal-release.apk")
    arm=$(size "$out/keliver-compose-size-android-$r-arm64-v8a-release.apk")
    [ "$apk" -gt 0 ] && [ "$arm" -gt 0 ] || fail "no $r APKs in $out"
    dex=$(dirsize "$ANDROID/intermediates/dex/${r}Release")
    libdir="$ANDROID/intermediates/stripped_native_libs/${r}Release/strip$(cap "$r")ReleaseDebugSymbols/out/lib/arm64-v8a"
    libs=$(dirsize "$libdir")
    kept=$(dir_elf_kept "$libdir")
    debug=$(( libs - kept ))
    assets=$(dirsize "$ANDROID/intermediates/compressed_assets/${r}Release")
    if [ "$r" = nativeOnly ]; then base_apk=$apk; base_arm=$arm; base_dex=$dex; base_debug=$debug; fi
    echo "| $r | $apk | $(delta "$apk" "$base_apk") | $arm | $(delta "$arm" "$base_arm") | $dex | $(delta "$dex" "$base_dex") | $libs | $kept | $assets | $(delta $(( arm - debug )) $(( base_arm - base_debug ))) |"
  done
  echo
  echo "### The guest bundle (keliver-compose-size-guest, production)"
  echo
  echo "| Files | Uncompressed | gzip -9 |"
  echo "|---|---|---|"
  echo "| $(find "$GUEST" -type f | wc -l | tr -d ' ') | $(dirsize "$GUEST") | $(gzsize "$GUEST") |"
  if [ -n "$IOS_TARGET" ]; then
    echo
    echo "### iOS (release executables, $IOS_TARGET)"
    echo
    echo "| Executable | As linked | Δ | Stripped | Δ |"
    echo "|---|---|---|---|---|"
    local base=0 base_s=0 exe s stripped tmp
    tmp=$(mktemp -d)
    for r in "${IOS_ROUTES[@]}"; do
      # Kotlin/Native names the file after the binary: bin/<target>/<Name>ReleaseExecutable/<Name>.kexe
      # (its .dSYM holds a file of the same name: not that one).
      exe="$ROOT/keliver-compose-size-ios/build/bin/$IOS_DIR/${r}ReleaseExecutable/$r.kexe"
      s=$(size "$exe")
      [ "$s" -gt 0 ] || { rm -rf "$tmp"; fail "no $r executable at $exe"; }
      xcrun strip -S -x -o "$tmp/$r" "$exe"
      stripped=$(size "$tmp/$r")
      if [ "$r" = Native ]; then base=$s; base_s=$stripped; fi
      echo "| $r | $s | $(delta "$s" "$base") | $stripped | $(delta "$stripped" "$base_s") |"
    done
    rm -rf "$tmp"
  fi
}

if [ -n "$OUT" ]; then report | tee "$OUT"; else report; fi
