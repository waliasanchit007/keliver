#!/usr/bin/env bash
#
# The reference app's production route on iOS: a tools release (the PUBLIC
# 0.3.7 asset by default, or a pinned release candidate), a signed publish, and
# a production host scaffolded by that zip's own bin/keliver-new-ios-host.sh,
# run on an iOS simulator.
#
#   ci/ios.sh <work-dir> <evidence-dir> <keliver-portal-tools-X.Y.Z.zip>
#
# The zip must be the published 0.3.7 asset; its hash is pinned below. To check
# a release CANDIDATE instead, set KELIVER_CANDIDATE_SHA256 to the candidate
# zip's sha256 (from its build run); the version is then read from the zip's
# VERSION.json, and the results say "candidate", never "published".
#
# iOS P-checks, the counterparts of device.sh's P1–P7:
#   P1  the host embeds this app's public key; it builds and installs as its own app
#   P2  signed v1 loads, the screen reads "Inventory", no empty-URL load (U28)
#   P4  an edit, published as v2, reaches the host: "Stockroom"
#   P5  a copy of the app publishes with ITS key; the host refuses that bundle
#   P6  back on this app's relay, its bundle loads again
#   P7  relay down: the host starts from its cached bundle, verified
# P3 (repeated taps) has no iOS driver here and is not run.
# The 0.3.6 relay serves no bundles/index.json, so these also show the host's
# fallback to /bundles/latest on a 404.
#
# Then W3/W4, the static route with no relay (ci/w3/ios-static.sh): S2,
# S4-S15 against a static HTTPS server fed only by keliver-publish; and W2, the
# host framework embedded in an existing SwiftUI app (ci/w2/ios-embed.sh).
#
# The simulator has no view-hierarchy dump. What a screen shows is read from
# its screenshot by macOS Vision (ci/ocr.swift), and every screenshot is kept.
#
# Isolation: every JVM runs with user.home = <work-dir>/home, behind the
# repository's isolation guard. The keys are disposable and app-owned. Nothing
# here reads, prints or copies a private key; the app's signing block is the
# only reader, on each publish (the U31-fixed block from 0.3.7 on: the key is
# never on a command line, in a log or in .gradle/).
set -uo pipefail

TOOLS_VERSION=0.3.7
TOOLS_SHA256=75ce0928fdf0a3a759c92641bcba23727360ed7ff8d995fb7207c1c11c03d8cb
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REF="$(cd "$HERE/.." && pwd -P)"
REPO="$(cd "$HERE/../../.." && pwd -P)"
WORK="${1:?usage: $0 <work-dir> <evidence-dir> <tools.zip>}"
EV="${2:?}"
ZIP="${3:?}"
mkdir -p "$WORK/home" "$EV"
WORK="$(cd "$WORK" && pwd -P)"; EV="$(cd "$EV" && pwd -P)"

pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); echo "PASS $1" >> "$EV/ios.results"; }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); echo "FAIL $1" >> "$EV/ios.results"; }
sha256(){ if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
: > "$EV/ios.results"

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$WORK/home"
unset PORTAL_STORE KELIVER_USE_MAVEN_LOCAL

# --- 1. the app, from the tools zip -------------------------------------------
got="$(sha256 "$ZIP" | cut -d' ' -f1)"
if [ -n "${KELIVER_CANDIDATE_SHA256:-}" ]; then
  [ "$got" = "$KELIVER_CANDIDATE_SHA256" ] || { bad "the tools zip is $got, not the candidate $KELIVER_CANDIDATE_SHA256"; exit 1; }
  TOOLS_VERSION="$(unzip -p "$ZIP" '*/VERSION.json' | python3 -c 'import json,sys; print(json.load(sys.stdin)["toolsVersion"])')" \
    || { bad "the candidate zip has no readable VERSION.json"; exit 1; }
  ok "the tools zip is the CANDIDATE $TOOLS_VERSION ($got), not a published release"
  LABEL="candidate $TOOLS_VERSION"
else
  [ "$got" = "$TOOLS_SHA256" ] || { bad "the tools zip is $got, not the published $TOOLS_SHA256"; exit 1; }
  ok "the tools zip is the published $TOOLS_VERSION asset (${TOOLS_SHA256:0:8}…)"
  LABEL="published $TOOLS_VERSION"
fi
mkdir -p "$WORK/tools" && ( cd "$WORK/tools" && unzip -q "$ZIP" )
KP="$WORK/tools/keliver-portal-tools-$TOOLS_VERSION/bin"
( cd "$WORK" && "$KP/keliver-init" Inventory ) > "$EV/init.log" 2>&1 || { bad "keliver-init failed"; exit 1; }
APP="$WORK/inventory"
(
  set -e
  cd "$APP"
  rm src/jsMain/kotlin/screens/home.kt src/jsMain/kotlin/logic/HomePresenter.kt
  cp "$REF"/app/src/jsMain/kotlin/screens/*.kt src/jsMain/kotlin/screens/
  cp "$REF"/app/src/jsMain/kotlin/logic/*.kt src/jsMain/kotlin/logic/
  "$KP/keliver-new-device-target.sh" --screen InventoryScreen --presenter InventoryPresenter
  # The files the scaffolders cannot produce (bootstrap.sh's overlay).
  for f in keliver.portal.json build.gradle settings.gradle src/jsMain/kotlin/device/Main.kt; do cp "$REF/app/$f" "$f"; done
  "$KP/keliver-new-publish-target.sh"
  git init -q && git add -A && git -c user.name=ci -c user.email=ci@invalid commit -qm "inventory, tools $LABEL"
) > "$EV/app.log" 2>&1 && ok "the app: keliver-init, device target and publish target from tools $LABEL" \
  || { bad "recreating the app failed"; tail -20 "$EV/app.log"; exit 1; }

# --- 2. a relay for this app, isolated -----------------------------------------
# shellcheck source=/dev/null
. "$REPO/scripts/keliver-test-isolation-guard.sh"
portal_up(){  # $1 = app dir, $2 = log
  keliver_require_isolated_store "$WORK" "$1" >> "$EV/guard.log" 2>&1 || { echo "isolation guard refused $1" >&2; return 1; }
  # A relay this run did not start would answer every check below.
  local free=0
  for _ in $(seq 1 10); do curl -s -m 2 -o /dev/null http://localhost:8077/devstate || { free=1; break; }; sleep 2; done
  [ "$free" = 1 ] || { echo ":8077 already answers: a relay this run did not start" >&2; return 1; }
  ( cd "$1" && nohup "$KP/keliver-portal" --no-editor-build . > "$2" 2>&1 & )
  for _ in $(seq 1 60); do curl -sf -m 2 -o /dev/null http://localhost:8077/devstate && return 0; sleep 3; done
  return 1
}
portal_down(){ ( cd "$1" && "$KP/keliver-portal" stop . >/dev/null 2>&1 ); sleep 2; }
# Installed before anything is started, so every exit stops what this run started.
UDID=""; FAPP=""; SPID=""
cleanup(){
  [ -n "$SPID" ] && kill "$SPID" 2>/dev/null
  if [ -n "$UDID" ]; then xcrun simctl shutdown "$UDID" >/dev/null 2>&1; xcrun simctl delete "$UDID" >/dev/null 2>&1; fi
  portal_down "$APP"; [ -n "$FAPP" ] && portal_down "$FAPP"
  return 0
}
trap cleanup EXIT
portal_up "$APP" "$EV/relay-A-1.log" && ok "the relay for this app answered on :8077" \
  || { bad "the relay never answered"; tail -30 "$EV/relay-A-1.log"; exit 1; }
STORE="$(cat "$APP/.gradle/keliver-store-path")"
case "$STORE" in "$WORK"/*) ok "the relay's store is inside the work dir" ;; *) bad "the store is OUTSIDE the work dir: $STORE"; exit 1 ;; esac
PUB="$(tr -d ' \n' < "$STORE/keys/ed25519.pub")"
echo "$PUB" > "$EV/app-public-key.hex"
ls -l "$STORE/keys" | awk 'NR>1 {print "key file mode: " $1 "  " $NF}' | tee "$EV/key-modes.txt"

curl -s -m 900 -X POST http://localhost:8077/publish > "$EV/publish-v1.log" 2>&1
grep -q 'publish OK: bundle v1' "$EV/publish-v1.log" && ok "publish v1: $(grep 'publish OK' "$EV/publish-v1.log" | cut -c1-80)" \
  || { bad "publish v1 failed"; tail -20 "$EV/publish-v1.log"; }
cp "$STORE/bundles/v1/manifest.zipline.json" "$EV/manifest-v1.zipline.json" 2>/dev/null

# --- 3. P1: the iOS host, scaffolded, with THIS app's key ----------------------
BID=inventory.ioshost
# By default the zip's own copy is what is under test (published since 0.3.7),
# so a missing or non-executable one fails here; there is no silent fallback.
# KELIVER_SCAFFOLD_FROM=repo selects this repository's scripts/ explicitly: the
# W3 workflows set it, because W3's index-reading host template exists only here
# until a release ships it; switch it off once one does.
case "${KELIVER_SCAFFOLD_FROM:-zip}" in
  zip)
    if [ -x "$KP/keliver-new-ios-host.sh" ]; then IOS_SCAFFOLD="$KP/keliver-new-ios-host.sh"; WHICH="the tools $LABEL zip's bin/"
    else bad "P1: the tools zip has no executable bin/keliver-new-ios-host.sh"; exit 1; fi ;;
  repo) IOS_SCAFFOLD="$REPO/scripts/keliver-new-ios-host.sh"; WHICH="this repository's scripts/ (W3, unreleased)" ;;
  *) bad "KELIVER_SCAFFOLD_FROM must be zip or repo, not ${KELIVER_SCAFFOLD_FROM}"; exit 1 ;;
esac
( cd "$APP" && "$IOS_SCAFFOLD" --bundle-server http://localhost:8077 --bundle-id "$BID" ) \
  > "$EV/ios-host-scaffold.log" 2>&1 && ok "P1: keliver-new-ios-host.sh ($WHICH) scaffolded host-ios/" \
  || { bad "P1: the iOS host was not scaffolded"; cat "$EV/ios-host-scaffold.log"; exit 1; }
CFG="$(find "$APP/host-ios/src/iosMain/kotlin" -name HostConfig.kt | head -1)"
grep -q "PORTAL_PUBLIC_KEY_HEX: String = \"$PUB\"" "$CFG" \
  && ok "P1: HostConfig.kt embeds this app's public key (${PUB:0:8}…)" || bad "P1: the embedded key is not this app's"
( cd "$APP" && xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -configuration Debug \
    -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath "$WORK/dd" \
    CODE_SIGNING_ALLOWED=NO build ) > "$EV/xcodebuild.log" 2>&1 \
  && ok "P1: xcodebuild built the host from Maven Central" \
  || { bad "P1: xcodebuild failed"; grep -E '^e: |error:' "$EV/xcodebuild.log" | head -20; exit 1; }
APPB="$(find "$WORK/dd/Build/Products/Debug-iphonesimulator" -maxdepth 1 -name '*.app' | head -1)"
( cd "$APP/host-ios" && ../gradlew -q dependencies --configuration iosSimulatorArm64CompileKlibraries ) > "$EV/host-dependencies.txt" 2>&1
grep -oE 'dev\.keliver:[a-z0-9-]+:[0-9.]+' "$EV/host-dependencies.txt" | sort -u > "$EV/host-keliver-artifacts.txt"
echo "    $(wc -l < "$EV/host-keliver-artifacts.txt" | tr -d ' ') dev.keliver artifacts: $(cut -d: -f3 "$EV/host-keliver-artifacts.txt" | sort -u | tr '\n' ' ')"

# A simulator of our own: the newest iOS runtime, an iPhone it supports.
SIM="$(python3 - <<'PY'
import json, subprocess
j = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', '-j', 'runtimes']))
rts = [r for r in j['runtimes'] if r.get('isAvailable') and r.get('platform', r['name']).startswith('iOS')]
rt = sorted(rts, key=lambda r: [int(x) for x in r['version'].split('.')])[-1]
# Prefer an iPhone model this runtime ships a default device for (the current
# models); fall back to any iPhone it supports.
devs = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', '-j', 'devices', 'available']))
shipped = [d['deviceTypeIdentifier'] for d in devs['devices'].get(rt['identifier'], [])
           if d['name'].startswith('iPhone') and 'deviceTypeIdentifier' in d]
phones = [d['identifier'] for d in rt.get('supportedDeviceTypes', []) if d['name'].startswith('iPhone')]
print(rt['identifier'], (shipped or phones)[0])
PY
)"
UDID="$(xcrun simctl create keliver-ios-ci ${SIM#* } ${SIM% *})" || { bad "could not create a simulator ($SIM)"; exit 1; }
echo "simulator: $UDID ($SIM)" | tee "$EV/simulator.txt"
xcrun simctl boot "$UDID" && xcrun simctl bootstatus "$UDID" -b > /dev/null 2>&1
xcrun simctl install "$UDID" "$APPB" && ok "P1: the host installed as its own app ($BID)" || bad "P1: install failed"

# launch <label>: a fresh process; its console (the KeliverHost: lines), a
# screenshot taken while it runs, and what that screenshot reads.
launch(){
  local label="$1" pid
  xcrun simctl terminate "$UDID" "$BID" >/dev/null 2>&1
  xcrun simctl launch --console-pty "$UDID" "$BID" > "$EV/$label.console.txt" 2>&1 &
  pid=$!
  sleep 30
  xcrun simctl io "$UDID" screenshot "$EV/$label.png" > /dev/null 2>&1
  kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
  # A failed reading must not let a "never appeared" check pass.
  if swift "$HERE/ocr.swift" "$EV/$label.png" > "$EV/$label.ocr.txt" 2>"$EV/$label.ocr.err" && [ -s "$EV/$label.ocr.txt" ]; then
    printf '    %s: %s\n' "$label" "$(head -3 "$EV/$label.ocr.txt" | tr '\n' '|')"
  else
    bad "$label: the screenshot could not be read ($(head -1 "$EV/$label.ocr.err"))"
  fi
}
reads(){ grep -qx "$2" "$EV/$1.ocr.txt"; }   # the screen has a line exactly equal to $2

# --- P2 -------------------------------------------------------------------------
launch P2-v1
C="$EV/P2-v1.console.txt"
grep -q "KeliverHost: verifying manifests with portal-ed25519 ${PUB:0:8}" "$C" && ok "P2: the host verifies with this app's key" || bad "P2: no verification line"
grep -q "KeliverHost: loading http://localhost:8077/bundles/v1/manifest.zipline.json" "$C" && grep -q "codeLoadSuccess" "$C" \
  && ok "P2: signed v1 loaded (codeLoadSuccess)" || bad "P2: v1 did not load"
grep -q "KeliverHost: no bundles/index.json at http://localhost:8077; asking bundles/latest" "$C" \
  && ok "P2: the 0.3.6 relay serves no index; the host fell back to /bundles/latest on the 404" \
  || bad "P2: no fallback to /bundles/latest in the console"
grep -q "codeLoadFailed" "$C" && bad "P2: a load failed (U28 would be an empty-URL failure): $(grep codeLoadFailed "$C" | head -1)" \
  || ok "P2: no failed load, so no empty-URL attempt (U28 absent)"
reads P2-v1 Inventory && ok "P2: the screen reads 'Inventory'" || bad "P2: 'Inventory' is not on the screen"

# --- P4 -------------------------------------------------------------------------
V="$(curl -sf "http://localhost:8077/doc?project=default&screen=inventory" | python3 -c 'import json,sys; print(json.load(sys.stdin)["version"])')"
cat > "$WORK/op-title.json" <<EOF
{"baseVersion": $V, "envelope": {"session": "reference-app-ios", "atMillis": 0}, "ops": [
 {"kind": "dev.keliver.portal.document.DocOp.SetProp", "target": 2, "name": "text",
  "value": {"kind": "dev.keliver.portal.document.PropValue.Lit", "tag": "s", "s": "Stockroom"}},
 {"kind": "dev.keliver.portal.document.DocOp.SetProp", "target": 2, "name": "fontSize",
  "value": {"kind": "dev.keliver.portal.document.PropValue.Lit", "tag": "i", "i": 30}}]}
EOF
curl -s -X POST --data-binary @"$WORK/op-title.json" "http://localhost:8077/ops?project=default&screen=inventory" > "$EV/ops-title.json"
grep -q '"ok":true' "$EV/ops-title.json" && ok "P4: the relay applied the title edit" || bad "P4: the edit was rejected: $(cat "$EV/ops-title.json")"
sleep 3
curl -s -m 900 -X POST http://localhost:8077/publish > "$EV/publish-v2.log" 2>&1
grep -q 'publish OK: bundle v2' "$EV/publish-v2.log" && ok "P4: publish v2" || bad "P4: publish v2 failed"
cp "$STORE/bundles/v2/manifest.zipline.json" "$EV/manifest-v2.zipline.json" 2>/dev/null
launch P4-v2
grep -q "loading http://localhost:8077/bundles/v2/manifest.zipline.json" "$EV/P4-v2.console.txt" && grep -q "codeLoadSuccess" "$EV/P4-v2.console.txt" \
  && ok "P4: the host loaded v2" || bad "P4: v2 did not load"
reads P4-v2 Stockroom && ok "P4: the screen reads 'Stockroom'" || bad "P4: 'Stockroom' is not on the screen"
portal_down "$APP"

# --- P5 -------------------------------------------------------------------------
# Another app's identity: a COPY with its inherited pointer removed gets its own
# store, so its own key. A fresh install, so no cache or saved URL helps.
mkdir -p "$WORK/foreign"
cp -R "$APP" "$WORK/foreign/inventory"
FAPP="$WORK/foreign/inventory"
rm -rf "$FAPP/.gradle/keliver-store-path" "$FAPP/build" "$FAPP/host-ios/build"
portal_up "$FAPP" "$EV/relay-B.log" && ok "P5: a relay for the copy is up" || bad "P5: the copy's relay did not start"
FSTORE="$(cat "$FAPP/.gradle/keliver-store-path")"
FPUB="$(tr -d ' \n' < "$FSTORE/keys/ed25519.pub")"
[ "$FSTORE" != "$STORE" ] && [ "$FPUB" != "$PUB" ] && ok "P5: the copy has its own store and key (${FPUB:0:8}… vs ${PUB:0:8}…)" \
  || bad "P5: the copy shares the identity"
FV="$(curl -sf "http://localhost:8077/doc?project=default&screen=inventory" | python3 -c 'import json,sys; print(json.load(sys.stdin)["version"])')"
sed -e "s/\"baseVersion\": [0-9]*/\"baseVersion\": $FV/" -e 's/"Stockroom"/"Foreign build"/' "$WORK/op-title.json" > "$WORK/op-foreign.json"
curl -s -X POST --data-binary @"$WORK/op-foreign.json" "http://localhost:8077/ops?project=default&screen=inventory" > "$EV/ops-foreign.json"
curl -s -m 900 -X POST http://localhost:8077/publish > "$EV/publish-foreign.log" 2>&1
grep -q 'publish OK' "$EV/publish-foreign.log" && ok "P5: the copy published a bundle signed with ITS key" || bad "P5: the copy did not publish"
xcrun simctl uninstall "$UDID" "$BID" && xcrun simctl install "$UDID" "$APPB"
launch P5-foreign
C="$EV/P5-foreign.console.txt"
grep -q "verifying manifests with portal-ed25519 ${PUB:0:8}" "$C" && ok "P5: verification still uses this app's key" || bad "P5: verification key not seen"
grep -qE "codeLoadFailed.*(signature|verif)" "$C" && ok "P5: the foreign-signed bundle was refused on its signature" \
  || bad "P5: no signature refusal: $(grep -E 'codeLoad' "$C" | head -2 | tr '\n' ' ')"
grep -q "codeLoadSuccess" "$C" && bad "P5: something loaded" || ok "P5: no code loaded"
# On its own this is weak: a refused load leaves the screen blank. The console
# lines above (a signature refusal, no codeLoadSuccess) are the proof.
reads P5-foreign "Foreign build" && bad "P5: 'Foreign build' is on the screen" \
  || ok "P5: 'Foreign build' is not on the screen (it reads $(grep -c . "$EV/P5-foreign.ocr.txt") line(s): blank but for the status bar)"
portal_down "$FAPP"

# --- P6 -------------------------------------------------------------------------
portal_up "$APP" "$EV/relay-A-2.log" || bad "P6: the relay did not come back"
launch P6-recover
grep -q "codeLoadSuccess" "$EV/P6-recover.console.txt" && ok "P6: this app's signed bundle loads again" || bad "P6: no load after recovery"
reads P6-recover Stockroom && ok "P6: the screen reads 'Stockroom'" || bad "P6: 'Stockroom' is not on the screen"
portal_down "$APP"

# --- P7 -------------------------------------------------------------------------
curl -sf -m 2 -o /dev/null http://localhost:8077/devstate && bad "P7: a relay is still answering" || ok "P7: no bundle server is answering"
launch P7-offline
C="$EV/P7-offline.console.txt"
grep -q "lookup failed; starting from the cached bundle" "$C" && ok "P7: the lookup failed and the host started from its cache" \
  || bad "P7: it did not start from the cache"
grep -q "codeLoadSuccess" "$C" && ok "P7: code loaded offline (codeLoadSuccess), verified with this app's key" || bad "P7: no load offline"
reads P7-offline Stockroom && ok "P7: the screen reads 'Stockroom' offline" || bad "P7: 'Stockroom' is not on the screen"

# shellcheck source=w3/ios-static.sh
. "$HERE/w3/ios-static.sh"
# shellcheck source=w2/ios-embed.sh
. "$HERE/w2/ios-embed.sh"

for f in "$EV"/*.png; do echo "$(basename "$f"): $(python3 "$HERE/shot.py" "$f")"; done > "$EV/shots.results"
echo "ios: passed $pass, failed $fail"
[ "$fail" -eq 0 ]
