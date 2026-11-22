#!/usr/bin/env bash
#
# keliver-nf4-ios-sim — NF4 (cold start) on an iOS simulator: the production iOS host scaffolded
# from THIS checkout, with Keliver.shared.start() in the App's init() and a declared first
# request (Keliver.shared.prefetch), against a RUNNING guest whose first screen makes that request.
#
#   scripts/keliver-nf4-ios-sim.sh <disposable-parent> [--runs N] [--port PORT] [--delay-ms MS]
#
# Self-contained, from this checkout (Keliver from Maven Central, as an adopter's host):
#   1. keliver-init a small app; its device/Main.kt is replaced by a screen that GETs items?page=1
#      through the host's HostHttp and shows the answer, marking (KeliverTiming, M0) how long the
#      answer took in the guest ("http-<ms>") and "content" once it is drawn;
#   2. signs it with a throwaway key (keliver-publish --new-signing-key) and publishes it into a
#      static site (keliver-publish --init, portal-relay's installDist from this checkout);
#   3. serves the site and an API from 127.0.0.1/::1 PORT (default 8495; never 8077, 8096 or
#      8478): /api/items answers after --delay-ms (default 300), and every request is logged;
#   4. scaffolds host-ios/ (keliver-new-ios-host.sh, this checkout's template) for that server and
#      API, UPDATES next-launch, and replaces iosApp/iOSApp.swift with the template's own init()
#      made switchable by a launch argument (-nf4Arm): a = no start() in init (the host starts at
#      the first screen), b = start() in init(), c = b + the prefetch of GET /items?page=1,
#      d = a + that prefetch (declared in init(), sent once the first screen started the host).
#      The Swift prints the process's exec time and init() on CACurrentMediaTime's clock;
#   5. builds the Debug configuration (it has the timing marks and the prefetch's debug log),
#      CREATES a simulator (newest iOS runtime, an iPhone), installs the host, launches it once to
#      fill Zipline's cache and stays until the bundle proved healthy (crash rollback counts a
#      process ended while a new bundle is on trial as a failure), then N rounds of process-cold
#      launches (terminate, 1 s, launch), the four arms in a rotating order, each until its
#      "content" mark or 45 s; then E5, a background launch (a content-available push: the
#      harness's copy adds UIBackgroundModes remote-notification) and the same process brought
#      to the foreground;
#   6. checks, from each launch's console and the server's log (E1-E4 below), writes the medians,
#      and deletes the simulator and stops the server, whatever happened.
#   E1  every launch drew the guest's answer (content), and every arm logged exactly one UI start
#       (what it was, and whether start() was held until it: the app state read in init)
#   E2  arms c and d: one GET items per launch at the server, sent before the bundle's code loaded, and the
#       guest's request answered in under the server's delay (less 20 ms): it took the prefetch,
#       in flight when it asked or answered already (its own request would take the whole delay)
#   E3  arms a and b: one GET items per launch, after the code loaded, answered in at least the delay
#   E4  no crash: every launch reached its content mark (an app state read too early in init() would
#       end the process before any mark)
#   E5  a push-launched process (state 2 in init()) holds start(): no lookup, no code load, no
#       request at the server; opened in the same process, the host starts at the scene's
#       foreground, sends one GET items and draws it. SKIP (not FAIL) when the simulator does not
#       launch the app for the push: iOS decides that, not the host.
# Results: <run>/evidence/ (results.md with the medians, each launch's console, the server log,
# screenshots). A simulator is not a phone: the Mac's CPU runs it, and this is a Debug build, so the
# numbers compare the arms here; they are not phone numbers. On an iPhone, the same arms run with a
# Benchmark build (docs/DEVICE_HOST.md, "NF4 on iOS").
#
# Needs: macOS with Xcode (xcodebuild, xcrun simctl), python3, JDK 17.
set -uo pipefail
NAME=keliver-nf4-ios-sim
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
PARENT="${1:?usage: $0 <disposable-parent> [--runs N] [--port PORT] [--delay-ms MS]}"; shift
RUNS=5; PORT=8495; DELAY=300
while [ $# -gt 0 ]; do
  case "$1" in
    --runs) RUNS="${2:?}"; shift 2 ;;
    --port) PORT="${2:?}"; shift 2 ;;
    --delay-ms) DELAY="${2:?}"; shift 2 ;;
    *) echo "$NAME: unknown argument $1" >&2; exit 2 ;;
  esac
