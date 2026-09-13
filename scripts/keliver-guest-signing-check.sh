#!/usr/bin/env bash
#
# keliver-guest-signing-check — the guest bundle is signed when there is a key,
# and unsigned when there is not.
#
#   scripts/keliver-guest-signing-check.sh <disposable-root>
#
# WHY THIS EXISTS. portal-published-guest does not configure signing through the
# `zipline { signingKeys { ... } }` extension; it writes the compile task's own
# signingKeys ListProperty from afterEvaluate, because the store must not be
# resolved while the build file is being read. Gradle runs a task's
# configuration actions in the order they were added, so ANY later writer to
# that property silently wins — and the symptom is not an error. It is a bundle
# that ships UNSIGNED with a signing key sitting in the store. That happened
# once during development and was caught by hand.
#
# This turns "signed" into a gate. Both directions are asserted, so the check
# cannot pass by never signing anything.
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

# The same guard every sibling check uses. Without it this script would
# mkdir -p whatever it was handed and write a disposable PRIVATE KEY inside it:
# `keliver-guest-signing-check.sh ~/.keliver-portal` would plant one in the real
# store.
# shellcheck source=/dev/null
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "$DISP_PARENT" guest-signing)" || exit 1

# signingKeys is an @Input, so the disposable private key would otherwise be
# serialised into Gradle's execution history under the repo. Keep the project
# cache — and therefore that history — inside the disposable root.
GRADLE_FLAGS="--console=plain -q --project-cache-dir $DISP/project-cache"

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
if ( cd "$ROOT" && ./gradlew $GRADLE_FLAGS -Pkeliver.portalStore="$DISP/store" "$TASK" ) \
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
if ( cd "$ROOT" && ./gradlew $GRADLE_FLAGS -Pkeliver.portalStore="$DISP/nostore" "$TASK" ) \
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
# case. Nothing packages or verifies that path today, but leaving an unsigned
# manifest lying in the repo is the sort of thing a later check trips over, so
# remove it rather than explain it later.
rm -f "$MANIFEST"

echo
echo "passed: $PASS   failed: $FAIL"
[ "$FAIL" = 0 ]
