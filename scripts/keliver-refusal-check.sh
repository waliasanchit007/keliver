#!/usr/bin/env bash
#
# keliver-refusal-check — the disposable-parent refusal, spelling by spelling.
#
#   scripts/keliver-refusal-check.sh <disposable-root>
#
# WHY THIS EXISTS. Nine checks mint throwaway stores, public keys and signing
# keys beneath a parent directory the caller names. keliver_make_run_dir refuses
# a parent that lies inside the real portal store, the Gradle home or
# $PORTAL_STORE. That refusal FAILED OPEN in three consecutive reviewed commits:
#
#   * a parent whose own parent did not exist (empty canonicalisation),
#   * a case-variant spelling on macOS, where the filesystem is
#     case-insensitive and ~/.KELIVER-PORTAL is the same inode,
#   * a .. segment past a component that did not exist yet, which mkdir -p later
#     resolved into the store.
#
# Each was found by an independent review, and each was reachable by a ten-line
# harness that nobody had written. This is that harness.
#
# It never touches the real store: every case runs against a FAKE $HOME under
# the disposable root, and the assertions are about refusal, not about keys.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DISP_PARENT="${1:?usage: $0 <disposable-root>}"

# shellcheck source=/dev/null
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "$DISP_PARENT" refusal)" || exit $?

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '  PASS  %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '  FAIL  %s\n' "$1"; }

FH="$DISP/home"
mkdir -p "$FH/.keliver-portal/apps" "$FH/.gradle/caches" "$DISP/legit" "$DISP/other"
ln -s "$FH/.keliver-portal" "$DISP/symlink-to-store"
ln -s "$FH/.keliver-portal/does-not-exist" "$DISP/dangling"

echo "=== disposable-parent refusal"
echo "    fake home: $FH"

# Every case runs the refusal in a SUBSHELL with a fake HOME, so the real one is
# never consulted and nothing in this suite can write outside $DISP.
refuse_rc() { # refuse_rc <home> <parent> [extra-env...]
  local home="$1" parent="$2"; shift 2
  ( export HOME="$home"; unset PORTAL_STORE
    for kv in "$@"; do export "${kv?}"; done
    KELIVER_STAT_FMT=""; KELIVER_JVM_HOME_MEMO=""
    keliver_refuse_protected_parent "$parent" >/dev/null 2>&1 )
  echo $?
}

must_refuse() { # must_refuse <label> <home> <parent> [env...]
  local label="$1"; shift
  local rc; rc="$(refuse_rc "$@")"
  [ "$rc" = 2 ] && ok "refused: $label" || bad "ALLOWED (rc=$rc): $label"
}
must_allow() {
  local label="$1"; shift
  local rc; rc="$(refuse_rc "$@")"
  [ "$rc" = 0 ] && ok "allowed: $label" || bad "refused (rc=$rc) a legitimate parent: $label"
}

echo "--- spellings of the same protected directory"
must_refuse "the store itself"                 "$FH" "$FH/.keliver-portal"
must_refuse "a subdirectory of the store"      "$FH" "$FH/.keliver-portal/apps"
must_refuse "a path whose ancestor is missing" "$FH" "$FH/.keliver-portal/apps/nope/deeper"
# Case variants are a property of the FILESYSTEM, not of the guard, and the two
# platforms genuinely differ: on a case-insensitive filesystem (macOS default)
# .KELIVER-PORTAL IS the store — same device, same inode — and must be refused;
# on a case-sensitive one (Linux CI) it is a different directory that happens to
# look similar, and refusing it would be wrong. Asserting the macOS answer
# everywhere failed on Linux, correctly. So detect, then assert the real
# property in BOTH directions rather than skipping one of them.
touch "$FH/.keliver-CaseProbe"
if [ -e "$FH/.keliver-caseprobe" ]; then
  CASE_FOLDING="insensitive"
  must_refuse "a case variant, on a case-insensitive filesystem"   "$FH" "$FH/.KELIVER-PORTAL"
  must_refuse "a mixed-case variant + subdir, likewise"            "$FH" "$FH/.Keliver-Portal/apps"
else
  CASE_FOLDING="sensitive"
  must_allow  "a case variant, on a case-SENSITIVE filesystem"     "$FH" "$FH/.KELIVER-PORTAL"
  must_allow  "a mixed-case variant + subdir, likewise"            "$FH" "$FH/.Keliver-Portal/apps"
