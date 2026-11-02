#!/usr/bin/env bash
#
# As run from a scratch directory on 2026-11-02 (paths and the device serial replaced). M5's memory cycles, more of them: the benchmark host built by
# android-m45.sh (published 0.3.17 zip), compiled to speed, one launch per run with CYCLES memory
# cycles 25 s apart, then memory.py --cycles CYCLES. For a leak bound in memory.py.
#
#   android-cycles.sh [runs] [cycles]     (default 2 runs of 8 cycles)
set -uo pipefail
P=<scratch>/phone-m45
APP="$P/app"; H="$APP/host-android"; SITE="$P/site"
CI=<checkout>/reference/inventory/ci   # origin/main at 1c6db68ef or later
TLS=<scratch>/k4-a12/work3/w3/tls
S="<the phone's adb serial>"
RUNS="${1:-2}"; CYCLES="${2:-8}"; EV="$P/ev-cycles"; mkdir -p "$EV"
export JAVA_HOME="$(/usr/libexec/java_home -v 17)" GRADLE_USER_HOME=<scratch>/a12-live/gradle-home
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH" JAVA_TOOL_OPTIONS="-Duser.home=$P/home"
adb_(){ adb -s "$S" "$@"; }
log(){ echo "$*" | tee -a "$EV/run.log"; }

phone_free(){
  local p
  p="$(adb_ shell dumpsys power | tr -d '\r')"
  grep -q "mWakefulness=Awake" <<<"$p" || { echo "the phone is not awake"; return 1; }
  p="$(adb_ shell dumpsys window | tr -d '\r' | sed -nE 's/^ *isKeyguardShowing=(true|false).*/\1/p' | head -1)"
  [ "$p" = false ] || { echo "the phone is locked (isKeyguardShowing=${p:-not reported})"; return 1; }
  p="$(adb_ shell dumpsys telephony.registry | tr -d '\r' | sed -nE 's/^ *mCallState=([0-9]+).*/\1/p' | sort -u | paste -sd, -)"
  [ "$p" = 0 ] || { echo "a phone call may be in progress (mCallState=${p:-not reported})"; return 1; }
  return 0
}
why="$(phone_free)" || { log "not starting: $why"; exit 3; }

AAPT2="$(ls "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)"

lsof -nP -iTCP:8443 -sTCP:LISTEN >/dev/null 2>&1 && { log "something already listens on 8443"; exit 2; }
adb_ reverse --list | grep -q "tcp:8443 " && { log "the phone already has a reverse on tcp:8443"; exit 2; }
python3 "$CI/w3/static_https.py" "$SITE" 8443 "$TLS/server.pem" "$TLS/server.key" "$EV/server.log" &
SERVER=$!; REVERSED=""; ID=""; STREAM=""
cleanup(){
  [ -n "$STREAM" ] && kill "$STREAM" 2>/dev/null
  kill "$SERVER" 2>/dev/null
  [ -n "$REVERSED" ] && adb_ reverse --remove tcp:8443 >/dev/null 2>&1
  [ -n "$ID" ] && adb_ uninstall "$ID" >/dev/null 2>&1
  return 0
}
trap cleanup EXIT
for _ in $(seq 1 30); do
  curl -sf --cacert "$TLS/ca.pem" -m 2 https://127.0.0.1:8443/bundles/index.json | cmp -s - "$SITE/bundles/index.json" && break; sleep 1
done
adb_ reverse tcp:8443 tcp:8443 >/dev/null && REVERSED=1 || { log "adb reverse failed"; exit 1; }

start_stream(){ adb_ logcat -c; adb -s "$S" logcat -v threadtime -s KeliverTiming:V KeliverHost:V > "$1" 2>/dev/null & STREAM=$!; }
stop_stream(){ kill "$STREAM" 2>/dev/null; wait "$STREAM" 2>/dev/null; STREAM=""; }
wait_content(){ for _ in $(seq 1 90); do grep -q "KeliverTiming: content [0-9]" "$1" && return 0; sleep 0.5; done; return 1; }
launch(){  # $1 label: a fresh process, its log streaming to $EV/$1.log; 0 once its content mark is in
  local why
  why="$(phone_free)" || { log "$1: stopped: $why"; exit 3; }
  adb_ shell am force-stop "$ID"; sleep 2
  start_stream "$EV/$1.log"
  adb_ shell am start -W -n "$ID/$CMP" > "$EV/$1.am.txt" 2>&1
  wait_content "$EV/$1.log" || { log "$1: no content mark"; return 1; }
  grep -qE "codeLoadSuccess modules=[0-9]+ sequence=9 " "$EV/$1.log" || { log "$1: sequence 9 did not load"; return 1; }
}

build=benchmark
APK="$(find "$H/build/outputs/apk/$build" -name '*.apk' | head -1)"
BADGING="$("$AAPT2" dump badging "$APK" 2>/dev/null)"
ID="$(sed -nE "s/^package: name='([^']+)'.*/\1/p" <<<"$BADGING" | head -1)"
CMP="$(sed -nE "s/^launchable-activity: name='([^']+)'.*/\1/p" <<<"$BADGING" | head -1)"
adb_ install -r "$APK" > "$EV/install-$build.log" 2>&1 || { log "$build: install failed"; exit 1; }
adb_ shell cmd package compile -m speed -f "$ID" > "$EV/compile-$build.log" 2>&1 || { log "$build: compile failed"; exit 1; }
DEXOPT="$(adb_ shell dumpsys package "$ID" | tr -d '\r' | sed -n '/Dexopt state:/,/^$/p' | grep -E 'arm64' | head -1 | tr -s ' ')"
log "== $build: $ID/$CMP, dexopt$DEXOPT, apk $(shasum -a 256 "$APK" | cut -c1-12) $(date -u +%FT%TZ), $RUNS runs of $CYCLES cycles, battery $(adb_ shell dumpsys battery | tr -d '\r' | sed -nE 's/^ *(level|temperature): *([0-9]+).*/\1=\2/p' | paste -sd' ' -)"
for r in $(seq 1 "$RUNS"); do
  L="$build-cycles-$r"
  if launch "$L"; then
    sleep 5
    for c in $(seq 1 "$CYCLES"); do
      why="$(phone_free)" || { log "$L: stopped at cycle $c: $why"; stop_stream; exit 3; }
      adb_ shell am broadcast -a dev.keliver.debug.MEMORY_CYCLE -p "$ID" > /dev/null; sleep 25
    done
    stop_stream
    log "$L: $(python3 "$CI/m0/memory.py" --cycles "$CYCLES" "$EV/$L.log" 2>&1)"
  else
    stop_stream
  fi
done
adb_ shell am force-stop "$ID"; adb_ uninstall "$ID" > /dev/null 2>&1; ID=""
log "== done $(date -u +%FT%TZ), battery $(adb_ shell dumpsys battery | tr -d '\r' | sed -nE 's/^ *(level|temperature): *([0-9]+).*/\1=\2/p' | paste -sd' ' -)"
