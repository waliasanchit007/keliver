#!/usr/bin/env bash
#
# keliver-adopter-tree-check — an adopter's git working tree must stay clean.
#
#   scripts/keliver-adopter-tree-check.sh <disposable-root> [--init PATH]
#
# The recurring defect in this project is the portal leaving files in someone
# else's repository: main.kt and Compiled_main.kt from a parameterless GET /doc
# (U16), feed.kt from a shared store (U17), and most recently the
# .gradle/keliver-store-path pointer showing up as untracked noise because
# keliver-init shipped no .gitignore.
#
# So this tests the PROPERTY, not the individual files: scaffold an app, commit
# it, use the portal the ordinary way, build it — and `git status` must be
# empty. Any future mechanism that writes into an adopter's tree fails here.
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DISP="${1:?usage: $0 <disposable-root> [--init PATH]}"; shift || true
INIT="$ROOT/scripts/keliver-init"
RELAY="$ROOT/portal-relay/build/install/portal-relay/bin/portal-relay"
while [ $# -gt 0 ]; do
  case "$1" in
    --init) INIT="${2:?--init needs a value}"; shift 2 ;;
    --relay) RELAY="${2:?--relay needs a value}"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
rm -rf "$DISP"; mkdir -p "$DISP/home"; DISP="$(cd "$DISP" && pwd -P)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$DISP/home"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
export NODE_EXTRA_CA_CERTS="${NODE_EXTRA_CA_CERTS:-$HOME/.android-certs/full-ca-bundle.pem}"
pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }

( cd "$DISP" && env -u KELIVER_USE_MAVEN_LOCAL KELIVER_VERSION="${KELIVER_VERSION:-0.3.3}" \
    "$INIT" Tree > "$DISP/init.log" 2>&1 ) || { echo "scaffold failed"; cat "$DISP/init.log"; exit 1; }
APP="$DISP/tree"
[ -f "$APP/.gitignore" ] && ok "the scaffold ships a .gitignore" || bad "no .gitignore scaffolded"

( cd "$APP" && git init -q && git add -A \
    && git -c user.email=t@example.invalid -c user.name=t commit -qm scaffold )
( cd "$APP" && [ -z "$(git status --porcelain)" ] ) && ok "clean immediately after scaffold" \
  || { bad "dirty immediately after scaffold"; ( cd "$APP" && git status --porcelain | sed 's/^/        /' ); }

# .gitignore must be tracked, and the pointer must not be
( cd "$APP" && git ls-files --error-unmatch .gitignore >/dev/null 2>&1 ) \
  && ok ".gitignore is committed with the app" || bad ".gitignore is not tracked"

# shellcheck source=/dev/null
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
keliver_require_isolated_store "$DISP" "$APP" || exit 1

PORT="$(python3 -c "import json;print(json.load(open('$APP/keliver.portal.json')).get('port',8077))")"
( cd "$APP" && PORTAL_REPO="$APP" "$RELAY" > "$DISP/relay.log" 2>&1 & )
for _ in $(seq 1 40); do curl -sf -m 2 -o /dev/null "http://localhost:$PORT/screens" && break; sleep 3; done
curl -sf -m 2 -o /dev/null "http://localhost:$PORT/screens" || bad "the relay did not start"
curl -s -o /dev/null "http://localhost:$PORT/doc"     # opening the editor
sleep 3
lsof -ti :"$PORT" -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null; sleep 2

STATUS="$( cd "$APP" && git status --porcelain )"
[ -z "$STATUS" ] && ok "clean after an ordinary portal run" \
  || { bad "the portal left files in the adopter's tree:"; printf '%s\n' "$STATUS" | sed 's/^/        /'; }

( cd "$APP" && env -u KELIVER_USE_MAVEN_LOCAL ./gradlew compileKotlinJs --console=plain -q \
    > "$DISP/build.log" 2>&1 ) || bad "the scaffolded app did not compile"
STATUS="$( cd "$APP" && git status --porcelain )"
[ -z "$STATUS" ] && ok "clean after a build" \
  || { bad "the build left tracked-visible files:"; printf '%s\n' "$STATUS" | sed 's/^/        /'; }

# the store pointer exists but is invisible to git
[ -f "$APP/.gradle/keliver-store-path" ] && ok "the store pointer was written" || bad "no store pointer"
( cd "$APP" && git check-ignore -q .gradle/keliver-store-path ) \
  && ok "the store pointer is ignored by git" || bad "the store pointer is NOT ignored"

# and the store itself is still outside the app
STORE="$("$ROOT/scripts/keliver-store-path.sh" "$APP")"
case "$STORE" in "$APP"/*) bad "the store is inside the app: $STORE" ;; *) ok "the store is outside the app" ;; esac

echo
echo "passed: $pass   failed: $fail"
[ "$fail" -eq 0 ]
