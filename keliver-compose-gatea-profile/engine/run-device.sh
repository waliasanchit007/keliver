#!/bin/bash
# Runs the engine comparison of docs/GUEST_PROFILE.md "The engine, measured" on an Android arm64
# device, with the Mac's matrix: 2021 -Os as shipped; 2026 (fixed) -Os and -O2, as shipped and
# with Long as BigInt; each in every recording mode; 3 rounds, the order reversed in round 2;
# 3 warm-up and 30 measured iterations; run.js's median rule.
#
# Usage: run-device.sh [-s SERIAL] <command> [args]      (SERIAL defaults to $ANDROID_SERIAL; required)
#   devices                                        list attached devices (no serial needed)
#   emulator-start <avd> / emulator-stop <pid>     a headless arm64 emulator for a rehearsal (no serial needed)
#   push <engines dir> <as-shipped dir> <bigint dir>
#                                                  <engines dir>/<build>/qjs from build-engines-android.sh;
#                                                  the two dirs hold the Kotlin/JS production executable
#                                                  (*.js). Replaces /data/local/tmp/keliver-engine.
#   start [--rounds N] [--warmup N] [--iterations N] [--modes "kotlinx direct modcache none"]
#         [--cooldown SECONDS] [--stay-awake]
#                                                  starts the on-device driver (setsid nohup sh), which
#                                                  keeps running if adb drops; --stay-awake keeps the screen
#                                                  on while plugged in and restores the setting at the end
#   status                                         progress, and whether the driver is alive
#   wait [--poll SECONDS]                          polls status until the driver finishes
#   pull <local dir>                               copies the device's results/ to <local dir>
#   check <local dir> <Mac combine.jsonl>          hashes against the Mac runs, missing runs, summary
#   stop                                           stops a running driver (results stay)
#   cleanup                                        stops the driver, restores the stay-awake setting,
#                                                  removes /data/local/tmp/keliver-engine
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
D=/data/local/tmp/keliver-engine
BUILDS="q21-Os q26fix-Os q26fix-O2"
serial="${ANDROID_SERIAL:-}"
if [ "${1:-}" = -s ]; then serial="${2:?-s needs a serial}"; shift 2; fi
cmd="${1:?usage: run-device.sh [-s SERIAL] devices|push|start|status|wait|pull|check|stop|cleanup}"; shift
adb_bin="adb"
[ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ] && adb_bin="$ANDROID_HOME/platform-tools/adb"
if [ "$cmd" = devices ]; then exec "$adb_bin" devices -l; fi
if [ "$cmd" = emulator-start ]; then # <avd>: a headless, read-only emulator for a rehearsal
  avd="${1:?emulator-start <avd>}"
  emu="${ANDROID_HOME:-$HOME/Library/Android/sdk}/emulator/emulator"
  before="$("$adb_bin" devices | awk '/^emulator-/{print $1}')"
  log="${TMPDIR:-/tmp}/keliver-emulator-$avd.log"
  nohup "$emu" -avd "$avd" -no-window -no-audio -no-boot-anim -no-snapshot-save -read-only > "$log" 2>&1 < /dev/null &
  pid=$!
  new=""
  for _ in $(seq 1 90); do
    kill -0 "$pid" 2>/dev/null || { echo "the emulator exited; see $log" >&2; exit 1; }
    for s in $("$adb_bin" devices | awk '/^emulator-/{print $1}'); do
      case " $(echo $before) " in *" $s "*) ;; *) new="$s";; esac
    done
    if [ -n "$new" ] && [ "$("$adb_bin" -s "$new" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
      echo "emulator $avd booted: serial $new, pid $pid (stop it with: emulator-stop $pid)"; exit 0
    fi
    sleep 4
  done
  echo "the emulator did not boot in 6 minutes (pid $pid, log $log)" >&2; exit 1
fi
if [ "$cmd" = emulator-stop ]; then # <pid>: stops only that emulator process
  pid="${1:?emulator-stop <pid>}"
  ps -p "$pid" -o command= | grep -q 'emulator\|qemu' || { echo "$pid is not an emulator" >&2; exit 1; }
  kill "$pid"; echo "stopped emulator pid $pid"; exit 0
fi
[ -n "$serial" ] || { echo "no device serial: pass -s SERIAL or set ANDROID_SERIAL (see: run-device.sh devices)" >&2; exit 2; }
adb() { "$adb_bin" -s "$serial" "$@"; }
sh_dev() { adb shell "$1" | tr -d '\r'; }
driver_alive() { # prints the pid when the driver runs
  sh_dev "p=\$(cat $D/driver.pid 2>/dev/null) && [ -n \"\$p\" ] && grep -q driver.sh /proc/\$p/cmdline 2>/dev/null && echo \$p" || true
}

case "$cmd" in
push)
  engines="${1:?push <engines dir> <as-shipped dir> <bigint dir>}"; ws="${2:?as-shipped dir}"; wb="${3:?bigint dir}"
  [ "$(adb get-state)" = device ] || { echo "$serial is not ready" >&2; exit 1; }
  abi="$(sh_dev 'getprop ro.product.cpu.abi')"
  [ "$abi" = arm64-v8a ] || { echo "$serial is $abi; these binaries are arm64-v8a" >&2; exit 1; }
  [ -z "$(driver_alive)" ] || { echo "a driver is running on $serial; run: stop or cleanup" >&2; exit 1; }
  for b in $BUILDS; do [ -f "$engines/$b/qjs" ] || { echo "missing $engines/$b/qjs" >&2; exit 1; }; done
  for w in "$ws" "$wb"; do [ -f "$w/guest-profile.js" ] || { echo "$w has no guest-profile.js" >&2; exit 1; }; done
  stage="$(mktemp -d)"; trap 'rm -rf "$stage"' EXIT
  mkdir -p "$stage/workload/as-shipped" "$stage/workload/bigint" "$stage/bin"
  cp "$ws"/*.js "$stage/workload/as-shipped/"; cp "$wb"/*.js "$stage/workload/bigint/"
  for b in $BUILDS; do mkdir -p "$stage/bin/$b"; cp "$engines/$b/qjs" "$stage/bin/$b/qjs"; done
  cp "$here/run.js" "$here/device-driver.sh" "$stage/"
  sh_dev "rm -rf $D && mkdir -p $D"
  for item in bin workload run.js device-driver.sh; do adb push "$stage/$item" "$D/" > /dev/null; done
  sh_dev "chmod 755 $D/bin/*/qjs $D/device-driver.sh"
  # The copies on the device are the files built here, and each engine starts.
  for b in $BUILDS; do
    want="$(shasum -a 256 "$engines/$b/qjs" | awk '{print $1}')"
    got="$(sh_dev "sha256sum $D/bin/$b/qjs" | awk '{print $1}')"
    [ "$want" = "$got" ] || { echo "$b: device copy differs ($got)" >&2; exit 1; }
    ok="$(sh_dev "cd $D && ./bin/$b/qjs -e \"print('qjs-ok')\"" 2>&1)"
    [ "$ok" = qjs-ok ] || { echo "$b does not start on $serial: $ok" >&2; exit 1; }
  done
  n="$(sh_dev "ls $D/workload/as-shipped | wc -l" | tr -d ' ')"; m="$(sh_dev "ls $D/workload/bigint | wc -l" | tr -d ' ')"
  echo "pushed to $serial:$D — engines: $BUILDS; workloads: as-shipped $n files, bigint $m files"
  ;;
