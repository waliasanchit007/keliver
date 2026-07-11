#!/usr/bin/env bash
#
# keliver-dev — one command to boot the whole portal dev loop.
#
#   scripts/keliver-dev.sh            # server + editor + live device bundle
#   scripts/keliver-dev.sh --android  # also install & launch the Android host
#
# Starts:
#   • portal-server  (:8077) — the live document, ops, ingest, publish, gating
#   • editor         (:8096) — the wasm portal editor (open in a browser)
#   • zipline serve  (:8080) — the dev guest bundle for on-device live preview
#
# Ctrl-C stops everything.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
export PORTAL_REPO="$ROOT"

EDITOR_PORT=8096
SERVER_PORT=8077
ZIPLINE_PORT=8080
WITH_ANDROID=false
[[ "${1:-}" == "--android" ]] && WITH_ANDROID=true

pids=()
cleaned=false
cleanup() {
  set +e                       # MUST be first — before any command that could trip errexit
  if $cleaned; then return; fi
  cleaned=true
  echo ""
  echo "keliver-dev: stopping…"
  # Kill tracked children and their subtrees…
  for pid in "${pids[@]:-}"; do
    pkill -TERM -P "$pid" 2>/dev/null
    kill -TERM "$pid" 2>/dev/null
  done
  # …then sweep the ports (gradle's serve runs in its daemon and python re-parents,
  # so port ownership — not the launch pid — is the reliable handle). TERM, then KILL.
  ports="$SERVER_PORT $EDITOR_PORT $ZIPLINE_PORT"
  for p in $ports; do lsof -ti ":$p" 2>/dev/null | xargs kill 2>/dev/null; done
  sleep 1
  for p in $ports; do lsof -ti ":$p" 2>/dev/null | xargs kill -9 2>/dev/null; done
  echo "keliver-dev: stopped."
  exit 0
}
trap cleanup INT TERM EXIT

step() { printf "\n\033[1;35m▸ %s\033[0m\n" "$1"; }

step "Building portal-server + editor (first run compiles; later runs are incremental)…"
./gradlew -q :portal-relay:installDist :web-spike:wasmJsBrowserDevelopmentExecutableDistribution

step "Starting portal-server on :$SERVER_PORT"
lsof -ti ":$SERVER_PORT" | xargs kill 2>/dev/null || true
"$ROOT/portal-relay/build/install/portal-relay/bin/portal-relay" >/tmp/keliver-dev-server.log 2>&1 &
pids+=($!)

step "Serving the editor on :$EDITOR_PORT"
DIST="$ROOT/web-spike/build/dist/wasmJs/developmentExecutable"
lsof -ti ":$EDITOR_PORT" | xargs kill 2>/dev/null || true
( cd "$DIST" && python3 -m http.server "$EDITOR_PORT" >/tmp/keliver-dev-editor.log 2>&1 ) &
pids+=($!)

step "Serving the live device bundle on :$ZIPLINE_PORT (for on-device preview)"
lsof -ti ":$ZIPLINE_PORT" | xargs kill 2>/dev/null || true
./gradlew -q :portal-device-guest:serveDevelopmentZipline >/tmp/keliver-dev-zipline.log 2>&1 &
pids+=($!)

if $WITH_ANDROID; then
  step "Installing + launching the Android host (dev overlay mode)"
  ./gradlew -q :portal-device-android:installDebug || echo "  (install skipped — no device?)"
  "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb" shell am start \
    -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity >/dev/null 2>&1 || true
fi

# wait for the server to answer before declaring ready
for _ in $(seq 1 30); do
  curl -s -m 1 -o /dev/null "http://localhost:$SERVER_PORT/devstate" && break
  sleep 1
done

printf "\n\033[1;32m✓ keliver portal is live\033[0m\n\n"
printf "  Editor        →  http://localhost:%s\n" "$EDITOR_PORT"
printf "  Server API    →  http://localhost:%s   (docs, ops, publish, /capabilities)\n" "$SERVER_PORT"
printf "  Device bundle →  http://localhost:%s   (dev guest, for Android/iOS)\n" "$ZIPLINE_PORT"
printf "  AI agent      →  PORTAL_REPO=%s portal-mcp/build/install/portal-mcp/bin/portal-mcp   (stdio MCP)\n\n" "$ROOT"
printf "  Edit screens in the browser, in portal-app-lib/src/jsMain/kotlin/screens/*.kt,\n"
printf "  or via the MCP agent — all three stay in sync. Publish signs a versioned bundle.\n\n"
printf "  Ctrl-C to stop.\n"

# Block until a signal (the INT/TERM trap does the real shutdown). `wait` returns
# when a trapped signal arrives; loop guards against a transient child exit.
while true; do wait || break; done
