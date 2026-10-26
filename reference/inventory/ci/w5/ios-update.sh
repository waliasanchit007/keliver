# Sourced by ci/ios.sh after W2: W5's U1 on iOS. The production host, rebuilt
# with UPDATES = "on-resume" in HostConfig.kt (or, from tools 0.3.10 on, with
# UPDATES empty: a debug build's default), runs v8; v9 is published while it
# runs; the app goes to the background (Settings comes forward) and back, and
# the SAME process now shows v9. Uses ios.sh's APP, UDID, BID, APPB, WORK, EV,
# HERE, ok/bad/reads, and ios-static.sh's W3, w3_title, w3_publish,
# w3_signed_sequence.
#   U1  an update applied on resume: looked up, loaded in place, floor raised,
#       one process, the new screen shown
#   U2  a debug build checks at once when told to (a Darwin notification), twice
#       in a row: not held to the 30 s between on-resume checks
#   V1  (W6) the host POSTed its reports to REPORT_URL: loaded (8), then
#       update-applied (9), from one install
#   L1-L3  a debug host takes its bundle server from the launch environment
#       (KELIVER_BUNDLE_SERVER), refuses a malformed one, and without it uses
#       its built-in server again
#   LV1-LV3  live updates (a debug host from 2026-10-27 on, UPDATES empty):
#       a static server has no /bundles/events, so U1's host says so and
#       updates on resume; against a server that announces bundles, a publish
#       is applied in the open app with no resume; in the background the host
#       stops listening
echo "--- W5: an update applied when the app comes back to the foreground"
app_pid(){ xcrun simctl spawn "$UDID" launchctl list 2>/dev/null | awk -v b="UIKitApplication:$BID" 'index($3, b) == 1 { print $1 }' | head -1; }
CFG="$(find "$APP/host-ios/src/iosMain/kotlin" -name HostConfig.kt | head -1)"
# A host with a debug default (tools 0.3.10 on) is built with UPDATES empty, so
# U1 shows that default; an older one is set to on-resume.
if grep -qs 'fun updatesMode' "$(dirname "$CFG")/MainViewController.kt"; then U1_UPDATES=""; else U1_UPDATES="on-resume"; fi
# A host with live updates runs U1 live (its debug default) against a static
# server, which has no /bundles/events: LV1 is that fallback to on-resume.
if [ -z "$U1_UPDATES" ] && grep -qs 'bundles/events' "$(dirname "$CFG")/MainViewController.kt"; then W5_LIVE=1; else W5_LIVE=0; fi
sed -i '' -e "s|UPDATES: String = \"[^\"]*\"|UPDATES: String = \"$U1_UPDATES\"|" \
  -e 's|REPORT_URL: String = "[^"]*"|REPORT_URL: String = "https://localhost:8443/report"|' "$CFG"
grep -q "UPDATES: String = \"$U1_UPDATES\"" "$CFG" && grep -q 'REPORT_URL: String = "https://localhost:8443/report"' "$CFG" \
  && ( cd "$APP" && xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -configuration Debug \
      -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath "$WORK/dd" \
      CODE_SIGNING_ALLOWED=NO build ) > "$EV/w5-xcodebuild.log" 2>&1 \
  && ok "U1: the host rebuilt (Debug) with UPDATES = \"$U1_UPDATES\" and REPORT_URL" \
  || { bad "U1: the on-resume build failed"; grep -E '^e: |error:' "$EV/w5-xcodebuild.log" | head; }
xcrun simctl uninstall "$UDID" "$BID" > /dev/null 2>&1; xcrun simctl install "$UDID" "$APPB"
python3 "$HERE/w3/static_https.py" "$W3/site" 8443 "$W3/tls/server.pem" "$W3/tls/server.key" "$EV/w5-server.log" &
SPID=$!
for _ in $(seq 1 30); do curl -sf --cacert "$W3/tls/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json && break; sleep 1; done

w3_title Attic Garage && w3_publish v8-update && grep -q "published v8 (sequence 8, channel stable" "$EV/w3-publish-v8-update.log" && w3_signed_sequence 8 \
  && ok "U1: keliver-publish wrote v8 (\"Garage\")" || { bad "U1: publish v8"; tail -20 "$EV/w3-publish-v8-update.log"; }
