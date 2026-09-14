#!/usr/bin/env bash
#
# keliver-refusal-check — the disposable-parent refusal, spelling by spelling.
#
#   scripts/keliver-refusal-check.sh <disposable-root>
#
# WHY THIS EXISTS. Ten scripts mint throwaway stores, public keys and signing
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

# keliver_protected_roots spawns a JVM to read user.home. Resolving it once and
# exporting it keeps every subshell below from doing so: measured, resetting the
# memo per case cost 9 seconds in a suite that is otherwise pure filesystem work.
KELIVER_JVM_HOME_MEMO="$(keliver_effective_jvm_home 2>/dev/null)"
[ -n "$KELIVER_JVM_HOME_MEMO" ] || KELIVER_JVM_HOME_MEMO="-"
export KELIVER_JVM_HOME_MEMO

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
    KELIVER_STAT_FMT=""
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
must_refuse "the filesystem root"              "$FH" "/"
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
# A relative PORTAL_STORE resolves against the CALLER's cwd, so this case has to
# control it: run from $DISP/other, where "." is not an ancestor of $DISP/legit.
# Measured: invoked from a directory that IS an ancestor, the guard refuses —
# correctly, and the assertion would have failed for the right reason in the
# wrong test.
printf '  ....  relative PORTAL_STORE, from a controlled directory\n'
rc=$( ( cd "$DISP/other" && export HOME="$FH" PORTAL_STORE="."
            keliver_refuse_protected_parent "$DISP/legit" >/dev/null 2>&1 ); echo $? )
[ "$rc" = 0 ] && ok "allowed: a relative PORTAL_STORE that is not an ancestor" \
              || bad "refused (rc=$rc) a legitimate parent under a relative PORTAL_STORE"
must_allow "a sibling of the store"            "$FH" "$FH/scratch"

echo "--- the whole path, through keliver_make_run_dir, asserting the END STATE"
# keliver_refuse_protected_parent contains no mkdir and no mktemp, so counting
# around IT could never fail however broken the guard was — measured, that
# assertion passed against a guard stubbed to `return 0`. The creation happens
# in keliver_make_run_dir, so the count has to be around that.
make_run_dir_rc() { # <home> <parent>
  ( export HOME="$1"; unset PORTAL_STORE
    keliver_make_run_dir "$2" probe >/dev/null 2>&1 )
  echo $?
}
state_of() { find "$FH" | LC_ALL=C sort; }

for spelling in \
  "$FH/.keliver-portal/apps/nope/deeper" \
  "$FH/nope3/../.keliver-portal" ; do
  BEFORE="$(state_of)"
  rc="$(make_run_dir_rc "$FH" "$spelling")"
  AFTER="$(state_of)"
  label="${spelling#"$FH"/}"
  [ "$rc" = 2 ] && ok "make_run_dir refuses: $label" \
                 || bad "make_run_dir ALLOWED (rc=$rc): $label"
  if [ "$BEFORE" = "$AFTER" ]; then
    ok "and left the filesystem exactly as it was: $label"
  else
    bad "it created something before refusing: $label"
    diff <(printf '%s\n' "$BEFORE") <(printf '%s\n' "$AFTER") | sed 's/^/        /'
  fi
done
# The one that matters most, stated separately: no run directory anywhere in the
# store, whatever spelling was used to reach it.
if find "$FH/.keliver-portal" -name 'keliver-probe-*' 2>/dev/null | grep -q .; then
  bad "a run directory was created INSIDE the protected store"
  find "$FH/.keliver-portal" -name 'keliver-probe-*' | sed 's/^/        /'
else
  ok "no run directory was created inside the protected store"
fi

