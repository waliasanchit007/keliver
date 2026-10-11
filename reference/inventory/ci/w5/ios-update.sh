# Sourced by ci/ios.sh after W2: W5's U1 on iOS. The production host, rebuilt
# with UPDATES = "on-resume" in HostConfig.kt, runs v8; v9 is published while it
# runs; the app goes to the background (Settings comes forward) and back, and
# the SAME process now shows v9. Uses ios.sh's APP, UDID, BID, APPB, WORK, EV,
# HERE, ok/bad/reads, and ios-static.sh's W3, w3_title, w3_publish,
# w3_signed_sequence.
#   U1  an update applied on resume: looked up, loaded in place, floor raised,
#       one process, the new screen shown
#   V1  (W6) the host POSTed its reports to REPORT_URL: loaded (8), then
#       update-applied (9), from one install
echo "--- W5: an update applied when the app comes back to the foreground"
app_pid(){ xcrun simctl spawn "$UDID" launchctl list 2>/dev/null | awk -v b="UIKitApplication:$BID" 'index($3, b) == 1 { print $1 }' | head -1; }
CFG="$(find "$APP/host-ios/src/iosMain/kotlin" -name HostConfig.kt | head -1)"
sed -i '' -e 's|UPDATES: String = "[^"]*"|UPDATES: String = "on-resume"|' \
  -e 's|REPORT_URL: String = "[^"]*"|REPORT_URL: String = "https://localhost:8443/report"|' "$CFG"
grep -q 'UPDATES: String = "on-resume"' "$CFG" && grep -q 'REPORT_URL: String = "https://localhost:8443/report"' "$CFG" \
  && ( cd "$APP" && xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -configuration Debug \
      -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath "$WORK/dd" \
      CODE_SIGNING_ALLOWED=NO build ) > "$EV/w5-xcodebuild.log" 2>&1 \
  && ok "U1: the host rebuilt with UPDATES = \"on-resume\" and REPORT_URL" \
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
grep -q "KeliverHost: verifying manifests with portal-ed25519 ${PUB:0:8}…; updates on-resume" "$C" \
  && ok "U1: the host runs with updates on-resume" || bad "U1: the host is not set to update on resume: $(grep -m1 'verifying manifests' "$C")"
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

kill "$CPID" 2>/dev/null; wait "$CPID" 2>/dev/null
xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
sed -i '' -e 's|UPDATES: String = "[^"]*"|UPDATES: String = "next-launch"|' -e 's|REPORT_URL: String = "[^"]*"|REPORT_URL: String = ""|' "$CFG"
python3 "$HERE/w5/reports.py" "$EV/w5-server.log" ios > "$EV/w6-reports.txt" 2>&1 \
  && ok "V1: the host reported loaded (sequence 8, network) and then update-applied (sequence 9) to its report URL" \
  || bad "V1: $(tail -1 "$EV/w6-reports.txt")"
kill "$SPID" 2>/dev/null; wait "$SPID" 2>/dev/null; SPID=""
