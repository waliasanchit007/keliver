#!/usr/bin/env bash
#
# Regression coverage for m4-isolation-preflight.sh.
#
#   scripts/m4-isolation-preflight-selftest.sh [--preflight PATH]
#   scripts/m4-isolation-preflight-selftest.sh --meta
#
# --meta runs the suite against a stub that reproduces the ORIGINAL destructive
# behaviour (sentinels written straight over reports/<cond>-report.txt) and
# requires the suite to reject it. A suite that cannot fail is not coverage.
#
# Builds a throwaway trial directory containing REAL-LOOKING participant
# reports, runs the preflight twice, and requires:
#
#   * both runs pass
#   * every pre-existing file under reports/ and evaluator/ is byte-identical
#     afterwards — the preflight used to overwrite reports/<cond>-report.txt
#     with its sentinels and silently destroy a completed trial
#   * no sentinel files are left behind anywhere
#   * the preflight still FAILS against a permissive profile (a check that
#     cannot fail is not a check)
#   * the preflight FAILS if something else modifies a report while it runs,
#     so the non-destructiveness assertion is itself load-bearing
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PF="$ROOT/scripts/m4-isolation-preflight.sh"
META=0
while [ $# -gt 0 ]; do
  case "$1" in
    --preflight) [ $# -ge 2 ] || { echo "--preflight needs a value" >&2; exit 2; }
                 PF="$2"; shift 2 ;;
    --meta)      META=1; shift ;;
    *)           echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

if [ "$META" = 1 ]; then
  MW="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/preflight-meta-XXXXXX")" && pwd -P)"
  # The original implementation, reduced to the part that mattered: it wrote
  # its sentinels directly onto the participant reports.
  cat > "$MW/destructive.sh" <<'EOS'
#!/usr/bin/env bash
T="$1"
S="SENTINEL-$(date +%s)-HARMLESS"
echo "$S report-baseline" > "$T/reports/baseline-report.txt"
echo "$S report-semantic" > "$T/reports/semantic-report.txt"
echo "passed: 18   failed: 0"
exit 0
EOS
  chmod +x "$MW/destructive.sh"
  "$0" --preflight "$MW/destructive.sh" > "$MW/destructive.out" 2>&1
  rc=$?
  if [ "$rc" != 0 ] && grep -q "a participant report was overwritten" "$MW/destructive.out"; then
    echo "  PASS  suite rejects the original destructive preflight"
    echo; echo "meta passed: 1   failed: 0"; echo "artifacts: $MW"; exit 0
  else
    echo "  FAIL  suite ACCEPTED the destructive preflight (rc=$rc)"
    grep -E "PASS|FAIL" "$MW/destructive.out" | head -10
    echo; echo "meta passed: 0   failed: 1"; echo "artifacts: $MW"; exit 1
  fi
fi
WORK="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/preflight-selftest-XXXXXX")" && pwd -P)"
pass=0; fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }

# A trial dir shaped like the real one. ws-*/src and the gradle wrapper are not
# needed: those checks are allowed to fail here, we assert on the report files
# and on the exit code of the permissive case only.
T="$WORK/trial"
mkdir -p "$T/reports" "$T/evaluator" "$T/ws-baseline/src" "$T/ws-semantic/src"
printf 'VERDICT: no\nDIAGNOSIS: real baseline report, must survive\n'  > "$T/reports/baseline-report.txt"
printf 'VERDICT: no\nDIAGNOSIS: real semantic report, must survive\n'  > "$T/reports/semantic-report.txt"
printf 'baseline exit=0 elapsed=124s\n' > "$T/reports/baseline-timing.txt"
printf 'the task\n'                     > "$T/evaluator/task.txt"
echo '{}' > "$T/ws-baseline/keliver.portal.json"
echo '{}' > "$T/ws-semantic/keliver.portal.json"

