# Sourced by ci/device.sh after the W3/W4 static route: W2, the Keliver host
# embedded in an "existing" app (reference/embed/android), which prepare.sh
# scaffolded with keliver-new-production-host.sh --embed and built (W2_APK,
# and W2_FOREIGN_APK trusting another key). Uses device.sh's SERIAL, EV, HERE,
# PUB, HOST_TAG, ok/bad/drive/fold, and android-static.sh's W3, W3_TLS,
# W3_PUBLISH, w3_title, w3_publish, w3_signed_sequence.
#   X1  one screen holds the native views AND the guest's screen, inside the
#       KeliverView's bounds
#   X2  that screen is a signed load from the static server (this app's key)
#   X3  the embedded guest is interactive (Add 1, three times)
#   X4  native navigation and a rotation recreate the native screen (positive
#       control), with one lookup and one code load in the process
#   X5  the host's rollback floor is stored, in the existing app's own data
#   X6  trusting another key: the Keliver view loads nothing, the native views
#       stay and the process lives
#   X7  the app shrunk by R8 (a minified release): the guest still verifies,
#       loads and renders in the KeliverView
# The host kit on a device (K4), through the existing app's deep links:
#   X8  a route reaches the guest (launchRoute), with only the app's allowed
#       query keys
#   X9  the guest closes its screen: the app's onClose replaces the view
#   X10 the guest throws while its screen shows: screen-failed, and the app's
#       onUnavailable replaces the view; the process lives
# X6 also checks that the app's onUnavailable ran (its native fallback shows).
echo "--- 5. W2: the host embedded in an existing app"
W2_ID="${W2_ID:-com.example.existing}"
# Whether the existing app's own process crashed: on a phone, other processes' crashes are in
# the same log ("FATAL EXCEPTION: main" is followed by "Process: <id>, PID: <n>").
w2_crashed(){ grep -A2 "FATAL EXCEPTION" "$1" | grep -q "Process: $W2_ID, PID:"; }
w2_launch(){  # $1 = logcat file: a cold start of the existing app
  adb -s "$SERIAL" logcat -c || true
  adb -s "$SERIAL" shell am force-stop "$W2_ID"
  adb -s "$SERIAL" shell am start -n "$W2_ID/.MainActivity" > /dev/null
  sleep 15
  adb -s "$SERIAL" logcat -d -v threadtime > "$1"
}
w2_route(){  # $1 = deep link, $2 = logcat file: a cold start of the existing app on that route
  adb -s "$SERIAL" logcat -c || true
  adb -s "$SERIAL" shell am force-stop "$W2_ID"
  adb -s "$SERIAL" shell "am start -a android.intent.action.VIEW -d '$1' -n $W2_ID/.MainActivity" > /dev/null
  sleep 15
  adb -s "$SERIAL" logcat -d -v threadtime > "$2"
}
w2_dump(){  # $1 = label: the view hierarchy, kept as evidence
  adb -s "$SERIAL" shell rm -f /sdcard/w2.xml
  adb -s "$SERIAL" shell uiautomator dump /sdcard/w2.xml > /dev/null 2>&1
  adb -s "$SERIAL" shell cat /sdcard/w2.xml > "$EV/w2-$1.xml"
}

# A fresh, unconstrained bundle for this app to load (the W4 steps above left
# rollouts and gates on the newest entries). The screen text is still "Attic".
w3_publish v7-embed && grep -q "published v7 (sequence 7, channel stable" "$EV/w3-publish-v7-embed.log" && w3_signed_sequence 7 \
  && ok "W2: keliver-publish wrote v7 (\"Attic\") for the embedded host" || { bad "W2: publish v7"; tail -20 "$EV/w3-publish-v7-embed.log"; }
python3 "$HERE/w3/static_https.py" "$W3/site" 8443 "$W3_TLS/server.pem" "$W3_TLS/server.key" "$EV/w2-server.log" &
SERVE_PID=$!
for _ in $(seq 1 30); do curl -sf --cacert "$W3_TLS/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json && break; sleep 1; done

adb -s "$SERIAL" install -r "$W2_APK" > "$EV/install-embed-app.log" 2>&1 \
  && ok "W2: installed the existing app ($(sha256 "$W2_APK" | cut -c1-12)…)" || bad "W2: the existing app did not install"
