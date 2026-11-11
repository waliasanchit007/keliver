#!/usr/bin/env bash
#
# keliver-run-ios — this app's screens on an iOS simulator, in one command.
#
#   keliver-run-ios.sh [--udid UDID] [app-dir]      (app-dir: default the current directory)
#
# Run it on a Mac with Xcode, with the app's portal running (keliver-portal) and a
# bundle published (⬆ Publish in the editor, or curl -X POST http://localhost:<port>/publish). It:
#   1. finds the relay on keliver.portal.json's "port" (default 8077) and checks
#      that it has published a bundle;
#   2. picks the simulator: --udid, or the only booted one, or else an iPhone on the
#      newest iOS runtime, which it boots (xcrun simctl list devices lists them).
#      Simulators only: a phone is never chosen;
#   3. scaffolds host-ios/ (keliver-new-ios-host.sh) when the app has none;
#   4. builds a DEBUG host for that simulator (xcodebuild, into build/keliver-run-ios/)
#      that looks for bundles at http://localhost:<port>: a simulator reaches this
#      Mac's relay directly. A host-ios/ made for another server is pointed here
#      for this launch (a debug build's KELIVER_BUNDLE_SERVER) and built with a copy
#      of its Info.plist that allows http:// to localhost; host-ios/ is not changed;
#   5. installs and starts the host, and waits for it to load your bundle from the
#      relay (its log's codeLoadSuccess, source=network), or to say why not.
# A debug host from tools 0.3.14 on then updates live: edit, ⬆ Publish, and the open
# app takes the new bundle. Run this again only after changing the host itself.
#
# Needs: Xcode (xcodebuild, xcrun simctl), python3, curl. It never publishes, and it
# touches no simulator but the one chosen.
set -euo pipefail
SELF="${BASH_SOURCE[0]}"
while [ -L "$SELF" ]; do
  link="$(readlink "$SELF")"; case "$link" in /*) SELF="$link" ;; *) SELF="$(dirname "$SELF")/$link" ;; esac
done
HERE="$(cd "$(dirname "$SELF")" && pwd -P)"
NAME=keliver-run-ios
fail() { echo "$NAME: $*" >&2; exit 1; }
# Where the host looks is this script's choice alone, not an inherited setting.
unset SIMCTL_CHILD_KELIVER_BUNDLE_SERVER

UDID=""; APP_DIR="."; WAIT="${KELIVER_RUN_WAIT_SECONDS:-120}"; QUIET="${KELIVER_RUN_QUIET_SECONDS:-30}"
while [ $# -gt 0 ]; do
  case "$1" in
    --udid) [ $# -ge 2 ] || { echo "$NAME: --udid needs a value" >&2; exit 2; }; UDID="$2"; shift 2 ;;
    -h|--help) sed -n '2,26p' "$SELF" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "$NAME: unknown option $1 (see --help)" >&2; exit 2 ;;
    *) APP_DIR="$1"; shift ;;
  esac
done
[ -d "$APP_DIR" ] || fail "no such directory: $APP_DIR"
APP_DIR="$(cd "$APP_DIR" && pwd -P)"
[ -f "$APP_DIR/keliver.portal.json" ] || fail "$APP_DIR has no keliver.portal.json: run this in a Keliver app (keliver-init makes one)."
command -v python3 >/dev/null || fail "python3 is needed."
command -v curl >/dev/null || fail "curl is needed."

# 1. The relay and a published bundle.
PORT="$(python3 -I - "$APP_DIR/keliver.portal.json" <<'PY'
import json, sys
try:
    config = json.load(open(sys.argv[1]))
except (OSError, ValueError) as e:
    sys.exit(f"keliver-run-ios: cannot read {sys.argv[1]} as JSON: {e}")
port = config.get("port", 8077) if isinstance(config, dict) else None
if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
    sys.exit(f"keliver-run-ios: \"port\" in keliver.portal.json must be a port number, not {json.dumps(port)}")
print(port)
PY
)" || exit 1
RELAY="http://127.0.0.1:$PORT"
curl -sf -m 5 -o /dev/null "$RELAY/devstate" \
  || fail "no portal answers on port $PORT. Start it first: $HERE/keliver-portal $APP_DIR"
INDEX="$(curl -sf -m 20 "$RELAY/bundles/index.json" 2>/dev/null || true)"
LATEST="$(printf '%s' "$INDEX" | python3 -I -c '
import json, sys
try:
    entries = json.load(sys.stdin).get("entries") or []
    print(max(int(e["sequence"]) for e in entries))
except Exception:
    pass
')"
[ -n "$LATEST" ] || fail "the portal on port $PORT has published no bundle yet. Publish one first: ⬆ Publish in the editor, or curl -X POST http://localhost:$PORT/publish"
echo "$NAME: the portal on port $PORT has published bundle sequence $LATEST"

# 2. The simulator. simctl lists simulators only, so a phone's UDID is never found here.
command -v xcrun >/dev/null && command -v xcodebuild >/dev/null \
  || fail "Xcode's xcrun and xcodebuild are needed: this runs on a Mac with Xcode (xcode-select -p names the one in use)."
# Its warnings (on stderr) stay out of the JSON read below.
SIMS="$(xcrun simctl list -j devices)" || fail "xcrun simctl list devices failed (above)."
# One line, "<udid>|<state>|<name>|<runtime>", or the reason none is chosen.
SIM="$(printf '%s' "$SIMS" | python3 -I -c '
import json, re, sys
want = sys.argv[1]
try:
    devices = json.load(sys.stdin)["devices"]
except Exception as e:
    sys.exit("cannot read xcrun simctl list -j devices: %s" % e)
def runtime(key):  # com.apple.CoreSimulator.SimRuntime.iOS-18-2 -> ("iOS", (18, 2))
    m = re.search(r"SimRuntime\.([A-Za-z]+)-([0-9-]+)$", key)
    return (m.group(1), tuple(int(x) for x in m.group(2).split("-") if x)) if m else (None, ())
sims = []
for key, devs in devices.items():
    platform, version = runtime(key)
    for d in devs:
        sims.append(dict(d, platform=platform, version=version, runtime="%s %s" % (platform, ".".join(map(str, version)))))
def line(d):
    return "%s|%s|%s|%s" % (d["udid"], d.get("state", "?"), d.get("name", "?"), d["runtime"])
if want:
    hit = [d for d in sims if d.get("udid", "").lower() == want.lower()]
    if not hit:
        sys.exit("no simulator %s on this Mac (xcrun simctl list devices). keliver-run-ios runs on simulators only; an iPhone build needs your team and signing (host-ios/Configuration/Config.xcconfig)." % want)
    d = hit[0]
    if d["platform"] != "iOS":
        sys.exit("%s is a %s simulator (%s), not an iOS one." % (want, d["platform"], d.get("name")))
    if not d.get("isAvailable", True):
        sys.exit("simulator %s (%s, %s) is not available: %s" % (want, d.get("name"), d["runtime"], d.get("availabilityError", "its runtime is missing")))
    print(line(d)); sys.exit(0)
ios = [d for d in sims if d["platform"] == "iOS" and d.get("isAvailable", True)]
booted = [d for d in ios if d.get("state") == "Booted"]
if len(booted) == 1:
    print(line(booted[0])); sys.exit(0)
if len(booted) > 1:
    sys.exit("several simulators are booted; choose one with --udid: " + ", ".join("%s (%s)" % (d["udid"], d.get("name")) for d in booted))
if not ios:
    sys.exit("no iOS simulator on this Mac: add one in Xcode (Window > Devices and Simulators), or with xcrun simctl create, and run this again.")
newest = max(d["version"] for d in ios)
phones = [d for d in ios if d["version"] == newest and d.get("name", "").startswith("iPhone")]
print(line((phones or [d for d in ios if d["version"] == newest])[0]))
' "$UDID")" || exit 1
[ -n "$SIM" ] || fail "no simulator was chosen."
UDID="${SIM%%|*}"; rest="${SIM#*|}"; STATE="${rest%%|*}"; rest="${rest#*|}"; SIM_NAME="${rest%%|*}"; SIM_RUNTIME="${rest#*|}"
echo "$NAME: simulator $UDID ($SIM_NAME, $SIM_RUNTIME, $STATE)"

# 3. The host.
SERVER="http://localhost:$PORT"
HOST="$APP_DIR/host-ios"
if [ ! -d "$HOST" ]; then
  echo "$NAME: no host-ios/ yet; scaffolding it (keliver-new-ios-host.sh --bundle-server $SERVER)"
  ( cd "$APP_DIR" && "$HERE/keliver-new-ios-host.sh" --bundle-server "$SERVER" ) || fail "scaffolding host-ios/ failed (above)."
fi
[ -d "$HOST/iosApp.xcodeproj" ] || fail "$HOST has no iosApp.xcodeproj: is it a host from keliver-new-ios-host.sh?"
CONFIG="$(find "$HOST/src/iosMain/kotlin" -name HostConfig.kt 2>/dev/null | head -1 || true)"
[ -n "$CONFIG" ] || fail "cannot find HostConfig.kt under $HOST/src/iosMain/kotlin."
config_value() { sed -n "s/.*const val $1: String = \"\([^\"]*\)\".*/\1/p" "$CONFIG" | head -1; }
BUILT_FOR="$(config_value BUNDLE_SERVER)"
UPDATES_LINE="$(grep -c 'const val UPDATES: String' "$CONFIG" || true)"
UPDATES="$(config_value UPDATES)"
OUT="$APP_DIR/build/$NAME"; mkdir -p "$OUT"
# Built for another server: this launch's KELIVER_BUNDLE_SERVER points the debug build here
# (HostConfig.kt is unchanged), and App Transport Security must let it reach http://localhost.
LAUNCH_SERVER=""; PLIST_COPY=""
if [ "${BUILT_FOR%/}" != "$SERVER" ]; then
  grep -rqs KELIVER_BUNDLE_SERVER "$HOST/src/iosMain/kotlin" \
    || fail "host-ios/ looks for bundles at ${BUILT_FOR:-?}, and it is from before tools 0.3.10: it takes no server at launch. Set BUNDLE_SERVER = \"$SERVER\" in $CONFIG, or scaffold host-ios/ again."
  LAUNCH_SERVER="$SERVER"
  echo "$NAME: host-ios/ looks for bundles at ${BUILT_FOR:-?}; this launch points it at $SERVER (KELIVER_BUNDLE_SERVER, a debug build's; HostConfig.kt is not changed)"
