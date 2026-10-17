#!/usr/bin/env bash
#
# M2 (docs/ADOPTION_PLAN_2026-10.md, item 2): startup numbers for the reference
# app's BENCHMARK build (release settings, minified with R8, profileable, with
# M0's timing marks) on an Android phone or emulator.
#
#   m2/android.sh <app-dir> <site-dir> <tls-dir> <serial> <evidence-dir> [runs]
#
# <app-dir>  the reference app with host-android/ scaffolded from templates that
#            have the timing marks (tools 0.3.10 on), its key matching the site's
# <site-dir> a keliver-publish output (bundles/index.json, bundles/v<N>/)
# <tls-dir>  ci/w3/tls.sh's output: the site is served over https on
#            127.0.0.1:8443 and reached through `adb reverse`; the benchmark
#            build trusts that throwaway CA in its own source set
#            (ci/w7/android-test-ca.sh), as the debug builds do on a phone
#
# Scenarios, each [runs] times (default 5) after one discarded warm-up round:
#   first  the first launch after `pm clear`: lookup, download, load, show
#   cold   the process stopped, the bundle cached: process start to content
#          (`process` is Process.getStartElapsedRealtime(), at bindApplication:
#          the fork and zygote work before it are in am_total_ms, not in it)
#   warm   the process running, the app sent home, then opened again in a new
#          task (so a new KeliverScreen; the proof's "warm reopen"): open to
#          content, with no `process` or `code` mark (asserted)
# Every launch, cold included, asks the server for bundles/index.json and the
# manifest (only the modules come from the cache): those numbers include TLS
# through `adb reverse` to a Python server on this Mac. Each run's requests are
# kept (<scenario>-<run>.server.txt).
# `content` is the reference guest's first composition (LaunchedEffect(Unit)),
# so it lands with first-frame: there is no data load to wait for.
# Memory: `dumpsys meminfo` TOTAL PSS, about 1 s after each content mark.
# Compilation: the APK as `adb install` leaves it (no baseline profile in the
# host); background dexopt is not controlled, so the state is recorded before
# and after the runs (m2-android.env). The build pins keliver.updates and
# keliver.channel (stable); a first or cold run must load the site's newest
# stable entry.
# Output: <evidence-dir>/m2-android.tsv (one row per good run), the raw logcat
# and `am start -W` output of each run, m2-android.errors (empty when every run
# was good), m2-android.env; summarise with m2/summarise.py. Exit 1 when any run
# failed. The APK stays installed (its applicationId is the reference app's);
# remove it with `adb uninstall`.
#
# A launch takes over the phone's screen: the script refuses to start, and
# stops, while the phone is locked or asleep or a call is in progress.
set -uo pipefail
usage="usage: $0 <app-dir> <site-dir> <tls-dir> <serial> <evidence-dir> [runs]"
[ $# -ge 5 ] || { echo "$usage" >&2; exit 2; }
for d in "$1" "$2" "$3"; do [ -d "$d" ] || { echo "not a directory: $d" >&2; echo "$usage" >&2; exit 2; }; done
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
CI="$HERE/../ci"
APP="$(cd "$1" && pwd -P)"; SITE="$(cd "$2" && pwd -P)"; TLS="$(cd "$3" && pwd -P)"; SERIAL="$4"
mkdir -p "$5" || exit 2; EV="$(cd "$5" && pwd -P)"; RUNS="${6:-5}"
H="$APP/host-android"
grep -rqs "interface KeliverTrace" "$H/src/timing" || { echo "host-android has no timing marks (src/timing): scaffold it from tools 0.3.10 or later" >&2; exit 2; }
SEQ="$(python3 -c 'import json,sys; print(max(e["sequence"] for e in json.load(open(sys.argv[1]))["entries"] if e.get("channel","stable")=="stable"))' "$SITE/bundles/index.json" 2>/dev/null)"
[ -n "$SEQ" ] || { echo "no sequence in $SITE/bundles/index.json" >&2; exit 2; }
adb_(){ adb -s "$SERIAL" "$@"; }
ERR="$EV/m2-android.errors"; : > "$ERR"
err(){ echo "$*" | tee -a "$ERR" >&2; }

# The phone must be awake, unlocked and not on a call before anything takes its screen.
phone_free(){
  local p
  p="$(adb_ shell dumpsys power | tr -d '\r')"
  grep -q "mWakefulness=Awake" <<<"$p" || { echo "the phone is not awake"; return 1; }
  # Each check needs its line to be there (an unknown format refuses), then its value.
  p="$(adb_ shell dumpsys window | tr -d '\r' | sed -nE 's/^ *isKeyguardShowing=(true|false).*/\1/p' | head -1)"
  [ "$p" = false ] || { echo "the phone is locked (isKeyguardShowing=${p:-not reported})"; return 1; }
  p="$(adb_ shell dumpsys telephony.registry | tr -d '\r' | sed -nE 's/^ *mCallState=([0-9]+).*/\1/p' | sort -u | paste -sd, -)"
  [ "$p" = 0 ] || { echo "a phone call may be in progress (mCallState=${p:-not reported})"; return 1; }
  p="$(adb_ shell dumpsys audio | tr -d '\r' | sed -nE 's/^[ -]*Actual mode = ([A-Z_]+).*/\1/p' | head -1)"
  [ "$p" = MODE_NORMAL ] || { echo "the audio mode is ${p:-not reported}, not MODE_NORMAL (a call?)"; return 1; }
  return 0
}
why="$(phone_free)" || { echo "not starting: $why" >&2; exit 3; }

# The benchmark build, for https://127.0.0.1:8443, trusting the throwaway CA.
if [ -f "$H/src/benchmark/AndroidManifest.xml" ]; then
  cmp -s "$H/src/benchmark/res/raw/keliver_test_ca.pem" "$TLS/ca.pem" \
    || { echo "host-android/src/benchmark trusts another CA than $TLS/ca.pem: remove src/benchmark and rerun" >&2; exit 2; }
else
  bash "$CI/w7/android-test-ca.sh" "$H/src/benchmark" "$TLS/ca.pem" > "$EV/test-ca.log" 2>&1 \
    || { echo "the test CA overlay failed: $(cat "$EV/test-ca.log")" >&2; exit 1; }
fi
( cd "$APP" && ./gradlew --console=plain -p host-android assembleBenchmark \
    -Pkeliver.bundleServer=https://127.0.0.1:8443 -Pkeliver.updates=next-launch -Pkeliver.channel=stable ) > "$EV/build.log" 2>&1 \
  || { echo "assembleBenchmark failed" >&2; tail -30 "$EV/build.log" >&2; exit 1; }
APK="$(find "$H/build/outputs/apk/benchmark" -name '*.apk' | head -1)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AAPT2="$(python3 -c 'import glob,os,sys
c=glob.glob(os.path.join(sys.argv[1],"build-tools","*","aapt2"))
k=lambda p:[int(x) if x.isdigit() else -1 for x in os.path.basename(os.path.dirname(p)).replace("-",".").split(".")]
print(max(c,key=k) if c else "")' "$SDK")"
[ -x "$AAPT2" ] || { echo "no aapt2 under $SDK/build-tools" >&2; exit 1; }
BADGING="$("$AAPT2" dump badging "$APK" 2>/dev/null)"
# The id is the APK's own, never build.gradle's: a suffix or a flavor would
# otherwise point `pm clear` at another installed app.
ID="$(sed -nE "s/^package: name='([^']+)'.*/\1/p" <<<"$BADGING" | head -1)"
CMP="$(sed -nE "s/^launchable-activity: name='([^']+)'.*/\1/p" <<<"$BADGING" | head -1)"
[ -n "$ID" ] && [ -n "$CMP" ] || { echo "could not read the application id or the launcher activity from $APK" >&2; exit 1; }
grep -q 'KELIVER_UPDATES = "next-launch"' "$(find "$H/build/generated/source/buildConfig/benchmark" -name BuildConfig.java | head -1)" 2>/dev/null \
  || { echo "the benchmark BuildConfig does not say next-launch" >&2; exit 1; }

