#!/usr/bin/env bash
#
# keliver-gateb-ios-sim — Gate B part c on a real iOS simulator app (docs/COMPOSE_GENERATOR_DESIGN.md §21).
#
#   scripts/keliver-gateb-ios-sim.sh [--out DIR] [--port PORT] [--runs N] [--debug] [--with-tests] [--capture]
#   scripts/keliver-gateb-ios-sim.sh --compare-only --out DIR   (re-runs only the comparison and the report on DIR)
#
# Exits non-zero if a guest launch did not verify the manifest with the scratch key, load its code,
# or draw; if a widget was unknown; or if any sample's native and guest screenshots differ by a
# pixel or in size.
#
# Self-contained, from this checkout, prototype modules only (nothing is published):
#   1. builds the same-source samples as a SIGNED Zipline guest bundle
#      (keliver-compose-gatea-sample-guest; a scratch Ed25519 key in its build dir) and a
#      Kotlin-only iOS simulator app (keliver-compose-gatea-sample-ios) that trusts that key and
#      registers the generated gatea renderers (Gradle, with -Pkeliver.gatebIos);
#   2. serves the bundle from 127.0.0.1:PORT (default 8494; never 8077, 8096 or 8478);
#   3. CREATES a simulator (newest iOS runtime, an iPhone), with --with-tests first runs
#      :keliver-compose-gatea-composeui:iosSimulatorArm64Test on it, then boots it, fixes its
#      status bar, installs the app;
#   4. for each sample (orders, card, item: Gate B-d's ItemDetail) launches the app twice — the screen drawn by native
#      Compose, and the same file loaded from the signed bundle through the real protocol into the
#      generated renderers — screenshots each (xcrun simctl io screenshot) and compares the pixels;
#      With --capture, also the device script's pixel route here: each sample and mode launched
#      with GATEB_CAPTURE (the app records its Compose content into a PNG in its Documents), the
#      PNGs compared into DIR/capture/results.md (0 differing pixels; the control must differ);
#   5. times first frames: N process-cold launches per route (terminate, 1 s, launch), alternating.
#      The page cache is warm and the guest's Zipline module cache was filled by step 4, so a guest
#      launch is manifest verification over loopback plus modules from the local cache, not a first
#      download. The app logs main() to the second frame after its root was first composed;
#   6. deletes the simulator and stops the server, whatever happened.
# Results: DIR/results.md, DIR/*.png (native, guest, diff), DIR/logs/. A run first removes the
# files an earlier run left there (only those names). The phone counterpart is
# scripts/keliver-gateb-ios-device.sh; both report through scripts/keliver-gateb-report.py.
#
# Needs: macOS with Xcode (xcrun simctl, codesign), python3, JDK 17 for Gradle.
set -euo pipefail
NAME=keliver-gateb-ios-sim
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
fail() { echo "$NAME: $*" >&2; exit 1; }

OUT="$ROOT/build/$NAME"; PORT=8494; RUNS=5; TYPE=release; TESTS=0; COMPARE_ONLY=0; CAPTURE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="${2:?--out needs a directory}"; shift 2 ;;
    --port) PORT="${2:?--port needs a number}"; shift 2 ;;
    --runs) RUNS="${2:?--runs needs a number}"; shift 2 ;;
    --debug) TYPE=debug; shift ;;
    --with-tests) TESTS=1; shift ;;
    --compare-only) COMPARE_ONLY=1; shift ;;
    --capture) CAPTURE=1; shift ;;
    -h|--help) sed -n '2,36p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "$NAME: unknown argument $1 (see --help)" >&2; exit 2 ;;
  esac
done
case "$PORT" in ''|*[!0-9]*) fail "--port must be a number" ;; esac
case "$RUNS" in ''|*[!0-9]*) fail "--runs must be a number" ;; esac
case "$PORT" in 8077|8096|8478) fail "port $PORT belongs to the owner's portal or the iOS CI relay; choose another" ;; esac
command -v python3 >/dev/null || fail "python3 is needed."
SAMPLES="orders card item"

