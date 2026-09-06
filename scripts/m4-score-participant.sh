#!/usr/bin/env bash
#
# m4-score-participant — score one M4 participant's final screen with the
# evaluator's own behavioural check.
#
#   scripts/m4-score-participant.sh <final-screen.kt> <label>
#
# Substitutes the participant's final screen body into the parity fixture that
# M4LabelUpdateCheck composes, runs the check, and restores the fixture. The
# same check scores every condition, so scoring is independent of what any
# participant said about its own verification.
#
# Exit 0 = the requirement holds at runtime. Exit 1 = it does not.
#
set -uo pipefail
SRC="${1:-}"; LABEL="${2:-participant}"
[ -f "$SRC" ] || { echo "usage: $0 <final-screen.kt> <label>" >&2; exit 2; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIX="$ROOT/portal-parity-fixtures/kotlin/dev/keliver/portal/render/fixture/M4CounterScreen.kt"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/m4-score-XXXXXX")"
BAK="$WORK/M4CounterScreen-orig.kt"
cp "$FIX" "$BAK"
[ -s "$BAK" ] || { echo "could not back up the fixture; refusing to modify it" >&2; exit 2; }
restore() { cp "$BAK" "$FIX"; cmp -s "$BAK" "$FIX" || echo "WARNING: fixture not restored — $FIX" >&2; }
trap restore EXIT

# Rename the participant's screen/bindings onto the fixture's names. The
# fixture package and the check are untouched.
python3 - "$SRC" "$FIX" <<'PY'
import re, sys
src, dst = sys.argv[1], sys.argv[2]
s = open(src).read()
s = re.sub(r'^package .*$', 'package dev.keliver.portal.render.fixture', s, count=1, flags=re.M)
s = s.replace('HomeScreenBindings', 'M4CounterBindings').replace('HomeScreen', 'M4CounterScreen')
open(dst, 'w').write(s)
PY

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}"
export NODE_EXTRA_CA_CERTS="${NODE_EXTRA_CA_CERTS:-$HOME/.android-certs/full-ca-bundle.pem}"
LOG="$WORK/gradle.log"
( cd "$ROOT" && env -u KELIVER_USE_MAVEN_LOCAL ./gradlew :portal-render:wasmJsTest \
    --rerun-tasks --console=plain >"$LOG" 2>&1 )
rc=$?
if [ $rc -eq 0 ]; then
  echo "SCORE $LABEL: PASS — summary label reflects state after addItem()"
else
  echo "SCORE $LABEL: FAIL — requirement not met at runtime"
  grep -A3 "REQUIREMENT\|summaryLabelReflectsStateAfterTransition" "$LOG" | head -20
fi
echo "  log: $LOG"
exit $rc
