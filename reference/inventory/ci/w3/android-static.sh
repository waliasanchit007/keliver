# Sourced by ci/device.sh after P7: W3, the static route, with no relay at all.
# Uses device.sh's SERIAL, APP, STORE, FPUB, PROD_ID, HOST_TAG, WORK, EV, HERE,
# SERVE_PID, and its ok/bad/launch/drive/fold; prepare.sh built W3_PUBLISH
# (keliver-publish from this checkout), W3_TLS (a throwaway CA and server
# certificate) and W3_APK (the same production host, built for
# https://10.0.2.2:8443).
#   S2  signed v1 loads through bundles/index.json over HTTPS ("Depot")
#   S4  an edit, published as v2 by the CLI, reaches the host ("Warehouse")
#   S5  checked against another app's public key, the CLI refuses this app's
#       already-built bundle (--skip-build); nothing changes
#   S6  an index whose sha256 isn't the manifest's: nothing loads
#   S7  server down: the host starts from its cached v2
# Signing here is the CI route: KELIVER_SIGNING_KEY_FILE names the key, and
# KELIVER_TOOLS_BIN points at nothing, so a build that fell back to a store
# lookup would fail instead of signing. The app was wired by the published
# 0.3.7 zip, whose signing block predates KELIVER_SIGNING_KEY_FILE; so first
# this checkout's keliver-new-publish-target.sh upgrades it (W3's block, not in
# a released bundle yet). That is also the 0.3.7 -> current upgrade, on CI.

echo "--- 4. W3: static HTTPS, no relay"
W3="$WORK/w3"
bash "$HERE/w3/android-ca.sh" "$SERIAL" "$W3_TLS/ca.pem" > "$EV/w3-android-ca.log" 2>&1 \
  && ok "W3: the throwaway CA is trusted by this emulator (system store, until reboot)" \
  || { bad "W3: the CA could not be installed"; cat "$EV/w3-android-ca.log"; }
cp "$W3_TLS/ca.pem" "$EV/w3-ca.pem"

( cd "$APP" && "$REPO/scripts/keliver-new-publish-target.sh" ) > "$EV/w3-signing-upgrade.log" 2>&1 \
  && grep -q '(signing-0.3.7) was replaced by the current one' "$EV/w3-signing-upgrade.log" \
  && grep -q 'keliver.signingKeyFile' "$APP/build.gradle" \
  && ( cd "$APP" && git add build.gradle && git -c user.name=w3 -c user.email=w3@invalid commit -qm "W3: the signing block that reads KELIVER_SIGNING_KEY_FILE" ) \
  && ok "W3: this checkout's keliver-new-publish-target.sh upgraded the app's 0.3.7 signing block" \
  || { bad "W3: upgrading the 0.3.7 signing block"; cat "$EV/w3-signing-upgrade.log"; }

SCREEN="$APP/src/jsMain/kotlin/screens/inventory.kt"
w3_title(){  # $1 from, $2 to: the edit a developer makes; no relay involved
  sed -i.bak "s/text = \"$1\"/text = \"$2\"/" "$SCREEN" && rm -f "$SCREEN.bak" && grep -q "text = \"$2\"" "$SCREEN"
}
w3_publish(){  # $1 label, then extra flags: the CLI as a CI job runs it — the key from a file, no store
  local label="$1"; shift
  ( cd "$APP" && KELIVER_SIGNING_KEY_FILE="$STORE/keys/ed25519.priv" KELIVER_TOOLS_BIN="$W3/no-tools-bin" \
      "$W3_PUBLISH" . --out "$W3/site" --public-key-file "$STORE/keys/ed25519.pub" "$@" ) > "$EV/w3-publish-$label.log" 2>&1
}
w3_signed_sequence(){  # $1 = N: v<N>'s manifest carries keliver.sequence "N" in its signed metadata (W4.1)
  python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); sys.exit(0 if m.get("metadata",{}).get("keliver.sequence")==sys.argv[2] else 1)' \
    "$W3/site/bundles/v$1/manifest.zipline.json" "$1"
}
w3_site(){ ( cd "$W3/site" && find . -type f -exec sha256sum {} + | sort ); }

w3_title Stockroom Depot && w3_publish v1 --init && grep -q "published v1 (sequence 1" "$EV/w3-publish-v1.log" \
  && ok "W3: keliver-publish compiled (key from KELIVER_SIGNING_KEY_FILE), verified and wrote $(grep -o 'published v1 ([^)]*)' "$EV/w3-publish-v1.log")" \
  || { bad "W3: publish v1"; tail -20 "$EV/w3-publish-v1.log"; }
cp "$W3/site/bundles/index.json" "$EV/w3-index-v1.json" 2>/dev/null
w3_signed_sequence 1 && ok "W4.1: v1's manifest is signed for sequence 1 (metadata keliver.sequence)" \
  || bad "W4.1: v1's manifest does not carry signed sequence 1"

