#!/usr/bin/env bash
#
# keliver-guest-signing-check — the guest bundle is signed when there is a key,
# and unsigned when there is not.
#
#   scripts/keliver-guest-signing-check.sh <disposable-root>
#
# WHY THIS EXISTS. portal-published-guest does not configure signing through the
# `zipline { signingKeys { ... } }` extension; it writes the compile task's own
# signingKeys ListProperty from a `configureEach` placed BELOW the `kotlin {}`
# block, because the store must not be resolved while the build file is being
# read. Gradle splices a task's registration action into the container's action
# chain at the position register() was called, so the same statement moved above
# that block is added earlier, loses to the plugin's own write, and the symptom
# is not an error. It is a bundle that ships UNSIGNED with a signing key sitting
# in the store. That happened once during development and was caught by hand.
#
# This turns "signed" into a gate. Both directions are asserted, so the check
# cannot pass by never signing anything.
#
# WHAT IT DOES AND DOES NOT COVER. It builds the DEVELOPMENT variant only, and
# it runs in portal-tools.yml, which fires on `portal-tools-v*` tags and on
# workflow_dispatch — not on pull requests and not on pushes to main. So it is a
# release-time and on-demand gate, not a per-commit one.
#
# It never reads the developer's real store: -Pkeliver.portalStore names a
# disposable one, which is resolved before the resolver is consulted, so no real
# key is read and none is written. The private key it generates is disposable
# and is never printed.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DISP_PARENT="${1:?usage: $0 <disposable-root>}"
# /usr/libexec/java_home is macOS-only; on Linux (CI) JAVA_HOME is already set
# by setup-java. Falling through with an empty value would be worse than saying
# so — gradlew would silently pick whatever java is on PATH.
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x /usr/libexec/java_home ]; then JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
  [ -n "${JAVA_HOME:-}" ] || { echo "JAVA_HOME is not set and cannot be discovered" >&2; exit 2; }
fi
export JAVA_HOME

# keliver_make_run_dir gives this script a fresh mktemp -d under the parent, and
# refuses outright when that parent lies inside the real portal store, the
# Gradle home or $PORTAL_STORE — by device+inode, so a case-variant or
# symlinked spelling of the same directory is refused too. That check lives in
# the guard rather than here because six checks mint throwaway identities under
# a caller-supplied parent and they should not each carry their own copy of the
# rule; two earlier copies of it, local to this script, both failed open.
# shellcheck source=/dev/null
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "$DISP_PARENT" guest-signing)" || exit $?

# Keep Gradle's project cache — task history, file hashes — inside the
# disposable root, so the run leaves no .gradle state in the repo. NOT because
# the private key would otherwise be stored there: measured, Gradle records a
# hash of the @Input, and the key bytes appear in no cache file. The cost is a
# cold project cache, so the Kotlin/JS + Zipline chain re-executes every run.
GRADLE_FLAGS=(--console=plain -q --project-cache-dir "$DISP/project-cache")

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '  PASS  %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '  FAIL  %s\n' "$1"; }

MANIFEST="$ROOT/portal-published-guest/build/zipline/Development/manifest.zipline.json"
TASK=":portal-published-guest:compileDevelopmentExecutableKotlinJsZipline"

echo "=== guest bundle signing"
echo "    disposable root: $DISP"

# A disposable Ed25519 signing identity. Zipline wants the raw 32-byte private
# key as hex; any 32 random bytes are a valid seed. NOT printed.
mkdir -p "$DISP/store/keys" "$DISP/nostore/keys"
python3 -c "import os,sys; open(sys.argv[1],'w').write(os.urandom(32).hex())" \
  "$DISP/store/keys/ed25519.priv"

signatures_in() {
  python3 - "$1" <<'PY'
import json, sys
try:
    m = json.load(open(sys.argv[1]))
except Exception as e:
    print("UNREADABLE: %s" % e); raise SystemExit(0)
print(",".join(sorted(m.get("unsigned", {}).get("signatures", {}))) or "(none)")
PY
}

echo "--- a store WITH a signing key"
rm -f "$MANIFEST"
if ( cd "$ROOT" && ./gradlew "${GRADLE_FLAGS[@]}" -Pkeliver.portalStore="$DISP/store" "$TASK" ) \
     > "$DISP/signed.log" 2>&1; then
  ok "the guest bundle builds against a store that holds a key"
else
  bad "the guest bundle failed to build against a store that holds a key"
  tail -15 "$DISP/signed.log" | sed 's/^/        /'
fi
SIGS="$(signatures_in "$MANIFEST")"
if [ "$SIGS" = "portal-ed25519" ]; then
  ok "and its manifest is signed with the store's identity"
else
  bad "the manifest is NOT signed with the store's identity (signatures: $SIGS)"
fi

echo "--- a store with NO signing key"
rm -f "$MANIFEST"
if ( cd "$ROOT" && ./gradlew "${GRADLE_FLAGS[@]}" -Pkeliver.portalStore="$DISP/nostore" "$TASK" ) \
     > "$DISP/unsigned.log" 2>&1; then
  ok "the guest bundle still builds when the store holds no key"
else
  bad "the guest bundle failed to build when the store holds no key"
  tail -15 "$DISP/unsigned.log" | sed 's/^/        /'
fi
SIGS="$(signatures_in "$MANIFEST")"
if [ "$SIGS" = "(none)" ]; then
  ok "and it is unsigned rather than signed by something else"
else
  bad "an unsigned build produced signatures: $SIGS"
fi

# The manifest under portal-published-guest/build is left UNSIGNED by the second
# case, and that path is not unused: keliver-verify-signed-bundle.sh globs for
# it. That script rebuilds first, so an unsigned leftover would not mislead it
# today — but leaving one in the repo is the sort of thing a later check trips
# over, so remove it rather than explain it later.
rm -f "$MANIFEST"

echo
echo "passed: $PASS   failed: $FAIL"
[ "$FAIL" = 0 ]
