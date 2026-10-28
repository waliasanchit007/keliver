#!/usr/bin/env bash
#
# keliver-run-android — this app's screens on an Android phone or emulator, in one command.
#
#   keliver-run-android.sh [--serial SERIAL] [app-dir]      (app-dir: default the current directory)
#
# Run it with the app's portal running (keliver-portal) and a bundle published
# (⬆ Publish in the editor, or curl -X POST http://localhost:<port>/publish). It:
#   1. finds the relay on keliver.portal.json's "port" (default 8077) and checks
#      that it has published a bundle;
#   2. picks the device: --serial, or the only one `adb devices` lists;
#   3. scaffolds host-android/ (keliver-new-production-host.sh) when the app has none;
#   4. builds a DEBUG host that looks for bundles at http://localhost:<port>
#      (-Pkeliver.bundleServer: host-android/gradle.properties is not changed);
#   5. `adb reverse tcp:<port> tcp:<port>`: the device reaches this machine's relay
#      over USB or wireless debugging, and nothing listens on your network;
#   6. installs and starts the host, and waits for it to load your bundle from the
#      relay (its log's codeLoadSuccess, source=network), or to say why not.
# A debug host scaffolded from tools 0.3.13 on then updates live: edit, ⬆ Publish,
# and the open app takes the new bundle. Run this again only after changing the
# host itself.
#
# Needs: adb (on PATH, or in $ANDROID_HOME/platform-tools), the Android SDK for the
# build, python3, curl. It never publishes, and it touches no device but the one chosen.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAME=keliver-run-android
fail() { echo "$NAME: $*" >&2; exit 1; }

SERIAL=""; APP_DIR="."; WAIT="${KELIVER_RUN_WAIT_SECONDS:-120}"
while [ $# -gt 0 ]; do
  case "$1" in
    --serial) [ $# -ge 2 ] || { echo "$NAME: --serial needs a value" >&2; exit 2; }; SERIAL="$2"; shift 2 ;;
    -h|--help) sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "$NAME: unknown option $1 (see --help)" >&2; exit 2 ;;
    *) APP_DIR="$1"; shift ;;
  esac
done
[ -d "$APP_DIR" ] || fail "no such directory: $APP_DIR"
APP_DIR="$(cd "$APP_DIR" && pwd)"
[ -f "$APP_DIR/keliver.portal.json" ] || fail "$APP_DIR has no keliver.portal.json: run this in a Keliver app (keliver-init makes one)."
command -v python3 >/dev/null || fail "python3 is needed."
command -v curl >/dev/null || fail "curl is needed."

# 1. The relay and a published bundle.
PORT="$(python3 -I - "$APP_DIR/keliver.portal.json" <<'PY'
import json, sys
try:
    config = json.load(open(sys.argv[1]))
except (OSError, ValueError) as e:
    sys.exit(f"keliver-run-android: cannot read {sys.argv[1]} as JSON: {e}")
port = config.get("port", 8077) if isinstance(config, dict) else None
if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
    sys.exit(f"keliver-run-android: \"port\" in keliver.portal.json must be a port number, not {json.dumps(port)}")
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

# 2. The device.
ADB="$(command -v adb || true)"
if [ -z "$ADB" ] && [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then ADB="$ANDROID_HOME/platform-tools/adb"; fi
[ -n "$ADB" ] || fail "adb is not on PATH (install the Android SDK platform-tools, or set ANDROID_HOME)."
if [ -z "$SERIAL" ]; then
  DEVS="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
  case "$(printf '%s' "$DEVS" | grep -c . || true)" in
    0) fail "no device: connect a phone with USB or wireless debugging on, or start an emulator (adb devices lists none)." ;;
    1) SERIAL="$DEVS" ;;
    *) fail "several devices; choose one with --serial: $(printf '%s' "$DEVS" | tr '\n' ' ')" ;;
  esac
fi
[ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)" = device ] || fail "device $SERIAL is not ready (adb get-state)."
adbs() { "$ADB" -s "$SERIAL" "$@"; }
echo "$NAME: device $SERIAL ($(adbs shell getprop ro.product.model 2>/dev/null | tr -d '\r'))"

