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
#   S6  an index whose sha256 isn't the manifest's: that manifest is refused,
#       and the host falls back to the cached last good bundle (W5, U2)
#   S8  (W4.2) v1 offered again after v2 ran: refused on its signed sequence
#   S10 (W4.3) rollback done right: keliver-publish --republish 1 publishes v1's
#       code again as v3, at sequence 3; the host (floor 2) runs it ("Depot")
#   S11 (W4.4) v4 ("Backroom"), published to beta only, does not reach this
#       stable host: it keeps running v3
#   S12 (W4.4) --promote 4 --channel stable: the same v4 now reaches it
#   S13 (W4.5) v5 ("Loft") at rollout 0: not delivered, the host stays on v4
#   S14 (W4.5) --set-rollout 5 --rollout 100: delivered (floor 4 -> 5)
#   S14b (W4.5) halted again (0): the host that ran v5 keeps it (its floor)
#   S15 (W4.5) v6 ("Attic") with minHostVersion 999, above this host's 1:
#       skipped, the host stays on v5
#   S7  server down: the host starts from its cached newest bundle ($LAST_V)
#   S9  (W4.2) the stored floor above that cached bundle, offline: refused
#   S9b (W4.2) the same with the server up but failing the lookup: the network
#       fallback to the last-good manifest is refused too
# The app was wired by the published 0.3.7 zip, whose signing block writes no
# publish sequence (W4). So first this checkout's keliver-new-publish-target.sh
# upgrades it in place (the 0.3.7 -> current upgrade, on CI); the CLI then signs
# through the store (KELIVER_TOOLS_BIN). device.sh signs with
# KELIVER_SIGNING_KEY_FILE and no store lookup instead.

echo "--- W3 static HTTPS"
W3="$WORK/w3"; mkdir -p "$W3"
# Which keliver-publish and publish-target scaffolder: the zip's own bin/ when it
# ships them (0.3.8 on) and the hosts come from the zip; a CANDIDATE must ship
# them (no fallback). Otherwise this checkout's, in the tools layout.
if [ "${KELIVER_SCAFFOLD_FROM:-zip}" = zip ] && [ -x "$KP/keliver-publish" ]; then
  PUBLISH="$KP/keliver-publish"; PUBLISH_TARGET="$KP/keliver-new-publish-target.sh"
  ok "W3: keliver-publish and keliver-new-publish-target.sh from the tools $LABEL zip's bin/"
elif [ "${KELIVER_SCAFFOLD_FROM:-zip}" = zip ] && [ -n "${KELIVER_CANDIDATE_SHA256:-}" ]; then
  bad "W3: the candidate zip has no executable bin/keliver-publish"; PUBLISH=""; PUBLISH_TARGET="$KP/keliver-new-publish-target.sh"
else
  PUBLISH="$(bash "$HERE/w3/tools.sh" "$W3/tools" 2> "$EV/w3-tools.log" | tail -1)"; PUBLISH_TARGET="$REPO/scripts/keliver-new-publish-target.sh"
  [ -n "$PUBLISH" ] && [ -x "$PUBLISH" ] && ok "W3: keliver-publish from this checkout, in the tools layout" \
    || { bad "W3: keliver-publish was not built"; tail -20 "$EV/w3-tools.log"; }
fi
bash "$HERE/w3/tls.sh" "$W3/tls" > "$EV/w3-tls.txt" 2>&1 && cp "$W3/tls/ca.pem" "$EV/w3-ca.pem" \
  && xcrun simctl keychain "$UDID" add-root-cert "$W3/tls/ca.pem" \
  && ok "W3: a throwaway CA, trusted by this run's simulator only" || bad "W3: the CA was not installed"

( cd "$APP" && "$PUBLISH_TARGET" ) > "$EV/w3-signing-upgrade.log" 2>&1
if grep -q '(signing-0.3.7) was replaced by the current one' "$EV/w3-signing-upgrade.log"; then
  ( cd "$APP" && git add build.gradle && git -c user.name=w3 -c user.email=w3@invalid commit -qm "W4: the signing block that signs a publish sequence" ) \
    && ok "W3: this checkout's keliver-new-publish-target.sh upgraded the app's 0.3.7 signing block" || bad "W3: could not commit the upgraded signing block"
elif grep -q 'already has the signing block this script writes' "$EV/w3-signing-upgrade.log"; then
  ok "W3: the app's signing block is already the current one (a candidate zip's scaffolder wrote it)"
