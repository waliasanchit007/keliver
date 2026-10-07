# Sourced by ci/ios.sh after P7: W3, the static route, with no relay at all.
# Uses ios.sh's APP, STORE, FPUB, UDID, BID, APPB, WORK, EV, HERE, SPID, and
# its ok/bad/launch/reads.
#
# This checkout's keliver-publish writes bundles/ into a directory that a plain
# static HTTPS server (w3/static_https.py) serves. Its certificate is from a
# throwaway CA added to THIS run's simulator only. The host is rebuilt for
# https://localhost:8443; nothing else about it changes.
#   S2  signed v1 loads through bundles/index.json over HTTPS ("Depot")
#   S4  an edit, published as v2 by the CLI, reaches the host ("Warehouse")
#   S5  checked against another app's public key, the CLI refuses this app's
#       already-built bundle (--skip-build); nothing changes
#   S6  an index whose sha256 isn't the manifest's: nothing loads
#   S7  server down: the host starts from its cached v2
# The app's signing block here is the one tools 0.3.6 wrote, so the CLI signs
# through the store (KELIVER_TOOLS_BIN). device.sh signs with
# KELIVER_SIGNING_KEY_FILE and no store lookup instead.

echo "--- W3 static HTTPS"
W3="$WORK/w3"; mkdir -p "$W3"
PUBLISH="$(bash "$HERE/w3/tools.sh" "$W3/tools" 2> "$EV/w3-tools.log" | tail -1)"
[ -n "$PUBLISH" ] && [ -x "$PUBLISH" ] && ok "W3: keliver-publish from this checkout, in the tools layout" \
  || { bad "W3: keliver-publish was not built"; tail -20 "$EV/w3-tools.log"; }
bash "$HERE/w3/tls.sh" "$W3/tls" > "$EV/w3-tls.txt" 2>&1 && cp "$W3/tls/ca.pem" "$EV/w3-ca.pem" \
  && xcrun simctl keychain "$UDID" add-root-cert "$W3/tls/ca.pem" \
  && ok "W3: a throwaway CA, trusted by this run's simulator only" || bad "W3: the CA was not installed"

SCREEN="$APP/src/jsMain/kotlin/screens/inventory.kt"
w3_title(){  # $1 from, $2 to: the edit a developer makes; no relay involved
  sed -i '' "s/text = \"$1\"/text = \"$2\"/" "$SCREEN" && grep -q "text = \"$2\"" "$SCREEN"
}
w3_publish(){  # $1 label, then extra flags: the CLI, run as a CI job would run it
  local label="$1"; shift
  ( cd "$APP" && "$PUBLISH" . --out "$W3/site" --public-key-file "$STORE/keys/ed25519.pub" "$@" ) > "$EV/w3-publish-$label.log" 2>&1
}
w3_site(){ ( cd "$W3/site" && find . -type f -exec shasum -a 256 {} + | sort ); }

w3_title Stockroom Depot && w3_publish v1 --init && grep -q "published v1 (sequence 1" "$EV/w3-publish-v1.log" \
  && ok "W3: keliver-publish compiled, verified and wrote $(grep -o 'published v1 ([^)]*)' "$EV/w3-publish-v1.log")" \
  || { bad "W3: publish v1"; tail -20 "$EV/w3-publish-v1.log"; }
cp "$W3/site/bundles/index.json" "$EV/w3-index-v1.json" 2>/dev/null

python3 "$HERE/w3/static_https.py" "$W3/site" 8443 "$W3/tls/server.pem" "$W3/tls/server.key" "$EV/w3-server.log" &
SPID=$!
for _ in $(seq 1 30); do curl -sf --cacert "$W3/tls/ca.pem" -o /dev/null https://localhost:8443/bundles/index.json && break; sleep 1; done
curl -sf --cacert "$W3/tls/ca.pem" -o /dev/null https://localhost:8443/bundles/index.json \
  && ok "W3: the static server answers over HTTPS, trusted through that CA" || bad "W3: the static server did not answer"

CFG="$(find "$APP/host-ios/src/iosMain/kotlin" -name HostConfig.kt | head -1)"
sed -i '' 's|BUNDLE_SERVER: String = "[^"]*"|BUNDLE_SERVER: String = "https://localhost:8443"|' "$CFG"
( cd "$APP" && xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -configuration Debug \
    -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath "$WORK/dd" \
    CODE_SIGNING_ALLOWED=NO build ) > "$EV/w3-xcodebuild.log" 2>&1 \
  && ok "W3: the host rebuilt for https://localhost:8443" \
  || { bad "W3: xcodebuild failed"; grep -E '^e: |error:' "$EV/w3-xcodebuild.log" | head; }
