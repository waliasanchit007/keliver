#!/usr/bin/env bash
# Before an ios-host job on a SELF-HOSTED runner (a developer's Mac): remove what
# an earlier, cancelled run of this harness can leave behind, and nothing else.
#   - simulators named keliver-ios-ci* (ios.sh creates them and deletes them on
#     exit; a cancelled run may not get to);
#   - processes listening on the harness's ports (the relay on
#     KELIVER_RELAY_PORT and its editor at +19, the static HTTPS servers on
#     8443-8445) whose command line lies under this runner's work directory.
#     A process outside it (someone's own portal) is reported and left alone.
# Usage: reset.sh <runner work dir> <relay port>
set -uo pipefail
work="${1:?runner work dir}"; port="${2:?relay port}"
xcrun simctl list devices -j 2>/dev/null | python3 -c '
import json, sys
for runtime, devs in json.load(sys.stdin).get("devices", {}).items():
    for d in devs:
        if d.get("name", "").startswith("keliver-ios-ci"):
            print(d["udid"])' | while read -r udid; do
  xcrun simctl shutdown "$udid" >/dev/null 2>&1
  xcrun simctl delete "$udid" >/dev/null 2>&1 && echo "reset: deleted a leftover simulator $udid"
done
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