else
  bad "W3: upgrading the 0.3.7 signing block"; cat "$EV/w3-signing-upgrade.log"
fi

SCREEN="$APP/src/jsMain/kotlin/screens/inventory.kt"
w3_title(){  # $1 from, $2 to: the edit a developer makes; no relay involved
  sed -i '' "s/text = \"$1\"/text = \"$2\"/" "$SCREEN" && grep -q "text = \"$2\"" "$SCREEN"
}
w3_publish(){  # $1 label, then extra flags: the CLI, run as a CI job would run it
  local label="$1"; shift
  ( cd "$APP" && "$PUBLISH" . --out "$W3/site" --public-key-file "$STORE/keys/ed25519.pub" "$@" ) > "$EV/w3-publish-$label.log" 2>&1
}
w3_same_modules(){  # $1 $2: v<$1> and v<$2> hold the same module files, byte for byte (W4.3)
  python3 -c 'import os,sys; a,b=sys.argv[1:3]; fa=sorted(f for f in os.listdir(a) if f!="manifest.zipline.json"); fb=sorted(f for f in os.listdir(b) if f!="manifest.zipline.json"); sys.exit(0 if fa and fa==fb and all(open(os.path.join(a,f),"rb").read()==open(os.path.join(b,f),"rb").read() for f in fa) else 1)' \
    "$W3/site/bundles/v$1" "$W3/site/bundles/v$2"
}
w3_signed_sequence(){  # $1 = N: v<N>'s manifest carries keliver.sequence "N" in its signed metadata (W4.1)
  python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); sys.exit(0 if m.get("metadata",{}).get("keliver.sequence")==sys.argv[2] else 1)' \
    "$W3/site/bundles/v$1/manifest.zipline.json" "$1"
}
w3_site(){ ( cd "$W3/site" && find . -type f -exec shasum -a 256 {} + | sort ); }

w3_title Stockroom Depot && w3_publish v1 --init && grep -q "published v1 (sequence 1" "$EV/w3-publish-v1.log" \
  && ok "W3: keliver-publish compiled, verified and wrote $(grep -o 'published v1 ([^)]*)' "$EV/w3-publish-v1.log")" \
  || { bad "W3: publish v1"; tail -20 "$EV/w3-publish-v1.log"; }
cp "$W3/site/bundles/index.json" "$EV/w3-index-v1.json" 2>/dev/null
w3_signed_sequence 1 && ok "W4.1: v1's manifest is signed for sequence 1 (metadata keliver.sequence)" \
  || bad "W4.1: v1's manifest does not carry signed sequence 1"

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
w3_signed_sequence 2 && ok "W4.1: v2's manifest is signed for sequence 2 (metadata keliver.sequence)" \
  || bad "W4.1: v2's manifest does not carry signed sequence 2"
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
[ "$rc" = 4 ] && grep -q "REFUSED" "$EV/w3-publish-foreign-key.log" && grep -q "does not verify" "$EV/w3-publish-foreign-key.log" \
  && [ "$before" = "$(w3_site)" ] \
  && ok "S5: against another app's key the CLI refused (exit 4); the site is byte-identical" || bad "S5: exit $rc"

# S6: the newest entry's manifestSha256 is not its manifest's. The host's
# Zipline client refuses the manifest it fetched; since v2 ran before, the host
# falls back to the cached v2 (W5, U2).
cp "$W3/site/bundles/index.json" "$W3/index.good"
python3 -c 'import json,sys; p=sys.argv[1]; i=json.load(open(p)); i["entries"][-1]["manifestSha256"]="0"*64; json.dump(i, open(p,"w"))' \
  "$W3/site/bundles/index.json"
launch S6-pin
C="$EV/S6-pin.console.txt"
grep -q "manifest sha256 mismatch" "$C" && ok "S6: the manifest was refused on its sha256" \
  || bad "S6: no sha256 refusal: $(grep -E 'codeLoad' "$C" | head -2 | tr '\n' ' ')"
grep -q "KeliverHost: the bundle did not load; falling back to the cached last good bundle (last loaded from https://localhost:8443/bundles/v2/" "$C" \
  && grep -q "codeLoadSuccess modules=[0-9]* sequence=2" "$C" && ! grep "codeLoadSuccess" "$C" | grep -qv "sequence=2" \
  && ok "S6/U2: the host fell back to the cached last good bundle (v2, sequence 2), and loaded nothing else" \
  || bad "S6/U2: no fall-back: $(grep -E 'KeliverHost|codeLoad' "$C" | tail -4 | tr '\n' ' ')"