# One console for the whole scenario: the process must not change.
xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
t0="$(date +%s)"
xcrun simctl launch --console-pty "$UDID" "$BID" > "$EV/U1.console.txt" 2>&1 &
CPID=$!
sleep 30
C="$EV/U1.console.txt"
grep -q "loading https://localhost:8443/bundles/v8/manifest.zipline.json (index sequence 8," "$C" && grep -q "codeLoadSuccess modules=[0-9]* sequence=8" "$C" \
  && ok "U1: the host started on v8" || bad "U1: v8 did not load: $(grep -E 'KeliverHost' "$C" | head -3 | tr '\n' ' ')"
if [ "$W5_LIVE" = 1 ]; then
  grep -q "KeliverHost: verifying manifests with portal-ed25519 ${PUB:0:8}…; updates live" "$C" \
    && ok "U1: the debug host runs with updates live (the default)" || bad "U1: the host is not set to live: $(grep -m1 'verifying manifests' "$C")"
  grep -q "KeliverHost: live updates: https://localhost:8443/bundles/events is not served (a static bundle server); updates apply on resume" "$C" \
    && ok "LV1: a static server has no /bundles/events; the host says so and falls back to on-resume" \
    || bad "LV1: no fallback line: $(grep -E 'KeliverHost: live updates' "$C" | head -3 | tr '\n' ' ')"
else
  grep -q "KeliverHost: verifying manifests with portal-ed25519 ${PUB:0:8}…; updates on-resume" "$C" \
    && ok "U1: the host runs with updates on-resume" || bad "U1: the host is not set to update on resume: $(grep -m1 'verifying manifests' "$C")"
fi
xcrun simctl io "$UDID" screenshot "$EV/U1-before.png" > /dev/null 2>&1
swift "$HERE/ocr.swift" "$EV/U1-before.png" > "$EV/U1-before.ocr.txt" 2>"$EV/U1-before.ocr.err"
pid_before="$(app_pid)"
reads U1-before Garage && ok "U1: the screen reads 'Garage' (v8)" || bad "U1: 'Garage' is not on the screen: $(tr '\n' '|' < "$EV/U1-before.ocr.txt" | cut -c1-200)"

w3_title Garage Cellar && w3_publish v9-update && grep -q "published v9 (sequence 9, channel stable" "$EV/w3-publish-v9-update.log" \
  && ok "U1: keliver-publish wrote v9 (\"Cellar\") while the app ran" || { bad "U1: publish v9"; tail -20 "$EV/w3-publish-v9-update.log"; }

# To the background (Settings comes forward) and back (forward, not relaunched),
# past the host's 30 s between lookups (its start was one).
while [ $(( $(date +%s) - t0 )) -lt 40 ]; do sleep 1; done
xcrun simctl launch "$UDID" com.apple.Preferences > /dev/null 2>&1; sleep 5
xcrun simctl launch "$UDID" "$BID" > "$EV/U1-forward.txt" 2>&1
sleep 25
xcrun simctl io "$UDID" screenshot "$EV/U1-after.png" > /dev/null 2>&1
swift "$HERE/ocr.swift" "$EV/U1-after.png" > "$EV/U1-after.ocr.txt" 2>"$EV/U1-after.ocr.err"
grep -q "KeliverHost: update check: sequence 9 is available (running 8); applying https://localhost:8443/bundles/v9/manifest.zipline.json" "$C" \
  && ok "U1: back in the foreground the host looked up and found v9" \
  || bad "U1: no update check on resume: $(grep -E 'KeliverHost' "$C" | tail -4 | tr '\n' ' ')"
grep -q "codeLoadSuccess modules=[0-9]* sequence=9" "$C" && grep -q "KeliverHost: update applied: sequence 9" "$C" \
  && grep -q "rollback floor raised: 8 -> 9" "$C" \
  && ok "U1: v9 loaded in place, applied, and the floor rose 8 -> 9" \
  || bad "U1: v9 was not applied: $(grep -E 'KeliverHost|codeLoad' "$C" | tail -4 | tr '\n' ' ')"
