#!/usr/bin/env bash
#
# keliver-adopter-acceptance — execute the adopter guide, literally, from a
# packaged build.
#
#   scripts/keliver-adopter-acceptance.sh <parent-dir> <package.zip> [--serial S]
#
# This is a WORKFLOW ACCEPTANCE CHECK. It is not an M4 comparison and is not
# evidence that semantic access outperforms editing source.
#
# Only the package and the guide's documented prerequisites are used — no
# scripts from a Keliver checkout, no undocumented configuration. The device
# steps are skipped when no serial is given.
#
# <parent-dir> is a PARENT. A uniquely named run directory is created beneath
# it and nothing the caller supplied is ever deleted. This script used to start
# with `rm -rf` on that argument, before the isolation guard ran.
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
PARENT="${1:?usage: $0 <parent-dir> <package.zip> [--serial S]}"
ZIP="${2:?usage: $0 <parent-dir> <package.zip> [--serial S]}"
shift 2
SERIAL=""
while [ $# -gt 0 ]; do
  case "$1" in --serial) SERIAL="${2:?}"; shift 2 ;; *) echo "unknown: $1" >&2; exit 2 ;; esac
done
ZIP="$(cd "$(dirname "$ZIP")" && pwd -P)/$(basename "$ZIP")"   # the script cd's away
[ -f "$ZIP" ] || { echo "no package at $ZIP" >&2; exit 2; }

# shellcheck source=/dev/null
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "$PARENT" acceptance)" || exit 1
mkdir -p "$DISP/home" "$DISP/pkg" "$DISP/work"
echo "run dir: $DISP"

# Only ever stop what THIS invocation started.
STARTED_PIDS=""
track(){ STARTED_PIDS="$STARTED_PIDS $1"; }
stop_started(){ for pid in $STARTED_PIDS; do kill "$pid" 2>/dev/null; done; }
trap stop_started EXIT
pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
note(){ printf '  ....  %s\n' "$1"; }

echo "package: $(shasum -a 256 "$ZIP" | awk '{print $1}')"
( cd "$DISP/pkg" && unzip -q "$ZIP" )
PKG="$(ls -d "$DISP/pkg"/keliver-portal-tools-*)"; KP="$PKG/bin"

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$DISP/home"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
export NODE_EXTRA_CA_CERTS="${NODE_EXTRA_CA_CERTS:-$HOME/.android-certs/full-ca-bundle.pem}"

# --- guide: scaffold and start -----------------------------------------------
( cd "$DISP/work" && env -u KELIVER_USE_MAVEN_LOCAL "$KP/keliver-init" MyApp >"$DISP/init.log" 2>&1 ) \
  && ok "keliver-init scaffolded the app" || { bad "keliver-init failed"; cat "$DISP/init.log"; exit 1; }
APP="$DISP/work/myapp"
( cd "$APP" && git init -q && git add -A && git -c user.email=a@b.invalid -c user.name=a commit -qm scaffold )
( cd "$APP" && find src -type f | sort | xargs shasum ) > "$DISP/fingerprint-before.txt"
LOGIC_BEFORE="$(shasum "$APP/src/jsMain/kotlin/logic/HomePresenter.kt" | awk '{print $1}')"

keliver_require_isolated_store "$DISP" "$APP" || exit 1

PORT="$(python3 -c "import json;print(json.load(open('$APP/keliver.portal.json')).get('port',8077))")"
( cd "$APP" && env -u KELIVER_USE_MAVEN_LOCAL "$KP/keliver-portal" . >"$DISP/portal.log" 2>&1 & )
for _ in $(seq 1 40); do curl -sf -m 2 -o /dev/null "http://localhost:$PORT/screens" && break; sleep 3; done
curl -sf -m 2 -o /dev/null "http://localhost:$PORT/screens" \
  && ok "keliver-portal started and answers" || { bad "the portal did not start"; tail -5 "$DISP/portal.log"; exit 1; }

mcp(){ printf '%s\n' \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"acc","version":"1"}}}' \
 "$1" | ( cd "$APP" && PORTAL_REPO="$APP" PORTAL_SERVER="http://localhost:$PORT" "$PKG/mcp/bin/portal-mcp" 2>/dev/null ) | tail -1; }