reads S6-pin Warehouse && ok "S6/U2: the screen still reads 'Warehouse' (v2, from the cache)" \
  || bad "S6/U2: 'Warehouse' is not on the screen: $(tr '\n' '|' < "$EV/S6-pin.ocr.txt" | cut -c1-200)"
cp "$W3/index.good" "$W3/site/bundles/index.json"

# S8 (W4.2): the floor rose when v2 ran; the server then offers v1 again, as the
# newest entry (a replayed or rolled-back index). v1's manifest is validly
# signed, but for sequence 1: the host refuses it on the network and does not
# run it; it falls back to the cached v2 (W5).
grep -q "rollback floor raised: 1 -> 2" "$EV/S4-static.console.txt" \
  && ok "S8: running v2 raised the host's rollback floor from 1 to 2" || bad "S8: no floor raise logged at v2"
python3 -c 'import json,sys; p=sys.argv[1]; i=json.load(open(p)); v1=[e for e in i["entries"] if e["version"]==1][0]; i["entries"].append(dict(v1, sequence=max(e["sequence"] for e in i["entries"])+1)); json.dump(i, open(p,"w"))' \
  "$W3/site/bundles/index.json"
launch S8-rollback
C="$EV/S8-rollback.console.txt"
grep -q "rollback refused: sequence 1 is below 2" "$C" && ok "S8: v1 offered again after v2: refused on its signed sequence (1 < 2)" \
  || bad "S8: no rollback refusal: $(grep -E 'codeLoad|loading' "$C" | head -3 | tr '\n' ' ')"
grep -q "falling back to the cached last good bundle" "$C" && grep -q "codeLoadSuccess modules=[0-9]* sequence=2" "$C" \
  && ! grep "codeLoadSuccess" "$C" | grep -qv "sequence=2" \
  && ok "S8: v1 did not run; the host fell back to the cached v2 (sequence 2)" \
  || bad "S8: $(grep -E 'KeliverHost|codeLoad' "$C" | tail -4 | tr '\n' ' ')"
cp "$W3/index.good" "$W3/site/bundles/index.json"

# S10 (W4.3): rollback done right. v1's code goes out AGAIN as a new sequence:
# keliver-publish --republish 1 copies v1's modules, the signing block's
# keliverResign signs the copy for sequence 3 (the key found through the store,
# as for a build; nothing compiled), and it is published as v3. The host, whose
# floor is 2, runs it: the screen reads "Depot" again, and the floor rises to 3.
w3_publish republish-v1 --republish 1 && grep -q "republished v1 as v3 (sequence 3" "$EV/w3-publish-republish-v1.log" \
  && ok "S10: $(grep -o 'republished v1 as v3 ([^)]*)' "$EV/w3-publish-republish-v1.log"), by the app's keliverResign" \
  || { bad "S10: republish v1"; tail -20 "$EV/w3-publish-republish-v1.log"; }
grep -q "Task :compile" "$EV/w3-publish-republish-v1.log" && bad "S10: the republish compiled" || ok "S10: the republish compiled nothing"
cp "$W3/site/bundles/index.json" "$EV/w3-index-v3.json" 2>/dev/null
w3_signed_sequence 3 && ok "W4.3: v3's manifest is signed for sequence 3" || bad "W4.3: v3's manifest does not carry signed sequence 3"
w3_same_modules 1 3 && ok "S10: v3's modules are v1's, byte for byte" || bad "S10: v3's modules differ from v1's"
launch S10-republish
C="$EV/S10-republish.console.txt"
grep -q "loading https://localhost:8443/bundles/v3/manifest.zipline.json (index sequence 3" "$C" && grep -q "codeLoadSuccess" "$C" \
  && ok "S10: the host that had run v2 (floor 2) loaded the republished v1 as v3" \
  || bad "S10: v3 did not load: $(grep -E 'codeLoad|loading|refused' "$C" | head -3 | tr '\n' ' ')"
grep -q "rollback floor raised: 2 -> 3" "$C" && ok "S10: running v3 raised the floor from 2 to 3" || bad "S10: no floor raise logged at v3"
reads S10-republish Depot && ok "S10: v1's code is back: the screen reads 'Depot'" || bad "S10: 'Depot' is not on the screen"

