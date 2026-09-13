#!/usr/bin/env bash
#
# keliver-store-recovery-check — the outcomes that matter after the U23/U24/
# U25.1 correction, exercised through the PACKAGED commands from disposable
# external apps.
#
#   scripts/keliver-store-recovery-check.sh <disposable-root>
#
#   C1  a bundle signed before a relocation still verifies afterwards
#   C2  the app restarts normally after recovery
#   C3  an unrelated app stays isolated, and is still refused this store
#   C4  two apps racing to recover one store: one wins, the loser changes
#       nothing — not the store, not its own
#   C5  the packaged keliver-portal launcher starts the recovered app
#
# The commands under test run from a bundle-shaped staging directory
# (bin/ + relay/, assembled exactly as scripts/build-portal-tools.sh does) so
# that the paths inside them are the bundle's, not the repository's.
#
# Disposable by construction: its own user.home, its own stores, its own
# throwaway signing keys. The isolation guard runs before every relay start.
# ed25519.priv is never read by this script — signing happens inside the JVM
# (StoreRelocationSignatureTest) and identities are compared by a fingerprint
# of the PUBLIC key.
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DISP_PARENT="${1:?usage: $0 <disposable-root>}"
# /usr/libexec/java_home is macOS-only; on Linux (CI) JAVA_HOME is already set
# by setup-java. Falling through with an empty value would be worse than saying so.
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x /usr/libexec/java_home ]; then JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
  [ -n "${JAVA_HOME:-}" ] || { echo "JAVA_HOME is not set and cannot be discovered" >&2; exit 2; }
fi
export JAVA_HOME

. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "$DISP_PARENT" store-recovery)" || exit 1
mkdir -p "$DISP/home"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$DISP/home"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

pass=0; fail=0
ok(){   printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){  printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
note(){ printf '        %s\n' "$1"; }

# --- the bundle under test ---------------------------------------------------
BUNDLE="$DISP/bundle"
mkdir -p "$BUNDLE/bin" "$BUNDLE/relay"
# BUILD WHAT WE STAGE. This suite copies the INSTALLED relay into its bundle,
# and `:portal-relay:compileKotlin` does not refresh that — so a source edit
# followed by a compile left it testing the previous binary, which is how a
# refusal message and the assertion that greps it drifted apart while the run
# reported green. Comparing mtimes is not enough either: Gradle correctly skips
# a rebuild when content is unchanged, and the stale-looking timestamp is then
# a false alarm. So just build it, every time; it is up-to-date in seconds.
echo "==> refreshing the relay this suite stages"
( cd "$ROOT" && ./gradlew --console=plain -q :portal-relay:installDist ) || {
  echo "could not build the relay; refusing to report results for whatever is on disk" >&2
  exit 2
}
RELAY_BIN="$ROOT/portal-relay/build/install/portal-relay/bin/portal-relay"
[ -x "$RELAY_BIN" ] || { echo "no relay at $RELAY_BIN" >&2; exit 2; }
cp -R "$ROOT/portal-relay/build/install/portal-relay/." "$BUNDLE/relay/"
cp "$ROOT/scripts/keliver-portal" "$ROOT/scripts/keliver-store-path.sh" \
   "$ROOT/scripts/keliver-store-recover.sh" "$ROOT/scripts/keliver-adopt-legacy-store.sh" "$BUNDLE/bin/"
chmod +x "$BUNDLE/bin/"*
RELAY="$BUNDLE/relay/bin/portal-relay"
RECOVER="$BUNDLE/bin/keliver-store-recover.sh"
RESOLVE="$BUNDLE/bin/keliver-store-path.sh"

echo "=== store recovery outcomes"
echo "    disposable root: $DISP"
echo "    bundle:          $BUNDLE"
echo

# --- helpers -----------------------------------------------------------------
mkapp() {
  local d="$1" port="$2" name
  name="$(basename "$d" | tr -cd 'a-zA-Z0-9')"; [ -n "$name" ] || name=app
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

# sha256sum on Linux, shasum on macOS. Resolved to a COMMAND, not a shell
# function: `xargs -0 sha256` below cannot call a function, and macOS has a
# /sbin/sha256 binary that would silently answer for it in a different output
# format — while Linux, where CI runs, has no such binary at all.
if command -v sha256sum >/dev/null 2>&1; then SHA256_CMD="sha256sum"; else SHA256_CMD="shasum -a 256"; fi
sha256(){ $SHA256_CMD "$@"; }

fingerprint() {
  if [ -r "$1/keys/ed25519.pub" ]; then sha256 "$1/keys/ed25519.pub" | cut -c1-16
  elif [ -d "$1" ]; then echo "none yet"; else echo "(no store)"; fi
}

# Snapshot a directory's content, key material included BY HASH ONLY.
snapshot() { [ -d "$1" ] && (cd "$1" && find . -type f -print0 | sort -z | xargs -0 $SHA256_CMD) 2>/dev/null || echo "(absent)"; }

# A disposable store that looks like a real one: an owner marker and a
# throwaway public key. No private key is written, so nothing here can sign.
mkstore() { # dir, owner-path, pubkey-hex
  mkdir -p "$1/keys"
  printf '%s\n' "$2" > "$1/owner"
  printf '%s' "$3" > "$1/keys/ed25519.pub"
}

# What the ORDINARY resolver selects for an app — the question every recovery
# has to answer afterwards. Prints "" and returns non-zero when it refuses.
effective() { "$RESOLVE" "$1" --home "$DISP/home"; }

boot() {
  local app="$1" port="$2" tag="$3"
  BOOT_LOG="$DISP/$tag.log"; BOOT_STORE=""; BOOT_RC=1
  keliver_require_isolated_store "$DISP" "$app" > "$DISP/$tag.guard" 2>&1 || {
    note "guard refused for $tag:"; sed 's/^/          /' "$DISP/$tag.guard"; return 1; }
  keliver_port_free_or_die "$port" || return 1
  ( cd "$app" && PORTAL_REPO="$app" "$RELAY" > "$BOOT_LOG" 2>&1 ) & local pid=$!
  local i
  for i in $(seq 1 45); do
    curl -sf -m 2 -o /dev/null "http://localhost:$port/devstate" && { BOOT_RC=0; break; }
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  kill -0 "$pid" 2>/dev/null && keliver_kill_own "$port" "$pid"
  wait "$pid" 2>/dev/null
  for i in $(seq 1 30); do
    lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1 || break
    sleep 1
  done
  BOOT_STORE="$(cat "$app/.gradle/keliver-store-path" 2>/dev/null | tr -d '\n')"
  return 0
}

# --- C1: a bundle signed before relocation still verifies afterwards ---------
echo "--- C1  a bundle signed before the move still verifies after it"
P1="$DISP/apps/checkout"; mkapp "$P1" 8161
boot "$P1" 8161 c1-first || exit 2
[ "$BOOT_RC" = 0 ] || { bad "C1 the app did not start"; tail -20 "$BOOT_LOG" | sed 's/^/        /'; }
S="$BOOT_STORE"; FP="$(fingerprint "$S")"
note "store:    $S"
note "identity: $FP"

SRC_MANIFEST="$ROOT/portal-device-guest/build/zipline/Development/manifest.zipline.json"
if [ ! -f "$SRC_MANIFEST" ]; then
  note "no built manifest; producing one (:portal-device-guest:compileDevelopmentZipline)"
  ( cd "$ROOT" && ./gradlew --console=plain -q :portal-device-guest:compileDevelopmentZipline \
      > "$DISP/guest-build.log" 2>&1 ) || note "guest build failed; see $DISP/guest-build.log"
fi
# A driven test that SKIPPED is not a passed test. -D reaches the Gradle JVM,
# not the forked test JVM, unless the build forwards it (U26) — so every driven
# step below is checked against its own result XML, not against `gradlew`'s
# exit status.
assert_ran() { # class-name, label
  local xml="$ROOT/portal-relay/build/test-results/test/TEST-$1.xml"
  if [ ! -f "$xml" ]; then bad "$2: no test result XML was produced"; return 1; fi
  if grep -q "skipped (no " "$xml"; then bad "$2: the test SKIPPED — it proved nothing"; return 1; fi
  local counts; counts="$(grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' "$xml" | head -1)"
  case "$counts" in
    *'tests="0"'*) bad "$2: no tests ran ($counts)"; return 1 ;;
    *'failures="0" errors="0"'*) note "$counts"; return 0 ;;
    *) bad "$2: $counts"; return 1 ;;
  esac
}

