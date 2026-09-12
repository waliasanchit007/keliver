#!/usr/bin/env bash
#
# keliver-store-identity-repro — the U23 / U24 / U25.1 reproductions.
#
#   scripts/keliver-store-identity-repro.sh <disposable-root> [--relay BIN]
#
# Every scenario asserts the behaviour docs/STORE_IDENTITY.md defines, so this
# script FAILS on the code as released in tools 0.3.4 and PASSES on the fix.
# It is the failing-before/passing-after evidence and the regression at once.
#
#   R1  a renamed app keeps one identity, or is refused — never silently a new one
#   R2  the recovery the conflict message recommends actually works
#   R3  the real path and a symlink to it resolve one store
#   R4  Kotlin and the shell mirror agree, including on a Unicode directory name
#
# Disposable by construction: its own HOME, its own stores, its own throwaway
# signing keys. The isolation guard runs before every relay start. Private key
# material is never read or printed; identities are compared by a fingerprint
# of the PUBLIC key.
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DISP_PARENT="${1:?usage: $0 <disposable-root> [--relay BIN]}"; shift
RELAY="$ROOT/portal-relay/build/install/portal-relay/bin/portal-relay"
while [ $# -gt 0 ]; do
  case "$1" in
    --relay) RELAY="${2:?--relay needs a value}"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -x "$RELAY" ] || { echo "no relay at $RELAY (./gradlew :portal-relay:installDist)" >&2; exit 2; }

. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "$DISP_PARENT" store-identity)" || exit 1
mkdir -p "$DISP/home"
# Deliberately NOT `export HOME`. The store expands "~/" through the JVM's
# user.home, which macOS takes from the passwd entry and not from $HOME, so
# exporting HOME would not isolate anything — and it would make the guard's
# own "is this the developer's real store?" check compare against the fake
# home and mis-report. -Duser.home is what actually moves the store.
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$DISP/home"
STORE_PATH_SH="$ROOT/scripts/keliver-store-path.sh"