pid_after="$(app_pid)"
# The update lines above are in the first launch's console, and the process id
# is unchanged: no relaunch took v9.
[ -n "$pid_before" ] && [ "$pid_before" = "$pid_after" ] && [ "$(grep -c 'KeliverHost: verifying manifests' "$C")" = 1 ] \
  && ok "U1: the same process (pid $pid_after) took v9, with one start" || bad "U1: the process changed ($pid_before -> $pid_after) or started again"
reads U1-after Cellar && ok "U1: the running app now reads 'Cellar' (v9)" \
  || bad "U1: 'Cellar' is not on the screen: $(tr '\n' '|' < "$EV/U1-after.ocr.txt" | cut -c1-200)"

# U2: "check now" in a debug build, from the computer: two Darwin notifications
# a few seconds apart, each a lookup (up to date on v9), in the same process.
if [ -z "$U1_UPDATES" ]; then
  U2_NAME="$BID.keliver.check-for-update"
  n0="$(grep -c 'KeliverHost: update check: up to date (sequence 9)' "$C")"
  xcrun simctl spawn "$UDID" notifyutil -p "$U2_NAME"; sleep 6
  xcrun simctl spawn "$UDID" notifyutil -p "$U2_NAME"; sleep 6
  n1="$(grep -c 'KeliverHost: update check: up to date (sequence 9)' "$C")"
  [ "$(grep -c "KeliverHost: update check requested by $U2_NAME (a debug build)" "$C")" = 2 ] && [ $(( n1 - n0 )) = 2 ] \
    && ! grep -q 'update check skipped' "$C" && [ "$(app_pid)" = "$pid_before" ] \
    && ok "U2: two '$U2_NAME' notifications, two lookups at once (up to date on v9), same process" \
    || bad "U2: the debug check-now hook: $(grep -E 'KeliverHost: update check' "$C" | tail -4 | tr '\n' ' ')"
else
  echo "  SKIP  U2: this host predates the debug check-now hook (not counted)"
fi

kill "$CPID" 2>/dev/null; wait "$CPID" 2>/dev/null
xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
sed -i '' -e 's|UPDATES: String = "[^"]*"|UPDATES: String = "next-launch"|' -e 's|REPORT_URL: String = "[^"]*"|REPORT_URL: String = ""|' "$CFG"
python3 "$HERE/w5/reports.py" "$EV/w5-server.log" ios > "$EV/w6-reports.txt" 2>&1 \
  && ok "V1: the host reported loaded (sequence 8, network) and then update-applied (sequence 9) to its report URL" \
  || bad "V1: $(tail -1 "$EV/w6-reports.txt")"