fi
rm -f "$OUT/Info.plist"
if [ -f "$HOST/iosApp/Info.plist" ]; then
  ATS="$(python3 -I - "$HOST/iosApp/Info.plist" "$OUT/Info.plist" <<'PY'
import plistlib, sys
src, dst = sys.argv[1:3]
try:
    with open(src, "rb") as f:
        plist = plistlib.load(f)
except Exception as e:
    sys.exit("keliver-run-ios: cannot read %s: %s" % (src, e))
ats = plist.get("NSAppTransportSecurity") or {}
domains = {k.lower(): v for k, v in (ats.get("NSExceptionDomains") or {}).items()}
local = domains.get("localhost") or {}
if ats.get("NSAllowsLocalNetworking") is True or ats.get("NSAllowsArbitraryLoads") is True \
        or local.get("NSExceptionAllowsInsecureHTTPLoads") is True or local.get("NSTemporaryExceptionAllowsInsecureHTTPLoads") is True:
    print("allowed")
else:
    ats = dict(ats)
    ats["NSExceptionDomains"] = dict(ats.get("NSExceptionDomains") or {}, localhost={"NSExceptionAllowsInsecureHTTPLoads": True})
    plist["NSAppTransportSecurity"] = ats
    with open(dst, "wb") as f:
        plistlib.dump(plist, f)
    print("copied")