# 3. The host.
SERVER="http://localhost:$PORT"
HOST="$APP_DIR/host-android"
if [ ! -d "$HOST" ]; then
  echo "$NAME: no host-android/ yet; scaffolding it (keliver-new-production-host.sh --bundle-server $SERVER)"
  ( cd "$APP_DIR" && "$HERE/keliver-new-production-host.sh" --bundle-server "$SERVER" ) || fail "scaffolding host-android/ failed (above)."
fi
APP_ID="$(sed -n "s/^[[:space:]]*applicationId[[:space:]]*['\"]\([^'\"]*\)['\"].*/\1/p" "$HOST/build.gradle" | head -1)"
[ -n "$APP_ID" ] || fail "cannot read applicationId from $HOST/build.gradle."

# 4. A debug build that looks for bundles on this machine's relay.
[ -x "$APP_DIR/gradlew" ] || fail "$APP_DIR has no gradlew."
echo "$NAME: building $APP_ID (debug, bundle server $SERVER)"
LOG="$APP_DIR/build/$NAME.log"; mkdir -p "$APP_DIR/build"
( cd "$APP_DIR" && ./gradlew --console=plain -p host-android assembleDebug "-Pkeliver.bundleServer=$SERVER" ) > "$LOG" 2>&1 \
  || { grep -E '^e: |What went wrong' -A3 "$LOG" | head -20 >&2 || true; fail "the build failed; the whole log is $LOG"; }
APK="$(find "$HOST/build/outputs/apk/debug" -name '*.apk' 2>/dev/null | head -1)"
[ -n "$APK" ] || fail "the build wrote no APK under $HOST/build/outputs/apk/debug."

# 5. The device reaches the relay through adb.
adbs reverse "tcp:$PORT" "tcp:$PORT" > /dev/null || fail "adb reverse tcp:$PORT failed."

# 6. Install, start, and wait for the host's outcome.
adbs install -r "$APK" > "$APP_DIR/build/$NAME.install.log" 2>&1 \
  || fail "the install failed: $(tail -2 "$APP_DIR/build/$NAME.install.log" | tr '\n' ' ')"
# Only this start's lines: from the device's clock now (and the buffer cleared, where allowed).
SINCE="$(adbs shell date "'+%m-%d %H:%M:%S.000'" 2>/dev/null | tr -d '\r' || true)"
adbs logcat -c > /dev/null 2>&1 || true
hostlog() {
  if [ -n "$SINCE" ]; then adbs logcat -d -T "$SINCE" -s KeliverHost:D 2>/dev/null || true
  else adbs logcat -d -s KeliverHost:D 2>/dev/null || true; fi
}
adbs shell am force-stop "$APP_ID"
adbs shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1 || fail "could not start $APP_ID."
echo "$NAME: started $APP_ID; waiting for it to load a bundle (at most ${WAIT} s)"
# Every host logs these, whatever its report settings: codeLoadSuccess (with the sequence and
# whether it came from the network or its cache), and the lookup's and the load's failures.
outcome=""; line=""; t=0
while [ "$t" -lt "$WAIT" ]; do
  sleep 3; t=$((t + 3))
  line="$(hostlog | grep -E 'codeLoadSuccess |codeLoadFailed|refusing to load|refusing a manifest URL|bundle lookup failed|bundle lookup: HTTP|no compatible bundle' | head -1 || true)"
  case "$line" in
    *"codeLoadSuccess "*"source=network"*) outcome=ok; break ;;
    *"codeLoadSuccess "*) outcome=cache; break ;;
    ?*) outcome=failed; break ;;
  esac
done
if [ "$outcome" = ok ]; then
  seq="$(printf '%s' "$line" | sed -n 's/.*sequence=\([0-9][0-9]*\).*/\1/p')"
  echo "$NAME: running — $APP_ID on $SERIAL loaded bundle sequence ${seq:-?} from $SERVER."
  echo "  Edit in the editor and ⬆ Publish: a debug host from tools 0.3.13 on takes the new bundle while it is open."
  echo "  This machine's relay stays reachable from the device until: $ADB -s $SERIAL reverse --remove tcp:$PORT"
  exit 0
fi
hostlog | tail -12 >&2 || true
case "$outcome" in
  cache)  fail "the host started from its cached bundle, not from $SERVER: it did not reach the relay (its log is above)." ;;
  failed) fail "the host did not load a bundle: $(printf '%s' "$line" | sed 's/.*KeliverHost: //')" ;;
esac
fail "the host said nothing about a bundle within ${WAIT} s (its log is above)."