python3 "$HERE/w3/static_https.py" "$W3/site" 8443 "$W3_TLS/server.pem" "$W3_TLS/server.key" "$EV/w3-server.log" &
SERVE_PID=$!
for _ in $(seq 1 30); do curl -sf --cacert "$W3_TLS/ca.pem" -o /dev/null https://localhost:8443/bundles/index.json && break; sleep 1; done
curl -sf --cacert "$W3_TLS/ca.pem" -o /dev/null https://localhost:8443/bundles/index.json \
  && ok "W3: the static server answers over HTTPS, trusted through that CA" || bad "W3: the static server did not answer"

adb -s "$SERIAL" install -r "$W3_APK" > "$EV/install-static-host.log" 2>&1 \
  && ok "W3: installed the host built for https://10.0.2.2:8443 ($(sha256sum "$W3_APK" | cut -c1-12)…)" || bad "W3: the static host did not install"
adb -s "$SERIAL" shell pm clear "$PROD_ID" > /dev/null

launch prod "$EV/logcat-static-v1.txt"
grep -q "$HOST_TAG: loading https://10.0.2.2:8443/bundles/v1/manifest.zipline.json (index sequence 1" "$EV/logcat-static-v1.txt" \
  && grep -q "codeLoadSuccess" "$EV/logcat-static-v1.txt" \
  && ok "S2: signed v1 loaded through bundles/index.json over HTTPS" \
  || { bad "S2: v1 did not load from the static server"; grep "$HOST_TAG" "$EV/logcat-static-v1.txt" | head -8; }
drive title Depot S2static; fold "S2: v1 shows Depot" $?
grep -q "^GET /bundles/index.json 200" "$EV/w3-server.log" && grep -q "^GET /bundles/v1/manifest.zipline.json 200" "$EV/w3-server.log" \
  && ok "S2: the static server served index.json and v1's manifest" || bad "S2: the server log lacks the index or the manifest"

w3_title Depot Warehouse && w3_publish v2 && grep -q "published v2 (sequence 2" "$EV/w3-publish-v2.log" \
  && ok "S4: keliver-publish wrote v2 at sequence 2" || { bad "S4: publish v2"; tail -20 "$EV/w3-publish-v2.log"; }
cp "$W3/site/bundles/index.json" "$EV/w3-index-v2.json" 2>/dev/null
w3_signed_sequence 2 && ok "W4.1: v2's manifest is signed for sequence 2 (metadata keliver.sequence)" \
  || bad "W4.1: v2's manifest does not carry signed sequence 2"
launch prod "$EV/logcat-static-v2.txt"
grep -q "$HOST_TAG: loading https://10.0.2.2:8443/bundles/v2/manifest.zipline.json (index sequence 2" "$EV/logcat-static-v2.txt" \
  && grep -q "codeLoadSuccess" "$EV/logcat-static-v2.txt" && ok "S4: the host loaded v2" || bad "S4: v2 did not load"
drive title Warehouse S4static; fold "S4: v2 shows Warehouse" $?

# S5: the copy's key from P5 is not this app's.
printf '%s\n' "$FPUB" > "$W3/foreign.pub"
before="$(w3_site)"
( cd "$APP" && "$W3_PUBLISH" . --out "$W3/site" --skip-build --public-key-file "$W3/foreign.pub" ) > "$EV/w3-publish-foreign-key.log" 2>&1
rc=$?
[ "$rc" = 4 ] && grep -q "REFUSED" "$EV/w3-publish-foreign-key.log" && [ "$before" = "$(w3_site)" ] \
  && ok "S5: against another app's key the CLI refused (exit 4); the site is byte-identical" || bad "S5: exit $rc"

# S6: the newest entry's manifestSha256 is not its manifest's.
cp "$W3/site/bundles/index.json" "$W3/index.good"
python3 -c 'import json,sys; p=sys.argv[1]; i=json.load(open(p)); i["entries"][-1]["manifestSha256"]="0"*64; json.dump(i, open(p,"w"))' \
  "$W3/site/bundles/index.json"
launch prod "$EV/logcat-static-pin.txt"
grep -q "manifest sha256 mismatch" "$EV/logcat-static-pin.txt" && ok "S6: the manifest was refused on its sha256" \
  || bad "S6: no sha256 refusal: $(grep -E 'codeLoad' "$EV/logcat-static-pin.txt" | head -2 | tr '\n' ' ')"
grep -q "codeLoadSuccess" "$EV/logcat-static-pin.txt" && bad "S6: something loaded" || ok "S6: no code loaded"
cp "$W3/index.good" "$W3/site/bundles/index.json"

kill "$SERVE_PID" 2>/dev/null; wait "$SERVE_PID" 2>/dev/null; SERVE_PID=""
curl -sf --cacert "$W3_TLS/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json \
  && bad "S7: the static server is still answering" || ok "S7: no bundle server is answering"
launch prod "$EV/logcat-static-offline.txt"
grep -q "$HOST_TAG: lookup failed; starting from the cached bundle (last loaded from https://10.0.2.2:8443/bundles/v2/" "$EV/logcat-static-offline.txt" \
  && grep -q "codeLoadSuccess" "$EV/logcat-static-offline.txt" && ok "S7: offline, the host started from its cached v2" || bad "S7: no start from the cache"
drive title Warehouse S7static; fold "S7: Warehouse offline" $?
mkdir -p "$EV/w3-site" && cp "$W3/site/bundles/index.json" "$EV/w3-site/index.json"