SIGNED="$DISP/signed-manifest.json"
if [ -f "$SRC_MANIFEST" ]; then
  ( cd "$ROOT" && ./gradlew --console=plain -q :portal-relay:test --rerun-tasks \
      --tests '*StoreRelocationSignatureTest*' \
      -Dkeliver.sign.privkey="$S/keys/ed25519.priv" \
      -Dkeliver.sign.manifest="$SRC_MANIFEST" \
      -Dkeliver.sign.out="$SIGNED" > "$DISP/sign.log" 2>&1 )
  if assert_ran StoreRelocationSignatureTest "C1 signing" && [ -s "$SIGNED" ]; then
    ok "C1 a real Zipline manifest was signed with this store's identity"
  else
    bad "C1 signing produced nothing usable"; tail -20 "$DISP/sign.log" | sed 's/^/        /'
  fi
else
  bad "C1 no Zipline manifest available to sign"
fi

P2="$DISP/apps/checkout-renamed"; mv "$P1" "$P2"
boot "$P2" 8161 c1-moved || exit 2
[ "$BOOT_RC" = 0 ] && bad "C1 the moved app started without an explicit recovery" \
                   || ok "C1 the moved app was refused before any identity was minted"

if "$RECOVER" "$P2" --home "$DISP/home" > "$DISP/c1-recover.log" 2>&1; then
  ok "C1 the packaged recovery command accepted the move"
  grep -E '^identity' "$DISP/c1-recover.log" | sed 's/^/        /'
else
  bad "C1 the packaged recovery command refused the move"
  sed 's/^/        /' "$DISP/c1-recover.log"
fi

boot "$P2" 8161 c1-after || exit 2
AFTER_STORE="$BOOT_STORE"
[ "$BOOT_RC" = 0 ] && [ "$AFTER_STORE" = "$S" ] \
  && ok "C1 the moved app is serving from its original store" \
  || { bad "C1 after recovery the app resolved '$AFTER_STORE' (expected $S)"; tail -15 "$BOOT_LOG" | sed 's/^/        /'; }
[ "$(fingerprint "$AFTER_STORE")" = "$FP" ] && ok "C1 the public-key fingerprint is unchanged ($FP)" \
                                            || bad "C1 the identity changed across the move"

if [ -s "$SIGNED" ] && [ -n "$AFTER_STORE" ]; then
  ( cd "$ROOT" && ./gradlew --console=plain -q :portal-relay:test --rerun-tasks \
      --tests '*SignedBundleVerificationTest*' \
      -Dkeliver.verify.manifest="$SIGNED" \
      -Dkeliver.verify.pubkey="$AFTER_STORE/keys/ed25519.pub" > "$DISP/verify.log" 2>&1 )
  vrc=$?
  if [ $vrc -ne 0 ]; then
    bad "C1 the pre-move signed bundle does NOT verify after the move"
    tail -25 "$DISP/verify.log" | sed 's/^/        /'
  elif assert_ran SignedBundleVerificationTest "C1 verification"; then
    # Two tests ran: the genuine verification and the tamper rejection. Both
    # are required, so a vacuous "it verifies" cannot pass alone.
    ok "C1 the pre-move signed bundle verifies against the post-move public key"
  fi
else
  bad "C1 could not run the post-move verification"
fi

# --- C2: restart -------------------------------------------------------------
echo
echo "--- C2  the recovered app restarts normally"
boot "$P2" 8161 c2 || exit 2
[ "$BOOT_RC" = 0 ] && [ "$BOOT_STORE" = "$S" ] \
  && ok "C2 a second start after recovery is uneventful" \
  || { bad "C2 the recovered app did not restart cleanly"; tail -15 "$BOOT_LOG" | sed 's/^/        /'; }

# --- C3: unrelated apps ------------------------------------------------------
echo
echo "--- C3  an unrelated app is unaffected, and still refused this store"
Q="$DISP/apps/other"; mkapp "$Q" 8162
boot "$Q" 8162 c3-own || exit 2
QS="$BOOT_STORE"; QFP="$(fingerprint "$QS")"
QSNAP="$(snapshot "$QS")"
[ "$BOOT_RC" = 0 ] && [ -n "$QS" ] && [ "$QS" != "$S" ] \
  && ok "C3 the unrelated app took a store of its own" \
  || bad "C3 the unrelated app resolved '$QS' (the other app's store is $S)"
[ "$QFP" != "$FP" ] && ok "C3 it has its own signing identity" \
                    || bad "C3 two unrelated apps share one identity"

# Now aim it squarely at the recovered app's store.
python3 - "$Q/keliver.portal.json" "$S" <<'PYEOF'
import json, sys
p, store = sys.argv[1], sys.argv[2]
cfg = json.load(open(p)); cfg["store"] = store
json.dump(cfg, open(p, "w"), indent=2)
PYEOF
SSNAP_BEFORE="$(snapshot "$S")"
boot "$Q" 8162 c3-foreign || exit 2
[ "$BOOT_RC" = 0 ] && bad "C3 a foreign app was allowed into another app's store" \
                   || ok "C3 the foreign claim was refused"
[ "$(snapshot "$S")" = "$SSNAP_BEFORE" ] && ok "C3 the refused claim changed nothing in the store" \
                                         || bad "C3 the store changed during a refused claim"
# And recovery must refuse it too: the owner is live and still uses this store.
if "$RECOVER" "$Q" --store "$S" --home "$DISP/home" > "$DISP/c3-recover.log" 2>&1; then
  bad "C3 recovery handed a live app's store to an unrelated app"
else
  ok "C3 recovery refused an unrelated app while the owner is live"
  head -8 "$DISP/c3-recover.log" | sed 's/^/        /'
fi
[ "$(snapshot "$S")" = "$SSNAP_BEFORE" ] && ok "C3 the refused recovery changed nothing in the store" \
                                         || bad "C3 the refused recovery modified the store"
[ "$(snapshot "$QS")" = "$QSNAP" ] && ok "C3 the unrelated app's own store is untouched" \
                                   || bad "C3 the unrelated app's store was modified"

# --- C4: two apps racing to recover one store --------------------------------
echo
echo "--- C4  two plausible claimants race to recover one store"
# Both carry the pointer (one is the move, one is a cp -a of it) and the
# recorded owner path no longer exists, so both pass every pre-check. Only the
# lock decides.
ORIG="$DISP/apps/race-orig"; R1="$DISP/apps/race-a"; R2="$DISP/apps/race-b"
mkapp "$ORIG" 8163
boot "$ORIG" 8163 c4-seed || exit 2
RS="$BOOT_STORE"; RFP="$(fingerprint "$RS")"
[ -n "$RS" ] || { bad "C4 could not seed a store"; }
cp -R "$ORIG" "$R1"; cp -R "$ORIG" "$R2"
# Retire the recorded owner path so neither claimant is it and neither can be
# refused for "the owner is still live". Nothing is deleted; it is moved aside.
mv "$ORIG" "$DISP/apps/race-orig-retired"
rm -f "$DISP/apps/race-orig-retired/.gradle/keliver-store-path"
RSNAP_BEFORE="$(snapshot "$RS")"
"$RECOVER" "$R1" --home "$DISP/home" > "$DISP/c4-a.log" 2>&1 & pa=$!
"$RECOVER" "$R2" --home "$DISP/home" > "$DISP/c4-b.log" 2>&1 & pb=$!
wait "$pa"; ra=$?
wait "$pb"; rb=$?
note "race-a exit=$ra  race-b exit=$rb"
WON=$(( (ra == 0 ? 1 : 0) + (rb == 0 ? 1 : 0) ))
[ "$WON" = 1 ] && ok "C4 exactly one claimant won" \
               || { bad "C4 $WON claimants won (expected 1)"; sed 's/^/    a: /' "$DISP/c4-a.log"; sed 's/^/    b: /' "$DISP/c4-b.log"; }
OWNER_NOW="$(tr -d '\n' < "$RS/owner")"
{ [ "$OWNER_NOW" = "$R1" ] || [ "$OWNER_NOW" = "$R2" ]; } \
  && ok "C4 the marker names exactly one of the claimants" \
  || bad "C4 the owner marker is $OWNER_NOW"
# Everything except the owner marker must be byte-identical.
# Filter by path, not by line shape: sha256sum prints "<hash>  ./owner" and
# BSD shasum can print "SHA256 (./owner) = <hash>".
DIFF="$(diff <(echo "$RSNAP_BEFORE" | grep -v '\./owner') <(snapshot "$RS" | grep -v '\./owner') || true)"
[ -z "$DIFF" ] && ok "C4 nothing but the owner marker changed" \
               || { bad "C4 the race modified more than the marker"; echo "$DIFF" | sed 's/^/        /'; }
[ "$(fingerprint "$RS")" = "$RFP" ] && ok "C4 the identity survived the race ($RFP)" \
                                    || bad "C4 the identity changed during the race"
# The loser must have no store of its own conjured up, and must still be refused.
LOSER="$R1"; [ "$OWNER_NOW" = "$R1" ] && LOSER="$R2"
boot "$LOSER" 8163 c4-loser || exit 2
[ "$BOOT_RC" = 0 ] && bad "C4 the losing app started against the store it did not win" \
                   || ok "C4 the losing app is still refused"

