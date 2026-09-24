#!/usr/bin/env bash
# #77 — does generatePortalKey's directory hold ONLY its intended output, and
# does a foreign source planted there reach the iOS build?
#
#   repro.sh <label>      (run once on the unfixed tree, once on the fixed one)
#
# Isolated: GRADLE_USER_HOME and the JVM's user.home are disposable, and the
# store passed as -Pkeliver.portalStore holds only a dummy PUBLIC key
# (5ca1ab1e x8). No private key exists anywhere in this run.
#
# Three boundaries, each on a WARM build (the task has already run once):
#   1  task level    — plant a file, run generatePortalKey again: survives?
#   2a klib          — is the planted source compiled into the module klib?
#   2b framework     — debug iosSimulatorArm64 framework: header export,
#                      compiled symbol, string literal.
# Plus: the generated constant carries the store's key, in the source and in
# the linked binary.
set -uo pipefail
I="$(cd "$(dirname "$0")" && pwd -P)"
LABEL="${1:?usage: $0 <label>}"
L="$I/out-$LABEL"; rm -rf "$L"; mkdir -p "$L"
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:?set a disposable GRADLE_USER_HOME}"
export JAVA_TOOL_OPTIONS="-Duser.home=$I/home"
# Kotlin/Native's toolchain cache. Without this it is <user.home>/.konan as the
# DAEMON sees it, and a daemon started earlier without the user.home override
# resolves the real ~/.konan — measured once, which is why this is explicit.
export KONAN_DATA_DIR="$I/konan"
unset PORTAL_STORE
MARK="$L/.start"; : > "$MARK"
cd "$I/wt"
OUT=portal-device-ios/build/generated/portalKeys/kotlin
KEY="$(cat "$I/store/keys/ed25519.pub")"
G() { ./gradlew -Pkeliver.portalStore="$I/store" --console=plain "$@"; }
outcome() { grep -E "> Task :portal-device-ios:($1)( |$)" "$2" | tail -1 | sed 's/^> Task //'; }
plant() {
  printf 'package dev.keliver.portaldevice.ios\n\npublic fun plantedMarker(): String = "PLANTED_MARKER_77"\n' > "$OUT/Planted.kt"
}
u16count() { # <file> <ascii text>: occurrences of the text as UTF-16LE (Kotlin/Native string literals)
  python3 - "$1" "$2" <<'PY'
import sys
data = open(sys.argv[1], 'rb').read()
print(data.count(sys.argv[2].encode('utf-16-le')))
PY
}

echo "== tree: $(git rev-parse --short HEAD) $(git status --porcelain portal-device-ios/build.gradle | wc -l | tr -d ' ') modified build file(s)"
git diff --stat -- portal-device-ios/build.gradle

echo "== 0. first run of generatePortalKey"
G :portal-device-ios:generatePortalKey > "$L/run0.log" 2>&1; echo "rc=$?  $(outcome generatePortalKey "$L/run0.log")"
ls -A "$OUT"

echo "== 1. plant Planted.kt, run generatePortalKey again (warm)"
plant
G :portal-device-ios:generatePortalKey > "$L/run1.log" 2>&1; echo "rc=$?  $(outcome generatePortalKey "$L/run1.log")"
ls -A "$OUT"
if [ -e "$OUT/Planted.kt" ]; then echo "RESULT 1: Planted.kt SURVIVED"; else echo "RESULT 1: Planted.kt ABSENT"; fi

echo "== generated constant"
EXPECTED="package dev.keliver.portaldevice.ios

// GENERATED from the portal store's ed25519.pub — do not edit.
internal const val PORTAL_PUBLIC_KEY_HEX: String = \"$KEY\""
if [ "$(cat "$OUT/PortalPublicKey.kt")" = "$EXPECTED" ]; then
  echo "RESULT const: PortalPublicKey.kt is exactly the expected source, key $KEY"
else
  echo "RESULT const: PortalPublicKey.kt DIFFERS from the expected source:"; cat "$OUT/PortalPublicKey.kt"
fi

echo "== 2. plant again (if gone), then link the debug iosSimulatorArm64 framework (warm)"
[ -e "$OUT/Planted.kt" ] || plant
ls -A "$OUT"
G :portal-device-ios:linkDebugFrameworkIosSimulatorArm64 > "$L/link.log" 2>&1; echo "rc=$?"
for t in generatePortalKey compileKotlinIosSimulatorArm64 linkDebugFrameworkIosSimulatorArm64; do
  echo "  $(outcome "$t" "$L/link.log")"
done
grep -E '^e: |BUILD' "$L/link.log" | tail -3
ls -A "$OUT"

echo "== 2a. klib"
KL="$(find portal-device-ios/build/classes/kotlin/iosSimulatorArm64/main -maxdepth 2 \( -name '*.klib' -o -type d -name klib \) 2>/dev/null | head -1)"
echo "klib: ${KL:-none found}"
if [ -n "$KL" ]; then
  printf 'RESULT 2a: files in the klib mentioning PLANTED/plantedMarker: '
  if [ -d "$KL" ]; then grep -r -a -l -E 'PLANTED_MARKER_77|plantedMarker' "$KL" | wc -l | tr -d ' '
  else unzip -Z1 "$KL" | while read -r e; do unzip -p "$KL" "$e" 2>/dev/null | grep -a -q -E 'PLANTED_MARKER_77|plantedMarker' && echo "$e"; done | wc -l | tr -d ' '; fi
fi

echo "== 2b. framework"
F="$(find portal-device-ios/build/bin/iosSimulatorArm64/debugFramework -name 'PortalDeviceHost.framework' -type d | head -1)"
echo "framework: $F"
echo "  built: $(stat -f '%Sm' "$F/PortalDeviceHost")"
printf 'RESULT 2b header: plantedMarker lines in Headers = '; grep -rn 'plantedMarker' "$F/Headers" | tee "$L/header-grep.txt" | wc -l | tr -d ' '
printf 'RESULT 2b symbol: nm lines for plantedMarker = '; nm "$F/PortalDeviceHost" 2>/dev/null | grep 'plantedMarker' | tee "$L/nm-grep.txt" | wc -l | tr -d ' '
printf 'RESULT 2b literal: UTF-16LE "PLANTED_MARKER_77" occurrences = '; u16count "$F/PortalDeviceHost" PLANTED_MARKER_77
printf 'RESULT const binary: UTF-16LE key hex occurrences = '; u16count "$F/PortalDeviceHost" "$KEY"
rm -f "$OUT/Planted.kt"

echo "== 3. nothing planted: link again (does rewriting the key file dirty the compile?)"
G :portal-device-ios:linkDebugFrameworkIosSimulatorArm64 > "$L/link2.log" 2>&1; echo "rc=$?"
for t in generatePortalKey compileKotlinIosSimulatorArm64 linkDebugFrameworkIosSimulatorArm64; do
  echo "  $(outcome "$t" "$L/link2.log")"
done
ls -A "$OUT"
echo "== isolation"
echo "  KONAN_DATA_DIR=$KONAN_DATA_DIR ($(ls "$KONAN_DATA_DIR" 2>/dev/null | wc -l | tr -d ' ') entries)"
echo "  entries under the real ~/.konan changed during this run: $(find "$HOME/.konan" -newer "$MARK" 2>/dev/null | wc -l | tr -d ' ')"
echo "  daemons: $(./gradlew --status 2>/dev/null | grep -cE 'IDLE|BUSY') in $GRADLE_USER_HOME"
