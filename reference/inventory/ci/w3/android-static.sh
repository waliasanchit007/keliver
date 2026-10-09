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
#   S8  (W4.2) v1 offered again after v2 ran: refused on its signed sequence
#   S10 (W4.3) rollback done right: keliver-publish --republish 1 publishes v1's
#       code again as v3, at sequence 3; the host (floor 2) runs it ("Depot")
#   S11 (W4.4) v4 ("Backroom"), published to beta only, does not reach this
#       stable host: it keeps running v3
#   S12 (W4.4) --promote 4 --channel stable: the same v4 now reaches it
#   S7  server down: the host starts from its cached newest bundle ($LAST_V)
#   S9  (W4.2) the stored floor above that cached bundle, offline: refused
#   S9b (W4.2) the same with the server up but failing the lookup: the network
#       fallback to the last-good manifest is refused too
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

( cd "$APP" && "$REPO/scripts/keliver-new-publish-target.sh" ) > "$EV/w3-signing-upgrade.log" 2>&1
if grep -q '(signing-0.3.7) was replaced by the current one' "$EV/w3-signing-upgrade.log"; then
  ( cd "$APP" && git add build.gradle && git -c user.name=w3 -c user.email=w3@invalid commit -qm "W3: the signing block that reads KELIVER_SIGNING_KEY_FILE" ) \
    && ok "W3: this checkout's keliver-new-publish-target.sh upgraded the app's 0.3.7 signing block" || bad "W3: could not commit the upgraded signing block"
elif grep -q 'already has the signing block this script writes' "$EV/w3-signing-upgrade.log"; then
  ok "W3: the app's signing block is already the current one (a candidate zip's scaffolder wrote it)"
else
  bad "W3: upgrading the 0.3.7 signing block"; cat "$EV/w3-signing-upgrade.log"
fi

