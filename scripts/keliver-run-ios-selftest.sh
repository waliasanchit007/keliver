#!/usr/bin/env bash
#
# Self-test for keliver-run-ios.sh, with no simulator and no build: a small relay
# (python3), and xcrun, xcodebuild and open stand-ins that log what they were asked
# and answer as simctl and a build would. It checks what the command asks of them,
# and what it says and refuses. Nothing here needs macOS: it runs on a Linux CI box
# too, and on a Mac it never reaches the real Xcode.
#
#   scripts/keliver-run-ios-selftest.sh <disposable-parent> [--package <unpacked tools bundle>]
#
# Without --package it runs this checkout's scripts/keliver-run-ios.sh; with it, the
# bundle's bin/ copy (and its keliver-new-ios-host.sh, for the app with no host-ios/).
# The real route (Xcode, a simulator, a host's own log) is not run here.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PARENT="${1:?usage: $0 <disposable-parent> [--package <unpacked tools bundle>]}"
# It writes a fixture store (a public key) for the scaffolding case: never under the real
# store, the Gradle home or $PORTAL_STORE.
# shellcheck source=keliver-test-isolation-guard.sh
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
RUN="$(keliver_make_run_dir "$PARENT" run-ios)" || exit $?
if [ "${2:-}" = "--package" ]; then
  CMD="$(cd "${3:?--package needs a directory}" && pwd -P)/bin/keliver-run-ios.sh"
else
  CMD="$ROOT/scripts/keliver-run-ios.sh"
fi
[ -x "$CMD" ] || { echo "not executable: $CMD" >&2; exit 1; }
pass=0; fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
echo "=== keliver-run-ios: $CMD"
echo "    disposable root: $RUN"

