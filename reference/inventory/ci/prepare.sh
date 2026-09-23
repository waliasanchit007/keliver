#!/usr/bin/env bash
#
# Before the emulator: build the reference app from the PUBLIC tools release,
# check ingest, publish a signed v1, and build a production host that embeds
# THIS app's public key.
#
#   ci/prepare.sh <work-dir> <evidence-dir> <keliver-release-checkout> [tools.zip]
#
# <keliver-release-checkout> is a checkout of the commit the tools release was
# built from (portal-tools-v0.3.5 -> b5615637). The production host is built
# from THAT source, because no production host that renders keliver-material
# widgets is published: host/README.md in the bundle points at the Keliver
# repository, and this is the step that finds out what that costs.
#
# Isolation: every JVM here runs with user.home = <work-dir>/home, so the store,
# its keys and the relay's state are all inside <work-dir>. The repository's own
# isolation guard checks the EFFECTIVE user.home and store before each relay
# start and refuses otherwise. The keys are disposable and app-owned; nothing
# reads, prints or copies a private key.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO="$(cd "$HERE/../../.." && pwd -P)"
WORK="${1:?usage: $0 <work-dir> <evidence-dir> <keliver-release-checkout> [tools.zip]}"
EV="${2:?}"
RELEASE_SRC="$(cd "${3:?}" && pwd -P)"
ZIP="${4:-}"
mkdir -p "$WORK/home" "$EV"
WORK="$(cd "$WORK" && pwd -P)"; EV="$(cd "$EV" && pwd -P)"

pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); echo "PASS $1" >> "$EV/prepare.results"; }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); echo "FAIL $1" >> "$EV/prepare.results"; }
sha256(){ if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
: > "$EV/prepare.results"

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$WORK/home"
unset PORTAL_STORE KELIVER_USE_MAVEN_LOCAL

# --- 1. the app, from the public release ------------------------------------
echo "==> bootstrap"
if "$HERE/../bootstrap.sh" "$WORK/boot" $ZIP > "$EV/bootstrap.log" 2>&1; then
  ok "bootstrap: the app was scaffolded from the published tools 0.3.5 zip"
else
  bad "bootstrap failed"; tail -30 "$EV/bootstrap.log"; exit 1
fi
tail -8 "$EV/bootstrap.log"
APP="$WORK/boot/inventory"
export KP="$(ls -d "$WORK"/boot/tools/keliver-portal-tools-*/bin)"
cp "$APP/hand-edits.diff" "$EV/hand-edits.diff"
echo "APP=$APP" > "$WORK/env"; echo "KP=$KP" >> "$WORK/env"

( cd "$APP" && ./gradlew compileKotlinJs --console=plain ) > "$EV/compile.log" 2>&1 \
  && ok "compile: ./gradlew compileKotlinJs against Maven Central 0.3.3" \
  || { bad "compile failed"; tail -30 "$EV/compile.log"; exit 1; }

# --- 2. a relay for this app, isolated ----------------------------------------
# shellcheck source=/dev/null
. "$REPO/scripts/keliver-test-isolation-guard.sh"
keliver_require_isolated_store "$WORK" "$APP" > "$EV/guard-1.log" 2>&1 \
  && ok "isolation guard: user.home and the resolved store are inside the work dir" \
  || { bad "isolation guard refused"; cat "$EV/guard-1.log"; exit 1; }
cat "$EV/guard-1.log"

portal_up(){  # $1 = app dir, $2 = log
  ( cd "$1" && nohup "$KP/keliver-portal" --no-editor-build . > "$2" 2>&1 & )
  for _ in $(seq 1 60); do curl -sf -m 2 -o /dev/null http://localhost:8077/devstate && return 0; sleep 3; done
  return 1
}
portal_down(){ ( cd "$1" && "$KP/keliver-portal" stop . >/dev/null 2>&1 ); sleep 2; }

portal_up "$APP" "$EV/relay-prepare.log" && ok "the relay for this app answered on :8077" \
  || { bad "the relay never answered"; tail -30 "$EV/relay-prepare.log"; exit 1; }
STORE="$(cat "$APP/.gradle/keliver-store-path")"
echo "STORE=$STORE" >> "$WORK/env"
case "$STORE" in "$WORK"/*) ok "the relay's store is inside the work dir: $STORE" ;;
                 *) bad "the relay's store is OUTSIDE the work dir: $STORE"; exit 1 ;; esac
[ -f "$STORE/keys/ed25519.pub" ] && [ -f "$STORE/keys/ed25519.priv" ] \
  && ok "the relay generated this app's disposable signing identity" \
  || bad "no signing identity in the store"
PUB="$(tr -d ' \n' < "$STORE/keys/ed25519.pub")"
echo "$PUB" > "$EV/app-public-key.hex"
echo "    app public key: ${PUB:0:16}…"
ls -l "$STORE/keys" | awk 'NR>1 {print "    key file mode: " $1 "  " $NF}'

# --- 3. D1: ingest with zero RawCode -----------------------------------------
for s in inventory item; do
  curl -sf "http://localhost:8077/doc?project=default&screen=$s" > "$EV/doc-$s.json"
  n="$(grep -o 'DocNode.RawCode' "$EV/doc-$s.json" | wc -l | tr -d ' ')"
  w="$(grep -o 'DocNode.Widget' "$EV/doc-$s.json" | wc -l | tr -d ' ')"
  [ -s "$EV/doc-$s.json" ] && [ "$n" = 0 ] && [ "$w" -gt 5 ] \
    && ok "D1 $s: $w widget nodes, 0 RawCode" || bad "D1 $s: $n RawCode (of $w widgets)"
done

# --- 4. P2 precondition: publish v1, signed with this app's key --------------
curl -s -m 600 -X POST http://localhost:8077/publish > "$EV/publish-v1.log" 2>&1
grep -q 'publish OK: bundle v1' "$EV/publish-v1.log" && ok "publish v1: $(grep 'publish OK' "$EV/publish-v1.log")" \
  || { bad "publish v1 failed"; tail -20 "$EV/publish-v1.log"; }
M1="$STORE/bundles/v1/manifest.zipline.json"
python3 - "$M1" > "$EV/manifest-v1.summary" <<'PY'
import json, sys, hashlib
p = sys.argv[1]; raw = open(p, 'rb').read(); m = json.loads(raw)
sig = m.get('unsigned', {}).get('signatures', {})
print('manifest sha256', hashlib.sha256(raw).hexdigest())
print('signed by', sorted(sig))
print('modules', len(m['modules']))
PY
cat "$EV/manifest-v1.summary"
grep -q "signed by \['portal-ed25519'\]" "$EV/manifest-v1.summary" \
  && ok "v1's manifest carries a portal-ed25519 signature" || bad "v1's manifest is not signed"
portal_down "$APP"

# --- 5. P1: the production host, with THIS app's key -------------------------
# The Keliver build would otherwise resolve the Keliver checkout's own store;
# -Pkeliver.portalStore names this app's. It is a build-only override that warns.
( cd "$RELEASE_SRC" && git rev-parse HEAD > "$EV/host-source-commit" && \
  ./gradlew -q -Pkeliver.devOnlyHost=false -Pkeliver.portalStore="$STORE" \
    :portal-device-android:assembleDebug --console=plain ) > "$EV/host-build.log" 2>&1 \
  && ok "the production host built from $(cat "$EV/host-source-commit")" \
  || { bad "the production host did not build"; tail -40 "$EV/host-build.log"; exit 1; }
grep -i 'keliver:' "$EV/host-build.log" | sed 's/^/    /' | head -5
APK="$RELEASE_SRC/portal-device-android/build/outputs/apk/debug/portal-device-android-debug.apk"
cp "$APK" "$EV/production-host.apk"
sha256 "$EV/production-host.apk" | tee "$EV/production-host.apk.sha256"
EMB="$(unzip -p "$EV/production-host.apk" assets/portal_ed25519.pub 2>/dev/null | tr -d ' \n')"
[ -n "$EMB" ] && [ "$EMB" = "$PUB" ] \
  && ok "P1: the host embeds assets/portal_ed25519.pub, equal to this app's public key" \
  || bad "P1: the embedded key (${EMB:0:16}) is not this app's (${PUB:0:16})"
echo "APK=$EV/production-host.apk" >> "$WORK/env"

# Warm the development bundle so the device step does not wait on it.
( cd "$APP" && ./gradlew compileDevelopmentExecutableKotlinJsZipline --console=plain ) > "$EV/dev-bundle.log" 2>&1 \
  && ok "the development bundle builds" || bad "the development bundle did not build"

echo "prepare: passed $pass, failed $fail"
[ "$fail" -eq 0 ]