# --- C5: the packaged launcher ----------------------------------------------
echo
echo "--- C5  the packaged keliver-portal starts the recovered app"
if keliver_port_free_or_die 8161 && keliver_port_free_or_die 8096; then
  keliver_require_isolated_store "$DISP" "$P2" > "$DISP/c5.guard" 2>&1 \
    && sed 's/^/        /' "$DISP/c5.guard" \
    || { note "guard refused"; sed 's/^/        /' "$DISP/c5.guard"; }
  # keliver-portal stays in the foreground until Ctrl-C, so start it detached
  # and stop it with its own subcommand — which is also the thing under test.
  ( cd "$P2" && "$BUNDLE/bin/keliver-portal" --no-editor-build "$P2" > "$DISP/c5-start.log" 2>&1 ) &
  C5_PID=$!
  UP=0
  for _ in $(seq 1 60); do
    curl -sf -m 2 -o /dev/null http://localhost:8161/devstate && { UP=1; break; }
    kill -0 "$C5_PID" 2>/dev/null || break
    sleep 2
  done
  if [ "$UP" = 1 ]; then
    ok "C5 the packaged launcher started the recovered app"
    grep -m1 "store=" "$DISP/c5-start.log" | sed 's/^/        /' || true
  else
    bad "C5 the packaged launcher did not bring the app up"
    tail -25 "$DISP/c5-start.log" | sed 's/^/        /'
  fi
  ( cd "$P2" && "$BUNDLE/bin/keliver-portal" stop "$P2" > "$DISP/c5-stop.log" 2>&1 )
  wait "$C5_PID" 2>/dev/null
  if curl -sf -m 3 -o /dev/null http://localhost:8161/devstate; then
    bad "C5 the app is still answering after stop"
    keliver_kill_own 8161 "$C5_PID"
  else
    ok "C5 the packaged launcher stopped it again"
  fi
else
  bad "C5 could not run — 8161 or 8096 is in use by something this run does not own"
fi


# --- C6: a recovery that cannot write the pointer must not report success ----
echo
echo "--- C6  recovery reports success only when the binding actually works"
c6_app() { # dir, store, [port] -> an app already pointing at <store>
  local d="$1" store="$2" port="${3:-8164}"
  mkapp "$d" "$port"
  mkdir -p "$d/.gradle"
  printf '%s\n' "$store" > "$d/.gradle/keliver-store-path"
}
# (a) a pre-existing invalid destination: .gradle is a regular FILE.
A6="$DISP/apps/c6-file"
S6="$DISP/home/.keliver-portal/apps/c6-store-aaaaaaa1"
mkstore "$S6" "$DISP/apps/c6-gone" "$(printf 'ab%.0s' $(seq 1 32))"
mkapp "$A6" 8164
printf 'not a directory\n' > "$A6/.gradle"
S6SNAP="$(snapshot "$S6")"
if "$RECOVER" "$A6" --store "$S6" --home "$DISP/home" > "$DISP/c6a.log" 2>&1; then
  bad "C6a recovery reported success with no writable pointer destination"
  sed 's/^/        /' "$DISP/c6a.log"
else
  ok "C6a recovery refused when the pointer destination is unusable"
fi
[ "$(snapshot "$S6")" = "$S6SNAP" ] && ok "C6a the store was not modified" \
                                    || bad "C6a the owner was rewritten anyway"

# (b) a failure DURING the update. KELIVER_RECOVER_FAIL_POINTER is a fault
# injector that exists for exactly this: the rollback path is otherwise
# unreachable from outside the process, and untested rollback is not rollback.
A6B="$DISP/apps/c6-midway"
S6B="$DISP/home/.keliver-portal/apps/c6b-store-aaaaaaa2"
mkstore "$S6B" "$DISP/apps/c6b-gone" "$(printf 'cd%.0s' $(seq 1 32))"
c6_app "$A6B" "$S6B"
OWNER_BEFORE="$(cat "$S6B/owner")"
if KELIVER_RECOVER_FAIL_POINTER=1 "$RECOVER" "$A6B" --store "$S6B" --home "$DISP/home" \
     > "$DISP/c6b.log" 2>&1; then
  bad "C6b recovery reported success after the pointer write failed"
else
  ok "C6b recovery failed when the pointer write failed mid-update"
fi
[ "$(cat "$S6B/owner")" = "$OWNER_BEFORE" ] \
  && ok "C6b the previous owner marker was restored" \
  || bad "C6b the owner marker was left rewritten: $(cat "$S6B/owner")"

# (c) the positive case must be checked by RESOLVING, not by exit status.
A6C="$DISP/apps/c6-good"
S6C="$DISP/home/.keliver-portal/apps/c6c-store-aaaaaaa3"
mkstore "$S6C" "$DISP/apps/c6c-gone" "$(printf 'ef%.0s' $(seq 1 32))"
c6_app "$A6C" "$S6C"
if "$RECOVER" "$A6C" --store "$S6C" --home "$DISP/home" > "$DISP/c6c.log" 2>&1; then
  ok "C6c a valid recovery succeeded"
else
  bad "C6c a valid recovery was refused"; sed 's/^/        /' "$DISP/c6c.log"
fi
[ "$(effective "$A6C")" = "$S6C" ] \
  && ok "C6c the ordinary resolver now selects the recovered store" \
  || bad "C6c the resolver selects $(effective "$A6C" || echo '<refused>'), not $S6C"

# --- C7: the EFFECTIVE binding, not just the default -------------------------
echo
echo "--- C7  recovery respects configuration, the pointer, and precedence"
# (a) the app is configured for store B; recovering A would leave it on B.
A7="$DISP/apps/c7-configured"
S7A="$DISP/home/.keliver-portal/apps/c7a-store-aaaaaaa4"
S7B="$DISP/stores/c7-b"
mkstore "$S7A" "$DISP/apps/c7-gone" "$(printf '11%.0s' $(seq 1 32))"
mkstore "$S7B" "$DISP/apps/c7-configured" "$(printf '22%.0s' $(seq 1 32))"
mkapp "$A7" 8164
python3 - "$A7/keliver.portal.json" "$S7B" <<'PYEOF'
import json, sys
p, store = sys.argv[1], sys.argv[2]
cfg = json.load(open(p)); cfg["store"] = store
json.dump(cfg, open(p, "w"), indent=2)
PYEOF
S7A_SNAP="$(snapshot "$S7A")"; S7B_SNAP="$(snapshot "$S7B")"
if "$RECOVER" "$A7" --store "$S7A" --home "$DISP/home" > "$DISP/c7a.log" 2>&1; then
  bad "C7a recovery bound a store the app does not resolve to"
  note "resolver still selects: $(effective "$A7" || echo '<refused>')"
else
  ok "C7a recovery refused a store the configuration overrides"
fi
[ "$(snapshot "$S7A")" = "$S7A_SNAP" ] && [ "$(snapshot "$S7B")" = "$S7B_SNAP" ] \
  && ok "C7a neither store was modified" || bad "C7a a store was modified by a refused recovery"

# (b) the app's pointer already names a DIFFERENT store that has an identity.
A7B="$DISP/apps/c7-pointed"
S7C="$DISP/home/.keliver-portal/apps/c7c-store-aaaaaaa5"
S7D="$DISP/stores/c7-d"
mkstore "$S7C" "$DISP/apps/c7b-gone" "$(printf '33%.0s' $(seq 1 32))"
mkstore "$S7D" "$DISP/apps/c7-pointed" "$(printf '44%.0s' $(seq 1 32))"
mkapp "$A7B" 8164
mkdir -p "$A7B/.gradle"; printf '%s\n' "$S7D" > "$A7B/.gradle/keliver-store-path"
S7C_SNAP="$(snapshot "$S7C")"
if "$RECOVER" "$A7B" --store "$S7C" --home "$DISP/home" > "$DISP/c7b.log" 2>&1; then
  bad "C7b recovery ignored an existing pointer to another identity"
else
  ok "C7b recovery refused while the pointer names another identity"
fi
[ "$(snapshot "$S7C")" = "$S7C_SNAP" ] && ok "C7b the target store was not modified" \
                                       || bad "C7b the target store was modified"