SCREEN="$APP/src/jsMain/kotlin/screens/inventory.kt"
w3_title(){  # $1 from, $2 to: the edit a developer makes; no relay involved
  sed -i.bak "s/text = \"$1\"/text = \"$2\"/" "$SCREEN" && rm -f "$SCREEN.bak" && grep -q "text = \"$2\"" "$SCREEN"
}
w3_publish(){  # $1 label, then extra flags: the CLI as a CI job runs it — the key from a file, no store
  local label="$1"; shift
  ( cd "$APP" && KELIVER_SIGNING_KEY_FILE="$STORE/keys/ed25519.priv" KELIVER_TOOLS_BIN="$W3/no-tools-bin" \
      "$W3_PUBLISH" . --out "$W3/site" --public-key-file "$STORE/keys/ed25519.pub" "$@" ) > "$EV/w3-publish-$label.log" 2>&1
}
w3_same_modules(){  # $1 $2: v<$1> and v<$2> hold the same module files, byte for byte (W4.3)
  python3 -c 'import os,sys; a,b=sys.argv[1:3]; fa=sorted(f for f in os.listdir(a) if f!="manifest.zipline.json"); fb=sorted(f for f in os.listdir(b) if f!="manifest.zipline.json"); sys.exit(0 if fa and fa==fb and all(open(os.path.join(a,f),"rb").read()==open(os.path.join(b,f),"rb").read() for f in fa) else 1)' \
    "$W3/site/bundles/v$1" "$W3/site/bundles/v$2"
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
[ "$rc" = 4 ] && grep -q "REFUSED" "$EV/w3-publish-foreign-key.log" && grep -q "does not verify" "$EV/w3-publish-foreign-key.log" \
  && [ "$before" = "$(w3_site)" ] \
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

# S8 (W4.2): the floor rose when v2 ran; the server then offers v1 again, as the
# newest entry (a replayed or rolled-back index). v1's manifest is validly
# signed, but for sequence 1: the host refuses it on the network and runs nothing.
grep -q "$HOST_TAG: rollback floor raised: 1 -> 2" "$EV/logcat-static-v2.txt" \
  && ok "S8: running v2 raised the host's rollback floor from 1 to 2" || bad "S8: no floor raise logged at v2"
python3 -c 'import json,sys; p=sys.argv[1]; i=json.load(open(p)); v1=[e for e in i["entries"] if e["version"]==1][0]; i["entries"].append(dict(v1, sequence=max(e["sequence"] for e in i["entries"])+1)); json.dump(i, open(p,"w"))' \
  "$W3/site/bundles/index.json"
launch prod "$EV/logcat-static-rollback.txt"
grep -q "rollback refused: sequence 1 is below 2" "$EV/logcat-static-rollback.txt" \
  && ok "S8: v1 offered again after v2: refused on its signed sequence (1 < 2)" \
  || bad "S8: no rollback refusal: $(grep -E "$HOST_TAG" "$EV/logcat-static-rollback.txt" | head -3 | tr '\n' ' ')"
grep -q "codeLoadSuccess" "$EV/logcat-static-rollback.txt" && bad "S8: something loaded" || ok "S8: no code loaded"
cp "$W3/index.good" "$W3/site/bundles/index.json"

# S10 (W4.3): rollback done right. v1's code goes out AGAIN as a new sequence:
# keliver-publish --republish 1 copies v1's modules, the signing block's
# keliverResign signs the copy for sequence 3 (key from KELIVER_SIGNING_KEY_FILE,
# nothing compiled), and it is published as v3. The host, whose floor is 2,
# runs it: the screen reads "Depot" again, and the floor rises to 3.
w3_publish republish-v1 --republish 1 && grep -q "republished v1 as v3 (sequence 3" "$EV/w3-publish-republish-v1.log" \
  && ok "S10: $(grep -o 'republished v1 as v3 ([^)]*)' "$EV/w3-publish-republish-v1.log"), by the app's keliverResign" \
  || { bad "S10: republish v1"; tail -20 "$EV/w3-publish-republish-v1.log"; }
grep -q "Task :compile" "$EV/w3-publish-republish-v1.log" && bad "S10: the republish compiled" || ok "S10: the republish compiled nothing"
cp "$W3/site/bundles/index.json" "$EV/w3-index-v3.json" 2>/dev/null
w3_signed_sequence 3 && ok "W4.3: v3's manifest is signed for sequence 3" || bad "W4.3: v3's manifest does not carry signed sequence 3"
w3_same_modules 1 3 && ok "S10: v3's modules are v1's, byte for byte" || bad "S10: v3's modules differ from v1's"
launch prod "$EV/logcat-static-republish.txt"
grep -q "$HOST_TAG: loading https://10.0.2.2:8443/bundles/v3/manifest.zipline.json (index sequence 3" "$EV/logcat-static-republish.txt" \
  && grep -q "codeLoadSuccess" "$EV/logcat-static-republish.txt" \
  && ok "S10: the host that had run v2 (floor 2) loaded the republished v1 as v3" \
  || bad "S10: v3 did not load: $(grep -E "$HOST_TAG" "$EV/logcat-static-republish.txt" | head -3 | tr '\n' ' ')"
grep -q "$HOST_TAG: rollback floor raised: 2 -> 3" "$EV/logcat-static-republish.txt" \
  && ok "S10: running v3 raised the floor from 2 to 3" || bad "S10: no floor raise logged at v3"
drive title Depot S10static; fold "S10: v1's code is back: the screen reads Depot" $?

# S11 (W4.4): channels. An edit published to beta only ("Backroom", v4 at
# sequence 4) does not reach this host, which was built for stable: it keeps
# running v3.
w3_title Warehouse Backroom && w3_publish v4-beta --channel beta && grep -q "published v4 (sequence 4, channel beta" "$EV/w3-publish-v4-beta.log" \
  && ok "S11: keliver-publish wrote v4 at sequence 4 on channel beta" || { bad "S11: publish v4 to beta"; tail -20 "$EV/w3-publish-v4-beta.log"; }
launch prod "$EV/logcat-static-beta.txt"
C="$EV/logcat-static-beta.txt"
grep -q "$HOST_TAG: loading https://10.0.2.2:8443/bundles/v3/manifest.zipline.json (index sequence 3, channel stable" "$C" && grep -q "codeLoadSuccess" "$C" \
  && ! grep -q "bundles/v4/" "$C" && ok "S11: the stable host passed over beta's v4 and loaded v3" \
  || bad "S11: $(grep -E 'loading|codeLoad' "$C" | head -3 | tr '\n' ' ')"
drive title Depot S11static; fold "S11: the stable host still reads Depot" $?

# S12 (W4.4): promotion. --promote 4 --channel stable adds a second index entry
# for the same v4 (nothing built or signed, no new v<N>/); the host now loads it,
# and its floor rises to 4.
( cd "$APP" && "$W3_PUBLISH" . --out "$W3/site" --public-key-file "$STORE/keys/ed25519.pub" --promote 4 --channel stable ) > "$EV/w3-promote-4.log" 2>&1 \
  && grep -q "promoted sequence 4 (v4) from beta to stable" "$EV/w3-promote-4.log" && ! grep -q "Task :" "$EV/w3-promote-4.log" \
  && ok "S12: v4 promoted from beta to stable; nothing built or signed" || { bad "S12: promote"; tail -20 "$EV/w3-promote-4.log"; }
cp "$W3/site/bundles/index.json" "$EV/w3-index-promoted.json" 2>/dev/null
[ "$(ls "$W3/site/bundles" | grep -c '^v')" = 4 ] && ok "S12: the promotion wrote no new v<N>/" || bad "S12: the bundle directories changed"
launch prod "$EV/logcat-static-promoted.txt"
C="$EV/logcat-static-promoted.txt"
grep -q "$HOST_TAG: loading https://10.0.2.2:8443/bundles/v4/manifest.zipline.json (index sequence 4, channel stable" "$C" && grep -q "codeLoadSuccess" "$C" \
  && ok "S12: the stable host loaded the promoted v4" || bad "S12: v4 did not load: $(grep -E 'loading|codeLoad|refused' "$C" | head -3 | tr '\n' ' ')"
grep -q "rollback floor raised: 3 -> 4" "$C" && ok "S12: running v4 raised the floor from 3 to 4" || bad "S12: no floor raise logged at v4"
drive title Backroom S12static; fold "S12: after the promotion the screen reads Backroom" $?
# The newest bundle this host has run, for S7, S9 and S9b.
LAST_V=4; LAST_TITLE=Backroom

kill "$SERVE_PID" 2>/dev/null; wait "$SERVE_PID" 2>/dev/null; SERVE_PID=""
curl -sf --cacert "$W3_TLS/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json \
  && bad "S7: the static server is still answering" || ok "S7: no bundle server is answering"
launch prod "$EV/logcat-static-offline.txt"
grep -q "$HOST_TAG: lookup failed; starting from the cached bundle (last loaded from https://10.0.2.2:8443/bundles/v$LAST_V/" "$EV/logcat-static-offline.txt" \
  && grep -q "codeLoadSuccess" "$EV/logcat-static-offline.txt" && ok "S7: offline, the host started from its cached v$LAST_V" || bad "S7: no start from the cache"
drive title "$LAST_TITLE" S7static; fold "S7: $LAST_TITLE offline" $?

# S9 (W4.2): the floor holds on the cache start too. Raise the stored floor above
# the cached newest bundle (as a host that had run a newer bundle would have it), then start
# offline: the cached manifest is refused and nothing runs.
adb -s "$SERIAL" shell am force-stop "$PROD_ID"
adb -s "$SERIAL" shell "run-as $PROD_ID sed -i -E 's/(name=\"highestSequence-[^\"]*\" value=\")[0-9]+/\\19/' shared_prefs/keliver-host.xml"
adb -s "$SERIAL" shell "run-as $PROD_ID cat shared_prefs/keliver-host.xml" > "$EV/w4-prefs-floor9.xml" 2>&1
grep -q 'highestSequence-[^"]*" value="9"' "$EV/w4-prefs-floor9.xml" \
  && ok "S9: the host's stored rollback floor is now 9" || bad "S9: could not set the floor: $(head -c 300 "$EV/w4-prefs-floor9.xml")"
launch prod "$EV/logcat-static-cache-floor.txt"
grep -q "cached bundle refused: rollback refused: sequence $LAST_V is below 9" "$EV/logcat-static-cache-floor.txt" \
  && ok "S9: offline, the cached v$LAST_V was refused below the floor" \
  || bad "S9: no cache refusal: $(grep -E "$HOST_TAG" "$EV/logcat-static-cache-floor.txt" | head -3 | tr '\n' ' ')"
grep -q "codeLoadSuccess" "$EV/logcat-static-cache-floor.txt" && bad "S9: something loaded" || ok "S9: no code loaded"

# S9b (W4.2): the same, with the server UP but failing the lookup on purpose (no
# index, no bundles/latest). The host takes the cache path; the cache is refused,
# so Zipline fetches the last-good manifest from the network, and that fetch is
# held to the floor too: the newest (sequence $LAST_V) is refused against 9. (Before the review
# of W4.2 the Android cache path used an unguarded client, and this loaded v2.)
mv "$W3/site/bundles/index.json" "$W3/index.hidden"
python3 "$HERE/w3/static_https.py" "$W3/site" 8443 "$W3_TLS/server.pem" "$W3_TLS/server.key" "$EV/w3-server-s9b.log" &
SERVE_PID=$!
for _ in $(seq 1 30); do curl -s --cacert "$W3_TLS/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/v$LAST_V/manifest.zipline.json && break; sleep 1; done
launch prod "$EV/logcat-static-fallback-floor.txt"
grep -q "$HOST_TAG: lookup failed; starting from the cached bundle" "$EV/logcat-static-fallback-floor.txt" \
  && grep -q "rollback refused: sequence $LAST_V is below 9" "$EV/logcat-static-fallback-floor.txt" \
  && ok "S9b: lookup failed with the server up; the cache and then the network fetch of v$LAST_V were both held to the floor" \
  || bad "S9b: no refusal on the network fallback: $(grep -E "$HOST_TAG" "$EV/logcat-static-fallback-floor.txt" | head -4 | tr '\n' ' ')"
grep -q "^GET /bundles/v$LAST_V/manifest.zipline.json" "$EV/w3-server-s9b.log" \
  && ok "S9b: the host did fetch v$LAST_V's manifest from the network (the guarded path ran)" || bad "S9b: no network fetch of v$LAST_V's manifest"
grep -q "codeLoadSuccess" "$EV/logcat-static-fallback-floor.txt" && bad "S9b: something loaded" || ok "S9b: no code loaded"
kill "$SERVE_PID" 2>/dev/null; wait "$SERVE_PID" 2>/dev/null; SERVE_PID=""
mv "$W3/index.hidden" "$W3/site/bundles/index.json"
mkdir -p "$EV/w3-site" && cp "$W3/site/bundles/index.json" "$EV/w3-site/index.json"
