# Sourced by ci/device.sh after W5: WS6, crash rollback on Android. The production
# host built for W5 (W5_APK: a debug build, bundle server https://$HOST_ADDR:8443,
# reports to its /report) runs a fresh static site of its own ($W3/site-crash), so
# W3's site and its sequences, which W8 goes on from, are left as they were. Uses
# device.sh's SERIAL, EV, HERE, PROD_ID, HOST_TAG, ok/bad/skip/drive/fold/launch, and
# android-static.sh's W3, W3_TLS, W3_PUBLISH, SCREEN and STORE.
#   C1  a good bundle (sequence 1) loads and proves healthy: 10 s on show, no failure
#   C2  a bundle whose screen throws as it shows (sequence 2): loaded, threw, one
#       failure counted
#   C3  at the next start it throws again: set aside after 2 failures, and the same
#       process goes back to sequence 1 from the manifest this install kept; a debug
#       build says so loudly; the screen is sequence 1's again
#   C4  the report URL received crash-rollback for sequence 2, reason previous-good,
#       in the 8 fixed fields only
#   C5  the next start skips sequence 2 and starts from sequence 1 again, counting nothing
#   C6  a newer good bundle (sequence 3) is not held back by the set-aside one
#   C7  sequence 3, force-stopped while it is on trial (shown, inside its crash window,
#       the "not yet healthy" marker set), is not counted at the next start: the system's
#       exit record for that process says user requested (ApplicationExitInfo, matched by
#       pid); it then proves healthy
#   C8  the app's own view: a fresh install that only ever saw a crashing bundle
#       (sequence 4) sets it aside after 2 failures with nothing to go back to; the next
#       start shows the app's view again without loading it, and the report URL received
#       one crash-rollback for it (reason app-view), not one per start
echo "--- 6b. WS6: crash rollback"
if ! grep -rqs 'CrashGuard' "$APP/host-android/src/main/kotlin"; then
  skip "C1-C6: this host predates crash rollback (WS6)"