xcrun simctl uninstall "$UDID" "$BID"; xcrun simctl install "$UDID" "$APPB"

launch S2-static
C="$EV/S2-static.console.txt"
grep -q "KeliverHost: loading https://localhost:8443/bundles/v1/manifest.zipline.json (index sequence 1" "$C" && grep -q "codeLoadSuccess" "$C" \
  && ok "S2: signed v1 loaded through bundles/index.json over HTTPS" || bad "S2: v1 did not load from the static server"
reads S2-static Depot && ok "S2: the screen reads 'Depot'" || bad "S2: 'Depot' is not on the screen"
grep -q "^GET /bundles/index.json 200" "$EV/w3-server.log" && grep -q "^GET /bundles/v1/manifest.zipline.json 200" "$EV/w3-server.log" \
  && ok "S2: the static server served index.json and v1's manifest" || bad "S2: the server log lacks the index or the manifest"

w3_title Depot Warehouse && w3_publish v2 && grep -q "published v2 (sequence 2" "$EV/w3-publish-v2.log" \
  && ok "S4: keliver-publish wrote v2 at sequence 2" || { bad "S4: publish v2"; tail -20 "$EV/w3-publish-v2.log"; }
cp "$W3/site/bundles/index.json" "$EV/w3-index-v2.json" 2>/dev/null
launch S4-static
C="$EV/S4-static.console.txt"
grep -q "loading https://localhost:8443/bundles/v2/manifest.zipline.json (index sequence 2" "$C" && grep -q "codeLoadSuccess" "$C" \
  && ok "S4: the host loaded v2" || bad "S4: v2 did not load"
reads S4-static Warehouse && ok "S4: the screen reads 'Warehouse'" || bad "S4: 'Warehouse' is not on the screen"

# S5: the copy's key from P5 is not this app's.
printf '%s\n' "$FPUB" > "$W3/foreign.pub"
before="$(w3_site)"
( cd "$APP" && "$PUBLISH" . --out "$W3/site" --skip-build --public-key-file "$W3/foreign.pub" ) > "$EV/w3-publish-foreign-key.log" 2>&1
rc=$?
[ "$rc" = 4 ] && grep -q "REFUSED" "$EV/w3-publish-foreign-key.log" && [ "$before" = "$(w3_site)" ] \
  && ok "S5: against another app's key the CLI refused (exit 4); the site is byte-identical" || bad "S5: exit $rc"

# S6: the newest entry's manifestSha256 is not its manifest's. The host's
# Zipline client refuses the manifest it fetched, so nothing loads.
cp "$W3/site/bundles/index.json" "$W3/index.good"
python3 -c 'import json,sys; p=sys.argv[1]; i=json.load(open(p)); i["entries"][-1]["manifestSha256"]="0"*64; json.dump(i, open(p,"w"))' \
  "$W3/site/bundles/index.json"
launch S6-pin
C="$EV/S6-pin.console.txt"
grep -q "manifest sha256 mismatch" "$C" && ok "S6: the manifest was refused on its sha256" \
  || bad "S6: no sha256 refusal: $(grep -E 'codeLoad' "$C" | head -2 | tr '\n' ' ')"
grep -q "codeLoadSuccess" "$C" && bad "S6: something loaded" || ok "S6: no code loaded"
cp "$W3/index.good" "$W3/site/bundles/index.json"

kill "$SPID" 2>/dev/null; wait "$SPID" 2>/dev/null; SPID=""
curl -sf --cacert "$W3/tls/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json \
  && bad "S7: the static server is still answering" || ok "S7: no bundle server is answering"
launch S7-static
C="$EV/S7-static.console.txt"
grep -q "lookup failed; starting from the cached bundle (last loaded from https://localhost:8443/bundles/v2/" "$C" \
  && grep -q "codeLoadSuccess" "$C" && ok "S7: offline, the host started from its cached v2" || bad "S7: no start from the cache"
reads S7-static Warehouse && ok "S7: the screen reads 'Warehouse' offline" || bad "S7: 'Warehouse' is not on the screen"
mkdir -p "$EV/w3-site" && cp "$W3/site/bundles/index.json" "$EV/w3-site/index.json"