# S11 (W4.4): channels. An edit published to beta only ("Backroom", v4 at
# sequence 4) does not reach this host, which was built for stable: it keeps
# running v3.
w3_title Warehouse Backroom && w3_publish v4-beta --channel beta && grep -q "published v4 (sequence 4, channel beta" "$EV/w3-publish-v4-beta.log" \
  && ok "S11: keliver-publish wrote v4 at sequence 4 on channel beta" || { bad "S11: publish v4 to beta"; tail -20 "$EV/w3-publish-v4-beta.log"; }
launch S11-beta
C="$EV/S11-beta.console.txt"
grep -q "loading https://localhost:8443/bundles/v3/manifest.zipline.json (index sequence 3, channel stable" "$C" && grep -q "codeLoadSuccess" "$C" \
  && ! grep -q "bundles/v4/" "$C" && ok "S11: the stable host passed over beta's v4 and loaded v3" \
  || bad "S11: $(grep -E 'loading|codeLoad' "$C" | head -3 | tr '\n' ' ')"
reads S11-beta Depot && ok "S11: the stable host still reads 'Depot'" || bad "S11: 'Depot' is not on the screen"

# S12 (W4.4): promotion. --promote 4 --channel stable adds a second index entry
# for the same v4 (nothing built or signed, no new v<N>/); the host now loads it,
# and its floor rises to 4.
( cd "$APP" && "$PUBLISH" . --out "$W3/site" --public-key-file "$STORE/keys/ed25519.pub" --promote 4 --channel stable ) > "$EV/w3-promote-4.log" 2>&1 \
  && grep -q "promoted sequence 4 (v4) from beta to stable" "$EV/w3-promote-4.log" && ! grep -q "Task :" "$EV/w3-promote-4.log" \
  && ok "S12: v4 promoted from beta to stable; nothing built or signed" || { bad "S12: promote"; tail -20 "$EV/w3-promote-4.log"; }
cp "$W3/site/bundles/index.json" "$EV/w3-index-promoted.json" 2>/dev/null
[ "$(ls "$W3/site/bundles" | grep -c '^v')" = 4 ] && ok "S12: the promotion wrote no new v<N>/" || bad "S12: the bundle directories changed"
launch S12-promoted
C="$EV/S12-promoted.console.txt"
grep -q "loading https://localhost:8443/bundles/v4/manifest.zipline.json (index sequence 4, channel stable" "$C" && grep -q "codeLoadSuccess" "$C" \
  && ok "S12: the stable host loaded the promoted v4" || bad "S12: v4 did not load: $(grep -E 'loading|codeLoad|refused' "$C" | head -3 | tr '\n' ' ')"
grep -q "rollback floor raised: 3 -> 4" "$C" && ok "S12: running v4 raised the floor from 3 to 4" || bad "S12: no floor raise logged at v4"
reads S12-promoted Backroom && ok "S12: after the promotion the screen reads 'Backroom'" || bad "S12: 'Backroom' is not on the screen"
# S13 (W4.5): a staged rollout at 0%. v5 ("Loft") is in the index on stable,
# but no install's bucket is below 0, so this host stays on v4.
w3_title Backroom Loft && w3_publish v5-rollout0 --rollout 0 && grep -q "published v5 (sequence 5, channel stable" "$EV/w3-publish-v5-rollout0.log" \
  && python3 -c 'import json,sys; e=json.load(open(sys.argv[1]))["entries"][-1]; sys.exit(0 if e["sequence"]==5 and e["constraints"]=={"rollout":0} else 1)' "$W3/site/bundles/index.json" \
  && ok "S13: keliver-publish wrote v5 at sequence 5 with constraints.rollout 0" || { bad "S13: publish v5 at rollout 0"; tail -20 "$EV/w3-publish-v5-rollout0.log"; }
launch rollout0
C="$EV/rollout0.console.txt"
grep -q "loading https://localhost:8443/bundles/v4/manifest.zipline.json (index sequence 4," "$C" && grep -q "codeLoadSuccess" "$C" \
  && ok "S13: at rollout 0 the host passed over v5 and loaded v4" || bad "S13: at rollout 0 the host passed over v5 and loaded v4: $(grep -E 'loading|codeLoad|refused|index' "$C" | head -3 | tr '\n' ' ')"
grep -q "bundles/v5/" "$C" && bad "S13: the host asked for v5" || ok "S13: the host never asked for v5"
reads rollout0 Backroom && ok "S13: the screen still reads Backroom" || bad "S13: the screen still reads Backroom: 'Backroom' is not on the screen"