# L1-L3: a DEBUG host takes its bundle server from the launch environment
# (KELIVER_BUNDLE_SERVER), so a new tunnel URL needs no rebuild. The installed
# build names https://localhost:8443; a second server on 8444 serves the same
# site. L3 relaunches without it, so the cached last good bundle is 8443's again
# for the rows after this one.
# Only a host scaffolded from these templates has the override: a hand-run
# (published-only) route builds from the released tools zip, which may not.
if grep -rqs KELIVER_BUNDLE_SERVER "$APP/host-ios/src/iosMain/kotlin"; then
  echo "--- a debug host takes its bundle server from the launch environment"
  python3 "$HERE/w3/static_https.py" "$W3/site" 8444 "$W3/tls/server.pem" "$W3/tls/server.key" "$EV/l1-server.log" &
  LPID=$!
  for _ in $(seq 1 30); do curl -sf --cacert "$W3/tls/ca.pem" -m 2 -o /dev/null https://localhost:8444/bundles/index.json && break; sleep 1; done
  env_launch() { # <name> <KELIVER_BUNDLE_SERVER or empty>
    xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
    if [ -n "$2" ]; then
      SIMCTL_CHILD_KELIVER_BUNDLE_SERVER="$2" xcrun simctl launch --console-pty "$UDID" "$BID" > "$EV/$1.console.txt" 2>&1 &
    else
      xcrun simctl launch --console-pty "$UDID" "$BID" > "$EV/$1.console.txt" 2>&1 &
    fi
    local pid=$!; sleep 25; kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
  }
  env_launch L1 https://localhost:8444
  C="$EV/L1.console.txt"
  grep -q "KeliverHost: bundle server from the launch environment (a debug build): https://localhost:8444" "$C" \
    && grep -q "loading https://localhost:8444/bundles/v9/manifest.zipline.json (index sequence 9," "$C" \
    && grep -q "codeLoadSuccess modules=[0-9]* sequence=9" "$C" && grep -q "GET /bundles/index.json" "$EV/l1-server.log" \
    && ok "L1: launched with KELIVER_BUNDLE_SERVER=https://localhost:8444, the debug host verified and loaded v9 from there" \
    || bad "L1: the launch environment's server was not used: $(grep -E 'KeliverHost' "$C" | head -3 | tr '\n' ' ')"
  for bad_server in "ftp://localhost:8444" "https://localhost:8444/?x=1"; do
    env_launch L2 "$bad_server"
    grep -qF "KeliverHost: refusing to load: the bundle server '$bad_server' is not an http(s) URL" "$EV/L2.console.txt" \
      && ! grep -q "codeLoadSuccess" "$EV/L2.console.txt" \
      && ok "L2: a launch environment server $bad_server is refused, and nothing loads" \
      || bad "L2: $bad_server was not refused: $(grep -E 'KeliverHost' "$EV/L2.console.txt" | head -3 | tr '\n' ' ')"
  done
  kill "$LPID" 2>/dev/null; wait "$LPID" 2>/dev/null
  env_launch L3 ""
  grep -q "loading https://localhost:8443/bundles/v9/manifest.zipline.json" "$EV/L3.console.txt" \
    && ! grep -q "launch environment" "$EV/L3.console.txt" \
    && ok "L3: launched without it, the host is back on its built-in https://localhost:8443" \
    || bad "L3: the host did not return to its built-in server: $(grep -E 'KeliverHost' "$EV/L3.console.txt" | head -3 | tr '\n' ' ')"
  xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
else
  echo "  SKIP  L1-L3: this host predates the launch-environment bundle server (not counted)"
fi

