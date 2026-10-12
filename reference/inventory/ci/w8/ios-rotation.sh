# Sourced by ci/ios.sh after W5: W8, a signing-key rotation on iOS, against
# W3's static HTTPS server (its site continues: v10 on). The counterpart of
# ci/w8/android-rotation.sh, with the same rows (K1-K8, below). The next key is
# made here by keliver-publish --new-signing-key; the host is rebuilt trusting
# both keys (PORTAL_NEXT_PUBLIC_KEY_HEX), then the next key only. Each
# `simctl install` over the installed app keeps its data, as an App Store
# update does. Uses ios.sh's APP, STORE, PUB, UDID, BID, APPB, WORK, EV, HERE,
# SPID, ok/bad/launch/reads, and ios-static.sh's W3, PUBLISH, w3_title, w3_site.
#   K1  the publish guard: a bundle signed only by the next key, straight after
#       bundles signed only by this app's key, is refused; nothing is written
#   K2  dual-signed v10: one signature per key, then portal-ed25519, last
#   K3  the host trusting only this app's key loads the dual-signed v10
#   K4  upgraded to the host trusting both keys: v10 again, and the rollback
#       floor (10) is copied under the next key too
#   K5  v11, signed by the next key only, reaches the two-key host
#   K6  a host trusting only the old key refuses v11 (nothing loads)
#   K7  upgraded to the host trusting only the next key: v11 loads, and the
#       floor it starts from is 11, carried over by the two-key host
#   K8  that host refuses v12, signed by the old key only (published with
#       --accept-key-change), and falls back to the cached v11
echo "--- W8: a signing-key rotation"
if [ -z "${PUBLISH:-}" ] || ! "$PUBLISH" --help 2>/dev/null | grep -q -- '--new-signing-key'; then
  echo "  SKIP  W8: this keliver-publish predates key rotation; not counted"
else
W8D="$WORK/w8"; mkdir -p "$W8D"
"$PUBLISH" --new-signing-key "$W8D/next" > "$EV/w8-new-key.log" 2>&1
mode="$(stat -f %Lp "$W8D/next/ed25519.priv" 2>/dev/null)"
NEXT_PUB="$(tr -d ' \n' < "$W8D/next/ed25519.pub" 2>/dev/null)"
[ "$mode" = 600 ] && [[ "$NEXT_PUB" =~ ^[0-9a-f]{64}$ ]] && [ "$NEXT_PUB" != "$PUB" ] \
  && grep -q "$NEXT_PUB" "$EV/w8-new-key.log" && ! grep -qFf "$W8D/next/ed25519.priv" "$EV/w8-new-key.log" \
  && ok "W8: keliver-publish --new-signing-key made the next key pair (private key mode $mode, never printed)" \
  || bad "W8: --new-signing-key (mode '$mode'): $(head -3 "$EV/w8-new-key.log" | tr '\n' ' ')"
# Both private keys, one per line, as a CI secret file would hold them (owner-only).
( umask 077; { tr -d ' \n' < "$STORE/keys/ed25519.priv"; echo; tr -d ' \n' < "$W8D/next/ed25519.priv"; echo; } > "$W8D/both.priv" )
printf '%s\n%s\n' "$PUB" "$NEXT_PUB" > "$W8D/both.pub"
w8_publish(){  # $1 label, $2 private key file, then extra flags; checked against both public keys
  local label="$1" key="$2"; shift 2
  ( cd "$APP" && KELIVER_SIGNING_KEY_FILE="$key" "$PUBLISH" . --out "$W3/site" --public-key-file "$W8D/both.pub" "$@" ) \
    > "$EV/w8-publish-$label.log" 2>&1
}
w8_signatures(){  # $1 = N: v<N>'s signature names, in order, comma-separated
  python3 -c 'import json,sys; print(",".join(json.load(open(sys.argv[1]))["unsigned"]["signatures"]))' "$W3/site/bundles/v$1/manifest.zipline.json"
}
w8_floor(){  # $1 = a public key: the rollback floor the host keeps under it (the app stopped first, so it is flushed)
  local prefs
  xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1; sleep 2
  prefs="$(xcrun simctl get_app_container "$UDID" "$BID" data)/Library/Preferences/$BID"
  xcrun simctl spawn "$UDID" defaults read "$prefs" "keliver.highestSequence-keliver-production-${1:0:16}" 2>/dev/null | tr -dc '0-9'
}
CFG="$(find "$APP/host-ios/src/iosMain/kotlin" -name HostConfig.kt | head -1)"
w8_build(){  # $1 label: rebuild the host with HostConfig.kt as it is now, and keep a copy of the app
  ( cd "$APP" && xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -configuration Debug \
      -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath "$WORK/dd" \
      CODE_SIGNING_ALLOWED=NO build ) > "$EV/w8-xcodebuild-$1.log" 2>&1 \
    && rm -rf "$W8D/$1.app" && cp -R "$APPB" "$W8D/$1.app"
}
OLD_NAME="keliver-ed25519-${PUB:0:16}"; NEXT_NAME="keliver-ed25519-${NEXT_PUB:0:16}"
rm -rf "$W8D/old.app"; cp -R "$APPB" "$W8D/old.app"   # the host installed now: this app's key only

