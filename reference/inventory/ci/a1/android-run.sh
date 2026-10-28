# Sourced last by ci/device.sh: adoption item 6a, the app's screens on a device
# in one command. keliver-run-android.sh against this app's relay, on this
# device, starting as a new developer would: the host not installed. Uses
# device.sh's APP, KP, REPO, SERIAL, EV, PROD_ID, HOST_TAG, ok/bad, portal_up and
# portal_down.
#   A1  with the portal up and a bundle published, the command builds a debug
#       host for http://localhost:<port>, forwards the port with adb reverse,
#       installs, starts it, and the host loads the newest published bundle
#   A2  nothing else was asked of the developer: the app's files are unchanged
echo "--- 9. A1: the app's screens on this device in one command (keliver-run-android)"
# The physical route's host-android/src/debug trusts the test CA and refuses cleartext (W7),
# so a debug build for the relay's http:// cannot load there; that route is checked by hand.
if [ "$DEVICE_KIND" = physical ]; then
  skip "A1: not on the physical route (W7's no-cleartext overlay on host-android/src/debug)"
else
if [ -x "$KP/keliver-run-android.sh" ]; then RUNA="$KP/keliver-run-android.sh"; RUNA_WHICH="the zip's bin/"
else RUNA="$REPO/scripts/keliver-run-android.sh"; RUNA_WHICH="this repository's scripts/ (unreleased)"; fi
portal_up "$APP" "$EV/relay-A-run.log" && ok "A1: the relay for this app is up again" || bad "A1: the relay did not start"
adb -s "$SERIAL" uninstall "$PROD_ID" > /dev/null 2>&1 || true
before="$(cd "$APP" && git status --porcelain --untracked-files=all -- . ':!build' ':!host-android/build' ':!.gradle' ':!host-android/.gradle' 2>/dev/null | sort)"
t0=$(date +%s)
"$RUNA" --serial "$SERIAL" "$APP" > "$EV/a1-run-android.txt" 2>&1; rc=$?
echo "    keliver-run-android ($RUNA_WHICH): exit $rc in $(( $(date +%s) - t0 )) s"
sed 's/^/    | /' "$EV/a1-run-android.txt"
newest="$(sed -n 's/.*has published bundle sequence \([0-9][0-9]*\).*/\1/p' "$EV/a1-run-android.txt" | head -1)"
loaded="$(sed -n 's/.*loaded bundle sequence \([0-9][0-9]*\) from http:\/\/localhost:.*/\1/p' "$EV/a1-run-android.txt" | head -1)"
[ "$rc" = 0 ] && [ -n "$newest" ] && [ "$loaded" = "$newest" ] \
  && ok "A1: one command, from no host installed to the host showing the newest bundle (sequence $loaded)" \
  || bad "A1: rc=$rc, newest published ${newest:-?}, loaded ${loaded:-?}"
adb -s "$SERIAL" reverse --list 2>/dev/null | grep -q "tcp:8077" && ok "A1: the device reaches the relay through adb reverse" || bad "A1: no adb reverse for the relay"
after="$(cd "$APP" && git status --porcelain --untracked-files=all -- . ':!build' ':!host-android/build' ':!.gradle' ':!host-android/.gradle' 2>/dev/null | sort)"
[ "$before" = "$after" ] && ok "A2: the app's tracked and untracked files are as they were" || bad "A2: the command changed the app: $(diff <(echo "$before") <(echo "$after") | head -5 | tr '\n' ' ')"
adb -s "$SERIAL" reverse --remove tcp:8077 > /dev/null 2>&1 || true
portal_down "$APP"
fi