adb -s "$SERIAL" shell pm clear "$W2_ID" > /dev/null
w2_launch "$EV/logcat-embed.txt"

# X1: the native views and the guest's list on one screen; every guest node
# inside the KeliverView, every native one outside it.
w2_dump X1
python3 - "$EV/w2-X1.xml" > "$EV/w2-X1.txt" 2>&1 <<'PY'
import re, sys, xml.etree.ElementTree as ET
raw = open(sys.argv[1]).read(); root = ET.fromstring(raw[raw.index("<?xml"):].split("?>", 1)[1])
nodes = list(root.iter("node"))
def box(n): return tuple(map(int, re.findall(r"\d+", n.get("bounds"))))
def inside(b, o): return o[0] <= b[0] and o[1] <= b[1] and b[2] <= o[2] and b[3] <= o[3]
view = [n for n in nodes if n.get("content-desc") == "keliver-view"]
header = [n for n in nodes if n.get("text") == "Native header"]
button = [n for n in nodes if n.get("text", "").lower() == "native details"]
title = [n for n in nodes if n.get("text") == "Attic"]
assert view and header and button and title, ("missing", bool(view), bool(header), bool(button), bool(title))
v = box(view[0])
assert inside(box(title[0]), v), "the guest's title is outside the KeliverView"
assert not inside(box(header[0]), v) and not inside(box(button[0]), v), "a native view is inside the KeliverView"
print("ok: native header and button above", v, "; guest title", box(title[0]), "inside it")
PY
[ $? = 0 ] && ok "X1: native views and the guest's screen ('Attic') on one screen, the guest inside the KeliverView" \
  || { bad "X1: $(tail -1 "$EV/w2-X1.txt")"; }

# X2: a signed load, from the static server, verified with this app's key.
grep -q "$HOST_TAG: verifying manifests with portal-ed25519 ${PUB:0:8}" "$EV/logcat-embed.txt" \
  && grep -q "$HOST_TAG: loading https://$HOST_ADDR:8443/bundles/v7/manifest.zipline.json (index sequence 7," "$EV/logcat-embed.txt" \
  && grep -q "$HOST_TAG: codeLoadSuccess" "$EV/logcat-embed.txt" \
  && ok "X2: the embedded host verified with this app's key and loaded v7 from the static server" \
  || bad "X2: $(grep -E "$HOST_TAG" "$EV/logcat-embed.txt" | head -3 | tr '\n' ' ')"

# X3: the embedded guest is interactive.
drive prod X3embed; fold "X3: Add 1 three times inside the embedded view" $?

# X4: to the native details screen and back, then a rotation. The native screen
# logs each creation (the positive control); the lookup and the load stay one.
adb -s "$SERIAL" logcat -c || true
w2_launch "$EV/logcat-embed-nav.txt"
w2_dump X4-before
python3 - "$EV/w2-X4-before.xml" > "$EV/w2-X4-tap.txt" <<'PY'
import re, sys, xml.etree.ElementTree as ET
raw = open(sys.argv[1]).read(); root = ET.fromstring(raw[raw.index("<?xml"):].split("?>", 1)[1])
n = [n for n in root.iter("node") if n.get("text", "").lower() == "native details"][0]
x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds"))); print((x1 + x2) // 2, (y1 + y2) // 2)
PY
adb -s "$SERIAL" shell input tap $(cat "$EV/w2-X4-tap.txt"); sleep 4
w2_dump X4-details
grep -q 'text="Native details screen"' "$EV/w2-X4-details.xml" && ok "X4: the native details screen opened" || bad "X4: no native details screen"
adb -s "$SERIAL" shell input keyevent KEYCODE_BACK; sleep 4
adb -s "$SERIAL" shell settings put system accelerometer_rotation 0
adb -s "$SERIAL" shell settings put system user_rotation 1; sleep 8
adb -s "$SERIAL" shell settings put system user_rotation 0; sleep 8
adb -s "$SERIAL" logcat -d -v threadtime > "$EV/logcat-embed-nav.txt"
created="$(grep -c "ExistingApp: native screen created" "$EV/logcat-embed-nav.txt")"
loads="$(grep -c "$HOST_TAG: loading https://" "$EV/logcat-embed-nav.txt")"
codeloads="$(grep -c "$HOST_TAG: codeLoadSuccess" "$EV/logcat-embed-nav.txt")"
[ "$created" -ge 2 ] && ok "X4: the rotation recreated the native screen ($created creations)" || bad "X4: the native screen was not recreated ($created)"
[ "$loads" = 1 ] && [ "$codeloads" = 1 ] && ok "X4: one lookup and one code load across native navigation and a rotation" \
  || bad "X4: $loads lookups and $codeloads code loads"
