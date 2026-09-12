#!/usr/bin/env bash
# Participant readiness, run immediately before EACH participant.
set -uo pipefail
T2="$(cd "$(dirname "$0")" && pwd -P)"; COND="$1"
WS="$T2/ws-$COND"; OUT="$T2/evaluator/readiness-$COND"
mkdir -p "$OUT"
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME="$HOME/Library/Android/sdk"; export PATH="$ANDROID_HOME/platform-tools:$PATH"
export NODE_EXTRA_CA_CERTS="$HOME/.android-certs/full-ca-bundle.pem"
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$HOME/.android-certs/jssecacerts -Djavax.net.ssl.trustStorePassword=changeit"
step() { printf '  %-52s %s\n' "$1" "$2"; }

FROZEN="${FROZEN_SCREEN:?set FROZEN_SCREEN to the frozen fixture screen}"
cp "$FROZEN" "$WS/src/jsMain/kotlin/screens/cart.kt"
cmp -s "$FROZEN" "$WS/src/jsMain/kotlin/screens/cart.kt" \
  && step "workspace screen restored to the frozen fixture" "ok" \
  || { step "workspace screen restore" "FAILED"; exit 1; }

# NEVER `pkill -f serveDevelopmentZipline`: the task text handed to the
# participant contains that string, so it is part of the participant's own
# `claude -p "<task>"` argv and pkill matches the PARTICIPANT. That killed nine
# runs with SIGTERM mid-diagnosis. Kill the bundle server by PORT and Gradle by
# its daemon class instead.
pkill -f GradleDaemon >/dev/null 2>&1
lsof -ti :8080 -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null; sleep 4
step "stale daemons and serves killed (by port, never by task-text match)" "ok"

( cd "$WS" && env -u KELIVER_USE_MAVEN_LOCAL ./gradlew serveDevelopmentZipline --no-daemon --console=plain \
    > "$OUT/serve.log" 2>&1 & )
for i in $(seq 1 90); do curl -sf -m 3 -o /dev/null http://localhost:8080/manifest.zipline.json && break; sleep 5; done
curl -sf -m 3 -o /dev/null http://localhost:8080/manifest.zipline.json || { step "serve" "FAILED"; exit 1; }
BUNDLE="$(curl -s http://localhost:8080/manifest.zipline.json | shasum | awk '{print $1}')"
step "controller serve up, manifest sha" "${BUNDLE:0:16}"

adb -s emulator-5554 shell am force-stop dev.keliver.portaldevice
adb -s emulator-5554 shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity >/dev/null 2>&1
sleep 16
adb -s emulator-5554 shell uiautomator dump /sdcard/r1.xml >/dev/null 2>&1
L1="$(adb -s emulator-5554 shell cat /sdcard/r1.xml | grep -oE 'text="[^"]+"' | tr -d '\r' | paste -sd' ' -)"
adb -s emulator-5554 exec-out screencap -p > "$OUT/launch.png"
step "initial state" "$L1"
echo "$L1" | grep -q 'Cart' && echo "$L1" | grep -q '\$0.00' || { step "initial state" "UNEXPECTED"; exit 1; }

B=$(adb -s emulator-5554 shell cat /sdcard/r1.xml | tr '>' '\n' | grep 'Add item' | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
set -- $(echo "$B" | grep -oE '[0-9]+'); cx=$(( ($1+$3)/2 )); cy=$(( ($2+$4)/2 ))
adb -s emulator-5554 shell input tap $cx $cy; sleep 3
adb -s emulator-5554 shell uiautomator dump /sdcard/r2.xml >/dev/null 2>&1
L2="$(adb -s emulator-5554 shell cat /sdcard/r2.xml | grep -oE 'text="[^"]+"' | tr -d '\r' | paste -sd' ' -)"
adb -s emulator-5554 exec-out screencap -p > "$OUT/after-tap.png"
step "defective transition exercised" "$L2"
echo "$L2" | grep -q '12.00" text="Total" text="\$12.00' || step "transition" "NOTE: check the string above"

# hand the environment over clean: no controller serve, app reset, 8080 free
lsof -ti :8080 -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null; sleep 4
lsof -ti :8080 -sTCP:LISTEN >/dev/null 2>&1 && { step "port 8080 free for the participant" "STILL BUSY"; exit 1; }
step "controller serve stopped, port 8080 free" "ok"
adb -s emulator-5554 shell am force-stop dev.keliver.portaldevice
step "app force-stopped, same initial state for this participant" "ok"
pkill -f GradleDaemon >/dev/null 2>&1
step "gradle daemons cleared" "ok"
{ echo "condition: $COND"; echo "manifest_sha: $BUNDLE"; echo "initial: $L1"; echo "after_tap: $L2"; } > "$OUT/readiness.txt"
