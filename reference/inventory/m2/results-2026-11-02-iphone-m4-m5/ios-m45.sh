#!/usr/bin/env bash
#
# As run from a scratch directory on 2026-11-02 (its paths and the device's ids replaced below).
# M4's guest frame profile and M5's memory profile on an iPhone 13, from host-ios scaffolded by the PUBLISHED tools 0.3.17 zip, Debug build
# (a Release framework refuses http://, and the phone would need a profile installed to trust a
# test CA), against the reference app's static site (sequence 9) served read-only over
# http://<this Mac's Wi-Fi address>:8497.
#
#   ios-m45.sh <app.app> <tap-derived-data-dir|none> [runs]
#   ("none": no tap driver (it needs an App ID registered by an Xcode account); frames lines are
#   then only checked for being well formed, as CI's T3 does without its driver)
#
# Per run:
#   frames  a launch with its console (devicectl --console); after the content mark, the
#           XCTest tap driver (ci/m0/ios-tap, built for the phone with the app's signing team,
#           its runner's App ID registered in that team) opens Espresso beans and taps
#           Add 1 twice, each observed; then frames.py --changed on the console (CI's T3)
#   memory  a launch of its own; after its content, two memory cycles (the Darwin
#           notification <bundle id>.keliver.memory-cycle, 25 s apart); then memory.py (T4)
# Both apps are uninstalled and the server stopped at the end.
set -uo pipefail
P=<scratch>/phone-m45
APPB="${1:?the built .app}"; TAPDIR="${2:?the tap driver derived data dir}"; RUNS="${3:-3}"
CI=<checkout>/reference/inventory/ci
DEV="<the phone's devicectl id>"; UDID="<the phone's UDID>"
BID=dev.keliver.local.phonerun; SITE="$P/site"; IP="<this Mac's Wi-Fi address>"; PORT=8497
EV="$P/ev-ios"; mkdir -p "$EV"
log(){ echo "$*" | tee -a "$EV/run.log"; }

lsof -nP -iTCP:$PORT -sTCP:LISTEN >/dev/null 2>&1 && { log "something already listens on $PORT"; exit 2; }
python3 -m http.server "$PORT" --bind "$IP" --directory "$SITE" > "$EV/server.log" 2>&1 &
SERVER=$!; CONSOLE=""; INSTALLED=""
cleanup(){
  [ -n "$CONSOLE" ] && kill "$CONSOLE" 2>/dev/null
  kill "$SERVER" 2>/dev/null
  if [ -n "$INSTALLED" ]; then
    xcrun devicectl device uninstall app --device "$DEV" "$BID" > /dev/null 2>&1
    xcrun devicectl device uninstall app --device "$DEV" dev.keliver.reference.iostap.xctrunner > /dev/null 2>&1
  fi
  return 0
}
trap cleanup EXIT
for _ in $(seq 1 20); do curl -sf -m 2 "http://$IP:$PORT/bundles/index.json" | cmp -s - "$SITE/bundles/index.json" && break; sleep 0.5; done
xcrun devicectl device install app --device "$DEV" "$APPB" > "$EV/install.log" 2>&1 || { log "install failed: $(tail -3 "$EV/install.log")"; exit 1; }
INSTALLED=1

pid_of(){
  xcrun devicectl device info processes --device "$DEV" --json-output "$EV/ps.json" > /dev/null 2>&1
  python3 -I "$P/pidof.py" "$EV/ps.json" "$(basename "$APPB")"
}
stop_app(){
  local pid; pid="$(pid_of)"
  [ -n "$pid" ] && xcrun devicectl device process terminate --device "$DEV" --pid "$pid" > /dev/null 2>&1
  [ -n "$CONSOLE" ] && { kill "$CONSOLE" 2>/dev/null; wait "$CONSOLE" 2>/dev/null; CONSOLE=""; }
  sleep 2
}
launch(){  # $1 label: a fresh process, its console in $EV/$1.console.txt; 0 once content is in
  stop_app
  xcrun devicectl device process launch --device "$DEV" --console --terminate-existing "$BID" > "$EV/$1.console.txt" 2>&1 &
  CONSOLE=$!
  for _ in $(seq 1 120); do grep -q "KeliverTiming: content [0-9]" "$EV/$1.console.txt" && break; sleep 0.5; done
  grep -q "KeliverTiming: content [0-9]" "$EV/$1.console.txt" || { log "$1: no content mark: $(grep -E 'KeliverHost:|rror' "$EV/$1.console.txt" | tail -3 | tr '\n' ' ')"; return 1; }
  grep -qE "codeLoadSuccess modules=[0-9]+ sequence=9" "$EV/$1.console.txt" || { log "$1: sequence 9 did not load"; return 1; }
}
tap(){  # $1 label: Add 1 twice in the running host, through the XCTest driver
  TEST_RUNNER_TAP_BUNDLE_ID="$BID" TEST_RUNNER_TAP_COUNT=2 TEST_RUNNER_TAP_FROM="" \
    xcodebuild test-without-building -project "$CI/m0/ios-tap/IosTap.xcodeproj" -scheme IosTap \
      -destination "id=$UDID" -derivedDataPath "$TAPDIR" -resultBundlePath "$EV/$1-tap.xcresult" > "$EV/$1-tap.log" 2>&1
  grep -q "Test Case '-\[IosTapUITests.AddOne testAddOne\]' passed" "$EV/$1-tap.log"
}

log "== $(date -u +%FT%TZ) $BID on $DEV, app $(basename "$APPB"), $RUNS runs"
for r in $(seq 1 "$RUNS"); do
  L="frames-$r"
  if launch "$L"; then
    if [ "$TAPDIR" = none ]; then
      sleep 8
      log "$L: no tap driver (launch frames only): $(python3 "$CI/m0/frames.py" "$EV/$L.console.txt" 2>&1)"
    elif tap "$L"; then
      sleep 3
      log "$L: Add 1 twice: ok ($(grep -c 'AddOne: F[0-9]* ' "$EV/$L-tap.log") steps); $(python3 "$CI/m0/frames.py" --changed "$EV/$L.console.txt" 2>&1)"
    else
      log "$L: the tap failed: $(grep -E 'error: |AddOne: ' "$EV/$L-tap.log" | head -3 | cut -c1-200 | tr '\n' ' ')"
    fi
  fi
  stop_app
  L="memory-$r"
  if launch "$L"; then
    sleep 5
    xcrun devicectl device notification post --device "$DEV" --name "$BID.keliver.memory-cycle" > /dev/null 2>&1; sleep 25
    xcrun devicectl device notification post --device "$DEV" --name "$BID.keliver.memory-cycle" > /dev/null 2>&1; sleep 25
    log "$L: $(python3 "$CI/m0/memory.py" "$EV/$L.console.txt" 2>&1)"
  fi
  stop_app
done
log "== done $(date -u +%FT%TZ)"
