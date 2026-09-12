#!/usr/bin/env bash
#
# Mechanics check for m4-run-participant.sh.
#
#   scripts/m4-runner-selftest.sh <template-trial-dir>
#
# Verifies the instrument, not the hypothesis. It builds a throwaway trial from
# an existing one, gives both conditions a short deterministic task, and
# requires:
#
#   * both conditions resolve to the SAME model, and it is recorded
#   * a tool-call trace is retained for both, derived from the controller's own
#     stream capture rather than from anything the participant says
#   * the semantic condition's portal MCP call appears in that trace
#   * the baseline condition records zero portal MCP calls
#   * a second run refuses to overwrite the first run's artifacts
#
# The task deliberately ASKS for the MCP call. This checks that a call would be
# observed if one happened — it is not evidence about whether a participant
# solving a real task chooses to use the channel.
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
SRC="${1:-}"
[ -n "$SRC" ] && [ -d "$SRC/ws-semantic" ] && [ -d "$SRC/runtime" ] || {
  echo "usage: $0 <template-trial-dir>   (needs ws-semantic/ and runtime/)" >&2; exit 2; }
SRC="$(cd "$SRC" && pwd -P)"

WORK="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/m4-runner-XXXXXX")" && pwd -P)"
T="$WORK/trial"
pass=0; fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }

echo "m4 runner mechanics check"
echo "workdir: $T"

mkdir -p "$T/reports" "$T/evaluator"
# APFS clones: instant, no 130MB of copying.
cp -Rc "$SRC/runtime"     "$T/runtime"     2>/dev/null || cp -R "$SRC/runtime"     "$T/runtime"
cp -Rc "$SRC/ws-semantic" "$T/ws-baseline" 2>/dev/null || cp -R "$SRC/ws-semantic" "$T/ws-baseline"
cp -Rc "$SRC/ws-semantic" "$T/ws-semantic" 2>/dev/null || cp -R "$SRC/ws-semantic" "$T/ws-semantic"

cat > "$T/evaluator/task.txt" <<'EOT'
Do exactly these three things and then stop. Do not explore the project.

1. Run `echo mechanics-ok` with Bash.
2. If a tool for reading the portal UI document is available to you, call it
   once for the screen named `home`. If no such tool exists, skip this step.
3. Reply with a single line: DONE

Do not edit any file. Do not build anything.
EOT

profile() {
  cat > "$T/sandbox-$1.sb" <<EOF
(version 1)
(allow default)
(deny file-read* file-write*
  (subpath "$ROOT")
  (subpath "$WORK"))
(allow file-read-metadata (subpath "$WORK") (subpath "$ROOT"))
(allow file-read* file-write*
  (subpath "$T/ws-$1")
  (subpath "$T/runtime"))
EOF
}
profile baseline; profile semantic

# relay + mcp config for the semantic condition
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}"
PORT=8677
# Refuse an occupied port rather than clearing it. Without -sTCP:LISTEN this
# also matched processes holding a CLIENT socket to the port, so the old line
# could kill something that merely talked to :8677.
if lsof -nP -iTCP:$PORT -sTCP:LISTEN -t >/dev/null 2>&1; then
  echo "port $PORT is already in use; stop that process or free the port and re-run" >&2
  exit 2
fi
PORTAL_REPO="$T/ws-semantic" PORTAL_STORE="$WORK/portal-store" \
  "$T/runtime/portal-relay/bin/portal-relay" > "$WORK/relay.log" 2>&1 &
RELAY=$!
trap 'kill "$RELAY" 2>/dev/null' EXIT
for i in $(seq 1 40); do
  PORT="$(grep -oE 'portal-server: :[0-9]+' "$WORK/relay.log" | head -1 | grep -oE '[0-9]+$')"
  [ -n "$PORT" ] && curl -sf -m 2 -o /dev/null "http://localhost:$PORT/screens" && break
  sleep 3
done
if [ -n "${PORT:-}" ]; then ok "relay up on :$PORT"; else bad "relay never came up"; fi
cat > "$T/runtime/mcp-config.json" <<EOF
{"mcpServers":{"keliver-portal":{"command":"$T/runtime/portal-mcp/bin/portal-mcp","args":[],
 "env":{"PORTAL_REPO":"$T/ws-semantic","PORTAL_SERVER":"http://localhost:${PORT:-8577}"}}}}
EOF

for c in baseline semantic; do
  "$ROOT/scripts/m4-run-participant.sh" "$T" "$c" --model sonnet > "$WORK/$c.out" 2>&1
  echo "  ($c exited $?)"
done

cfg() { python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get(sys.argv[2]))" "$T/reports/$1-config.json" "$2" 2>/dev/null; }

BM="$(cfg baseline model_resolved)"; SM="$(cfg semantic model_resolved)"
if [ -n "$BM" ] && [ "$BM" != "None" ] && [ "$BM" = "$SM" ]; then
  ok "both conditions resolved the same model, recorded: $BM"
else
  bad "resolved models differ or were not recorded (baseline=$BM semantic=$SM)"
fi

for c in baseline semantic; do
  n="$(cfg "$c" tool_calls_total)"
  if [ -s "$T/reports/$c-toolcalls.txt" ] && [ "${n:-0}" -gt 0 ] 2>/dev/null; then
    ok "$c: tool-call trace retained ($n calls)"
  else
    bad "$c: no tool-call trace (calls=$n)"
  fi
done

SMCP="$(python3 -c "import json;print(sum(json.load(open('$T/reports/semantic-config.json'))['portal_mcp_calls'].values()))" 2>/dev/null)"
BMCP="$(python3 -c "import json;print(sum(json.load(open('$T/reports/baseline-config.json'))['portal_mcp_calls'].values()))" 2>/dev/null)"
[ "${SMCP:-0}" -ge 1 ] 2>/dev/null && ok "semantic: portal MCP call captured in the trace ($SMCP)" \
                                   || bad "semantic: MCP call NOT captured (a real 'no calls' finding would be unprovable)"
[ "${BMCP:-1}" = 0 ] 2>/dev/null && ok "baseline: zero portal MCP calls, as configured" \
                                 || bad "baseline: portal MCP calls present ($BMCP)"

"$ROOT/scripts/m4-run-participant.sh" "$T" "baseline" --model sonnet > "$WORK/rerun.out" 2>&1
[ $? = 3 ] && ok "a second run refuses to overwrite the first run's artifacts" \
           || bad "a second run overwrote or failed differently"

echo
echo "passed: $pass   failed: $fail"
echo "artifacts: $WORK"
[ "$fail" -eq 0 ]