# --- stand-ins -------------------------------------------------------------------
FAKE="$RUN/fake"; mkdir -p "$FAKE"
# xcrun simctl: the simulators in $FAKE_SIMS; a launch writes the host's stdout (its
# KeliverHost: lines, FAKE_OUTCOME last) to --stdout, as a running host would. The first
# FAKE_QUIET_LAUNCHES launches print nothing, as a process that never started.
cat > "$FAKE/xcrun" <<'SH'
#!/usr/bin/env bash
echo "xcrun $*" >> "$FAKE_LOG"
[ "${1:-}" = simctl ] || { echo "stand-in xcrun: only simctl" >&2; exit 1; }
shift
case "$1" in
  list)  # simctl's warnings go to stderr, beside the JSON on stdout
    echo "stand-in simctl: a warning on stderr" >&2
    [ "${FAKE_LIST_FAILS:-}" = 1 ] && { echo "CoreSimulatorService connection became invalid" >&2; exit 1; }
    cat "$FAKE_SIMS" ;;
  bootstatus) echo "Device booted" ;;
  install) [ -f "$3/Info.plist" ] || { echo "no app at $3" >&2; exit 1; } ;;
  terminate) ;;
  launch)
    echo "launch-env ${SIMCTL_CHILD_KELIVER_BUNDLE_SERVER-unset}" >> "$FAKE_LOG"
    out=""
    for a in "$@"; do case "$a" in --stdout=*) out="${a#--stdout=}" ;; esac; done
    [ -n "$out" ] || { echo "stand-in: no --stdout" >&2; exit 1; }
    n=$(( $(cat "$FAKE_LAUNCHES" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$FAKE_LAUNCHES"
    # A quiet launch leaves --stdout as it found it: an old host.log is the command's to remove.
    if [ "${FAKE_QUIET_LAUNCHES:-0}" -ge "$n" ]; then echo "launched: 4242"; exit 0; fi
    { echo "KeliverHost: verifying manifests with portal-ed25519 abcd1234…; updates live"
      # A debug Kotlin framework says it took the launch's server; a release one (FAKE_RELEASE_FRAMEWORK) ignores it.
      [ -n "${SIMCTL_CHILD_KELIVER_BUNDLE_SERVER:-}" ] && [ "${FAKE_RELEASE_FRAMEWORK:-}" != 1 ] \
        && echo "KeliverHost: bundle server from the launch environment (a debug build): $SIMCTL_CHILD_KELIVER_BUNDLE_SERVER"
      [ -n "${FAKE_OUTCOME:-}" ] && echo "KeliverHost: $FAKE_OUTCOME"; } > "$out"
    echo "launched: 4242" ;;
esac
exit 0
SH
# xcodebuild: logs its arguments and where it ran, and writes the .app a build would
# (a binary Info.plist, as Xcode writes it).
cat > "$FAKE/xcodebuild" <<'SH'
#!/usr/bin/env bash
echo "xcodebuild $*" >> "$FAKE_LOG"
echo "xcodebuild-cwd $(pwd -P)" >> "$FAKE_LOG"
[ "${FAKE_BUILD_FAILS:-}" = 1 ] && { echo "e: something did not compile"; echo "** BUILD FAILED **"; exit 65; }
dd=""
while [ $# -gt 0 ]; do case "$1" in -derivedDataPath) dd="$2"; shift 2 ;; *) shift ;; esac; done
[ -n "$dd" ] || { echo "stand-in: no -derivedDataPath"; exit 1; }
app="$dd/Build/Products/Debug-iphonesimulator/SelftestHost.app"; mkdir -p "$app"
python3 -c 'import plistlib, sys; plistlib.dump({"CFBundleIdentifier": sys.argv[2]}, open(sys.argv[1], "wb"), fmt=plistlib.FMT_BINARY)' \
  "$app/Info.plist" "${FAKE_BUNDLE_ID:-com.example.selftest.host}"
echo "** BUILD SUCCEEDED **"
SH
cat > "$FAKE/open" <<'SH'
#!/usr/bin/env bash
echo "open $*" >> "$FAKE_LOG"
SH
chmod +x "$FAKE/xcrun" "$FAKE/xcodebuild" "$FAKE/open"
export FAKE_LOG="$RUN/calls.log" FAKE_SIMS="$RUN/sims.json" FAKE_LAUNCHES="$RUN/launches"

# Simulators: an old runtime whose version sorts after 18.2 as text (9.3), an iPad listed
# before the iPhones on 18.2, a watch, and an iPhone whose runtime is gone.
OLD=11111111-1111-4111-8111-111111111111; IPAD=22222222-2222-4222-8222-222222222222
PHONE_A=33333333-3333-4333-8333-333333333333; PHONE_B=44444444-4444-4444-8444-444444444444
WATCH=55555555-5555-4555-8555-555555555555; GONE=66666666-6666-4666-8666-666666666666
sims() {  # the UDIDs given are booted
  python3 - "$FAKE_SIMS" "$@" <<PY
import json, sys
booted = set(sys.argv[2:])
def d(udid, name, available=True):
    e = {"udid": udid, "name": name, "state": "Booted" if udid in booted else "Shutdown", "isAvailable": available}
    if not available: e["availabilityError"] = "runtime profile not found"
    return e
rt = "com.apple.CoreSimulator.SimRuntime."
json.dump({"devices": {
  rt + "iOS-9-3": [d("$OLD", "iPhone 6s")],
  rt + "iOS-18-2": [d("$IPAD", "iPad Pro 13-inch (M4)"), d("$PHONE_A", "iPhone 16"), d("$PHONE_B", "iPhone 16 Pro")],
  rt + "watchOS-11-2": [d("$WATCH", "Apple Watch Series 10 (46mm)")],
  rt + "iOS-17-0": [d("$GONE", "iPhone 15", available=False)],
}}, open(sys.argv[1], "w"), indent=1)
PY
}

# A relay: /devstate, and /bundles/index.json from $RUN/index.json. It logs every request's
# method and path to $RUN/relay.log, and answers any POST (a publish) with 404.
PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
RELAY_LOG="$RUN/relay.log"
cat > "$RUN/relay.py" <<'PY'
import http.server, sys
root = sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def note(self):
        with open(root + "/relay.log", "a") as f:
            f.write("%s %s\n" % (self.command, self.path))
    def do_POST(self):
        self.note(); self.send_response(404); self.end_headers()
    def do_PUT(self): self.do_POST()
    def do_DELETE(self): self.do_POST()
    def do_GET(self):
        self.note()
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

# An app with a host-ios/ made for this relay: HostConfig.kt, and an Info.plist with the
# scaffolder's App Transport Security exception for localhost.
host_ios() {  # $1 = app dir, $2 = BUNDLE_SERVER, $3 = "ats" for the localhost exception
  local h="$1/host-ios"
  mkdir -p "$h/iosApp.xcodeproj" "$h/iosApp" "$h/src/iosMain/kotlin/com/example/selftest/host"
  printf 'project\n' > "$h/iosApp.xcodeproj/project.pbxproj"
  printf 'package com.example.selftest.host\n\ninternal const val PORTAL_PUBLIC_KEY_HEX: String = "%s"\ninternal const val BUNDLE_SERVER: String = "%s"\ninternal const val UPDATES: String = ""\n' \
    "$(printf 'ab%.0s' $(seq 1 32))" "$2" > "$h/src/iosMain/kotlin/com/example/selftest/host/HostConfig.kt"
  # A debug build's launch-time server (tools 0.3.10 on).
  printf 'val fromLaunch = NSProcessInfo.processInfo.environment["KELIVER_BUNDLE_SERVER"]\n' \
    > "$h/src/iosMain/kotlin/com/example/selftest/host/MainViewController.kt"
  python3 - "$h/iosApp/Info.plist" "${3:-}" <<'PY'
import plistlib, sys
p = {"CADisableMinimumFrameDurationOnPhone": True}
if sys.argv[2] == "ats":
    p["NSAppTransportSecurity"] = {"NSExceptionDomains": {"localhost": {"NSExceptionAllowsInsecureHTTPLoads": True}}}
plistlib.dump(p, open(sys.argv[1], "wb"))
PY
}
digest() {  # every file under $1 but build/, hashed: "the app's files are unchanged"
  python3 - "$1" <<'PY'
import hashlib, os, sys
root, lines = sys.argv[1], []
for d, dirs, files in os.walk(root):  # top-down, so build/ is pruned before it is entered
    dirs[:] = [x for x in dirs if not (d == root and x == "build")]
    for f in files:
        p = os.path.join(d, f)
        lines.append("%s %s" % (os.path.relpath(p, root), hashlib.sha256(open(p, "rb").read()).hexdigest()))
print("\n".join(sorted(lines)))
PY
}
APP="$RUN/app"; mkdir -p "$APP"
printf '{"port": %s}\n' "$PORT" > "$APP/keliver.portal.json"
host_ios "$APP" "http://localhost:$PORT" ats
BEFORE="$(digest "$APP")"

run() {  # $1 = label; then the command's arguments. Output in $RUN/$1.out, status in $RC.
  local label="$1"; shift
  : > "$FAKE_LOG"; : > "$RELAY_LOG"; rm -f "$FAKE_LAUNCHES"
  PATH="$FAKE:$PATH" KELIVER_RUN_WAIT_SECONDS="${WAIT:-6}" "$CMD" "$@" > "$RUN/$label.out" 2>&1; RC=$?
}
says() { grep -qF -- "$2" "$RUN/$1.out"; }
calls() { grep -c "$1" "$FAKE_LOG" || true; }
only_listed() { ! grep '^xcrun ' "$FAKE_LOG" | grep -qv '^xcrun simctl list -j devices$' && ! grep -q '^xcodebuild ' "$FAKE_LOG"; }

# --- refusals before anything is asked of a simulator ---------------------------------
run help --help
[ "$RC" = 0 ] && says help "this app's screens on an iOS simulator" && ok "--help prints what it does" || bad "--help: rc=$RC"

WAIT=two run badwait "$RUN"
[ "$RC" = 2 ] && says badwait 'KELIVER_RUN_WAIT_SECONDS must be a whole number of seconds, not "two"' && [ ! -s "$FAKE_LOG" ] \
  && ok "a KELIVER_RUN_WAIT_SECONDS that is not a number: refused (usage), naming it" || bad "a non-numeric wait: rc=$RC $(head -2 "$RUN/badwait.out")"

mkdir -p "$RUN/not-an-app"
run notapp "$RUN/not-an-app"
[ "$RC" != 0 ] && says notapp "has no keliver.portal.json" && ok "outside an app: refused, saying why" || bad "outside an app: rc=$RC $(head -2 "$RUN/notapp.out")"

sims "$PHONE_A"
run norelay "$APP"
[ "$RC" != 0 ] && says norelay "no portal answers on port $PORT" && says norelay "keliver-portal" && [ ! -s "$FAKE_LOG" ] \
  && ok "no portal running: refused, naming the port and keliver-portal; Xcode never asked" || bad "no portal: rc=$RC $(head -2 "$RUN/norelay.out")"

python3 "$RUN/relay.py" "$PORT" "$RUN" & RELAY_PID=$!
for _ in $(seq 1 50); do curl -sf -m 1 -o /dev/null "http://127.0.0.1:$PORT/devstate" && break; sleep 0.1; done
# The relay's log would show a publish: the "never publishes" row below can fail.
: > "$RELAY_LOG"; curl -s -m 5 -o /dev/null -X POST "http://127.0.0.1:$PORT/publish" || true
grep -qx "POST /publish" "$RELAY_LOG" && ok "the stand-in relay logs a POST /publish (so a publish would be seen)" || bad "the relay did not log a POST: $(cat "$RELAY_LOG")"

echo '{"format":1,"entries":[]}' > "$RUN/index.json"
run nobundle "$APP"
[ "$RC" != 0 ] && says nobundle "has published no bundle yet" && says nobundle "curl -X POST http://localhost:$PORT/publish" && [ ! -s "$FAKE_LOG" ] \
  && ok "nothing published: refused, saying how to publish; Xcode never asked" || bad "nothing published: rc=$RC $(head -2 "$RUN/nobundle.out")"

echo '{"format":1,"entries":[{"sequence":1,"manifest":"v1/manifest.zipline.json"},{"sequence":3,"manifest":"v3/manifest.zipline.json"}]}' > "$RUN/index.json"
# No Xcode: a PATH with what the command needs before it looks for Xcode, and no xcrun.
NOX="$RUN/no-xcode"; mkdir -p "$NOX"
for t in bash dirname readlink python3 curl sed head tr; do p="$(command -v "$t")" && ln -sf "$p" "$NOX/$t"; done
: > "$FAKE_LOG"
PATH="$NOX" "$CMD" "$APP" > "$RUN/noxcode.out" 2>&1; RC=$?
[ "$RC" != 0 ] && says noxcode "Xcode's xcrun and xcodebuild are needed" \
  && ok "no Xcode (as on Linux): refused, naming Xcode" || bad "no Xcode: rc=$RC $(tail -2 "$RUN/noxcode.out" | tr '\n' ' ')"

FAKE_LIST_FAILS=1 run listfails "$APP"
[ "$RC" != 0 ] && says listfails "xcrun simctl list devices failed" && says listfails "CoreSimulatorService connection became invalid" && only_listed \
  && says listfails "xcode-select -s /Applications/Xcode.app" \
  && ok "simctl cannot list the simulators: refused, with simctl's own error and the xcode-select hint" || bad "a failed list: rc=$RC $(tail -2 "$RUN/listfails.out" | tr '\n' ' ')"

python3 -c 'import json,sys; json.dump({"devices": {"com.apple.CoreSimulator.SimRuntime.watchOS-11-2": []}}, open(sys.argv[1], "w"))' "$FAKE_SIMS"
run nosim "$APP"
[ "$RC" != 0 ] && says nosim "no iOS simulator on this Mac" && only_listed \
  && ok "no iOS simulator: refused, saying how to add one" || bad "no simulator: rc=$RC $(tail -2 "$RUN/nosim.out" | tr '\n' ' ')"

sims "$PHONE_A" "$PHONE_B"
run twosims "$APP"
[ "$RC" != 0 ] && says twosims "several simulators are booted; choose one with --udid" && says twosims "$PHONE_A" && only_listed \
  && ok "two booted and no --udid: refused, naming both; nothing built or touched" || bad "two booted: rc=$RC $(tail -2 "$RUN/twosims.out" | tr '\n' ' ')"

# A phone's UDID has this shape (a made-up one); simctl never lists a phone.
run phone --udid 00000000-0000000000000001 "$APP"
[ "$RC" != 0 ] && says phone "no simulator 00000000-0000000000000001 on this Mac" && says phone "simulators only" && only_listed \
  && ok "a phone's UDID: refused (simulators only); nothing built or touched" || bad "a phone's UDID: rc=$RC $(tail -2 "$RUN/phone.out" | tr '\n' ' ')"

run watch --udid "$WATCH" "$APP"
[ "$RC" != 0 ] && says watch "is a watchOS simulator" && only_listed \
  && ok "a watch simulator: refused" || bad "a watch simulator: rc=$RC $(tail -2 "$RUN/watch.out" | tr '\n' ' ')"

run gone --udid "$GONE" "$APP"
[ "$RC" != 0 ] && says gone "is not available: runtime profile not found" && only_listed \
  && ok "a simulator whose runtime is gone: refused, with simctl's reason" || bad "an unavailable simulator: rc=$RC $(tail -2 "$RUN/gone.out" | tr '\n' ' ')"

# --- the route ---------------------------------------------------------------------
export FAKE_OUTCOME="codeLoadSuccess modules=4 sequence=3 source=network"
# Inherited from the shell: not passed on (the script decides where the host looks).
export SIMCTL_CHILD_KELIVER_BUNDLE_SERVER=http://elsewhere.example
run happy --udid "$PHONE_B" "$APP"
unset SIMCTL_CHILD_KELIVER_BUNDLE_SERVER
L="$FAKE_LOG"; OUT="$APP/build/keliver-run-ios"
[ "$RC" = 0 ] && says happy "loaded bundle sequence 3 from http://localhost:$PORT" \
  && ok "with --udid among two booted: it builds, installs, starts, and reports the bundle the host loaded" \
  || bad "the route: rc=$RC $(tail -4 "$RUN/happy.out" | tr '\n' ' ')"
says happy "has published bundle sequence 3" && ok "it names the newest published sequence (3)" || bad "no newest sequence in the output"
grep -qx "xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator -destination id=$PHONE_B -derivedDataPath $OUT/DerivedData CODE_SIGNING_ALLOWED=NO build" "$L" \
  && grep -qx "xcodebuild-cwd $APP" "$L" \
  && ok "a Debug build for that simulator, unsigned, into build/keliver-run-ios/ (from the app root)" || bad "xcodebuild was not asked for that build: $(grep xcodebuild "$L" | tr '\n' ' ')"
grep -qx "xcrun simctl install $PHONE_B $OUT/DerivedData/Build/Products/Debug-iphonesimulator/SelftestHost.app" "$L" \
  && ok "it installs the .app the build wrote" || bad "no install of the built app: $(grep install "$L")"
grep -qx "xcrun simctl terminate $PHONE_B com.example.selftest.host" "$L" \
  && grep -qx "xcrun simctl launch --stdout=$OUT/host.log --stderr=$OUT/host-stderr.log $PHONE_B com.example.selftest.host" "$L" \
  && ok "it restarts the host by the built app's bundle id, its stdout into build/keliver-run-ios/host.log" || bad "the host was not started by its bundle id: $(grep -E 'launch|terminate' "$L" | tr '\n' ' ')"
grep -qx "launch-env unset" "$L" && ok "built for this relay: no launch override, and an inherited SIMCTL_CHILD_KELIVER_BUNDLE_SERVER is not passed on" \
  || bad "the launch environment: $(grep launch-env "$L")"
grep -q INFOPLIST_FILE "$L" && bad "an Info.plist copy for a host that allows localhost" || ok "its Info.plist allows localhost: built as it is"
grep -q 'simctl bootstatus' "$L" && bad "it booted an already booted simulator" || ok "already booted: not booted again"
grep -qx "open -a Simulator --args -CurrentDeviceUDID $PHONE_B" "$L" && ok "it shows that simulator's window" || bad "no Simulator window: $(grep '^open' "$L")"
others="$(grep '^xcrun simctl' "$L" | grep -v "^xcrun simctl list -j devices$" | grep -v " $PHONE_B" || true)"
[ -z "$others" ] && ! grep -q "$PHONE_A" "$L" && ok "every simctl call names the chosen simulator: the other booted one is never touched" \
  || bad "simctl calls not on the chosen simulator: $others"
relay_asked="$(sort -u "$RELAY_LOG" | tr '\n' ' ')"
[ "$relay_asked" = "GET /bundles/index.json GET /devstate " ] && ! grep -qi 'publish' "$L" \
  && ok "it never publishes: of the relay it asks only GET /devstate and GET /bundles/index.json" \
  || bad "it asked the relay for more than reading: $relay_asked"
[ "$(digest "$APP")" = "$BEFORE" ] && ok "the app's files are unchanged (all it writes is under build/)" || bad "it changed the app's files"
says happy "takes the new bundle while it is open" && says happy "$OUT/host.log" \
  && ok "it says how updates reach the open app, and where the host's log is" || bad "no live-update or log line: $(tail -3 "$RUN/happy.out" | tr '\n' ' ')"

sims "$PHONE_B" "$WATCH"
run onesim "$APP"
[ "$RC" = 0 ] && grep -qx "xcrun simctl install $PHONE_B $OUT/DerivedData/Build/Products/Debug-iphonesimulator/SelftestHost.app" "$FAKE_LOG" \
  && ok "one booted iOS simulator (and a booted watch) and no --udid: that simulator" || bad "one booted: rc=$RC $(tail -3 "$RUN/onesim.out" | tr '\n' ' ')"

sims
run nonebooted "$APP"
first() { grep -n "$1" "$FAKE_LOG" | head -1 | cut -d: -f1; }
[ "$RC" = 0 ] && says nonebooted "simulator $PHONE_A (iPhone 16, iOS 18.2, Shutdown)" \
  && grep -qx "xcrun simctl bootstatus $PHONE_A -b" "$FAKE_LOG" \
  && [ "$(first '^xcodebuild ')" -lt "$(first 'simctl bootstatus')" ] && [ "$(first 'simctl bootstatus')" -lt "$(first 'simctl install')" ] \
  && ok "none booted: the first iPhone on the newest iOS runtime (18.2, not 9.3 or the iPad), booted after the build" \
  || bad "none booted: rc=$RC $(grep 'simulator' "$RUN/nonebooted.out" | head -1) $(grep -E 'bootstatus|install' "$FAKE_LOG" | tr '\n' ' ')"

sims "$PHONE_A"
export FAKE_OUTCOME="codeLoadSuccess modules=4 sequence=1 source=network"
run older "$APP"
[ "$RC" = 0 ] && says older "loaded bundle sequence 1" && says older "The portal's newest is 3" \
  && ok "a host that takes an older sequence than the newest: it says so" || bad "an older sequence: rc=$RC $(tail -3 "$RUN/older.out" | tr '\n' ' ')"

export FAKE_OUTCOME="codeLoadSuccess modules=4 sequence=3 source=network"
FAKE_QUIET_LAUNCHES=1 KELIVER_RUN_QUIET_SECONDS=3 WAIT=12 run quiet "$APP"
[ "$RC" = 0 ] && says quiet "printed nothing in 3 s; launching it once more" && [ "$(calls '^xcrun simctl launch')" = 2 ] \
  && ok "a first launch that prints nothing: launched once more, then loads" || bad "a quiet first launch: rc=$RC $(tail -3 "$RUN/quiet.out" | tr '\n' ' ')"

FAKE_QUIET_LAUNCHES=9 KELIVER_RUN_QUIET_SECONDS=3 run neverstarted "$APP"
[ "$RC" != 0 ] && says neverstarted "the host printed nothing within 6 s: did it start?" && [ "$(calls '^xcrun simctl launch')" = 2 ] \
  && ok "a host that never prints: one relaunch, then it fails, saying so" || bad "a host that never starts: rc=$RC $(tail -3 "$RUN/neverstarted.out" | tr '\n' ' ')"

# A host.log left by an earlier run that loaded from the network: a launch that prints nothing
# must not be read as a success from it.
mkdir -p "$OUT"; echo "KeliverHost: codeLoadSuccess modules=4 sequence=3 source=network" > "$OUT/host.log"
FAKE_QUIET_LAUNCHES=9 KELIVER_RUN_QUIET_SECONDS=3 run stalelog "$APP"
[ "$RC" != 0 ] && says stalelog "the host printed nothing within 6 s" && ! says stalelog "loaded bundle sequence" \
  && ok "an old host.log saying codeLoadSuccess, and a launch that prints nothing: it fails (the old log is removed first)" \
  || bad "a stale host.log: rc=$RC $(tail -3 "$RUN/stalelog.out" | tr '\n' ' ')"

# An app under a path with spaces.
SPACED="$RUN/with spaces/my app"; mkdir -p "$SPACED"
printf '{"port": %s}\n' "$PORT" > "$SPACED/keliver.portal.json"
host_ios "$SPACED" "http://localhost:$PORT" ats
run spaced "$SPACED"
[ "$RC" = 0 ] && says spaced "loaded bundle sequence 3" && grep -qx "xcodebuild-cwd $SPACED" "$FAKE_LOG" \
  && grep -qF -- "--stdout=$SPACED/build/keliver-run-ios/host.log" "$FAKE_LOG" \
  && ok "an app under a path with spaces: built, started and loaded" || bad "a path with spaces: rc=$RC $(tail -3 "$RUN/spaced.out" | tr '\n' ' ')"

export FAKE_OUTCOME="bundle lookup failed: Could not connect to the server."
run lookupfails "$APP"
[ "$RC" != 0 ] && says lookupfails "the host did not load a bundle: bundle lookup failed: Could not connect to the server." \
  && ok "the host's lookup fails: it fails, with the host's own line" || bad "a failed lookup: rc=$RC $(tail -3 "$RUN/lookupfails.out" | tr '\n' ' ')"

export FAKE_OUTCOME="bundle index: no compatible entry among 2 (channel stable, widget version 1, capabilities [host-sql])"
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

export FAKE_OUTCOME="codeLoadSuccess modules=4 sequence=3 source=network"
FAKE_BUILD_FAILS=1 run buildfails "$APP"
[ "$RC" != 0 ] && says buildfails "the build failed" && says buildfails "e: something did not compile" \
  && ! grep -qE 'simctl (install|launch|bootstatus)' "$FAKE_LOG" \
  && ok "a failed build: it stops before installing, showing the compiler's error" || bad "a failed build: rc=$RC $(tail -3 "$RUN/buildfails.out" | tr '\n' ' ')"

# --- a host-ios/ made for another server ----------------------------------------------
OTHER="$RUN/other"; mkdir -p "$OTHER"
printf '{"port": %s}\n' "$PORT" > "$OTHER/keliver.portal.json"
host_ios "$OTHER" "https://bundles.example.com"
OTHER_BEFORE="$(digest "$OTHER")"
run other "$OTHER"
[ "$RC" = 0 ] && grep -qx "launch-env http://localhost:$PORT" "$FAKE_LOG" \
  && says other "host-ios/ looks for bundles at https://bundles.example.com; this launch points it at http://localhost:$PORT" \
  && says other "opened from the home screen, the host looks at https://bundles.example.com again" \
  && ok "built for https://bundles.example.com: this launch points it at the relay (KELIVER_BUNDLE_SERVER), and says the home screen's launch does not" \
  || bad "another server: rc=$RC $(grep launch-env "$FAKE_LOG") $(tail -3 "$RUN/other.out" | tr '\n' ' ')"
grep -q " INFOPLIST_FILE=$OTHER/build/keliver-run-ios/Info.plist build$" "$FAKE_LOG" \
  && python3 - "$OTHER/build/keliver-run-ios/Info.plist" <<'PY' && ok "no ATS exception for localhost: built with a copy of Info.plist that has one, and keeps the rest" || bad "the Info.plist copy: $(grep '^xcodebuild ' "$FAKE_LOG")"
import plistlib, sys
p = plistlib.load(open(sys.argv[1], "rb"))
assert p["NSAppTransportSecurity"]["NSExceptionDomains"]["localhost"]["NSExceptionAllowsInsecureHTTPLoads"] is True
assert p["CADisableMinimumFrameDurationOnPhone"] is True
PY
[ "$(digest "$OTHER")" = "$OTHER_BEFORE" ] && ok "host-ios/ is unchanged (HostConfig.kt and Info.plist as they were)" || bad "it changed the other app's files"
says other "loaded bundle sequence 3" && grep -qF "bundle server from the launch environment (a debug build): http://localhost:$PORT" "$OTHER/build/keliver-run-ios/host.log" \
  && ok "success counted only with the host's own line that it took the launch's server" || bad "the override line: $(cat "$OTHER/build/keliver-run-ios/host.log")"

# The same host, but its Debug configuration builds a release Kotlin framework: the launch's
# server is ignored, and the host loads from its own BUNDLE_SERVER (source=network all the same).
FAKE_RELEASE_FRAMEWORK=1 run notoverridden "$OTHER"
[ "$RC" != 0 ] && grep -qx "launch-env http://localhost:$PORT" "$FAKE_LOG" \
  && says notoverridden "the host loaded a bundle, but not from http://localhost:$PORT" \
  && says notoverridden "The launch override was not taken" && says notoverridden "so it looked at https://bundles.example.com" \
  && ! says notoverridden "running —" \
  && ok "a host that ignores the launch's server (a release framework): not a success; it says the override was not taken" \
  || bad "an ignored override: rc=$RC $(tail -3 "$RUN/notoverridden.out" | tr '\n' ' ')"

# The same, from before tools 0.3.10: its debug build reads no server at launch.
OLDHOST="$RUN/oldhost"; mkdir -p "$OLDHOST"
printf '{"port": %s}\n' "$PORT" > "$OLDHOST/keliver.portal.json"
host_ios "$OLDHOST" "https://bundles.example.com"
rm "$OLDHOST/host-ios/src/iosMain/kotlin/com/example/selftest/host/MainViewController.kt"
run oldhost "$OLDHOST"
[ "$RC" != 0 ] && says oldhost "it is from before tools 0.3.10: it takes no server at launch" && says oldhost "BUNDLE_SERVER = \"http://localhost:$PORT\"" \
  && ! grep -q '^xcodebuild ' "$FAKE_LOG" \
  && ok "a host from before 0.3.10 made for another server: refused before the build, saying what to change" \
  || bad "a pre-0.3.10 host: rc=$RC $(tail -2 "$RUN/oldhost.out" | tr '\n' ' ')"

# A host-ios/ with an Xcode project and no Kotlin sources: refused by name, before the build.
NOSRC="$RUN/nosrc"; mkdir -p "$NOSRC/host-ios/iosApp.xcodeproj"
printf '{"port": %s}\n' "$PORT" > "$NOSRC/keliver.portal.json"
run nosrc "$NOSRC"
[ "$RC" != 0 ] && says nosrc "cannot find HostConfig.kt under $NOSRC/host-ios/src/iosMain/kotlin" && ! grep -q '^xcodebuild ' "$FAKE_LOG" \
  && ok "a host-ios/ with no HostConfig.kt: refused, naming where it looked; nothing built" \
  || bad "no HostConfig.kt: rc=$RC $(tail -2 "$RUN/nosrc.out" | tr '\n' ' ')"

# --- an app with no host-ios/: scaffolded for this relay --------------------------------
NEW="$RUN/new"; STORE="$RUN/store"
mkdir -p "$NEW/src/jsMain/kotlin/screens" "$STORE/keys" "$RUN/home"
printf '{"port": %s}\n' "$PORT" > "$NEW/keliver.portal.json"
printf "rootProject.name = 'demo'\n" > "$NEW/settings.gradle"
printf 'package com.example.demo.screens\n\nfun HomeScreen() {}\n' > "$NEW/src/jsMain/kotlin/screens/home.kt"
printf '#!/bin/sh\nexit 0\n' > "$NEW/gradlew"; chmod +x "$NEW/gradlew"
printf '%s\n' "$(printf 'ab%.0s' $(seq 1 32))" > "$STORE/keys/ed25519.pub"
RESOLVE="$(dirname "$CMD")/keliver-store-path.sh"
if [ "$(PORTAL_STORE="$STORE" JAVA_TOOL_OPTIONS="-Duser.home=$RUN/home" "$RESOLVE" "$NEW" 2>/dev/null)" = "$STORE" ]; then
  PORTAL_STORE="$STORE" JAVA_TOOL_OPTIONS="-Duser.home=$RUN/home" FAKE_BUNDLE_ID=com.example.demo.host run scaffold "$NEW"
  CFG="$(find "$NEW/host-ios/src/iosMain/kotlin" -name HostConfig.kt 2>/dev/null | head -1)"
  [ "$RC" = 0 ] && says scaffold "no host-ios/ yet; scaffolding it" && [ -n "$CFG" ] \
    && grep -q "const val BUNDLE_SERVER: String = \"http://localhost:$PORT\"" "$CFG" \
    && grep -qx "launch-env unset" "$FAKE_LOG" && ! grep -q INFOPLIST_FILE "$FAKE_LOG" \
    && says scaffold "loaded bundle sequence 3" \
    && ok "no host-ios/: scaffolded with --bundle-server http://localhost:$PORT (and its ATS exception), built and run as it is" \
    || bad "scaffolding: rc=$RC $(grep -E 'BUNDLE_SERVER' "${CFG:-/dev/null}") $(tail -4 "$RUN/scaffold.out" | tr '\n' ' ')"
else
  bad "the fixture store did not resolve inside the run directory ($RESOLVE): the scaffolding case was not run"
fi

echo
echo "keliver-run-ios: $pass passed, $fail failed   ($RUN)"
[ "$fail" -eq 0 ]