# The bundle server: our own, on a free port, answering with the site's index.
lsof -nP -iTCP:8443 -sTCP:LISTEN >/dev/null 2>&1 && { echo "something already listens on 8443 on this Mac" >&2; exit 2; }
adb_ reverse --list | grep -q "tcp:8443 " && { echo "the phone already has an adb reverse on tcp:8443" >&2; exit 2; }
python3 "$CI/w3/static_https.py" "$SITE" 8443 "$TLS/server.pem" "$TLS/server.key" "$EV/server.log" &
SERVER=$!
REVERSED=""
cleanup(){
  kill -0 "$SERVER" 2>/dev/null && kill "$SERVER"
  [ -n "$REVERSED" ] && adb_ reverse --remove tcp:8443 >/dev/null 2>&1
  [ -n "${ID:-}" ] && adb_ shell am force-stop "$ID" >/dev/null 2>&1
  [ -n "${LOGCAT:-}" ] && kill "$LOGCAT" 2>/dev/null
  return 0
}
trap cleanup EXIT
up=""
for _ in $(seq 1 30); do
  kill -0 "$SERVER" 2>/dev/null || break
  curl -sf --cacert "$TLS/ca.pem" -m 2 https://127.0.0.1:8443/bundles/index.json | cmp -s - "$SITE/bundles/index.json" && { up=1; break; }
  sleep 1