# (c) a resolver REFUSAL must not be read as "this app has no store".
A7C="$DISP/apps/c7-split"
mkapp "$A7C" 8164
H7="$(python3 -c "import hashlib,os,sys;print(hashlib.sha256(os.path.realpath(sys.argv[1]).encode()).hexdigest()[:8])" "$A7C")"
mkstore "$DISP/home/.keliver-portal/apps/one-$H7"  "$A7C" "$(printf '55%.0s' $(seq 1 32))"
mkstore "$DISP/home/.keliver-portal/apps/two-$H7"  "$A7C" "$(printf '66%.0s' $(seq 1 32))"
S7E="$DISP/home/.keliver-portal/apps/c7e-store-aaaaaaa6"
# The recorded owner EXISTS and resolves somewhere else. That matters: checking
# it runs the resolver a second time, and the split diagnosis below has to stay
# about THIS app rather than about whatever the old owner resolves to.
mkapp "$DISP/apps/c7e-other" 8164
mkstore "$S7E" "$DISP/apps/c7e-other" "$(printf '77%.0s' $(seq 1 32))"
if "$RECOVER" "$A7C" --store "$S7E" --home "$DISP/home" > "$DISP/c7c.log" 2>&1; then
  bad "C7c a resolver refusal was swallowed and read as 'no store'"
else
  ok "C7c a resolver refusal was reported, not swallowed"
  head -8 "$DISP/c7c.log" | sed 's/^/        /'
fi
grep -q "split across stores" "$DISP/c7c.log" \
  && ok "C7c the refusal names the split, not the old owner's resolution" \
  || bad "C7c the reported reason is not this app's split"

# (d) PORTAL_STORE is a ONE-RUN override and must not decide a binding.
A7D="$DISP/apps/c7-env"
S7F="$DISP/home/.keliver-portal/apps/c7f-store-aaaaaaa7"
S7G="$DISP/stores/c7-override"
mkstore "$S7F" "$DISP/apps/c7f-gone" "$(printf '88%.0s' $(seq 1 32))"
mkstore "$S7G" "$DISP/apps/c7-env" "$(printf '99%.0s' $(seq 1 32))"
c6_app "$A7D" "$S7F"
if PORTAL_STORE="$S7G" "$RECOVER" "$A7D" --home "$DISP/home" > "$DISP/c7d.log" 2>&1; then
  POINTED="$(tr -d '\n' < "$A7D/.gradle/keliver-store-path" 2>/dev/null)"
  [ "$POINTED" != "$S7G" ] && ok "C7d PORTAL_STORE did not become the binding" \
                           || bad "C7d a one-run override was written into the pointer"
else
  ok "C7d recovery refused rather than let PORTAL_STORE decide"
fi
grep -qi "PORTAL_STORE" "$DISP/c7d.log" && ok "C7d the override was reported, not silently applied" \
                                        || bad "C7d PORTAL_STORE was neither reported nor refused"

# --- C8: the same-owner split ------------------------------------------------
echo
echo "--- C8  a split where BOTH markers already name this app"
# claimStoreFor writes the CANONICAL path, so after a symlink split both stores
# record the same owner. "owner == me" is therefore not evidence that the
# app-side binding exists, or that it selects this store.
A8="$DISP/apps/c8-app"
mkapp "$A8" 8165
H8="$(python3 -c "import hashlib,os,sys;print(hashlib.sha256(os.path.realpath(sys.argv[1]).encode()).hexdigest()[:8])" "$A8")"
KEEP="$DISP/home/.keliver-portal/apps/current-$H8"
OTHER="$DISP/home/.keliver-portal/apps/c8-app-$H8"
mkstore "$KEEP"  "$A8" "$(printf 'aa%.0s' $(seq 1 32))"
mkstore "$OTHER" "$A8" "$(printf 'bb%.0s' $(seq 1 32))"
OTHER_FP="$(fingerprint "$OTHER")"; OTHER_SNAP="$(snapshot "$OTHER")"
effective "$A8" > /dev/null 2>&1 && bad "C8 the split was not detected at all" \
                                 || ok "C8 the resolver refuses the split before recovery"
if "$RECOVER" "$A8" --store "$KEEP" --home "$DISP/home" > "$DISP/c8.log" 2>&1; then
  ok "C8 recovery accepted an explicitly chosen store in a same-owner split"
else
  bad "C8 recovery refused the documented way out of a split"
  sed 's/^/        /' "$DISP/c8.log"
fi
[ "$(effective "$A8" 2>/dev/null)" = "$KEEP" ] \
  && ok "C8 ordinary resolution now selects the chosen store" \
  || bad "C8 resolution selects '$(effective "$A8" 2>/dev/null || echo '<refused>')', not $KEEP"
[ "$(fingerprint "$OTHER")" = "$OTHER_FP" ] && [ "$(snapshot "$OTHER")" = "$OTHER_SNAP" ] \
  && ok "C8 the unselected store and its identity are untouched" \
  || bad "C8 the unselected store was modified"
boot "$A8" 8165 c8-boot || BOOT_RC=1
[ "$BOOT_RC" = 0 ] && [ "$BOOT_STORE" = "$KEEP" ] \
  && ok "C8 the relay starts and serves from the chosen store" \
  || { bad "C8 the relay did not come up on the chosen store (got '$BOOT_STORE')"; tail -12 "$BOOT_LOG" | sed 's/^/        /'; }

# --- C9: two recoveries, two DIFFERENT stores, one app -----------------------
echo
echo "--- C9  two recoveries targeting different stores for one app"
# A per-store lock does not serialize these: they never contend for it.
A9="$DISP/apps/c9-app"
S9A="$DISP/home/.keliver-portal/apps/c9a-store-aaaaaab1"
S9B="$DISP/home/.keliver-portal/apps/c9b-store-aaaaaab2"
mkstore "$S9A" "$DISP/apps/c9-gone" "$(printf '1a%.0s' $(seq 1 32))"
mkstore "$S9B" "$DISP/apps/c9-gone" "$(printf '2b%.0s' $(seq 1 32))"
c6_app "$A9" "$S9A"
"$RECOVER" "$A9" --store "$S9A" --home "$DISP/home" > "$DISP/c9-a.log" 2>&1 & p9a=$!
"$RECOVER" "$A9" --store "$S9B" --home "$DISP/home" > "$DISP/c9-b.log" 2>&1 & p9b=$!
wait "$p9a"; r9a=$?
wait "$p9b"; r9b=$?
note "target-A exit=$r9a  target-B exit=$r9b"
W9=$(( (r9a == 0 ? 1 : 0) + (r9b == 0 ? 1 : 0) ))
[ "$W9" -le 1 ] && ok "C9 at most one of the two reported success" \
                || bad "C9 both reported success on different stores"
CLAIMED=""
[ "$(tr -d '\n' < "$S9A/owner")" = "$A9" ] && CLAIMED="$CLAIMED A"
[ "$(tr -d '\n' < "$S9B/owner")" = "$A9" ] && CLAIMED="$CLAIMED B"
note "stores whose owner now names this app:${CLAIMED:- none}"
[ "$(echo $CLAIMED | wc -w | tr -d ' ')" -le 1 ] \
  && ok "C9 at most one owner marker names this app" \
  || bad "C9 both stores claim this app"
P9="$(tr -d '\n' < "$A9/.gradle/keliver-store-path" 2>/dev/null)"
# Either outcome is correct — the loser can be refused by the LOCK, or, if it
# won the lock, by PRECEDENCE (the pointer already names the other store, which
# holds an identity). Both branches assert, so neither can pass by default.
if [ "$W9" = 1 ]; then
  OWNED="$S9A"; [ "$CLAIMED" = " B" ] && OWNED="$S9B"
  [ "$P9" = "$OWNED" ] && ok "C9 the pointer agrees with the owner marker" \
                       || bad "C9 pointer=$P9 but the owner marker is on $OWNED"
  [ "$(effective "$A9" 2>/dev/null)" = "$OWNED" ] \
    && ok "C9 the resolver selects the store that was actually claimed" \
    || bad "C9 the resolver selects something else"
else
  note "both were refused; reasons:"
  grep -h '^✗' "$DISP/c9-a.log" "$DISP/c9-b.log" 2>/dev/null | sed 's/^/          /'
  [ -z "$CLAIMED" ] && ok "C9 with no winner, no store claims this app" \
                    || bad "C9 nobody succeeded but$CLAIMED claims this app"
  [ "$P9" = "$S9A" ] && ok "C9 with no winner, the pointer is exactly as it was" \
                     || bad "C9 the pointer moved to $P9 without a successful recovery"
  [ "$(effective "$A9" 2>/dev/null)" = "$S9A" ] \
    && ok "C9 and ordinary resolution is unchanged" \
    || bad "C9 ordinary resolution changed without a successful recovery"
fi

# --- C10: recovery versus relay startup --------------------------------------
echo
echo "--- C10  recovery and relay startup are serialized"
# The relay claims the store and writes the pointer at startup. A lock only the
# recovery command takes would not stop it.
A10="$DISP/apps/c10-app"
S10="$DISP/home/.keliver-portal/apps/c10-store-aaaaaab3"
mkstore "$S10" "$DISP/apps/c10-gone" "$(printf '3c%.0s' $(seq 1 32))"
c6_app "$A10" "$S10" 8166
mkdir -p "$A10/.gradle/keliver-store.lock"      # stand in for a recovery in flight
boot "$A10" 8166 c10-locked || true
if [ "${BOOT_RC:-1}" = 0 ]; then
  bad "C10 the relay started while a store recovery held the app lock"