# The comparison and the report (scripts/keliver-gateb-report.py, shared with the device script).
# Exits non-zero if any sample differs; the control must differ.
report() { # device runtime build bundle-bytes
  python3 -I "$ROOT/scripts/keliver-gateb-report.py" "$OUT" "Gate B part c: the simulator run" \
    "$1, ${2##*.}" "$3" "$4" "$SAMPLES" \
    "Process-cold launches (terminate, 1 s, launch), alternating; the page cache is warm and the guest's Zipline module cache was filled by the screenshot launches, so a guest launch is manifest verification over loopback plus modules from the local cache. A simulator is not a phone: the Mac's CPU and GPU run it, so the times are for comparing the two routes here, not for absolute numbers."
}

if [ "$COMPARE_ONLY" = 1 ]; then
  [ -d "$OUT" ] || fail "--compare-only needs an existing --out directory"
  OUT="$(cd "$OUT" && pwd -P)"
  report "(compare only)" "-" "-" "-"
  exit 0
fi

command -v xcrun >/dev/null || fail "xcrun is needed (Xcode)."
mkdir -p "$OUT/logs"
OUT="$(cd "$OUT" && pwd -P)"
# An earlier run's output would mix into this one's report: remove it (only the names this script writes).
for sample in $SAMPLES; do rm -f "$OUT/$sample-native.png" "$OUT/$sample-guest.png" "$OUT/$sample-diff.png"; done
rm -f "$OUT/timing.txt" "$OUT/results.md" "$OUT"/logs/shot-*.log "$OUT"/logs/time-*.log \
  "$OUT/logs/gradle.log" "$OUT/logs/server.log" "$OUT/logs/ios-test.log"
rm -rf "$OUT/ios-test-captures" "$OUT/capture"

