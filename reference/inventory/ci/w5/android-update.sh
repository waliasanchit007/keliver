# Sourced by ci/device.sh after W2: W5's U1 on Android. The production host,
# built with keliver.updates=on-resume, or from tools 0.3.10 on with it unset (a
# debug build's default) (W5_APK, prepare.sh), runs v8; v9 is
# published while it runs; the app goes to the background and comes back, and
# the SAME process now shows v9. Uses device.sh's SERIAL, EV, HERE, PROD_ID,
# HOST_TAG, ok/bad/drive/fold, and android-static.sh's W3, W3_TLS, w3_title,
# w3_publish, w3_signed_sequence.
#   U1  an update applied on resume: looked up, loaded in place, floor raised,
#       one process, the new screen shown
#   U2  a debug build checks at once when told to from adb, twice in a row:
#       not held to the 30 s between on-resume checks
#   V1  (W6) the host POSTed its reports to the report URL: loaded (8), then
#       update-applied (9), from one install
#   L1-L3  a debug host takes a bundle server set on the device
#       (files/keliver/bundle-server), refuses a malformed one, and without it
#       uses its built-in server again
#   LV1-LV3  live updates (a debug host from 2026-10-27 on, keliver.updates
#       unset): against a static server (no /bundles/events) it says so and
#       falls back to on-resume (U1 above is that fallback); against a server
#       that announces bundles, a publish made while a screen shows is applied
#       in the same process with no trip to the background; in the background
#       it stops listening
echo "--- 6. W5: an update applied when the app comes back to the foreground"
python3 "$HERE/w3/static_https.py" "$W3/site" 8443 "$W3_TLS/server.pem" "$W3_TLS/server.key" "$EV/w5-server.log" &
SERVE_PID=$!
for _ in $(seq 1 30); do curl -sf --cacert "$W3_TLS/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json && break; sleep 1; done

w3_title Attic Garage && w3_publish v8-update && grep -q "published v8 (sequence 8, channel stable" "$EV/w3-publish-v8-update.log" && w3_signed_sequence 8 \
  && ok "U1: keliver-publish wrote v8 (\"Garage\")" || { bad "U1: publish v8"; tail -20 "$EV/w3-publish-v8-update.log"; }
adb -s "$SERIAL" install -r "$W5_APK" > "$EV/install-update-host.log" 2>&1 \
  && ok "U1: installed the host built for on-resume updates" || bad "U1: the on-resume host did not install"
adb -s "$SERIAL" shell pm clear "$PROD_ID" > /dev/null
t0="$(date +%s)"
launch prod "$EV/logcat-update-v8.txt"
if grep -rqs 'bundles/events' "$APP/host-android/src/main/kotlin"; then W5_LIVE=1; else W5_LIVE=0; fi
if [ "$W5_LIVE" = 1 ]; then
  sleep 3  # the listener's first request
  adb -s "$SERIAL" logcat -d > "$EV/logcat-update-v8.txt"
  grep -q "$HOST_TAG: verifying manifests with portal-ed25519 ${PUB:0:8}…; updates live" "$EV/logcat-update-v8.txt" \
    && ok "U1: the debug host runs with updates live (the default)" || bad "U1: the host is not set to live: $(grep -m1 'verifying manifests' "$EV/logcat-update-v8.txt")"
  grep -q "$HOST_TAG: live updates: https://$HOST_ADDR:8443/bundles/events is not served (a static bundle server); updates apply on resume" "$EV/logcat-update-v8.txt" \
    && ok "LV1: a static server has no /bundles/events; the host says so and falls back to on-resume" \
    || bad "LV1: no fallback line: $(grep -E "$HOST_TAG: live updates" "$EV/logcat-update-v8.txt" | head -3 | tr '\n' ' ')"
else
  grep -q "$HOST_TAG: verifying manifests with portal-ed25519 ${PUB:0:8}…; updates on-resume" "$EV/logcat-update-v8.txt" \
    && ok "U1: the host runs with updates on-resume" || bad "U1: the host is not set to update on resume: $(grep -m1 'verifying manifests' "$EV/logcat-update-v8.txt")"
fi
grep -q "$HOST_TAG: loading https://$HOST_ADDR:8443/bundles/v8/manifest.zipline.json (index sequence 8," "$EV/logcat-update-v8.txt" \
  && grep -q "codeLoadSuccess modules=[0-9]* sequence=8" "$EV/logcat-update-v8.txt" \
  && ok "U1: the host started on v8" || bad "U1: v8 did not load: $(grep -E "$HOST_TAG" "$EV/logcat-update-v8.txt" | head -3 | tr '\n' ' ')"
