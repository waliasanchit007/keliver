#!/usr/bin/env bash
#
# On a running emulator or device, after ci/prepare.sh:
#   1. the development route on the GENERIC host from the tools bundle: E1-E10
#   2. D14: a layout edit through the relay, a surgical diff, logic/ untouched,
#      and the rebuilt screen on the device (D2, D3)
#   3. production on the app's OWN host: signed v1, repeated actions, signed v2,
#      a bundle signed by another key rejected, and recovery (P2-P6)
#
#   ci/device.sh <work-dir> <evidence-dir> [serial]
#
# Checks, not a demo: every row of EXPECTATIONS.md is asserted on the device's
# own view hierarchy or log, and the exit status is non-zero if any fails.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO="$(cd "$HERE/../../.." && pwd -P)"
WORK="$(cd "${1:?usage: $0 <work-dir> <evidence-dir> [serial]}" && pwd -P)"
EV="$(mkdir -p "${2:?}" && cd "$2" && pwd -P)"
SERIAL="${3:-$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
[ -n "$SERIAL" ] || { echo "no adb device" >&2; exit 2; }
# shellcheck source=/dev/null
. "$WORK/env"   # APP, KP, STORE, APK
export KP JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$WORK/home"
unset PORTAL_STORE KELIVER_USE_MAVEN_LOCAL

