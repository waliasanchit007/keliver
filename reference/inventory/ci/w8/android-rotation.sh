# Sourced by ci/device.sh after W5: W8, a signing-key rotation on Android,
# against W3's static HTTPS server (its site continues: v10 on). prepare.sh made
# the next key (keliver-publish --new-signing-key, W8_NEXT) and built the same
# production host trusting both keys (W8_BOTH_APK) and the next key only
# (W8_NEXT_APK), and W8_LEGACY_APK: a host from the published zip's own
# scaffolder, from before W8 (one key, trusted only as portal-ed25519), as every
# app in the field has today. With a zip whose template already has W8 it is
# this checkout's one-key host instead (W8_LEGACY_IS_PRE_W8=0), and K3/K6 say so.
# Each install is an upgrade of the one before (install -r keeps the app's
# data), as a store update is. Uses device.sh's SERIAL, EV, HERE, STORE, PUB,
# PROD_ID, HOST_TAG, ok/bad/launch/drive/fold, and android-static.sh's W3,
# W3_TLS, w3_title, w3_site.
#   K1  the publish guard: a bundle signed only by the next key, straight after
#       bundles signed only by this app's key, is refused; nothing is written
#   K2  dual-signed v10: one signature per key, then portal-ed25519, last
#   K3  a host from before W8 (portal-ed25519 only) loads the dual-signed v10
#   K4  upgraded to the host trusting both keys: v10 again, and the rollback
#       floor (10) is copied under the next key too
#   K5  v11, signed by the next key only, reaches the two-key host
#   K6  that host refuses v11 (nothing loads): its portal-ed25519 is the next key's
#   K7  upgraded to the host trusting only the next key: v11 loads, and the
#       floor it starts from is 11, carried over by the two-key host
#   K8  that host refuses v12, signed by the old key only (published with
#       --accept-key-change), and falls back to the cached v11
echo "--- 7. W8: a signing-key rotation"
if [ "${W8_OK:-0}" != 1 ]; then
  echo "  SKIP  W8: the tools in use predate key rotation (prepare.sh); not counted"
else
NEXT_PUB="$(tr -d ' \n' < "$W8_NEXT/ed25519.pub")"
W8D="$WORK/w8"; mkdir -p "$W8D"
# Both private keys, one per line, as a CI secret file would hold them (owner-only).
( umask 077; { tr -d ' \n' < "$STORE/keys/ed25519.priv"; echo; tr -d ' \n' < "$W8_NEXT/ed25519.priv"; echo; } > "$W8D/both.priv" )
printf '%s\n%s\n' "$PUB" "$NEXT_PUB" > "$W8D/both.pub"
w8_publish(){  # $1 label, $2 private key file, then extra flags; checked against both public keys
  local label="$1" key="$2"; shift 2
  ( cd "$APP" && KELIVER_SIGNING_KEY_FILE="$key" KELIVER_TOOLS_BIN="$W3/no-tools-bin" \
      "$W3_PUBLISH" . --out "$W3/site" --public-key-file "$W8D/both.pub" "$@" ) > "$EV/w8-publish-$label.log" 2>&1
}
w8_signatures(){  # $1 = N: v<N>'s signature names, in order, comma-separated
  python3 -c 'import json,sys; print(",".join(json.load(open(sys.argv[1]))["unsigned"]["signatures"]))' "$W3/site/bundles/v$1/manifest.zipline.json"
}
w8_floor(){  # $1 = a public key: the rollback floor the (debuggable) host keeps under it, or none
  adb -s "$SERIAL" shell run-as "$PROD_ID" cat shared_prefs/keliver-host.xml 2>/dev/null | tr -d '\r' \
    | grep -o "name=\"highestSequence-keliver-production-${1:0:16}\" value=\"[0-9]*\"" | grep -o 'value="[0-9]*"' | tr -dc '0-9'
}
OLD_NAME="keliver-ed25519-${PUB:0:16}"; NEXT_NAME="keliver-ed25519-${NEXT_PUB:0:16}"
# What a host says when the first signature it trusts is not from its key (Zipline 1.22).
REFUSED_LINE="manifest signature for key portal-ed25519 did not verify"
if [ "${W8_LEGACY_IS_PRE_W8:-0}" = 1 ]; then OLD_HOST="the host from before W8 (published tools from before W8, their own template)"
else OLD_HOST="this checkout's one-key host (the zip's template already has W8)"; fi

python3 "$HERE/w3/static_https.py" "$W3/site" 8443 "$W3_TLS/server.pem" "$W3_TLS/server.key" "$EV/w8-server.log" &
SERVE_PID=$!
for _ in $(seq 1 30); do curl -sf --cacert "$W3_TLS/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json && break; sleep 1; done