done
[ -n "$up" ] || { echo "the bundle server did not answer with $SITE's index" >&2; exit 1; }
adb_ reverse tcp:8443 tcp:8443 > /dev/null || { echo "adb reverse tcp:8443 failed" >&2; exit 1; }
REVERSED=1
adb_ install -r "$APK" > "$EV/install.log" 2>&1 || { echo "install failed: $(cat "$EV/install.log")" >&2; exit 1; }
adb_ shell pm path "$ID" | grep -q "^package:" || { echo "$ID is not installed after the install" >&2; exit 1; }

dexopt(){ adb_ shell dumpsys package "$ID" | tr -d '\r' | sed -n '/Dexopt state:/,/^$/p' | grep -E "\[status=|arm64" | head -3 | tr -s ' ' | paste -sd' ' -; }
{
  echo "apk $(shasum -a 256 "$APK" | cut -d' ' -f1) $(wc -c < "$APK" | tr -d ' ') bytes, $ID/$CMP, site sequence $SEQ"
  echo "device $(adb_ shell getprop ro.product.model | tr -d '\r') Android $(adb_ shell getprop ro.build.version.release | tr -d '\r') build $(adb_ shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "dexopt after install: $(dexopt)"
  echo "thermal: $(adb_ shell dumpsys thermalservice | tr -d '\r' | grep -m1 'Thermal Status')"
} | tee "$EV/m2-android.env"

# One launch: its own logcat stream (KeliverTiming and KeliverHost only, from
# an empty buffer), `am start -W`'s report, and the server's requests.
MARKS="process open code first-frame content"
start_stream(){  # $1 file
  adb_ logcat -c || { err "logcat -c failed"; return 1; }
  # adb itself, not adb_: a backgrounded function is a subshell, and killing
  # it would leave this adb reading the phone's log.
  adb -s "$SERIAL" logcat -v brief -s KeliverTiming:I KeliverHost:D > "$1" 2>/dev/null &
  LOGCAT=$!
}
stop_stream(){ kill "$LOGCAT" 2>/dev/null; wait "$LOGCAT" 2>/dev/null; LOGCAT=""; }
wait_content(){  # $1 file: up to 30 s for the content mark, then 1 s for PSS to settle
  for _ in $(seq 1 60); do
    grep -q "KeliverTiming.*: content [0-9]" "$1" && { sleep 1; return 0; }
    sleep 0.5
  done
  return 1
}
at(){ sed -nE "s/.*KeliverTiming.*: $2 ([0-9]+)\$/\1/p" "$1" | head -1; }
count(){ grep -cE "KeliverTiming.*: $2 [0-9]+\$" "$1"; }
pss(){ adb_ shell dumpsys meminfo "$ID" | tr -d '\r' | sed -nE 's/^ *TOTAL PSS: *([0-9]+).*/\1/p' | head -1; }
temp(){ adb_ shell dumpsys battery | tr -d '\r' | sed -nE 's/^ *temperature: *([0-9]+).*/\1/p' | head -1; }
ms(){ [ -n "$1" ] && [ -n "$2" ] && echo $(( ($2 - $1) / 1000000 )) || echo "-"; }
amv(){ sed -nE "s/^$2: *([A-Z0-9]+).*/\1/p" "$1" | head -1; }
TSV="$EV/m2-android.tsv"
printf 'scenario\trun\tprocess_open\tprocess_code\tprocess_first_frame\tprocess_content\topen_first_frame\topen_content\tam_state\tam_total_ms\tpss_kb\tbattery_dC\n' > "$TSV"