else
  CR="$W3/site-crash"
  rm -rf "$CR"
  cr_publish(){  # $1 label, then flags: keliver-publish into the crash site, as w3_publish does into W3's
    local label="$1"; shift
    ( cd "$APP" && KELIVER_SIGNING_KEY_FILE="$STORE/keys/ed25519.priv" KELIVER_TOOLS_BIN="$W3/no-tools-bin" \
        "$W3_PUBLISH" . --out "$CR" --public-key-file "$STORE/keys/ed25519.pub" "$@" ) > "$EV/ws6-publish-$label.log" 2>&1
  }
  cr_wait(){  # $1 pattern, $2 logcat file, $3 seconds: wait for a line in the host's log
    local i
    for i in $(seq 1 "$3"); do
      adb -s "$SERIAL" logcat -d -v threadtime > "$2"
      grep -q "$1" "$2" && return 0
      sleep 1
    done
    return 1
  }
  cr_start(){  # $1 logcat file: a launcher start, as device.sh's launch does, without its 15 s wait
    local cmp
    adb -s "$SERIAL" logcat -c || true
    adb -s "$SERIAL" shell am force-stop "$PROD_ID"
    cmp="$(adb -s "$SERIAL" shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
      -c android.intent.category.LAUNCHER "$PROD_ID" | tr -d '\r' | tail -1)"
    adb -s "$SERIAL" shell am start -n "$cmp" > /dev/null
    : > "$1"
  }
  # The screen's title now (W5 leaves it as it found it): the edits below put it back.
  CR_TITLE="$(sed -n 's/^      text = "\([A-Za-z]*\)",$/\1/p' "$SCREEN" | head -1)"
  [ -n "$CR_TITLE" ] || bad "C1: could not read the screen's title from $SCREEN"

  cr_publish v1 --init && grep -q "published v1 (sequence 1" "$EV/ws6-publish-v1.log" \
    && ok "C1: keliver-publish wrote sequence 1 (\"$CR_TITLE\") to a fresh site" || { bad "C1: publish sequence 1"; tail -20 "$EV/ws6-publish-v1.log"; }
  python3 "$HERE/w3/static_https.py" "$CR" 8443 "$W3_TLS/server.pem" "$W3_TLS/server.key" "$EV/ws6-server.log" &
  CR_PID=$!
  for _ in $(seq 1 30); do curl -sf --cacert "$W3_TLS/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json && break; sleep 1; done
  adb -s "$SERIAL" install -r "$W5_APK" > "$EV/install-ws6-host.log" 2>&1 || bad "C1: the host did not install"
  adb -s "$SERIAL" shell am force-stop "$PROD_ID"
  adb -s "$SERIAL" shell pm clear "$PROD_ID" > /dev/null

  # C1: sequence 1 loads and, shown for the crash window with no failure, proves healthy.
  launch prod "$EV/logcat-ws6-c1.txt"
  cr_wait "$HOST_TAG: crash rollback: sequence 1 proved healthy on this install" "$EV/logcat-ws6-c1.txt" 30 \
    && grep -q "$HOST_TAG: codeLoadSuccess modules=[0-9]* sequence=1 source=network" "$EV/logcat-ws6-c1.txt" \
    && ok "C1: sequence 1 loaded from the network and proved healthy (on show for the crash window, no failure)" \
    || bad "C1: sequence 1 did not prove healthy: $(grep -E "$HOST_TAG" "$EV/logcat-ws6-c1.txt" | tail -4 | tr '\n' ' ')"
  drive title "$CR_TITLE" C1ws6; fold "C1: sequence 1 shows $CR_TITLE" $?

  # C2: sequence 2's screen throws as it shows (an index out of bounds while it composes).
  sed -i.bak "s/^      text = \"$CR_TITLE\",$/      text = listOf(\"$CR_TITLE\")[1],/" "$SCREEN" && rm -f "$SCREEN.bak"
  grep -q "text = listOf(\"$CR_TITLE\")\[1\]," "$SCREEN" || bad "C2: could not make the screen throw"
  cr_publish v2 && grep -q "published v2 (sequence 2" "$EV/ws6-publish-v2.log" \
    && ok "C2: keliver-publish wrote sequence 2, whose screen throws as it shows" || { bad "C2: publish sequence 2"; tail -20 "$EV/ws6-publish-v2.log"; }
  launch prod "$EV/logcat-ws6-c2.txt"
  cr_wait "$HOST_TAG: crash rollback: sequence 2 failed on this install (its code threw): 1 of 2" "$EV/logcat-ws6-c2.txt" 20 \
    && grep -q "$HOST_TAG: codeLoadSuccess modules=[0-9]* sequence=2" "$EV/logcat-ws6-c2.txt" \
    && grep -q "$HOST_TAG: uncaughtException" "$EV/logcat-ws6-c2.txt" \
    && ok "C2: sequence 2 loaded, threw as its screen showed, and one failure was counted (1 of 2)" \
    || bad "C2: no failure counted for sequence 2: $(grep -E "$HOST_TAG" "$EV/logcat-ws6-c2.txt" | tail -5 | tr '\n' ' ')"

  # C3: the next start throws again: set aside, and the same process goes back to sequence 1,
  # from the manifest this install kept. (The counted exception already cleared the marker, so
  # the force-stop between these starts proves nothing about exit records: C7 tests that.)
  launch prod "$EV/logcat-ws6-c3.txt"
  C="$EV/logcat-ws6-c3.txt"
  cr_wait "$HOST_TAG: codeLoadSuccess modules=[0-9]* sequence=1" "$C" 30
  grep -q "$HOST_TAG: crash rollback: sequence 2 SET ASIDE after 2 failures on this install (the last: its code threw)" "$C" \
    && grep -q "$HOST_TAG: !!!!!!!! CRASH ROLLBACK !!!!!!!!" "$C" \
    && grep -q "$HOST_TAG: crash rollback: sequence 2 set aside after 2 of 2 failures on this install (the last: its code threw); going back to sequence 1" "$C" \
    && ok "C3: sequence 2 threw again: set aside after 2 failures, said loudly by the debug build" \
    || bad "C3: sequence 2 was not set aside: $(grep -E "$HOST_TAG" "$C" | tail -6 | tr '\n' ' ')"
  [ "$(grep -c "$HOST_TAG: verifying manifests" "$C")" = 1 ] && grep -q "$HOST_TAG: codeLoadSuccess modules=[0-9]* sequence=1" "$C" \
    && ok "C3: the same process went back to sequence 1, below the floor (2), from the kept manifest" \
    || bad "C3: sequence 1 did not load again: $(grep -E "$HOST_TAG|codeLoad" "$C" | tail -5 | tr '\n' ' ')"
  drive title "$CR_TITLE" C3ws6; fold "C3: the screen is sequence 1's again ($CR_TITLE)" $?

  # C4: what the report URL received: crash-rollback, sequence 2, previous-good; 8 fixed fields.
  python3 - "$EV/ws6-server.log" <<'PY' && ok "C4: the report URL received crash-rollback (sequence 2, previous-good) in the 8 fixed fields" || bad "C4: no crash-rollback report"
