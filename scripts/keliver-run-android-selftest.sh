#!/usr/bin/env bash
#
# Self-test for keliver-run-android.sh, with no device and no build: a small
# relay (python3), and adb and gradlew stand-ins that log what they were asked
# and answer as a device and a build would. It checks what the command asks of
# them, and what it says and refuses.
#
#   scripts/keliver-run-android-selftest.sh <disposable-parent> [--package <unpacked tools bundle>]
#
# Without --package it runs this checkout's scripts/keliver-run-android.sh; with
# it, the bundle's bin/ copy. The real route (an emulator, a phone) is checked by
# the reference app's CI and by hand (docs/DEVICE_HOST.md).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PARENT="${1:?usage: $0 <disposable-parent> [--package <unpacked tools bundle>]}"
mkdir -p "$PARENT"
RUN="$(mktemp -d "$(cd "$PARENT" && pwd -P)/keliver-run-android-XXXXXX")"
if [ "${2:-}" = "--package" ]; then
  CMD="$(cd "${3:?--package needs a directory}" && pwd -P)/bin/keliver-run-android.sh"
else
  CMD="$ROOT/scripts/keliver-run-android.sh"
fi
[ -x "$CMD" ] || { echo "not executable: $CMD" >&2; exit 1; }
pass=0; fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
echo "=== keliver-run-android: $CMD"
echo "    disposable root: $RUN"

# --- stand-ins -------------------------------------------------------------------
FAKE="$RUN/fake"; mkdir -p "$FAKE"
# adb: FAKE_DEVICES (newline-separated serials) and FAKE_OUTCOME (the host's line, as a
# default host logs it: no report URL, so no "report:" lines).
cat > "$FAKE/adb" <<'SH'
#!/usr/bin/env bash
echo "adb $*" >> "$FAKE_LOG"
if [ "$1" = devices ]; then
  echo "List of devices attached"
  printf '%s\n' "$FAKE_DEVICES" | while read -r s; do [ -n "$s" ] && printf '%s\tdevice\n' "$s"; done
  exit 0
fi
[ "$1" = -s ] && shift 2
case "$1 ${2:-}" in
  "get-state "*) echo device ;;
  "shell getprop") echo "Selftest Phone" ;;
  "shell date") echo "10-28 12:00:00.000" ;;
  "logcat -d") echo "10-28 12:00:00.000 D KeliverHost: verifying manifests with portal-ed25519 abcd1234…"
               [ -n "${FAKE_OUTCOME:-}" ] && echo "10-28 12:00:01.000 D KeliverHost: $FAKE_OUTCOME" ;;
  "install "*) echo Success ;;
esac
exit 0
SH
# gradlew: logs its arguments and writes the debug APK a build would.
cat > "$FAKE/gradlew" <<'SH'
#!/usr/bin/env bash
echo "gradlew $*" >> "$FAKE_LOG"
[ "${FAKE_BUILD_FAILS:-}" = 1 ] && { echo "e: something did not compile"; exit 1; }
mkdir -p host-android/build/outputs/apk/debug && echo apk > host-android/build/outputs/apk/debug/host-android-debug.apk
SH
chmod +x "$FAKE/adb" "$FAKE/gradlew"
export FAKE_LOG="$RUN/calls.log"

# A relay: /devstate, and /bundles/index.json from $RUN/index.json.
PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
cat > "$RUN/relay.py" <<'PY'
import http.server, sys
root = sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        if self.path == "/devstate":
            body = b'{"ok":true}'
        elif self.path == "/bundles/index.json":
            body = open(root + "/index.json", "rb").read()
        else:
            self.send_response(404); self.end_headers(); return
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers(); self.wfile.write(body)
http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
RELAY_PID=""
trap '[ -n "$RELAY_PID" ] && kill "$RELAY_PID" 2>/dev/null' EXIT

# An app: keliver.portal.json on that port, a gradlew, and a host-android/ with an applicationId.
APP="$RUN/app"; mkdir -p "$APP/host-android"
printf '{"port": %s}\n' "$PORT" > "$APP/keliver.portal.json"
cp "$FAKE/gradlew" "$APP/gradlew"
printf "android {\n  defaultConfig {\n    applicationId 'com.example.selftest.host'\n  }\n}\n" > "$APP/host-android/build.gradle"

