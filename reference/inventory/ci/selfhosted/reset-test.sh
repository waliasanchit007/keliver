#!/usr/bin/env bash
# reset.sh's own test, with stand-ins: a fake xcrun lists no simulators (none is
# touched), and the only processes reset.sh may stop are this test's, under a
# scratch "work dir". Safe on the self-hosted runner itself.
#   R1  a relative, empty or root work dir is refused (exit 2)
#   R2  with no orphan, nothing is stopped and there is no wait
#   R3  an orphaned ios.sh under its lock wrapper, with a child and a grandchild
#       (the foreground build), and an orphaned self-test, are stopped whole:
#       every process exits, both scripts' EXIT traps ran, and the lock wrapper's
#       ran after ios.sh's (the lock is not released under a running trap)
#   R4  a matching script under a sibling dir (<work>2/) is left alone
#   R5  a process that only names the script (tail -f <work>/.../ios.sh, as a
#       pager or editor would) is left alone: only bash running it is a harness
# Usage: reset-test.sh [scratch dir]
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
T="${1:-$(mktemp -d "${TMPDIR:-/tmp}/keliver-reset-test.XXXXXX")}"
mkdir -p "$T" && T="$(cd "$T" && pwd -P)"
fails=0
ok() { echo "PASS $*"; }
bad() { echo "FAIL $*"; fails=$((fails + 1)); }
alive() { # a pid that has exited (or is a zombie) is not alive
  local s; s="$(ps -o stat= -p "$1" 2>/dev/null | tr -d ' ')"
  [ -n "$s" ] && [ "${s#Z}" = "$s" ]
}
spawned=()
cleanup() { [ "${#spawned[@]}" -gt 0 ] && kill -KILL "${spawned[@]}" 2>/dev/null; return 0; }
trap cleanup EXIT

mkdir -p "$T/bin"
cat > "$T/bin/xcrun" <<'EOF'
#!/bin/sh
# Stand-in: no simulators, and nothing to delete.
[ "$1 $2" = "simctl list" ] && echo '{"devices": {}}'
exit 0
EOF
chmod +x "$T/bin/xcrun"
export PATH="$T/bin:$PATH"
PORT=59478 # nothing of this test listens: the port sweep only reports

# A stand-in harness script: records its EXIT trap, runs a child that runs a
# grandchild in the foreground, as ios.sh runs xcodebuild or gradle.
harness() { # <path>
  mkdir -p "$(dirname "$1")"
  cat > "$1" <<'EOF'
#!/usr/bin/env bash
trap 'echo "trap $0" >> "$EVENTS"' EXIT
sh -c 'echo $$ > "$0.child"; sleep 300; echo "the child outlived its kill" >> "$EVENTS"' "$0"
EOF
}
cat > "$T/lock-wrapper.sh" <<'EOF'
#!/usr/bin/env bash
# Stand-in for the machine's lock wrapper: runs its command, releases on exit.
name="$1"; shift
trap 'echo "released $name" >> "$EVENTS"' EXIT
"$@"
EOF
export EVENTS="$T/events"
: > "$EVENTS"

# R1
for w in "" "." "relative/dir" "/"; do
  out="$(bash "$HERE/reset.sh" "$w" "$PORT" 2>&1)"; rc=$?
  if [ "$rc" = 2 ]; then ok "R1: work dir '$w' refused"; else bad "R1: work dir '$w' gave exit $rc: $out"; fi
done

# R2
W="$T/work"
mkdir -p "$W/_temp"
start=$SECONDS
out="$(bash "$HERE/reset.sh" "$W" "$PORT" 2>&1)"; rc=$?
if [ "$rc" = 0 ] && ! grep -q "stopping\|exited\|outlived" <<<"$out" && [ $((SECONDS - start)) -lt 5 ]; then
  ok "R2: no orphan, nothing stopped ($((SECONDS - start)) s)"
else
  bad "R2: exit $rc after $((SECONDS - start)) s: $out"
fi

