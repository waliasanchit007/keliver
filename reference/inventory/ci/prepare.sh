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
# this harness prints a private key. The Zipline compile task's signing block
# reads it on each publish; the W8 rows (ci/w8/) also write both disposable
# private keys into one owner-only signing key file under <work-dir>/w8, as a CI
# secret holds them during a rotation, never into the evidence directory.
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

# W7: the device the hosts are built for. emulator (the default; CI): it reaches
# this machine as 10.0.2.2, and ci/w3/android-ca.sh puts the throwaway CA in its
# system store. physical: a USB phone. It reaches this machine as 127.0.0.1
# through `adb reverse` (device.sh sets that up), and its system store cannot
# take the CA without root, so the HTTPS test builds carry the CA themselves
# (ci/w7/android-test-ca.sh). docs/DEVICE_PROOF.md has the whole session.
DEVICE_KIND="${KELIVER_DEVICE:-emulator}"
case "$DEVICE_KIND" in
  emulator) HOST_ADDR=10.0.2.2 ;;
  physical) HOST_ADDR=127.0.0.1 ;;
  *) bad "KELIVER_DEVICE must be emulator or physical, not $DEVICE_KIND"; exit 1 ;;
esac
# The relay's port: KELIVER_RELAY_PORT, 8077 by default (CI). Another one on a
# machine whose :8077 is someone else's portal; the editor follows at port + 19.
# It goes into the app's keliver.portal.json (committed with the publish wiring
# below) and into <work-dir>/env, which device.sh reads. ci/ios.sh builds its own
# app and keeps :8077.
RELAY_PORT="${KELIVER_RELAY_PORT:-8077}"
case "$RELAY_PORT" in
  ''|*[!0-9]*) bad "KELIVER_RELAY_PORT must be a port number, not $RELAY_PORT"; exit 1 ;;