else
  # Match the refusal's own words. `grep -i recovery` matched the disposable
  # RUN DIRECTORY's name in the log's paths and passed for the wrong reason.
  if grep -q "a store recovery is in progress" "$BOOT_LOG"; then
    ok "C10 the relay refused while a recovery held the lock"
  else
    bad "C10 the relay did not start, but not because of the lock"
    tail -8 "$BOOT_LOG" | sed 's/^/        /'
  fi
fi
if "$RECOVER" "$A10" --store "$S10" --home "$DISP/home" > "$DISP/c10-locked.log" 2>&1; then
  bad "C10 a second recovery ran while the app lock was held"
else
  ok "C10 a second recovery refused while the app lock was held"
fi
# A lock whose recorded holder is gone must not block forever: one SIGKILLed
# relay would otherwise wedge every later start behind a manual rm.
# A pid that is certainly gone: a child this shell has already reaped. 999999
# is a VALID pid on Linux (pid_max is 4194304) and could be live on the runner.
( : ) & DEAD_PID=$!; wait "$DEAD_PID" 2>/dev/null
printf '%s\n' "$DEAD_PID" > "$A10/.gradle/keliver-store.lock/pid"
if "$RECOVER" "$A10" --store "$S10" --home "$DISP/home" --dry-run > "$DISP/c10-stale.log" 2>&1; then
  grep -q "taking over" "$DISP/c10-stale.log" \
    && ok "C10 a lock whose holder is gone is taken over, not waited on forever" \
    || bad "C10 the stale lock was not reported as taken over"
else
  bad "C10 a stale lock still blocked recovery"
  sed 's/^/        /' "$DISP/c10-stale.log"
fi
rm -f  "$A10/.gradle/keliver-store.lock/pid" 2>/dev/null
rmdir  "$A10/.gradle/keliver-store.lock" 2>/dev/null
"$RECOVER" "$A10" --store "$S10" --home "$DISP/home" > "$DISP/c10-recover.log" 2>&1 \
  && ok "C10 recovery proceeds once the lock is free" \
  || { bad "C10 recovery still refused after the lock was released"; sed 's/^/        /' "$DISP/c10-recover.log"; }
boot "$A10" 8166 c10-after || BOOT_RC=1
[ "$BOOT_RC" = 0 ] && [ "$BOOT_STORE" = "$S10" ] \
  && ok "C10 the relay starts normally afterwards" \
  || { bad "C10 the relay did not start after recovery"; tail -12 "$BOOT_LOG" | sed 's/^/        /'; }

# --- C11: interruption at transaction boundaries -----------------------------
echo
echo "--- C11  SIGINT/SIGTERM at each transaction boundary"
# A trap that only released the locks was worse than none: bash RESUMES at the
# interrupted statement once the handler returns, so a TERM between the two
# writes released the locks and then went on to finish and report success.
# KELIVER_RECOVER_PAUSE_AT stops the command at a NAMED boundary so the signal
# lands there instead of being raced for.
c11_app() { # dir, store -> an app bound to <store>, which is owned by nobody live
  local d="$1" store="$2"
  mkapp "$d" 8167
  mkdir -p "$d/.gradle"
  printf '%s\n' "$store" > "$d/.gradle/keliver-store-path"
}
# Run recovery paused at $1, wait until it says so, then send $2. Sets C11_RC.
interrupt_at() { # boundary, signal, tag
  local boundary="$1" sig="$2" tag="$3" i
  # `set -m`: a background job started by a NON-interactive shell inherits
  # SIGINT as SIG_IGN, and `trap` cannot override an inherited ignore — so
  # without job control `kill -INT` here would do nothing and the command would
  # sail past the boundary. Job control puts it in its own process group, where
  # INT is deliverable exactly as it is when a person hits Ctrl-C.
  set -m
  KELIVER_RECOVER_PAUSE_AT="$boundary" KELIVER_RECOVER_PAUSE_S=20 \
    "$RECOVER" "$C11_APP" --store "$C11_STORE" --home "$DISP/home" > "$DISP/$tag.log" 2>&1 &
  local pid=$!
  set +m
  for i in $(seq 1 60); do
    grep -q "paused at $boundary" "$DISP/$tag.log" 2>/dev/null && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
  done
  if ! grep -q "paused at $boundary" "$DISP/$tag.log" 2>/dev/null; then
    wait "$pid" 2>/dev/null
    C11_RC=99; C11_DELIVERED=0
    # NOT a note. Without this the whole interruption section passes vacuously:
    # "exits non-zero" is satisfied by 99, and because nothing was written every
    # follow-up assertion — owner restored, pointer unchanged, locks released —
    # is trivially true. Green having tested nothing, on the platform where
    # signal handling is least verified.
    bad "C11 the command never reached '$boundary'; no signal was delivered"
    tail -6 "$DISP/$tag.log" | sed 's/^/        /'
    return 0
  fi
  C11_DELIVERED=1
  kill -"$sig" "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null; C11_RC=$?
  return 0
}

# Every C11 assertion runs only when the signal actually landed.
delivered() {
  [ "${C11_DELIVERED:-0}" = 1 ] && return 0
  bad "$1 (skipped: the signal was never delivered)"
  return 1
}

# (a) TERM after the owner replacement, before the pointer replacement.
C11_APP="$DISP/apps/c11-term"; C11_STORE="$DISP/home/.keliver-portal/apps/c11a-store-aaaaaac1"
mkstore "$C11_STORE" "$DISP/apps/c11-gone" "$(printf '4d%.0s' $(seq 1 32))"
c11_app "$C11_APP" "$C11_STORE"
OWNER_WAS="$(cat "$C11_STORE/owner")"
POINTER_WAS="$(cat "$C11_APP/.gradle/keliver-store-path")"
interrupt_at after-owner TERM c11a
delivered "C11a the interruption assertions" && \
[ "${C11_RC:-0}" -ne 0 ] && ok "C11a an interrupted recovery exits non-zero" \
                         || bad "C11a the interrupted recovery reported success (rc=${C11_RC:-0})"
[ "$(cat "$C11_STORE/owner")" = "$OWNER_WAS" ] \
  && ok "C11a the owner marker was restored byte for byte" \
  || bad "C11a the owner marker is now '$(cat "$C11_STORE/owner")'"
[ "$(cat "$C11_APP/.gradle/keliver-store-path")" = "$POINTER_WAS" ] \
  && ok "C11a the store pointer is unchanged" || bad "C11a the store pointer changed"
[ -d "$C11_APP/.gradle/keliver-store.lock" ] \
  && bad "C11a the app lock was left behind" || ok "C11a the locks were released"
[ -d "$C11_STORE/owner.lock" ] && bad "C11a the store lock was left behind" \
                               || ok "C11a the store lock was released"
ls -d "$C11_APP/.gradle"/keliver-store-recover.backup.* >/dev/null 2>&1 \
  && bad "C11a backup material was left behind after a clean rollback" \
  || ok "C11a no backup material is left after a verified rollback"

# (b) TERM after the transaction has committed: the rebinding stands.
C11_APP="$DISP/apps/c11-after"; C11_STORE="$DISP/home/.keliver-portal/apps/c11b-store-aaaaaac2"
mkstore "$C11_STORE" "$DISP/apps/c11b-gone" "$(printf '5e%.0s' $(seq 1 32))"
c11_app "$C11_APP" "$C11_STORE"
interrupt_at after-commit TERM c11b
delivered "C11b the post-commit assertions" && \
[ "$(tr -d '\n' < "$C11_STORE/owner")" = "$C11_APP" ] \
  && ok "C11b a committed rebinding is not undone by a later signal" \
  || bad "C11b the committed rebinding was rolled back"
[ "$(effective "$C11_APP")" = "$C11_STORE" ] \
  && ok "C11b and the resolver still selects it" || bad "C11b the resolver disagrees"

# (c) INT before anything is written.
C11_APP="$DISP/apps/c11-early"; C11_STORE="$DISP/home/.keliver-portal/apps/c11c-store-aaaaaac3"
mkstore "$C11_STORE" "$DISP/apps/c11c-gone" "$(printf '6f%.0s' $(seq 1 32))"
c11_app "$C11_APP" "$C11_STORE"
OWNER_WAS="$(cat "$C11_STORE/owner")"
interrupt_at before-owner INT c11c
delivered "C11c the early-interruption assertions" && \
[ "${C11_RC:-0}" -ne 0 ] && ok "C11c an early interruption exits non-zero" \
                         || bad "C11c the early interruption reported success"
[ "$(cat "$C11_STORE/owner")" = "$OWNER_WAS" ] && ok "C11c nothing was written" \
                                               || bad "C11c something was written before the boundary"

