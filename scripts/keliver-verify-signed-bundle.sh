#!/usr/bin/env bash
#
# keliver-verify-signed-bundle — prove the publisher and the hosts agree.
#
#   scripts/keliver-verify-signed-bundle.sh <disposable-root>
#
# Generates a signing identity in a DISPOSABLE store, publishes a bundle with
# it, and verifies the manifest against the public key the device hosts would
# embed from that same store — using Zipline's own ManifestVerifier, with
# signature checks ON. An unsigned bundle fails.
#
# Never touches the developer's real store: the isolation guard runs first and
# refuses to continue if the effective JVM user.home or resolved store falls
# outside <disposable-root>.
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DISP="${1:?usage: $0 <disposable-root>}"
mkdir -p "$DISP"; DISP="$(cd "$DISP" && pwd -P)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"

STORE="$DISP/store"
export PORTAL_STORE="$STORE"
# -Duser.home is what actually redirects the store (HOME does not). It also
# moves Gradle's default home, so pin GRADLE_USER_HOME at the real one: the
# Gradle distribution and dependency cache are not what is under test, and
# re-downloading them through a TLS-inspecting proxy is how this first failed.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$DISP/home"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
mkdir -p "$DISP/home" "$STORE"

# shellcheck source=/dev/null
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
keliver_require_isolated_store "$DISP" "$ROOT" || exit 1

echo "==> generating a disposable signing identity via the relay"
( cd "$ROOT" && PORTAL_REPO="$ROOT" "$ROOT/portal-relay/build/install/portal-relay/bin/portal-relay" \
    > "$DISP/relay.log" 2>&1 & )
PORT="$(python3 -c "import json;print(json.load(open('$ROOT/keliver.portal.json')).get('port',8077))")"
for _ in $(seq 1 40); do curl -sf -m 2 -o /dev/null "http://localhost:$PORT/screens" && break; sleep 3; done
lsof -ti :"$PORT" -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null; sleep 2

PRIV="$STORE/keys/ed25519.priv"; PUB="$STORE/keys/ed25519.pub"
[ -s "$PRIV" ] && [ -s "$PUB" ] || { echo "no disposable keypair generated in $STORE" >&2; exit 1; }
echo "    keypair present (contents never printed)"

echo "==> the resolver and the build agree on the store"
RESOLVED="$("$ROOT/scripts/keliver-store-path.sh" "$ROOT")"
[ "$RESOLVED" = "$STORE" ] || { echo "resolver says $RESOLVED, expected $STORE" >&2; exit 1; }
echo "    $RESOLVED"

echo "==> publishing a bundle signed with that identity"
( cd "$ROOT" && env -u KELIVER_USE_MAVEN_LOCAL ./gradlew :portal-published-guest:compileDevelopmentZipline \
    --console=plain > "$DISP/publish.log" 2>&1 )
rc=$?
[ $rc -eq 0 ] || { echo "publish failed (see $DISP/publish.log)" >&2; tail -20 "$DISP/publish.log" >&2; exit 1; }

MANIFEST="$(find "$ROOT/portal-published-guest/build/zipline" -name manifest.zipline.json | head -1)"
[ -n "$MANIFEST" ] || { echo "no manifest produced" >&2; exit 1; }
echo "    $MANIFEST"

echo "==> verifying with Zipline's ManifestVerifier (checks ON)"
( cd "$ROOT" && ./gradlew :portal-relay:test --tests '*SignedBundleVerificationTest*' \
    -Dkeliver.verify.manifest="$MANIFEST" -Dkeliver.verify.pubkey="$PUB" \
    --rerun-tasks --console=plain > "$DISP/verify.log" 2>&1 )
vrc=$?
grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' \
  "$ROOT/portal-relay/build/test-results/test/TEST-SignedBundleVerificationTest.xml" 2>/dev/null
[ $vrc -eq 0 ] || { echo "VERIFICATION FAILED (see $DISP/verify.log)" >&2; exit 1; }
echo "==> signed bundle verifies against the store's public key"