# THE CASE THAT ACTUALLY LEAKED. It needs BOTH a case-insensitive filesystem and
# a home where the store does not exist yet:
#
#   * with the store present, a case-variant spelling resolves to it and the
#     FIRST refusal catches it before mkdir — which is why an earlier version of
#     this block, run against the leaking guard, passed and proved nothing;
#   * with the store absent there is no inode to compare, the name fallback is
#     case-sensitive and misses, and mkdir -p CREATES the store four levels deep
#     before the second refusal fires;
#   * on a case-SENSITIVE filesystem .KELIVER-PORTAL is simply a different
#     directory, so it is an ordinary parent and creating it is correct.
#
# Asserting the first answer on both is the mistake this suite already made once
# with the plain case-variant assertion, and Linux CI caught it. So: assert the
# real property for the filesystem in hand, and say which one ran.
FH2="$DISP/home-empty"
mkdir -p "$FH2"
BEFORE="$(find "$FH2" | LC_ALL=C sort)"
rc="$(make_run_dir_rc "$FH2" "$FH2/.KELIVER-PORTAL/apps/live/x")"
AFTER="$(find "$FH2" | LC_ALL=C sort)"
if [ "$CASE_FOLDING" = "insensitive" ]; then
  [ "$rc" = 2 ] && ok "make_run_dir refuses a case variant of a store that does not exist yet" \
                 || bad "make_run_dir ALLOWED (rc=$rc) a case variant of an absent store"
  if [ "$BEFORE" = "$AFTER" ]; then
    ok "and did not bring the store into existence on the way"
  else
    bad "it created the store while refusing to use it"
    diff <(printf '%s\n' "$BEFORE") <(printf '%s\n' "$AFTER") | sed 's/^/        /'
  fi
else
  [ "$rc" = 0 ] && ok "a case variant is an ordinary parent on a case-sensitive filesystem" \
                 || bad "refused (rc=$rc) an ordinary parent that merely resembles the store"
  if [ -d "$FH2/.keliver-portal" ]; then
    bad "using .KELIVER-PORTAL brought the real .keliver-portal into existence"
  else
    ok "and the real store was not created by using a similar-looking name"
  fi
fi
printf '        (the mkdir-then-refuse undo path is reachable only where the filesystem\n'
printf '         folds case; on this run the filesystem is case-%s)\n' "$CASE_FOLDING"

echo "--- EVERY exit undoes, not only the refusal"
# The undo used to run on the refusal path alone, so a mkdir that failed partway
# left what it had made — on a case-folding filesystem, the store. Three
# comments then claimed "every exit" while mktemp was still uncovered. Each exit
# gets its own case, and each is provoked by a different mechanism rather than
# by one trick that happens to hit them all.
undo_case() { # undo_case <label> <base> <parent> <setup-cmd...>
  local label="$1" base="$2" parent="$3"; shift 3
  local before after rc
  before="$(find "$base" 2>/dev/null | LC_ALL=C sort)"
  rc=$( ( export HOME="$FH3"; unset PORTAL_STORE; "$@"
          keliver_make_run_dir "$parent" probe >/dev/null 2>&1 ); echo $? )
  after="$(find "$base" 2>/dev/null | LC_ALL=C sort)"
  [ "$rc" != 0 ] && ok "$label: not accepted (rc=$rc)" \
                  || bad "$label: accepted when it should not have been"
  if [ "$before" = "$after" ]; then
    ok "$label: and nothing was left behind"
  else
    bad "$label: directories were left behind"
    diff <(printf '%s\n' "$before") <(printf '%s\n' "$after") | sed 's/^/        /'
  fi
}
FH3="$DISP/home-exits"
mkdir -p "$FH3/ro"
chmod a-w "$FH3/ro"
undo_case "mkdir cannot create it" "$FH3/ro" "$FH3/ro/a/b/c" true
chmod u+w "$FH3/ro"
mkdir -p "$FH3/mk"
undo_case "mktemp cannot create in it" "$FH3/mk" "$FH3/mk/a/b/c" umask 0222
mkdir -p "$FH3/cd"
undo_case "cd cannot enter it" "$FH3/cd" "$FH3/cd/a/b/c" umask 0777
# The same exits, reached through a spelling containing a `.` component. rmdir
# fails with EINVAL on a basename of `.`, which used to abort the undo at the
# first level — so the dot has to be exercised on a path that actually REACHES
# the undo, not on one the first refusal catches by name.
mkdir -p "$FH3/dot"
undo_case "mktemp cannot create in it, via a . component" \
  "$FH3/dot" "$FH3/dot/a/./b/c" umask 0222
mkdir -p "$FH3/dot2"
undo_case "cd cannot enter it, via a trailing ." \
  "$FH3/dot2" "$FH3/dot2/a/b/c/." umask 0777

# The umask 0777 cases leave directories this suite cannot later remove, which
# turns a failure into an unremovable evidence tree. Put the permissions back.
chmod -R u+rwx "$FH3" 2>/dev/null || true

