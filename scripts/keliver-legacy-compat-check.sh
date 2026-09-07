#!/usr/bin/env bash
#
# Upgrade behaviour: a pre-relocation store's identity, bundles and documents
# must not silently disappear, and must be adoptable without being guessed at.
#
#   scripts/keliver-legacy-compat-check.sh <disposable-root>
#
# Uses DISPOSABLE legacy fixtures only. The isolation guard runs before any
# relay starts.
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DISP="${1:?usage: $0 <disposable-root>}"
# A unique run dir beneath the caller's parent; never erase what the caller
# supplied. See keliver_make_run_dir.
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "$DISP" legacy-compat)" || exit 1
mkdir -p "$DISP/home" "$DISP/app"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$DISP/home"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }

# --- a disposable app, and a disposable LEGACY store beside it ---------------
APP="$DISP/app"
mkdir -p "$APP/src/jsMain/kotlin/screens"
cat > "$APP/keliver.portal.json" <<'JSON'
{ "screensDir": "src/jsMain/kotlin/screens", "port": 8131 }
JSON
cat > "$APP/src/jsMain/kotlin/screens/home.kt" <<'KT'
package app.screens
import androidx.compose.runtime.Composable
import dev.keliver.material.compose.StyledText
@Composable fun HomeScreen(b: HomeScreenBindings) { StyledText(text = b.t, fontSize = 14) }
interface HomeScreenBindings { val t: String }
KT
LEGACY="$DISP/home/.keliver-portal"
mkdir -p "$LEGACY/keys" "$LEGACY/bundles/v1" "$LEGACY/default"
printf 'DISPOSABLE-FIXTURE-PRIVATE-KEY\n' > "$LEGACY/keys/ed25519.priv"
printf 'DISPOSABLE-FIXTURE-PUBLIC-KEY\n'  > "$LEGACY/keys/ed25519.pub"
printf '{"version":1}\n'                  > "$LEGACY/bundles/v1/meta.json"
printf '{"root":{"type":"Column"}}\n'     > "$LEGACY/default/legacyscreen.json"
printf 'default\nlegacyscreen\n'          > "$LEGACY/active"
# Per-app stores legitimately live UNDER the global root (apps/), so the
# invariant is "no pre-existing legacy file changed", not "the directory is
# byte-identical".
legacy_files() { ( cd "$LEGACY" && find . -type f -not -path './apps/*' | sort | xargs shasum ); }
LEGACY_BEFORE="$(legacy_files)"

# shellcheck source=/dev/null
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
keliver_require_isolated_store "$DISP" "$APP" || { echo "guard refused"; exit 1; }

echo "legacy compatibility   legacy=$LEGACY"

# --- 1. the relay must ANNOUNCE the legacy contents, not silently ignore them
( cd "$APP" && PORTAL_REPO="$APP" "$ROOT/portal-relay/build/install/portal-relay/bin/portal-relay" \
    > "$DISP/relay.log" 2>&1 & )
for _ in $(seq 1 40); do curl -sf -m 2 -o /dev/null http://localhost:8131/screens && break; sleep 3; done
curl -sf -m 2 -o /dev/null http://localhost:8131/screens || bad "relay did not start"
lsof -ti :8131 -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null; sleep 2

grep -q "legacy shared store" "$DISP/relay.log" && ok "the relay announces the legacy store" \
  || bad "the legacy store was not mentioned at all"
grep -q "a signing identity" "$DISP/relay.log" && ok "it names the signing identity" || bad "identity not named"
grep -q "1 published bundle" "$DISP/relay.log" && ok "it names the bundles" || bad "bundles not named"
grep -q "documents for project" "$DISP/relay.log" && ok "it names the documents" || bad "documents not named"
grep -q "keliver-adopt-legacy-store" "$DISP/relay.log" && ok "it points at the adopt route" || bad "no route offered"

# --- 2. nothing was adopted automatically ------------------------------------
NEW_STORE="$("$ROOT/scripts/keliver-store-path.sh" "$APP")"
[ -e "$NEW_STORE/keys/ed25519.priv" ] && grep -q DISPOSABLE-FIXTURE "$NEW_STORE/keys/ed25519.priv" 2>/dev/null \
  && bad "the legacy identity was adopted automatically" \
  || ok "nothing was adopted automatically"

# --- 3. the explicit adopt route works ---------------------------------------
"$ROOT/scripts/keliver-adopt-legacy-store.sh" "$APP" --legacy "$LEGACY" > "$DISP/adopt.log" 2>&1 \
  && ok "adopt ran" || { bad "adopt failed"; cat "$DISP/adopt.log"; }
for rel in keys/ed25519.priv keys/ed25519.pub bundles/v1/meta.json default/legacyscreen.json; do
  [ -f "$NEW_STORE/$rel" ] && ok "adopted: $rel" || bad "missing after adopt: $rel"
done

# --- 4. the legacy directory is untouched ------------------------------------
if [ "$LEGACY_BEFORE" = "$(legacy_files)" ]; then
  ok "every pre-existing legacy file is byte-identical (read-only)"
else
  bad "a pre-existing legacy file changed"; diff <(echo "$LEGACY_BEFORE") <(legacy_files) | head -5
fi

# --- 5. a second adopt does not clobber --------------------------------------
printf 'LOCAL-EDIT\n' > "$NEW_STORE/keys/ed25519.pub"
"$ROOT/scripts/keliver-adopt-legacy-store.sh" "$APP" --legacy "$LEGACY" > "$DISP/adopt2.log" 2>&1
grep -q "LOCAL-EDIT" "$NEW_STORE/keys/ed25519.pub" && ok "re-adopt does not overwrite existing files" \
  || bad "re-adopt clobbered an existing file"
grep -q "skip (exists)" "$DISP/adopt2.log" && ok "skips are reported" || bad "skips not reported"

echo
echo "passed: $pass   failed: $fail"
[ "$fail" -eq 0 ]
