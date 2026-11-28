# shellcheck shell=bash
# MS4-04, schema-mismatch reports, on the local-maven route (sourced by ci/device.sh after P7:
# the relay is down, and the production host has run v2). A test bundle with one unknown tag: the
# guest's probe (app/src/jsMain/kotlin/device/Main.kt, SCHEMA_MISMATCH_PROBE) is switched on,
# published as the next version and loaded. Two of its texts carry one modifier that it sends with
# a tag no schema has. The host built from this checkout (0.5 line, keliver.schemaMismatchReports@1)
# must ignore the modifier, show the rest, and report the mismatch exactly once: S1-S4. The probe
# is switched off again afterwards.
#
# Uses device.sh's ok, bad, launch, drive, fold, portal_up and portal_down, and its APP, EV, HERE,
# RELAY_PORT and HOST_TAG.

MS4_MAIN="$APP/src/jsMain/kotlin/device/Main.kt"
MS4_PROBE_OFF='^private const val SCHEMA_MISMATCH_PROBE = false$'
MS4_PROBE_ON='private const val SCHEMA_MISMATCH_PROBE = true'
cp "$MS4_MAIN" "$EV/Main.kt.before-ms4-04"
if grep -q "$MS4_PROBE_OFF" "$MS4_MAIN"; then
  sed "s/$MS4_PROBE_OFF/$MS4_PROBE_ON/" "$EV/Main.kt.before-ms4-04" > "$MS4_MAIN"
fi
if grep -qx "$MS4_PROBE_ON" "$MS4_MAIN"; then
  ok "S1: the guest's schema-mismatch probe is on, for one bundle"
  if portal_up "$APP" "$EV/relay-A-ms4-04.log"; then
    curl -s -m 600 -X POST "http://localhost:$RELAY_PORT/publish" > "$EV/publish-mismatch.log" 2>&1
    MS4_SEQ="$(grep -o 'publish OK: bundle v[0-9]*' "$EV/publish-mismatch.log" | head -1 | grep -o '[0-9]*$')"
    if [ -n "$MS4_SEQ" ]; then
      ok "S1: the probe's bundle published as v$MS4_SEQ"
      launch prod "$EV/logcat-prod-mismatch.txt"
      grep -q "$HOST_TAG: codeLoadSuccess" "$EV/logcat-prod-mismatch.txt" \
        && ok "S2: the probe's signed bundle loaded (codeLoadSuccess)" || bad "S2: the probe's bundle did not load"
      # The host ignored the modifier, not the widgets, nor the rest of the tree.
      drive title Stockroom S2; fold "S2: with the unknown modifier ignored, the screen still shows Stockroom" $?
      # The title is v2's too; the probe's own texts are only in this bundle, and they are the
      # widgets that carry the unknown modifier (drive.py keeps each screen it read as S2-NN-title.xml).
      MS4_DUMP="$(ls "$EV"/S2-*-title.xml 2>/dev/null | tail -1)"
      [ -n "$MS4_DUMP" ] && grep -q 'text="Schema mismatch probe"' "$MS4_DUMP" && grep -q 'text="Schema mismatch probe, again"' "$MS4_DUMP" \
        && ok "S2: the probe's bundle is on screen, with both texts that carry the unknown modifier drawn" \
        || bad "S2: the probe's two texts are not on screen (${MS4_DUMP:-no S2 screen dump})"
      # S3: one report on the device, for the probe's bundle, with the category as its reason.
      MS4_LINES="$(tr -d '\r' < "$EV/logcat-prod-mismatch.txt" | grep "$HOST_TAG: report: schema-mismatch ")"
      MS4_N="$(printf '%s' "$MS4_LINES" | grep -c 'report: schema-mismatch ')"
      [ "$MS4_N" = 1 ] && printf '%s' "$MS4_LINES" | grep -q "report: schema-mismatch sequence=$MS4_SEQ reason=schema-mismatch\$" \
        && ok "S3: one unknown modifier tag on two widgets, one schema-mismatch report on the device (sequence $MS4_SEQ)" \
        || bad "S3: $MS4_N schema-mismatch reports in the host's log, expected one for sequence $MS4_SEQ: $(printf '%s' "$MS4_LINES" | head -3 | tr '\n' ' ')"
      # S4: the relay received exactly that one. The host POSTs it in the background after the log
      # line, best effort, so the relay is asked for up to 30 s.
      MS4_RELAY=1
      for _ in $(seq 1 10); do
        MS4_HTTP="$(curl -s -m 5 -o "$EV/relay-reports-mismatch.json" -w '%{http_code}' "http://localhost:$RELAY_PORT/reports")"
        # Only a 200 is a summary (a refusal or a failure is JSON too, and would read as "no reports").
        [ "$MS4_HTTP" = 200 ] && python3 "$HERE/ms4-04/mismatch_reports.py" "$EV/relay-reports-mismatch.json" android "$MS4_SEQ" > "$EV/relay-reports-mismatch.txt" 2>&1 \
          && { MS4_RELAY=0; break; }
        sleep 3
      done
      if [ "$MS4_RELAY" = 0 ]; then
        ok "S4: the relay's /report took exactly one schema-mismatch report, for v$MS4_SEQ (GET /reports)"
      else
        # Why not: the host logs a report it could not send (KeliverHost.postReport's notSent).
        adb -s "$SERIAL" logcat -d -v threadtime > "$EV/logcat-prod-mismatch-after.txt"
        bad "S4: GET /reports answered ${MS4_HTTP:-none}: $(head -c 300 "$EV/relay-reports-mismatch.json" | tr '\n' ' '); $(tail -1 "$EV/relay-reports-mismatch.txt" 2>/dev/null); the relay's log: $(tail -3 "$EV/relay-A-ms4-04.log" | tr '\n' ' '); the host:$(tr -d '\r' < "$EV/logcat-prod-mismatch-after.txt" | grep -E "$HOST_TAG: report( not sent)?:" | sed 's/^.*'"$HOST_TAG"': //' | tr '\n' ';')"
      fi
    else
      bad "S1: the probe's bundle did not publish: $(tail -2 "$EV/publish-mismatch.log" | tr '\n' ' ')"
    fi
    portal_down "$APP"
  else
    bad "S1: the relay did not come back for the probe's bundle"
  fi
else
  bad "S1: could not switch the probe on in $MS4_MAIN (no '$MS4_PROBE_OFF' line)"
fi
cp "$EV/Main.kt.before-ms4-04" "$MS4_MAIN"
