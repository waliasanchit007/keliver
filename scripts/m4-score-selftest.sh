#!/usr/bin/env bash
#
# Regression coverage for m4-score-participant.sh.
#
#   scripts/m4-score-selftest.sh
#
# Requires the scorer to separate three outcomes that an earlier version
# collapsed into two:
#
#   * the reference fix                  -> PASS  (exit 0)
#   * the known defective screen         -> FAIL  (exit 1) on the ASSERTION
#   * Gradle unable to run at all        -> ERROR (exit 4), NOT a runtime failure
#   * a screen that does not compile     -> ERROR (exit 4), NOT a runtime failure
#   * a stale result from a previous run -> ERROR (exit 4), never inherited
#
# and to restore the fixture in every one of those cases.
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
SCORE="$ROOT/scripts/m4-score-participant.sh"
FIX="$ROOT/portal-parity-fixtures/kotlin/dev/keliver/portal/render/fixture/M4CounterScreen.kt"
XML="$ROOT/portal-render/build/test-results/wasmJsBrowserTest/TEST-dev.keliver.portal.render.M4LabelUpdateCheck.xml"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/m4-score-selftest-XXXXXX")"
pass=0; fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
chk() { if [ "$2" = "$3" ]; then ok "$1 (exit $3)"; else bad "$1 — expected exit $2, got $3"; fi; }

FIXSUM="$(shasum "$FIX" | awk '{print $1}')"
fixture_intact() {
  [ "$(shasum "$FIX" | awk '{print $1}')" = "$FIXSUM" ] \
    && ok "$1: fixture restored" || bad "$1: FIXTURE LEFT MODIFIED"
}

echo "m4-score-participant self-test"
echo "workdir: $WORK"

# the reference fix, and the defect, expressed against the app's own names
cat > "$WORK/reference-fix.kt" <<'EOK'
package basket.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledText

@Composable
fun HomeScreen(b: HomeScreenBindings) {
  Column {
    StyledText(text = "Basket", fontSize = 20, bold = true)
    StyledText(text = b.summary, fontSize = 14)
    Button(text = "Add item", onClick = { b.addItem() })
  }
}

interface HomeScreenBindings {
  val summary: String
  fun addItem()
}
EOK
sed 's/text = b\.summary/text = "0 items"/' "$WORK/reference-fix.kt" > "$WORK/defective.kt"
sed 's/StyledText(text = b\.summary, fontSize = 14)/this is not valid kotlin/' "$WORK/reference-fix.kt" > "$WORK/uncompilable.kt"

"$SCORE" "$WORK/reference-fix.kt" reference >"$WORK/ref.out" 2>&1
chk "reference fix scores PASS" 0 "$?"; fixture_intact "reference"

"$SCORE" "$WORK/defective.kt" defective >"$WORK/def.out" 2>&1
chk "known defect scores FAIL" 1 "$?"; fixture_intact "defective"
grep -q "REQUIREMENT" "$WORK/def.out" && ok "FAIL cites the assertion, not a generic build failure" \
                                      || bad "FAIL does not cite the assertion"

# INFRASTRUCTURE FAILURE with the KNOWN-GOOD input. The old scorer said
# "requirement not met at runtime" for code it never executed.
GRADLE_USER_HOME="$WORK/unusable-gradle-home" \
  "$SCORE" "$WORK/reference-fix.kt" infra >"$WORK/infra.out" 2>&1
chk "unusable Gradle scores ERROR, not FAIL" 4 "$?"; fixture_intact "infra"
grep -q "nothing is claimed about this participant" "$WORK/infra.out" \
  && ok "ERROR says explicitly that nothing is claimed" || bad "ERROR does not disclaim"
grep -q "requirement not met" "$WORK/infra.out" \
  && bad "ERROR still reports a runtime verdict" || ok "ERROR reports no runtime verdict"

"$SCORE" "$WORK/uncompilable.kt" uncompilable >"$WORK/nc.out" 2>&1
chk "uncompilable screen scores ERROR" 4 "$?"; fixture_intact "uncompilable"

# STALENESS: a leftover PASS from a previous run must not be inherited when the
# check does not run.
"$SCORE" "$WORK/reference-fix.kt" warmup >/dev/null 2>&1
[ -f "$XML" ] && ok "a passing run leaves a result file (staleness is possible)" \
              || bad "no result file to test staleness against"
GRADLE_USER_HOME="$WORK/unusable-gradle-home" \
  "$SCORE" "$WORK/reference-fix.kt" stale >"$WORK/stale.out" 2>&1
chk "a stale PASS is not inherited" 4 "$?"; fixture_intact "stale"

echo
echo "passed: $pass   failed: $fail"
echo "artifacts: $WORK"
[ "$fail" -eq 0 ]
