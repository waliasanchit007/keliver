#!/usr/bin/env bash
#
# Before the emulator: build the reference app from the PUBLIC tools release,
# check ingest, publish a signed v1, and build a production host that embeds
# THIS app's public key.
#
#   ci/prepare.sh <work-dir> <evidence-dir> [tools.zip]
#
# Publishing is wired, and the production host SCAFFOLDED into the app, by the
# published tools zip's own bin/keliver-new-publish-target.sh and
# bin/keliver-new-production-host.sh (tools 0.3.7, with the U31 signing block);
# the host is built by the app's own Gradle from Maven Central. It used to be
# portal-device-android compiled from Keliver's source at the release commit,
# then this repository's scripts/ copies; neither is used now.
#
# Isolation: every JVM here runs with user.home = <work-dir>/home, so the store,
# its keys and the relay's state are all inside <work-dir>. The repository's own
# isolation guard checks the EFFECTIVE user.home and store before each relay
# start and refuses otherwise. The keys are disposable and app-owned; nothing in
# this harness reads, prints or copies a private key — the Zipline compile task's
# signing block in the app's build.gradle is the only reader, on each publish.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO="$(cd "$HERE/../../.." && pwd -P)"
WORK="${1:?usage: $0 <work-dir> <evidence-dir> [tools.zip]}"
EV="${2:?}"
ZIP="${3:-}"
mkdir -p "$WORK/home" "$EV"
WORK="$(cd "$WORK" && pwd -P)"; EV="$(cd "$EV" && pwd -P)"

pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); echo "PASS $1" >> "$EV/prepare.results"; }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); echo "FAIL $1" >> "$EV/prepare.results"; }
sha256(){ if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
: > "$EV/prepare.results"

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$WORK/home"
unset PORTAL_STORE KELIVER_USE_MAVEN_LOCAL

# --- 1. the app, from the public release ------------------------------------
echo "==> bootstrap"
if "$HERE/../bootstrap.sh" "$WORK/boot" $ZIP > "$EV/bootstrap.log" 2>&1; then
  ok "bootstrap: the app was scaffolded from the published tools zip ($(basename "$(dirname "$(ls -d "$WORK"/boot/tools/keliver-portal-tools-*/bin)")"))"
else
  bad "bootstrap failed"; tail -30 "$EV/bootstrap.log"; exit 1
fi
tail -8 "$EV/bootstrap.log"
APP="$WORK/boot/inventory"
export KP="$(ls -d "$WORK"/boot/tools/keliver-portal-tools-*/bin)"
cp "$APP/hand-edits.diff" "$EV/hand-edits.diff"
echo "APP=$APP" > "$WORK/env"; echo "KP=$KP" >> "$WORK/env"

( cd "$APP" && ./gradlew compileKotlinJs --console=plain ) > "$EV/compile.log" 2>&1 \
  && ok "compile: ./gradlew compileKotlinJs against Maven Central 0.3.3" \
  || { bad "compile failed"; tail -30 "$EV/compile.log"; exit 1; }

# publishTask/publishOutput and the signing block. The bootstrap no longer
# overlays a hand-written block; this is the scaffolder an adopter would run.
( cd "$APP" && "$KP/keliver-new-publish-target.sh" ) > "$EV/publish-target.log" 2>&1 \
  && ok "publishing wired by the zip's bin/keliver-new-publish-target.sh" \
  || { bad "keliver-new-publish-target.sh failed"; cat "$EV/publish-target.log"; exit 1; }
cp "$APP/build.gradle" "$EV/app-build.gradle"
# Committed, as an adopter would: D2 asserts that a layout edit changes exactly
# one tracked file.
( cd "$APP" && git add build.gradle keliver.portal.json \
    && git -c user.name=prepare -c user.email=prepare@invalid commit -qm "keliver-new-publish-target.sh" ) \
  || { bad "could not commit the publish wiring"; exit 1; }

# --- 2. a relay for this app, isolated ----------------------------------------
# shellcheck source=/dev/null
. "$REPO/scripts/keliver-test-isolation-guard.sh"
keliver_require_isolated_store "$WORK" "$APP" > "$EV/guard-1.log" 2>&1 \
  && ok "isolation guard: user.home and the resolved store are inside the work dir" \
  || { bad "isolation guard refused"; cat "$EV/guard-1.log"; exit 1; }
cat "$EV/guard-1.log"

portal_up(){  # $1 = app dir, $2 = log
  ( cd "$1" && nohup "$KP/keliver-portal" --no-editor-build . > "$2" 2>&1 & )
  for _ in $(seq 1 60); do curl -sf -m 2 -o /dev/null http://localhost:8077/devstate && return 0; sleep 3; done
  return 1
}
portal_down(){ ( cd "$1" && "$KP/keliver-portal" stop . >/dev/null 2>&1 ); sleep 2; }

portal_up "$APP" "$EV/relay-prepare.log" && ok "the relay for this app answered on :8077" \
  || { bad "the relay never answered"; tail -30 "$EV/relay-prepare.log"; exit 1; }
STORE="$(cat "$APP/.gradle/keliver-store-path")"
echo "STORE=$STORE" >> "$WORK/env"
case "$STORE" in "$WORK"/*) ok "the relay's store is inside the work dir: $STORE" ;;
                 *) bad "the relay's store is OUTSIDE the work dir: $STORE"; exit 1 ;; esac
[ -f "$STORE/keys/ed25519.pub" ] && [ -f "$STORE/keys/ed25519.priv" ] \
  && ok "the relay generated this app's disposable signing identity" \
  || bad "no signing identity in the store"
PUB="$(tr -d ' \n' < "$STORE/keys/ed25519.pub")"
echo "$PUB" > "$EV/app-public-key.hex"
echo "    app public key: ${PUB:0:16}…"
ls -l "$STORE/keys" | awk 'NR>1 {print "    key file mode: " $1 "  " $NF}' | tee "$EV/key-modes.txt"

# --- 3. D1: ingest with zero RawCode -----------------------------------------
for s in inventory item; do
  curl -sf "http://localhost:8077/doc?project=default&screen=$s" > "$EV/doc-$s.json"
  n="$(grep -o 'DocNode.RawCode' "$EV/doc-$s.json" | wc -l | tr -d ' ')"
  w="$(grep -o 'DocNode.Widget' "$EV/doc-$s.json" | wc -l | tr -d ' ')"
  [ -s "$EV/doc-$s.json" ] && [ "$n" = 0 ] && [ "$w" -gt 5 ] \
    && ok "D1 $s: $w widget nodes, 0 RawCode" || bad "D1 $s: $n RawCode (of $w widgets)"
done

# --- 4. P2 precondition: publish v1, signed with this app's key --------------
curl -s -m 600 -X POST http://localhost:8077/publish > "$EV/publish-v1.log" 2>&1
grep -q 'publish OK: bundle v1' "$EV/publish-v1.log" && ok "publish v1: $(grep 'publish OK' "$EV/publish-v1.log")" \
  || { bad "publish v1 failed"; tail -20 "$EV/publish-v1.log"; }
M1="$STORE/bundles/v1/manifest.zipline.json"
cp "$M1" "$EV/manifest-v1.zipline.json"
python3 - "$M1" > "$EV/manifest-v1.summary" <<'PY'
import json, sys, hashlib
p = sys.argv[1]; raw = open(p, 'rb').read(); m = json.loads(raw)
sig = m.get('unsigned', {}).get('signatures', {})
print('manifest sha256', hashlib.sha256(raw).hexdigest())
print('signed by', sorted(sig))
print('modules', len(m['modules']))
PY
cat "$EV/manifest-v1.summary"
grep -q "signed by \['portal-ed25519'\]" "$EV/manifest-v1.summary" \
  && ok "v1's manifest carries a portal-ed25519 signature" || bad "v1's manifest is not signed"
portal_down "$APP"

# --- 5. P1: the production host, scaffolded, with THIS app's key ------------
# keliver-new-production-host.sh reads the PUBLIC key from this app's store
# (resolved under this run's user.home) and writes host-android/, a standalone
# build on Maven Central only. 10.0.2.2 is how the emulator reaches the relay.
PROD_ID=inventory.host
# Which scaffolder writes the host. By default the published zip's own bin/
# copy. KELIVER_SCAFFOLD_FROM=repo selects this repository's scripts/ instead:
# the W3 workflows set it, because W3's index-reading host templates exist only
# here until a release ships them; switch it off once one does.
case "${KELIVER_SCAFFOLD_FROM:-zip}" in
  zip)  PROD_SCAFFOLD="$KP/keliver-new-production-host.sh"; PROD_WHICH="the zip's bin/" ;;
  repo) PROD_SCAFFOLD="$REPO/scripts/keliver-new-production-host.sh"; PROD_WHICH="this repository's scripts/ (W3, unreleased)" ;;
  *)    bad "KELIVER_SCAFFOLD_FROM must be zip or repo, not ${KELIVER_SCAFFOLD_FROM}"; exit 1 ;;
esac
( cd "$APP" && "$PROD_SCAFFOLD" --bundle-server http://10.0.2.2:8077 \
    --application-id "$PROD_ID" ) > "$EV/host-scaffold.log" 2>&1 \
  && ok "P1: keliver-new-production-host.sh ($PROD_WHICH) scaffolded host-android/" \
  || { bad "P1: the production host was not scaffolded"; cat "$EV/host-scaffold.log"; exit 1; }
sed 's/^/    /' "$EV/host-scaffold.log"
printf 'sdk.dir=%s\n' "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}" > "$APP/host-android/local.properties"
( cd "$APP" && ./gradlew --console=plain -p host-android assembleDebug ) > "$EV/host-build.log" 2>&1 \
  && ok "P1: the scaffolded host built from Maven Central" \
  || { bad "P1: the scaffolded host did not build"; tail -40 "$EV/host-build.log"; exit 1; }
# Where every Keliver artifact in that build came from.
( cd "$APP" && ./gradlew --console=plain -q -p host-android dependencies --configuration debugRuntimeClasspath ) \
  > "$EV/host-dependencies.txt" 2>&1
grep -oE 'dev\.keliver:[a-z0-9-]+:[0-9.]+' "$EV/host-dependencies.txt" | sort -u > "$EV/host-keliver-artifacts.txt"
echo "    $(wc -l < "$EV/host-keliver-artifacts.txt" | tr -d ' ') dev.keliver artifacts, all $(cut -d: -f3 "$EV/host-keliver-artifacts.txt" | sort -u | tr '\n' ' ')"
APK="$(find "$APP/host-android/build/outputs/apk/debug" -name '*.apk' | head -1)"
cp "$APK" "$EV/production-host.apk"
sha256 "$EV/production-host.apk" | tee "$EV/production-host.apk.sha256"
EMB="$(unzip -p "$EV/production-host.apk" assets/keliver/portal_ed25519.pub 2>/dev/null | tr -d ' \n')"
[ -n "$EMB" ] && [ "$EMB" = "$PUB" ] \
  && ok "P1: the host embeds assets/keliver/portal_ed25519.pub, equal to this app's public key" \
  || bad "P1: the embedded key (${EMB:0:16}) is not this app's (${PUB:0:16})"
echo "PROD_ID=$PROD_ID" >> "$WORK/env"
echo "APK=$EV/production-host.apk" >> "$WORK/env"

# --- 6. W3 inputs: keliver-publish, a throwaway CA, the host for HTTPS -------
# The static route (ci/w3/android-static.sh, run by device.sh) needs no relay:
# keliver-publish from this checkout (no published tools bundle has it yet), a
# CA and server certificate that only the emulator will trust, and the same
# production host built for https://10.0.2.2:8443 (only the server differs).
W3="$WORK/w3"; mkdir -p "$W3"
# The zip's own keliver-publish and publish-target scaffolder when it ships them
# (0.3.8 on) and the hosts come from the zip; otherwise this checkout's.
if [ "${KELIVER_SCAFFOLD_FROM:-zip}" = zip ] && [ -x "$KP/keliver-publish" ]; then
  W3_PUBLISH="$KP/keliver-publish"; W3_PUBLISH_TARGET="$KP/keliver-new-publish-target.sh"
  ok "W3: keliver-publish and keliver-new-publish-target.sh from the tools zip's bin/"
else
  W3_PUBLISH="$(bash "$HERE/w3/tools.sh" "$W3/tools" 2> "$EV/w3-tools.log" | tail -1)"; W3_PUBLISH_TARGET="$REPO/scripts/keliver-new-publish-target.sh"
  [ -n "$W3_PUBLISH" ] && [ -x "$W3_PUBLISH" ] && ok "W3: keliver-publish built from this checkout, in the tools layout" \
    || { bad "W3: keliver-publish was not built"; tail -20 "$EV/w3-tools.log"; }
fi
bash "$HERE/w3/tls.sh" "$W3/tls" > "$EV/w3-tls.txt" 2>&1 && ok "W3: a throwaway CA and a server certificate for 10.0.2.2" \
  || { bad "W3: no certificates"; cat "$EV/w3-tls.txt"; }
( cd "$APP" && ./gradlew --console=plain -p host-android assembleDebug -Pkeliver.bundleServer=https://10.0.2.2:8443 ) \
  > "$EV/w3-host-build.log" 2>&1 \
  && cp "$(find "$APP/host-android/build/outputs/apk/debug" -name '*.apk' | head -1)" "$EV/production-host-static.apk" \
  && ok "W3: the production host, built for https://10.0.2.2:8443" || { bad "W3: the static host did not build"; tail -30 "$EV/w3-host-build.log"; }
{ echo "W3_PUBLISH=$W3_PUBLISH"; echo "W3_PUBLISH_TARGET=$W3_PUBLISH_TARGET"; echo "W3_TLS=$W3/tls"; echo "W3_APK=$EV/production-host-static.apk"; } >> "$WORK/env"
# W5 (U1): the same host, built to check for updates when it comes back to the
# foreground (keliver.updates=on-resume; only that build setting differs).
( cd "$APP" && ./gradlew --console=plain -p host-android assembleDebug -Pkeliver.bundleServer=https://10.0.2.2:8443 -Pkeliver.updates=on-resume ) \
  > "$EV/w5-host-build.log" 2>&1 \
  && cp "$(find "$APP/host-android/build/outputs/apk/debug" -name '*.apk' | head -1)" "$EV/production-host-onresume.apk" \
  && ok "W5: the production host, built with keliver.updates=on-resume" || { bad "W5: the on-resume host did not build"; tail -30 "$EV/w5-host-build.log"; }
echo "W5_APK=$EV/production-host-onresume.apk" >> "$WORK/env"

# --- 7. W2: the host embedded in an "existing" app (reference/embed/android) ---
# The same scaffolder, with --embed, writes keliver-host/ into a plain View-based
# app whose own files carry only the documented edits (KELIVER EMBED). Built
# for W3's static HTTPS server; and once more trusting a key that is not this
# app's, for the refusal check.
EMB_APP="$WORK/embed"; rm -rf "$EMB_APP"; cp -R "$REPO/reference/embed/android" "$EMB_APP"
cp "$APP/gradlew" "$EMB_APP/" && cp -R "$APP/gradle" "$EMB_APP/"
printf 'sdk.dir=%s\n' "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}" > "$EMB_APP/local.properties"
( cd "$APP" && "$PROD_SCAFFOLD" --embed --into "$EMB_APP" --bundle-server https://10.0.2.2:8443 ) > "$EV/w2-embed-scaffold.log" 2>&1 \
  && ok "W2: keliver-new-production-host.sh --embed ($PROD_WHICH) wrote keliver-host/ into the existing app" \
  || { bad "W2: --embed failed"; cat "$EV/w2-embed-scaffold.log"; }
grep -q "^warning:" "$EV/w2-embed-scaffold.log" && bad "W2: --embed warned about the existing app's build: $(grep '^warning:' "$EV/w2-embed-scaffold.log" | head -2 | tr '\n' ' ')" \
  || ok "W2: the existing app's build declares what the library needs (no warning)"
( cd "$EMB_APP" && ./gradlew --console=plain assembleDebug ) > "$EV/w2-embed-build.log" 2>&1 \
  && cp "$(find "$EMB_APP/app/build/outputs/apk/debug" -name '*.apk' | head -1)" "$EV/embed-app.apk" \
  && ok "W2: the existing app built with the embedded host, from Maven Central" \
  || { bad "W2: the existing app did not build"; grep -E '^e: |What went wrong' -A3 "$EV/w2-embed-build.log" | head -20; }
EMB_KEY="$(unzip -p "$EV/embed-app.apk" assets/keliver/portal_ed25519.pub 2>/dev/null | tr -d ' \n')"
[ -n "$EMB_KEY" ] && [ "$EMB_KEY" = "$PUB" ] && ok "W2: the existing app's APK trusts this app's key (assets/keliver/)" || bad "W2: the embedded key is not this app's"
# W2.7: the same app shrunk by R8 (a release build, debug-signed for the emulator).
( cd "$EMB_APP" && ./gradlew --console=plain assembleRelease ) > "$EV/w2-embed-release-build.log" 2>&1 \
  && cp "$(find "$EMB_APP/app/build/outputs/apk/release" -name '*.apk' | head -1)" "$EV/embed-app-release.apk" \
  && ok "W2.7: the existing app's minified release (R8) built" || { bad "W2.7: the release build failed"; grep -E 'Missing class|ERROR|What went wrong' -A3 "$EV/w2-embed-release-build.log" | head -20; }
KEYF="$EMB_APP/keliver-host/src/main/assets/keliver/portal_ed25519.pub"; cp "$KEYF" "$WORK/embed-key.saved"
printf '%s\n' "$(printf '5a%.0s' $(seq 1 32))" > "$KEYF"
( cd "$EMB_APP" && ./gradlew --console=plain assembleDebug ) > "$EV/w2-embed-foreign-build.log" 2>&1 \
  && cp "$(find "$EMB_APP/app/build/outputs/apk/debug" -name '*.apk' | head -1)" "$EV/embed-app-foreign.apk" \
  && ok "W2: the same app, built trusting another key (for the refusal check)" || bad "W2: the foreign-key build failed"
cp "$WORK/embed-key.saved" "$KEYF"
( cd "$EMB_APP" && ./gradlew --stop ) > /dev/null 2>&1 || true
{ echo "W2_APK=$EV/embed-app.apk"; echo "W2_RELEASE_APK=$EV/embed-app-release.apk"; echo "W2_FOREIGN_APK=$EV/embed-app-foreign.apk"; echo "W2_ID=com.example.existing"; } >> "$WORK/env"

# Warm the development bundle so the device step does not wait on it.
( cd "$APP" && ./gradlew compileDevelopmentExecutableKotlinJsZipline --console=plain ) > "$EV/dev-bundle.log" 2>&1 \
  && ok "the development bundle builds" || bad "the development bundle did not build"

echo "prepare: passed $pass, failed $fail"
[ "$fail" -eq 0 ]