# S14 (W4.5): the rollout raised to 100% (an index edit; no key, no build): v5 reaches the host.
( cd "$APP" && "$PUBLISH" . --out "$W3/site" --set-rollout 5 --rollout 100 ) > "$EV/w3-rollout-100.log" 2>&1 \
  && grep -q "rollout 0 -> 100%" "$EV/w3-rollout-100.log" && ok "S14: --set-rollout 5 --rollout 100 (no key)" \
  || { bad "S14: set the rollout"; tail -10 "$EV/w3-rollout-100.log"; }
launch rollout100
C="$EV/rollout100.console.txt"
grep -q "loading https://localhost:8443/bundles/v5/manifest.zipline.json (index sequence 5," "$C" && grep -q "codeLoadSuccess" "$C" \
  && ok "S14: at rollout 100 the host loaded v5" || bad "S14: at rollout 100 the host loaded v5: $(grep -E 'loading|codeLoad|refused|index' "$C" | head -3 | tr '\n' ' ')"
grep -q "rollback floor raised: 4 -> 5" "$C" && ok "S14: running v5 raised the floor from 4 to 5" || bad "S14: no floor raise logged at v5"
reads rollout100 Loft && ok "S14: the screen reads Loft" || bad "S14: the screen reads Loft: 'Loft' is not on the screen"

# S14b (W4.5): halting the rollout (back to 0) stops new installs, but this host
# has run v5: its floor is 5, and a rollout gates only sequences above the floor.
( cd "$APP" && "$PUBLISH" . --out "$W3/site" --set-rollout 5 --rollout 0 ) > "$EV/w3-rollout-halt.log" 2>&1 \
  && grep -q "rollout 100 -> 0% (halted" "$EV/w3-rollout-halt.log" && ok "S14b: the rollout of v5 halted (0)" \
  || { bad "S14b: halt the rollout"; tail -10 "$EV/w3-rollout-halt.log"; }
launch rollout-halted
C="$EV/rollout-halted.console.txt"
grep -q "loading https://localhost:8443/bundles/v5/manifest.zipline.json (index sequence 5," "$C" && grep -q "codeLoadSuccess" "$C" \
  && ok "S14b: halted, the host that ran v5 still loads it" || bad "S14b: halted, the host that ran v5 still loads it: $(grep -E 'loading|codeLoad|refused|index' "$C" | head -3 | tr '\n' ' ')"
grep -q "refused" "$C" && bad "S14b: something was refused" || ok "S14b: nothing was refused"
reads rollout-halted Loft && ok "S14b: the screen still reads Loft" || bad "S14b: the screen still reads Loft: 'Loft' is not on the screen"

# S15 (W4.5): a host-version gate. v6 ("Attic") needs host version 999; this host
# is version 1 (versionCode / CFBundleVersion), so it skips v6 and stays on v5.
w3_title Loft Attic && w3_publish v6-gated --min-host-version 999 && grep -q "published v6 (sequence 6, channel stable" "$EV/w3-publish-v6-gated.log" \
  && python3 -c 'import json,sys; e=json.load(open(sys.argv[1]))["entries"][-1]; sys.exit(0 if e["sequence"]==6 and e["constraints"]=={"minHostVersion":999} else 1)' "$W3/site/bundles/index.json" \
  && ok "S15: keliver-publish wrote v6 with constraints.minHostVersion 999" || { bad "S15: publish v6"; tail -20 "$EV/w3-publish-v6-gated.log"; }
cp "$W3/site/bundles/index.json" "$EV/w3-index-w45.json" 2>/dev/null
launch gated
C="$EV/gated.console.txt"
grep -q "loading https://localhost:8443/bundles/v5/manifest.zipline.json (index sequence 5," "$C" && grep -q "codeLoadSuccess" "$C" \
  && ok "S15: the host (version 1) skipped v6 and loaded v5" || bad "S15: the host (version 1) skipped v6 and loaded v5: $(grep -E 'loading|codeLoad|refused|index' "$C" | head -3 | tr '\n' ' ')"
grep -q "host version 1," "$C" && ok "S15: the host checked the entries against its version, 1" || bad "S15: the lookup did not name host version 1"
grep -q "bundles/v6/" "$C" && bad "S15: the host asked for v6" || ok "S15: the host never asked for v6"
reads gated Loft && ok "S15: the screen still reads Loft" || bad "S15: the screen still reads Loft: 'Loft' is not on the screen"

# The newest bundle this host has run, for S7, S9 and S9b.
LAST_V=5; LAST_TITLE=Loft

