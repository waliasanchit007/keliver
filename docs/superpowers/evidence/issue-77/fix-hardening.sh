#!/usr/bin/env bash
# #77, the review's cases — what generatePortalKey does with a SYMLINK in its
# directory, a plant it cannot delete, and a public key that is not 64 hex
# digits (it is spliced into Kotlin source).
#
#   hardening.sh <label>
#
# Same layout and isolation as fix-repro.sh: <dir>/wt (the worktree), <dir>/store
# (a dummy PUBLIC key), a disposable Gradle home, user.home and KONAN_DATA_DIR.
# Task level only; no compile. The link target and the bad-key store are made
# here, inside <dir>.
set -uo pipefail
I="$(cd "$(dirname "$0")" && pwd -P)"
LABEL="${1:?usage: $0 <label>}"
L="$I/out-$LABEL"; rm -rf "$L"; mkdir -p "$L"
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:?set a disposable GRADLE_USER_HOME}"
export JAVA_TOOL_OPTIONS="-Duser.home=$I/home"
export KONAN_DATA_DIR="$I/konan"
unset PORTAL_STORE
cd "$I/wt"
OUT=portal-device-ios/build/generated/portalKeys/kotlin
G() { ./gradlew --console=plain "$@"; }
echo "== tree: $(git rev-parse --short HEAD), build file $(git diff --quiet -- portal-device-ios/build.gradle && echo 'as committed' || echo 'modified in the worktree')"
G :portal-device-ios:generatePortalKey -Pkeliver.portalStore="$I/store" > "$L/run0.log" 2>&1

echo "== 4. a symlink in the directory, pointing at a directory holding a canary"
rm -rf "$I/linktarget"; mkdir -p "$I/linktarget"; echo canary > "$I/linktarget/canary.txt"
ln -s "$I/linktarget" "$OUT/x"
G :portal-device-ios:generatePortalKey -Pkeliver.portalStore="$I/store" > "$L/run4.log" 2>&1; echo "rc=$?"
[ -f "$I/linktarget/canary.txt" ] && echo "RESULT 4: the link's target was left alone (canary.txt present)" \
  || echo "RESULT 4: the link's TARGET WAS EMPTIED (canary.txt gone)"
echo "  directory now: $(ls -A "$OUT" | tr '\n' ' ')"

echo "== 5. a plant that cannot be deleted (chflags uchg)"
printf 'package dev.keliver.portaldevice.ios\npublic fun plantedMarker(): String = "PLANTED_MARKER_77"\n' > "$OUT/Planted.kt"
chflags uchg "$OUT/Planted.kt"
G :portal-device-ios:generatePortalKey -Pkeliver.portalStore="$I/store" > "$L/run5.log" 2>&1; rc=$?
echo "rc=$rc  $(grep -E 'BUILD (SUCCESSFUL|FAILED)' "$L/run5.log")"
grep -m1 -E 'AccessDenied|Operation not permitted|holds|generatePortalKey:' "$L/run5.log" | cut -c1-200 | sed 's/^/  /'
if [ -e "$OUT/Planted.kt" ] && [ "$rc" = 0 ]; then echo "RESULT 5: the plant SURVIVED a GREEN build"
elif [ -e "$OUT/Planted.kt" ]; then echo "RESULT 5: the plant survived, and the build FAILED (not silently)"
else echo "RESULT 5: the plant is gone"; fi
chflags nouchg "$OUT/Planted.kt" 2>/dev/null; rm -f "$OUT/Planted.kt"

echo "== 6. a store whose ed25519.pub is not 64 hex digits"
mkdir -p "$I/store-bad/keys"
printf '00"\npublic fun injected(): Int = 1\nprivate const val Z = "' > "$I/store-bad/keys/ed25519.pub"
G :portal-device-ios:generatePortalKey -Pkeliver.portalStore="$I/store-bad" > "$L/run6.log" 2>&1; rc=$?
echo "rc=$rc  $(grep -E 'BUILD (SUCCESSFUL|FAILED)' "$L/run6.log")"
if grep -q 'injected' "$OUT/PortalPublicKey.kt" 2>/dev/null; then echo "RESULT 6: the key's text became SOURCE (PortalPublicKey.kt declares injected())"
else echo "RESULT 6: no injected source in PortalPublicKey.kt"; fi

echo "== restore: a normal run"
G :portal-device-ios:generatePortalKey -Pkeliver.portalStore="$I/store" > "$L/run7.log" 2>&1; echo "rc=$?  directory: $(ls -A "$OUT" | tr '\n' ' ')"