# (d) cleanup must not remove a lock that now belongs to a LATER process.
C11_APP="$DISP/apps/c11-lock"; C11_STORE="$DISP/home/.keliver-portal/apps/c11d-store-aaaaaac4"
mkstore "$C11_STORE" "$DISP/apps/c11d-gone" "$(printf '70%.0s' $(seq 1 32))"
c11_app "$C11_APP" "$C11_STORE"
OWNER_WAS="$(cat "$C11_STORE/owner")"
KELIVER_RECOVER_PAUSE_AT=after-owner KELIVER_RECOVER_PAUSE_S=20 \
  "$RECOVER" "$C11_APP" --store "$C11_STORE" --home "$DISP/home" > "$DISP/c11d.log" 2>&1 &
C11D_PID=$!
C11D_PAUSED=0
for _ in $(seq 1 60); do
  grep -q "paused at after-owner" "$DISP/c11d.log" 2>/dev/null && { C11D_PAUSED=1; break; }
  sleep 0.5
done
[ "$C11D_PAUSED" = 1 ] || bad "C11d the command never reached the boundary; the case below proves nothing"
# A later process takes the lock over and records its own (live) pid.
printf '%s\n' "$$" > "$C11_APP/.gradle/keliver-store.lock/pid"
kill -TERM "$C11D_PID" 2>/dev/null; wait "$C11D_PID" 2>/dev/null
if [ -d "$C11_APP/.gradle/keliver-store.lock" ] \
   && [ "$(tr -d '\n' < "$C11_APP/.gradle/keliver-store.lock/pid")" = "$$" ]; then
  ok "C11d cleanup left the lock that now belongs to another process"
else
  bad "C11d cleanup removed a lock it no longer owned"
fi
[ "$(cat "$C11_STORE/owner")" = "$OWNER_WAS" ] \
  && ok "C11d the binding was still restored" || bad "C11d the binding was not restored"
rm -f "$C11_APP/.gradle/keliver-store.lock/pid"; rmdir "$C11_APP/.gradle/keliver-store.lock" 2>/dev/null

# --- C12: restoration itself failing -----------------------------------------
echo
echo "--- C12  when restoration fails, say what is actually true"
A12="$DISP/apps/c12-app"; S12="$DISP/home/.keliver-portal/apps/c12-store-aaaaaac5"
mkstore "$S12" "$DISP/apps/c12-gone" "$(printf '81%.0s' $(seq 1 32))"
c11_app "$A12" "$S12"
if KELIVER_RECOVER_FAIL_POINTER=1 KELIVER_RECOVER_FAIL_RESTORE=1 \
     "$RECOVER" "$A12" --store "$S12" --home "$DISP/home" > "$DISP/c12.log" 2>&1; then
  bad "C12 a failed restoration still reported success"
else
  ok "C12 a failed restoration exits non-zero"
fi
grep -q "COULD NOT BE RESTORED" "$DISP/c12.log" && ok "C12 the partial state is reported as partial" \
                                                || bad "C12 the failure was not reported as a partial state"
grep -qi "unchanged\|was not modified\|Nothing was changed" "$DISP/c12.log" \
  && { bad "C12 it claimed nothing changed while the state is partial"; grep -in "unchanged\|was not modified" "$DISP/c12.log" | sed 's/^/        /'; } \
  || ok "C12 it does not claim the store is unchanged"
BK="$(ls -d "$A12/.gradle"/keliver-store-recover.backup.* 2>/dev/null | head -1)"
if [ -n "$BK" ] && [ -f "$BK/owner" ] && [ -f "$BK/pointer.existed" ]; then
  ok "C12 the material needed to restore by hand was kept"
  grep -q "$BK" "$DISP/c12.log" && ok "C12 and the report names it" || bad "C12 the report does not name the backup"
else
  bad "C12 the backup was deleted after a failed restoration"
fi
# The reported owner must match reality, not a hopeful claim — and it has to be
# the line that REPORTS the marker, not the log's own "app: <path>" header,
# which contains the same string and made this pass for any log at all.
REPORTED="$(sed -n 's/^ *owner marker  .* -> //p' "$DISP/c12.log" | tail -1)"
[ -n "$REPORTED" ] && [ "$REPORTED" = "$(tr -d '\n' < "$S12/owner")" ] \
  && ok "C12 the reported owner marker matches what is on disk" \
  || bad "C12 reported '$REPORTED' but the store says '$(tr -d '\n' < "$S12/owner")'"

# --- C13: startup resolves under the lock ------------------------------------
echo
echo "--- C13  a startup that waits re-resolves; it does not use a stale answer"
# Constructed interleaving: the relay resolves store A and blocks on the app
# lock; the binding is then changed to store B exactly as a completed recovery
# leaves it (the real command cannot be used here — it would block on the same
# lock the relay is waiting for); the lock is released and the relay proceeds.
A13="$DISP/apps/c13-app"
S13A="$DISP/home/.keliver-portal/apps/c13a-store-aaaaaac6"
S13B="$DISP/home/.keliver-portal/apps/c13b-store-aaaaaac7"
mkapp "$A13" 8167
mkstore "$S13A" "$A13" "$(printf '92%.0s' $(seq 1 32))"
mkstore "$S13B" "$A13" "$(printf 'a3%.0s' $(seq 1 32))"
mkdir -p "$A13/.gradle"; printf '%s\n' "$S13A" > "$A13/.gradle/keliver-store-path"
S13A_SNAP="$(snapshot "$S13A")"
if keliver_port_free_or_die 8167; then
  mkdir -p "$A13/.gradle/keliver-store.lock"
  printf '%s\n' "$$" > "$A13/.gradle/keliver-store.lock/pid"   # a LIVE holder, so it is not taken over
  ( cd "$A13" && PORTAL_REPO="$A13" "$RELAY" > "$DISP/c13.log" 2>&1 ) & C13_PID=$!
  # The boundary has to belong to THIS relay. `pgrep -f RelayKt` matched any
  # relay on the machine — the developer's own portal, or another job on a
  # self-hosted runner — so it could be satisfied instantly by a process this
  # test never launched, and a fixed sleep afterwards could expire before the
  # real one had blocked. Then the pointer was swapped while the relay was not
  # yet waiting, and every assertion below passed without the interleaving
  # having happened.
  #
  # The relay now announces the wait on ITS OWN stdout, which is this file.
  # Seeing that line proves this process reached the lock wait.
  C13_WAITING=0
  for _ in $(seq 1 90); do
    grep -q "waiting for a store recovery to finish" "$DISP/c13.log" 2>/dev/null \
      && { C13_WAITING=1; break; }
    kill -0 "$C13_PID" 2>/dev/null || break
    sleep 1
  done
  if [ "$C13_WAITING" = 1 ]; then
    ok "C13 the relay reached the lock wait (its own log says so)"
  else
    bad "C13 the relay never reached the lock wait; the interleaving did not happen"
    tail -12 "$DISP/c13.log" | sed 's/^/        /'
  fi
  # Only now is it safe to change the binding underneath it and let it proceed.
  printf '%s\n' "$S13B" > "$A13/.gradle/keliver-store-path"
  rm -f "$A13/.gradle/keliver-store.lock/pid"; rmdir "$A13/.gradle/keliver-store.lock"
  C13_UP=0
  for _ in $(seq 1 40); do
    curl -sf -m 2 -o /dev/null http://localhost:8167/devstate && { C13_UP=1; break; }
    kill -0 "$C13_PID" 2>/dev/null || break
    sleep 1
  done
  kill -0 "$C13_PID" 2>/dev/null && keliver_kill_own 8167 "$C13_PID"
  wait "$C13_PID" 2>/dev/null
  for _ in $(seq 1 30); do lsof -nP -iTCP:8167 -sTCP:LISTEN -t >/dev/null 2>&1 || break; sleep 1; done
  C13_PTR="$(tr -d '\n' < "$A13/.gradle/keliver-store-path")"
  [ "$C13_UP" = 1 ] && ok "C13 the relay started after waiting for the lock" \
                    || { bad "C13 the relay did not start"; tail -12 "$DISP/c13.log" | sed 's/^/        /'; }
  [ "$C13_PTR" = "$S13B" ] \
    && ok "C13 the pointer still names the store the recovery chose" \
    || bad "C13 startup wrote back its stale answer (pointer is now $C13_PTR)"
  grep -qF "store=$S13B" "$DISP/c13.log" \
    && ok "C13 the relay is serving the re-resolved store" \
    || bad "C13 the relay is serving something other than $S13B"
  [ "$(snapshot "$S13A")" = "$S13A_SNAP" ] && ok "C13 the abandoned store was not touched" \
                                           || bad "C13 the abandoned store was modified"
  [ "$(effective "$A13")" = "$S13B" ] && ok "C13 ordinary resolution agrees" \
                                      || bad "C13 ordinary resolution disagrees"
  boot "$A13" 8167 c13-restart || BOOT_RC=1
  [ "$BOOT_RC" = 0 ] && [ "$BOOT_STORE" = "$S13B" ] \
    && ok "C13 and it restarts on the same store" \
    || { bad "C13 the restart did not land on $S13B"; tail -10 "$BOOT_LOG" | sed 's/^/        /'; }