drive title Garage U1before; fold "U1: v8 shows Garage" $?
pid_before="$(adb -s "$SERIAL" shell pidof "$PROD_ID" | tr -d '\r')"

# While it runs: v9. (Publishing takes longer than the host's 30 s between checks.)
w3_title Garage Cellar && w3_publish v9-update && grep -q "published v9 (sequence 9, channel stable" "$EV/w3-publish-v9-update.log" \
  && ok "U1: keliver-publish wrote v9 (\"Cellar\") while the app ran" || { bad "U1: publish v9"; tail -20 "$EV/w3-publish-v9-update.log"; }

# To the background and back: the launcher intent brings the running task forward.
# Past the host's 30 s between lookups first (its start was one).
while [ $(( $(date +%s) - t0 )) -lt 40 ]; do sleep 1; done
adb -s "$SERIAL" logcat -c || true
adb -s "$SERIAL" shell input keyevent KEYCODE_HOME; sleep 5
adb -s "$SERIAL" shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
  -n "$(adb -s "$SERIAL" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PROD_ID" | tr -d '\r' | tail -1)" > /dev/null
sleep 20
adb -s "$SERIAL" logcat -d > "$EV/logcat-update-resume.txt"
L="$EV/logcat-update-resume.txt"
grep -q "$HOST_TAG: update check: sequence 9 is available (running 8); applying https://$HOST_ADDR:8443/bundles/v9/manifest.zipline.json" "$L" \
  && ok "U1: back in the foreground the host looked up and found v9" \
  || bad "U1: no update check on resume: $(grep -E "$HOST_TAG" "$L" | head -4 | tr '\n' ' ')"
grep -q "codeLoadSuccess modules=[0-9]* sequence=9" "$L" && grep -q "$HOST_TAG: update applied: sequence 9" "$L" \
  && grep -q "$HOST_TAG: rollback floor raised: 8 -> 9" "$L" \
  && ok "U1: v9 loaded in place, applied, and the floor rose 8 -> 9" \
  || bad "U1: v9 was not applied: $(grep -E "$HOST_TAG|codeLoad" "$L" | tail -4 | tr '\n' ' ')"
pid_after="$(adb -s "$SERIAL" shell pidof "$PROD_ID" | tr -d '\r')"
! grep -q "$HOST_TAG: verifying manifests" "$L" && [ -n "$pid_before" ] && [ "$pid_before" = "$pid_after" ] \
  && ok "U1: the same process (pid $pid_after), no new start" || bad "U1: the process restarted ($pid_before -> $pid_after)"
drive title Cellar U1after; fold "U1: the running app now shows Cellar (v9)" $?

# U2: "check now" in a debug build, from adb: two broadcasts a few seconds apart,
# each a lookup (up to date on v9), in the same process.
if grep -rqs DEBUG_CHECK_FOR_UPDATE "$APP/host-android/src/main/kotlin"; then
  adb -s "$SERIAL" logcat -c || true
  for _ in 1 2; do
    adb -s "$SERIAL" shell am broadcast -a dev.keliver.debug.CHECK_FOR_UPDATE -p "$PROD_ID" >> "$EV/U2-broadcast.txt" 2>&1; sleep 6
  done
  adb -s "$SERIAL" logcat -d > "$EV/logcat-U2.txt"
  L="$EV/logcat-U2.txt"
  [ "$(grep -c "$HOST_TAG: update check requested from adb (a debug build)" "$L")" = 2 ] \
    && [ "$(grep -c "$HOST_TAG: update check: up to date (sequence 9)" "$L")" = 2 ] && ! grep -q "update check skipped" "$L" \
    && [ "$(adb -s "$SERIAL" shell pidof "$PROD_ID" | tr -d '\r')" = "$pid_after" ] \
    && ok "U2: two adb broadcasts, two lookups at once (up to date on v9), same process" \
    || bad "U2: the debug check-now hook: $(grep -E "$HOST_TAG: update check" "$L" | tail -4 | tr '\n' ' ')"
else
  echo "  SKIP  U2: this host predates the debug check-now hook (not counted)"
fi

sleep 3  # the reports are sent in the background
python3 "$HERE/w5/reports.py" "$EV/w5-server.log" android > "$EV/w6-reports.txt" 2>&1 \
  && ok "V1: the host reported loaded (sequence 8, network) and then update-applied (sequence 9) to its report URL" \
  || bad "V1: $(tail -1 "$EV/w6-reports.txt")"

