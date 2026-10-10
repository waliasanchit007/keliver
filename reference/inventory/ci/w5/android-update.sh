# Sourced by ci/device.sh after W2: W5's U1 on Android. The production host,
# built with keliver.updates=on-resume (W5_APK, prepare.sh), runs v8; v9 is
# published while it runs; the app goes to the background and comes back, and
# the SAME process now shows v9. Uses device.sh's SERIAL, EV, HERE, PROD_ID,
# HOST_TAG, ok/bad/drive/fold, and android-static.sh's W3, W3_TLS, w3_title,
# w3_publish, w3_signed_sequence.
#   U1  an update applied on resume: looked up, loaded in place, floor raised,
#       one process, the new screen shown
echo "--- 6. W5: an update applied when the app comes back to the foreground"
python3 "$HERE/w3/static_https.py" "$W3/site" 8443 "$W3_TLS/server.pem" "$W3_TLS/server.key" "$EV/w5-server.log" &
SERVE_PID=$!
for _ in $(seq 1 30); do curl -sf --cacert "$W3_TLS/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json && break; sleep 1; done

w3_title Attic Garage && w3_publish v8-update && grep -q "published v8 (sequence 8, channel stable" "$EV/w3-publish-v8-update.log" && w3_signed_sequence 8 \
  && ok "U1: keliver-publish wrote v8 (\"Garage\")" || { bad "U1: publish v8"; tail -20 "$EV/w3-publish-v8-update.log"; }
adb -s "$SERIAL" install -r "$W5_APK" > "$EV/install-update-host.log" 2>&1 \
  && ok "U1: installed the host built with keliver.updates=on-resume" || bad "U1: the on-resume host did not install"
adb -s "$SERIAL" shell pm clear "$PROD_ID" > /dev/null
launch prod "$EV/logcat-update-v8.txt"
grep -q "$HOST_TAG: loading https://10.0.2.2:8443/bundles/v8/manifest.zipline.json (index sequence 8," "$EV/logcat-update-v8.txt" \
  && grep -q "codeLoadSuccess modules=[0-9]* sequence=8" "$EV/logcat-update-v8.txt" \
  && ok "U1: the host started on v8" || bad "U1: v8 did not load: $(grep -E "$HOST_TAG" "$EV/logcat-update-v8.txt" | head -3 | tr '\n' ' ')"
drive title Garage U1before; fold "U1: v8 shows Garage" $?
pid_before="$(adb -s "$SERIAL" shell pidof "$PROD_ID" | tr -d '\r')"

# While it runs: v9. (Publishing takes longer than the host's 30 s between checks.)
w3_title Garage Cellar && w3_publish v9-update && grep -q "published v9 (sequence 9, channel stable" "$EV/w3-publish-v9-update.log" \
  && ok "U1: keliver-publish wrote v9 (\"Cellar\") while the app ran" || { bad "U1: publish v9"; tail -20 "$EV/w3-publish-v9-update.log"; }

# To the background and back: the launcher intent brings the running task forward.
adb -s "$SERIAL" logcat -c || true
adb -s "$SERIAL" shell input keyevent KEYCODE_HOME; sleep 5
adb -s "$SERIAL" shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
  -n "$(adb -s "$SERIAL" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PROD_ID" | tr -d '\r' | tail -1)" > /dev/null
sleep 20
adb -s "$SERIAL" logcat -d > "$EV/logcat-update-resume.txt"
L="$EV/logcat-update-resume.txt"
grep -q "$HOST_TAG: update check: sequence 9 is available (running 8); applying https://10.0.2.2:8443/bundles/v9/manifest.zipline.json" "$L" \
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

kill "$SERVE_PID" 2>/dev/null; wait "$SERVE_PID" 2>/dev/null; SERVE_PID=""