else
  # Not a note: an unexercised C13 is not a passing C13.
  bad "C13 could not run — 8167 is in use by something this run does not own"
fi

# --- C14: a claimed takeover is not completed by someone else ----------------
echo
echo "--- C14  stale-lock takeover cannot clobber another contender"
A14="$DISP/apps/c14-app"; S14="$DISP/home/.keliver-portal/apps/c14-store-aaaaaac8"
mkstore "$S14" "$DISP/apps/c14-gone" "$(printf 'b4%.0s' $(seq 1 32))"
c11_app "$A14" "$S14"
L14="$A14/.gradle/keliver-store.lock"
# The state another contender leaves between claiming and recreating: the pid
# marker renamed aside, the directory still there.
mkdir -p "$L14"; printf '999999\n' > "$L14/pid.stale.4242"
if "$RECOVER" "$A14" --store "$S14" --home "$DISP/home" --dry-run > "$DISP/c14a.log" 2>&1; then
  bad "C14 a claimed takeover was completed by another process"
else
  ok "C14 a claimed takeover is left to the contender that claimed it"
fi
[ -d "$L14" ] && [ -f "$L14/pid.stale.4242" ] \
  && ok "C14 the claiming contender's lock is intact" || bad "C14 the claim was destroyed"
rm -f "$L14"/pid.stale.*; rmdir "$L14"
# And a LIVE holder is never taken over.
mkdir -p "$L14"; printf '%s\n' "$$" > "$L14/pid"
if "$RECOVER" "$A14" --store "$S14" --home "$DISP/home" --dry-run > "$DISP/c14b.log" 2>&1; then
  bad "C14 a live holder's lock was taken over"
else
  ok "C14 a live holder's lock is not taken over"
fi
[ -f "$L14/pid" ] && [ "$(tr -d '\n' < "$L14/pid")" = "$$" ] \
  && ok "C14 the live holder's marker is untouched" || bad "C14 the live holder's marker changed"
rm -f "$L14/pid"; rmdir "$L14"
# An UNREADABLE marker means wait, not steal. `kill -0 xx` fails the same way
# `kill -0 <dead pid>` does, so without a numeric guard this lock was taken over.
mkdir -p "$L14"; printf 'xx\n' > "$L14/pid"
if "$RECOVER" "$A14" --store "$S14" --home "$DISP/home" --dry-run > "$DISP/c14c.log" 2>&1; then
  bad "C14 a lock with an unreadable holder marker was taken over"
else
  ok "C14 an unreadable holder marker means wait, not steal"
fi
[ -f "$L14/pid" ] && [ "$(tr -d '\n' < "$L14/pid")" = "xx" ] \
  && ok "C14 and that lock is left exactly as it was" || bad "C14 the unreadable marker was disturbed"
grep -q "If nothing is running, remove" "$DISP/c14c.log" \
  && ok "C14 the refusal names the directory and the remedy" \
  || bad "C14 the refusal offers no way out"
rm -f "$L14/pid"; rmdir "$L14"
# NOTE the shell's holder-marker check (a failed marker write is a failed
# acquisition) is defensive and is NOT exercised here: by the time it runs, the
# probe write into .gradle has already succeeded, so no external setup makes it
# fail without an injector. The JVM equivalent IS exercised, through a seam, in
# StoreLockTest.anUnwritableMarkerIsTreatedAsAFailedAcquisition — which until
# this round reached onBusy instead and passed on the wrong branch.

# --- C15: only a positively absent holder permits a takeover -----------------
echo
echo "--- C15  the holder-state contract, end to end"
A15="$DISP/apps/c15-app"; S15="$DISP/home/.keliver-portal/apps/c15-store-aaaaaad1"
mkstore "$S15" "$DISP/apps/c15-gone" "$(printf 'd6%.0s' $(seq 1 32))"
c11_app "$A15" "$S15"
L15="$A15/.gradle/keliver-store.lock"
( : ) & C15_DEAD=$!; wait "$C15_DEAD" 2>/dev/null   # a pid that certainly does not exist

expect_state() { # marker, expected, label, [inspector]
  local got
  if [ -n "${4:-}" ]; then got="$(KELIVER_LOCK_INSPECTOR="$4" "$RECOVER" "$A15" --holder-state "$1" 2>/dev/null)"
  else got="$("$RECOVER" "$A15" --holder-state "$1" 2>/dev/null)"; fi
  [ "$got" = "$2" ] && ok "C15 $3 -> $2" || bad "C15 $3 -> $got (expected $2)"
}
# Both inspectors, on every platform. Linux takes the /proc branch and macOS
# takes ps, so without forcing each one the branch that is not native to the
# machine ships never having run.
for insp in "" proc ps; do
  [ "$insp" = proc ] && [ ! -d /proc/$$ ] && continue      # no /proc here
  tag="${insp:-auto}"
  expect_state "$C15_DEAD" GONE    "[$tag] a reaped child" "$insp"
  expect_state "$$"        ALIVE   "[$tag] this very process" "$insp"
  expect_state 1           ALIVE   "[$tag] pid 1, live and not ours to signal" "$insp"
  expect_state 99999999999999999999 UNKNOWN "[$tag] a 20-digit marker" "$insp"
  expect_state 4294967296  UNKNOWN "[$tag] a marker above pid_t" "$insp"
  expect_state xx          UNKNOWN "[$tag] a non-numeric marker" "$insp"
  expect_state ""          UNKNOWN "[$tag] an empty marker" "$insp"
  expect_state " 7 "       UNKNOWN "[$tag] a padded marker" "$insp"
done
# A FORCED inspector still has to prove itself. Forcing the one this machine
# does not have must yield UNKNOWN, never a verdict — returning a forced value
# unprobed made `proc` on a machine with no /proc answer GONE for a LIVE
# process, which is the wrong-GONE class this section exists to close.
# A forced inspector must still prove itself. This used to be built from
# whichever inspector the machine LACKED — which meant it ran on macOS and
# self-skipped on Linux, where both work, so the platform that matters had no
# coverage at all. A skipped setup is not coverage. Instead the failure is
# MANUFACTURED, identically on both: a stub `ps` that exits non-zero, ahead of
# the real one on PATH, with the inspector forced to `ps`. The probe then fails
# wherever this runs.
STUB="$DISP/stub-bin"; mkdir -p "$STUB"
printf '#!/bin/sh\nexit 1\n' > "$STUB/ps"; chmod +x "$STUB/ps"
stub_state() { PATH="$STUB:$PATH" KELIVER_LOCK_INSPECTOR=ps "$RECOVER" "$A15" --holder-state "$1" 2>/dev/null; }
[ "$(PATH="$STUB:$PATH" "$STUB/ps" -p 1 >/dev/null 2>&1; echo $?)" = 1 ] \
  && ok "C15 the stub inspector really does fail (the setup is not a no-op)" \
  || bad "C15 the stub inspector did not fail; the case below proves nothing"
[ "$(stub_state 1)" = UNKNOWN ] \
  && ok "C15 a forced inspector that cannot answer -> UNKNOWN for a LIVE pid" \
  || bad "C15 a forced-but-broken inspector reported $(stub_state 1) for pid 1"
[ "$(stub_state "$C15_DEAD")" = UNKNOWN ] \
  && ok "C15 a forced inspector that cannot answer -> UNKNOWN for a dead pid" \
  || bad "C15 a forced-but-broken inspector reported $(stub_state "$C15_DEAD") for a reaped child"
# And an unrecognised value is refused outright rather than meaning "auto".
# stdout and stderr kept APART: the point is that no verdict is printed, and
# folding them together made the "no stdout" half test a file that is never
# written — permanently true, and therefore no test at all.
KELIVER_LOCK_INSPECTOR=bogus "$RECOVER" "$A15" --holder-state 1 \
  > "$DISP/c15-bogus.out" 2> "$DISP/c15-bogus.err"
C15_BOGUS_RC=$?
[ "$C15_BOGUS_RC" = 2 ] && [ ! -s "$DISP/c15-bogus.out" ] \
  && grep -q "is not one of auto, proc, ps, none" "$DISP/c15-bogus.err" \
  && ok "C15 an unrecognised inspector is refused with no verdict printed" \
  || { bad "C15 an unrecognised inspector was accepted (rc=$C15_BOGUS_RC, stdout='$(cat "$DISP/c15-bogus.out")')"; }