done
for v in "$RUNS" "$PORT" "$DELAY"; do case "$v" in ''|*[!0-9]*) echo "$NAME: --runs, --port and --delay-ms take numbers" >&2; exit 2 ;; esac; done
case "$PORT" in 8077|8096|8478) echo "$NAME: port $PORT belongs to the owner's portal or the iOS CI relay" >&2; exit 2 ;; esac
RUN="$(keliver_make_run_dir "$PARENT" nf4-ios)" || exit $?
EV="$RUN/evidence"; mkdir -p "$EV/launches"
pass=0; fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); echo "PASS $1" >> "$EV/results.txt"; }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); echo "FAIL $1" >> "$EV/results.txt"; }
die() { bad "$1"; echo "$NAME: $pass passed, $fail failed"; exit 1; }
echo "=== $NAME: $RUN"

UDID=""; SPID=""
cleanup() {
  [ -n "$SPID" ] && kill "$SPID" 2>/dev/null
  if [ -n "$UDID" ]; then
    xcrun simctl shutdown "$UDID" >/dev/null 2>&1
    xcrun simctl delete "$UDID" >/dev/null 2>&1 && echo "simulator $UDID deleted"
  fi
}
trap cleanup EXIT

STORE="$RUN/store"; mkdir -p "$STORE/keys" "$RUN/home"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$RUN/home" PORTAL_STORE="$STORE"
unset KELIVER_USE_MAVEN_LOCAL
export KELIVER_RELAY_HOME="$ROOT/portal-relay/build/install/portal-relay"

# --- 1. the app and its guest ------------------------------------------------------------------
( cd "$ROOT" && ./gradlew --console=plain -q :portal-relay:installDist ) > "$EV/tools.log" 2>&1 \
  || die "portal-relay's installDist (keliver-publish's JVM) failed: $(tail -5 "$EV/tools.log" | tr '\n' ' ')"
APP="$RUN/nfprobe"
( cd "$RUN" && "$ROOT/scripts/keliver-init" NfProbe nfprobe ) > "$EV/init.log" 2>&1 || die "keliver-init failed"
keliver_require_isolated_store "$RUN" "$APP" > /dev/null || die "the isolation guard refused"
( cd "$APP" && "$ROOT/scripts/keliver-new-device-target.sh" ) > "$EV/device-target.log" 2>&1 || die "keliver-new-device-target.sh failed"
PKG="$(sed -n 's/^package \(.*\)\.device$/\1/p' "$APP/src/jsMain/kotlin/device/Main.kt")"
KV="$(sed -n "s/.*'dev.keliver:keliver-treehouse-guest:\([^']*\)'.*/\1/p" "$APP/build.gradle" | head -1)"
[ -n "$PKG" ] && [ -n "$KV" ] || die "could not read the app's package or Keliver version"
# HostHttp for the guest.
sed -i '' "s|^\(.*\)api 'dev.keliver:keliver-treehouse-guest:$KV'|&\\
\1implementation 'dev.keliver:keliver-http:$KV'|" "$APP/build.gradle"
grep -q "keliver-http:$KV" "$APP/build.gradle" || die "could not add keliver-http to the app"
cat > "$APP/src/jsMain/kotlin/device/KeliverTiming.kt" <<EOF
package $PKG.device

import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService

