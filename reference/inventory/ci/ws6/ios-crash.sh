# Sourced by ci/ios.sh after W5: WS6, crash rollback on iOS, as ci/ws6/android-crash.sh
# does on Android. W5 leaves its Debug host installed afresh (BUNDLE_SERVER and REPORT_URL
# on https://localhost:8443); this runs it against a fresh static site of its own
# ($W3/site-crash), so W3's site and its sequences, which W8 goes on from, are left as they
# were. Uses ios.sh's APP, UDID, BID, APPB, EV, HERE, STORE, ok/bad/launch/reads, and
# ios-static.sh's W3, PUBLISH, SCREEN and w3_title.
#   C1  a good bundle (sequence 1) loads and proves healthy: 10 s on show, no failure
#   C2  a bundle whose screen throws as it shows (sequence 2): loaded, threw, one failure
#   C3  at the next start it throws again: set aside after 2 failures, and the same process
#       goes back to sequence 1 from the manifest this install kept; the screen is its again
#   C4  REPORT_URL received crash-rollback for sequence 2, reason previous-good, 8 fields only
#   C5  the next start skips sequence 2 and starts from sequence 1 again, counting nothing
#   C6  a newer good bundle (sequence 3) is not held back by the set-aside one
#   C8  the app's own view: a fresh install that only ever saw a crashing bundle (sequence
#       4) sets it aside after 2 failures with nothing to go back to; the next start shows
#       the app's view again without loading it; REPORT_URL received one crash-rollback for
#       it (app-view), not one per start
# (Android's C7, a force-stop inside the window that is not counted, has no iOS row: iOS
# keeps no record of how a process ended, so there it counts; see docs/DEVICE_HOST.md.)
# Every launch here and in the scenarios after it (ios.sh's launch, M0's m0_launch) keeps the
# process 25-30 s, past the 10 s crash window, before terminating it. Afterwards CRASH_THRESHOLD
# is set to 0 in HostConfig.kt, so the hosts M0 and W8 build have crash rollback off and their
# terminates can never set a bundle aside; the Debug host M0's T1 runs was built before, and
# relies on that wait.
echo "--- WS6: crash rollback"
if ! grep -rqs 'CrashGuard' "$APP/host-ios/src/iosMain/kotlin"; then
  echo "  SKIP  C1-C6: this host predates crash rollback (WS6) (not counted)"
elif [ -z "${PUBLISH:-}" ]; then
  bad "C1: no keliver-publish to publish with"
