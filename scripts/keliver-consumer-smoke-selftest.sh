#!/usr/bin/env bash
#
# Regression coverage for keliver-consumer-smoke.sh.
#
#   scripts/keliver-consumer-smoke-selftest.sh [version]
#
# The smoke script exists to prevent a silent false pass. These cases pin the
# ways it previously could produce one, plus the happy path.
#
set -uo pipefail
VERSION="${1:-0.3.3}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SMOKE="$ROOT/scripts/keliver-consumer-smoke.sh"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/smoke-selftest-XXXXXX")"
pass=0; fail=0

check() { # name expected_exit actual_exit extra_note
  if [ "$2" = "$3" ]; then printf '  PASS  %-46s (exit %s) %s\n' "$1" "$3" "${4:-}"; pass=$((pass+1))
  else printf '  FAIL  %-46s expected %s got %s %s\n' "$1" "$2" "$3" "${4:-}"; fail=$((fail+1)); fi
}

echo "keliver-consumer-smoke self-test  (version under test: $VERSION)"
echo "workdir: $WORK"

# 1. missing --out value must not loop forever
( "$SMOKE" "$VERSION" --out >/dev/null 2>&1 ) & p=$!
( sleep 15; kill -9 $p 2>/dev/null ) & w=$!
wait $p 2>/dev/null; rc=$?; kill $w 2>/dev/null
check "missing --out value exits, does not hang" 2 "$rc"

# 2. missing --scaffolder value likewise
( "$SMOKE" "$VERSION" --scaffolder >/dev/null 2>&1 ) & p=$!
( sleep 15; kill -9 $p 2>/dev/null ) & w=$!
wait $p 2>/dev/null; rc=$?; kill $w 2>/dev/null
check "missing --scaffolder value exits, does not hang" 2 "$rc"

# 3. failed scaffolding step
"$SMOKE" "$VERSION" --scaffolder /nonexistent/keliver-init --out "$WORK/badscaffold" >/dev/null 2>&1
check "unusable scaffolder fails" 1 "$?"

# 4. artifacts unavailable (stands in for a failed resolution/compilation step)
"$SMOKE" 0.0.0-does-not-exist --out "$WORK/badversion" >/dev/null 2>&1
check "version absent from Central fails" 1 "$?"

# 5. snapshot versions refused up front
"$SMOKE" 9.9.9-SNAPSHOT --out "$WORK/snap" >/dev/null 2>&1
check "snapshot version refused" 2 "$?"

# 6. a PRE-EXISTING output/cache directory must not be reused as the cache
mkdir -p "$WORK/preexisting/gradle-home"
echo marker > "$WORK/preexisting/gradle-home/PRE_EXISTING_MARKER"
KELIVER_USE_MAVEN_LOCAL=1 "$SMOKE" "$VERSION" --out "$WORK/preexisting" >/dev/null 2>&1
rc=$?
note=""
[ -f "$WORK/preexisting/gradle-home/PRE_EXISTING_MARKER" ] \
  && [ "$(ls -A "$WORK/preexisting/gradle-home" | wc -l | tr -d ' ')" = "1" ] \
  && note="(seeded cache untouched)" || note="(SEEDED CACHE WAS USED)"
check "pre-existing cache dir not reused; still passes" 0 "$rc" "$note"

# 7. and that run must have produced Central-only resolution
if grep -q "mavenLocal" "$WORK/preexisting/generated-settings.gradle" 2>/dev/null; then
  printf '  FAIL  %-46s mavenLocal present despite env\n' "hostile env stripped from scaffold"; fail=$((fail+1))
else
  printf '  PASS  %-46s\n' "hostile env stripped from scaffold"; pass=$((pass+1))
fi

echo
echo "passed: $pass   failed: $fail"
echo "artifacts: $WORK"
[ "$fail" -eq 0 ]