start)
  rounds=3 warmup=3 iterations=30 modes="kotlinx direct modcache none" cooldown=0 stay=0
  while [ $# -gt 0 ]; do case "$1" in
    --rounds) rounds="$2"; shift 2;; --warmup) warmup="$2"; shift 2;; --iterations) iterations="$2"; shift 2;;
    --modes) modes="$2"; shift 2;; --cooldown) cooldown="$2"; shift 2;; --stay-awake) stay=1; shift;;
    *) echo "unknown option $1" >&2; exit 2;; esac; done
  [ -z "$(driver_alive)" ] || { echo "a driver is already running on $serial" >&2; exit 1; }
  sh_dev "[ -x $D/device-driver.sh ] && [ -x $D/bin/q21-Os/qjs ]" || { echo "nothing pushed to $serial; run push first" >&2; exit 1; }
  # setsid + nohup + no adb stdio: the driver survives a dropped (wireless) adb connection.
  # (Only the driver is backgrounded, with every fd redirected; otherwise adb shell waits for it.)
  sh_dev "cd $D && rm -rf results && mkdir results"
  "$adb_bin" -s "$serial" shell -n "cd $D; setsid nohup sh ./device-driver.sh $rounds $warmup $iterations $cooldown $stay $modes > results/driver.log 2>&1 < /dev/null &"
  sleep 3
  pid="$(driver_alive)"
  [ -n "$pid" ] || { echo "the driver did not start:" >&2; sh_dev "cat $D/results/driver.log" >&2; exit 1; }
  nm=$(echo $modes | wc -w | tr -d ' ')
  echo "driver pid $pid on $serial: $rounds rounds x 5 engine/workload pairs x $nm modes = $((rounds * 5 * nm)) runs"
  ;;
