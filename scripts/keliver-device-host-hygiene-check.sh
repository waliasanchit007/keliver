#!/usr/bin/env bash
#
# keliver-device-host-hygiene-check — U22 regression.
#
#   scripts/keliver-device-host-hygiene-check.sh <parent-dir>
#
# The generic development host must contain NO portal key, whatever the build
# directory saw before it. The original defect was environmental: assembleDebug
# copied <store>/keys/ed25519.pub into the APK whenever the build machine had a
# store, so a locally built tools bundle shipped its BUILDER'S portal identity.
# `copyPortalKey` was a Copy with onlyIf, so a key copied by an earlier build
# stayed in the output directory after its input disappeared — a warm build
# directory could smuggle it into a later APK.
#
# The matrix, all with DISPOSABLE keys and stores. The real store is never read,
# written or cleaned.
#
#   1. build with key A present          -> APK contains key A
#   2. dev-only build, same build dir    -> APK contains NO key          <-- the fix
#   3. build with key B, same build dir  -> APK contains key B, not A
#   4. build with no key at all          -> APK contains NO key
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
PARENT="${1:?usage: $0 <parent-dir>}"

# shellcheck source=/dev/null
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "$PARENT" devhost)" || exit 1
echo "run dir: $DISP"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
APK="$ROOT/portal-device-android/build/outputs/apk/debug/portal-device-android-debug.apk"

pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }

# Two disposable "portal stores", each with its own fake public key.
mkdir -p "$DISP/storeA/keys" "$DISP/storeB/keys" "$DISP/storeEmpty"
printf 'aaaaaaaa%s\n' "$(printf 'a%.0s' $(seq 1 56))" > "$DISP/storeA/keys/ed25519.pub"
printf 'bbbbbbbb%s\n' "$(printf 'b%.0s' $(seq 1 56))" > "$DISP/storeB/keys/ed25519.pub"

# Which key, if any, is inside the APK right now.
embedded_key() {
  if ! unzip -l "$APK" 2>/dev/null | grep -q 'assets/portal_ed25519.pub'; then
    echo "NONE"; return
  fi
  unzip -p "$APK" 'assets/portal_ed25519.pub' 2>/dev/null | tr -d '\r\n' | cut -c1-8
}

build() { # build <label> <store-or-empty> <devOnly true|false>
  local label="$1" store="$2" devonly="$3"
  printf '  ....  building: %s\n' "$label"
  ( cd "$ROOT" && ./gradlew -q --console=plain \
      -Pkeliver.portalStore="$store" -Pkeliver.devOnlyHost="$devonly" \
      :portal-device-android:assembleDebug ) \
    >"$DISP/build-$label.log" 2>&1 \
    || { bad "$label: gradle failed"; tail -5 "$DISP/build-$label.log"; return 1; }
}

# 1. a normal app-specific production host, key A
build A "$DISP/storeA" false || exit 1
K="$(embedded_key)"
[ "$K" = "aaaaaaaa" ] && ok "1. an app host built against store A embeds key A" \
  || bad "1. expected key A, APK has '$K'"

# 2. THE FIX: the generic development host, in the SAME warm build directory
build devonly "$DISP/storeA" true || exit 1
K="$(embedded_key)"
[ "$K" = "NONE" ] && ok "2. the development host embeds NO key, on a warm build dir that just built key A" \
  || bad "2. THE DEVELOPMENT HOST CARRIES A KEY: '$K'"

# 3. a different production host in the same directory must not inherit A
build B "$DISP/storeB" false || exit 1
K="$(embedded_key)"
[ "$K" = "bbbbbbbb" ] && ok "3. an app host built against store B embeds key B, not A" \
  || bad "3. expected key B, APK has '$K'"

# 4. no store at all: nothing to embed, and nothing left over
build none "$DISP/storeEmpty" false || exit 1
K="$(embedded_key)"
[ "$K" = "NONE" ] && ok "4. no store means no key, with no residue from B" \
  || bad "4. expected no key, APK has '$K'"

# Leave the tree in the state a release build wants.
build final "$DISP/storeEmpty" true || exit 1
K="$(embedded_key)"
[ "$K" = "NONE" ] && ok "5. the release-shaped build is key-free" || bad "5. APK has '$K'"

echo
echo "passed: $pass   failed: $fail"
[ "$fail" -eq 0 ]
