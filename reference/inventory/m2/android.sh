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
# Scenarios, each [runs] times (default 5), the app's data kept unless said:
#   first  the first launch after `pm clear`: lookup, download, load, show
#   cold   the process stopped, the bundle cached: process start to content
#   warm   the process running, the screen opened again (a new task, so a new
#          KeliverScreen): open to content, the code already loaded
# Memory: `dumpsys meminfo` TOTAL PSS after each cold content and each warm one.
# Output: <evidence-dir>/m2-android.tsv (one row per run) and the raw logcat of
# each run; summarise with m2/summarise.py.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
CI="$HERE/../ci"
APP="$(cd "${1:?usage: $0 <app-dir> <site-dir> <tls-dir> <serial> <evidence-dir> [runs]}" && pwd -P)"
SITE="$(cd "${2:?}" && pwd -P)"; TLS="$(cd "${3:?}" && pwd -P)"; SERIAL="${4:?}"
EV="$(mkdir -p "${5:?}" && cd "$5" && pwd -P)"; RUNS="${6:-5}"
H="$APP/host-android"
grep -rqs "interface KeliverTrace" "$H/src/timing" || { echo "host-android has no timing marks (src/timing): scaffold it from tools 0.3.10 or later" >&2; exit 2; }
adb_(){ adb -s "$SERIAL" "$@"; }

# The benchmark build, for https://127.0.0.1:8443, trusting the throwaway CA.
[ -f "$H/src/benchmark/AndroidManifest.xml" ] || bash "$CI/w7/android-test-ca.sh" "$H/src/benchmark" "$TLS/ca.pem" > "$EV/test-ca.log" 2>&1 \
  || { echo "the test CA overlay failed: $(cat "$EV/test-ca.log")" >&2; exit 1; }
( cd "$APP" && ./gradlew --console=plain -p host-android assembleBenchmark -Pkeliver.bundleServer=https://127.0.0.1:8443 ) > "$EV/build.log" 2>&1 \
  || { echo "assembleBenchmark failed" >&2; tail -30 "$EV/build.log" >&2; exit 1; }
APK="$(find "$H/build/outputs/apk/benchmark" -name '*.apk' | head -1)"
ID="$(sed -nE "s/^ *applicationId '([^']+)'.*/\1/p" "$H/build.gradle" | head -1)"
CMP="$(cd "$APP" && aapt2_="$(ls "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/aapt2 | tail -1)"; "$aapt2_" dump badging "$APK" 2>/dev/null | sed -nE "s/^launchable-activity: name='([^']+)'.*/\1/p")"
[ -n "$ID" ] && [ -n "$CMP" ] || { echo "could not read the application id or the launcher activity" >&2; exit 1; }
{ echo "apk $(shasum -a 256 "$APK" | cut -d' ' -f1) $(wc -c < "$APK" | tr -d ' ') bytes"; echo "device $(adb_ shell getprop ro.product.model | tr -d '\r') Android $(adb_ shell getprop ro.build.version.release | tr -d '\r')"; } | tee "$EV/m2-android.env"

python3 "$CI/w3/static_https.py" "$SITE" 8443 "$TLS/server.pem" "$TLS/server.key" "$EV/server.log" &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; adb_ reverse --remove tcp:8443 >/dev/null 2>&1' EXIT
for _ in $(seq 1 30); do curl -sf --cacert "$TLS/ca.pem" -m 2 -o /dev/null https://127.0.0.1:8443/bundles/index.json && break; sleep 1; done
adb_ reverse tcp:8443 tcp:8443 > /dev/null
adb_ install -r "$APK" > "$EV/install.log" 2>&1 || { echo "install failed: $(cat "$EV/install.log")" >&2; exit 1; }

at(){ sed -n "s/.*KeliverTiming: $2 \([0-9][0-9]*\)\r*$/\1/p" "$1" | tail -1; }
wait_content(){  # $1 logcat file: up to 30 s for the content mark
  for _ in $(seq 1 60); do
    adb_ logcat -d > "$1"
    grep -q "KeliverTiming: content [0-9]" "$1" && { sleep 1; adb_ logcat -d > "$1"; return 0; }
    sleep 0.5
  done
  return 1
}
pss(){ adb_ shell dumpsys meminfo "$ID" | sed -nE 's/^ *TOTAL PSS: *([0-9]+).*/\1/p; s/^ *TOTAL: *([0-9]+).*/\1/p' | head -1; }
ms(){ [ -n "$1" ] && [ -n "$2" ] && echo $(( ($2 - $1) / 1000000 )) || echo ""; }
TSV="$EV/m2-android.tsv"
printf 'scenario\trun\tprocess_open\tprocess_code\tprocess_first_frame\tprocess_content\topen_first_frame\topen_content\tpss_kb\n' > "$TSV"
row(){  # $1 scenario $2 run $3 logcat
  local p o c f t
  p="$(at "$3" process)"; o="$(at "$3" open)"; c="$(at "$3" code)"; f="$(at "$3" first-frame)"; t="$(at "$3" content)"
  [ "$1" = warm ] && { p=""; c=""; }
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$(ms "$p" "$o")" "$(ms "$p" "$c")" "$(ms "$p" "$f")" "$(ms "$p" "$t")" \
    "$(ms "$o" "$f")" "$(ms "$o" "$t")" "$(pss)" >> "$TSV"
}
launch(){ adb_ shell am start -W -n "$ID/$CMP" "$@" > /dev/null; }

for r in $(seq 1 "$RUNS"); do
  # first: no data, nothing cached
  adb_ shell am force-stop "$ID"; adb_ shell pm clear "$ID" > /dev/null; adb_ logcat -c
  launch; L="$EV/first-$r.txt"; wait_content "$L" && row first "$r" "$L" || echo "first $r: no content mark" | tee -a "$EV/m2-android.errors"
  # cold: the process stopped, the bundle cached
  sleep 2; adb_ shell am force-stop "$ID"; sleep 2; adb_ logcat -c
  launch; L="$EV/cold-$r.txt"; wait_content "$L" && row cold "$r" "$L" || echo "cold $r: no content mark" | tee -a "$EV/m2-android.errors"
  pid="$(adb_ shell pidof "$ID" | tr -d '\r')"
  # warm: the same process, a new task (so a new screen); the old one is cleared
  sleep 3; adb_ logcat -c
  launch -f 0x10008000; L="$EV/warm-$r.txt"; wait_content "$L" && row warm "$r" "$L" || echo "warm $r: no content mark" | tee -a "$EV/m2-android.errors"
  [ "$(adb_ shell pidof "$ID" | tr -d '\r')" = "$pid" ] || echo "warm $r: the process changed" | tee -a "$EV/m2-android.errors"
done
adb_ shell am force-stop "$ID"
column -t -s "$(printf '\t')" "$TSV"
