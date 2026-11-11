#!/usr/bin/env bash
# Before an ios-host job on a SELF-HOSTED runner (a developer's Mac): remove what
# an earlier, cancelled run of this harness can leave behind, and nothing else.
#   - an earlier job's harness script (ios.sh or the iOS host self-test) still
#     running under this runner's work dir (its job lost the runner), with
#     every process below it;
#   - simulators a CI run of ios.sh created (keliver-ios-ci-<run id>-<attempt>);
#     ios.sh deletes them on exit, a cancelled run may not get to. A local run's
#     keliver-ios-ci-local is left alone;
#   - processes listening on the harness's ports (the relay on
#     KELIVER_RELAY_PORT and its editor at +19, the static HTTPS servers on
#     8443-8445) whose command line lies under this runner's work directory.
#     A process outside it (someone's own portal) is reported and left alone.
# The work-dir match covers any job of this runner. That is safe because one
# runner instance runs one job at a time; a second runner instance on the same
# machine would need its own work dir passed here.
# Usage: reset.sh <runner work dir> <relay port>
# Test: reset-test.sh (stand-ins; touches no simulator).
set -uo pipefail
[ $# -eq 2 ] || { echo "usage: reset.sh <runner work dir> <relay port>" >&2; exit 2; }
work="${1%/}"; port="$2"
# Every match below is "under $work/": a relative or empty work dir (an unset
# RUNNER_TEMP gives ".") would match far more than this runner's jobs.
case "$work" in
  /?*) ;;
  *) echo "reset: the runner work dir must be an absolute path, not '$1'" >&2; exit 2 ;;
esac
running() { # <pid>: still running (a zombie has exited; its parent just hasn't reaped it)
  local s; s="$(ps -o stat= -p "$1" 2>/dev/null | tr -d ' ')"
  [ -n "$s" ] && [ "${s#Z}" = "$s" ]
}

# ── An orphaned harness script ───────────────────────────────────────────────
# A job whose runner lost its connection leaves its script running, still using the
# simulator, the ports and the machine's build lock. Its parent may be launchd, or the
# lock wrapper, whose command line names the same script (so it matches too) and whose
# EXIT trap releases the lock. This runs at the start of a new job, and one runner runs
# one job at a time, so any such script under this runner's work dir is an earlier job's.
#
# The whole tree goes, children first: stopping only the scripts would leave their
# foreground child (xcodebuild, gradle, swift, curl) running, with the lock released
# under it. The walk follows parent pids (pgrep -P), never a process group, which could
# reach the runner itself. Each process is frozen (SIGSTOP) before its children are
# listed, so none can start another while the tree is taken; then each gets SIGTERM and
# SIGCONT, deepest first, so the scripts' EXIT traps (ios.sh deletes its simulator and
# stops its relay) run after their children are gone. The topmost process is signalled
# only once the rest of its tree has exited (up to 60 s), then gets 15 s itself.
freeze() { # <pid>: stops it and every process below it; prints them, parents first
  kill -STOP "$1" 2>/dev/null || return 0
  echo "$1"
  local child
  for child in $(pgrep -P "$1" 2>/dev/null); do freeze "$child"; done
}
runs_harness() { # <command line>: bash runs a harness script (bash itself, or the lock
  # wrapper whose arguments are "bash <script> ..."). A pager, editor or tail with the
  # script's path among its arguments has no "bash <script>" pair, so it is left alone.
  local -a w; local i
  read -ra w <<<"$1"
  for ((i = 0; i + 1 < ${#w[@]}; i++)); do
    case "${w[i]}" in bash | */bash) ;; *) continue ;; esac
    case "${w[i + 1]}" in
      reference/inventory/ci/ios.sh | */reference/inventory/ci/ios.sh) return 0 ;;
      scripts/keliver-new-ios-host-selftest.sh | */scripts/keliver-new-ios-host-selftest.sh) return 0 ;;
    esac
  done
  return 1
}
ours=" "
for pid in $(pgrep -f 'reference/inventory/ci/ios\.sh|scripts/keliver-new-ios-host-selftest\.sh' 2>/dev/null); do
  cmd="$(ps -o command= -p "$pid" 2>/dev/null)"
  case "$cmd" in *"$work/"*) runs_harness "$cmd" && ours="$ours$pid " ;; esac
