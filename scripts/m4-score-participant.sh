#!/usr/bin/env bash
#
# m4-score-participant — score one M4 participant's final screen with the
# evaluator's own behavioural check.
#
#   scripts/m4-score-participant.sh <final-screen.kt> <label> [--case NAME]
#
# --case selects which evaluator check scores the screen. Defaults to `counter`
# (the literal-label pilot). `cart` is M4 case 2, the wrong-field binding.
#
# Substitutes the participant's final screen body into the parity fixture that
# M4LabelUpdateCheck composes, runs the check, and restores the fixture. The
# same check scores every condition, so scoring is independent of what any
# participant said about its own verification.
#
# THREE OUTCOMES, NEVER TWO. An earlier version treated any nonzero Gradle exit
# as "requirement not met at runtime": given the known-correct reference fix
# and an unusable Gradle distribution, it reported an application failure for
# code it never executed. Infrastructure that could not run the check is not
# evidence about the participant.
#
#   PASS  (exit 0) — the named test ran in this invocation and passed
#   FAIL  (exit 1) — the named test ran in this invocation and its assertion failed
#   ERROR (exit 4) — the check did not run; nothing is claimed about the participant
#
# Freshness is enforced, not assumed: the result file for this test is deleted
# before the run, and a result that is absent afterwards is an ERROR rather
# than being silently inherited from a previous invocation.
#
set -uo pipefail
SRC="${1:-}"; LABEL="${2:-participant}"; shift 2 2>/dev/null || true
CASE="counter"
while [ $# -gt 0 ]; do
  case "$1" in
    --case) [ $# -ge 2 ] || { echo "--case needs a value" >&2; exit 2; }; CASE="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -f "$SRC" ] || { echo "usage: $0 <final-screen.kt> <label> [--case counter|cart]" >&2; exit 2; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
FIXDIR="$ROOT/portal-parity-fixtures/kotlin/dev/keliver/portal/render/fixture"
case "$CASE" in
  counter)
    FIX="$FIXDIR/M4CounterScreen.kt"
    CLASS="dev.keliver.portal.render.M4LabelUpdateCheck"
    TEST="summaryLabelReflectsStateAfterTransition"
    SCREEN_FN="HomeScreen"; BINDINGS="HomeScreenBindings"
    FIX_FN="M4CounterScreen"; FIX_BINDINGS="M4CounterBindings"
    PASS_MSG="summary label reflects state after addItem()" ;;
  cart)
    FIX="$FIXDIR/M4CartScreen.kt"
    CLASS="dev.keliver.portal.render.M4CartTotalCheck"
    TEST="totalIncludesShippingAfterAdd"
    SCREEN_FN="CartScreen"; BINDINGS="CartScreenBindings"
    FIX_FN="M4CartScreen"; FIX_BINDINGS="M4CartBindings"
    PASS_MSG="Total line reads subtotal + shipping after tapping Add item" ;;
  *) echo "unknown case: $CASE (counter|cart)" >&2; exit 2 ;;
esac
XML="$ROOT/portal-render/build/test-results/wasmJsBrowserTest/TEST-$CLASS.xml"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/m4-score-XXXXXX")"
BAK="$WORK/M4CounterScreen-orig.kt"
cp "$FIX" "$BAK"
[ -s "$BAK" ] || { echo "could not back up the fixture; refusing to modify it" >&2; exit 2; }
restore() { cp "$BAK" "$FIX"; cmp -s "$BAK" "$FIX" || echo "WARNING: fixture not restored — $FIX" >&2; }
trap restore EXIT

# Rename the participant's screen/bindings onto the fixture's names. The
# fixture package and the check are untouched.
python3 - "$SRC" "$FIX" "$BINDINGS" "$FIX_BINDINGS" "$SCREEN_FN" "$FIX_FN" <<'PY'
import re, sys
src, dst, bindings, fix_bindings, screen_fn, fix_fn = sys.argv[1:7]
s = open(src).read()
s = re.sub(r'^package .*$', 'package dev.keliver.portal.render.fixture', s, count=1, flags=re.M)
# bindings first: the screen name is a prefix of the bindings name
s = s.replace(bindings, fix_bindings).replace(screen_fn, fix_fn)
open(dst, 'w').write(s)
PY

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}"
export NODE_EXTRA_CA_CERTS="${NODE_EXTRA_CA_CERTS:-$HOME/.android-certs/full-ca-bundle.pem}"
LOG="$WORK/gradle.log"

# Freshness: no stale result can be mistaken for this run's.
rm -f "$XML"

# --tests is not supported on the wasmJs Karma task, so the whole target runs
# and the named test is selected out of the result XML afterwards.
( cd "$ROOT" && env -u KELIVER_USE_MAVEN_LOCAL ./gradlew :portal-render:wasmJsTest \
    --rerun-tasks --console=plain >"$LOG" 2>&1 )
GRADLE_RC=$?

verdict() { # VERDICT, detail, exit
  printf 'SCORE %-20s %s\n' "$LABEL:" "$1${2:+ — $2}"
  echo "  test: $CLASS.$TEST"
  echo "  log:  $LOG"
  exit "$3"
}

if [ ! -f "$XML" ]; then
  verdict "ERROR" "the check did not run (no fresh result for $CLASS; gradle exit $GRADLE_RC) — nothing is claimed about this participant$(
    grep -m1 -iE 'could not (install|download)|no such file|compilation error|error:|FAILURE:' "$LOG" \
      | sed 's/^/\n        first error: /')" 4
fi

OUT="$(python3 - "$XML" "$TEST" "$PASS_MSG" <<'PY'
import sys, xml.etree.ElementTree as ET
xml, test, pass_msg = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    root = ET.parse(xml).getroot()
except Exception as e:
    print(f"ERROR|could not parse the result file: {e}"); raise SystemExit(0)
cases = [c for c in root.iter('testcase') if c.get('name', '').startswith(test)]
if not cases:
    print(f"ERROR|{test} is absent from the fresh result file"); raise SystemExit(0)
for c in cases:
    err = c.find('error')
    if err is not None:
        print("ERROR|the test errored before asserting: " + (err.get('message') or '').strip()[:200]); raise SystemExit(0)
for c in cases:
    f = c.find('failure')
    if f is not None:
        print("FAIL|" + (f.get('message') or '').strip().splitlines()[0][:200]); raise SystemExit(0)
print("PASS|" + pass_msg)
PY
)"
STATUS="${OUT%%|*}"; DETAIL="${OUT#*|}"
case "$STATUS" in
  PASS)  [ "$GRADLE_RC" != 0 ] && DETAIL="$DETAIL (note: other tests in the target failed; this one passed)"
         verdict "PASS"  "$DETAIL" 0 ;;
  FAIL)  verdict "FAIL"  "$DETAIL" 1 ;;
  *)     verdict "ERROR" "$DETAIL — nothing is claimed about this participant" 4 ;;
esac