PKGID=dev.keliver.portaldevice
ACT="$PKGID/dev.keliver.portaldevice.host.MainActivity"
pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); echo "PASS $1" >> "$EV/device.results"; }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); echo "FAIL $1" >> "$EV/device.results"; }
sha256(){ if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
: > "$EV/device.results"
drive(){ python3 "$HERE/drive.py" "$SERIAL" "$EV" "$@"; }
# drive.py's exit status is its failure count; fold its checks into ours.
fold(){ local label="$1" rc="$2"; [ "$rc" -eq 0 ] && ok "$label" || bad "$label ($rc failed checks)"; }

. "$REPO/scripts/keliver-test-isolation-guard.sh"
SERVE_PID=""
cleanup(){ [ -n "$SERVE_PID" ] && kill "$SERVE_PID" 2>/dev/null; for a in "$APP" "$WORK/foreign/inventory"; do
  [ -d "$a" ] && ( cd "$a" && "$KP/keliver-portal" stop . >/dev/null 2>&1 ); done; return 0; }
trap cleanup EXIT

portal_up(){  # $1 app, $2 log
  keliver_require_isolated_store "$WORK" "$1" >> "$EV/guard.log" 2>&1 || { bad "isolation guard refused $1"; return 1; }
  ( cd "$1" && nohup "$KP/keliver-portal" --no-editor-build . > "$2" 2>&1 & )
  for _ in $(seq 1 60); do curl -sf -m 2 -o /dev/null http://localhost:8077/devstate && return 0; sleep 3; done
  return 1
}
portal_down(){ ( cd "$1" && "$KP/keliver-portal" stop . >/dev/null 2>&1 ); sleep 3; }
launch(){  # $1 = dev|prod ; $2 = logcat file
  adb -s "$SERIAL" logcat -c || true
  adb -s "$SERIAL" shell am force-stop "$PKGID"
  if [ "$1" = prod ]; then adb -s "$SERIAL" shell am start -n "$ACT" --es mode prod >/dev/null
  else adb -s "$SERIAL" shell am start -n "$ACT" >/dev/null; fi
  sleep 15
  adb -s "$SERIAL" logcat -d > "$2"
}
serve_up(){
  ( cd "$APP" && ./gradlew serveDevelopmentZipline --console=plain > "$EV/serve-$1.log" 2>&1 ) &
  SERVE_PID=$!
  for _ in $(seq 1 60); do curl -sf -m 3 -o /dev/null http://localhost:8080/manifest.zipline.json && return 0; sleep 5; done
  return 1
}
serve_down(){
  [ -n "$SERVE_PID" ] && kill "$SERVE_PID" 2>/dev/null; pkill -f serveDevelopmentZipline 2>/dev/null; SERVE_PID=""
  # A server still answering would hand the host the PREVIOUS bundle.
  for _ in $(seq 1 30); do curl -sf -m 2 -o /dev/null http://localhost:8080/manifest.zipline.json || return 0; sleep 2; done
  bad "the previous bundle server is still answering on :8080"
}

echo "==> device $SERIAL: api $(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r'), abi $(adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"

# --- 1. the development route on the generic host ----------------------------
echo "--- 1. development route (generic host from the tools bundle)"
adb -s "$SERIAL" uninstall "$PKGID" >/dev/null 2>&1 || true
"$KP/keliver-install-device-host.sh" --serial "$SERIAL" > "$EV/install-dev-host.log" 2>&1 \
  && ok "the bundle's installer installed the generic development host" || bad "the development host did not install"
grep -iE 'sha256|checksum' "$EV/install-dev-host.log" | sed 's/^/    /'
serve_up v1 && ok "serveDevelopmentZipline is serving the app" || bad "the bundle server never answered"
launch dev "$EV/logcat-dev-1.txt"
grep -q "mode=dev" "$EV/logcat-dev-1.txt" && ok "the generic host entered the development path" || bad "no development path in the log"
drive dev Inventory; fold "E1-E10 on the development route" $?

# --- 2. D14: edit through the relay, rebuild, observe ------------------------
echo "--- 2. D14 layout edit"
portal_up "$APP" "$EV/relay-A-1.log" && ok "the relay for this app is up" || bad "the relay did not start"
( cd "$APP/src/jsMain/kotlin/logic" && sha256 *.kt ) > "$EV/logic-before.sha256"
( cd "$APP" && git status --porcelain ) > "$EV/git-status-before-edit.txt"
V="$(curl -sf "http://localhost:8077/doc?project=default&screen=inventory" | python3 -c 'import json,sys; print(json.load(sys.stdin)["version"])')"
cat > "$WORK/op-title.json" <<EOF
{"baseVersion": $V, "envelope": {"session": "reference-app", "atMillis": 0}, "ops": [
 {"kind": "dev.keliver.portal.document.DocOp.SetProp", "target": 2, "name": "text",
  "value": {"kind": "dev.keliver.portal.document.PropValue.Lit", "tag": "s", "s": "Stockroom"}},
 {"kind": "dev.keliver.portal.document.DocOp.SetProp", "target": 2, "name": "fontSize",
  "value": {"kind": "dev.keliver.portal.document.PropValue.Lit", "tag": "i", "i": 30}}]}
EOF
curl -s -X POST --data-binary @"$WORK/op-title.json" "http://localhost:8077/ops?project=default&screen=inventory" > "$EV/ops-title.json"
grep -q '"ok":true' "$EV/ops-title.json" && ok "D2: the relay applied the title edit: $(cat "$EV/ops-title.json")" || bad "D2: the edit was rejected: $(cat "$EV/ops-title.json")"
sleep 3
( cd "$APP" && git diff -- src/jsMain/kotlin/screens/inventory.kt ) > "$EV/d14-screen.diff"
( cd "$APP" && git diff --numstat ) > "$EV/d14-numstat.txt"
( cd "$APP" && git status --porcelain ) > "$EV/git-status-after-edit.txt"
cat "$EV/d14-screen.diff" | sed 's/^/    /'
[ "$(cat "$EV/d14-numstat.txt")" = "$(printf '2\t2\tsrc/jsMain/kotlin/screens/inventory.kt')" ] \
  && ok "D2: the source change is 2 lines in screens/inventory.kt and nothing else tracked" \
  || bad "D2: unexpected change set: $(tr '\n' ';' < "$EV/d14-numstat.txt")"
grep -q '^+      text = "Stockroom",' "$EV/d14-screen.diff" && grep -q '^+      fontSize = 30,' "$EV/d14-screen.diff" \
  && ok "D2: the diff is exactly the title text and size" || bad "D2: the diff is not the edit"
grep -q 'Compiled_inventory.kt' "$EV/git-status-after-edit.txt" && ok "D2: the Compiled_inventory.kt stamp appeared" || bad "D2: no version stamp"
( cd "$APP/src/jsMain/kotlin/logic" && sha256 *.kt ) > "$EV/logic-after.sha256"
cmp -s "$EV/logic-before.sha256" "$EV/logic-after.sha256" \
  && ok "D2: every file under logic/ is byte-identical after the edit" || bad "D2: logic/ changed"
serve_down; serve_up v2 && ok "rebuilt and serving the edited app" || bad "the rebuilt bundle was not served"
launch dev "$EV/logcat-dev-2.txt"
drive title Stockroom; fold "D3: the device shows the edited title" $?
serve_down

# --- 3. production on this app's own host ------------------------------------
echo "--- 3. production"
adb -s "$SERIAL" uninstall "$PKGID" >/dev/null 2>&1 || true
adb -s "$SERIAL" install -r "$APK" > "$EV/install-prod-host.log" 2>&1 \
  && ok "installed the production host $(cut -d' ' -f1 < "$EV/production-host.apk.sha256")" || bad "the production host did not install"
PUB="$(cat "$EV/app-public-key.hex")"
# P2: only v1 (title Inventory) is published, although the SOURCE now says
# Stockroom, so a screen reading Inventory is the signed bundle, not the source.
curl -sf "http://localhost:8077/bundles/latest?widgetVersion=1&caps=" > "$EV/latest-p2.json"; cat "$EV/latest-p2.json"; echo
launch prod "$EV/logcat-prod-v1.txt"
grep -q "prod mode: verifying manifests with portal-ed25519 ${PUB:0:8}" "$EV/logcat-prod-v1.txt" \
  && ok "P2: production verifies with this app's key (${PUB:0:8}…)" || bad "P2: no verification with this app's key in the log"
grep -q "refusing production mode" "$EV/logcat-prod-v1.txt" && bad "P2: the production host refused production" || ok "P2: production was not refused"
grep -q "codeLoadSuccess" "$EV/logcat-prod-v1.txt" && ok "P2: the signed v1 loaded (codeLoadSuccess)" || bad "P2: v1 did not load"
drive title Inventory; fold "P2: v1 shows Inventory in production" $?
drive prod; fold "P3: repeated actions run from the signed bundle" $?

curl -s -m 600 -X POST http://localhost:8077/publish > "$EV/publish-v2.log" 2>&1
grep -q 'publish OK: bundle v2' "$EV/publish-v2.log" && ok "P4: $(grep 'publish OK' "$EV/publish-v2.log")" || bad "P4: publish v2 failed"
sha256 "$STORE/bundles/v2/manifest.zipline.json" | tee "$EV/manifest-v2.sha256"
launch prod "$EV/logcat-prod-v2.txt"
grep -q "codeLoadSuccess" "$EV/logcat-prod-v2.txt" && ok "P4: the signed v2 loaded" || bad "P4: v2 did not load"
drive title Stockroom; fold "P4: the second signed version shows Stockroom" $?
portal_down "$APP"

# P5: another app's identity. A COPY with its inherited pointer removed — the
# guide's own instruction for a copy — gets its own store, so its own key.
mkdir -p "$WORK/foreign"
cp -R "$APP" "$WORK/foreign/inventory"
FAPP="$WORK/foreign/inventory"
rm -f "$FAPP/.gradle/keliver-store-path"
rm -rf "$FAPP/build" "$FAPP/.gradle/configuration-cache"
portal_up "$FAPP" "$EV/relay-B.log" && ok "P5: a relay for the copy is up" || bad "P5: the copy's relay did not start"
FSTORE="$(cat "$FAPP/.gradle/keliver-store-path")"
FPUB="$(tr -d ' \n' < "$FSTORE/keys/ed25519.pub")"
[ "$FSTORE" != "$STORE" ] && [ "$FPUB" != "$PUB" ] \
  && ok "P5: the copy has its own store and a different key (${FPUB:0:8}… vs ${PUB:0:8}…)" || bad "P5: the copy shares the identity"
FV="$(curl -sf "http://localhost:8077/doc?project=default&screen=inventory" | python3 -c 'import json,sys; print(json.load(sys.stdin)["version"])')"
sed -e "s/\"baseVersion\": [0-9]*/\"baseVersion\": $FV/" -e 's/"Stockroom"/"Foreign build"/' "$WORK/op-title.json" > "$WORK/op-foreign.json"
curl -s -X POST --data-binary @"$WORK/op-foreign.json" "http://localhost:8077/ops?project=default&screen=inventory" > "$EV/ops-foreign.json"
curl -s -m 600 -X POST http://localhost:8077/publish > "$EV/publish-foreign.log" 2>&1
grep -q 'publish OK' "$EV/publish-foreign.log" && ok "P5: the copy published a bundle signed with ITS key" || bad "P5: the copy did not publish"
adb -s "$SERIAL" shell pm clear "$PKGID" > /dev/null
launch prod "$EV/logcat-prod-foreign.txt"
grep -q "prod mode: verifying manifests with portal-ed25519 ${PUB:0:8}" "$EV/logcat-prod-foreign.txt" \
  && ok "P5: verification still uses this app's key" || bad "P5: verification key not seen"
grep -E "codeLoadFailed" "$EV/logcat-prod-foreign.txt" | head -3 | sed 's/^/    /'
grep -qE "codeLoadFailed.*(signature|verif)" "$EV/logcat-prod-foreign.txt" \
  && ok "P5: the foreign-signed bundle was rejected on its signature" || bad "P5: no signature rejection in the log"
grep -q "codeLoadSuccess" "$EV/logcat-prod-foreign.txt" && bad "P5: something loaded" || ok "P5: no code loaded"
python3 "$HERE/drive.py" "$SERIAL" "$EV" title "Foreign build" > "$EV/foreign-screen.txt" 2>&1
grep -q "PASS  TITLE " "$EV/foreign-screen.txt" && bad "P5: Foreign build is on screen" || ok "P5: Foreign build never appeared"
portal_down "$FAPP"

# P6: back to this app's relay; the good bundle loads again, still verified.
portal_up "$APP" "$EV/relay-A-2.log" || bad "P6: the relay did not come back"
launch prod "$EV/logcat-prod-recover.txt"
grep -q "prod mode: verifying manifests with portal-ed25519 ${PUB:0:8}" "$EV/logcat-prod-recover.txt" \
  && grep -q "codeLoadSuccess" "$EV/logcat-prod-recover.txt" \
  && ok "P6: this app's signed bundle loads again, verified" || bad "P6: no verified load after recovery"
drive title Stockroom; fold "P6: Stockroom is back" $?
portal_down "$APP"

echo "device: passed $pass, failed $fail"
[ "$fail" -eq 0 ]
