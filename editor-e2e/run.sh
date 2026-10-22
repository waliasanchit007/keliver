#!/usr/bin/env bash
# Runs the editor end-to-end suite: builds the generic editor and the relay
# (or takes prebuilt ones), copies the fixture app to a temp dir, starts a
# scratch relay on it (with its own store) and a static server for the editor
# on free ports, runs the tests in headless Chrome, and always stops what it
# started.
#
#   editor-e2e/run.sh                     build, then run every test
#   editor-e2e/run.sh --grep 'E1|E2'      run the tests whose "id title" matches
#   editor-e2e/run.sh --editor-dist DIR   test this built editor (skips its build)
#   editor-e2e/run.sh --relay BIN         use this relay launcher (skips its build)
#   editor-e2e/run.sh --no-build          use the editor and relay already built in this checkout
#
# Environment: E2E_OUT (artifacts; default build/editor-e2e), E2E_RELAY_PORT and
# E2E_EDITOR_PORT (default: free ports), E2E_KEEP=1 (keep the temp dir),
# CHROME (browser binary), JAVA_HOME (JDK 17; found on macOS if unset).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
EDITOR_DIST=""
RELAY_BIN=""
BUILD=1
GREP="${E2E_GREP:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --grep) GREP="$2"; shift 2 ;;
    --editor-dist) EDITOR_DIST="$(cd "$2" && pwd)"; shift 2 ;;
    --relay) RELAY_BIN="$2"; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "run.sh: unknown argument: $1" >&2; exit 64 ;;
  esac
done

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  export JAVA_HOME
fi

# ── Build what was not given ────────────────────────────────────────────────
TASKS=""
if [ -z "$EDITOR_DIST" ]; then
  EDITOR_DIST="$ROOT/web-spike/build/dist/wasmJs/productionExecutable"
  TASKS="$TASKS :web-spike:wasmJsBrowserDistribution"
fi
if [ -z "$RELAY_BIN" ]; then
  RELAY_BIN="$ROOT/portal-relay/build/install/portal-relay/bin/portal-relay"
  TASKS="$TASKS :portal-relay:installDist"
fi
if [ "$BUILD" = 1 ] && [ -n "$TASKS" ]; then
  echo "editor-e2e: building$TASKS (generic editor)"
  # shellcheck disable=SC2086 # TASKS is a space-separated task list
  (cd "$ROOT" && ./gradlew -q -Pkeliver.genericEditor=true $TASKS)
fi
[ -f "$EDITOR_DIST/index.html" ] || { echo "editor-e2e: no built editor at $EDITOR_DIST" >&2; exit 1; }
[ -x "$RELAY_BIN" ] || { echo "editor-e2e: no relay launcher at $RELAY_BIN" >&2; exit 1; }

# ── Node: the harness needs a global WebSocket (Node 22+, or 20/21 with a flag) ──
NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
NODE_FLAGS=""
if [ "$NODE_MAJOR" -lt 22 ]; then NODE_FLAGS="--experimental-websocket"; fi

free_port() { node -e 'const s=require("net").createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})'; }
RELAY_PORT="${E2E_RELAY_PORT:-$(free_port)}"
EDITOR_PORT="${E2E_EDITOR_PORT:-$(free_port)}"
OUT="${E2E_OUT:-$ROOT/build/editor-e2e}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

# ── A scratch copy of the fixture, its own store, and cleanup on any exit ──
WORK="$(mktemp -d "${TMPDIR:-/tmp}/keliver-editor-e2e.XXXXXX")"
RELAY_PID=""
SERVER_PID=""
cleanup() {
  status=$?
  for pid in $RELAY_PID $SERVER_PID; do
    kill "$pid" 2>/dev/null || true
  done
  for pid in $RELAY_PID $SERVER_PID; do
    for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || break; sleep 0.3; done
    kill -9 "$pid" 2>/dev/null || true
  done
  if [ -f "$WORK/relay.log" ]; then cp "$WORK/relay.log" "$OUT/relay.log"; fi
  if [ "${E2E_KEEP:-}" = 1 ]; then echo "editor-e2e: kept $WORK"; else rm -rf "$WORK"; fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

cp -R "$HERE/fixture" "$WORK/app"
node -e '
  const fs = require("fs"), f = process.argv[1];
  const c = JSON.parse(fs.readFileSync(f, "utf8")); c.port = +process.argv[2];
  fs.writeFileSync(f, JSON.stringify(c, null, 2) + "\n");
' "$WORK/app/keliver.portal.json" "$RELAY_PORT"
mkdir -p "$WORK/store"

echo "editor-e2e: relay :$RELAY_PORT on $WORK/app, editor :$EDITOR_PORT from $EDITOR_DIST"
(cd "$WORK/app" && PORTAL_REPO="$WORK/app" PORTAL_STORE="$WORK/store" exec "$RELAY_BIN") > "$WORK/relay.log" 2>&1 &
RELAY_PID=$!
node "$HERE/lib/serve.mjs" "$EDITOR_DIST" "$EDITOR_PORT" > "$WORK/serve.log" 2>&1 &
SERVER_PID=$!

wait_http() { # url, what, pid
  for _ in $(seq 1 120); do
    if curl -sf -o /dev/null "$1"; then return 0; fi
    if ! kill -0 "$3" 2>/dev/null; then echo "editor-e2e: the $2 exited:" >&2; cat "$WORK/relay.log" "$WORK/serve.log" >&2; return 1; fi
    sleep 0.5
  done
  echo "editor-e2e: the $2 did not answer at $1" >&2
  return 1
}
wait_http "http://127.0.0.1:$RELAY_PORT/active" relay "$RELAY_PID"
wait_http "http://127.0.0.1:$EDITOR_PORT/" "editor server" "$SERVER_PID"

set +e
# shellcheck disable=SC2086 # NODE_FLAGS is empty or one flag
E2E_EDITOR_URL="http://127.0.0.1:$EDITOR_PORT" E2E_RELAY_PORT="$RELAY_PORT" E2E_FIXTURE_DIR="$WORK/app" \
  E2E_OUT="$OUT" E2E_GREP="$GREP" node $NODE_FLAGS "$HERE/run.mjs"
RESULT=$?
set -e
echo "editor-e2e: artifacts in $OUT"
exit "$RESULT"
