#!/usr/bin/env bash
#
# keliver-key-permissions-check — U27/U29: this app's private signing key is
# owner-only when the relay creates it, stays the same identity across
# restarts, is never silently re-permissioned or rotated, half a key pair is
# never completed by generating, and the relay will not publish with either.
#
#   scripts/keliver-key-permissions-check.sh <disposable-parent> [relay-bin] [adopt-script]
#
# relay-bin and adopt-script default to this checkout's build and script; pass
# a released bundle's `relay/bin/portal-relay` and `bin/keliver-adopt-legacy-store.sh`
# to measure that release instead (the before-the-fix reproduction does).
#
# Optional, and reported as SKIP when absent:
#   KELIVER_PROBE_USER  an existing unrelated local account, usable through
#                       `sudo -n -u`. The check then tries to READ the key as
#                       that user. It first proves the user can reach a
#                       world-readable canary in the store directory, so a
#                       denial comes from keys/ or the key, not from the path
#                       leading to the store.
#   a filesystem that ignores modes — FAT, via hdiutil on macOS or a loop mount
#                       with passwordless sudo on Linux.
#
# ISOLATION. Every relay runs with the JVM's user.home inside a run directory
# made by keliver_make_run_dir, behind keliver_require_isolated_store. The run
# directory is made 0755 on purpose: mktemp makes it 0700, which would hide the
# store's own modes from the cross-user probe and make every probe pass.
# NO KEY MATERIAL IS PRINTED. Keys are compared by hash in shell variables and
# by `cmp`; logs are searched for the private key with grep -F reading the
# pattern from the file, and only the verdict is printed.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
PARENT="${1:?usage: $0 <disposable-parent> [relay-bin] [adopt-script]}"
RELAY="${2:-$ROOT/portal-relay/build/install/portal-relay/bin/portal-relay}"
ADOPT="${3:-$ROOT/scripts/keliver-adopt-legacy-store.sh}"
[ -x "$RELAY" ] || { echo "no relay at $RELAY (./gradlew :portal-relay:installDist)" >&2; exit 2; }
[ -x "$ADOPT" ] || { echo "no adopt script at $ADOPT" >&2; exit 2; }
# shellcheck source=/dev/null
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "$PARENT" key-perms)" || exit $?
chmod 755 "$DISP" || exit 1
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
export JAVA_HOME
unset PORTAL_STORE
PORT=8147
SHA="$(command -v sha256sum || echo 'shasum -a 256')"
pass=0; fail=0; skip=0
ok()   { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
skip() { printf '  SKIP  %s\n' "$1"; skip=$((skip+1)); }

if stat -c %a / >/dev/null 2>&1; then mode_of() { stat -c %a "$1" 2>/dev/null; }
else mode_of() { stat -f %Lp "$1" 2>/dev/null; }; fi
hash_of() { $SHA < "$1" | cut -c1-64; }
# A mode is owner-only when its group and other digits are 0.
owner_only() { case "$1" in *00) return 0;; *) return 1;; esac; }

echo "relay:  $RELAY"
echo "adopt:  $ADOPT"
echo "run:    $DISP"
echo "umask:  $(umask)"

