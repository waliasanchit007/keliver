#!/usr/bin/env bash
# Before an ios-host job on a SELF-HOSTED runner (a developer's Mac): remove what
# an earlier, cancelled run of this harness can leave behind, and nothing else.
#   - an earlier job's ios.sh still running under this runner's work dir (its job lost the runner);
#   - simulators a CI run of ios.sh created (keliver-ios-ci-<run id>, or the
#     older unnumbered keliver-ios-ci); ios.sh deletes them on exit, a cancelled
#     run may not get to. A local run's keliver-ios-ci-local is left alone;
#   - processes listening on the harness's ports (the relay on
#     KELIVER_RELAY_PORT and its editor at +19, the static HTTPS servers on
#     8443-8445) whose command line lies under this runner's work directory.
#     A process outside it (someone's own portal) is reported and left alone.
# The work-dir match covers any job of this runner. That is safe because one
# runner instance runs one job at a time; a second runner instance on the same
# machine would need its own work dir passed here.
# Usage: reset.sh <runner work dir> <relay port>
set -uo pipefail
work="${1:?runner work dir}"; port="${2:?relay port}"
xcrun simctl list devices -j 2>/dev/null | python3 -c '
import json, sys
for runtime, devs in json.load(sys.stdin).get("devices", {}).items():
    for d in devs:
        n = d.get("name", "")
        if n == "keliver-ios-ci" or (n.startswith("keliver-ios-ci-") and n[len("keliver-ios-ci-"):].isdigit()):
            print(d["udid"])' | while read -r udid; do
  xcrun simctl shutdown "$udid" >/dev/null 2>&1
  xcrun simctl delete "$udid" >/dev/null 2>&1 && echo "reset: deleted a leftover simulator $udid"
done
# An orphaned harness script: a job whose runner lost its connection leaves ios.sh running,
# still using the simulator and the ports (its parent may be launchd, or the machine's lock
# wrapper, which then releases the lock when ios.sh exits). This runs at the start of a new
# job, and one runner runs one job at a time, so any ios.sh under this runner's work dir is
# an earlier job's. It is stopped; its own EXIT trap deletes its simulator and relay.
for pid in $(pgrep -f "reference/inventory/ci/ios.sh" 2>/dev/null); do
  case "$(ps -o command= -p "$pid" 2>/dev/null)" in
    *"$work"*) kill "$pid" 2>/dev/null && echo "reset: stopped an earlier job's ios.sh (pid $pid)" ;;
  esac
done
sleep 5
for p in "$port" $((port + 19)) 8443 8444 8445; do
  for pid in $(lsof -nP -t -iTCP:"$p" -sTCP:LISTEN 2>/dev/null); do
    cmd="$(ps -o command= -p "$pid" 2>/dev/null)"
    case "$cmd" in
      *"$work"*) kill "$pid" 2>/dev/null && echo "reset: stopped a leftover harness process on :$p (pid $pid)" ;;
      *) echo "reset: :$p is taken by a process outside this runner's work dir (pid $pid); left alone, the run will refuse it" ;;
    esac
  done
done
exit 0