# K1: straight from this app's key to the next one.
before="$(w3_site)"
w3_title Cellar Vault
w8_publish v10-jump "$W8_NEXT/ed25519.priv"; rc=$?
[ "$rc" = 4 ] && grep -q -- "--accept-key-change" "$EV/w8-publish-v10-jump.log" && grep -q "signed by $NEXT_NAME" "$EV/w8-publish-v10-jump.log" \
  && [ "$(w3_site)" = "$before" ] \
  && ok "K1: keliver-publish refused a bundle signed only by the next key after bundles signed by this app's key (exit 4), and wrote nothing" \
  || bad "K1: the guard (exit $rc): $(grep -E 'REFUSED|published' "$EV/w8-publish-v10-jump.log" | head -2 | tr '\n' ' ')"

# K2: dual-signed.
w8_publish v10 "$W8D/both.priv" && grep -q "published v10 (sequence 10, channel stable" "$EV/w8-publish-v10.log" \
  && grep -q "signed manifest.zipline.json with this app's portal-ed25519 key ($OLD_NAME) and the next key ($NEXT_NAME)" "$EV/w8-publish-v10.log" \
  && ok "K2: keliver-publish wrote v10 (\"Vault\"), signed with both keys" || { bad "K2: publish v10"; tail -20 "$EV/w8-publish-v10.log"; }
[ "$(w8_signatures 10)" = "$OLD_NAME,$NEXT_NAME,portal-ed25519" ] \
  && ok "K2: v10's signatures: $OLD_NAME, $NEXT_NAME, then portal-ed25519 (the only one hosts before W8 trust), last" \
  || bad "K2: v10's signatures are $(w8_signatures 10)"

# K3: the host every app has today.
adb -s "$SERIAL" install -r "$W8_LEGACY_APK" > "$EV/install-w8-old.log" 2>&1 && adb -s "$SERIAL" shell pm clear "$PROD_ID" > /dev/null
launch prod "$EV/logcat-w8-old-v10.txt"
grep -q "$HOST_TAG: verifying manifests with portal-ed25519 ${PUB:0:8}…; updates" "$EV/logcat-w8-old-v10.txt" \
  && grep -q "$HOST_TAG: loading https://$HOST_ADDR:8443/bundles/v10/manifest.zipline.json (index sequence 10," "$EV/logcat-w8-old-v10.txt" \
  && grep -q "$HOST_TAG: codeLoadSuccess modules=[0-9]* sequence=10 source=network" "$EV/logcat-w8-old-v10.txt" \
  && ok "K3: $OLD_HOST loaded the dual-signed v10" \
  || bad "K3: $(grep -E "$HOST_TAG|codeLoad" "$EV/logcat-w8-old-v10.txt" | head -4 | tr '\n' ' ')"
drive title Vault K3; fold "K3: v10 shows Vault" $?

# K4: the app's next release trusts both keys.
adb -s "$SERIAL" install -r "$W8_BOTH_APK" > "$EV/install-w8-both.log" 2>&1 \
  && ok "K4: upgraded in place to the host trusting both keys" || bad "K4: the two-key host did not install"
launch prod "$EV/logcat-w8-both-v10.txt"
grep -q "$HOST_TAG: verifying manifests with portal-ed25519 ${PUB:0:8}… and the next key ${NEXT_PUB:0:8}…; updates" "$EV/logcat-w8-both-v10.txt" \
  && grep -q "$HOST_TAG: loading https://$HOST_ADDR:8443/bundles/v10/manifest.zipline.json (.*); rollback floor 10" "$EV/logcat-w8-both-v10.txt" \
  && grep -q "$HOST_TAG: codeLoadSuccess modules=[0-9]* sequence=10" "$EV/logcat-w8-both-v10.txt" \
  && ok "K4: the two-key host verifies with both keys and loaded v10 (floor 10)" \
  || bad "K4: $(grep -E "$HOST_TAG|codeLoad" "$EV/logcat-w8-both-v10.txt" | head -4 | tr '\n' ' ')"
f_old="$(w8_floor "$PUB")"; f_next="$(w8_floor "$NEXT_PUB")"
[ "$f_old" = 10 ] && [ "$f_next" = 10 ] && ok "K4: the floor is kept under both keys (10 and 10)" \
  || bad "K4: floors under this app's key '$f_old' and the next key '$f_next'"

# K5: the next key alone (this app's key retired from signing).
w3_title Vault Bunker
w8_publish v11 "$W8_NEXT/ed25519.priv" && grep -q "published v11 (sequence 11, channel stable" "$EV/w8-publish-v11.log" \
  && [ "$(w8_signatures 11)" = "$NEXT_NAME,portal-ed25519" ] \
  && ok "K5: keliver-publish wrote v11 (\"Bunker\"), signed by the next key only (no guard: v10 had it)" \
  || { bad "K5: publish v11 ($(w8_signatures 11))"; tail -20 "$EV/w8-publish-v11.log"; }