UDID=""; SERVER_PID=""
cleanup() {
  set +e
  if [ -n "$UDID" ]; then
    xcrun simctl terminate "$UDID" dev.keliver.gateb >/dev/null 2>&1
    xcrun simctl shutdown "$UDID" >/dev/null 2>&1
    xcrun simctl delete "$UDID" >/dev/null 2>&1 && echo "$NAME: deleted simulator $UDID"
  fi
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null && echo "$NAME: stopped the bundle server"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# 1. Build the bundle and the app.
echo "$NAME: building the guest bundle and the $TYPE app (Gradle; log in $OUT/logs/gradle.log)"
# The release link's devirtualization pass is heavy: it runs in its own process with its own heap,
# not in a shared daemon (whose heap ran out once).
( cd "$ROOT" && ./gradlew --console=plain -Pkeliver.gatebIos -PgatebBuildType="$TYPE" -DRELEASE_SIGNING_ENABLED=false \
    -Pkotlin.native.disableCompilerDaemon=true -Pkotlin.native.jvmArgs=-Xmx6g \
    :keliver-compose-gatea-sample-guest:compileProductionExecutableKotlinJsZipline \
    :keliver-compose-gatea-sample-ios:assembleGateBApp ) > "$OUT/logs/gradle.log" 2>&1 \
  || { grep -E '^e: |What went wrong' -A4 "$OUT/logs/gradle.log" | head -30 >&2; fail "the build failed; see $OUT/logs/gradle.log"; }
BUNDLE="$(dirname "$(find "$ROOT/keliver-compose-gatea-sample-guest/build/zipline" -name manifest.zipline.json -path '*roduction*' | head -1)")"
[ -f "$BUNDLE/manifest.zipline.json" ] || fail "no production manifest under keliver-compose-gatea-sample-guest/build/zipline"
grep -q '"gateb-ed25519"' "$BUNDLE/manifest.zipline.json" || fail "the manifest is not signed with the scratch key"
APP="$ROOT/keliver-compose-gatea-sample-ios/build/gateb/$TYPE/GateB.app"
[ -x "$APP/GateB" ] || fail "no app at $APP"
codesign --force --sign - "$APP" >/dev/null 2>&1 || fail "ad-hoc signing $APP failed"
BUNDLE_BYTES="$(find "$BUNDLE" -name '*.zipline' -exec cat {} + | wc -c | tr -d ' ')"
echo "$NAME: bundle $BUNDLE ($BUNDLE_BYTES bytes of .zipline modules)"

# 2. Serve it.
python3 -I -m http.server "$PORT" --bind 127.0.0.1 --directory "$BUNDLE" > "$OUT/logs/server.log" 2>&1 &
SERVER_PID=$!
MANIFEST="http://127.0.0.1:$PORT/manifest.zipline.json"
for _ in $(seq 1 50); do curl -sf -m 1 -o /dev/null "$MANIFEST" && break; sleep 0.2; done
curl -sf -m 2 -o /dev/null "$MANIFEST" || fail "the bundle server did not answer on $PORT (is the port taken?)"
# Something answered; make sure it is our server, not another one already on the port.
kill -0 "$SERVER_PID" 2>/dev/null || { cat "$OUT/logs/server.log" >&2; SERVER_PID=""; fail "the bundle server exited (port $PORT taken?)"; }

# 3. A simulator of our own.
read -r RUNTIME DEVTYPE DEVNAME < <(xcrun simctl list -j runtimes devicetypes | python3 -I -c '
import json, sys
d = json.load(sys.stdin)
ios = [r for r in d["runtimes"] if r.get("platform") == "iOS" and r.get("isAvailable")]
if not ios: sys.exit("no available iOS runtime")
r = max(ios, key=lambda r: tuple(int(x) for x in r["version"].split(".")))
supported = {t["identifier"]: t["name"] for t in r.get("supportedDeviceTypes", [])} or {t["identifier"]: t["name"] for t in d["devicetypes"]}
phones = [i for i, n in supported.items() if n.startswith("iPhone") and "Pro" not in n and "Plus" not in n and "Max" not in n and " e" not in n and "SE" not in n]
phones = phones or [i for i, n in supported.items() if n.startswith("iPhone")]
pick = sorted(phones)[-1]
print(r["identifier"], pick, supported[pick].replace(" ", "_"))
') || fail "no iOS runtime or iPhone device type"
UDID="$(xcrun simctl create "keliver-gateb-$$" "$DEVTYPE" "$RUNTIME")" || fail "simctl create failed"
echo "$NAME: created simulator $UDID (${DEVNAME//_/ }, $RUNTIME)"

if [ "$TESTS" = 1 ]; then
  echo "$NAME: :keliver-compose-gatea-composeui:iosSimulatorArm64Test on it (log in $OUT/logs/ios-test.log)"
  ( cd "$ROOT" && ./gradlew --console=plain -Pkeliver.iosTestDevice="$UDID" -DRELEASE_SIGNING_ENABLED=false \
      :keliver-compose-gatea-composeui:iosSimulatorArm64Test ) > "$OUT/logs/ios-test.log" 2>&1 \
    || { tail -40 "$OUT/logs/ios-test.log" >&2; fail "the iOS same-source test failed"; }
  cp -R "$ROOT/keliver-compose-gatea-composeui/build/gate-ios/iosSimulatorArm64Test" "$OUT/ios-test-captures" 2>/dev/null || true
  xcrun simctl shutdown "$UDID" >/dev/null 2>&1 || true
fi

xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b > /dev/null
xcrun simctl status_bar "$UDID" override --time 9:41 --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100 >/dev/null 2>&1 || true
xcrun simctl ui "$UDID" appearance light >/dev/null 2>&1 || true
xcrun simctl install "$UDID" "$APP"

# Launches the app process-cold (terminate, 1 s, launch) and waits for its first-frame line in the
# launched mode; prints the milliseconds.
launch() { # mode sample tag [capture file name]
  local log="$OUT/logs/$3.log"
  xcrun simctl terminate "$UDID" dev.keliver.gateb >/dev/null 2>&1 || true
  sleep 1
  : > "$log"
  # The app appends its log lines to the file simctl hands it as GATEB_LOG; GATEB_CAPTURE (only on
  # --capture's launches) makes it write the device script's in-app capture.
  if [ -n "${4:-}" ]; then
    SIMCTL_CHILD_GATEB_LOG="$log" SIMCTL_CHILD_GATEB_CAPTURE="$4" xcrun simctl launch "$UDID" dev.keliver.gateb "$1" "$2" "$MANIFEST" >/dev/null
  else
    SIMCTL_CHILD_GATEB_LOG="$log" xcrun simctl launch "$UDID" dev.keliver.gateb "$1" "$2" "$MANIFEST" >/dev/null
  fi
  local line ms
  for _ in $(seq 1 300); do
    # One read of the first-frame line, then judge that same line (it must be in the launched mode).
    line="$(grep -m1 'GATEB first-frame' "$log" || true)"
    if [ -n "$line" ]; then
      ms="$(printf '%s\n' "$line" | sed -n "s/.*GATEB first-frame mode=$1 ms=\([0-9][0-9]*\)\$/\1/p")"
      [ -n "$ms" ] && { echo "$ms"; return 0; }
      break
    fi
    grep -q 'codeLoadFailed\|uncaughtException\|manifestParseFailed\|unknownWidget' "$log" && break
    sleep 0.2
  done
  echo "$NAME: no first frame in mode $1 for $2; its log:" >&2; cat "$log" >&2; return 1
}

# A guest launch must have verified the manifest with the scratch key, loaded its code, and met no
# unknown widget.
check_guest_log() { # tag
  local log="$OUT/logs/$1.log"
  grep -q 'manifestVerified key=gateb-ed25519 ' "$log" || { cat "$log" >&2; fail "$1: the manifest was not verified with the scratch key"; }
  grep -q 'codeLoadSuccess' "$log" || { cat "$log" >&2; fail "$1: no codeLoadSuccess"; }
  ! grep -q 'unknownWidget' "$log" || { cat "$log" >&2; fail "$1: an unknown widget"; }
}

# 4. Screenshots and pixels.
for sample in $SAMPLES; do
  for mode in native guest; do
    ms="$(launch "$mode" "$sample" "shot-$sample-$mode")" || fail "the $mode $sample screen did not draw"
    sleep 2 # settle (no animation in the samples; shadows and images are drawn by then)
    xcrun simctl io "$UDID" screenshot "$OUT/$sample-$mode.png" >/dev/null 2>&1
    if [ "$mode" = guest ]; then check_guest_log "shot-$sample-$mode"; fi
    echo "$NAME: $sample/$mode drawn (first frame $ms ms), $OUT/$sample-$mode.png"
  done
done

# 4b. With --capture: the device script's pixel route on this simulator. The app records its Compose
# content and writes a PNG into its Documents; compared like the screenshots, into DIR/capture/.
if [ "$CAPTURE" = 1 ]; then
  mkdir -p "$OUT/capture"
  DATA="$(xcrun simctl get_app_container "$UDID" dev.keliver.gateb data)" || fail "no data container for the app"
  for sample in $SAMPLES; do
    for mode in native guest; do
      tag="capture-$sample-$mode"
      rm -f "$DATA/Documents/$tag.png"
      launch "$mode" "$sample" "$tag" "$tag.png" >/dev/null || fail "the $mode $sample capture launch did not draw"
      for _ in $(seq 1 75); do grep -q "GATEB capture" "$OUT/logs/$tag.log" && break; sleep 0.2; done
      grep -q "GATEB capture saved $tag.png" "$OUT/logs/$tag.log" || { cat "$OUT/logs/$tag.log" >&2; fail "$tag: no capture saved"; }
      if [ "$mode" = guest ]; then check_guest_log "$tag"; fi
      cp "$DATA/Documents/$tag.png" "$OUT/capture/$sample-$mode.png"
      echo "$NAME: $sample/$mode $(grep -o 'capture saved.*' "$OUT/logs/$tag.log")"
    done
  done
  python3 -I "$ROOT/scripts/keliver-gateb-report.py" "$OUT/capture" "Gate B part c: the in-app capture on the simulator" \
    "${DEVNAME//_/ }, ${RUNTIME##*.}" "$TYPE" "-" "$SAMPLES" "No timing: capture launches only." \
    || fail "the in-app captures differ (see $OUT/capture/results.md)"
fi

# 5. First-frame timing: alternating process-cold launches (see the header for what is warm).
: > "$OUT/timing.txt"
for i in $(seq 1 "$RUNS"); do
  for mode in native guest; do
    ms="$(launch "$mode" orders "time-$mode-$i")" || fail "timing launch $i ($mode) did not draw"
    if [ "$mode" = guest ]; then check_guest_log "time-$mode-$i"; fi
    echo "$mode $ms" >> "$OUT/timing.txt"
  done
done
xcrun simctl terminate "$UDID" dev.keliver.gateb >/dev/null 2>&1 || true

report "${DEVNAME//_/ }" "$RUNTIME" "$TYPE" "$BUNDLE_BYTES" || fail "the comparison failed (differing screens, or the report itself; see above and $OUT/results.md)"
echo "$NAME: results in $OUT/results.md"