import json, sys
reports = [json.loads(l[len("REPORT "):]) for l in open(sys.argv[1], encoding="utf-8", errors="replace") if l.startswith("REPORT ")]
keys = {"installId", "channel", "hostVersion", "sequence", "source", "outcome", "reason", "platform"}
for r in reports: print("  report:", {k: r.get(k) for k in ("outcome", "sequence", "source", "reason")})
cr = [r for r in reports if r.get("outcome") == "crash-rollback"]
sys.exit(0 if all(set(r) == keys for r in reports) and len(cr) == 1 and cr[0]["sequence"] == 2
         and cr[0]["reason"] == "previous-good" and cr[0]["platform"] == "android" else 1)
PY

  # C5: the next start skips sequence 2 (the index still names it) and starts from sequence 1.
  launch prod "$EV/logcat-ws6-c5.txt"
  cr_wait "$HOST_TAG: codeLoadSuccess modules=[0-9]* sequence=1" "$EV/logcat-ws6-c5.txt" 20
  grep -q "$HOST_TAG: crash rollback: sequence 2 is set aside; starting from sequence 1" "$EV/logcat-ws6-c5.txt" \
    && ! grep -q "sequence=2" "$EV/logcat-ws6-c5.txt" && ! grep -q "$HOST_TAG: crash rollback: sequence [0-9]* failed" "$EV/logcat-ws6-c5.txt" \
    && ok "C5: the next start skipped sequence 2 and started from sequence 1, counting nothing" \
    || bad "C5: $(grep -E "$HOST_TAG" "$EV/logcat-ws6-c5.txt" | tail -5 | tr '\n' ' ')"
  drive title "$CR_TITLE" C5ws6; fold "C5: still $CR_TITLE" $?

  # C6: a fix, published as sequence 3, is not held back.
  sed -i.bak "s/^      text = listOf(\"$CR_TITLE\")\[1\],$/      text = \"Pantry\",/" "$SCREEN" && rm -f "$SCREEN.bak"
  cr_publish v3 && grep -q "published v3 (sequence 3" "$EV/ws6-publish-v3.log" \
    && ok "C6: keliver-publish wrote sequence 3 (\"Pantry\"), the fix" || { bad "C6: publish sequence 3"; tail -20 "$EV/ws6-publish-v3.log"; }
  # C6, and C7's first half: sequence 3 loads and a screen shows it (its crash window opens and
  # the marker is set); it is force-stopped at once, well inside the 10 s window.
  C="$EV/logcat-ws6-c6.txt"
  cr_start "$C"
  if cr_wait "$HOST_TAG: crash rollback: sequence 3 is on trial" "$C" 30; then
    adb -s "$SERIAL" shell am force-stop "$PROD_ID"
    adb -s "$SERIAL" logcat -d -v threadtime > "$C"
  fi
  grep -q "$HOST_TAG: codeLoadSuccess modules=[0-9]* sequence=3" "$C" \
    && ok "C6: sequence 3, newer than the set-aside one, loaded" \
    || bad "C6: sequence 3 did not load: $(grep -E "$HOST_TAG" "$C" | tail -4 | tr '\n' ' ')"
  grep -q "$HOST_TAG: crash rollback: sequence 3 is on trial" "$C" && ! grep -q "$HOST_TAG: crash rollback: sequence 3 proved healthy" "$C" \
    && ok "C7: sequence 3 was force-stopped while on trial (shown, inside its crash window)" \
    || bad "C7: sequence 3 was not force-stopped inside its crash window: $(grep "$HOST_TAG: crash rollback" "$C" | tail -3 | tr '\n' ' ')"

  # C7: the next start finds the marker set, asks the system how that process ended (user
  # requested: a force-stop), counts nothing, and sequence 3 proves healthy.
  launch prod "$EV/logcat-ws6-c7.txt"
  C="$EV/logcat-ws6-c7.txt"
  cr_wait "$HOST_TAG: crash rollback: sequence 3 proved healthy on this install" "$C" 30 \
    && ! grep -q "$HOST_TAG: crash rollback: sequence 3 failed on this install" "$C" \
    && ok "C7: the force-stop inside sequence 3's window was not counted (ApplicationExitInfo), and sequence 3 proved healthy" \
    || bad "C7: $(grep "$HOST_TAG: crash rollback" "$C" | tail -3 | tr '\n' ' ')"
  drive title Pantry C7ws6; fold "C7: sequence 3 shows Pantry" $?

  # C8: the app's own view. A fresh install (cleared data) whose only bundle crashes.
  sed -i.bak "s/^      text = \"Pantry\",$/      text = listOf(\"Pantry\")[1],/" "$SCREEN" && rm -f "$SCREEN.bak"
  grep -q 'text = listOf("Pantry")\[1\],' "$SCREEN" || bad "C8: could not make the screen throw"
  cr_publish v4 && grep -q "published v4 (sequence 4" "$EV/ws6-publish-v4.log" \
    && ok "C8: keliver-publish wrote sequence 4, whose screen throws as it shows" || { bad "C8: publish sequence 4"; tail -20 "$EV/ws6-publish-v4.log"; }
  adb -s "$SERIAL" shell am force-stop "$PROD_ID"
  adb -s "$SERIAL" shell pm clear "$PROD_ID" > /dev/null
  launch prod "$EV/logcat-ws6-c8a.txt"
  cr_wait "$HOST_TAG: crash rollback: sequence 4 failed on this install (its code threw): 1 of 2" "$EV/logcat-ws6-c8a.txt" 20 \
    && ok "C8: a fresh install loaded sequence 4, which threw: 1 of 2" \
    || bad "C8: no failure counted for sequence 4: $(grep -E "$HOST_TAG" "$EV/logcat-ws6-c8a.txt" | tail -5 | tr '\n' ' ')"
  launch prod "$EV/logcat-ws6-c8b.txt"
  C="$EV/logcat-ws6-c8b.txt"
  cr_wait "$HOST_TAG: crash rollback: sequence 4 set aside after 2 of 2 failures on this install (the last: its code threw); nothing that ran on this install before it is left: the app's own view" "$C" 20 \
    && ok "C8: sequence 4 threw again: set aside, with nothing to go back to: the app's own view" \
    || bad "C8: sequence 4 was not set aside to the app's view: $(grep -E "$HOST_TAG" "$C" | tail -5 | tr '\n' ' ')"
  launch prod "$EV/logcat-ws6-c8c.txt"
  C="$EV/logcat-ws6-c8c.txt"
  grep -q "$HOST_TAG: crash rollback: sequence 4 is set aside on this install; nothing that ran on this install before it is left" "$C" \
    && ! grep -q "sequence=4" "$C" \
    && ok "C8: the next start showed the app's view again, without loading sequence 4" \
    || bad "C8: $(grep -E "$HOST_TAG" "$C" | tail -5 | tr '\n' ' ')"
  python3 - "$EV/ws6-server.log" <<'PY' && ok "C8: the report URL received one crash-rollback for sequence 4 (app-view), not one per start" || bad "C8: the app-view reports are wrong"
import json, sys
reports = [json.loads(l[len("REPORT "):]) for l in open(sys.argv[1], encoding="utf-8", errors="replace") if l.startswith("REPORT ")]
cr = [r for r in reports if r.get("outcome") == "crash-rollback" and r.get("sequence") == 4]
print("  crash-rollback reports for sequence 4:", [r.get("reason") for r in cr])
sys.exit(0 if len(cr) == 1 and cr[0]["reason"] == "app-view" else 1)
PY

  # Leave the app and the device as W5 left them.
  sed -i.bak "s/^      text = listOf(\"Pantry\")\[1\],$/      text = \"Pantry\",/" "$SCREEN" && rm -f "$SCREEN.bak"
  w3_title Pantry "$CR_TITLE" || bad "C8: could not put the title back to $CR_TITLE"
  kill "$CR_PID" 2>/dev/null; wait "$CR_PID" 2>/dev/null
  adb -s "$SERIAL" shell am force-stop "$PROD_ID"
  adb -s "$SERIAL" shell pm clear "$PROD_ID" > /dev/null
fi