run() {  # $1 = label; then the command's arguments. Output in $RUN/$1.out, status in $RC.
  local label="$1"; shift
  : > "$FAKE_LOG"
  PATH="$FAKE:$PATH" KELIVER_RUN_WAIT_SECONDS=6 "$CMD" "$@" > "$RUN/$label.out" 2>&1; RC=$?
}
says() { grep -qF -- "$2" "$RUN/$1.out"; }

# --- refusals before anything is asked of a device -----------------------------------
run help --help
[ "$RC" = 0 ] && says help "this app's screens on an Android phone or emulator" && ok "--help prints what it does" || bad "--help: rc=$RC"

mkdir -p "$RUN/not-an-app"
run notapp "$RUN/not-an-app"
[ "$RC" != 0 ] && says notapp "has no keliver.portal.json" && ok "outside an app: refused, saying why" || bad "outside an app: rc=$RC $(head -2 "$RUN/notapp.out")"

run norelay "$APP"
[ "$RC" != 0 ] && says norelay "no portal answers on port $PORT" && says norelay "keliver-portal" && ! grep -q '^adb -s' "$FAKE_LOG" \
  && ok "no portal running: refused, naming the port and keliver-portal; no device touched" || bad "no portal: rc=$RC $(head -2 "$RUN/norelay.out")"

python3 "$RUN/relay.py" "$PORT" "$RUN" & RELAY_PID=$!
for _ in $(seq 1 50); do curl -sf -m 1 -o /dev/null "http://127.0.0.1:$PORT/devstate" && break; sleep 0.1; done

echo '{"format":1,"entries":[]}' > "$RUN/index.json"
run nobundle "$APP"
[ "$RC" != 0 ] && says nobundle "has published no bundle yet" && says nobundle "curl -X POST http://localhost:$PORT/publish" && ! grep -q '^adb -s' "$FAKE_LOG" \
  && ok "nothing published: refused, saying how to publish; no device touched" || bad "nothing published: rc=$RC $(head -2 "$RUN/nobundle.out")"

echo '{"format":1,"entries":[{"sequence":1,"manifest":"v1/manifest.zipline.json"},{"sequence":3,"manifest":"v3/manifest.zipline.json"}]}' > "$RUN/index.json"
export FAKE_DEVICES=""
run nodevice "$APP"
[ "$RC" != 0 ] && says nodevice "no device" && ok "no device: refused" || bad "no device: rc=$RC $(head -3 "$RUN/nodevice.out")"

export FAKE_DEVICES="emulator-5554
phone-serial-b"
run twodevices "$APP"
[ "$RC" != 0 ] && says twodevices "several devices; choose one with --serial" && ! grep -q '^adb -s' "$FAKE_LOG" \
  && ok "two devices and no --serial: refused, touching neither" || bad "two devices: rc=$RC $(head -3 "$RUN/twodevices.out")"

# --- the route ---------------------------------------------------------------------
export FAKE_OUTCOME="codeLoadSuccess modules=4 sequence=3 source=network"
run happy --serial phone-serial-b "$APP"
L="$FAKE_LOG"
[ "$RC" = 0 ] && says happy "loaded bundle sequence 3 from http://localhost:$PORT" \
  && ok "with --serial among two devices: it builds, installs, starts, and reports the bundle the host loaded" \
  || bad "the route: rc=$RC $(tail -4 "$RUN/happy.out" | tr '\n' ' ')"
says happy "has published bundle sequence 3" && ok "it names the newest published sequence (3)" || bad "no newest sequence in the output"
grep -q "^gradlew --console=plain -p host-android assembleDebug -Pkeliver.bundleServer=http://localhost:$PORT$" "$L" \
  && ok "the debug build looks for bundles at http://localhost:$PORT (a -P property: gradle.properties is not changed)" || bad "gradlew was not asked for that build: $(grep gradlew "$L")"
