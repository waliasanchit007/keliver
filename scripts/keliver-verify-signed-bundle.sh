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
PORT="$(python3 -c "import json;print(json.load(open('$ROOT/keliver.portal.json')).get('port',8077))")"

# A relay already on this port is NOT ours to talk to or to kill. The default
# is 8077, the documented dev-loop port, so the likely occupant is the
# developer's own portal: answering there would generate nothing here, and
# killing it would stop their session while this script reported success.
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t >/dev/null 2>&1; then
  echo "port $PORT is already in use; stop that process or free the port and re-run" >&2
  exit 2
fi

( cd "$ROOT" && PORTAL_REPO="$ROOT" "$ROOT/portal-relay/build/install/portal-relay/bin/portal-relay" \
    > "$DISP/relay.log" 2>&1 ) &
RELAY_PID=$!
# Kill only what this invocation started, and both halves of it: the launcher
# subshell and the relay JVM it spawned.
cleanup_relay(){
  [ -n "${RELAY_LISTENER:-}" ] && kill "$RELAY_LISTENER" 2>/dev/null
  [ -n "${RELAY_PID:-}" ] && kill "$RELAY_PID" 2>/dev/null
  return 0
}
trap cleanup_relay EXIT
for _ in $(seq 1 40); do curl -sf -m 2 -o /dev/null "http://localhost:$PORT/screens" && break; sleep 3; done
RELAY_LISTENER="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1)"
cleanup_relay; RELAY_PID=""; RELAY_LISTENER=""; sleep 2

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
XML="$ROOT/portal-relay/build/test-results/test/TEST-SignedBundleVerificationTest.xml"
grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' "$XML" 2>/dev/null
[ $vrc -eq 0 ] || { echo "VERIFICATION FAILED (see $DISP/verify.log)" >&2; exit 1; }
# A green build is not the claim. -D reaches the GRADLE JVM, not the forked
# test JVM, and until portal-relay/build.gradle forwarded these properties the
# test read null, took its skip branch, and this line printed anyway (U26).
[ -f "$XML" ] || { echo "no test result XML — the verification did not run" >&2; exit 1; }
if grep -q "skipped (no manifest/pubkey properties)" "$XML"; then
  echo "VERIFICATION SKIPPED — it proved nothing. Check that the build forwards" >&2
  echo "  -Dkeliver.verify.* to the test JVM." >&2
  exit 1
fi
grep -q 'tests="2"' "$XML" || { echo "expected 2 tests (verification and tamper rejection)" >&2; exit 1; }
echo "==> signed bundle verifies against the store's public key, and a tampered one does not"