else
  CR="$W3/site-crash"
  rm -rf "$CR"
  cr_publish(){  # $1 label, then flags: keliver-publish into the crash site, as w3_publish does into W3's
    local label="$1"; shift
    ( cd "$APP" && "$PUBLISH" . --out "$CR" --public-key-file "$STORE/keys/ed25519.pub" "$@" ) > "$EV/ws6-publish-$label.log" 2>&1
  }
  # The screen's title now (W5 leaves it as it found it): the edits below put it back.
  CR_TITLE="$(sed -n 's/^      text = "\([A-Za-z]*\)",$/\1/p' "$SCREEN" | head -1)"
  [ -n "$CR_TITLE" ] || bad "C1: could not read the screen's title from $SCREEN"

  cr_publish v1 --init && grep -q "published v1 (sequence 1" "$EV/ws6-publish-v1.log" \
    && ok "C1: keliver-publish wrote sequence 1 (\"$CR_TITLE\") to a fresh site" || { bad "C1: publish sequence 1"; tail -20 "$EV/ws6-publish-v1.log"; }
  python3 "$HERE/w3/static_https.py" "$CR" 8443 "$W3/tls/server.pem" "$W3/tls/server.key" "$EV/ws6-server.log" &
  CR_PID=$!
  for _ in $(seq 1 30); do curl -sf --cacert "$W3/tls/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json && break; sleep 1; done
  xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
  xcrun simctl uninstall "$UDID" "$BID" > /dev/null 2>&1; xcrun simctl install "$UDID" "$APPB"

  # C1: sequence 1 loads and, shown for the crash window with no failure, proves healthy.
  launch ws6-C1
  C="$EV/ws6-C1.console.txt"
  grep -q "codeLoadSuccess modules=[0-9]* sequence=1 source=network" "$C" \
    && grep -q "KeliverHost: crash rollback: sequence 1 proved healthy on this install" "$C" \
    && ok "C1: sequence 1 loaded from the network and proved healthy (on show for the crash window, no failure)" \
    || bad "C1: sequence 1 did not prove healthy: $(grep -E 'KeliverHost' "$C" | tail -4 | tr '\n' ' ')"
  reads ws6-C1 "$CR_TITLE" && ok "C1: the screen reads '$CR_TITLE'" || bad "C1: '$CR_TITLE' is not on the screen: $(tr '\n' '|' < "$EV/ws6-C1.ocr.txt" | cut -c1-200)"

  # C2: sequence 2's screen throws as it shows (an index out of bounds while it composes).
  sed -i '' "s/^      text = \"$CR_TITLE\",$/      text = listOf(\"$CR_TITLE\")[1],/" "$SCREEN"
  grep -q "text = listOf(\"$CR_TITLE\")\[1\]," "$SCREEN" || bad "C2: could not make the screen throw"
  cr_publish v2 && grep -q "published v2 (sequence 2" "$EV/ws6-publish-v2.log" \
    && ok "C2: keliver-publish wrote sequence 2, whose screen throws as it shows" || { bad "C2: publish sequence 2"; tail -20 "$EV/ws6-publish-v2.log"; }
  launch ws6-C2
  C="$EV/ws6-C2.console.txt"
  grep -q "codeLoadSuccess modules=[0-9]* sequence=2" "$C" && grep -q "KeliverHost: uncaughtException" "$C" \
    && grep -q "KeliverHost: crash rollback: sequence 2 failed on this install (its code threw): 1 of 2" "$C" \
    && ok "C2: sequence 2 loaded, threw as its screen showed, and one failure was counted (1 of 2)" \
    || bad "C2: no failure counted for sequence 2: $(grep -E 'KeliverHost' "$C" | tail -5 | tr '\n' ' ')"

  # C3: the next start throws again: set aside, and back to sequence 1 in the same process.
  launch ws6-C3
  C="$EV/ws6-C3.console.txt"
  grep -q "KeliverHost: crash rollback: sequence 2 SET ASIDE after 2 failures on this install (the last: its code threw)" "$C" \
    && grep -q "KeliverHost: !!!!!!!! CRASH ROLLBACK !!!!!!!!" "$C" \
    && ok "C3: sequence 2 threw again: set aside after 2 failures, said loudly by the debug build" \
    || bad "C3: sequence 2 was not set aside: $(grep -E 'KeliverHost' "$C" | tail -6 | tr '\n' ' ')"
  [ "$(grep -c 'KeliverHost: verifying manifests' "$C")" = 1 ] && grep -q "codeLoadSuccess modules=[0-9]* sequence=1" "$C" \
    && ok "C3: the same process went back to sequence 1, below the floor (2), from the kept manifest" \
    || bad "C3: sequence 1 did not load again: $(grep -E 'KeliverHost|codeLoad' "$C" | tail -5 | tr '\n' ' ')"
  reads ws6-C3 "$CR_TITLE" && ok "C3: the screen reads '$CR_TITLE' again (sequence 1)" || bad "C3: '$CR_TITLE' is not on the screen: $(tr '\n' '|' < "$EV/ws6-C3.ocr.txt" | cut -c1-200)"

  # C4: what REPORT_URL received: crash-rollback, sequence 2, previous-good; 8 fixed fields.
  python3 - "$EV/ws6-server.log" <<'PY' && ok "C4: REPORT_URL received crash-rollback (sequence 2, previous-good) in the 8 fixed fields" || bad "C4: no crash-rollback report"
import json, sys
reports = [json.loads(l[len("REPORT "):]) for l in open(sys.argv[1], encoding="utf-8", errors="replace") if l.startswith("REPORT ")]
keys = {"installId", "channel", "hostVersion", "sequence", "source", "outcome", "reason", "platform"}
for r in reports: print("  report:", {k: r.get(k) for k in ("outcome", "sequence", "source", "reason")})
cr = [r for r in reports if r.get("outcome") == "crash-rollback"]
sys.exit(0 if all(set(r) == keys for r in reports) and len(cr) == 1 and cr[0]["sequence"] == 2
         and cr[0]["reason"] == "previous-good" and cr[0]["platform"] == "ios" else 1)