/** The host's timing service (M0), declared by shape; only Debug and Benchmark hosts bind it. */
interface KeliverTrace : ZiplineService {
  fun mark(name: String)
}

object KeliverTiming {
  private var trace: KeliverTrace? = null
  private var unavailable = false

  fun mark(name: String) {
    if (unavailable) return
    try {
      val t = trace ?: Zipline.get().take<KeliverTrace>("KeliverTrace").also { trace = it }
      t.mark(name)
    } catch (e: Throwable) {
      unavailable = true
      trace = null
    }
  }
}
EOF
cat > "$APP/src/jsMain/kotlin/device/Main.kt" <<EOF
package $PKG.device

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService
import dev.keliver.hostkit.KeliverScreenHost
import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledText
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
import kotlin.coroutines.cancellation.CancellationException

interface PortalPresenter : AppService, ZiplineService {
  fun launch(): ZiplineTreehouseUi
  fun launchRoute(route: String, screen: KeliverScreenHost): ZiplineTreehouseUi
}

private val hostHttp: HostHttpProvider by lazy { Zipline.get().take<HostHttpProvider>("HostHttp") }

/** NF4 probe: the first screen's data is one GET, the request an app would declare as its prefetch. */
private class AppUi(private val screen: KeliverScreenHost? = null) : TreehouseUi {
  @Composable
  override fun Show() {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
      val asked = kotlin.js.Date.now()
      text = try {
        hostHttp.execute(HttpRequest(method = "GET", path = "items", query = mapOf("page" to "1"))).body
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        "Failed: " + e.message.orEmpty().lineSequence().first()
      }
      KeliverTiming.mark("http-" + (kotlin.js.Date.now() - asked).toLong())
    }
    Column {
      StyledText(text = "NF4 probe", fontSize = 22)
      StyledText(text = text.ifEmpty { "Loading items" }, fontSize = 18)
    }
    if (text.isNotEmpty()) LaunchedEffect(Unit) { KeliverTiming.mark("content") }
  }

  override fun close() {
    screen?.close()
  }
}

private class AppPresenter(json: kotlinx.serialization.json.Json) : PortalPresenter {
  override val appLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
    json = json,
    widgetVersion = 1U,
  )
  override fun launch(): ZiplineTreehouseUi = AppUi().asZiplineTreehouseUi(appLifecycle)
  override fun launchRoute(route: String, screen: KeliverScreenHost): ZiplineTreehouseUi =
    AppUi(screen).asZiplineTreehouseUi(appLifecycle)
}

fun main() {
  val zipline = Zipline.get()
  zipline.bind<PortalPresenter>("PortalPresenter", AppPresenter(zipline.json))
}
EOF
ok "the app: keliver-init and keliver-new-device-target.sh from this checkout; a first screen that GETs items?page=1 through HostHttp"

# --- 2. signed, published into a static site ---------------------------------------------------
"$ROOT/scripts/keliver-publish" --new-signing-key "$STORE/keys" > "$EV/key.log" 2>&1 || die "keliver-publish --new-signing-key failed"
( cd "$APP" && "$ROOT/scripts/keliver-new-publish-target.sh" ) > "$EV/publish-target.log" 2>&1 || die "keliver-new-publish-target.sh failed"
SITE="$RUN/site"; mkdir -p "$SITE"; mkdir -p "$RUN/no-tools-bin"
( cd "$APP" && KELIVER_SIGNING_KEY_FILE="$STORE/keys/ed25519.priv" KELIVER_TOOLS_BIN="$RUN/no-tools-bin" \
    "$ROOT/scripts/keliver-publish" . --out "$SITE" --public-key-file "$STORE/keys/ed25519.pub" --init ) > "$EV/publish.log" 2>&1 \
  && grep -q "published v1" "$EV/publish.log" && ok "keliver-publish signed and wrote v1 into the static site" \
  || die "keliver-publish failed: $(grep -E 'error|e: |FAIL|refus' "$EV/publish.log" | head -5 | tr '\n' ' ')"