# L1-L3: a DEBUG host takes a bundle server set on the device
# (files/keliver/bundle-server, written with run-as), so a new tunnel URL needs
# no rebuild. The installed debug build names https://$HOST_ADDR:8443; a second
# server on 8444 serves the same site. L3 removes the file, so the cached last
# good bundle is 8443's again for the rows after this one.
# Only a host scaffolded from these templates has the override: a hand-run
# (published-only) route builds from the released tools zip, which may not.
if grep -rqs DEBUG_BUNDLE_SERVER_FILE "$APP/host-android/src/main/kotlin"; then
  echo "--- a debug host takes a bundle server set on the device"
  python3 "$HERE/w3/static_https.py" "$W3/site" 8444 "$W3_TLS/server.pem" "$W3_TLS/server.key" "$EV/l1-server.log" &
  LPID=$!
  for _ in $(seq 1 30); do curl -sf --cacert "$W3_TLS/ca.pem" -m 2 -o /dev/null https://localhost:8444/bundles/index.json && break; sleep 1; done
  set_server() { # <url, or empty to remove the file>
    if [ -n "$1" ]; then
      adb -s "$SERIAL" shell run-as "$PROD_ID" sh -c "'mkdir -p files/keliver && echo $1 > files/keliver/bundle-server'"
    else
      adb -s "$SERIAL" shell run-as "$PROD_ID" rm -f files/keliver/bundle-server
    fi
  }
  set_server "https://$HOST_ADDR:8444"
  launch prod "$EV/logcat-L1.txt"
  L="$EV/logcat-L1.txt"
  grep -q "$HOST_TAG: bundle server from files/keliver/bundle-server (a debug build): https://$HOST_ADDR:8444" "$L" \
    && grep -q "$HOST_TAG: loading https://$HOST_ADDR:8444/bundles/v9/manifest.zipline.json (index sequence 9," "$L" \
    && grep -q "codeLoadSuccess modules=[0-9]* sequence=9" "$L" && grep -q "GET /bundles/index.json" "$EV/l1-server.log" \
    && ok "L1: with files/keliver/bundle-server set to :8444, the debug host verified and loaded v9 from there" \
    || bad "L1: the server set on the device was not used: $(grep -E "$HOST_TAG" "$L" | head -3 | tr '\n' ' ')"
  set_server "https://$HOST_ADDR:8444/?x=1"
  launch prod "$EV/logcat-L2.txt"
  grep -q "$HOST_TAG: refusing to load: the bundle server 'https://$HOST_ADDR:8444/?x=1' is not an http(s) URL without a user, query or fragment" "$EV/logcat-L2.txt" \
    && ! grep -q "codeLoadSuccess" "$EV/logcat-L2.txt" \
    && ok "L2: a server with a query is refused, and nothing loads" \
    || bad "L2: a malformed server was not refused: $(grep -E "$HOST_TAG" "$EV/logcat-L2.txt" | head -3 | tr '\n' ' ')"
  kill "$LPID" 2>/dev/null; wait "$LPID" 2>/dev/null
  set_server ""
  launch prod "$EV/logcat-L3.txt"
  grep -q "$HOST_TAG: loading https://$HOST_ADDR:8443/bundles/v9/manifest.zipline.json" "$EV/logcat-L3.txt" \
    && ! grep -q "bundle server from files/" "$EV/logcat-L3.txt" \
    && ok "L3: with the file removed, the host is back on its built-in https://$HOST_ADDR:8443" \
    || bad "L3: the host did not return to its built-in server: $(grep -E "$HOST_TAG" "$EV/logcat-L3.txt" | head -3 | tr '\n' ' ')"
  adb -s "$SERIAL" shell am force-stop "$PROD_ID"
else
  echo "  SKIP  L1-L3: this host predates the bundle server set on the device (not counted)"
fi

