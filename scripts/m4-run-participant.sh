#!/usr/bin/env bash
#
# m4-run-participant — launch ONE M4 participant with a pinned, recorded
# configuration and a retained tool-call trace.
#
#   scripts/m4-run-participant.sh <trial-dir> <baseline|semantic> [--model M]
#
# WHY THIS EXISTS. The first pilot launched its two participants by hand and
# they were not comparable: baseline ran `--model sonnet` with an explicit
# allowed-tool list, semantic ran with no --model (so the user default, opus)
# and --permission-mode bypassPermissions. The semantic run's resolved model
# was never recorded, so "equal models" could not even be checked after the
# fact. Every knob is now set here, identically for both conditions, and
# written to <cond>-config.json.
#
# THE ONLY INTENDED DIFFERENCE between the conditions is that `semantic` loads
# the portal MCP server and may call its tools.
#
# It also keeps the audit trail. The first pilot could only report "the
# participant says it used no portal tool", because ~/.claude/projects is
# denied inside the sandbox and no transcript survived. The controller now
# captures --output-format stream-json into reports/<cond>-stream.jsonl and
# derives reports/<cond>-toolcalls.txt from it. Denying a participant access to
# its own history does not require losing the record: the controller holds the
# fd, the sandbox only blocks the participant from opening the path.
#
set -uo pipefail
T="${1:-}"; COND="${2:-}"; shift 2 2>/dev/null || true
MODEL="sonnet"
while [ $# -gt 0 ]; do
  case "$1" in
    --model) [ $# -ge 2 ] || { echo "--model needs a value" >&2; exit 2; }; MODEL="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$T" ] && [ -d "$T" ] || { echo "usage: $0 <trial-dir> <baseline|semantic> [--model M]" >&2; exit 2; }
case "$COND" in baseline|semantic) ;; *) echo "condition must be baseline or semantic" >&2; exit 2 ;; esac
T="$(cd "$T" && pwd -P)"

WS="$T/ws-$COND"
PROFILE="$T/sandbox-$COND.sb"
TASK="$T/evaluator/task.txt"
for f in "$WS" "$PROFILE" "$TASK"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; exit 2; }
done
mkdir -p "$T/reports"

# --- identical configuration for both conditions ------------------------------
TOOLS=(Bash Read Edit Write Glob Grep)
DISALLOWED=(WebSearch WebFetch)
# Baseline gets an EMPTY mcp config rather than no flag at all, so both
# conditions run the same code path and --strict-mcp-config keeps the operator's
# own global MCP servers out of either one.
if [ "$COND" = semantic ]; then
  MCP_CONFIG="$T/runtime/mcp-config.json"
  [ -f "$MCP_CONFIG" ] || { echo "missing mcp config: $MCP_CONFIG" >&2; exit 2; }
  TOOLS+=(mcp__keliver-portal)
else
  MCP_CONFIG="$T/runtime/mcp-config-empty.json"
  mkdir -p "$T/runtime"; echo '{"mcpServers":{}}' > "$MCP_CONFIG"
fi

STREAM="$T/reports/$COND-stream.jsonl"
ERR="$T/reports/$COND-err.txt"
REPORT="$T/reports/$COND-report.txt"
TRACE="$T/reports/$COND-toolcalls.txt"
CONFIG="$T/reports/$COND-config.json"

for f in "$STREAM" "$REPORT" "$TRACE" "$CONFIG"; do
  [ -e "$f" ] && { echo "refusing to overwrite existing $f — move the previous run aside" >&2; exit 3; }
done

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export NODE_EXTRA_CA_CERTS="${NODE_EXTRA_CA_CERTS:-$HOME/.android-certs/full-ca-bundle.pem}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Djavax.net.ssl.trustStore=$HOME/.android-certs/jssecacerts -Djavax.net.ssl.trustStorePassword=changeit}"

echo "m4 participant: $COND"
echo "  model:   $MODEL (pinned; identical for both conditions)"
echo "  tools:   ${TOOLS[*]}"
echo "  mcp:     $MCP_CONFIG"
echo "  stream:  $STREAM"

START=$(date +%s)
( cd "$WS" && sandbox-exec -f "$PROFILE" \
    env -u KELIVER_USE_MAVEN_LOCAL \
    claude -p "$(cat "$TASK")" \
      --model "$MODEL" \
      --allowedTools "${TOOLS[@]}" \
      --disallowedTools "${DISALLOWED[@]}" \
      --mcp-config "$MCP_CONFIG" \
      --strict-mcp-config \
      --output-format stream-json \
      --verbose \
      < /dev/null ) > "$STREAM" 2> "$ERR"
RC=$?
ELAPSED=$(( $(date +%s) - START ))

# --- derive the report, the trace and the resolved configuration --------------
python3 - "$STREAM" "$REPORT" "$TRACE" "$CONFIG" "$COND" "$MODEL" "$RC" "$ELAPSED" <<'PY'
import json, sys, collections
stream, report, trace, config, cond, asked_model, rc, elapsed = sys.argv[1:9]
events, bad = [], 0
for line in open(stream, encoding='utf-8', errors='replace'):
    line = line.strip()
    if not line:
        continue
    try:
        events.append(json.loads(line))
    except json.JSONDecodeError:
        bad += 1

resolved_model, result_text, usage = None, None, {}
calls = []
for e in events:
    if e.get('type') == 'system' and e.get('subtype') == 'init':
        resolved_model = e.get('model') or resolved_model
    if e.get('type') == 'result':
        result_text = e.get('result') or result_text
        usage = e.get('usage') or {}
        resolved_model = resolved_model or e.get('model')
    msg = e.get('message') or {}
    for block in (msg.get('content') or []):
        if isinstance(block, dict) and block.get('type') == 'tool_use':
            calls.append(block.get('name', '?'))

counts = collections.Counter(calls)
mcp = {k: v for k, v in counts.items() if k.startswith('mcp__')}

with open(report, 'w') as f:
    f.write(result_text or '(no result event in the stream)\n')

with open(trace, 'w') as f:
    f.write(f"condition:      {cond}\n")
    f.write(f"total calls:    {len(calls)}\n")
    f.write(f"unparsed lines: {bad}\n\n")
    for name, n in counts.most_common():
        f.write(f"  {n:4d}  {name}\n")
    f.write("\nportal MCP calls: ")
    f.write("NONE\n" if not mcp else "\n" + "".join(f"  {v:4d}  {k}\n" for k, v in mcp.items()))
    f.write("\nCall order:\n")
    for i, name in enumerate(calls, 1):
        f.write(f"  {i:3d}. {name}\n")

json.dump({
    "condition": cond,
    "model_requested": asked_model,
    "model_resolved": resolved_model,
    "exit_code": int(rc),
    "elapsed_seconds": int(elapsed),
    "tool_calls_total": len(calls),
    "tool_calls_by_name": dict(counts),
    "portal_mcp_calls": mcp,
    "usage": usage,
    "stream_events": len(events),
    "unparsed_lines": bad,
}, open(config, 'w'), indent=2)

print(f"  resolved model: {resolved_model}")
print(f"  exit {rc}, {elapsed}s, {len(calls)} tool calls, portal MCP calls: {sum(mcp.values())}")
PY

echo "  report:  $REPORT"
echo "  trace:   $TRACE"
echo "  config:  $CONFIG"
exit "$RC"