w2_dump X4-after
grep -q 'text="Attic"' "$EV/w2-X4-after.xml" && grep -q 'text="Native header"' "$EV/w2-X4-after.xml" \
  && ok "X4: afterwards the native header and the guest's screen are both still there" || bad "X4: the screen lost a part after navigation"

# X5: the rollback floor, in the existing app's own preferences.
adb -s "$SERIAL" shell "run-as $W2_ID cat shared_prefs/keliver-host.xml" > "$EV/w2-prefs.xml" 2>&1
grep -q 'highestSequence-[^"]*" value="7"' "$EV/w2-prefs.xml" \
  && ok "X5: the embedded host stored its rollback floor (7) in the existing app's data" || bad "X5: $(head -c 300 "$EV/w2-prefs.xml")"

# X8: a deep link's route reaches the guest. The guest marks the route it got
# (KeliverTiming, debug builds); the link's token is not an allowed key.
w2_route "existing://k4/items?tab=stock&token=k4secret" "$EV/logcat-embed-route.txt"
w2_dump X8
# The mark is the whole route: had the token got through, it would read
# route-k4-items-tab-stock-token-k (32 at most), and this would fail.
grep -q "KeliverTiming: route-k4-items-tab-stock " "$EV/logcat-embed-route.txt" \
  && ok "X8: the guest received the route k4/items?tab=stock and nothing else: the link's token is not an allowed key" \
  || bad "X8: no route mark: $(grep -E "KeliverTiming: route|$HOST_TAG" "$EV/logcat-embed-route.txt" | head -3 | tr '\n' ' ')"
grep -q 'text="Attic"' "$EV/w2-X8.xml" && grep -q 'text="Native header"' "$EV/w2-X8.xml" \
  && ok "X8: the routed screen shows the guest and the native views" || bad "X8: the routed screen is missing a part"

# X9: the guest closes its screen; the app's onClose puts a native view there.
w2_route "existing://k4/close" "$EV/logcat-embed-close.txt"
w2_dump X9
grep -q "KeliverTiming: route-k4-close " "$EV/logcat-embed-close.txt" && grep -q "ExistingApp: keliver closed" "$EV/logcat-embed-close.txt" \
  && ok "X9: the guest asked to close and the app's onClose ran" \
  || bad "X9: $(grep -E "KeliverTiming: route|ExistingApp|$HOST_TAG" "$EV/logcat-embed-close.txt" | head -4 | tr '\n' ' ')"
grep -q 'text="Keliver closed"' "$EV/w2-X9.xml" && grep -q 'text="Native header"' "$EV/w2-X9.xml" \
  && [ -n "$(adb -s "$SERIAL" shell pidof "$W2_ID" | tr -d '\r')" ] \
  && ok "X9: the app's native view took the Keliver view's place; the app is still running" || bad "X9: the screen after the close is not the app's"

# X10: the guest throws while its screen shows: the host reports screen-failed and
# the app's onUnavailable puts its own view there.
w2_route "existing://k4/crash" "$EV/logcat-embed-crash.txt"
w2_dump X10
grep -q "KeliverTiming: route-k4-crash " "$EV/logcat-embed-crash.txt" && grep -q "ExistingApp: keliver unavailable: screen-failed" "$EV/logcat-embed-crash.txt" \
  && ok "X10: the guest threw, and the app's onUnavailable ran with screen-failed" \
  || bad "X10: $(grep -E "KeliverTiming: route|ExistingApp|$HOST_TAG|uncaught" "$EV/logcat-embed-crash.txt" | head -5 | tr '\n' ' ')"