pass=0; fail=0
ok(){   printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){  printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
note(){ printf '        %s\n' "$1"; }

# --- helpers -----------------------------------------------------------------

# A minimal app repo. $1 = directory, $2 = port.
mkapp() {
  local d="$1" port="$2" name
  name="$(basename "$d" | tr -cd 'a-zA-Z0-9' )"; [ -n "$name" ] || name=app
  mkdir -p "$d/src/jsMain/kotlin/screens"
  printf '{ "screensDir": "src/jsMain/kotlin/screens", "port": %s }\n' "$port" > "$d/keliver.portal.json"
  cat > "$d/src/jsMain/kotlin/screens/Home.kt" <<KT
package $name.screens
import androidx.compose.runtime.Composable
import dev.keliver.material.compose.StyledText
@Composable fun HomeScreen(b: HomeScreenBindings) { StyledText(text = b.t, fontSize = 14) }
interface HomeScreenBindings { val t: String }
KT
}

# The identity of a store, as a fingerprint of its PUBLIC key. Never touches
# ed25519.priv. Prints "-" when the store has no identity yet.
fingerprint() {
  local pub="$1/keys/ed25519.pub"
  if [ -r "$pub" ]; then shasum -a 256 "$pub" | cut -c1-16; else echo "-"; fi
}

# Start the relay for an app and wait. Sets:
#   BOOT_RC    0 when it came up, 1 when it exited/never answered
#   BOOT_LOG   the log file
#   BOOT_STORE the store it recorded in the app's pointer ("" when it did not boot)
# Stops the relay before returning. Only ever kills the pid it started.
boot() {
  local app="$1" port="$2" tag="$3"
  BOOT_LOG="$DISP/$tag.log"; BOOT_STORE=""; BOOT_RC=1
  keliver_require_isolated_store "$DISP" "$app" > "$DISP/$tag.guard" 2>&1 || {
    note "guard refused for $tag:"; sed 's/^/          /' "$DISP/$tag.guard"; return 1; }
  keliver_port_free_or_die "$port" || return 1
  ( cd "$app" && PORTAL_REPO="$app" "$RELAY" > "$BOOT_LOG" 2>&1 ) & local pid=$!
  local i
  for i in $(seq 1 45); do
    if curl -sf -m 2 -o /dev/null "http://localhost:$port/devstate"; then BOOT_RC=0; break; fi
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  # Stop anything still alive, ours only: the subshell above and the JVM it
  # spawned. A relay that hung without answering must not outlive this call.
  kill -0 "$pid" 2>/dev/null && keliver_kill_own "$port" "$pid"
  wait "$pid" 2>/dev/null
  # The JVM releases the port a moment after the subshell is reaped. Wait for
  # it, or the next scenario refuses a port this run still owns.
  for i in $(seq 1 30); do
    lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1 || break
    sleep 1
  done
  # The store the relay RESOLVED, read from the pointer it writes after a
  # successful claim. Parsing the banner would be ambiguous: paths contain
  # spaces and the banner puts two fields on one line.
  BOOT_STORE="$(cat "$app/.gradle/keliver-store-path" 2>/dev/null | tr -d '\n')"
  return 0
}

# The shell mirror's answer for an app.
via_shell() { "$STORE_PATH_SH" "$1" --home "$DISP/home" 2>&1; }

echo "=== store identity reproductions"
echo "    disposable root: $DISP"
echo "    relay:           $RELAY"
echo

# --- R1: rename / move -------------------------------------------------------
echo "--- R1  renaming an app directory must not silently mint a new identity"
A1="$DISP/apps/myapp"; mkapp "$A1" 8151
boot "$A1" 8151 r1-before || exit 2
[ "$BOOT_RC" = 0 ] || { bad "R1 the relay did not start at the original path"; sed 's/^/        /' "$BOOT_LOG" | tail -20; }
S1="$BOOT_STORE"; F1="$(fingerprint "$S1")"
note "before: store=$S1"
note "before: identity=$F1"

A2="$DISP/apps/renamed"; mv "$A1" "$A2"
boot "$A2" 8151 r1-after || exit 2
S2="$BOOT_STORE"; F2="$(fingerprint "$S2")"
if [ "$BOOT_RC" = 0 ]; then
  note "after:  store=$S2"
  note "after:  identity=$F2"
  if [ "$F2" = "$F1" ] && [ -n "$F1" ] && [ "$F1" != "-" ]; then
    ok "R1 the renamed app kept its signing identity"
  else
    bad "R1 the renamed app silently got a DIFFERENT identity ($F1 -> $F2)"
  fi
else
  if grep -q "has moved\|keliver-store-recover" "$BOOT_LOG"; then
    ok "R1 the renamed app was refused, and told how to recover"
    note "$(grep -m1 'has moved' "$BOOT_LOG" || true)"
  else
    bad "R1 the relay refused, but not with a relocation message"
    tail -15 "$BOOT_LOG" | sed 's/^/        /'
  fi
fi
[ -d "$S1" ] && ok "R1 the original store still exists (nothing was destroyed)" \
             || bad "R1 the original store is gone"

# --- R2: recovery must exist, and must not dead-end --------------------------
echo
echo "--- R2a  the refusal must name a recovery that works"
# Exactly what the shipped 0.3.4 conflict message told the user to do: point the
# moved app's "store" at the directory that holds its identity. That claim is
# still refused — it has to be, the store is recorded against another path —
# but the refusal must not dead-end, which is U24.
set_store() {
  python3 - "$A2/keliver.portal.json" "$1" <<'PYEOF'
import json, sys
p, store = sys.argv[1], sys.argv[2]
cfg = json.load(open(p))
if store:
    cfg["store"] = store
else:
    cfg.pop("store", None)
json.dump(cfg, open(p, "w"), indent=2)
PYEOF
}
set_store "$S1"
boot "$A2" 8151 r2a || exit 2
if [ "$BOOT_RC" = 0 ]; then
  note "the relay started; the store was adopted without an explicit recovery"
  bad "R2a a claim by a different path was accepted"
elif grep -q "keliver-store-recover.sh" "$BOOT_LOG"; then
  ok "R2a refused, and named a recovery command"
  grep -m1 "keliver-store-recover.sh" "$BOOT_LOG" | sed 's/^/        /'
else
  bad "R2a refused with no way forward - this is U24"
  grep -m4 "store conflict\|owner:\|this:\|Fix:" "$BOOT_LOG" | sed 's/^/        /'
fi

echo
echo "--- R2b  the named recovery preserves the identity"
# No config edit: the pointer that travelled with the directory is enough.
set_store ""
if "$ROOT/scripts/keliver-store-recover.sh" "$A2" --home "$DISP/home" > "$DISP/r2b-recover.log" 2>&1; then
  sed 's/^/        /' "$DISP/r2b-recover.log"
  ok "R2b the recovery command accepted the relocation"
else
  sed 's/^/        /' "$DISP/r2b-recover.log"
  bad "R2b the recovery command refused a genuine relocation"
fi
boot "$A2" 8151 r2b || exit 2
if [ "$BOOT_RC" = 0 ]; then
  F3="$(fingerprint "$BOOT_STORE")"
  [ "$BOOT_STORE" = "$S1" ] && ok "R2b the moved app is back on its original store" \
                            || bad "R2b the moved app resolved $BOOT_STORE, not $S1"
  [ "$F3" = "$F1" ] && ok "R2b the signing identity survived the relocation ($F1)" \
                    || bad "R2b the identity changed across recovery ($F1 -> $F3)"
else
  bad "R2b the relay still did not start after recovery"
  tail -20 "$BOOT_LOG" | sed 's/^/        /'
fi

# --- R3: symlink vs real path ------------------------------------------------
echo
echo "--- R3  a symlink and the real path are one app, so one store"
REAL="$DISP/apps/app-v2"; mkapp "$REAL" 8152
LINK="$DISP/apps/current"; ln -s "$REAL" "$LINK"
boot "$REAL" 8152 r3-real || exit 2
SR="$BOOT_STORE"; FR="$(fingerprint "$SR")"
boot "$LINK" 8152 r3-link || exit 2
SL="$BOOT_STORE"; FL="$(fingerprint "$SL")"
note "via real path: $SR"
note "via symlink:   $SL"
if [ "$BOOT_RC" != 0 ]; then
  bad "R3 the relay did not start through the symlink"
  tail -15 "$BOOT_LOG" | sed 's/^/        /'
elif [ "$SR" = "$SL" ]; then
  ok "R3 both paths resolved the same store"
  [ "$FR" = "$FL" ] && ok "R3 both paths use the same signing identity" \
                    || bad "R3 same store but different identity ($FR vs $FL)"
  # And the name must come from the REAL directory, not whichever path was
  # typed first - otherwise the agreement is only the pointer papering over a
  # default that still differs.
  case "$(basename "$SR")" in
    app-v2-*) ok "R3 the store is named for the real directory" ;;
    *) bad "R3 the store is named $(basename "$SR"), not for the real directory" ;;
  esac