# measure <scenario> <run> [am start flags]: one launch; a row only when it is sound.
measure(){
  local s="$1" r="$2"; shift 2
  local L="$EV/$s-$r.txt" A="$EV/$s-$r.am.txt" n0 why m expect pairs pr want
  why="$(phone_free)" || { err "$s $r: stopped: $why"; exit 3; }
  sleep 3   # the checks' dumpsys work settles before the measured launch
  n0=0; [ -f "$EV/server.log" ] && n0="$(wc -l < "$EV/server.log")"
  start_stream "$L" || return 1
  adb_ shell am start -W -n "$ID/$CMP" "$@" > "$A" 2>&1
  if ! wait_content "$L"; then stop_stream; err "$s $r: no content mark"; return 1; fi
  local p_kb; p_kb="$(pss)"
  stop_stream
  tail -n +"$((n0 + 1))" "$EV/server.log" > "$EV/$s-$r.server.txt"
  # Each mark exactly once (none of process/code on a warm open), in the
  # orders that are guaranteed (as M0's rows). open and code are not ordered
  # against each other: the load waits for the screen AND the lookup, and
  # which of the screen or the lookup comes first varies.
  if [ "$s" = warm ]; then expect="open first-frame content"; else expect="$MARKS"; fi
  for m in $MARKS; do
    case " $expect " in
      *" $m "*) [ "$(count "$L" "$m")" = 1 ] || { err "$s $r: '$m' logged $(count "$L" "$m") times, not once"; return 1; } ;;
      *) [ "$(count "$L" "$m")" = 0 ] || { err "$s $r: '$m' logged on a warm open (not the same process, or the code reloaded)"; return 1; } ;;
    esac
  done
  if [ "$s" = warm ]; then pairs="open:first-frame open:content"
  else pairs="process:open process:code code:first-frame code:content open:first-frame open:content"; fi
  for pr in $pairs; do
    [ "$(at "$L" "${pr#*:}")" -ge "$(at "$L" "${pr%%:*}")" ] || { err "$s $r: '${pr#*:}' is before '${pr%%:*}'"; return 1; }
  done
  want=COLD; [ "$s" = warm ] && want=WARM
  [ "$(amv "$A" LaunchState)" = "$want" ] || { err "$s $r: am start says LaunchState $(amv "$A" LaunchState), not $want"; return 1; }
  if [ "$s" != warm ]; then
    grep -qE "codeLoadSuccess modules=[0-9]+ sequence=$SEQ " "$L" \
      || { err "$s $r: sequence $SEQ (the site's newest stable entry) did not load: $(grep -m1 codeLoad "$L")"; return 1; }
  fi
  local p o c f t
  p="$(at "$L" process)"; o="$(at "$L" open)"; c="$(at "$L" code)"; f="$(at "$L" first-frame)"; t="$(at "$L" content)"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$s" "$r" \
    "$(ms "$p" "$o")" "$(ms "$p" "$c")" "$(ms "$p" "$f")" "$(ms "$p" "$t")" "$(ms "$o" "$f")" "$(ms "$o" "$t")" \
    "$(amv "$A" LaunchState)" "$(amv "$A" TotalTime)" "${p_kb:--}" "$(temp)" >> "$TSV"
}

round(){  # $1 run number (0: the discarded warm-up)
  local r="$1" out
  # first: no data, nothing cached
  adb_ shell am force-stop "$ID"
  out="$(adb_ shell pm clear "$ID" | tr -d '\r')"
  [ "$out" = Success ] || { err "pm clear $ID: $out"; exit 1; }
  measure first "$r"
  # cold: the process stopped, the bundle cached
  sleep 2; adb_ shell am force-stop "$ID"
  measure cold "$r"
  # warm: home, then a new task in the same process (measure asserts WARM
  # and no process or code mark)
  adb_ shell input keyevent KEYCODE_HOME
  measure warm "$r" -f 0x10008000
  return 0
}

round 0 > /dev/null
# The warm-up's rows and errors are not results; but a warm-up with no good
# launch at all means the setup is wrong, and the runs would only time out.
for sc in first cold warm; do
  cut -f1 "$TSV" | grep -qx "$sc" || { echo "the warm-up had no good '$sc' launch: see $ERR" >&2; exit 1; }
done
head -1 "$TSV" > "$TSV.tmp" && mv "$TSV.tmp" "$TSV"
[ -s "$ERR" ] && { mv "$ERR" "$EV/m2-android.warmup-errors"; : > "$ERR"; }
for r in $(seq 1 "$RUNS"); do round "$r"; done
adb_ shell am force-stop "$ID"
echo "dexopt after the runs: $(dexopt)" | tee -a "$EV/m2-android.env"
python3 "$HERE/summarise.py" --runs "$RUNS" "$TSV"
[ -s "$ERR" ] && { echo "$(wc -l < "$ERR" | tr -d ' ') failed run(s): $ERR" >&2; exit 1; }
exit 0
