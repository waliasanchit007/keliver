# Sourced by ci/ios.sh after the W3/W4 static route: W2 on iOS, the Keliver host
# framework embedded in an "existing" SwiftUI app (reference/embed/ios). Uses
# ios.sh's APP, PUB, FPUB, UDID, BID, WORK, EV, HERE, REPO, IOS_SCAFFOLD, WHICH,
# ok/bad/launch/reads, and ios-static.sh's W3, w3_publish, w3_signed_sequence.
#   I1  one screen holds the native views AND the guest's screen
#   I2  that screen is a signed load from the static server (this app's key)
#   I4  the host's rollback floor is stored, in the existing app's own defaults
#   I5  trusting another app's key: the host looks v7 up, verifies with that
#       key, refuses the load and says so; the native views stay
# (There is no tap driver for the simulator here, so native navigation, I3 in
# the plan, is not exercised on iOS; Android's X4 covers it.)
echo "--- W2: the host framework embedded in an existing SwiftUI app"
EMB="$WORK/embed-ios"; rm -rf "$EMB"; cp -R "$REPO/reference/embed/ios" "$EMB"
( cd "$APP" && "$IOS_SCAFFOLD" --embed --into "$EMB" --bundle-server https://localhost:8443 ) > "$EV/w2-embed-scaffold.log" 2>&1 \
  && ok "W2: keliver-new-ios-host.sh --embed ($WHICH) wrote keliver-host-ios/ into the existing app" \
  || { bad "W2: --embed failed"; cat "$EV/w2-embed-scaffold.log"; }
cp "$EMB/keliver-host-ios/KeliverScreen.swift" "$EMB/iosApp/" # EMBED.md step 4
w2_build(){  # $1 = log: the existing app, through its own Xcode build phase
  ( cd "$EMB" && xcodebuild -project ExistingApp.xcodeproj -scheme iosApp -configuration Debug \
      -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath "$WORK/dd-embed" \
      CODE_SIGNING_ALLOWED=NO build ) > "$1" 2>&1
}
w2_build "$EV/w2-embed-xcodebuild.log" && ok "W2: the existing SwiftUI app built, its build phase compiling the Kotlin framework from Maven Central" \
  || { bad "W2: the existing app did not build"; grep -E 'error:|^e: ' "$EV/w2-embed-xcodebuild.log" | head -10; }
EAPP="$(find "$WORK/dd-embed/Build/Products" -maxdepth 2 -name 'ExistingApp.app' | head -1)"

# A fresh, unconstrained bundle (the W4 steps left rollouts and gates on the
# newest entries); the screen text is still "Attic".
w3_publish v7-embed && grep -q "published v7 (sequence 7, channel stable" "$EV/w3-publish-v7-embed.log" && w3_signed_sequence 7 \
  && ok "W2: keliver-publish wrote v7 (\"Attic\") for the embedded host" || { bad "W2: publish v7"; tail -20 "$EV/w3-publish-v7-embed.log"; }
python3 "$HERE/w3/static_https.py" "$W3/site" 8443 "$W3/tls/server.pem" "$W3/tls/server.key" "$EV/w2-server.log" &
SPID=$!
for _ in $(seq 1 30); do curl -sf --cacert "$W3/tls/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json && break; sleep 1; done

HOST_BID="$BID"; BID=com.example.existingios
xcrun simctl install "$UDID" "$EAPP" && ok "W2: installed the existing app ($BID)" || bad "W2: the existing app did not install"
launch W2-embed
C="$EV/W2-embed.console.txt"
# I1: native views and the guest's screen in one screenshot.
reads W2-embed "Native header" && reads W2-embed "Native details" && reads W2-embed Attic \
  && ok "I1: the native header and link and the guest's screen ('Attic') on one screen" \
  || bad "I1: $(tr '\n' '|' < "$EV/W2-embed.ocr.txt" | cut -c1-200)"
# I2: a signed load from the static server.
grep -q "KeliverHost: verifying manifests with portal-ed25519 ${PUB:0:8}" "$C" \
  && grep -q "loading https://localhost:8443/bundles/v7/manifest.zipline.json (index sequence 7," "$C" && grep -q "codeLoadSuccess" "$C" \
  && ok "I2: the embedded host verified with this app's key and loaded v7 from the static server" \
  || bad "I2: $(grep -E 'KeliverHost|codeLoad' "$C" | head -3 | tr '\n' ' ')"
# I4: the rollback floor, in the existing app's own defaults.
EPREFS="$(xcrun simctl get_app_container "$UDID" "$BID" data)/Library/Preferences/$BID"
EFLOOR="keliver.highestSequence-keliver-production-$(printf '%s' "$PUB" | tr 'A-F' 'a-f' | cut -c1-16)"
[ "$(xcrun simctl spawn "$UDID" defaults read "$EPREFS" "$EFLOOR" 2>/dev/null)" = 7 ] \
  && ok "I4: the embedded host stored its rollback floor (7) in the existing app's defaults" || bad "I4: no floor 7 at $EPREFS $EFLOOR"

# I5: the same app trusting another app's key: nothing loads; the native views stay.
HC="$(find "$EMB/keliver-host-ios/src/iosMain/kotlin" -name HostConfig.kt | head -1)"
cp "$HC" "$WORK/embed-hostconfig.saved"
sed -i '' "s/PORTAL_PUBLIC_KEY_HEX: String = \"[0-9a-f]*\"/PORTAL_PUBLIC_KEY_HEX: String = \"$(printf '%s' "$FPUB" | tr 'A-F' 'a-f')\"/" "$HC"
xcrun simctl uninstall "$UDID" "$BID" > /dev/null 2>&1
w2_build "$EV/w2-embed-foreign-xcodebuild.log" && xcrun simctl install "$UDID" "$EAPP" \
  && ok "I5: rebuilt and reinstalled trusting another app's key" || bad "I5: the foreign-key build failed"
launch W2-embed-foreign
C="$EV/W2-embed-foreign.console.txt"
grep -q "codeLoadSuccess" "$C" && bad "I5: code loaded under a key that did not sign it" || ok "I5: nothing loaded under another key"
# Not just "nothing loaded" (a dead server would pass that): the host trusted the
# other key, looked v7 up, and the load failed verification.
grep -q "KeliverHost: verifying manifests with portal-ed25519 $(printf '%s' "$FPUB" | tr 'A-F' 'a-f' | cut -c1-8)" "$C" \
  && grep -q "loading https://localhost:8443/bundles/v7/manifest.zipline.json (index sequence 7," "$C" && grep -q "codeLoadFailed" "$C" \
  && ok "I5: the host trusted the other key, looked v7 up and refused it (codeLoadFailed)" \
  || bad "I5: $(grep -E 'KeliverHost|codeLoad' "$C" | head -4 | tr '\n' ' ')"
reads W2-embed-foreign "Native header" && ! reads W2-embed-foreign Attic && reads W2-embed-foreign "Bundle did not load" \
  && ok "I5: the native header is still shown, and the Keliver view says the bundle did not load" || bad "I5: $(tr '\n' '|' < "$EV/W2-embed-foreign.ocr.txt" | cut -c1-200)"
cp "$WORK/embed-hostconfig.saved" "$HC"
xcrun simctl uninstall "$UDID" "$BID" > /dev/null 2>&1
BID="$HOST_BID"
kill "$SPID" 2>/dev/null; wait "$SPID" 2>/dev/null; SPID=""
