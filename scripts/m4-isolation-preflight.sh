#!/usr/bin/env bash
#
# m4-isolation-preflight — prove the participant sandbox before any trial runs.
#
#   scripts/m4-isolation-preflight.sh <trial-dir> [--isolation-only]
#
# Places harmless sentinels in every location a participant must not read, then
# attempts each read from inside each participant's profile. Every forbidden
# read must be DENIED. Also checks the participant can still build its own
# fixture and, for the semantic condition, run the staged MCP server.
#
# Sentinels are harmless strings. The boundary is never tested by planting a
# real answer key.
#
# NON-DESTRUCTIVE. An earlier version wrote its sentinels straight to
# reports/<cond>-report.txt and destroyed real participant reports on every
# rerun — it did exactly that to this experiment's own reports, which survived
# only because they had already been copied into the evidence directory.
# Sentinels now live in run-unique files that cannot collide with an artifact
# name, every pre-existing file under reports/ and evaluator/ is checksummed
# before and after, and a changed or missing file is a FAILURE, not a note.
# See scripts/m4-isolation-preflight-selftest.sh.
#
set -uo pipefail
ISOLATION_ONLY=0
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --isolation-only) ISOLATION_ONLY=1; shift ;;   # skip the build/MCP capability
                                                   # checks; used by the self-test,
                                                   # whose synthetic trial has no
                                                   # gradle wrapper or MCP binary
    *) ARGS+=("$1"); shift ;;
  esac
done
set -- "${ARGS[@]+"${ARGS[@]}"}"
T="${1:-}"
[ -n "$T" ] && [ -d "$T" ] || { echo "usage: $0 <trial-dir>" >&2; exit 2; }
T="$(cd "$T" && pwd -P)"
K="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
SCRATCH="$(dirname "$T")"

pass=0; fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }

mkdir -p "$T/evaluator" "$T/reports"

# --- record what already exists, so we can prove we did not touch it ----------
manifest() { # dir -> "sha  path" lines, sentinels excluded
  find "$1" -type f ! -name '.preflight-sentinel-*' -print0 2>/dev/null \
    | sort -z | xargs -0 shasum 2>/dev/null
}
BEFORE_REPORTS="$(manifest "$T/reports")"
BEFORE_EVAL="$(manifest "$T/evaluator")"

# --- plant harmless sentinels -------------------------------------------------
# Run-unique names. `.preflight-sentinel-` is not a name any artifact uses, and
# the run id keeps concurrent or repeated preflights from colliding.
RUNID="$$-$(date +%s)"
S="SENTINEL-$RUNID-HARMLESS"
sent() { echo "$1/.preflight-sentinel-$RUNID-$2.txt"; }

EVAL_SENT="$(sent "$T/evaluator" evaluator)"
PRIOR_SENT="$SCRATCH/.preflight-sentinel-$RUNID-prior.txt"
CTRL_SENT="$K/.preflight-sentinel-$RUNID-controller.txt"
SENTINELS=("$EVAL_SENT" "$PRIOR_SENT" "$CTRL_SENT")
echo "$S evaluator"        > "$EVAL_SENT"
echo "$S prior-experiment" > "$PRIOR_SENT"
echo "$S controller-repo"  > "$CTRL_SENT"
# Conditions are whatever sandbox profiles the trial defines, so a trial with
# more than two arms is covered without editing this script.
CONDS=()
for prof in "$T"/sandbox-*.sb; do
  [ -e "$prof" ] || continue
  n="$(basename "$prof" .sb)"; CONDS+=("${n#sandbox-}")