status)
  pid="$(driver_alive)"
  sh_dev "cd $D/results 2>/dev/null && { echo \"runs: \$(grep -c . combine.jsonl 2>/dev/null) (errors: \$(grep -c '\"error\"' combine.jsonl 2>/dev/null))\"; tail -3 progress.log 2>/dev/null; if [ -f DONE ]; then echo DONE; fi; } || echo 'no results on the device'"
  if [ -n "$pid" ]; then echo "driver running (pid $pid)"; else echo "driver not running"; fi
  ;;
wait)
  poll=60; [ "${1:-}" = --poll ] && poll="${2:-60}"
  while :; do
    if sh_dev "[ -f $D/results/DONE ] && echo y" | grep -q y; then echo "done"; sh_dev "tail -1 $D/results/progress.log"; exit 0; fi
    st="$("$adb_bin" -s "$serial" get-state 2>/dev/null || true)"
    if [ "$st" = device ] && [ -z "$(driver_alive)" ]; then echo "driver stopped without finishing" >&2; exit 1; fi
    [ "$st" = device ] && sh_dev "tail -1 $D/results/progress.log 2>/dev/null" || echo "$(date +%H:%M:%S) $serial not reachable (the driver keeps running on the device)"
    sleep "$poll"
  done
  ;;
pull)
  dest="${1:?pull <local dir>}"
  [ ! -e "$dest" ] || { echo "$dest exists; pull into a new directory" >&2; exit 1; }
  sh_dev "[ -f $D/results/DONE ]" || echo "warning: the run has not finished (pulling what is there)" >&2
  adb pull "$D/results" "$dest" > /dev/null   # $dest does not exist, so it becomes a copy of results/
  echo "pulled to $dest: $(grep -c . "$dest/combine.jsonl" 2>/dev/null || echo 0) runs"
  ;;
check)
  python3 -I "$here/device-check.py" "${1:?check <local dir> <Mac combine.jsonl>}" "${2:?Mac combine.jsonl}"
  ;;
stop|cleanup)
  pid="$(driver_alive)"
  if [ -n "$pid" ]; then sh_dev "pkill -P $pid; kill $pid"; echo "stopped driver $pid"; fi
  if [ "$cmd" = cleanup ]; then
    old="$(sh_dev "cat $D/results/stay_on.old 2>/dev/null" || true)"
    if [ -n "$old" ]; then sh_dev "settings put global stay_on_while_plugged_in $old"; echo "restored stay_on_while_plugged_in=$old"; fi
    sh_dev "rm -rf $D"
    echo "removed $serial:$D"
  fi
  ;;
*) echo "unknown command $cmd" >&2; exit 2;;
esac
