#!/usr/bin/env bash
#
# m4-isolation-preflight — prove the participant sandbox before any trial runs.
#
#   scripts/m4-isolation-preflight.sh <trial-dir>
#
# Places harmless sentinels in every location a participant must not read, then
# attempts each read from inside each participant's profile. Every forbidden
# read must be DENIED. Also checks the participant can still build its own
# fixture and, for the semantic condition, run the staged MCP server.
#
# Sentinels are harmless strings. The boundary is never tested by planting a
# real answer key.
#
set -uo pipefail
T="${1:-}"
[ -n "$T" ] && [ -d "$T" ] || { echo "usage: $0 <trial-dir>" >&2; exit 2; }
T="$(cd "$T" && pwd)"
K="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRATCH="$(dirname "$T")"
TASKS="$(dirname "$SCRATCH")/tasks"

pass=0; fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }

# --- plant harmless sentinels -------------------------------------------------
mkdir -p "$T/evaluator" "$T/reports"
S="SENTINEL-$(date +%s)-HARMLESS"
echo "$S evaluator"        > "$T/evaluator/sentinel.txt"
echo "$S report-baseline"  > "$T/reports/baseline-report.txt"
echo "$S report-semantic"  > "$T/reports/semantic-report.txt"
echo "$S prior-experiment" > "$SCRATCH/.m4-prior-sentinel.txt"
echo "$S controller-repo"  > "$K/.m4-controller-sentinel.txt"
trap 'rm -f "$K/.m4-controller-sentinel.txt" "$SCRATCH/.m4-prior-sentinel.txt"' EXIT

deny_read() { # label, profile, path
  local out
  out="$(sandbox-exec -f "$2" /bin/cat "$3" 2>&1)"
  if printf '%s' "$out" | grep -q "SENTINEL-"; then
    bad "$1 — READ SUCCEEDED (leak)"
  elif printf '%s' "$out" | grep -qi "not permitted\|Operation not permitted"; then
    ok "$1 — denied"
  else
    bad "$1 — unexpected: $(printf '%s' "$out" | head -c 80)"
  fi
}

echo "M4 isolation preflight"
echo "trial: $T"

for c in baseline semantic; do
  P="$T/sandbox-$c.sb"
  other=$([ "$c" = baseline ] && echo semantic || echo baseline)
  [ -f "$P" ] || { bad "$c: no sandbox profile"; continue; }
  echo "--- $c"
  deny_read "$c: evaluator area"                "$P" "$T/evaluator/sentinel.txt"
  deny_read "$c: controller repository"         "$P" "$K/.m4-controller-sentinel.txt"
  deny_read "$c: other participant workspace"   "$P" "$T/ws-$other/keliver.portal.json"
  deny_read "$c: other participant's report"    "$P" "$T/reports/$other-report.txt"
  deny_read "$c: own report location"           "$P" "$T/reports/$c-report.txt"
  deny_read "$c: prior experiment output"       "$P" "$SCRATCH/.m4-prior-sentinel.txt"

  # controller history / git objects
  if sandbox-exec -f "$P" /bin/ls "$K/.git" >/dev/null 2>&1; then
    bad "$c: controller git history readable"
  else ok "$c: controller git history denied"; fi

  # own workspace must remain usable
  if sandbox-exec -f "$P" /bin/ls "$T/ws-$c/src" >/dev/null 2>&1; then
    ok "$c: own workspace readable"
  else bad "$c: own workspace NOT readable"; fi
done

# --- runtime capability -------------------------------------------------------
echo "--- runtime"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}"
export NODE_EXTRA_CA_CERTS="${NODE_EXTRA_CA_CERTS:-$HOME/.android-certs/full-ca-bundle.pem}"
if ( cd "$T/ws-baseline" && sandbox-exec -f "$T/sandbox-baseline.sb" \
       env -u KELIVER_USE_MAVEN_LOCAL ./gradlew compileKotlinJs --console=plain >/dev/null 2>&1 ); then
  ok "baseline can build its own fixture inside the sandbox"
else bad "baseline CANNOT build inside the sandbox"; fi

if [ -x "$T/runtime/portal-mcp/bin/portal-mcp" ]; then
  probe=$(printf '%s\n' \
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"p","version":"1"}}}' \
    '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
    | sandbox-exec -f "$T/sandbox-semantic.sb" "$T/runtime/portal-mcp/bin/portal-mcp" 2>/dev/null \
    | grep -c '"name":"get_document"')
  [ "${probe:-0}" -ge 1 ] && ok "semantic can run the staged MCP server (get_document offered)" \
                          || bad "semantic CANNOT run the staged MCP server"
else bad "staged MCP binary missing at $T/runtime/portal-mcp/bin/portal-mcp"; fi

echo
echo "passed: $pass   failed: $fail"
[ "$fail" -eq 0 ]