# LV2-LV3: live updates against a server that announces bundles. Its own copy of
# the site and its own port (from the launch environment, as L1), so v10 here
# never reaches the main site, whose v10 is W8's. The app is reinstalled
# afterwards: this copy's v10 raises the rollback floor to 10.
if [ "$W5_LIVE" = 1 ] && grep -rqs KELIVER_BUNDLE_SERVER "$APP/host-ios/src/iosMain/kotlin"; then
  echo "--- live updates: a publish reaches the open app"
  rm -rf "$W3/site-live" && cp -R "$W3/site" "$W3/site-live"
  python3 "$HERE/w3/static_https.py" "$W3/site-live" 8445 "$W3/tls/server.pem" "$W3/tls/server.key" "$EV/lv-server.log" events &
  LVPID=$!
  for _ in $(seq 1 30); do curl -sf --cacert "$W3/tls/ca.pem" -m 2 -o /dev/null https://localhost:8445/bundles/index.json && break; sleep 1; done
  xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
  SIMCTL_CHILD_KELIVER_BUNDLE_SERVER=https://localhost:8445 xcrun simctl launch --console-pty "$UDID" "$BID" > "$EV/LV.console.txt" 2>&1 &
  CPID=$!
  sleep 25
  C="$EV/LV.console.txt"
  grep -q "KeliverHost: live updates: listening to https://localhost:8445/bundles/events" "$C" \
    && grep -q "codeLoadSuccess modules=[0-9]* sequence=9" "$C" \
    && ok "LV2: the debug host runs v9 and listens to /bundles/events while its screen shows" \
    || bad "LV2: not listening: $(grep -E 'KeliverHost' "$C" | tail -4 | tr '\n' ' ')"
  xcrun simctl io "$UDID" screenshot "$EV/LV2-before.png" > /dev/null 2>&1
  swift "$HERE/ocr.swift" "$EV/LV2-before.png" > "$EV/LV2-before.ocr.txt" 2>"$EV/LV2-before.ocr.err"
  reads LV2-before Cellar && ok "LV2: v9 shows 'Cellar'" || bad "LV2: 'Cellar' is not on the screen: $(tr '\n' '|' < "$EV/LV2-before.ocr.txt" | cut -c1-200)"
  pid_live="$(app_pid)"
  lines_before="$(wc -l < "$C")"
  # The edit and publish a developer makes while the app is open; nothing on the simulator is touched.
  w3_title Cellar Loft \
    && ( cd "$APP" && "$PUBLISH" . --out "$W3/site-live" --public-key-file "$STORE/keys/ed25519.pub" ) > "$EV/w3-publish-v10-live.log" 2>&1 \
    && grep -q "published v10 (sequence 10, channel stable" "$EV/w3-publish-v10-live.log" \
    && ok "LV2: keliver-publish wrote v10 (\"Loft\") to the live server's site while the app showed v9" \
    || { bad "LV2: publish v10 to the live site"; tail -20 "$EV/w3-publish-v10-live.log"; }
  sleep 20
  L="$EV/LV2.console.txt"; tail -n +"$((lines_before + 1))" "$C" > "$L"
  grep -q 'KeliverHost: live updates: the bundle server says {"version":10}' "$L" \
    && grep -q "KeliverHost: update check: sequence 10 is available (running 9); applying https://localhost:8445/bundles/v10/manifest.zipline.json" "$L" \
    && grep -q "codeLoadSuccess modules=[0-9]* sequence=10" "$L" && grep -q "KeliverHost: update applied: sequence 10" "$L" \
    && ok "LV2: the server announced v10 and the open app looked it up, verified it and applied it" \
    || bad "LV2: v10 was not applied live: $(grep -E 'KeliverHost|codeLoad' "$L" | tail -5 | tr '\n' ' ')"
  [ -n "$pid_live" ] && [ "$(app_pid)" = "$pid_live" ] && ! grep -q "KeliverHost: verifying manifests" "$L" \
    && ! grep -q "update check skipped" "$L" \
    && ok "LV2: the same process (pid $pid_live), no restart and no resume" || bad "LV2: the process changed or restarted"
  xcrun simctl io "$UDID" screenshot "$EV/LV2-after.png" > /dev/null 2>&1
  swift "$HERE/ocr.swift" "$EV/LV2-after.png" > "$EV/LV2-after.ocr.txt" 2>"$EV/LV2-after.ocr.err"
  reads LV2-after Loft && ok "LV2: the open app now reads 'Loft' (v10)" || bad "LV2: 'Loft' is not on the screen: $(tr '\n' '|' < "$EV/LV2-after.ocr.txt" | cut -c1-200)"
  closed_before="$(grep -c 'GET /bundles/events closed' "$EV/lv-server.log")"
  # To the background: Settings comes forward.
  xcrun simctl launch "$UDID" com.apple.Preferences > /dev/null 2>&1
  # The server notices a closed connection only when a keep-alive write fails,
  # 15-30 s after the close. Wait up to 45 s for it.
  for _ in $(seq 1 45); do
    [ "$(grep -c 'GET /bundles/events closed' "$EV/lv-server.log")" -gt "$closed_before" ] && break
    sleep 1
  done
  grep -q "KeliverHost: live updates: stopped listening (the app is in the background)" "$C" \
    && [ "$(grep -c 'GET /bundles/events closed' "$EV/lv-server.log")" -gt "$closed_before" ] \
    && ok "LV3: in the background the host stops listening, and its connection is closed (the server saw it go)" \
    || bad "LV3: still listening in the background: $(grep -E 'KeliverHost: live' "$C" | tail -3 | tr '\n' ' '); server: $(tail -3 "$EV/lv-server.log" | tr '\n' ' ')"
  kill "$CPID" 2>/dev/null; wait "$CPID" 2>/dev/null
  kill "$LVPID" 2>/dev/null; wait "$LVPID" 2>/dev/null
  w3_title Loft Cellar || bad "LV: could not put the title back to Cellar for W8"
  xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
  xcrun simctl uninstall "$UDID" "$BID" > /dev/null 2>&1; xcrun simctl install "$UDID" "$APPB"
else
  echo "  SKIP  LV2-LV3: this host predates live updates or the launch-environment bundle server (not counted)"
fi
kill "$SPID" 2>/dev/null; wait "$SPID" 2>/dev/null; SPID=""
