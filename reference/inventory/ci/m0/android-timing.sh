# Sourced by ci/device.sh after W5: M0's timing marks on Android, against W3's
# static HTTPS server (its site is at v9). Uses device.sh's SERIAL, EV, HERE,
# APP, PROD_ID, launch, drive/fold, ok/bad, and W3/W3_TLS, W5_APK and
# M0_BENCH_APK (prepare.sh). Only a host from templates with the marks
# (src/timing/); an older one SKIPs.
#   T1  the debug host logs process, open, code, first-frame and content (the
#       guest's KeliverTiming.mark), each once, on logcat (tag KeliverTiming)
#       and in files/keliver/timing.log; code and content come after process,
#       first-frame and content not before code
#   T2  the benchmark build (release settings: minified, not debuggable,
#       profileable) loads v9 and logs the same marks
if grep -rqs "interface KeliverTrace" "$APP/host-android/src/timing"; then
  echo "--- M0: timing marks"
  python3 "$HERE/w3/static_https.py" "$W3/site" 8443 "$W3_TLS/server.pem" "$W3_TLS/server.key" "$EV/m0-server.log" &
  SERVE_PID=$!
  for _ in $(seq 1 30); do curl -sf --cacert "$W3_TLS/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json && break; sleep 1; done
  # The nanoseconds of mark $2 in logcat file $1 (empty when absent).
  m0_at(){ sed -n "s/.*KeliverTiming: $2 \([0-9][0-9]*\)\r*$/\1/p" "$1" | head -1; }
  m0_count(){ grep -c "KeliverTiming: $2 [0-9]" "$1"; }
  m0_marks(){  # $1 label, $2 logcat file: all five once, in a plausible order; prints ms after process
    local f="$2" p o c ff ct n
    for n in process open code first-frame content; do
      [ "$(m0_count "$f" "$n")" = 1 ] || { bad "$1: '$n' is logged $(m0_count "$f" "$n") times, not once"; return 1; }
    done
    p="$(m0_at "$f" process)"; o="$(m0_at "$f" open)"; c="$(m0_at "$f" code)"; ff="$(m0_at "$f" first-frame)"; ct="$(m0_at "$f" content)"
    if [ "$o" -ge "$p" ] && [ "$c" -ge "$p" ] && [ "$ff" -ge "$c" ] && [ "$ct" -ge "$c" ]; then
      ok "$1: process, open, code, first-frame, content: open +$(( (o - p) / 1000000 )) ms, code +$(( (c - p) / 1000000 )) ms, first-frame +$(( (ff - p) / 1000000 )) ms, content +$(( (ct - p) / 1000000 )) ms"
    else
      bad "$1: the marks are out of order: process $p open $o code $c first-frame $ff content $ct"; return 1
    fi
  }

  adb -s "$SERIAL" install -r "$W5_APK" > "$EV/install-T1.log" 2>&1
  launch prod "$EV/logcat-T1.txt"
  m0_marks T1 "$EV/logcat-T1.txt"
  grep -q "$HOST_TAG: codeLoadSuccess modules=[0-9]* sequence=9" "$EV/logcat-T1.txt" && ok "T1: the debug host ran v9" || bad "T1: v9 did not load"
  adb -s "$SERIAL" shell run-as "$PROD_ID" cat files/keliver/timing.log > "$EV/T1-timing.log" 2>&1
  ct="$(m0_at "$EV/logcat-T1.txt" content)"
  [ -n "$ct" ] && grep -qx "content $ct" "$EV/T1-timing.log" \
    && ok "T1: files/keliver/timing.log has the same content mark ($ct)" || bad "T1: the file sink: $(tail -3 "$EV/T1-timing.log" | tr '\n' ' ')"

  if [ -n "${M0_BENCH_APK:-}" ] && [ -f "$M0_BENCH_APK" ]; then
    adb -s "$SERIAL" install -r "$M0_BENCH_APK" > "$EV/install-T2.log" 2>&1 \
      && ok "T2: installed the benchmark build" || { bad "T2: the benchmark build did not install"; cat "$EV/install-T2.log"; }
    adb -s "$SERIAL" shell dumpsys package "$PROD_ID" > "$EV/T2-dumpsys.txt" 2>&1
    ! grep -q "flags=\[.*DEBUGGABLE" "$EV/T2-dumpsys.txt" && ok "T2: it is not debuggable" || bad "T2: the benchmark build is debuggable"
    launch prod "$EV/logcat-T2.txt"
    grep -q "$HOST_TAG: codeLoadSuccess modules=[0-9]* sequence=9" "$EV/logcat-T2.txt" \
      && ok "T2: the benchmark build (R8) verified and ran v9" || bad "T2: v9 did not load: $(grep -E 'KeliverHost|codeLoad' "$EV/logcat-T2.txt" | head -3 | tr '\n' ' ')"
    m0_marks T2 "$EV/logcat-T2.txt"
    adb -s "$SERIAL" shell am force-stop "$PROD_ID"
  else
    echo "  SKIP  T2: no benchmark build (a physical device, whose test CA is in the debug build only)"
  fi
  kill "$SERVE_PID" 2>/dev/null; wait "$SERVE_PID" 2>/dev/null; SERVE_PID=""
else
  echo "  SKIP  T1-T2: this host predates the timing marks (not counted)"
fi