python3 "$HERE/w3/static_https.py" "$W3/site" 8443 "$W3/tls/server.pem" "$W3/tls/server.key" "$EV/w8-server.log" &
SPID=$!
for _ in $(seq 1 30); do curl -sf --cacert "$W3/tls/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json && break; sleep 1; done

# K1: straight from this app's key to the next one.
before="$(w3_site)"
w3_title Cellar Vault
w8_publish v10-jump "$W8D/next/ed25519.priv"; rc=$?
[ "$rc" = 4 ] && grep -q -- "--accept-key-change" "$EV/w8-publish-v10-jump.log" && grep -q "signed by $NEXT_NAME" "$EV/w8-publish-v10-jump.log" \
  && [ "$(w3_site)" = "$before" ] \
  && ok "K1: keliver-publish refused a bundle signed only by the next key after bundles signed by this app's key (exit 4), and wrote nothing" \
  || bad "K1: the guard (exit $rc): $(grep -E 'REFUSED|published' "$EV/w8-publish-v10-jump.log" | head -2 | tr '\n' ' ')"

# K2: dual-signed.
w8_publish v10 "$W8D/both.priv" && grep -q "published v10 (sequence 10, channel stable" "$EV/w8-publish-v10.log" \
  && grep -q "signed manifest.zipline.json with this app's portal-ed25519 key ($OLD_NAME) and the next key ($NEXT_NAME)" "$EV/w8-publish-v10.log" \
  && ok "K2: keliver-publish wrote v10 (\"Vault\"), signed with both keys" || { bad "K2: publish v10"; tail -20 "$EV/w8-publish-v10.log"; }
[ "$(w8_signatures 10)" = "$OLD_NAME,$NEXT_NAME,portal-ed25519" ] \
  && ok "K2: v10's signatures: $OLD_NAME, $NEXT_NAME, then portal-ed25519 (the only one older hosts read), last" \
  || bad "K2: v10's signatures are $(w8_signatures 10)"

# K3: the host every app has today.
launch K3-old
C="$EV/K3-old.console.txt"
grep -q "KeliverHost: verifying manifests with portal-ed25519 ${PUB:0:8}…; updates" "$C" \
  && grep -q "loading https://localhost:8443/bundles/v10/manifest.zipline.json (index sequence 10," "$C" \
  && grep -q "codeLoadSuccess modules=[0-9]* sequence=10 source=network" "$C" \
  && ok "K3: the host trusting only this app's key loaded the dual-signed v10" \
  || bad "K3: $(grep -E 'KeliverHost|codeLoad' "$C" | head -4 | tr '\n' ' ')"
reads K3-old Vault && ok "K3: the screen reads 'Vault' (v10)" || bad "K3: 'Vault' is not on the screen"

# K4: the app's next release trusts both keys.
sed -i '' "s|PORTAL_NEXT_PUBLIC_KEY_HEX: String = \"[^\"]*\"|PORTAL_NEXT_PUBLIC_KEY_HEX: String = \"$NEXT_PUB\"|" "$CFG"
grep -q "PORTAL_NEXT_PUBLIC_KEY_HEX: String = \"$NEXT_PUB\"" "$CFG" && w8_build both && xcrun simctl install "$UDID" "$W8D/both.app" \
  && ok "K4: rebuilt trusting both keys (PORTAL_NEXT_PUBLIC_KEY_HEX) and installed over the old host" \
  || { bad "K4: the two-key host did not build or install"; grep -E '^e: |error:' "$EV/w8-xcodebuild-both.log" | head; }
launch K4-both
C="$EV/K4-both.console.txt"
grep -q "KeliverHost: verifying manifests with portal-ed25519 ${PUB:0:8}… and portal-ed25519 ${NEXT_PUB:0:8}…; updates" "$C" \
  && grep -q "loading https://localhost:8443/bundles/v10/manifest.zipline.json (.*); rollback floor 10" "$C" \
  && grep -q "codeLoadSuccess modules=[0-9]* sequence=10" "$C" \
  && ok "K4: the two-key host verifies with both keys and loaded v10 (floor 10)" \
  || bad "K4: $(grep -E 'KeliverHost|codeLoad' "$C" | head -4 | tr '\n' ' ')"
f_old="$(w8_floor "$PUB")"; f_next="$(w8_floor "$NEXT_PUB")"
[ "$f_old" = 10 ] && [ "$f_next" = 10 ] && ok "K4: the floor is kept under both keys (10 and 10)" \
  || bad "K4: floors under this app's key '$f_old' and the next key '$f_next'"

# K5: the next key alone.
w3_title Vault Bunker
w8_publish v11 "$W8D/next/ed25519.priv" && grep -q "published v11 (sequence 11, channel stable" "$EV/w8-publish-v11.log" \
  && [ "$(w8_signatures 11)" = "$NEXT_NAME,portal-ed25519" ] \
  && ok "K5: keliver-publish wrote v11 (\"Bunker\"), signed by the next key only (no guard: v10 had it)" \
  || { bad "K5: publish v11 ($(w8_signatures 11))"; tail -20 "$EV/w8-publish-v11.log"; }