fi
rm -f "$FH/.keliver-CaseProbe"
printf '        (filesystem is case-%s; both directions are asserted, neither skipped)\n' \
  "$CASE_FOLDING"
must_refuse "a symlink pointing at the store"  "$FH" "$DISP/symlink-to-store"
must_refuse "through a symlink to the store"   "$FH" "$DISP/symlink-to-store/apps"
must_refuse ".. past an existing component"    "$FH" "$FH/.gradle/../.keliver-portal"
must_refuse ".. past a MISSING component"      "$FH" "$FH/nope/../.keliver-portal"
must_refuse "a trailing slash"                 "$FH" "$FH/.keliver-portal/"
must_refuse "the gradle home"                  "$FH" "$FH/.gradle/caches/x"
must_refuse "the home directory itself"        "$FH" "$FH"
must_refuse "a dangling symlink"               "$FH" "$DISP/dangling"

echo "--- environment shapes"
must_refuse "an unexpanded literal ~"          "$FH" '~/.keliver-portal'
must_refuse "PORTAL_STORE naming the parent"   "$FH" "$DISP/other" "PORTAL_STORE=$DISP/other"
must_refuse "PORTAL_STORE above the parent"    "$FH" "$DISP/other/x" "PORTAL_STORE=$DISP/other"
printf '  ....  HOME unset\n'
rc=$( ( unset HOME; unset PORTAL_STORE; KELIVER_STAT_FMT=""; KELIVER_JVM_HOME_MEMO=""
        keliver_refuse_protected_parent "$DISP/legit" >/dev/null 2>&1 ); echo $? )
[ "$rc" = 2 ] && ok "refused: HOME unset" || bad "ALLOWED (rc=$rc): HOME unset"

echo "--- legitimate parents must still work"
must_allow "an ordinary disposable directory"  "$FH" "$DISP/legit"
must_allow "one that does not exist yet"       "$FH" "$DISP/legit/not-yet/deeper"
must_allow "a relative PORTAL_STORE elsewhere" "$FH" "$DISP/legit" "PORTAL_STORE=."
must_allow "a sibling of the store"            "$FH" "$FH/scratch"

echo "--- the refusal must create nothing"
BEFORE="$(find "$FH" | wc -l | tr -d ' ')"
refuse_rc "$FH" "$FH/.keliver-portal/apps/nope/deeper" >/dev/null
refuse_rc "$FH" "$FH/nope2/../.keliver-portal" >/dev/null
AFTER="$(find "$FH" | wc -l | tr -d ' ')"
[ "$BEFORE" = "$AFTER" ] \
  && ok "nothing was created by a refused call ($BEFORE entries before and after)" \
  || { bad "a refused call created something ($BEFORE -> $AFTER)"; find "$FH" | sed 's/^/        /'; }

echo "--- and the whole path, through keliver_make_run_dir"
# The .. case is the one that got past the refusal and was then resolved into
# the store by mkdir -p, so assert on the END STATE, not only on the exit code.
rc=$( ( export HOME="$FH"; unset PORTAL_STORE
        KELIVER_STAT_FMT=""; KELIVER_JVM_HOME_MEMO=""
        keliver_make_run_dir "$FH/nope3/../.keliver-portal" probe >/dev/null 2>&1 ); echo $? )
[ "$rc" = 2 ] && ok "make_run_dir refuses the .. spelling" \
               || bad "make_run_dir ALLOWED the .. spelling (rc=$rc)"
if find "$FH/.keliver-portal" -maxdepth 1 -name 'keliver-probe-*' | grep -q .; then
  bad "a run directory was created INSIDE the protected store"
  find "$FH/.keliver-portal" -maxdepth 1 -name 'keliver-probe-*' | sed 's/^/        /'
else
  ok "no run directory was created inside the protected store"
fi

echo "--- stat must be able to prove identity, or the guard must refuse"
rc=$( ( export HOME="$FH"; unset PORTAL_STORE
        KELIVER_STAT_FMT="none"; KELIVER_JVM_HOME_MEMO="-"
        # keliver_stat_usable re-probes, so shadow stat itself.
        stat() { return 1; }
        keliver_refuse_protected_parent "$DISP/legit" >/dev/null 2>&1 ); echo $? )
[ "$rc" = 2 ] && ok "a stat that cannot answer refuses rather than matching names" \
               || bad "a broken stat degraded the guard to name matching (rc=$rc)"

echo
echo "passed: $PASS   failed: $FAIL"
[ "$FAIL" = 0 ]