# --- 3. the server: the site and the API ------------------------------------------------------
cat > "$RUN/server.py" <<'PY'
import http.server, os, socket, sys, threading, time, urllib.parse
site, port, delay, log = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]) / 1000.0, sys.argv[4]
lock = threading.Lock(); count = [0]
class H(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=site, **k)
    def log_message(self, *a): pass
    def do_GET(self):
        at = time.time()
        u = urllib.parse.urlsplit(self.path)
        if u.path.startswith('/api/'):
            with lock:
                count[0] += 1; n = count[0]
                open(log, 'a').write('%d %s %s #%d\n' % (int(at * 1000), 'GET', self.path, n))
            time.sleep(delay)
            body = ('items page 1, answer #%d' % n).encode() if u.path == '/api/items' else b'no such thing'
            self.send_response(200 if u.path == '/api/items' else 404)
            self.send_header('Content-Type', 'text/plain'); self.send_header('Content-Length', str(len(body)))
            self.end_headers(); self.wfile.write(body); return
        with lock: open(log, 'a').write('%d GET %s\n' % (int(at * 1000), self.path))
        super().do_GET()
class S4(http.server.ThreadingHTTPServer): address_family = socket.AF_INET
class S6(http.server.ThreadingHTTPServer):
    address_family = socket.AF_INET6
    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1); super().server_bind()
servers = [S4(('127.0.0.1', port), H), S6(('::1', port), H)]
for s in servers[1:]: threading.Thread(target=s.serve_forever, daemon=True).start()
servers[0].serve_forever()
PY
SLOG="$EV/server.log"; : > "$SLOG"
python3 -I "$RUN/server.py" "$SITE" "$PORT" "$DELAY" "$SLOG" > "$EV/server.out" 2>&1 &
SPID=$!
for _ in $(seq 1 20); do curl -sf -m 2 -o /dev/null "http://localhost:$PORT/bundles/index.json" && break; sleep 0.5; done
curl -sf -m 5 "http://localhost:$PORT/api/items?page=1" | grep -q "answer #1" \
  && ok "the server answers on localhost:$PORT (the site, and the API after $DELAY ms)" || die "the server did not answer"

# --- 4. the host ---------------------------------------------------------------------------------
BID=dev.keliver.nf4probe
( cd "$APP" && "$ROOT/scripts/keliver-new-ios-host.sh" --bundle-server "http://localhost:$PORT" \
    --api-base-url "http://localhost:$PORT/api" --public-key-file "$STORE/keys/ed25519.pub" --bundle-id "$BID" ) \
  > "$EV/host-scaffold.log" 2>&1 && ok "keliver-new-ios-host.sh (this checkout's template) scaffolded host-ios/" \
  || die "the iOS host was not scaffolded: $(tail -5 "$EV/host-scaffold.log" | tr '\n' ' ')"
CFG="$(find "$APP/host-ios/src/iosMain/kotlin" -name HostConfig.kt | head -1)"
sed -i '' 's|UPDATES: String = ""|UPDATES: String = "next-launch"|' "$CFG"
SWIFTAPP="$APP/host-ios/iosApp/iOSApp.swift"
cp "$SWIFTAPP" "$EV/iOSApp.swift.scaffolded"
cat > "$SWIFTAPP" <<'SWIFT'
import Darwin
import QuartzCore
import SwiftUI
import UIKit
import KeliverHost