grep -q "^adb -s phone-serial-b reverse tcp:$PORT tcp:$PORT$" "$L" && ok "adb reverse tcp:$PORT on the chosen device" || bad "no adb reverse: $(grep reverse "$L")"
grep -q "^adb -s phone-serial-b install -r $APP/host-android/build/outputs/apk/debug/host-android-debug.apk$" "$L" \
  && ok "it installs the APK the build wrote" || bad "no install of the built APK: $(grep install "$L")"
grep -q "^adb -s phone-serial-b logcat -d -T 10-28 12:00:00.000 -s KeliverHost:D$" "$L" \
  && ok "it reads only this start's host log (logcat -T, from the device's clock)" || bad "the log was not read from the device's time: $(grep logcat "$L" | tail -1)"
grep -q "^adb -s phone-serial-b shell monkey -p com.example.selftest.host -c android.intent.category.LAUNCHER 1$" "$L" \
  && grep -q "^adb -s phone-serial-b shell am force-stop com.example.selftest.host$" "$L" \
  && ok "it restarts the host by its applicationId (com.example.selftest.host)" || bad "the host was not started by its applicationId"
others="$(grep '^adb ' "$L" | grep -v '^adb devices$' | grep -v '^adb -s phone-serial-b ' || true)"
[ -z "$others" ] && ok "every adb call names the chosen device: emulator-5554 is never touched" || bad "adb calls not on the chosen device: $others"
grep -q 'publish' "$L" && bad "it asked something to publish" || ok "it never publishes"
grep -q 'keliver.bundleServer' "$APP/host-android/build.gradle" "$APP/keliver.portal.json" 2>/dev/null && bad "it wrote into the app's files" || ok "the app's files are unchanged"

export FAKE_DEVICES="emulator-5554"
run onedevice "$APP"
[ "$RC" = 0 ] && grep -q "^adb -s emulator-5554 reverse tcp:$PORT tcp:$PORT$" "$FAKE_LOG" \
  && ok "one device and no --serial: that device" || bad "one device: rc=$RC $(tail -3 "$RUN/onedevice.out" | tr '\n' ' ')"

export FAKE_OUTCOME="E KeliverHost: bundle lookup failed"
run nobundlehost "$APP"
[ "$RC" != 0 ] && says nobundlehost "the host did not load a bundle: bundle lookup failed" \
  && ok "the host's lookup fails: it fails, with the host's own lines" || bad "a failed lookup: rc=$RC $(tail -3 "$RUN/nobundlehost.out" | tr '\n' ' ')"

export FAKE_OUTCOME="E KeliverHost: bundle index: no compatible entry among 2 (channel stable, capabilities [])"
run noentry "$APP"
[ "$RC" != 0 ] && says noentry "the host did not load a bundle: bundle index: no compatible entry" \
  && ok "no compatible entry in the index: it fails at once, with the host's line" || bad "no compatible entry: rc=$RC $(tail -3 "$RUN/noentry.out" | tr '\n' ' ')"

export FAKE_OUTCOME="codeLoadSuccess modules=4 sequence=2 source=cache"
run fromcache "$APP"
[ "$RC" != 0 ] && says fromcache "started from its cached bundle, not from http://localhost:$PORT" \
  && ok "a host that starts from its cache: not counted as reaching the relay" || bad "a cache start: rc=$RC $(tail -3 "$RUN/fromcache.out" | tr '\n' ' ')"

export FAKE_OUTCOME=""
run silent "$APP"
[ "$RC" != 0 ] && says silent "the host said nothing about a bundle within 6 s" \
  && ok "a host that never reports: it gives up after the wait, and says so" || bad "a silent host: rc=$RC $(tail -3 "$RUN/silent.out" | tr '\n' ' ')"

export FAKE_OUTCOME="codeLoadSuccess modules=4 sequence=3 source=network" FAKE_BUILD_FAILS=1
run buildfails "$APP"
[ "$RC" != 0 ] && says buildfails "the build failed" && says buildfails "e: something did not compile" && ! grep -q ' install ' "$FAKE_LOG" \
  && ok "a failed build: it stops before installing, showing the compiler's error" || bad "a failed build: rc=$RC $(tail -3 "$RUN/buildfails.out" | tr '\n' ' ')"
unset FAKE_BUILD_FAILS

echo
echo "keliver-run-android: $pass passed, $fail failed   ($RUN)"
[ "$fail" -eq 0 ]
