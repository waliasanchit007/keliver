#!/usr/bin/env bash
#
# As run from a scratch directory on 2026-11-02 (its paths and the device's serial replaced
# below). M4's guest frame profile and M5's memory profile on a
# Galaxy A12, from host-android scaffolded by the PUBLISHED tools 0.3.17 zip
# (keliver-new-production-host.sh), in its debug and benchmark builds, against the reference
# app's static site (sequence 9) over https://127.0.0.1:8443 through `adb reverse`; both
# builds trust the throwaway CA from their own source sets (ci/w7/android-test-ca.sh).
#
#   android-m45.sh [runs]     (default 3 of each, per build)
#
# Per build and run:
#   frames  a launch; after its content, drive.py's `frames` (Add 1 twice on Espresso beans,
#           each observed in the view hierarchy), then the launch's KeliverTiming lines read
#           by frames.py --changed (a timed frame that sent a change list, as CI's T3)
#   memory  a launch of its own; after its content, two memory cycles (adb's broadcast
#           dev.keliver.debug.MEMORY_CYCLE), then memory.py (as CI's T4)
# Each build is compiled to `speed` after install (recorded), so dexopt cannot move the numbers.
# Each launch's log is its own filtered logcat stream (KeliverTiming, KeliverHost), so a
# phone's busy log cannot roll the lines away. Every launch first checks that the phone is
# awake, unlocked and not on a call, and stops otherwise. The app is uninstalled and the
# reverse removed at the end.
set -uo pipefail
P=<scratch>/phone-m45
APP="$P/app"; H="$APP/host-android"; SITE="$P/site"
CI=<checkout>/reference/inventory/ci   # origin/main at 1c6db68ef or later
TLS=<scratch>/k4-a12/work3/w3/tls
S="<the phone's adb serial>"
RUNS="${1:-3}"; EV="$P/ev-android"; mkdir -p "$EV"
export JAVA_HOME="$(/usr/libexec/java_home -v 17)" GRADLE_USER_HOME=<scratch>/a12-live/gradle-home
export ANDROID_HOME="$HOME/Library/Android/sdk"   # (and this machine's CA bundle for Node)
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

# Both builds trust the throwaway CA; next-launch, so each launch loads the code once.
for t in debug benchmark; do
  if [ -f "$H/src/$t/res/raw/keliver_test_ca.pem" ]; then
    cmp -s "$H/src/$t/res/raw/keliver_test_ca.pem" "$TLS/ca.pem" || { log "src/$t trusts another CA"; exit 2; }
  else
    bash "$CI/w7/android-test-ca.sh" "$H/src/$t" "$TLS/ca.pem" >> "$EV/test-ca.log" 2>&1 || { log "test CA overlay for $t failed"; exit 1; }
  fi
done
( cd "$APP" && ./gradlew --console=plain -p host-android assembleDebug assembleBenchmark \
    -Pkeliver.bundleServer=https://127.0.0.1:8443 -Pkeliver.updates=next-launch -Pkeliver.channel=stable ) \
  > "$EV/build.log" 2>&1 || { log "the build failed"; tail -30 "$EV/build.log"; exit 1; }
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

for build in debug benchmark; do
  APK="$(find "$H/build/outputs/apk/$build" -name '*.apk' | head -1)"
  BADGING="$("$AAPT2" dump badging "$APK" 2>/dev/null)"
  ID="$(sed -nE "s/^package: name='([^']+)'.*/\1/p" <<<"$BADGING" | head -1)"
  CMP="$(sed -nE "s/^launchable-activity: name='([^']+)'.*/\1/p" <<<"$BADGING" | head -1)"
  adb_ install -r "$APK" > "$EV/install-$build.log" 2>&1 || { log "$build: install failed"; exit 1; }
  # A fixed compile state (full AOT), so background dexopt during the runs cannot change the
  # timings (it did in M3's runs); recorded with each build.
  adb_ shell cmd package compile -m speed -f "$ID" > "$EV/compile-$build.log" 2>&1 || { log "$build: compile failed"; exit 1; }
  DEXOPT="$(adb_ shell dumpsys package "$ID" | tr -d '\r' | sed -n '/Dexopt state:/,/^$/p' | grep -E 'arm64' | head -1 | tr -s ' ')"
  log "== $build: $ID/$CMP, dexopt$DEXOPT, apk $(shasum -a 256 "$APK" | cut -c1-12) $(date -u +%FT%TZ), battery $(adb_ shell dumpsys battery | tr -d '\r' | sed -nE 's/^ *(level|temperature): *([0-9]+).*/\1=\2/p' | paste -sd' ' -)"
  for r in $(seq 1 "$RUNS"); do
    L="$build-frames-$r"
    if launch "$L"; then
      python3 "$CI/drive.py" "$S" "$EV" frames Inventory "$L" > "$EV/$L.drive.txt" 2>&1; rc=$?
      sleep 3; stop_stream
      if [ "$rc" = 0 ]; then
        log "$L: Add 1 twice: ok; $(python3 "$CI/m0/frames.py" --changed "$EV/$L.log" 2>&1)"
      else
        log "$L: Add 1 twice failed ($rc checks): $(tail -2 "$EV/$L.drive.txt" | tr '\n' ' ')"
      fi
    else
      stop_stream
    fi
    L="$build-memory-$r"
    if launch "$L"; then
      sleep 5
      adb_ shell am broadcast -a dev.keliver.debug.MEMORY_CYCLE -p "$ID" > /dev/null; sleep 25
      adb_ shell am broadcast -a dev.keliver.debug.MEMORY_CYCLE -p "$ID" > /dev/null; sleep 25
      stop_stream
      log "$L: $(python3 "$CI/m0/memory.py" "$EV/$L.log" 2>&1)"
    else
      stop_stream
    fi
  done
  adb_ shell am force-stop "$ID"; adb_ uninstall "$ID" > /dev/null 2>&1; ID=""
done
log "== done $(date -u +%FT%TZ), battery $(adb_ shell dumpsys battery | tr -d '\r' | sed -nE 's/^ *(level|temperature): *([0-9]+).*/\1=\2/p' | paste -sd' ' -)"