echo "--- a . component must not defeat the REFUSAL either"
# These two are caught by the FIRST refusal (the store exists here, so the name
# fallback matches), which is why they are stated as a refusal property. The
# undo's own dot handling is covered by the two undo_case rows above, which
# reach it. `./scratch` is an ordinary thing to pass, so refusing `.` outright
# would be wrong; it is normalised away instead.
for spelling in \
  "$FH/.keliver-portal/apps/live/x/." \
  "$FH/.keliver-portal/apps/./live/x" ; do
  BEFORE="$(find "$FH" | LC_ALL=C sort)"
  rc="$(make_run_dir_rc "$FH" "$spelling")"
  AFTER="$(find "$FH" | LC_ALL=C sort)"
  label="${spelling#"$FH"/}"
  [ "$rc" = 2 ] && ok "refused, with a . component: $label" \
                 || bad "ALLOWED (rc=$rc) with a . component: $label"
  [ "$BEFORE" = "$AFTER" ] && ok "and left nothing behind: $label" \
                           || { bad "left something behind: $label"
                                diff <(printf '%s\n' "$BEFORE") <(printf '%s\n' "$AFTER") | sed 's/^/        /'; }
done

echo "--- and pre-existing content on the undo chain is never removed"
# The earlier version of this used a LEGITIMATE parent, so the undo was never
# invoked at all — instrumented, it ran twice in the whole suite and not once
# here. The parent has to be one that gets refused, with something already on
# the chain the undo walks.
FH4="$DISP/home-keep"
mkdir -p "$FH4/.keliver-portal/apps/occupied"
: > "$FH4/.keliver-portal/apps/occupied/file"
rc="$(make_run_dir_rc "$FH4" "$FH4/.keliver-portal/apps/occupied/new/deeper")"
[ "$rc" = 2 ] && ok "refused a parent under an occupied directory" \
               || bad "ALLOWED (rc=$rc) a parent under the store"
if [ -f "$FH4/.keliver-portal/apps/occupied/file" ]; then
  ok "and the pre-existing file and its directory survived the undo"
else
  bad "the undo removed content it did not create"
fi

echo "--- 'could not tell' is not 'different'"
# stat that answers for / but not for the candidate: keliver_same_dir must
# report unknown, and the caller must refuse rather than treat it as a mismatch.
rc=$( ( export HOME="$FH"; unset PORTAL_STORE
        KELIVER_STAT_FMT=""
        stat() {
          # `case` patterns inside $( ( ... ) ) confuse bash's parser here, so
          # this is an if.
          if [ "${3:-}" = "/" ]; then command stat "$@"; else return 1; fi
        }
        keliver_refuse_protected_parent "$FH/.keliver-portal" >/dev/null 2>&1 ); echo $? )
[ "$rc" = 2 ] && ok "a per-path stat failure refuses instead of reading as 'different'" \
               || bad "a per-path stat failure was read as 'different' (rc=$rc)"

echo "--- 'could not tell' at the HOME comparison too"
# The case above returns from the protected-roots loop. This one targets the
# home-directory arm, which had no coverage for its unknown branch: instrumented,
# it only ever logged 0 or 1 across the whole suite.
rc=$( ( export HOME="$DISP/home-plain"; unset PORTAL_STORE
        mkdir -p "$HOME"
        KELIVER_STAT_FMT=""
        stat() {
          if [ "${3:-}" = "/" ]; then command stat "$@"; else return 1; fi
        }
        keliver_refuse_protected_parent "$HOME" >/dev/null 2>&1 ); echo $? )
[ "$rc" = 2 ] && ok "an unprovable home-directory comparison refuses" \
               || bad "an unprovable home-directory comparison did not refuse (rc=$rc)"

echo "--- and a legitimate parent still gets a run directory"
LEGIT_RUN="$( export HOME="$FH"; unset PORTAL_STORE; keliver_make_run_dir "$DISP/legit" probe )"
if [ -n "$LEGIT_RUN" ] && [ -d "$LEGIT_RUN" ]; then
  ok "make_run_dir still creates one for a legitimate parent"
else
  bad "make_run_dir created nothing for a legitimate parent"
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
# Clean up on success; keep the tree on failure, where it is evidence.
if [ "$FAIL" = 0 ]; then
  rm -rf "$DISP"
else
  echo "evidence: $DISP"
fi
[ "$FAIL" = 0 ]