PY
)" || exit 1
  if [ "$ATS" = copied ]; then
    PLIST_COPY="$OUT/Info.plist"
    echo "$NAME: host-ios/iosApp/Info.plist allows no http:// to localhost; this debug build uses a copy that does ($PLIST_COPY)"
  fi
fi

# 4. A debug build for this simulator.
echo "$NAME: building the host (Debug, for the simulator); the first build takes several minutes"
LOG="$OUT/build.log"; DD="$OUT/DerivedData"
xbuild() {
  ( cd "$APP_DIR" && xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -configuration Debug \
      -sdk iphonesimulator -destination "id=$UDID" -derivedDataPath "$DD" CODE_SIGNING_ALLOWED=NO "$@" build )
}
if [ -n "$PLIST_COPY" ]; then xbuild "INFOPLIST_FILE=$PLIST_COPY" > "$LOG" 2>&1; else xbuild > "$LOG" 2>&1; fi \
  || { grep -E '^e: |error:|What went wrong' -A3 "$LOG" | head -20 >&2 || true; fail "the build failed; the whole log is $LOG"; }
APPB="$(ls -td "$DD/Build/Products/Debug-iphonesimulator/"*.app 2>/dev/null | head -1 || true)"
[ -n "$APPB" ] || fail "the build wrote no .app under $DD/Build/Products/Debug-iphonesimulator."
BID="$(python3 -I -c '
import plistlib, sys
try:
    print(plistlib.load(open(sys.argv[1], "rb"))["CFBundleIdentifier"])
except Exception:
    pass
' "$APPB/Info.plist")"
[ -n "$BID" ] || fail "cannot read CFBundleIdentifier from $APPB/Info.plist."

# 5. Boot, install, start, and wait for the host's outcome.
if [ "$STATE" != Booted ]; then
  echo "$NAME: booting $SIM_NAME"
  xcrun simctl bootstatus "$UDID" -b > "$OUT/boot.log" 2>&1 || fail "the simulator did not boot: $(tail -2 "$OUT/boot.log" | tr '\n' ' ')"