# --- an app, a home, a store --------------------------------------------------
new_app() { # <name> -> sets APP H STORE
  APP="$DISP/$1/app"; H="$DISP/$1/home"
  mkdir -p "$APP/src/jsMain/kotlin/screens" "$H"
  printf '{ "screensDir": "src/jsMain/kotlin/screens", "port": %s }\n' "$PORT" > "$APP/keliver.portal.json"
  cat > "$APP/src/jsMain/kotlin/screens/home.kt" <<'KT'
package screens
import androidx.compose.runtime.Composable
@Composable fun HomeScreen(b: HomeScreenBindings) { StyledText(text = b.t, fontSize = 14) }
interface HomeScreenBindings { val t: String }
KT
  STORE="$(JAVA_TOOL_OPTIONS="-Duser.home=$H" "$ROOT/scripts/keliver-store-path.sh" "$APP" --home "$H")" || return 1
  case "$STORE" in "$DISP"/*) ;; *) echo "store outside the run dir: $STORE" >&2; return 1;; esac
}

# Start the relay for $APP with the JVM's home at $H, under umask $1. Returns
# when it answers or exits; RELAY_RC is its exit code if it exited, else "".
start_relay() { # <umask> <log> [env...]
  local u="$1" log="$2"; shift 2
  keliver_port_free_or_die "$PORT" || exit 2
  JAVA_TOOL_OPTIONS="-Duser.home=$H" keliver_require_isolated_store "$DISP" "$APP" > /dev/null \
    || { echo "guard refused for $APP" >&2; exit 2; }
  ( umask "$u"; cd "$APP" && exec env JAVA_TOOL_OPTIONS="-Duser.home=$H" PORTAL_REPO="$APP" "$@" "$RELAY" ) > "$log" 2>&1 &
  RELAY_PID=$!; RELAY_RC=""
  local i
  for i in $(seq 1 60); do
    if ! kill -0 "$RELAY_PID" 2>/dev/null; then wait "$RELAY_PID"; RELAY_RC=$?; return 0; fi
    curl -sf -m 2 -o /dev/null "http://localhost:$PORT/screens" && return 0
    sleep 1
  done
  echo "relay neither answered nor exited in 60s ($log)" >&2
  RELAY_RC=hang; keliver_kill_own "$PORT" "$RELAY_PID"; wait "$RELAY_PID" 2>/dev/null
}
stop_relay() { [ -z "$RELAY_RC" ] && { keliver_kill_own "$PORT" "$RELAY_PID"; wait "$RELAY_PID" 2>/dev/null; }; sleep 1; }
# adopt resolves the store through the JVM's user.home, so it gets the same guard.
adopt() { # <log> [args...]
  local log="$1"; shift
  JAVA_TOOL_OPTIONS="-Duser.home=$H" keliver_require_isolated_store "$DISP" "$APP" > /dev/null \
    || { echo "guard refused for $APP" >&2; exit 2; }
  ( JAVA_TOOL_OPTIONS="-Duser.home=$H" "$ADOPT" "$APP" "$@" ) > "$log" 2>&1
}

no_key_in() { # <label> <private key file> <log>...
  local label="$1" key="$2"; shift 2
  [ -s "$key" ] || { bad "$label: no private key to look for"; return; }
  if grep -qF -f "$key" "$@" 2>/dev/null; then bad "$label: the private key appears in a log"
  else ok "$label: the private key appears in no log"; fi
}

probe_read() { # <label> <store> <file>
  local label="$1" store="$2" file="$3"
  if [ -z "${KELIVER_PROBE_USER:-}" ]; then skip "$label: cross-user read (set KELIVER_PROBE_USER)"; return; fi
  if ! sudo -n -u "$KELIVER_PROBE_USER" true 2>/dev/null; then skip "$label: cross-user read (no sudo -n -u $KELIVER_PROBE_USER)"; return; fi
  local canary="$store/.u27-canary"
  printf 'canary\n' > "$canary" && chmod 644 "$canary"
  if ! sudo -n -u "$KELIVER_PROBE_USER" cat "$canary" > /dev/null 2>&1; then
    bad "$label: $KELIVER_PROBE_USER cannot read a world-readable canary in the store, so a denial below would prove nothing"
    rm -f "$canary"; return
  fi
  if sudo -n -u "$KELIVER_PROBE_USER" cat "$file" > /dev/null 2>&1; then
    bad "$label: $KELIVER_PROBE_USER (uid $(id -u "$KELIVER_PROBE_USER")) READ the private key"
  else
    ok "$label: $KELIVER_PROBE_USER can reach the store but cannot read the private key"
  fi
  rm -f "$canary"
}

traversal() { # <store>: every directory from the run dir down to the key
  local p="$1/keys/ed25519.priv" line=""
  while [ "$p" != "$DISP" ] && [ "$p" != "/" ]; do line="$(basename "$p")=$(mode_of "$p") $line"; p="$(dirname "$p")"; done
  echo "    modes: run=$(mode_of "$DISP") $line"
}

# --- A. a fresh key, default umask --------------------------------------------
echo "=== A. a fresh store under umask 022"
new_app a || exit 1
start_relay 022 "$DISP/a/relay1.log"
[ -z "$RELAY_RC" ] && ok "A: the relay started" || bad "A: the relay exited $RELAY_RC"
stop_relay
K="$STORE/keys"
traversal "$STORE"
m="$(mode_of "$K/ed25519.priv")"; owner_only "$m" && ok "A: ed25519.priv is $m" || bad "A: ed25519.priv is $m"
m="$(mode_of "$K")"; owner_only "$m" && ok "A: keys/ is $m" || bad "A: keys/ is $m"
[ -s "$K/ed25519.pub" ] && ok "A: ed25519.pub exists ($(mode_of "$K/ed25519.pub"))" || bad "A: no ed25519.pub"
[ -z "$(ls -A "$K" | grep -v -e '^ed25519.priv$' -e '^ed25519.pub$')" ] && ok "A: nothing else in keys/" || bad "A: keys/ holds $(ls -A "$K" | tr '\n' ' ')"
grep -q "as read back" "$DISP/a/relay1.log" && ok "A: the relay reports the modes it read back" \
  || bad "A: no read-back report ($(grep -m1 'signing' "$DISP/a/relay1.log"))"
probe_read A "$STORE" "$K/ed25519.priv"
A_PRIV="$(hash_of "$K/ed25519.priv")"; A_PUB="$(hash_of "$K/ed25519.pub")"
echo "    public key fingerprint: ${A_PUB:0:16}"

# --- B. restart keeps the identity --------------------------------------------
echo "=== B. restart"
start_relay 022 "$DISP/a/relay2.log"
stop_relay
[ "$(hash_of "$K/ed25519.priv")" = "$A_PRIV" ] && ok "B: same private key after restart" || bad "B: the private key CHANGED"
[ "$(hash_of "$K/ed25519.pub")" = "$A_PUB" ] && ok "B: same public key after restart" || bad "B: the public key CHANGED"
grep -q "generated" "$DISP/a/relay2.log" && bad "B: the restart generated a key" || ok "B: nothing generated on restart"
no_key_in "A/B" "$K/ed25519.priv" "$DISP/a/relay1.log" "$DISP/a/relay2.log"

# --- C. umask 000 cannot widen it -----------------------------------------------
echo "=== C. a fresh store under umask 000"
new_app c || exit 1
start_relay 000 "$DISP/c/relay.log"
stop_relay
traversal "$STORE"
m="$(mode_of "$STORE/keys/ed25519.priv")"; owner_only "$m" && ok "C: ed25519.priv is $m under umask 000" || bad "C: ed25519.priv is $m under umask 000"
m="$(mode_of "$STORE/keys")"; owner_only "$m" && ok "C: keys/ is $m under umask 000" || bad "C: keys/ is $m under umask 000"
m="$(mode_of "$STORE/keys/ed25519.pub")"; case "$m" in *[2367]?|*[2367]) bad "C: ed25519.pub is $m, writable by others";; *) ok "C: ed25519.pub is $m, not writable by others";; esac
# The store directory itself was created by the relay's store claim under this
# umask, so it is world-writable: another user could move keys/ aside. That is
# not a key the relay created, and it is reported, not changed.
m="$(mode_of "$STORE")"
if [ "$m" = 777 ]; then
  grep -q "store directory" "$DISP/c/relay.log" && ok "C: the world-writable store directory ($m) is reported at start" \
    || bad "C: the store directory is $m and the start said nothing"
else
  ok "C: the store directory is $m"
fi
probe_read C "$STORE" "$STORE/keys/ed25519.priv"

# --- D. an existing exposed key: reported, never changed, not signed with -------
echo "=== D. an existing key with the pre-U27 modes (0644 in 0755)"
new_app d || exit 1
start_relay 022 "$DISP/d/relay0.log"; stop_relay
K="$STORE/keys"
chmod 755 "$K" && chmod 644 "$K/ed25519.priv"
D_PRIV="$(hash_of "$K/ed25519.priv")"
start_relay 022 "$DISP/d/relay1.log"
if [ -z "$RELAY_RC" ]; then
  ok "D: the relay still starts (editing does not use the key)"
  curl -s -m 30 -X POST "http://localhost:$PORT/publish" > "$DISP/d/publish1.txt" 2>&1
fi
stop_relay
grep -q "not owner-only" "$DISP/d/relay1.log" && ok "D: the start warns that the identity is not owner-only" || bad "D: no warning at start"
grep -q "chmod 700 '$K' && chmod 600 '$K/ed25519.priv'" "$DISP/d/relay1.log" && ok "D: the warning names the exact commands" || bad "D: the commands are not in the warning"
grep -q "publish REFUSED" "$DISP/d/publish1.txt" 2>/dev/null && ok "D: publish is refused" \
  || bad "D: publish was not refused: $(head -c 160 "$DISP/d/publish1.txt" 2>/dev/null | tr '\n' ' ')"
[ "$(mode_of "$K/ed25519.priv")" = 644 ] && [ "$(mode_of "$K")" = 755 ] && ok "D: the modes were not changed" \
  || bad "D: the relay changed the modes to $(mode_of "$K")/$(mode_of "$K/ed25519.priv")"
[ "$(hash_of "$K/ed25519.priv")" = "$D_PRIV" ] && ok "D: the identity was not rotated" || bad "D: the private key CHANGED"
# The printed commands, run as printed. They name only paths in this run.
CMD="$(grep -m1 "^    chmod o-w '" "$DISP/d/relay1.log" | sed 's/^    //')"
case "$CMD" in *"$DISP"*) bash -c "$CMD" && ok "D: the printed commands ran" || bad "D: the printed commands failed";;
  *) bad "D: no runnable command was printed";; esac
start_relay 022 "$DISP/d/relay2.log"
if [ -z "$RELAY_RC" ]; then
  ok "D: the relay starts after tightening"
  curl -s -m 30 -X POST "http://localhost:$PORT/publish" > "$DISP/d/publish2.txt" 2>&1
else
  bad "D: the relay did not start after tightening ($RELAY_RC)"
fi
stop_relay
grep -q "not owner-only" "$DISP/d/relay2.log" && bad "D: still warned after tightening" || ok "D: no warning after tightening"
# Past the gate means it went on to run the app's Gradle build — which this
# fixture app does not have, so that is the failure it must reach.
if grep -q "publish REFUSED" "$DISP/d/publish2.txt" 2>/dev/null; then bad "D: publish still refused after tightening"
elif grep -q "gradlew" "$DISP/d/publish2.txt" 2>/dev/null; then ok "D: publish passes the key gate after tightening, and reaches the build (no gradlew in this fixture)"
else bad "D: publish after tightening did not reach the build: $(head -c 160 "$DISP/d/publish2.txt" 2>/dev/null | tr '\n' ' ')"; fi
[ "$(hash_of "$K/ed25519.priv")" = "$D_PRIV" ] && ok "D: same identity after tightening" || bad "D: the private key CHANGED"
no_key_in D "$K/ed25519.priv" "$DISP"/d/relay*.log "$DISP"/d/publish*.txt

# --- E. half an identity is refused, not regenerated ----------------------------
echo "=== E. half an identity"
for missing in ed25519.pub ed25519.priv; do
  new_app "e-$missing" || exit 1
  start_relay 022 "$DISP/e-$missing/relay0.log"; stop_relay
  K="$STORE/keys"
  kept=ed25519.priv; [ "$missing" = ed25519.priv ] && kept=ed25519.pub
  before="$(hash_of "$K/$kept")"
  mv "$K/$missing" "$DISP/e-$missing/set-aside"
  start_relay 022 "$DISP/e-$missing/relay1.log"
  if [ -z "$RELAY_RC" ]; then
    ok "E($missing): the relay still starts (editing does not use the key)"
    curl -s -m 30 -X POST "http://localhost:$PORT/publish" > "$DISP/e-$missing/publish.txt" 2>&1
  else
    bad "E($missing): the relay did not start ($RELAY_RC)"
  fi
  stop_relay
  grep -q "half of this app's signing identity" "$DISP/e-$missing/relay1.log" && ok "E($missing): the start says why" || bad "E($missing): no explanation at start"
  grep -q "publish REFUSED" "$DISP/e-$missing/publish.txt" 2>/dev/null && ok "E($missing): publish is refused" \
    || bad "E($missing): publish was not refused: $(head -c 120 "$DISP/e-$missing/publish.txt" 2>/dev/null | tr '\n' ' ')"
  [ "$(hash_of "$K/$kept")" = "$before" ] && ok "E($missing): $kept was not replaced" || bad "E($missing): $kept was REPLACED (identity rotated)"
  [ -e "$K/$missing" ] && bad "E($missing): a new $missing was created" || ok "E($missing): no new $missing was created"
  rm -f "$DISP/e-$missing/set-aside"
done

# --- F. a filesystem that ignores modes ------------------------------------------
echo "=== F. a filesystem that does not enforce permissions (FAT)"
FAT="$DISP/fat"; mkdir -p "$FAT"; FAT_UNMOUNT=""
if [ "$(uname)" = Darwin ] && command -v hdiutil >/dev/null 2>&1; then
  hdiutil create -quiet -size 16m -fs MS-DOS -volname KPERM "$DISP/fat.dmg" \
    && hdiutil attach -quiet -nobrowse -mountpoint "$FAT" "$DISP/fat.dmg" && FAT_UNMOUNT="hdiutil detach -quiet $FAT"
elif sudo -n true 2>/dev/null && command -v mkfs.vfat >/dev/null 2>&1; then
  truncate -s 16M "$DISP/fat.img" && mkfs.vfat -n KPERM "$DISP/fat.img" > /dev/null \
    && sudo -n mount -o loop,uid="$(id -u)",gid="$(id -g)",umask=022 "$DISP/fat.img" "$FAT" && FAT_UNMOUNT="sudo -n umount $FAT"
fi
if [ -n "$FAT_UNMOUNT" ]; then
  new_app f || exit 1
  STORE="$FAT/store"
  start_relay 022 "$DISP/f/relay.log" PORTAL_STORE="$STORE"; stop_relay
  echo "    what the filesystem reports for keys/: $(mode_of "$STORE/keys" || echo none)"
  [ -n "$RELAY_RC" ] && [ "$RELAY_RC" != 0 ] && ok "F: the relay refused to start (exit $RELAY_RC)" || bad "F: the relay started with a key it cannot protect"
  grep -q "does not enforce permissions" "$DISP/f/relay.log" && grep -q "No key was created" "$DISP/f/relay.log" \
    && ok "F: it says the filesystem does not enforce permissions, and that no key was created" \
    || bad "F: no accurate reason: $(grep -m1 -i 'key' "$DISP/f/relay.log")"
  [ -e "$STORE/keys/ed25519.priv" ] && bad "F: a private key was left on the filesystem" || ok "F: no private key was created"
  left="$(ls -A "$STORE/keys" 2>/dev/null | tr '\n' ' ')"
  [ -z "$left" ] && ok "F: nothing staged was left behind" || bad "F: keys/ holds $left"
  grep -q "generated" "$DISP/f/relay.log" && bad "F: it claimed to have generated a key" || ok "F: no generation claimed"
  # Adoption into a store on the same filesystem: refused, and accurately.
  new_app f2 || exit 1
  LEG="$DISP/f2/legacy"; mkdir -p "$LEG/keys"
  ( umask 077; python3 -c "import os,sys; open(sys.argv[1],'w').write(os.urandom(32).hex())" "$LEG/keys/ed25519.priv" )
  printf '%s' "$(printf 'cd%.0s' $(seq 1 32))" > "$LEG/keys/ed25519.pub"
  PORTAL_STORE="$FAT/store2" adopt "$DISP/f2/adopt.log" --legacy "$LEG"; rc=$?
  [ "$rc" != 0 ] && ok "F: adopt onto it exits $rc" || bad "F: adopt onto it exited 0"
  grep -q "does not enforce permissions" "$DISP/f2/adopt.log" && grep -q "was NOT copied" "$DISP/f2/adopt.log" \
    && ok "F: adopt says the key was not copied, and why" || bad "F: adopt's report: $(grep -m1 'priv' "$DISP/f2/adopt.log")"
  [ -e "$FAT/store2/keys/ed25519.priv" ] && bad "F: adopt left a private key there" || ok "F: adopt left no private key there"
  no_key_in F "$LEG/keys/ed25519.priv" "$DISP/f/relay.log" "$DISP/f2/adopt.log"
  $FAT_UNMOUNT || echo "    (could not unmount $FAT)"
else
  skip "F: no way to mount a FAT filesystem here (hdiutil, or passwordless sudo + mkfs.vfat)"
fi

# --- G. adoption copies the key owner-only, and leaves the legacy store alone ----
echo "=== G. keliver-adopt-legacy-store.sh"
new_app g || exit 1
LEG="$H/.keliver-portal"; mkdir -p "$LEG/keys"
( umask 022; "${SHA%% *}" --version > /dev/null 2>&1; python3 -c "import os,sys; open(sys.argv[1],'w').write(os.urandom(32).hex())" "$LEG/keys/ed25519.priv" )
printf '%s' "$(printf 'ab%.0s' $(seq 1 32))" > "$LEG/keys/ed25519.pub"
chmod 644 "$LEG/keys/ed25519.priv"
L_PRIV="$(hash_of "$LEG/keys/ed25519.priv")"
adopt "$DISP/g/adopt.log" --legacy "$LEG"; rc=$?
[ "$rc" = 0 ] && ok "G: adopt exited 0" || { bad "G: adopt exited $rc"; sed 's/^/    /' "$DISP/g/adopt.log"; }
m="$(mode_of "$STORE/keys/ed25519.priv")"; owner_only "$m" && ok "G: the adopted private key is $m" || bad "G: the adopted private key is $m"
m="$(mode_of "$STORE/keys")"; owner_only "$m" && ok "G: the adopted keys/ is $m" || bad "G: the adopted keys/ is $m"
[ "$(hash_of "$STORE/keys/ed25519.priv")" = "$L_PRIV" ] && ok "G: the adopted key is the legacy identity" || bad "G: the adopted key differs"
cmp -s "$STORE/keys/ed25519.pub" "$LEG/keys/ed25519.pub" && ok "G: the public key came with it (a pair)" || bad "G: the adopted public key is not the legacy one"
[ "$(mode_of "$LEG/keys/ed25519.priv")" = 644 ] && ok "G: the legacy copy was not changed (still 644)" || bad "G: the legacy copy's mode changed"
grep -q "readable by other users" "$DISP/g/adopt.log" && ok "G: adopt says the legacy copy is readable by others" || bad "G: adopt does not mention the legacy copy's exposure"
[ -z "$(ls -A "$STORE/keys" | grep -v -e '^ed25519.priv$' -e '^ed25519.pub$')" ] && ok "G: no temporary file left in keys/" || bad "G: keys/ holds $(ls -A "$STORE/keys" | tr '\n' ' ')"
# --force over an existing target key with the pre-U27 mode.
chmod 644 "$STORE/keys/ed25519.priv"
printf '%s' "$(printf 'ef%.0s' $(seq 1 32))" > "$STORE/keys/ed25519.pub"
adopt "$DISP/g/adopt-force.log" --legacy "$LEG" --force; rc=$?
m="$(mode_of "$STORE/keys/ed25519.priv")"; owner_only "$m" && ok "G: --force over a 644 key leaves $m" || bad "G: --force over a 644 key leaves $m"
cmp -s "$STORE/keys/ed25519.pub" "$LEG/keys/ed25519.pub" && ok "G: --force replaced BOTH halves" || bad "G: --force left a mismatched pair"
# Half a legacy identity is not adopted.
new_app g2 || exit 1
LEG="$H/.keliver-portal"; mkdir -p "$LEG/keys"
( umask 077; python3 -c "import os,sys; open(sys.argv[1],'w').write(os.urandom(32).hex())" "$LEG/keys/ed25519.priv" )
adopt "$DISP/g2/adopt.log" --legacy "$LEG"; rc=$?
[ "$rc" != 0 ] && grep -q "half an identity" "$DISP/g2/adopt.log" && ok "G: half a legacy identity is refused (exit $rc)" \
  || bad "G: half a legacy identity: rc=$rc, $(grep -m1 'keys' "$DISP/g2/adopt.log")"
[ -e "$STORE/keys/ed25519.priv" ] && bad "G: half a legacy identity was copied" || ok "G: nothing of it was copied"
# An existing keys/ that other users can write is not written into.
new_app g3 || exit 1
LEG="$H/.keliver-portal"; mkdir -p "$LEG/keys" "$STORE/keys"
( umask 077; python3 -c "import os,sys; open(sys.argv[1],'w').write(os.urandom(32).hex())" "$LEG/keys/ed25519.priv" )
printf '%s' "$(printf '12%.0s' $(seq 1 32))" > "$LEG/keys/ed25519.pub"
chmod 777 "$STORE/keys"
adopt "$DISP/g3/adopt.log" --legacy "$LEG"; rc=$?
[ "$rc" != 0 ] && grep -q "others can write keys/" "$DISP/g3/adopt.log" && ok "G: a keys/ others can write is refused (exit $rc)" \
  || bad "G: a 777 keys/: rc=$rc, $(grep -m1 'keys' "$DISP/g3/adopt.log")"
[ -e "$STORE/keys/ed25519.priv" ] && bad "G: the key was written into a 777 keys/" || ok "G: nothing was written there"
[ -z "$(ls -A "$STORE/keys")" ] && ok "G: no staging left behind" || bad "G: keys/ holds $(ls -A "$STORE/keys" | tr '\n' ' ')"

# --- every private key this run made, against every log and response it wrote --
echo "=== no key material in any log"
leaks=0; keys_seen=0
while IFS= read -r k; do
  [ -s "$k" ] || continue
  keys_seen=$((keys_seen+1))
  if find "$DISP" -type f \( -name '*.log' -o -name '*.txt' \) -print0 | xargs -0 grep -lF -f "$k" 2>/dev/null | grep -q .; then
    leaks=$((leaks+1))
  fi
done <<KEYS
$(find "$DISP" -name ed25519.priv -type f 2>/dev/null)
KEYS
[ "$keys_seen" -gt 0 ] && [ "$leaks" -eq 0 ] && ok "no log or response holds any of the $keys_seen private keys this run made" \
  || bad "$leaks of $keys_seen private keys appear in a log or response"

echo
echo "key-permissions: $pass passed, $fail failed, $skip skipped   ($DISP)"
[ "$fail" -eq 0 ]