done
[ ${#CONDS[@]} -gt 0 ] || { echo "no sandbox-*.sb profiles in $T" >&2; exit 2; }

for c in "${CONDS[@]}"; do
  RS="$(sent "$T/reports" "$c-report")"
  SENTINELS+=("$RS")
  echo "$S report-$c" > "$RS"
done
cleanup() { rm -f "${SENTINELS[@]}"; }
trap cleanup EXIT

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

deny_path() { # label, profile, path — for a REAL file we must not read and
              # must not modify. Denial is judged by the error, never by
              # inspecting content we are not allowed to see.
  local out
  out="$(sandbox-exec -f "$2" /bin/cat "$3" 2>&1 >/dev/null)"
  if [ -z "$out" ]; then
    bad "$1 — READ SUCCEEDED (leak)"
  elif printf '%s' "$out" | grep -qi "not permitted\|Operation not permitted"; then
    ok "$1 — denied"
  else
    bad "$1 — unexpected: $(printf '%s' "$out" | head -c 80)"
  fi
}

echo "M4 isolation preflight"
echo "trial:  $T"
echo "run id: $RUNID"

for c in "${CONDS[@]}"; do
  P="$T/sandbox-$c.sb"
  # "the other participant" = every condition that is not this one
  others=(); for o in "${CONDS[@]}"; do [ "$o" = "$c" ] || others+=("$o"); done
  other="${others[0]:-$c}"
  [ -f "$P" ] || { bad "$c: no sandbox profile"; continue; }
  echo "--- $c"
  deny_read "$c: evaluator area"                "$P" "$EVAL_SENT"
  deny_read "$c: controller repository"         "$P" "$CTRL_SENT"
  for o in ${others[@]+"${others[@]}"}; do
    deny_read "$c: $o's report"                 "$P" "$(sent "$T/reports" "$o-report")"
    deny_path "$c: $o's workspace"              "$P" "$T/ws-$o/keliver.portal.json"
  done
  deny_read "$c: own report location"           "$P" "$(sent "$T/reports" "$c-report")"
  deny_read "$c: prior experiment output"       "$P" "$PRIOR_SENT"

  # The REAL report paths, when a trial has already produced them. The sentinel
  # checks above prove the directory is denied; these prove the actual
  # artifacts are, without the preflight ever having written to them.
  for r in "$c" ${others[@]+"${others[@]}"}; do
    RP="$T/reports/$r-report.txt"
    [ -f "$RP" ] && deny_path "$c: real $r report on disk" "$P" "$RP"
  done

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
if [ "$ISOLATION_ONLY" = 1 ]; then
  echo "--- runtime (skipped: --isolation-only)"
else
echo "--- runtime"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}"
export NODE_EXTRA_CA_CERTS="${NODE_EXTRA_CA_CERTS:-$HOME/.android-certs/full-ca-bundle.pem}"
BUILD_C="${CONDS[0]}"
if ( cd "$T/ws-$BUILD_C" && sandbox-exec -f "$T/sandbox-$BUILD_C.sb" \
       env -u KELIVER_USE_MAVEN_LOCAL ./gradlew compileKotlinJs --console=plain >/dev/null 2>&1 ); then
  ok "$BUILD_C can build its own fixture inside the sandbox"
else bad "$BUILD_C CANNOT build inside the sandbox"; fi

MCP_C="${CONDS[${#CONDS[@]}-1]}"
if [ -x "$T/runtime/portal-mcp/bin/portal-mcp" ]; then
  probe=$(printf '%s\n' \
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"p","version":"1"}}}' \
    '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
    | sandbox-exec -f "$T/sandbox-$MCP_C.sb" "$T/runtime/portal-mcp/bin/portal-mcp" 2>/dev/null \
    | grep -c '"name":"get_document"')
  [ "${probe:-0}" -ge 1 ] && ok "$MCP_C can run the staged MCP server (get_document offered)" \
                          || bad "$MCP_C CANNOT run the staged MCP server"
else bad "staged MCP binary missing at $T/runtime/portal-mcp/bin/portal-mcp"; fi
fi

# --- prove the preflight destroyed nothing ------------------------------------
echo "--- non-destructiveness"
cleanup; trap - EXIT
unchanged() { # label, dir, expected-manifest
  if [ "$(manifest "$2")" = "$3" ]; then
    ok "$1 unchanged by this preflight"
  else
    bad "$1 WAS MODIFIED by this preflight"
    diff <(printf '%s\n' "$3") <(manifest "$2") | head -10
  fi
}
unchanged "reports/"   "$T/reports"   "$BEFORE_REPORTS"
unchanged "evaluator/" "$T/evaluator" "$BEFORE_EVAL"
for f in "$PRIOR_SENT" "$CTRL_SENT"; do
  [ -e "$f" ] && bad "sentinel left behind: $f"
done
ok "sentinels removed"

echo
echo "passed: $pass   failed: $fail"
[ "$fail" -eq 0 ]