fi
# Show it: the simulator boots without a window when nothing else opened one.
if command -v open >/dev/null; then open -a Simulator --args -CurrentDeviceUDID "$UDID" > /dev/null 2>&1 || true; fi
xcrun simctl install "$UDID" "$APPB" > "$OUT/install.log" 2>&1 \
  || fail "the install failed: $(tail -2 "$OUT/install.log" | tr '\n' ' ')"
# The host prints its lines (KeliverHost: ...) on stdout, which goes to this file.
HOSTLOG="$OUT/host.log"
start() {
  xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1 || true
  rm -f "$HOSTLOG" "$OUT/host-stderr.log"
  if [ -n "$LAUNCH_SERVER" ]; then
    SIMCTL_CHILD_KELIVER_BUNDLE_SERVER="$LAUNCH_SERVER" xcrun simctl launch "--stdout=$HOSTLOG" "--stderr=$OUT/host-stderr.log" "$UDID" "$BID"
  else
    xcrun simctl launch "--stdout=$HOSTLOG" "--stderr=$OUT/host-stderr.log" "$UDID" "$BID"
  fi > "$OUT/launch.log" 2>&1 || fail "could not start $BID: $(tail -2 "$OUT/launch.log" | tr '\n' ' ')"
}
start
echo "$NAME: started $BID; waiting for it to load a bundle (at most ${WAIT} s)"
hostlog() { cat "$HOSTLOG" 2>/dev/null || true; }
# Every host logs these, whatever its report settings: codeLoadSuccess (with the sequence and
# whether it came from the network or its cache), and the lookup's and the load's failures.
outcome=""; line=""; t=0; again=""
while [ "$t" -lt "$WAIT" ]; do
  sleep 3; t=$((t + 3))
  line="$(hostlog | grep -E 'codeLoadSuccess |codeLoadFailed|refusing to load|refusing a manifest URL|bundle index: |bundle lookup failed|bundle lookup: HTTP|no compatible bundle' | head -1 || true)"
  case "$line" in
    *"codeLoadSuccess "*"source=network"*) outcome=ok; break ;;
    *"codeLoadSuccess "*) outcome=cache; break ;;
    ?*) outcome=failed; break ;;
  esac
  # Nothing printed at all: the process never started (seen on a first launch after an
  # install on a slow machine). Launch it once more.
  if [ -z "$again" ] && [ "$t" -ge "$QUIET" ] && [ ! -s "$HOSTLOG" ]; then
    again=1
    echo "$NAME: the host printed nothing in $t s; launching it once more"
    start
  fi
done
if [ "$outcome" = ok ]; then
  seq="$(printf '%s' "$line" | sed -n 's/.*sequence=\([0-9][0-9]*\).*/\1/p')"
  echo "$NAME: running — $BID on $SIM_NAME ($UDID) loaded bundle sequence ${seq:-?} from $SERVER."
  [ -n "$seq" ] && [ "$seq" != "$LATEST" ] \
    && echo "  The portal's newest is $LATEST: the host took the newest one its channel and capabilities allow."
  case "$UPDATES_LINE:$UPDATES" in
    0:*)          echo "  This host (from before tools 0.3.10) takes a new bundle at its next launch: run this again after ⬆ Publish." ;;
    *:|*:live)    echo "  Edit in the editor and ⬆ Publish: a debug host from tools 0.3.14 on takes the new bundle while it is open." ;;
    *:on-resume)  echo "  Edit and ⬆ Publish: this host (UPDATES on-resume) takes the new bundle when it comes back to the foreground." ;;
    *)            echo "  Edit and ⬆ Publish: this host (UPDATES $UPDATES) takes the new bundle at its next launch: run this again." ;;
  esac
  [ -n "$LAUNCH_SERVER" ] \
    && echo "  Only a launch by this command looks at $SERVER; opened from the home screen, the host looks at ${BUILT_FOR:-its HostConfig.kt server} again."
  echo "  The host's log: $HOSTLOG"
  exit 0
fi
hostlog | tail -12 >&2 || true
case "$outcome" in
  cache)  fail "the host started from its cached bundle, not from $SERVER: it did not reach the relay (its log is above)." ;;
  failed) fail "the host did not load a bundle: $(printf '%s' "$line" | sed 's/.*KeliverHost: //')" ;;
esac
[ -s "$HOSTLOG" ] || fail "the host printed nothing within ${WAIT} s: did it start? ($OUT/launch.log, $OUT/host-stderr.log)"
fail "the host said nothing about a bundle within ${WAIT} s (its log is above)."
