#!/usr/bin/env bash
#
# keliver-acceptance-identity-check — U21 regression.
#
#   scripts/keliver-acceptance-identity-check.sh <parent-dir> <package.zip>
#
# Stands a FOREIGN portal relay on the port the acceptance expects, pointed at a
# different disposable app, and requires that the acceptance refuses to run:
#
#   * it exits NONZERO,
#   * it does so BEFORE issuing any mutation, so the foreign app's source and
#     store are byte-identical afterwards,
#   * and the foreign relay is still running — cleanup is restricted to
#     processes each invocation started.
#
# Before the fix the acceptance treated any answer on the port as its own
# portal, sent every MCP call to the foreign relay, and edited that app's source
# while reporting PASS.
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
PARENT="${1:?usage: $0 <parent-dir> <package.zip>}"
ZIP="${2:?usage: $0 <parent-dir> <package.zip>}"
ZIP="$(cd "$(dirname "$ZIP")" && pwd -P)/$(basename "$ZIP")"
[ -f "$ZIP" ] || { echo "no package at $ZIP" >&2; exit 2; }

# shellcheck source=/dev/null
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "$PARENT" identity)" || exit 1
echo "run dir: $DISP"

pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }

FOREIGN_PID=""      # the launcher subshell
FOREIGN_LISTENER=""  # the relay JVM it started
cleanup(){
  # Only what this script started, and both halves of it: killing the launcher
  # subshell alone leaves the JVM holding the port.
  [ -n "$FOREIGN_LISTENER" ] && kill "$FOREIGN_LISTENER" 2>/dev/null
  [ -n "$FOREIGN_PID" ] && kill "$FOREIGN_PID" 2>/dev/null
  return 0
}
trap cleanup EXIT

mkdir -p "$DISP/pkg" "$DISP/foreign-home" "$DISP/work"
( cd "$DISP/pkg" && unzip -q "$ZIP" )
PKG="$(ls -d "$DISP/pkg"/keliver-portal-tools-*)"; KP="$PKG/bin"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

# --- the foreign app, on its own isolated home -------------------------------
( cd "$DISP/work" && env -u KELIVER_USE_MAVEN_LOCAL JAVA_TOOL_OPTIONS="-Duser.home=$DISP/foreign-home" \
    "$KP/keliver-init" Foreign >"$DISP/foreign-init.log" 2>&1 ) \
  || { echo "could not scaffold the foreign app"; cat "$DISP/foreign-init.log"; exit 2; }
FOREIGN="$DISP/work/foreign"
PORT="$(python3 -c "import json;print(json.load(open('$FOREIGN/keliver.portal.json')).get('port',8077))")"

if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t >/dev/null 2>&1; then
  echo "port $PORT is already in use; free it and re-run" >&2; exit 2
fi

( cd "$FOREIGN" && PORTAL_REPO="$FOREIGN" JAVA_TOOL_OPTIONS="-Duser.home=$DISP/foreign-home" \
    "$PKG/relay/bin/portal-relay" >"$DISP/foreign-relay.log" 2>&1 ) &
FOREIGN_PID=$!
for _ in $(seq 1 40); do curl -sf -m 2 -o /dev/null "http://localhost:$PORT/devstate" && break; sleep 2; done
curl -sf -m 2 -o /dev/null "http://localhost:$PORT/devstate" \
  || { echo "the foreign relay never answered on :$PORT"; tail -5 "$DISP/foreign-relay.log"; exit 2; }
FOREIGN_LISTENER="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1)"
echo "  ....  foreign relay answering :$PORT (launcher $FOREIGN_PID, listener $FOREIGN_LISTENER), app $FOREIGN"

fingerprint(){ find "$1" -type f 2>/dev/null | sort | xargs shasum 2>/dev/null; }
FOREIGN_SRC_BEFORE="$(fingerprint "$FOREIGN/src")"
FOREIGN_STORE="$("$KP/keliver-store-path.sh" "$FOREIGN" 2>/dev/null)"
FOREIGN_STORE_BEFORE="$(fingerprint "$FOREIGN_STORE")"

# --- the acceptance must refuse ----------------------------------------------
"$ROOT/scripts/keliver-adopter-acceptance.sh" "$DISP/acceptance" "$ZIP" >"$DISP/acceptance.log" 2>&1
RC=$?
echo "  ....  acceptance exit code: $RC"

[ "$RC" -ne 0 ] \
  && ok "the acceptance exited nonzero" \
  || bad "the acceptance exited 0 with a foreign relay on :$PORT"

# The refusal must come from the startup/identity gate. Any of these three is
# that gate; a failure further down would mean requests had already gone out.
grep -qE "REFUSING to send any request|exited during startup|cannot establish whose relay|cannot list the process holding" "$DISP/acceptance.log" \
  && ok "it refused at the startup/identity gate, not on a downstream symptom" \
  || bad "no startup/identity refusal recorded; see $DISP/acceptance.log"

grep -qi "apply_ops committed\|apply_ops dry run" "$DISP/acceptance.log" \
  && bad "a mutation request was issued before the refusal" \
  || ok "no mutation request was issued"

[ "$FOREIGN_SRC_BEFORE" = "$(fingerprint "$FOREIGN/src")" ] \
  && ok "the foreign app's source is byte-identical" \
  || { bad "THE FOREIGN APP'S SOURCE CHANGED"; diff <(printf '%s\n' "$FOREIGN_SRC_BEFORE") <(fingerprint "$FOREIGN/src") | head; }

[ "$FOREIGN_STORE_BEFORE" = "$(fingerprint "$FOREIGN_STORE")" ] \
  && ok "the foreign app's store is byte-identical" \
  || { bad "THE FOREIGN APP'S STORE CHANGED"; diff <(printf '%s\n' "$FOREIGN_STORE_BEFORE") <(fingerprint "$FOREIGN_STORE") | head; }

kill -0 "$FOREIGN_LISTENER" 2>/dev/null \
  && ok "the foreign relay is still running (cleanup stayed inside this run)" \
  || bad "the foreign relay was killed by something that did not start it"

echo
echo "passed: $pass   failed: $fail"
[ "$fail" -eq 0 ]