esac
RELAY_PORT=$((10#$RELAY_PORT))
[ "$RELAY_PORT" -ge 1024 ] && [ "$RELAY_PORT" -le 65516 ] \
  || { bad "KELIVER_RELAY_PORT must be 1024-65516 (the editor takes port + 19), not $RELAY_PORT"; exit 1; }
# The harness's other servers: the development bundle server and the static
# HTTPS servers. Neither the relay nor its editor may take one of them.
for taken in 8080 8443 8444 8445; do
  [ "$RELAY_PORT" = "$taken" ] || [ $((RELAY_PORT + 19)) = "$taken" ] \
    && { bad "KELIVER_RELAY_PORT $RELAY_PORT: it or its editor (port + 19) would take :$taken, which the harness uses"; exit 1; }
done

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
{ echo "DEVICE_KIND=$DEVICE_KIND"; echo "HOST_ADDR=$HOST_ADDR"; echo "RELAY_PORT=$RELAY_PORT"; } >> "$WORK/env"
if [ "$RELAY_PORT" != 8077 ]; then
  python3 -c 'import json,sys; p=sys.argv[1]; c=json.load(open(p)); c["port"]=int(sys.argv[2]); open(p,"w").write(json.dumps(c,indent=2)+"\n")' \
    "$APP/keliver.portal.json" "$RELAY_PORT" \
    && ok "the relay's port is $RELAY_PORT (KELIVER_RELAY_PORT), in the app's keliver.portal.json" \
    || { bad "could not set the relay's port in keliver.portal.json"; exit 1; }
fi

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
  # A relay this run did not start would answer every check below, and take
  # its edits and publishes: refuse a port that still answers after 20 s.
  local free=0
  for _ in $(seq 1 10); do curl -s -m 2 -o /dev/null "http://localhost:$RELAY_PORT/devstate" || { free=1; break; }; sleep 2; done
  [ "$free" = 1 ] || { bad ":$RELAY_PORT already answers: a relay this run did not start (stop it, or set another KELIVER_RELAY_PORT)"; echo "prepare: passed $pass, failed $fail"; exit 1; }
  ( cd "$1" && nohup "$KP/keliver-portal" --no-editor-build . > "$2" 2>&1 & )
  for _ in $(seq 1 60); do curl -sf -m 2 -o /dev/null "http://localhost:$RELAY_PORT/devstate" && return 0; sleep 3; done
  return 1
}
portal_down(){ ( cd "$1" && "$KP/keliver-portal" stop . >/dev/null 2>&1 ); sleep 2; }

portal_up "$APP" "$EV/relay-prepare.log" && ok "the relay for this app answered on :$RELAY_PORT" \
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
  curl -sf "http://localhost:$RELAY_PORT/doc?project=default&screen=$s" > "$EV/doc-$s.json"
  n="$(grep -o 'DocNode.RawCode' "$EV/doc-$s.json" | wc -l | tr -d ' ')"
  w="$(grep -o 'DocNode.Widget' "$EV/doc-$s.json" | wc -l | tr -d ' ')"
  [ -s "$EV/doc-$s.json" ] && [ "$n" = 0 ] && [ "$w" -gt 5 ] \
    && ok "D1 $s: $w widget nodes, 0 RawCode" || bad "D1 $s: $n RawCode (of $w widgets)"
done

# --- 4. P2 precondition: publish v1, signed with this app's key --------------
curl -s -m 600 -X POST http://localhost:$RELAY_PORT/publish > "$EV/publish-v1.log" 2>&1
grep -q 'publish OK: bundle v1' "$EV/publish-v1.log" && ok "publish v1: $(grep 'publish OK' "$EV/publish-v1.log")" \
  || { bad "publish v1 failed"; tail -20 "$EV/publish-v1.log"; }
M1="$STORE/bundles/v1/manifest.zipline.json"
cp "$M1" "$EV/manifest-v1.zipline.json"
python3 - "$M1" > "$EV/manifest-v1.summary" <<'PY'
import json, sys, hashlib
p = sys.argv[1]; raw = open(p, 'rb').read(); m = json.loads(raw)
sig = m.get('unsigned', {}).get('signatures', {})
print('manifest sha256', hashlib.sha256(raw).hexdigest())
print('signed by', list(sig))
print('modules', len(m['modules']))
PY
cat "$EV/manifest-v1.summary"
# In manifest order: portal-ed25519 alone (signing blocks before tools 0.3.9),
# or this app's key under its own name, then portal-ed25519 LAST (0.3.9 on;
# hosts before W8 trust only that name, and Zipline checks the first it trusts).
KEYNAME="keliver-ed25519-$(printf %s "$PUB" | tr 'A-F' 'a-f' | cut -c1-16)"
grep -qxE "signed by \[('$KEYNAME', )?'portal-ed25519'\]" "$EV/manifest-v1.summary" \
  && ok "v1's manifest is signed with this app's key, portal-ed25519 last ($(grep '^signed by' "$EV/manifest-v1.summary"))" \
  || bad "v1's manifest is not signed as expected (portal-ed25519 last, by this app's key; $KEYNAME)"
portal_down "$APP"

# --- 5. P1: the production host, scaffolded, with THIS app's key ------------
# keliver-new-production-host.sh reads the PUBLIC key from this app's store
# (resolved under this run's user.home) and writes host-android/, a standalone
# build on Maven Central only. HOST_ADDR is how the device reaches the relay
# (10.0.2.2 on the emulator).
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
( cd "$APP" && "$PROD_SCAFFOLD" --bundle-server "http://$HOST_ADDR:$RELAY_PORT" \
    --application-id "$PROD_ID" ) > "$EV/host-scaffold.log" 2>&1 \
  && ok "P1: keliver-new-production-host.sh ($PROD_WHICH) scaffolded host-android/" \
  || { bad "P1: the production host was not scaffolded"; cat "$EV/host-scaffold.log"; exit 1; }
sed 's/^/    /' "$EV/host-scaffold.log"
printf 'sdk.dir=%s\n' "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}" > "$APP/host-android/local.properties"
# W6 (V2): it reports to the relay. A template without keliver.reportUrl
# (tools 0.3.8 candidate 2 and earlier) ignores the property.
# Every host but W5's is pinned to next-launch: the rows expect a bundle published
# while the app runs to show only after a relaunch (a template from tools 0.3.10
# on leaves keliver.updates empty, which a debug build takes as on-resume).
( cd "$APP" && ./gradlew --console=plain -p host-android assembleDebug -Pkeliver.updates=next-launch -Pkeliver.reportUrl="http://$HOST_ADDR:$RELAY_PORT/report" ) > "$EV/host-build.log" 2>&1 \
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
# production host built for https://$HOST_ADDR:8443 (only the server differs).
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
bash "$HERE/w3/tls.sh" "$W3/tls" > "$EV/w3-tls.txt" 2>&1 && ok "W3: a throwaway CA and a server certificate for $HOST_ADDR" \
  || { bad "W3: no certificates"; cat "$EV/w3-tls.txt"; }
# W7: on a phone, the HTTPS builds of host-android trust the CA themselves.
if [ "$DEVICE_KIND" = physical ]; then
  bash "$HERE/w7/android-test-ca.sh" "$APP/host-android/src/debug" "$W3/tls/ca.pem" > "$EV/w7-test-ca-host.log" 2>&1 \
    && ok "W7: the HTTPS debug builds of host-android trust the throwaway CA in the APK (physical device)" \
    || { bad "W7: the test CA overlay failed"; cat "$EV/w7-test-ca-host.log"; }
fi
( cd "$APP" && ./gradlew --console=plain -p host-android assembleDebug -Pkeliver.updates=next-launch -Pkeliver.bundleServer="https://$HOST_ADDR:8443" ) \
  > "$EV/w3-host-build.log" 2>&1 \
  && cp "$(find "$APP/host-android/build/outputs/apk/debug" -name '*.apk' | head -1)" "$EV/production-host-static.apk" \
  && ok "W3: the production host, built for https://$HOST_ADDR:8443" || { bad "W3: the static host did not build"; tail -30 "$EV/w3-host-build.log"; }
{ echo "W3_PUBLISH=$W3_PUBLISH"; echo "W3_PUBLISH_TARGET=$W3_PUBLISH_TARGET"; echo "W3_TLS=$W3/tls"; echo "W3_APK=$EV/production-host-static.apk"; } >> "$WORK/env"
# W5 (U1) and W6 (V1): the same host, built to check for updates when it comes
# back to the foreground (on-resume) and to POST its reports to the static
# server's /report (keliver.reportUrl); only those settings differ. A host with
# a debug default (tools 0.3.10 on: it has the debug check-now hook) is built
# with keliver.updates unset, so U1 shows that default; an older one is set to
# on-resume.
if grep -rqs DEBUG_CHECK_FOR_UPDATE "$APP/host-android/src/main/kotlin"; then W5_UPDATES=""; else W5_UPDATES="-Pkeliver.updates=on-resume"; fi
# shellcheck disable=SC2086 # W5_UPDATES: one argument or none
( cd "$APP" && ./gradlew --console=plain -p host-android assembleDebug -Pkeliver.bundleServer="https://$HOST_ADDR:8443" $W5_UPDATES -Pkeliver.reportUrl="https://$HOST_ADDR:8443/report" ) \
  > "$EV/w5-host-build.log" 2>&1 \
  && cp "$(find "$APP/host-android/build/outputs/apk/debug" -name '*.apk' | head -1)" "$EV/production-host-onresume.apk" \
  && ok "W5: the production host, built with ${W5_UPDATES:-keliver.updates unset (the debug default)} and keliver.reportUrl" || { bad "W5: the on-resume host did not build"; tail -30 "$EV/w5-host-build.log"; }
# U0: with keliver.updates unset, the debug build type takes on-resume and every
# other one next-launch (the release variant's BuildConfig, https servers).
if [ -z "$W5_UPDATES" ]; then
  ( cd "$APP" && ./gradlew --console=plain -p host-android generateDebugBuildConfig generateReleaseBuildConfig \
      -Pkeliver.bundleServer="https://$HOST_ADDR:8443" -Pkeliver.reportUrl="https://$HOST_ADDR:8443/report" ) > "$EV/u0-buildconfig.log" 2>&1 \
    || bad "U0: generating the debug and release BuildConfig failed: $(grep -E 'What went wrong' -A2 "$EV/u0-buildconfig.log" | head -3 | tr '\n' ' ')"
  u0(){ find "$APP/host-android/build/generated/source/buildConfig/$1" -name BuildConfig.java -exec grep -h KELIVER_UPDATES {} + 2>/dev/null | sed 's/.*= *"\([^"]*\)".*/\1/'; }
  # A host with live updates (2026-10-27 on) defaults its debug build to live; an older one to on-resume.
  if grep -rqs 'bundles/events' "$APP/host-android/src/main/kotlin"; then U0_DEBUG=live; else U0_DEBUG=on-resume; fi
  [ "$(u0 debug)" = "$U0_DEBUG" ] && [ "$(u0 release)" = next-launch ] \
    && ok "U0: keliver.updates unset: the debug build is $U0_DEBUG, the release build next-launch" \
    || { bad "U0: debug '$(u0 debug)' (expected $U0_DEBUG), release '$(u0 release)'"; tail -20 "$EV/u0-buildconfig.log"; }
fi
echo "W5_APK=$EV/production-host-onresume.apk" >> "$WORK/env"
# M0 (T0): the benchmark build (release settings, timing marks) for the static
# server, and the release build to show it has no marks. Not on a phone: the
# test CA is in the debug build only.
if grep -rqs "interface KeliverTrace" "$APP/host-android/src/timing" && [ "$DEVICE_KIND" != physical ]; then
  ( cd "$APP" && ./gradlew --console=plain -p host-android assembleBenchmark assembleRelease -Pkeliver.bundleServer="https://$HOST_ADDR:8443" ) \
    > "$EV/m0-benchmark-build.log" 2>&1 \
    && cp "$(find "$APP/host-android/build/outputs/apk/benchmark" -name '*.apk' | head -1)" "$EV/production-host-benchmark.apk" \
    && echo "M0_BENCH_APK=$EV/production-host-benchmark.apk" >> "$WORK/env" \
    && ok "T0: the benchmark build (minified with R8) built" || { bad "T0: the benchmark build failed"; tail -30 "$EV/m0-benchmark-build.log"; }
  AAPT2="$(ls "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"/build-tools/*/aapt2 2>/dev/null | tail -1)"
  if [ -f "$EV/production-host-benchmark.apk" ] && [ -n "$AAPT2" ]; then
    "$AAPT2" dump xmltree --file AndroidManifest.xml "$EV/production-host-benchmark.apk" > "$EV/m0-benchmark-manifest.txt" 2>&1
    grep -q "profileable" "$EV/m0-benchmark-manifest.txt" && ! grep -q "debuggable" "$EV/m0-benchmark-manifest.txt" \
      && ok "T0: the benchmark build is profileable and not debuggable" || bad "T0: the benchmark manifest: $(grep -iE 'profileable|debuggable' "$EV/m0-benchmark-manifest.txt" | tr '\n' ' ')"
  fi
  # The memory profile's strings (M5), in a host that has it: its cycle's action and pss's graphics stat.
  M0_STRINGS=(-e 'keliver/timing.log' -e 'KeliverTrace' -e 'first-frame')
  grep -rqs "MEMORY_CYCLE" "$APP/host-android/src/timing" && M0_STRINGS+=(-e 'dev.keliver.debug.MEMORY_CYCLE' -e 'summary.graphics')
  # A positive control: the benchmark build's dex carries every one, so the release check can see
  # them. Each grep reads the files itself: `cat … | grep -q` exits 141 under pipefail when grep
  # stops early, which read as "absent" here and as "no timing code" in the release check.
  if [ -f "$EV/production-host-benchmark.apk" ]; then
    rm -rf "$WORK/m0-bdex" && mkdir -p "$WORK/m0-bdex" && unzip -q -o "$EV/production-host-benchmark.apk" 'classes*.dex' -d "$WORK/m0-bdex"
    missing=""
    for ((i = 1; i < ${#M0_STRINGS[@]}; i += 2)); do
      grep -aq -e "${M0_STRINGS[$i]}" "$WORK"/m0-bdex/*.dex || missing="$missing ${M0_STRINGS[$i]}"
    done
    [ -z "$missing" ] && ok "T0: the benchmark build's dex carries $(( ${#M0_STRINGS[@]} / 2 )) timing strings (the release check can see them)" \
      || bad "T0: the benchmark build's dex lacks:$missing"
  fi
  # The store build has none of it: not the file sink, not the guest's service.
  rel="$(find "$APP/host-android/build/outputs/apk/release" -name '*.apk' | head -1)"
  if [ -n "$rel" ]; then
    rm -rf "$WORK/m0-dex" && mkdir -p "$WORK/m0-dex" && unzip -q -o "$rel" 'classes*.dex' -d "$WORK/m0-dex"
    # Only grep's 1 is "none": 2 (no dex unpacked, an unreadable file) looked at nothing.
    grep -aqs "${M0_STRINGS[@]}" "$WORK"/m0-dex/*.dex; rc=$?
    case $rc in
      1) ok "T0: the release build has no timing marks (no file sink, no KeliverTrace, no first-frame$([ ${#M0_STRINGS[@]} -gt 6 ] && echo ', no memory profile'))" ;;
      0) bad "T0: the release build carries timing code" ;;
      *) bad "T0: the release build's dex could not be read (grep exit $rc)" ;;
    esac
  else
    bad "T0: no release APK to check"
  fi
fi

# W8 (key rotation): the next signing key, made by keliver-publish
# --new-signing-key, and the same host built trusting this app's key AND the
# next one, then the next one only (only the key file differs). Only with tools
# that have W8: a published zip from before it has no --new-signing-key, and
# ci/w8/android-rotation.sh then SKIPs.
W8="$WORK/w8"; rm -rf "$W8"; mkdir -p "$W8"   # this run's own: --new-signing-key never replaces a key
if "$W3_PUBLISH" --help 2>/dev/null | grep -q -- '--new-signing-key' \
   && grep -q KELIVER_NEXT_PUBLIC_KEY_HEX "$APP/host-android/build.gradle"; then
  "$W3_PUBLISH" --new-signing-key "$W8/next" > "$EV/w8-new-key.log" 2>&1
  mode="$(stat -c %a "$W8/next/ed25519.priv" 2>/dev/null || stat -f %Lp "$W8/next/ed25519.priv" 2>/dev/null)"
  NEXT_PUB="$(tr -d ' \n' < "$W8/next/ed25519.pub" 2>/dev/null)"
  [ "$mode" = 600 ] && [[ "$NEXT_PUB" =~ ^[0-9a-f]{64}$ ]] && [ "$NEXT_PUB" != "$PUB" ] \
    && grep -q "$NEXT_PUB" "$EV/w8-new-key.log" && ! grep -qFf "$W8/next/ed25519.priv" "$EV/w8-new-key.log" \
    && ok "W8: keliver-publish --new-signing-key made the next key pair (private key mode $mode, never printed)" \
    || bad "W8: --new-signing-key (mode '$mode'): $(head -3 "$EV/w8-new-key.log" | tr '\n' ' ')"
  HKEY="$APP/host-android/src/main/assets/keliver/portal_ed25519.pub"; cp "$HKEY" "$W8/key.saved"
  w8_host(){  # $1 label: build the host for the static server with the key file as it is now
    ( cd "$APP" && ./gradlew --console=plain -p host-android assembleDebug -Pkeliver.updates=next-launch -Pkeliver.bundleServer="https://$HOST_ADDR:8443" ) \
      > "$EV/w8-host-$1-build.log" 2>&1 \
      && cp "$(find "$APP/host-android/build/outputs/apk/debug" -name '*.apk' | head -1)" "$EV/production-host-$1.apk"
  }
  printf '%s\n%s\n' "$PUB" "$NEXT_PUB" > "$HKEY"
  w8_host both && [ "$(unzip -p "$EV/production-host-both.apk" assets/keliver/portal_ed25519.pub | tr -d ' ' | grep -c .)" = 2 ] \
    && ok "W8: the host built trusting this app's key and the next one (two lines in assets/keliver/portal_ed25519.pub)" \
    || { bad "W8: the two-key host did not build"; tail -30 "$EV/w8-host-both-build.log"; }
  printf '%s\n' "$NEXT_PUB" > "$HKEY"
  w8_host next && ok "W8: the host built trusting only the next key" || { bad "W8: the next-key host did not build"; tail -30 "$EV/w8-host-next-build.log"; }
  printf '%s\n%s\n%s\n' "$PUB" "$NEXT_PUB" "$(printf '5a%.0s' $(seq 1 32))" > "$HKEY"
  ! w8_host three && grep -q "a host trusts at most two" "$EV/w8-host-three-build.log" \
    && ok "W8: a key file with three keys fails the build (checkPortalKey: at most two)" || bad "W8: a three-key host built, or failed otherwise"
  cp "$W8/key.saved" "$HKEY"
  # The hosts already in the field: one from the published zip's own scaffolder
  # (before W8: one key, trusted only as portal-ed25519), for K3 and K6. Written
  # where host-android/ is, which is moved aside and put back.
  W8_LEGACY_APK=""
  # Its scaffolder: the tools zip's own while that predates W8, else the
  # published tools 0.3.8 (ci/w8/legacy-tools.sh, hash-pinned).
  if [ -f "$KP/../templates/production-host/build.gradle" ] && ! grep -q KELIVER_NEXT_PUBLIC_KEY_HEX "$KP/../templates/production-host/build.gradle"; then
    LKP="$KP"
  else
    LKP="$(bash "$HERE/w8/legacy-tools.sh" "$W8/legacy-tools" 2> "$EV/w8-legacy-tools.log")"
  fi
  if [ -n "$LKP" ] && [ -x "$LKP/keliver-new-production-host.sh" ] \
     && ! grep -q KELIVER_NEXT_PUBLIC_KEY_HEX "$LKP/../templates/production-host/build.gradle"; then
    mv "$APP/host-android" "$W8/host-android.w8"
    ( cd "$APP" && "$LKP/keliver-new-production-host.sh" --bundle-server "https://$HOST_ADDR:8443" --application-id "$PROD_ID" ) \
      > "$EV/w8-legacy-host-scaffold.log" 2>&1 \
      && printf 'sdk.dir=%s\n' "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}" > "$APP/host-android/local.properties" \
      && { [ "$DEVICE_KIND" != physical ] || bash "$HERE/w7/android-test-ca.sh" "$APP/host-android/src/debug" "$W3/tls/ca.pem" >> "$EV/w7-test-ca-host.log" 2>&1; } \
      && w8_host legacy && W8_LEGACY_APK="$EV/production-host-legacy.apk" \
      && ok "W8: a host from $(basename "$(dirname "$LKP")")'s own scaffolder (before W8: one key, portal-ed25519 only), for K3 and K6" \
      || { bad "W8: the zip's pre-W8 host did not scaffold or build"; tail -20 "$EV/w8-legacy-host-scaffold.log"; tail -20 "$EV/w8-host-legacy-build.log" 2>/dev/null; }
    rm -rf "$APP/host-android"; mv "$W8/host-android.w8" "$APP/host-android"
  else
    echo "  NOTE  W8: no tools from before W8 to scaffold with ($(tail -1 "$EV/w8-legacy-tools.log" 2>/dev/null)): K3 and K6 use this checkout's one-key host"
  fi
  { echo "W8_OK=1"; echo "W8_NEXT=$W8/next"; echo "W8_BOTH_APK=$EV/production-host-both.apk"; echo "W8_NEXT_APK=$EV/production-host-next.apk"
    echo "W8_LEGACY_APK=${W8_LEGACY_APK:-$W3_APK}"; echo "W8_LEGACY_IS_PRE_W8=$([ -n "$W8_LEGACY_APK" ] && echo 1 || echo 0)"; } >> "$WORK/env"
else
  echo "  SKIP  W8: $W3_PUBLISH has no --new-signing-key, or the host template predates key rotation; not counted"
  echo "W8_OK=0" >> "$WORK/env"
fi

# --- 7. W2: the host embedded in an "existing" app (reference/embed/android) ---
# The same scaffolder, with --embed, writes keliver-host/ into a plain View-based
# app whose own files carry only the documented edits (KELIVER EMBED). Built
# for W3's static HTTPS server; and once more trusting a key that is not this
# app's, for the refusal check.
EMB_APP="$WORK/embed"; rm -rf "$EMB_APP"; cp -R "$REPO/reference/embed/android" "$EMB_APP"
cp "$APP/gradlew" "$EMB_APP/" && cp -R "$APP/gradle" "$EMB_APP/"
printf 'sdk.dir=%s\n' "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}" > "$EMB_APP/local.properties"
( cd "$APP" && "$PROD_SCAFFOLD" --embed --into "$EMB_APP" --bundle-server "https://$HOST_ADDR:8443" ) > "$EV/w2-embed-scaffold.log" 2>&1 \
  && ok "W2: keliver-new-production-host.sh --embed ($PROD_WHICH) wrote keliver-host/ into the existing app" \
  || { bad "W2: --embed failed"; cat "$EV/w2-embed-scaffold.log"; }
# W7: on a phone, the fixture's debug and R8 release builds trust the CA themselves
# (a release build is not debuggable, so the CA goes in its own source set too).
if [ "$DEVICE_KIND" = physical ]; then
  for t in debug release; do
    bash "$HERE/w7/android-test-ca.sh" "$EMB_APP/app/src/$t" "$W3/tls/ca.pem" >> "$EV/w7-test-ca-embed.log" 2>&1 \
      && ok "W7: the existing app's $t build trusts the throwaway CA in the APK (physical device)" \
      || { bad "W7: the test CA overlay failed for the existing app's $t build"; cat "$EV/w7-test-ca-embed.log"; }
  done
fi
grep -q "^warning:" "$EV/w2-embed-scaffold.log" && bad "W2: --embed warned about the existing app's build: $(grep '^warning:' "$EV/w2-embed-scaffold.log" | head -2 | tr '\n' ' ')" \
  || ok "W2: the existing app's build declares what the library needs (no warning)"
( cd "$EMB_APP" && ./gradlew --console=plain assembleDebug -Pkeliver.updates=next-launch ) > "$EV/w2-embed-build.log" 2>&1 \
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
( cd "$EMB_APP" && ./gradlew --console=plain assembleDebug -Pkeliver.updates=next-launch ) > "$EV/w2-embed-foreign-build.log" 2>&1 \
  && cp "$(find "$EMB_APP/app/build/outputs/apk/debug" -name '*.apk' | head -1)" "$EV/embed-app-foreign.apk" \
  && ok "W2: the same app, built trusting another key (for the refusal check)" || bad "W2: the foreign-key build failed"
cp "$WORK/embed-key.saved" "$KEYF"
( cd "$EMB_APP" && ./gradlew --stop ) > /dev/null 2>&1 || true
{ echo "W2_APK=$EV/embed-app.apk"; echo "W2_RELEASE_APK=$EV/embed-app-release.apk"; echo "W2_FOREIGN_APK=$EV/embed-app-foreign.apk"; echo "W2_ID=com.example.existing"; } >> "$WORK/env"

# Warm the development bundle so the device step's serveDevelopmentZipline does not wait on it.
# (Publishing builds the Production bundle, which keliver-new-publish-target.sh wires by default.)
( cd "$APP" && ./gradlew compileDevelopmentExecutableKotlinJsZipline --console=plain ) > "$EV/dev-bundle.log" 2>&1 \
  && ok "the development bundle builds" || bad "the development bundle did not build"

echo "prepare: passed $pass, failed $fail"
[ "$fail" -eq 0 ]
