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
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"

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
[ -x "$ROOT/portal-relay/build/install/portal-relay/bin/portal-relay" ] || {
  echo "build the relay first: ./gradlew :portal-relay:installDist" >&2; exit 2; }
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

fingerprint() {
  if [ -r "$1/keys/ed25519.pub" ]; then shasum -a 256 "$1/keys/ed25519.pub" | cut -c1-16
  elif [ -d "$1" ]; then echo "none yet"; else echo "(no store)"; fi
}

# Snapshot a directory's content, key material included BY HASH ONLY.
snapshot() { [ -d "$1" ] && (cd "$1" && find . -type f -print0 | sort -z | xargs -0 shasum -a 256) 2>/dev/null || echo "(absent)"; }

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
  BOOT_STORE="$(tr -d '\n' < "$app/.gradle/keliver-store-path" 2>/dev/null)"
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
SIGNED="$DISP/signed-manifest.json"
if [ -f "$SRC_MANIFEST" ]; then
  ( cd "$ROOT" && ./gradlew --console=plain -q :portal-relay:test --rerun-tasks \
      --tests '*StoreRelocationSignatureTest*' \
      -Dkeliver.sign.privkey="$S/keys/ed25519.priv" \
      -Dkeliver.sign.manifest="$SRC_MANIFEST" \
      -Dkeliver.sign.out="$SIGNED" > "$DISP/sign.log" 2>&1 )
  [ -s "$SIGNED" ] && ok "C1 a real Zipline manifest was signed with this store's identity" \
                   || { bad "C1 signing produced nothing"; tail -20 "$DISP/sign.log" | sed 's/^/        /'; }
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
  XML="$ROOT/portal-relay/build/test-results/test/TEST-SignedBundleVerificationTest.xml"
  if [ $vrc -ne 0 ]; then
    bad "C1 the pre-move signed bundle does NOT verify after the move"
    tail -25 "$DISP/verify.log" | sed 's/^/        /'
  elif grep -q "skipped (no manifest/pubkey properties)" "$XML" 2>/dev/null; then
    # The properties are passed with -D, which reaches the Gradle JVM and not
    # the forked test JVM unless the build forwards them. When it did not, this
    # test took its skip branch and the build went green having verified
    # nothing. Refuse to count that as a pass.
    bad "C1 the verification SKIPPED — it proved nothing"
  else
    grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' "$XML" 2>/dev/null \
      | sed 's/^/        /'
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
DIFF="$(diff <(echo "$RSNAP_BEFORE" | grep -v ' ./owner$') <(snapshot "$RS" | grep -v ' ./owner$') || true)"
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
  note "8161 or 8096 is in use by something this run does not own; C5 skipped"
fi

echo
echo "passed: $pass   failed: $fail"
echo "evidence: $DISP"
[ "$fail" -eq 0 ]