launch prod "$EV/logcat-w8-both-v11.txt"
grep -q "$HOST_TAG: codeLoadSuccess modules=[0-9]* sequence=11 source=network" "$EV/logcat-w8-both-v11.txt" \
  && grep -q "$HOST_TAG: rollback floor raised: 10 -> 11" "$EV/logcat-w8-both-v11.txt" \
  && ok "K5: the two-key host loaded v11, signed by the next key alone (floor 10 -> 11)" \
  || bad "K5: $(grep -E "$HOST_TAG|codeLoad" "$EV/logcat-w8-both-v11.txt" | head -4 | tr '\n' ' ')"
drive title Bunker K5; fold "K5: v11 shows Bunker" $?

# K6: a host that never learned the next key.
adb -s "$SERIAL" install -r "$W8_LEGACY_APK" > "$EV/install-w8-old-again.log" 2>&1
launch prod "$EV/logcat-w8-old-v11.txt"
F="$EV/logcat-w8-old-v11.txt"
grep -q "$HOST_TAG: loading https://$HOST_ADDR:8443/bundles/v11/manifest.zipline.json" "$F" \
  && grep -q "$REFUSED_LINE" "$F" && ! grep -q "$HOST_TAG: codeLoadSuccess" "$F" \
  && ok "K6: $OLD_HOST refused v11 ($REFUSED_LINE) and loaded nothing" \
  || bad "K6: $(grep -E "$HOST_TAG|codeLoad" "$F" | head -5 | tr '\n' ' ')"

# K7: the release after the rotation trusts only the next key.
adb -s "$SERIAL" install -r "$W8_NEXT_APK" > "$EV/install-w8-next.log" 2>&1 \
  && ok "K7: upgraded in place to the host trusting only the next key" || bad "K7: the next-key host did not install"
launch prod "$EV/logcat-w8-next-v11.txt"
F="$EV/logcat-w8-next-v11.txt"
grep -q "$HOST_TAG: verifying manifests with portal-ed25519 ${NEXT_PUB:0:8}…; updates" "$F" \
  && grep -q "$HOST_TAG: loading https://$HOST_ADDR:8443/bundles/v11/manifest.zipline.json (.*); rollback floor 11" "$F" \
  && grep -q "$HOST_TAG: codeLoadSuccess modules=[0-9]* sequence=11 source=network" "$F" \
  && ok "K7: the next-key host loaded v11, starting from floor 11 (carried over by the two-key host)" \
  || bad "K7: $(grep -E "$HOST_TAG|codeLoad" "$F" | head -4 | tr '\n' ' ')"
drive title Bunker K7; fold "K7: v11 shows Bunker" $?

# K8: a bundle signed by the retired key only, published on purpose.
w3_title Bunker Shelter
w8_publish v12 "$STORE/keys/ed25519.priv" --accept-key-change && grep -q "published v12 (sequence 12, channel stable" "$EV/w8-publish-v12.log" \
  && [ "$(w8_signatures 12)" = "$OLD_NAME,portal-ed25519" ] \
  && ok "K8: v12 (\"Shelter\"), signed by the old key only, published with --accept-key-change" \
  || { bad "K8: publish v12"; tail -20 "$EV/w8-publish-v12.log"; }
launch prod "$EV/logcat-w8-next-v12.txt"
F="$EV/logcat-w8-next-v12.txt"
grep -q "$HOST_TAG: loading https://$HOST_ADDR:8443/bundles/v12/manifest.zipline.json" "$F" && grep -q "$REFUSED_LINE" "$F" \
  && grep -q "$HOST_TAG: the bundle did not load; falling back to the cached last good bundle (last loaded from https://$HOST_ADDR:8443/bundles/v11/" "$F" \
  && grep -q "$HOST_TAG: codeLoadSuccess modules=[0-9]* sequence=11 source=cache" "$F" && ! grep -q "$HOST_TAG: codeLoadSuccess modules=[0-9]* sequence=12" "$F" \
  && ok "K8: the next-key host refused v12 (old key only) and fell back to the cached v11" \
  || bad "K8: $(grep -E "$HOST_TAG|codeLoad" "$F" | tail -5 | tr '\n' ' ')"
drive title Bunker K8; fold "K8: still Bunker (v11)" $?
kill "$SERVE_PID" 2>/dev/null; wait "$SERVE_PID" 2>/dev/null; SERVE_PID=""
fi