text(){ python3 -c 'import sys,json;print(json.load(sys.stdin)["result"]["content"][0]["text"])'; }

# --- guide: get_guide --------------------------------------------------------
G="$(mcp '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_guide","arguments":{}}}' | text)"
case "$G" in
  *"adopter guide"*) ok "get_guide returns the adopter guide ($(printf '%s' "$G" | wc -c | tr -d ' ') bytes)" ;;
  *) bad "get_guide did not return the adopter guide" ;;
esac
printf '%s' "$G" | grep -q "keliver-dev.sh" && bad "the guide names a Keliver-repo-only script" \
  || ok "the guide names no Keliver-repo-only commands"

# --- guide: inspect ----------------------------------------------------------
D="$(mcp '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_document","arguments":{"screen":"home"}}}' | text)"
VER="$(printf '%s' "$D" | python3 -c 'import sys,json;print(json.load(sys.stdin)["version"])' 2>/dev/null)"
TITLE="$(printf '%s' "$D" | python3 -c 'import sys,json;print(json.load(sys.stdin)["root"]["children"][0]["props"]["text"]["s"])' 2>/dev/null)"
[ -n "$VER" ] && ok "get_document returned the screen (version $VER, title '$TITLE')" || bad "get_document failed"

# --- guide: one supported edit ----------------------------------------------
BATCH="{\"baseVersion\":$VER,\"envelope\":{\"session\":\"agent\",\"atMillis\":0},\"ops\":[{\"kind\":\"dev.keliver.portal.document.DocOp.SetProp\",\"target\":2,\"name\":\"text\",\"value\":{\"kind\":\"dev.keliver.portal.document.PropValue.Lit\",\"tag\":\"s\",\"s\":\"My Inbox\"}}]}"
req(){ python3 -c "
import json,sys
args={'screen':'home','batchJson':sys.argv[1]}
if len(sys.argv)>2: args['dryRun']='1'
print(json.dumps({'jsonrpc':'2.0','id':9,'method':'tools/call','params':{'name':'apply_ops','arguments':args}}))" "$1" ${2:+dry}; }
DRY="$(mcp "$(req "$BATCH" dry)" | text)"
case "$DRY" in *'"ok":true'*) ok "apply_ops dry run validated: $DRY" ;; *) bad "dry run rejected: $DRY" ;; esac
COMMIT="$(mcp "$(req "$BATCH")" | text)"
case "$COMMIT" in *'"ok":true'*) ok "apply_ops committed: $COMMIT" ;; *) bad "commit rejected: $COMMIT" ;; esac
sleep 3

# --- guide: the source diff --------------------------------------------------
grep -q 'text = "My Inbox"' "$APP/src/jsMain/kotlin/screens/home.kt" \
  && ok "the screen source now reads the edited title" || bad "the screen source did not change"
CHANGED="$( cd "$APP" && git diff --name-only )"
[ "$CHANGED" = "src/jsMain/kotlin/screens/home.kt" ] \
  && ok "exactly one tracked file changed: $CHANGED" || bad "unexpected tracked changes: $CHANGED"
[ "$LOGIC_BEFORE" = "$(shasum "$APP/src/jsMain/kotlin/logic/HomePresenter.kt" | awk '{print $1}')" ] \
  && ok "hand-owned logic is byte-identical" || bad "HAND-OWNED LOGIC CHANGED"
[ -f "$APP/src/jsMain/kotlin/screens/Compiled_home.kt" ] \
  && ok "the generated version stamp appeared, as the guide says" \
  || note "no Compiled_home.kt (the guide says to expect one)"

# --- guide: build ------------------------------------------------------------
( cd "$APP" && env -u KELIVER_USE_MAVEN_LOCAL ./gradlew compileKotlinJs --console=plain -q >"$DISP/compile.log" 2>&1 ) \
  && ok "./gradlew compileKotlinJs succeeds" || { bad "compile failed"; tail -5 "$DISP/compile.log"; }

