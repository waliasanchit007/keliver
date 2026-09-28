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
grep -m1 -E 'AccessDenied|Operation not permitted|holds|generatePortalKey:' "$L/run5.log" | sed -e "s#$PWD/##" -e 's/^/  /'
if [ -e "$OUT/Planted.kt" ] && [ "$rc" = 0 ]; then echo "RESULT 5: the plant SURVIVED a GREEN build"
elif [ -e "$OUT/Planted.kt" ]; then echo "RESULT 5: the plant survived, and the build FAILED (not silently)"
else echo "RESULT 5: the plant is gone"; fi
chflags nouchg "$OUT/Planted.kt" 2>/dev/null; rm -f "$OUT/Planted.kt"

echo "== 6. a store whose ed25519.pub is not 64 hex digits"
G :portal-device-ios:generatePortalKey -Pkeliver.portalStore="$I/store" > "$L/run6pre.log" 2>&1
BEFORE6="$(shasum -a 256 "$OUT/PortalPublicKey.kt" 2>/dev/null | cut -c1-64)"
echo "  PortalPublicKey.kt before: ${BEFORE6:-ABSENT}"
mkdir -p "$I/store-bad/keys"
printf '00"\npublic fun injected(): Int = 1\nprivate const val Z = "' > "$I/store-bad/keys/ed25519.pub"
G :portal-device-ios:generatePortalKey -Pkeliver.portalStore="$I/store-bad" > "$L/run6.log" 2>&1; rc=$?
echo "rc=$rc  $(grep -E 'BUILD (SUCCESSFUL|FAILED)' "$L/run6.log")"
grep -m1 'not 64 hex digits' "$L/run6.log" | sed -e "s#$I#<run>#g" -e 's/^/  /'
AFTER6="$(shasum -a 256 "$OUT/PortalPublicKey.kt" 2>/dev/null | cut -c1-64)"
if grep -q 'injected' "$OUT/PortalPublicKey.kt" 2>/dev/null; then echo "RESULT 6: the key's text became SOURCE (PortalPublicKey.kt declares injected())"
elif grep -q 'not 64 hex digits' "$L/run6.log" && [ -n "$BEFORE6" ] && [ "$AFTER6" = "$BEFORE6" ]; then
  echo "RESULT 6: refused by the hex check; PortalPublicKey.kt present before and unchanged after"
else echo "RESULT 6: INCONCLUSIVE (rc=$rc, hex message $(grep -c 'not 64 hex digits' "$L/run6.log"), before ${BEFORE6:-ABSENT}, after ${AFTER6:-ABSENT})"; fi

echo "== 7. a symlink ABOVE the directory: build/generated/portalKeys -> a directory holding kotlin/canary.txt"
rm -rf "$I/uptarget"; mkdir -p "$I/uptarget/kotlin"; echo canary > "$I/uptarget/kotlin/canary.txt"
rm -rf portal-device-ios/build/generated/portalKeys; ln -s "$I/uptarget" portal-device-ios/build/generated/portalKeys
G :portal-device-ios:generatePortalKey -Pkeliver.portalStore="$I/store" > "$L/run7link.log" 2>&1; rc=$?
echo "rc=$rc  $(grep -E 'BUILD (SUCCESSFUL|FAILED)' "$L/run7link.log")"
grep -m1 'is a symbolic link' "$L/run7link.log" | sed -e "s#$PWD/##" -e 's/^/  /'
[ -f "$I/uptarget/kotlin/canary.txt" ] && echo "RESULT 7: the link's target was left alone (canary.txt present)" \
  || echo "RESULT 7: the link's TARGET WAS EMPTIED (canary.txt gone)"
rm -f portal-device-ios/build/generated/portalKeys

echo "== restore: a normal run"
G :portal-device-ios:generatePortalKey -Pkeliver.portalStore="$I/store" > "$L/run8.log" 2>&1; echo "rc=$?  directory: $(ls -A "$OUT" | tr '\n' ' ')"