# LV2-LV3: live updates against a server that announces bundles. Its own copy of
# the site and its own port (set on the device, as L1 does), so v10 here never
# reaches the main site, whose v10 is W8's. Data is cleared afterwards: this
# copy's v10 raises the rollback floor to 10.
if [ "$W5_LIVE" = 1 ] && grep -rqs DEBUG_BUNDLE_SERVER_FILE "$APP/host-android/src/main/kotlin"; then
  echo "--- live updates: a publish reaches the open app"
  rm -rf "$W3/site-live" && cp -R "$W3/site" "$W3/site-live"
  python3 "$HERE/w3/static_https.py" "$W3/site-live" 8445 "$W3_TLS/server.pem" "$W3_TLS/server.key" "$EV/lv-server.log" events &
  LVPID=$!
  for _ in $(seq 1 30); do curl -sf --cacert "$W3_TLS/ca.pem" -m 2 -o /dev/null https://localhost:8445/bundles/index.json && break; sleep 1; done
  adb -s "$SERIAL" shell run-as "$PROD_ID" sh -c "'mkdir -p files/keliver && echo https://$HOST_ADDR:8445 > files/keliver/bundle-server'"
  launch prod "$EV/logcat-LV2-start.txt"
  sleep 3
  adb -s "$SERIAL" logcat -d > "$EV/logcat-LV2-start.txt"
  grep -q "$HOST_TAG: live updates: listening to https://$HOST_ADDR:8445/bundles/events" "$EV/logcat-LV2-start.txt" \
    && grep -q "codeLoadSuccess modules=[0-9]* sequence=9" "$EV/logcat-LV2-start.txt" \
    && ok "LV2: the debug host runs v9 and listens to /bundles/events while its screen shows" \
    || bad "LV2: not listening: $(grep -E "$HOST_TAG" "$EV/logcat-LV2-start.txt" | tail -4 | tr '\n' ' ')"
  drive title Cellar LV2before; fold "LV2: v9 shows Cellar" $?
  pid_live="$(adb -s "$SERIAL" shell pidof "$PROD_ID" | tr -d '\r')"
  adb -s "$SERIAL" logcat -c || true
  # The edit and publish a developer makes while the app is open; no key press on the phone.
  w3_title Cellar Loft \
    && ( cd "$APP" && KELIVER_SIGNING_KEY_FILE="$STORE/keys/ed25519.priv" KELIVER_TOOLS_BIN="$W3/no-tools-bin" \
      "$W3_PUBLISH" . --out "$W3/site-live" --public-key-file "$STORE/keys/ed25519.pub" ) > "$EV/w3-publish-v10-live.log" 2>&1 \
    && grep -q "published v10 (sequence 10, channel stable" "$EV/w3-publish-v10-live.log" \
    && ok "LV2: keliver-publish wrote v10 (\"Loft\") to the live server's site while the app showed v9" \
    || { bad "LV2: publish v10 to the live site"; tail -20 "$EV/w3-publish-v10-live.log"; }
  sleep 20
  adb -s "$SERIAL" logcat -d > "$EV/logcat-LV2.txt"
  L="$EV/logcat-LV2.txt"
  grep -q "$HOST_TAG: live updates: the bundle server says {\"version\":10}" "$L" \
    && grep -q "$HOST_TAG: update check: sequence 10 is available (running 9); applying https://$HOST_ADDR:8445/bundles/v10/manifest.zipline.json" "$L" \
    && grep -q "codeLoadSuccess modules=[0-9]* sequence=10" "$L" && grep -q "$HOST_TAG: update applied: sequence 10" "$L" \
    && ok "LV2: the server announced v10 and the open app looked it up, verified it and applied it" \
    || bad "LV2: v10 was not applied live: $(grep -E "$HOST_TAG|codeLoad" "$L" | tail -5 | tr '\n' ' ')"
  [ "$(adb -s "$SERIAL" shell pidof "$PROD_ID" | tr -d '\r')" = "$pid_live" ] && ! grep -q "$HOST_TAG: verifying manifests" "$L" \
    && ! grep -q "update check skipped" "$L" \
    && ok "LV2: the same process (pid $pid_live), no restart and no resume" || bad "LV2: the process changed or restarted"
  drive title Loft LV2after; fold "LV2: the open app now shows Loft (v10)" $?
  adb -s "$SERIAL" logcat -c || true
  adb -s "$SERIAL" shell input keyevent KEYCODE_HOME; sleep 4
  adb -s "$SERIAL" logcat -d > "$EV/logcat-LV3.txt"
  grep -q "$HOST_TAG: live updates: stopped listening (no screen on show)" "$EV/logcat-LV3.txt" \
    && ok "LV3: in the background the host stops listening" \
    || bad "LV3: still listening in the background: $(grep -E "$HOST_TAG: live" "$EV/logcat-LV3.txt" | tail -3 | tr '\n' ' ')"
  kill "$LVPID" 2>/dev/null; wait "$LVPID" 2>/dev/null
  w3_title Loft Cellar || bad "LV: could not put the title back to Cellar for W8"
  adb -s "$SERIAL" shell am force-stop "$PROD_ID"
  adb -s "$SERIAL" shell pm clear "$PROD_ID" > /dev/null
else
  echo "  SKIP  LV2-LV3: this host predates live updates or the bundle server set on the device (not counted)"
fi
kill "$SERVE_PID" 2>/dev/null; wait "$SERVE_PID" 2>/dev/null; SERVE_PID=""