/// NF4 harness (scripts/keliver-nf4-ios-sim.sh): the template's App, its start chosen per launch
/// (-nf4Arm): a = no start() in init (the host starts at the first screen); b = start() in init();
/// c = b and the first screen's GET declared as a prefetch; d = a and that prefetch.
@main
struct iOSApp: App {
    init() {
        let initNanos = Int64(CACurrentMediaTime() * 1e9)
        let wall = Int64(Date().timeIntervalSince1970 * 1000)
        // A launch the system made (no -nf4Arm: a background push) has no console: its lines go to
        // Library/Caches/nf4-console.txt, which the harness reads.
        if !CommandLine.arguments.contains("-nf4Arm") { consoleToFile() }
        // UIApplication's state as init() sees it (0 active, 1 inactive, 2 background; -1: there
        // is no shared application yet).
        // Whether the shared application exists yet (KVC on the class: nil-safe, unlike .shared).
        let hasApp = (UIApplication.self as AnyObject).value(forKey: "sharedApplication") != nil
        let state = hasApp ? UIApplication.shared.applicationState.rawValue : -1
        let arm = UserDefaults.standard.string(forKey: "nf4Arm") ?? "c"
        if arm == "c" || arm == "d" { Keliver.shared.prefetch = [KeliverPrefetch(path: "/items", query: ["page": "1"])] }
        if arm == "b" || arm == "c" { Keliver.shared.start() }
        let done = Int64(CACurrentMediaTime() * 1e9)
        print("nf4: arm=\(arm) state=\(state) exec=\(execNanos(initNanos)) init=\(initNanos) initDone=\(done) wall=\(wall)")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

/// stdout (the host's lines and print's) appended to Library/Caches/nf4-console.txt, unbuffered.
private func consoleToFile() {
    let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
    let fd = open(dir.appendingPathComponent("nf4-console.txt").path, O_WRONLY | O_CREAT | O_APPEND, 0o644)
    guard fd >= 0 else { return }
    dup2(fd, STDOUT_FILENO)
    close(fd)
    setvbuf(stdout, nil, _IONBF, 0)
}

/// When this process started, on CACurrentMediaTime's clock (sysctl's start time, in µs).
private func execNanos(_ nowNanos: Int64) -> Int64 {
    var info = kinfo_proc()
    var size = MemoryLayout<kinfo_proc>.stride
    var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
    guard sysctl(&mib, 4, &info, &size, nil, 0) == 0 else { return -1 }
    let tv = info.kp_proc.p_un.__p_starttime
    let started = Double(tv.tv_sec) + Double(tv.tv_usec) / 1e6
    let age = Date().timeIntervalSince1970 - started
    return nowNanos - Int64(age * 1e9)
}
SWIFT
# E5: a content-available push may launch the app in the background (the harness's copy only).
for pl in "$APP/host-ios/iosApp/Info.plist" "$APP/host-ios/iosApp/Info-Debug.plist"; do
  [ -f "$pl" ] || continue
  /usr/libexec/PlistBuddy -c 'Add :UIBackgroundModes array' -c 'Add :UIBackgroundModes:0 string remote-notification' "$pl" > /dev/null 2>&1 \
    || die "could not add UIBackgroundModes to $pl"
done
( cd "$APP" && xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -configuration Debug \
    -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath "$RUN/dd" \
    CODE_SIGNING_ALLOWED=NO build ) > "$EV/xcodebuild.log" 2>&1 \
  && ok "xcodebuild built the Debug host (Keliver $KV from Maven Central)" \
  || die "xcodebuild failed: $(grep -E '^e: |error:' "$EV/xcodebuild.log" | head -5 | tr '\n' ' ')"
APPB="$(find "$RUN/dd/Build/Products/Debug-iphonesimulator" -maxdepth 1 -name '*.app' | head -1)"

# --- 5. the simulator and the launches -------------------------------------------------------------
SIM="$(python3 -I - <<'PY'
import json, subprocess
j = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', '-j', 'runtimes']))
rts = [r for r in j['runtimes'] if r.get('isAvailable') and r.get('platform', r['name']).startswith('iOS')]
rt = sorted(rts, key=lambda r: [int(x) for x in r['version'].split('.')])[-1]
devs = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', '-j', 'devices', 'available']))
shipped = [d['deviceTypeIdentifier'] for d in devs['devices'].get(rt['identifier'], [])
           if d['name'].startswith('iPhone') and 'deviceTypeIdentifier' in d]
phones = [d['identifier'] for d in rt.get('supportedDeviceTypes', []) if d['name'].startswith('iPhone')]
print(rt['identifier'], (shipped or phones)[0])
PY
)"
# A run that was killed (not stopped) leaves its simulator: this harness's name, nobody else's.
for old in $(xcrun simctl list devices | sed -n 's/^ *keliver-nf4-local (\([0-9A-F-]*\)).*/\1/p'); do
  xcrun simctl shutdown "$old" > /dev/null 2>&1; xcrun simctl delete "$old" > /dev/null 2>&1 && echo "deleted the simulator $old, left by an earlier run"
done
UDID="$(xcrun simctl create keliver-nf4-local ${SIM#* } ${SIM% *})" || die "could not create a simulator ($SIM)"
echo "simulator: $UDID ($SIM)" | tee "$EV/simulator.txt"
xcrun simctl boot "$UDID" && xcrun simctl bootstatus "$UDID" -b > /dev/null 2>&1
xcrun simctl install "$UDID" "$APPB" || die "install failed"

# launch <label> <arm> [<line to wait for>]: a process-cold launch, until its content mark (or
# the given line), at most 45 s.
launch() {
  local label="$1" arm="$2" want="${3:-KeliverTiming: content [0-9]}" pid i
  xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
  sleep 1
  echo "=== $label" >> "$SLOG"
  xcrun simctl launch --console-pty "$UDID" "$BID" -nf4Arm "$arm" > "$EV/launches/$label.console.txt" 2>&1 &
  pid=$!
  for i in $(seq 1 90); do
    grep -q "$want" "$EV/launches/$label.console.txt" && break
    sleep 0.5
  done
  sleep 1
  kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
}
# The warmup fills Zipline's cache and stays until the bundle proved healthy (crash rollback, WS6):
# a process ended while its new bundle is on trial counts as a failure, and two set it aside.
launch warmup c "proved healthy on this install"
grep -q "proved healthy on this install" "$EV/launches/warmup.console.txt" \
  && ok "the warmup launch drew the guest's answer and the bundle proved healthy (later terminations are not crashes)" \
  || die "the warmup did not prove the bundle healthy: $(grep -E 'CRASH|refus|fail' "$EV/launches/warmup.console.txt" | head -3 | tr '\n' ' ')"
xcrun simctl io "$UDID" screenshot "$EV/warmup.png" > /dev/null 2>&1
ARMS=(a b c d)
for r in $(seq 1 "$RUNS"); do
  for k in 0 1 2 3; do
    arm="${ARMS[$(( (k + r) % 4 ))]}"
    launch "$arm-$r" "$arm"
    [ "$r" = 1 ] && xcrun simctl io "$UDID" screenshot "$EV/arm-$arm.png" > /dev/null 2>&1
  done
done

# --- 5b. E5: a background launch (a content-available push), then the user opens the app ----------
# The harness's App: no -nf4Arm means arm c (start() and the prefetch in init()), with its lines in
# Library/Caches/nf4-console.txt. In the background the host must look up nothing, load no code
# and send nothing; brought to the foreground in the same process, it starts then and draws.
xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
sleep 1
DATA="$(xcrun simctl get_app_container "$UDID" "$BID" data 2>/dev/null)"
BGC="$DATA/Library/Caches/nf4-console.txt"
rm -f "$BGC"
printf '{"aps":{"content-available":1},"nf4":"background launch"}\n' > "$RUN/push.json"
echo "=== background" >> "$SLOG"
if [ -z "$DATA" ] || ! xcrun simctl push "$UDID" "$BID" "$RUN/push.json" > "$EV/push.log" 2>&1; then
  echo "SKIP E5: simctl push failed: $(cat "$EV/push.log" 2>/dev/null | head -2 | tr '\n' ' ')" | tee -a "$EV/results.txt"
else
  for _ in $(seq 1 30); do grep -q '^nf4: ' "$BGC" 2>/dev/null && break; sleep 0.5; done
  if ! grep -q '^nf4: ' "$BGC" 2>/dev/null; then
    # Not a failure of the host: the simulator decides whether a push launches the app.
    echo "SKIP E5: the simulator did not launch the app for a content-available push (15 s); the background launch is unverified here" | tee -a "$EV/results.txt"
  else
    sleep 5
    cp "$BGC" "$EV/launches/background.console.txt"
    bgreq="$(sed -n '/^=== background$/,$p' "$SLOG" | grep -c ' GET ')"
    grep -q '^nf4: arm=c ' "$BGC" \
      && grep -q 'start: no scene in the foreground yet' "$BGC" \
      && ! grep -q 'KeliverTiming: start [0-9]' "$BGC" && ! grep -q 'KeliverTiming: code [0-9]' "$BGC" \
      && [ "$bgreq" = 0 ] \
      && ok "E5: a push launched the app in the background ($(sed -n 's/^nf4: arm=c \(state=-*[0-9]*\).*/\1/p' "$BGC") in init()): start() in init() was held; no lookup, no code load, $bgreq requests at the server" \
      || bad "E5: the background launch was not held ($bgreq requests at the server; $(grep -E '^nf4:|UI start|background|KeliverTiming: (start|code) ' "$BGC" | head -4 | tr '\n' ' '))"
    # The user opens the app: the same process comes to the foreground.
    echo "=== background-foreground" >> "$SLOG"
    xcrun simctl launch "$UDID" "$BID" > /dev/null 2>&1
    for _ in $(seq 1 90); do grep -q 'KeliverTiming: content [0-9]' "$BGC" && break; sleep 0.5; done
    cp "$BGC" "$EV/launches/background.console.txt"
    pids="$(grep -c '^nf4: ' "$BGC")"
    fgreq="$(sed -n '/^=== background-foreground$/,$p' "$SLOG" | grep -c 'GET /api/items')"
    http="$(sed -n 's/.*KeliverTiming: http-\([0-9]*\) .*/\1/p' "$BGC" | head -1)"
    [ "$pids" = 1 ] && grep -qE 'UI start: UI(Scene|Application)' "$BGC" && grep -q 'KeliverTiming: content [0-9]' "$BGC" && [ "$fgreq" = 1 ] \
      && ok "E5: brought to the foreground in the same process, the host started at $(sed -n 's/.*UI start: //p' "$BGC" | head -1), sent its one GET items and drew it (the guest's request answered in ${http:-?} ms)" \
      || bad "E5: after the foreground: $pids processes, $fgreq GET items, $(grep -E 'UI start|KeliverTiming: (start|content) ' "$BGC" | head -3 | tr '\n' ' ')"
    xcrun simctl io "$UDID" screenshot "$EV/background-foreground.png" > /dev/null 2>&1
  fi
fi

# --- 6. the checks and the medians ---------------------------------------------------------------
python3 -I "$ROOT/scripts/keliver-nf4-ios-sim-report.py" "$EV" "$DELAY" "$RUNS" > "$EV/report.out" 2>&1; rc=$?
cat "$EV/report.out"
while IFS= read -r line; do
  case "$line" in
    "PASS "*) ok "${line#PASS }" ;;
    "FAIL "*) bad "${line#FAIL }" ;;
  esac
done < <(grep -E '^(PASS|FAIL) ' "$EV/report.out")
[ "$rc" = 0 ] || bad "the report failed (rc=$rc)"
{ echo; echo '## The background launch (E5)'; echo; grep -E '^(PASS|FAIL|SKIP) E5' "$EV/results.txt" | sed 's/^/- /'; } >> "$EV/results.md"
echo "$NAME: $pass passed, $fail failed (evidence: $EV)"
[ "$fail" = 0 ]