# --- guide: run on a device --------------------------------------------------
if [ -n "$SERIAL" ]; then
  ( cd "$APP" && env -u KELIVER_USE_MAVEN_LOCAL "$KP/keliver-new-device-target.sh" >"$DISP/devtarget.log" 2>&1 ) \
    && ok "keliver-new-device-target.sh added the device target" || bad "device target failed"
  ( cd "$APP" && "$KP/keliver-install-device-host.sh" --serial "$SERIAL" >"$DISP/install.log" 2>&1 ) \
    && ok "keliver-install-device-host.sh installed the host" || bad "host install failed"
  # Do NOT clear port 8080 blindly — a foreign process there is not ours to
  # kill. Fail loudly instead, and track the serve we start so cleanup only
  # ever stops this invocation's own process.
  if lsof -ti :8080 -sTCP:LISTEN >/dev/null 2>&1; then
    bad "port 8080 is already in use by another process; free it and re-run"
  fi
  ( cd "$APP" && env -u KELIVER_USE_MAVEN_LOCAL ./gradlew serveDevelopmentZipline --console=plain >"$DISP/serve.log" 2>&1 ) &
  track $!
  for _ in $(seq 1 60); do curl -sf -m 3 -o /dev/null http://localhost:8080/manifest.zipline.json && break; sleep 5; done
  curl -sf -m 3 -o /dev/null http://localhost:8080/manifest.zipline.json \
    && ok "serveDevelopmentZipline is serving the bundle" || bad "the bundle server never answered"
  adb -s "$SERIAL" shell am force-stop dev.keliver.portaldevice
  adb -s "$SERIAL" shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity >/dev/null 2>&1
  sleep 16
  adb -s "$SERIAL" shell uiautomator dump /sdcard/acc.xml >/dev/null 2>&1
  SCREEN="$(adb -s "$SERIAL" shell cat /sdcard/acc.xml | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
  case "$SCREEN" in *"My Inbox"*) ok "the edited title is on the device: $SCREEN" ;;
                    *) bad "the device does not show the edit: $SCREEN" ;; esac
  stop_started; STARTED_PIDS=""
else
  note "device steps skipped (no --serial)"
fi

# --- guide: restart, and the edit persists -----------------------------------
( cd "$APP" && "$KP/keliver-portal" stop . >/dev/null 2>&1 )
( cd "$APP" && env -u KELIVER_USE_MAVEN_LOCAL "$KP/keliver-portal" . >"$DISP/portal2.log" 2>&1 & )
for _ in $(seq 1 40); do curl -sf -m 2 -o /dev/null "http://localhost:$PORT/screens" && break; sleep 3; done
curl -sf -m 2 -o /dev/null "http://localhost:$PORT/screens" \
  && ok "stop then start works" || { bad "restart failed"; tail -3 "$DISP/portal2.log"; }
D2="$(mcp '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_document","arguments":{"screen":"home"}}}' | text)"
T2="$(printf '%s' "$D2" | python3 -c 'import sys,json;print(json.load(sys.stdin)["root"]["children"][0]["props"]["text"]["s"])' 2>/dev/null)"
[ "$T2" = "My Inbox" ] && ok "the edit persists in the document after restart" || bad "after restart the title is '$T2'"
grep -q 'text = "My Inbox"' "$APP/src/jsMain/kotlin/screens/home.kt" \
  && ok "the edit persists in the source after restart" || bad "the source lost the edit"
( cd "$APP" && "$KP/keliver-portal" stop . >/dev/null 2>&1 )

# --- ownership, by fingerprint not just git status ---------------------------
( cd "$APP" && find src -type f | sort | xargs shasum ) > "$DISP/fingerprint-after.txt"
echo "  ---- source fingerprint delta ----"
diff "$DISP/fingerprint-before.txt" "$DISP/fingerprint-after.txt" | sed 's/^/        /' || true
STORE="$("$KP/keliver-store-path.sh" "$APP")"
case "$STORE" in "$APP"/*) bad "the store is inside the app" ;; *) ok "the store is outside the app: ${STORE##*/}" ;; esac

echo
echo "passed: $pass   failed: $fail"
[ "$fail" -eq 0 ]