else
  bad "R3 one app split across two stores, chosen by which path was typed"
  note "identities: $FR vs $FL"
fi

# --- R4: Kotlin vs the shell mirror ------------------------------------------
echo
echo "--- R4  the Kotlin resolver and the shell mirror must agree"
# The shell answer is taken BEFORE the relay runs. Once the relay has written
# .gradle/keliver-store-path both sides read that pointer and agree trivially;
# the divergence is in the DEFAULT derivation, which is all a first run has.
agree() { # label, app-dir, port, tag
  local label="$1" app="$2" port="$3" tag="$4" sh
  sh="$(via_shell "$app")"
  boot "$app" "$port" "$tag" || exit 2
  note "$label kotlin: $BOOT_STORE"
  note "$label shell:  $sh"
  if [ "$BOOT_RC" != 0 ]; then
    bad "R4 $label: the relay did not start"; tail -12 "$BOOT_LOG" | sed 's/^/        /'
  elif [ "$BOOT_STORE" = "$sh" ]; then ok "R4 $label agrees"
  else bad "R4 $label DISAGREES"
  fi
}
# A symlinked app is where they part company: the hash is canonical in both,
# the slug is canonical only in the shell.
REAL2="$DISP/apps/app-v3"; mkapp "$REAL2" 8153
LINK2="$DISP/apps/live"; ln -s "$REAL2" "$LINK2"
agree "symlinked path" "$LINK2" 8153 r4-link

# A Unicode directory name: Kotlin's slug filter is ASCII, Python's isalnum()
# is Unicode-aware, and an astral character is two UTF-16 units but one
# code point.
UNI="$DISP/apps/café-☕"; mkapp "$UNI" 8154
agree "unicode name" "$UNI" 8154 r4-unicode

echo
echo "passed: $pass   failed: $fail"
echo "evidence: $DISP"
[ "$fail" -eq 0 ]