PY

  # C5: the next start skips sequence 2 (the index still names it) and starts from sequence 1.
  launch ws6-C5
  C="$EV/ws6-C5.console.txt"
  grep -q "KeliverHost: crash rollback: sequence 2 is set aside; starting from sequence 1" "$C" \
    && grep -q "codeLoadSuccess modules=[0-9]* sequence=1" "$C" && ! grep -q "sequence=2" "$C" \
    && ! grep -q "KeliverHost: crash rollback: sequence [0-9]* failed" "$C" \
    && ok "C5: the next start skipped sequence 2 and started from sequence 1, counting nothing" \
    || bad "C5: $(grep -E 'KeliverHost' "$C" | tail -5 | tr '\n' ' ')"
  reads ws6-C5 "$CR_TITLE" && ok "C5: still '$CR_TITLE'" || bad "C5: '$CR_TITLE' is not on the screen: $(tr '\n' '|' < "$EV/ws6-C5.ocr.txt" | cut -c1-200)"

  # C6: a fix, published as sequence 3, is not held back.
  sed -i '' "s/^      text = listOf(\"$CR_TITLE\")\[1\],$/      text = \"Pantry\",/" "$SCREEN"
  cr_publish v3 && grep -q "published v3 (sequence 3" "$EV/ws6-publish-v3.log" \
    && ok "C6: keliver-publish wrote sequence 3 (\"Pantry\"), the fix" || { bad "C6: publish sequence 3"; tail -20 "$EV/ws6-publish-v3.log"; }
  launch ws6-C6
  grep -q "codeLoadSuccess modules=[0-9]* sequence=3" "$EV/ws6-C6.console.txt" \
    && ok "C6: sequence 3, newer than the set-aside one, loaded" \
    || bad "C6: sequence 3 did not load: $(grep -E 'KeliverHost' "$EV/ws6-C6.console.txt" | tail -4 | tr '\n' ' ')"
  reads ws6-C6 Pantry && ok "C6: the screen reads 'Pantry' (sequence 3)" || bad "C6: 'Pantry' is not on the screen: $(tr '\n' '|' < "$EV/ws6-C6.ocr.txt" | cut -c1-200)"

  # C8: the app's own view. A fresh install whose only bundle crashes.
  sed -i '' "s/^      text = \"Pantry\",$/      text = listOf(\"Pantry\")[1],/" "$SCREEN"
  grep -q 'text = listOf("Pantry")\[1\],' "$SCREEN" || bad "C8: could not make the screen throw"
  cr_publish v4 && grep -q "published v4 (sequence 4" "$EV/ws6-publish-v4.log" \
    && ok "C8: keliver-publish wrote sequence 4, whose screen throws as it shows" || { bad "C8: publish sequence 4"; tail -20 "$EV/ws6-publish-v4.log"; }
  xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
  xcrun simctl uninstall "$UDID" "$BID" > /dev/null 2>&1; xcrun simctl install "$UDID" "$APPB"
  launch ws6-C8a
  grep -q "KeliverHost: crash rollback: sequence 4 failed on this install (its code threw): 1 of 2" "$EV/ws6-C8a.console.txt" \
    && ok "C8: a fresh install loaded sequence 4, which threw: 1 of 2" \
    || bad "C8: no failure counted for sequence 4: $(grep -E 'KeliverHost' "$EV/ws6-C8a.console.txt" | tail -5 | tr '\n' ' ')"
  launch ws6-C8b
  grep -q "crash rollback: sequence 4 set aside after 2 of 2 failures on this install (the last: its code threw); nothing that ran on this install before it is left: the app's own view" "$EV/ws6-C8b.console.txt" \
    && ok "C8: sequence 4 threw again: set aside, with nothing to go back to: the app's own view" \
    || bad "C8: sequence 4 was not set aside to the app's view: $(grep -E 'KeliverHost' "$EV/ws6-C8b.console.txt" | tail -5 | tr '\n' ' ')"
  launch ws6-C8c
  C="$EV/ws6-C8c.console.txt"
  grep -q "KeliverHost: crash rollback: sequence 4 is set aside on this install; nothing that ran on this install before it is left" "$C" \
    && ! grep -q "sequence=4" "$C" \
    && ok "C8: the next start showed the app's view again, without loading sequence 4" \
    || bad "C8: $(grep -E 'KeliverHost' "$C" | tail -5 | tr '\n' ' ')"
  python3 - "$EV/ws6-server.log" <<'PY' && ok "C8: REPORT_URL received one crash-rollback for sequence 4 (app-view), not one per start" || bad "C8: the app-view reports are wrong"
import json, sys
reports = [json.loads(l[len("REPORT "):]) for l in open(sys.argv[1], encoding="utf-8", errors="replace") if l.startswith("REPORT ")]
cr = [r for r in reports if r.get("outcome") == "crash-rollback" and r.get("sequence") == 4]
print("  crash-rollback reports for sequence 4:", [r.get("reason") for r in cr])
sys.exit(0 if len(cr) == 1 and cr[0]["reason"] == "app-view" else 1)
PY

  # Leave the app and the simulator as W5 left them.
  sed -i '' "s/^      text = listOf(\"Pantry\")\[1\],$/      text = \"Pantry\",/" "$SCREEN"
  w3_title Pantry "$CR_TITLE" || bad "C8: could not put the title back to $CR_TITLE"
  kill "$CR_PID" 2>/dev/null; wait "$CR_PID" 2>/dev/null
  xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
  xcrun simctl uninstall "$UDID" "$BID" > /dev/null 2>&1; xcrun simctl install "$UDID" "$APPB"
  # The hosts later scenarios build (M0's Benchmark and Release, W8's) have crash rollback off.
  CR_CFG="$(find "$APP/host-ios/src/iosMain/kotlin" -name HostConfig.kt | head -1)"
  sed -i '' 's|CRASH_THRESHOLD: Int = [0-9]*|CRASH_THRESHOLD: Int = 0|' "$CR_CFG"
  grep -q 'CRASH_THRESHOLD: Int = 0' "$CR_CFG" || bad "C8: could not set CRASH_THRESHOLD to 0 for the scenarios after WS6"
fi