# A missing VALUE is a usage error, not an empty marker — and must not hang.
( "$RECOVER" "$A15" --holder-state ) > "$DISP/c15-arity.log" 2>&1 & ap=$!
( sleep 10; kill -9 "$ap" 2>/dev/null ) & wp=$!
wait "$ap" 2>/dev/null; arc=$?
kill "$wp" 2>/dev/null
[ "$arc" = 2 ] && ok "C15 --holder-state with no value is a usage error, not a hang" \
               || bad "C15 --holder-state with no value exited $arc"
# The diagnostic must answer without creating anything in the app tree.
A15B="$DISP/apps/c15-untouched"; mkdir -p "$A15B"
"$RECOVER" "$A15B" --holder-state 1 > /dev/null 2>&1
[ -e "$A15B/.gradle" ] && bad "C15 the diagnostic created .gradle in the app tree" \
                       || ok "C15 the diagnostic answers without touching the app tree"
if [ "$(KELIVER_LOCK_INSPECTOR=none "$RECOVER" "$A15" --holder-state "$C15_DEAD" 2>/dev/null)" = UNKNOWN ]; then
  ok "C15 an inspector that cannot answer -> UNKNOWN, even for a dead pid"
else
  bad "C15 a broken inspector was read as absence"
fi
# Locale cannot change any of it — nothing parses an error message.
[ "$(LC_ALL=de_DE.UTF-8 "$RECOVER" "$A15" --holder-state "$C15_DEAD" 2>/dev/null)" = GONE ] \
  && ok "C15 the verdict is locale-independent" || bad "C15 the verdict changed under another locale"

# And the verdicts are what the LOCK actually does. Uncertainty must leave it
# exactly as it was; only GONE may be taken over.
for spec in "99999999999999999999:a 20-digit marker" "4294967296:a marker above pid_t" \
            "xx:a non-numeric marker" "1:a live holder we cannot signal"; do
  m="${spec%%:*}"; lbl="${spec#*:}"
  mkdir -p "$L15"; printf '%s\n' "$m" > "$L15/pid"
  BEFORE="$(snapshot "$L15")"
  if "$RECOVER" "$A15" --store "$S15" --home "$DISP/home" --dry-run > "$DISP/c15.log" 2>&1; then
    bad "C15 $lbl was taken over"
  elif grep -q "locked by another store recovery or a starting portal" "$DISP/c15.log"; then
    ok "C15 $lbl is refused, not taken over"
  else
    bad "C15 $lbl was refused, but for an unrelated reason"
    head -3 "$DISP/c15.log" | sed 's/^/        /'
  fi
  [ "$(snapshot "$L15")" = "$BEFORE" ] && ok "C15 $lbl left the lock unchanged" \
                                       || bad "C15 $lbl disturbed the lock"
  rm -f "$L15/pid"; rmdir "$L15"
done
# The one case that MAY proceed.
mkdir -p "$L15"; printf '%s\n' "$C15_DEAD" > "$L15/pid"
if "$RECOVER" "$A15" --store "$S15" --home "$DISP/home" --dry-run > "$DISP/c15-dead.log" 2>&1; then
  ok "C15 a positively absent holder is taken over"
  grep -q "taking over" "$DISP/c15-dead.log" && ok "C15 and it says so" || bad "C15 the takeover was silent"
else
  bad "C15 a dead holder's lock was not reclaimable"
  sed 's/^/        /' "$DISP/c15-dead.log"
fi
rm -f "$L15/pid" 2>/dev/null; rmdir "$L15" 2>/dev/null

# --- C16: a failed startup leaves nothing, and a broken resolver fails closed --
echo
echo "--- C16  failure is non-destructive, and never falls back to another identity"
stores_under() { ls -1 "$DISP/home/.keliver-portal/apps" 2>/dev/null | wc -l | tr -d ' '; }

# (a) the pointer destination is unusable: refuse BEFORE claiming anything.
A16="$DISP/apps/c16-badpointer"; mkapp "$A16" 8178
mkdir -p "$A16/.gradle/keliver-store-path"          # the pointer path is a directory
BEFORE16="$(stores_under)"
boot "$A16" 8178 c16a || BOOT_RC=1
[ "$BOOT_RC" != 0 ] && ok "C16a the relay refused to start" \
                    || bad "C16a the relay started with an unwritable pointer"
[ "$(stores_under)" = "$BEFORE16" ] \
  && ok "C16a no store was created by the failed start" \
  || { bad "C16a the failed start left a store behind"; ls -1 "$DISP/home/.keliver-portal/apps" | sed 's/^/        /'; }
grep -q "has been claimed or created" "$BOOT_LOG" \
  && ok "C16a and it says so truthfully" || bad "C16a the refusal does not say what it left"

# (b) a failure BETWEEN validating the destination and writing it. The relay
# has already claimed the store by then, so the rollback is what is under test.
A16B="$DISP/apps/c16-midway"; mkapp "$A16B" 8178
BEFORE16B="$(stores_under)"
export KELIVER_RELAY_FAIL_POINTER=1
boot "$A16B" 8178 c16b || BOOT_RC=1
unset KELIVER_RELAY_FAIL_POINTER
[ "$BOOT_RC" != 0 ] && ok "C16b the relay refused when the pointer write failed" \
                    || bad "C16b the relay started anyway"
[ "$(stores_under)" = "$BEFORE16B" ] \
  && ok "C16b the store this start had just claimed was removed" \
  || { bad "C16b a claimed store was left behind"; ls -1 "$DISP/home/.keliver-portal/apps" | sed 's/^/        /'; }
# and a PRE-EXISTING store must never be removed by the same path.
A16C="$DISP/apps/c16-existing"; mkapp "$A16C" 8178
boot "$A16C" 8178 c16c-first || BOOT_RC=1
S16C="$BOOT_STORE"
[ -n "$S16C" ] && [ -f "$S16C/keys/ed25519.pub" ] \
  && ok "C16c a first start created a real identity" || bad "C16c no identity to protect"
FP16="$(fingerprint "$S16C")"
export KELIVER_RELAY_FAIL_POINTER=1
boot "$A16C" 8178 c16c-second || BOOT_RC=1
unset KELIVER_RELAY_FAIL_POINTER
[ -d "$S16C" ] && [ "$(fingerprint "$S16C")" = "$FP16" ] \
  && ok "C16c a later failed start left the existing identity alone" \
  || bad "C16c the rollback removed a store it did not create"

# (c) the resolver failing must fail the BUILD, not pick the global store.
NOPY="$DISP/nopy"; mkdir -p "$NOPY"; printf '#!/bin/sh\nexit 127\n' > "$NOPY/python3"; chmod +x "$NOPY/python3"
( cd "$ROOT" && PATH="$NOPY:$PATH" ./gradlew --console=plain -q :portal-relay:compileKotlin ) \
  > "$DISP/c16-gradle.log" 2>&1
if [ $? = 0 ]; then
  bad "C16d a build with a broken store resolver succeeded"
else
  ok "C16d a build with a broken store resolver fails"
fi
grep -q "could not resolve this app's portal store" "$DISP/c16-gradle.log" \
  && ok "C16d and says which resolver and why" || bad "C16d the failure is not the store resolver's"
grep -qi "falling back" "$DISP/c16-gradle.log" \
  && bad "C16d it still mentions falling back" || ok "C16d no fallback identity is offered"

# (d) the recovery CLI, without touching the filesystem.
CLI_PROBE="$DISP/apps/c16-cli"; mkdir -p "$CLI_PROBE"
# Run it FROM the probe directory: asking whether --help touched a directory it
# was never pointed at could only ever answer "no".
( cd "$CLI_PROBE" && "$RECOVER" --help ) > "$DISP/c16-help.log" 2>&1
[ $? = 0 ] && grep -q "^usage:" "$DISP/c16-help.log" \
  && ok "C16e --help works as the first argument" || bad "C16e --help as the first argument failed"
[ -z "$(ls -A "$CLI_PROBE" 2>/dev/null)" ] \
  && ok "C16e --help left its working directory empty" \
  || { bad "C16e --help touched the filesystem"; ls -A "$CLI_PROBE" | sed 's/^/        /'; }
( cd "$CLI_PROBE" && "$RECOVER" . --home "$DISP/home" > "$DISP/c16-dot.log" 2>&1 )
# "no such app dir" would mean '.' was NOT accepted, so it cannot be one of the
# outcomes that counts as success.
if grep -q "no such app dir" "$DISP/c16-dot.log"; then
  bad "C16e '.' was rejected as an app directory"
elif grep -q "no store at\|has no owner marker\|app:   " "$DISP/c16-dot.log"; then
  ok "C16e '.' is treated as an ordinary app directory"
else
  bad "C16e '.' produced an unexpected outcome"; head -2 "$DISP/c16-dot.log" | sed 's/^/        /'
fi
echo
echo "passed: $pass   failed: $fail"
echo "evidence: $DISP"
[ "$fail" -eq 0 ]