grep -q 'text="Native fallback"' "$EV/w2-X10.xml" && grep -q 'text="Native header"' "$EV/w2-X10.xml" \
  && [ -n "$(adb -s "$SERIAL" shell pidof "$W2_ID" | tr -d '\r')" ] \
  && ok "X10: the app's fallback took the Keliver view's place; the app is still running" || bad "X10: the screen after the exception is not the app's"
w2_crashed "$EV/logcat-embed-crash.txt" && bad "X10: the guest's exception crashed the process" || ok "X10: no process crash"

# X6: the same app trusting another key: nothing loads in the Keliver view, the
# native views stay, the process lives.
adb -s "$SERIAL" install -r "$W2_FOREIGN_APK" > "$EV/install-embed-foreign.log" 2>&1 \
  && ok "X6: installed the existing app built trusting another key" || bad "X6: the foreign-key build did not install"
adb -s "$SERIAL" shell pm clear "$W2_ID" > /dev/null
w2_launch "$EV/logcat-embed-foreign.txt"
w2_dump X6
grep -q "$HOST_TAG: codeLoadSuccess" "$EV/logcat-embed-foreign.txt" && bad "X6: code loaded under a key that did not sign it" \
  || ok "X6: nothing loaded under another key"
grep -q "$HOST_TAG: codeLoadFailed" "$EV/logcat-embed-foreign.txt" && ok "X6: the host reported the load failed (the manifest did not verify)" \
  || bad "X6: no codeLoadFailed: $(grep -E "$HOST_TAG" "$EV/logcat-embed-foreign.txt" | head -3 | tr '\n' ' ')"
grep -q 'text="Native header"' "$EV/w2-X6.xml" && ! grep -q 'text="Attic"' "$EV/w2-X6.xml" \
  && [ -n "$(adb -s "$SERIAL" shell pidof "$W2_ID" | tr -d '\r')" ] \
  && ok "X6: the native views are still shown and the app is still running" || bad "X6: the existing app did not survive the refusal"
grep -q "ExistingApp: keliver unavailable: " "$EV/logcat-embed-foreign.txt" && grep -q 'text="Native fallback"' "$EV/w2-X6.xml" \
  && ok "X6: the app's onUnavailable ran ($(sed -n 's/.*ExistingApp: keliver unavailable: \([a-z-]*\).*/\1/p' "$EV/logcat-embed-foreign.txt" | head -1)) and its fallback shows (not a blank view)" \
  || bad "X6: no onUnavailable fallback: $(grep -E "ExistingApp|$HOST_TAG" "$EV/logcat-embed-foreign.txt" | head -3 | tr '\n' ' ')"

# X7: the minified release. Zipline crosses the bridge by name and reflection, so
# R8 can break it at run time only: this is the check that the library's
# consumer rules are enough.
adb -s "$SERIAL" install -r "$W2_RELEASE_APK" > "$EV/install-embed-release.log" 2>&1 \
  && ok "X7: installed the existing app's minified release" || bad "X7: the release did not install: $(tail -2 "$EV/install-embed-release.log" | tr '\n' ' ')"
adb -s "$SERIAL" shell pm clear "$W2_ID" > /dev/null
w2_launch "$EV/logcat-embed-release.txt"
w2_dump X7
# The host's own lines only: on a phone other processes log the word too (a
# Samsung service prints "value for uncaughtException will be default").
grep -q "$HOST_TAG: codeLoadSuccess" "$EV/logcat-embed-release.txt" && ! grep -qE "$HOST_TAG: (codeLoadFailed|uncaughtException)" "$EV/logcat-embed-release.txt" \
  && ! w2_crashed "$EV/logcat-embed-release.txt" \
  && ok "X7: the minified app verified and loaded v7 (codeLoadSuccess, no failure)" \
  || bad "X7: $(grep -E "$HOST_TAG|FATAL|AndroidRuntime" "$EV/logcat-embed-release.txt" | head -4 | tr '\n' ' ')"
grep -q 'text="Attic"' "$EV/w2-X7.xml" && grep -q 'text="Native header"' "$EV/w2-X7.xml" \
  && ok "X7: the minified app shows the native header and the guest's screen" || bad "X7: the minified app's screen is missing a part"

kill "$SERVE_PID" 2>/dev/null; wait "$SERVE_PID" 2>/dev/null; SERVE_PID=""
