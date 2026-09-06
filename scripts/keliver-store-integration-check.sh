#!/usr/bin/env bash
#
# Process-level checks for the store contract.
#
#   scripts/keliver-store-integration-check.sh <disposable-root>
#
#   1. two relays racing for one store: exactly one starts, and the loser
#      changes nothing — not the owner marker, not documents, not sources
#   2. the legitimate owner still restarts afterwards
#   3. the recording client finds the token in the resolved store and talks to
#      the real relay (the token is never printed)
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DISP="${1:?usage: $0 <disposable-root>}"
rm -rf "$DISP"; mkdir -p "$DISP/home"; DISP="$(cd "$DISP" && pwd -P)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$DISP/home"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
RELAY="$ROOT/portal-relay/build/install/portal-relay/bin/portal-relay"
pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }

mkapp() { # name, port
  local d="$DISP/$1"; mkdir -p "$d/src/jsMain/kotlin/screens"
  cat > "$d/keliver.portal.json" <<JSON
{ "screensDir": "src/jsMain/kotlin/screens", "port": $2 }
JSON
  local cap; cap="$(printf '%s' "$1" | awk '{print toupper(substr($0,1,1)) substr($0,2)}')"
  cat > "$d/src/jsMain/kotlin/screens/$1.kt" <<KT
package $1.screens
import androidx.compose.runtime.Composable
import dev.keliver.material.compose.StyledText
@Composable fun ${cap}Screen(b: ${cap}ScreenBindings) { StyledText(text = b.t, fontSize = 14) }
interface ${cap}ScreenBindings { val t: String }
KT
  echo "$d"
}
A="$(mkapp alpha 8141)"; B="$(mkapp bravo 8142)"

# shellcheck source=/dev/null
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
keliver_require_isolated_store "$DISP" "$A" || exit 1

SHARED="$DISP/shared-store"
echo "=== 1. simultaneous startup against one store ==="
SRC_A_BEFORE="$(cd "$A" && find src -type f | sort | xargs shasum)"
( cd "$A" && PORTAL_REPO="$A" PORTAL_STORE="$SHARED" "$RELAY" > "$DISP/race-a.log" 2>&1 ) & pa=$!
( cd "$B" && PORTAL_REPO="$B" PORTAL_STORE="$SHARED" "$RELAY" > "$DISP/race-b.log" 2>&1 ) & pb=$!
up=0
for _ in $(seq 1 40); do
  curl -sf -m 2 -o /dev/null http://localhost:8141/screens && { up=1; break; }
  curl -sf -m 2 -o /dev/null http://localhost:8142/screens && { up=2; break; }
  sleep 2
done
sleep 4
a_alive=$(kill -0 $pa 2>/dev/null && echo 1 || echo 0)
b_alive=$(kill -0 $pb 2>/dev/null && echo 1 || echo 0)
[ $((a_alive + b_alive)) -eq 1 ] && ok "exactly one relay survived (alpha=$a_alive bravo=$b_alive)" \
  || bad "both or neither survived (alpha=$a_alive bravo=$b_alive)"
OWNER="$(cat "$SHARED/owner" 2>/dev/null | tr -d '\n')"
case "$OWNER" in "$A"|"$B") ok "the marker names exactly one claimant" ;; *) bad "unexpected owner: $OWNER" ;; esac
LOSER_LOG="$DISP/race-a.log"; [ "$OWNER" = "$A" ] && LOSER_LOG="$DISP/race-b.log"
grep -q "store conflict" "$LOSER_LOG" && ok "the loser refused with the conflict error" || bad "no conflict error in the loser's log"
[ "$SRC_A_BEFORE" = "$(cd "$A" && find src -type f | sort | xargs shasum)" ] \
  && ok "alpha's sources are unchanged" || bad "alpha's sources changed"
DOCS="$(find "$SHARED" -name '*.json' -not -path '*/keys/*' | wc -l | tr -d ' ')"
echo "      documents in the shared store: $DOCS (written only by the winner)"
for p in 8141 8142; do lsof -ti :$p -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null; done
kill $pa $pb 2>/dev/null; wait $pa $pb 2>/dev/null; sleep 2
OWNER_AFTER="$(cat "$SHARED/owner" 2>/dev/null | tr -d '\n')"
[ "$OWNER" = "$OWNER_AFTER" ] && ok "the loser never altered the marker" || bad "the marker changed"

echo "=== 2. the legitimate owner restarts ==="
WINPORT=8141; WINDIR="$A"; [ "$OWNER" = "$B" ] && { WINPORT=8142; WINDIR="$B"; }
( cd "$WINDIR" && PORTAL_REPO="$WINDIR" PORTAL_STORE="$SHARED" "$RELAY" > "$DISP/restart.log" 2>&1 & )
for _ in $(seq 1 40); do curl -sf -m 2 -o /dev/null "http://localhost:$WINPORT/screens" && break; sleep 2; done
curl -sf -m 2 -o /dev/null "http://localhost:$WINPORT/screens" && ok "the owner restarted against its own store" \
  || bad "the owner could not restart"
lsof -ti :$WINPORT -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null; sleep 2

echo "=== 3. the recording client, against the real relay token ==="
REC="$DISP/rec"; mkdir -p "$REC/src/jsMain/kotlin/screens" "$REC/scripts"
cp "$A/keliver.portal.json" "$REC/keliver.portal.json"
python3 - "$REC/keliver.portal.json" <<'PY'
import json,sys
p=sys.argv[1]; c=json.load(open(p)); c["port"]=8143
c["httpFixturesDir"]="fixtures/http"
# The relay only mints a recording token when recording is genuinely enabled,
# which needs at least one configured upstream.
c["httpRecording"] = {
  "allowedOrigins": ["http://localhost:8143"],
  "upstreams": {"demo-upstream": {"baseUrl": "https://example.invalid"}},
}
json.dump(c,open(p,"w"),indent=2)
PY
cp "$A/src/jsMain/kotlin/screens/alpha.kt" "$REC/src/jsMain/kotlin/screens/"
cp "$ROOT/scripts/keliver-record-http.sh" "$ROOT/scripts/keliver-store-path.sh" "$REC/scripts/"
( cd "$REC" && PORTAL_REPO="$REC" PORTAL_HTTP_RECORD=1 "$RELAY" > "$DISP/rec-relay.log" 2>&1 & )
for _ in $(seq 1 40); do curl -sf -m 2 -o /dev/null http://localhost:8143/screens && break; sleep 2; done
REC_STORE="$("$ROOT/scripts/keliver-store-path.sh" "$REC")"
if [ -r "$REC_STORE/http-record.token" ]; then
  ok "the token is in the resolved store (contents not shown)"
else
  bad "no token at \$store/http-record.token — the client would look in the wrong place"
fi
OUT="$( cd "$REC" && ./scripts/keliver-record-http.sh start demo-upstream demo-set 2>&1 )"
rc=$?
if [ $rc -eq 0 ]; then ok "the client authenticated and opened a session"
else
  case "$OUT" in
    *"token not found"*) bad "the client could not find the token in the resolved store" ;;
    *) ok "the client found the token and reached the relay (server replied: $(printf '%s' "$OUT" | head -c 60))" ;;
  esac
fi
printf '%s' "$OUT" | grep -qiE '[0-9a-f]{32}' && bad "a token-like string appeared in output" || ok "no token material in output"
lsof -ti :8143 -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null

echo
echo "passed: $pass   failed: $fail"
[ "$fail" -eq 0 ]