kill "$SPID" 2>/dev/null; wait "$SPID" 2>/dev/null; SPID=""
curl -sf --cacert "$W3/tls/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json \
  && bad "S7: the static server is still answering" || ok "S7: no bundle server is answering"
launch S7-static
C="$EV/S7-static.console.txt"
grep -q "lookup failed; starting from the cached bundle (last loaded from https://localhost:8443/bundles/v$LAST_V/" "$C" \
  && grep -q "codeLoadSuccess" "$C" && ok "S7: offline, the host started from its cached v$LAST_V" || bad "S7: no start from the cache"
reads S7-static "$LAST_TITLE" && ok "S7: the screen reads '$LAST_TITLE' offline" || bad "S7: '$LAST_TITLE' is not on the screen"

# S9 (W4.2): the floor holds on the cache start too. Raise the stored floor above
# the cached newest bundle (as a host that had run a newer bundle would have it), then start
# offline: the cached manifest is refused and nothing runs.
FLOOR_KEY="keliver.highestSequence-keliver-production-$(printf '%s' "$PUB" | tr 'A-F' 'a-f' | cut -c1-16)"
xcrun simctl terminate "$UDID" "$BID" >/dev/null 2>&1
# The app's own domain lives in its data container: `defaults write <bundle id>`
# from simctl spawn writes the simulator user's domain instead, which the app
# never reads (that is how S9 first failed, in run 37742935620). Writing by path
# through the simulator's defaults keeps cfprefsd coherent with what the app sees.
PREFS="$(xcrun simctl get_app_container "$UDID" "$BID" data)/Library/Preferences/$BID"
xcrun simctl spawn "$UDID" defaults write "$PREFS" "$FLOOR_KEY" -int 9
[ "$(xcrun simctl spawn "$UDID" defaults read "$PREFS" "$FLOOR_KEY" 2>/dev/null)" = 9 ] \
  && ok "S9: the host's stored rollback floor is now 9 (in its container's preferences)" || bad "S9: could not set the floor ($PREFS $FLOOR_KEY)"
launch S9-cache-floor
C="$EV/S9-cache-floor.console.txt"
grep -q "cached bundle refused: rollback refused: sequence $LAST_V is below 9" "$C" && ok "S9: offline, the cached v$LAST_V was refused below the floor" \
  || bad "S9: no cache refusal: $(grep -E 'codeLoad|cached|lookup' "$C" | head -3 | tr '\n' ' ')"
grep -q "codeLoadSuccess" "$C" && bad "S9: something loaded" || ok "S9: no code loaded"

# S9b (W4.2): the same, with the server UP but failing the lookup on purpose (no
# index, no bundles/latest). The host takes the cache path; the cache is refused,
# so Zipline fetches the last-good manifest from the network, and that fetch is
# held to the floor too: the newest (sequence $LAST_V) is refused against 9.
mv "$W3/site/bundles/index.json" "$W3/index.hidden"
python3 "$HERE/w3/static_https.py" "$W3/site" 8443 "$W3/tls/server.pem" "$W3/tls/server.key" "$EV/w3-server-s9b.log" &
SPID=$!
for _ in $(seq 1 30); do curl -s --cacert "$W3/tls/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/v$LAST_V/manifest.zipline.json && break; sleep 1; done
launch S9b-fallback-floor
C="$EV/S9b-fallback-floor.console.txt"
grep -q "lookup failed; starting from the cached bundle" "$C" && grep -q "rollback refused: sequence $LAST_V is below 9" "$C" \
  && ok "S9b: lookup failed with the server up; the cache and then the network fetch of v$LAST_V were both held to the floor" \
  || bad "S9b: no refusal on the network fallback: $(grep -E 'codeLoad|cached|lookup|refused' "$C" | head -4 | tr '\n' ' ')"
grep -q "^GET /bundles/v$LAST_V/manifest.zipline.json" "$EV/w3-server-s9b.log" \
  && ok "S9b: the host did fetch v$LAST_V's manifest from the network (the guarded path ran)" || bad "S9b: no network fetch of v$LAST_V's manifest"
grep -q "codeLoadSuccess" "$C" && bad "S9b: something loaded" || ok "S9b: no code loaded"
kill "$SPID" 2>/dev/null; wait "$SPID" 2>/dev/null; SPID=""
mv "$W3/index.hidden" "$W3/site/bundles/index.json"
mkdir -p "$EV/w3-site" && cp "$W3/site/bundles/index.json" "$EV/w3-site/index.json"