launch K5-both
C="$EV/K5-both.console.txt"
grep -q "codeLoadSuccess modules=[0-9]* sequence=11 source=network" "$C" && grep -q "rollback floor raised: 10 -> 11" "$C" \
  && ok "K5: the two-key host loaded v11, signed by the next key alone (floor 10 -> 11)" \
  || bad "K5: $(grep -E 'KeliverHost|codeLoad' "$C" | head -4 | tr '\n' ' ')"
reads K5-both Bunker && ok "K5: the screen reads 'Bunker' (v11)" || bad "K5: 'Bunker' is not on the screen"

# K6: a host that never learned the next key.
xcrun simctl install "$UDID" "$W8D/old.app"
launch K6-old
C="$EV/K6-old.console.txt"
grep -q "loading https://localhost:8443/bundles/v11/manifest.zipline.json" "$C" \
  && grep -qE "did not verify|no keys in the manifest were recognized" "$C" && ! grep -q "codeLoadSuccess" "$C" \
  && ok "K6: the host trusting only the old key refused v11 (signature did not verify) and loaded nothing" \
  || bad "K6: $(grep -E 'KeliverHost|codeLoad' "$C" | head -5 | tr '\n' ' ')"

# K7: the release after the rotation trusts only the next key.
sed -i '' -e "s|PORTAL_PUBLIC_KEY_HEX: String = \"[^\"]*\"|PORTAL_PUBLIC_KEY_HEX: String = \"$NEXT_PUB\"|" \
  -e 's|PORTAL_NEXT_PUBLIC_KEY_HEX: String = "[^"]*"|PORTAL_NEXT_PUBLIC_KEY_HEX: String = ""|' "$CFG"
grep -q "val PORTAL_PUBLIC_KEY_HEX: String = \"$NEXT_PUB\"" "$CFG" && w8_build next && xcrun simctl install "$UDID" "$W8D/next.app" \
  && ok "K7: rebuilt trusting only the next key and installed (the floors the two-key host wrote are kept)" \
  || { bad "K7: the next-key host did not build or install"; grep -E '^e: |error:' "$EV/w8-xcodebuild-next.log" | head; }
launch K7-next
C="$EV/K7-next.console.txt"
grep -q "KeliverHost: verifying manifests with portal-ed25519 ${NEXT_PUB:0:8}…; updates" "$C" \
  && grep -q "loading https://localhost:8443/bundles/v11/manifest.zipline.json (.*); rollback floor 11" "$C" \
  && grep -q "codeLoadSuccess modules=[0-9]* sequence=11 source=network" "$C" \
  && ok "K7: the next-key host loaded v11, starting from floor 11 (carried over by the two-key host)" \
  || bad "K7: $(grep -E 'KeliverHost|codeLoad' "$C" | head -4 | tr '\n' ' ')"
reads K7-next Bunker && ok "K7: the screen reads 'Bunker' (v11)" || bad "K7: 'Bunker' is not on the screen"

# K8: a bundle signed by the retired key only, published on purpose.
w3_title Bunker Shelter
w8_publish v12 "$STORE/keys/ed25519.priv" --accept-key-change && grep -q "published v12 (sequence 12, channel stable" "$EV/w8-publish-v12.log" \
  && [ "$(w8_signatures 12)" = "$OLD_NAME,portal-ed25519" ] \
  && ok "K8: v12 (\"Shelter\"), signed by the old key only, published with --accept-key-change" \
  || { bad "K8: publish v12"; tail -20 "$EV/w8-publish-v12.log"; }
launch K8-next
C="$EV/K8-next.console.txt"
grep -q "loading https://localhost:8443/bundles/v12/manifest.zipline.json" "$C" && grep -qE "did not verify|no keys in the manifest were recognized" "$C" \
  && grep -q "the bundle did not load; falling back to the cached last good bundle (last loaded from https://localhost:8443/bundles/v11/" "$C" \
  && grep -q "codeLoadSuccess modules=[0-9]* sequence=11 source=cache" "$C" && ! grep -q "codeLoadSuccess modules=[0-9]* sequence=12" "$C" \
  && ok "K8: the next-key host refused v12 (old key only) and fell back to the cached v11" \
  || bad "K8: $(grep -E 'KeliverHost|codeLoad' "$C" | tail -5 | tr '\n' ' ')"
reads K8-next Bunker && ok "K8: the screen still reads 'Bunker' (v11)" || bad "K8: 'Bunker' is not on the screen"

sed -i '' -e "s|PORTAL_PUBLIC_KEY_HEX: String = \"[^\"]*\"|PORTAL_PUBLIC_KEY_HEX: String = \"$PUB\"|" "$CFG"
xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
kill "$SPID" 2>/dev/null; wait "$SPID" 2>/dev/null; SPID=""
fi