profile() { # cond
  cat > "$T/sandbox-$1.sb" <<EOF
(version 1)
(allow default)
(deny file-read* file-write*
  (subpath "$ROOT")
  (subpath "$WORK"))
(allow file-read-metadata (subpath "$WORK") (subpath "$ROOT"))
(allow file-read* file-write* (subpath "$T/ws-$1"))
EOF
}
profile baseline; profile semantic

snapshot() { find "$T/reports" "$T/evaluator" -type f -print0 | sort -z | xargs -0 shasum; }

BEFORE="$(snapshot)"
"$PF" "$T" --isolation-only > "$WORK/run1.log" 2>&1; rc1=$?
"$PF" "$T" --isolation-only > "$WORK/run2.log" 2>&1; rc2=$?
AFTER="$(snapshot)"

echo "m4-isolation-preflight self-test"
echo "workdir: $WORK"

[ "$rc1" = 0 ] && ok "first preflight passes"  || bad "first preflight failed (rc=$rc1)"
[ "$rc2" = 0 ] && ok "repeated preflight passes" || bad "repeated preflight failed (rc=$rc2)"

if [ "$BEFORE" = "$AFTER" ]; then
  ok "reports/ and evaluator/ byte-identical after two preflights"
else
  bad "preflight MODIFIED existing artifacts"
  diff <(printf '%s\n' "$BEFORE") <(printf '%s\n' "$AFTER") | head -10
fi

if grep -q "must survive" "$T/reports/baseline-report.txt" &&
   grep -q "must survive" "$T/reports/semantic-report.txt"; then
  ok "both participant reports still hold their original content"
else
  bad "a participant report was overwritten"
fi

LEFT="$(find "$T" "$WORK" "$ROOT" -maxdepth 2 -name '.preflight-sentinel-*' 2>/dev/null | head -5)"
[ -z "$LEFT" ] && ok "no sentinel files left behind" || bad "sentinels left behind: $LEFT"

# the preflight must still be able to fail: a profile that denies nothing
cp "$T/sandbox-semantic.sb" "$WORK/semantic.bak"
printf '(version 1)\n(allow default)\n' > "$T/sandbox-semantic.sb"
"$PF" "$T" --isolation-only > "$WORK/permissive.log" 2>&1; rcp=$?
cp "$WORK/semantic.bak" "$T/sandbox-semantic.sb"
leaks="$(grep -c 'READ SUCCEEDED (leak)' "$WORK/permissive.log")"
if [ "$rcp" != 0 ] && [ "${leaks:-0}" -ge 1 ]; then
  ok "permissive profile is rejected ($leaks leak(s) reported)"
else
  bad "permissive profile was NOT rejected (rc=$rcp, leaks=${leaks:-0})"
fi

# The non-destructiveness assertion must itself be able to fail. A saboteur
# rewrites the report with DIFFERENT content on every tick for the duration of
# the run, so whenever the preflight takes its "before" snapshot, a later write
# must change the checksum.
cat > "$WORK/saboteur.sh" <<EOS
#!/usr/bin/env bash
touch "$WORK/saboteur-ready"
for i in \$(seq 1 60); do
  echo "clobbered \$i" > "$T/reports/baseline-report.txt"
  sleep 0.1
done
EOS
chmod +x "$WORK/saboteur.sh"
cp "$T/reports/baseline-report.txt" "$WORK/baseline.bak"
rm -f "$WORK/saboteur-ready"
"$WORK/saboteur.sh" & sab=$!
until [ -e "$WORK/saboteur-ready" ]; do sleep 0.05; done
"$PF" "$T" --isolation-only > "$WORK/sabotage.log" 2>&1; rcs=$?
kill "$sab" 2>/dev/null; wait "$sab" 2>/dev/null
if [ "$rcs" != 0 ] && grep -q "WAS MODIFIED by this preflight" "$WORK/sabotage.log"; then
  ok "a report modified during the run is detected and fails the preflight"
else
  bad "report modification during the run went undetected (rc=$rcs)"
fi
cp "$WORK/baseline.bak" "$T/reports/baseline-report.txt"

echo
echo "passed: $pass   failed: $fail"
echo "artifacts: $WORK"
[ "$fail" -eq 0 ]