done
tree=(); roots=" "
for pid in $ours; do
  # The lock wrapper and the script it runs both match: take the tree from the topmost.
  case "$ours" in *" $(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ') "*) continue ;; esac
  case " ${tree[*]-} " in *" $pid "*) continue ;; esac
  echo "reset: stopping an earlier job's harness: $(ps -o pid=,command= -p "$pid" 2>/dev/null | cut -c1-200)"
  roots="$roots$pid "
  while read -r p; do tree+=("$p"); done < <(freeze "$pid")
done
signal() { local p; for p in "$@"; do kill -TERM "$p"; kill -CONT "$p"; done 2>/dev/null; }
wait_gone() { # <seconds> <pid>...: until none is running, or the time is up; leaves the rest in alive
  local n="$1" p; shift
  for _ in $(seq 1 "$n"); do
    alive=(); for p in "$@"; do running "$p" && alive+=("$p"); done
    [ "${#alive[@]}" -eq 0 ] && return 0
    sleep 1
  done
}
if [ "${#tree[@]}" -gt 0 ]; then
  # The topmost process of each tree (the lock wrapper, whose EXIT trap releases the
  # lock, or a script with no wrapper) goes last, once everything below it has exited:
  # ios.sh's EXIT trap is still deleting its simulator and stopping its relay until then.
  below=(); top=()
  for ((i = ${#tree[@]} - 1; i >= 0; i--)); do
    case "$roots" in *" ${tree[i]} "*) top+=("${tree[i]}") ;; *) below+=("${tree[i]}") ;; esac
  done
  # Bounded: the scripts' EXIT traps shut down a simulator and a relay.
  if [ "${#below[@]}" -gt 0 ]; then signal "${below[@]}"; wait_gone 60 "${below[@]}"; fi
  signal "${top[@]}"
  wait_gone 15 "${tree[@]}"
  if [ "${#alive[@]}" -gt 0 ]; then
    echo "reset: ${#alive[@]} of its ${#tree[@]} processes outlived their wait; killed: ${alive[*]}"
    kill -KILL "${alive[@]}" 2>/dev/null
  else
    echo "reset: the earlier harness's ${#tree[@]} processes have exited"
  fi
fi

# ── Leftover simulators ──────────────────────────────────────────────────────
# After the orphan is gone, so it can't be mid-way through using one. Also matched, so a
# leftover from an earlier ios.sh is removed (a one-time migration; drop these once the
# runner has run this reset): plain keliver-ios-ci (ios.sh before 2026-11-10; a simulator
# someone names that by hand goes too) and keliver-ios-ci-<run id> (before 2026-11-11,
# when the run attempt was added because a rerun's attempts share the run id).
xcrun simctl list devices -j 2>/dev/null | python3 -c '
import json, re, sys
for runtime, devs in json.load(sys.stdin).get("devices", {}).items():
    for d in devs:
        if re.fullmatch(r"keliver-ios-ci(-[0-9]+(-[0-9]+)?)?", d.get("name", "")):
            print(d["udid"])' | while read -r udid; do
  xcrun simctl shutdown "$udid" >/dev/null 2>&1
  xcrun simctl delete "$udid" >/dev/null 2>&1 && echo "reset: deleted a leftover simulator $udid"
done

# ── Leftover listeners on the harness's ports ────────────────────────────────
for p in "$port" $((port + 19)) 8443 8444 8445; do
  for pid in $(lsof -nP -t -iTCP:"$p" -sTCP:LISTEN 2>/dev/null); do
    cmd="$(ps -o command= -p "$pid" 2>/dev/null)"
    case "$cmd" in
      *"$work/"*) kill "$pid" 2>/dev/null && echo "reset: stopped a leftover harness process on :$p (pid $pid)" ;;
      *) echo "reset: :$p is taken by a process outside this runner's work dir (pid $pid); left alone, the run will refuse it" ;;
    esac
  done
done
exit 0