# R3 + R4
IOS="$W/_temp/a/reference/inventory/ci/ios.sh"
SELF="$W/_temp/b/scripts/keliver-new-ios-host-selftest.sh"
OTHER="$T/work2/_temp/c/reference/inventory/ci/ios.sh"
harness "$IOS"; harness "$SELF"; harness "$OTHER"
bash "$T/lock-wrapper.sh" ci:test bash "$IOS" "$W/_temp/ios" & wrapper=$!
bash "$SELF" "$W/_temp/selftest" & selftest=$!
bash "$OTHER" "$T/work2/_temp/ios" & other=$!
VIEWED="$W/_temp/d/reference/inventory/ci/ios.sh"
harness "$VIEWED"
tail -f "$VIEWED" >/dev/null & viewer=$!
spawned+=("$wrapper" "$selftest" "$other" "$viewer")
# Up to 20 s: a loaded shared machine can be slow to start three bash trees.
for _ in $(seq 1 200); do [ -s "$IOS.child" ] && [ -s "$SELF.child" ] && [ -s "$OTHER.child" ] && break; sleep 0.1; done
ios="$(pgrep -P "$wrapper" | head -1)"
ios_child="$(cat "$IOS.child" 2>/dev/null)"; self_child="$(cat "$SELF.child" 2>/dev/null)"
other_child="$(cat "$OTHER.child" 2>/dev/null)"
ios_grand="$(pgrep -P "${ios_child:-0}" | head -1)"; self_grand="$(pgrep -P "${self_child:-0}" | head -1)"
other_grand="$(pgrep -P "${other_child:-0}" | head -1)"
for p in "$ios" "$ios_child" "$ios_grand" "$self_child" "$self_grand" "$other_child" "$other_grand"; do
  [ -n "$p" ] && spawned+=("$p")
done
if [ -z "$ios" ] || [ -z "$ios_grand" ] || [ -z "$self_grand" ] || [ -z "$other_grand" ]; then
  bad "R3: the stand-in trees did not start (wrapper $wrapper ios '$ios' grandchildren '$ios_grand' '$self_grand')"
else
  out="$(bash "$HERE/reset.sh" "$W" "$PORT" 2>&1)"; rc=$?
  while IFS= read -r line; do echo "  | $line"; done <<<"$out"
  left=""
  for p in $wrapper $ios $ios_child $ios_grand $selftest $self_child $self_grand; do alive "$p" && left="$left $p"; done
  if [ "$rc" = 0 ] && [ -z "$left" ]; then ok "R3: both orphaned trees are gone (wrapper, scripts, children, grandchildren)"; else bad "R3: exit $rc; still running:$left"; fi
  if grep -qxF "trap $IOS" "$EVENTS" && grep -qxF "trap $SELF" "$EVENTS"; then
    ok "R3: the scripts' EXIT traps ran"
  else
    bad "R3: the scripts' EXIT traps did not run: $(tr '\n' ';' < "$EVENTS")"
  fi
  if grep -qxF "released ci:test" "$EVENTS"; then ok "R3: the lock wrapper's EXIT trap ran (the lock is released)"; else bad "R3: the lock wrapper's EXIT trap did not run"; fi
  # The wrapper is signalled only after its script's trap has finished.
  if [ "$(grep -nxF "trap $IOS" "$EVENTS" | cut -d: -f1)" -lt "$(grep -nxF "released ci:test" "$EVENTS" | cut -d: -f1)" ] 2>/dev/null; then
    ok "R3: the lock is released after ios.sh's EXIT trap"
  else
    bad "R3: the lock was released before ios.sh's EXIT trap: $(tr '\n' ';' < "$EVENTS")"
  fi
  if grep -q "outlived" "$EVENTS"; then bad "R3: a child carried on after its kill"; fi
  if alive "$other" && alive "$other_child" && alive "$other_grand"; then ok "R4: the script under work2/ is left alone"; else bad "R4: the script under work2/ was stopped"; fi
  if alive "$viewer"; then ok "R5: tail -f of a harness script under the work dir is left alone"; else bad "R5: tail -f of $VIEWED was stopped"; fi
fi

echo
if [ "$fails" = 0 ]; then echo "reset-test: all passed"; else echo "reset-test: $fails failed"; fi
[ "$fails" = 0 ]
