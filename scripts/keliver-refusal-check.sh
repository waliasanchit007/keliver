#!/usr/bin/env bash
#
# keliver-refusal-check — the disposable-parent refusal, spelling by spelling.
#
#   scripts/keliver-refusal-check.sh <disposable-root>
#
# WHY THIS EXISTS. Eleven scripts mint throwaway stores, public keys and signing
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

# This suite used to resolve the JVM user.home once and hand it to every subshell
# through KELIVER_JVM_HOME_MEMO, to avoid ~90 JVM starts. That variable is gone:
# it was the hole. A caller-settable global decided whether a protected root
# existed, and the suite was the caller — so the very assertions below ran with
# the root they were testing switched off. The guard now asks java per call and
# the suite pays the seconds.
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
rc=$( ( unset HOME; unset PORTAL_STORE; KELIVER_STAT_FMT=""
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
undo_case "mkdir cannot create it at all" "$FH3/ro" "$FH3/ro/a/b/c" true
chmod u+w "$FH3/ro"
# A read-only parent makes mkdir fail at the FIRST level, so it creates nothing
# and the end-state assertion cannot fail. The leak this exit is about is a
# mkdir that fails PARTWAY, which needs a component the filesystem will not
# accept. Both cases, because replacing one with the other left the partial
# case uncovered.
mkdir -p "$FH3/partial"
undo_case "mkdir fails partway through" "$FH3/partial" \
  "$FH3/partial/a/$(printf 'x%.0s' $(seq 1 300))/c" true
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

echo "--- the undo removes only empty directories, and says when it cannot"
# Tested by calling keliver_undo_created DIRECTLY. Two earlier attempts at this
# went through keliver_make_run_dir and never reached the undo at all — the
# first used a legitimate parent, the second one the FIRST refusal catches
# before any recording happens. Instrumented, the undo ran six times in the
# whole suite and not once in either block. And by construction the recorded
# list holds only levels that did not exist, so "pre-existing content on the
# chain" cannot be produced through make_run_dir; the contract worth asserting
# is rmdir-not-rm-rf, which is a property of the helper.
U="$DISP/undo-unit"
mkdir -p "$U/a/b/c"
: > "$U/a/b/keepme"
( keliver_undo_created "$U/a/b/c" "$U/a/b" "$U/a" ) > "$DISP/undo.log" 2>&1
if [ -f "$U/a/b/keepme" ] && [ -d "$U/a/b" ]; then
  ok "it stopped at a directory that was not empty"
else
  bad "it removed a directory that had contents"
fi
[ -d "$U/a/b/c" ] && bad "it did not remove the empty directory it was given" \
                  || ok "and it did remove the empty one below it"
grep -q "could not remove" "$DISP/undo.log" \
  && ok "and it said so rather than reporting a clean removal" \
  || { bad "it stopped silently"; cat "$DISP/undo.log" | sed 's/^/        /'; }

echo "--- an already-existing parent records nothing, and that must not be fatal"
# keliver_created is empty whenever the parent already exists. Expanding an
# empty array under `set -u` is FATAL on bash 3.2, which is /bin/bash on macOS —
# the platform the local pre-gate and the device checks run on. It kills the
# shell rather than returning, so the caller never sees a status.
FH5="$DISP/home-exists"
mkdir -p "$FH5/ro"
chmod a-w "$FH5/ro"
out="$( ( export HOME="$FH5"; unset PORTAL_STORE
          keliver_make_run_dir "$FH5/ro" probe 2>&1 >/dev/null ); echo "rc=$?" )"
chmod u+w "$FH5/ro"
case "$out" in
  *"unbound variable"*) bad "an empty record killed the shell: $out";;
  *rc=0*) bad "mktemp succeeded in a read-only directory, so this proves nothing";;
  *) ok "an already-existing parent that mktemp cannot use returns, not crashes";;
esac

echo "--- .. through a symlink is refused, and agrees with the kernel"
# Bash's cd is LOGICAL by default: it cancels link/.. textually, so a .. that
# traverses a symlink was gone before the .. check looked, and the guard's
# answer disagreed with realpath's. MEASURED, that let a signing key be minted
# under the Gradle home.
SYMH="$DISP/home-symlink"
mkdir -p "$SYMH/.gradle/caches" "$SYMH/safe"
ln -s "$SYMH/.gradle/caches" "$SYMH/safe/link"
rc="$(refuse_rc "$SYMH" "$SYMH/safe/link/../keys")"
[ "$rc" = 2 ] && ok "refused: .. traversing a symlink into a protected tree" \
               || bad "ALLOWED (rc=$rc): .. traversing a symlink into a protected tree"
GOT="$( ( keliver_abs_of "$SYMH/safe/link/../keys" ) )"
WANT="$(python3 -c "import os,sys;print(os.path.realpath(sys.argv[1]))" "$SYMH/safe/link/../keys")"
[ "$GOT" = "$WANT" ] && ok "and the guard's resolution agrees with the kernel's" \
                     || bad "guard said $GOT, kernel said $WANT"

# A .. traversing a symlink into a HARMLESS tree. Only the given-spelling check
# can refuse this one: the resolved path lands nowhere protected, so the
# protected-root match cannot fire. Mutation-tested — deleting that check left
# the whole suite passing, which made it unfalsifiable and the next refactor's
# free deletion.
mkdir -p "$SYMH/elsewhere"
ln -s "$SYMH/elsewhere" "$SYMH/safe/harmless"
rc="$(refuse_rc "$SYMH" "$SYMH/safe/harmless/../keys")"
[ "$rc" = 2 ] && ok "refused: .. traversing a symlink, even somewhere harmless" \
               || bad "ALLOWED (rc=$rc): .. traversing a symlink somewhere harmless"

# A symlink to / puts a DOUBLED leading slash in pwd -P's answer, and every
# name-prefix comparison downstream then fails to match. MEASURED, that let a
# parent inside the Gradle home through, and the one caller with no second
# refusal created its store there.
mkdir -p "$SYMH/root"
ln -s / "$SYMH/root/R"
# The protected subpath must NOT exist, or the identity walk catches it before
# the name comparison and the assertion proves nothing — measured, that is why
# an earlier version of this case passed against the guard it was written for.
ROOTSPELL="$SYMH/root/R$SYMH/.keliver-portal/apps/evil"
rc="$(refuse_rc "$SYMH" "$ROOTSPELL")"
[ "$rc" = 2 ] && ok "refused: a protected tree reached through a symlink to /" \
               || bad "ALLOWED (rc=$rc): a protected tree reached through a symlink to /"
GOT="$( ( keliver_abs_of "$ROOTSPELL" ) )"
WANT="$(python3 -c "import os,sys;print(os.path.realpath(sys.argv[1]))" "$ROOTSPELL")"
[ "$GOT" = "$WANT" ] && ok "and no doubled leading slash survives into the answer" \
                     || bad "guard said $GOT, kernel said $WANT"

echo "--- .. is refused outright, which is a policy and not an accident"
# Every .. spelling is refused, including one that reaches nowhere near a
# protected tree. That is deliberate — .. cannot be resolved against a directory
# that does not exist yet — but it is a tightening, so it is pinned rather than
# left to be rediscovered as a bug.
must_refuse "an ordinary relative parent containing .." "$FH" "$DISP/legit/../legit2"
must_refuse "the ../scratch spelling the docs quote"  "$FH" "$DISP/legit/../scratch"

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
        KELIVER_STAT_FMT="none"
        # keliver_stat_usable re-probes, so shadow stat itself.
        stat() { return 1; }
        keliver_refuse_protected_parent "$DISP/legit" >/dev/null 2>&1 ); echo $? )
[ "$rc" = 2 ] && ok "a stat that cannot answer refuses rather than matching names" \
               || bad "a broken stat degraded the guard to name matching (rc=$rc)"

echo "--- the PROTECTED side must be resolved too, not just the candidate"
# The candidate path is normalised and symlink-resolved; the roots were not, so
# the name comparison had one resolved operand and one raw one. Every row here
# was measured ALLOWED before that was fixed, and each needs the protected
# subtree to be ABSENT (with it present the identity walk catches it and the
# assertion proves nothing) or the root itself to be a symlink.
PS="$DISP/protected-side"
mkdir -p "$PS/realhome" "$PS/realgradle" "$PS/elsewherestore" "$PS/dslash/h"
ln -s "$PS/realhome" "$PS/linkhome"
mkdir -p "$PS/home2"; ln -s "$PS/realgradle" "$PS/home2/.gradle"
mkdir -p "$PS/home3"; ln -s "$PS/elsewherestore" "$PS/home3/.keliver-portal"
must_refuse "HOME spelled with a doubled slash"      "/$PS/dslash/h" "$PS/dslash/h/.gradle/caches/evil"
must_refuse "HOME reached through a symlink (gradle)" "$PS/linkhome" "$PS/realhome/.gradle/caches/evil"
must_refuse "HOME reached through a symlink (store)"  "$PS/linkhome" "$PS/realhome/.keliver-portal/apps/evil"
must_refuse "a ~/.gradle that is itself a symlink"    "$PS/home2"    "$PS/realgradle/caches/evil"
must_refuse "a ~/.keliver-portal that is a symlink"   "$PS/home3"    "$PS/elsewherestore/apps"
must_refuse "the home directory itself, via a symlink" "$PS/linkhome" "$PS/realhome"
must_allow  "an ordinary parent beside a symlinked home" "$PS/linkhome" "$PS/scratch"

must_refuse "PORTAL_STORE with an unexpanded ~"      "$PS/realhome" "$PS/scratch2" "PORTAL_STORE=~/store"
# ~user/... is the same unexpanded tilde one character along, and it walked
# straight through the check added for ~/store.
must_refuse "PORTAL_STORE with an unexpanded ~user" "$PS/realhome" "$PS/scratch2" "PORTAL_STORE=~someuser/store"
must_refuse "a parent argument spelled ~user"       "$PS/realhome" "~someuser/store"
# A PORTAL_STORE of / has no useful answer either: protect it and nothing can
# run, skip it and the named store is unprotected. It was a silent skip.
must_refuse "PORTAL_STORE that is the filesystem root" "$PS/realhome" "$PS/scratch2" "PORTAL_STORE=/"
# HOME naming a directory that does not exist yet: keliver_same_dir's [ -d ]
# returns 1 there, so the string comparison is the only thing that refuses.
# Mutation-tested — without it, and without resolving $HOME first, this is the
# case that gets through while the symlinked-home row still passes on stat -L.
must_refuse "the home directory itself, not yet created" "$PS/nohome" "$PS/nohome"

echo "--- a protected root that resolves to / has no useful answer"
# Protect it and every directory is inside it; skip it and the one tree that
# must be protected is not. It must say which, and refuse.
ROOTSLASH="$DISP/home-rootslash"
mkdir -p "$ROOTSLASH"
ln -s / "$ROOTSLASH/.gradle"
rc="$(refuse_rc "$ROOTSLASH" "$DISP/legit")"
[ "$rc" = 2 ] && ok "refused, rather than denying everything in silence" \
               || bad "a root resolving to / gave rc=$rc"
( export HOME="$ROOTSLASH"; unset PORTAL_STORE; KELIVER_STAT_FMT=""
  keliver_refuse_protected_parent "$DISP/legit" >/dev/null 2>"$DISP/rootslash.err" )
if grep -q "resolves to the filesystem root" "$DISP/rootslash.err"; then
  ok "and said which root it was"
else
  bad "it refused without saying why"
  head -3 "$DISP/rootslash.err" | sed 's/^/        /'
fi

echo "--- no caller-settable variable may switch a protected root off"
# THE ELEVENTH AND TWELFTH WAYS IN, and the reason the mechanism is gone rather
# than guarded. The JVM's user.home is a protected root BECAUSE $HOME is not
# trusted — on macOS they differ. It used to be cached in KELIVER_JVM_HOME_MEMO,
# with KELIVER_JVM_HOME_TRIED recording that java had been asked. Both were
# wiped at source time, so an exported value could not reach them, but either
# could be assigned after sourcing — which is what this suite did for speed.
#
# The round before this one validated the MEMO and left the FLAG alone, and
# validated it for shape (absolute, and a directory that exists) rather than for
# provenance. Two shapes survived, each measured against that commit, each
# returning 0 on a parent inside the protected root AND creating two directories
# there:
#
#   KELIVER_JVM_HOME_TRIED=1            java is never asked, the root is absent
#   KELIVER_JVM_HOME_MEMO=<existing dir> honoured — and an honoured memo REPLACES
#                                        the JVM root rather than adding to it
#
# The previous version of this block asserted rc only, against $HOME/.keliver-portal
# — the developer's REAL store — and began each case with `unset
# KELIVER_JVM_HOME_TRIED`, which is precisely what kept it from seeing the flag.
# It is a DISPOSABLE fake user.home now, produced by a stub `java` on PATH, which
# is what makes it safe to drive keliver_make_run_dir here and assert the whole
# property: refuses, AND leaves the tree byte-identical.
JH="$DISP/jvmhome"
mkdir -p "$JH/.keliver-portal/apps" "$JH/.gradle" "$DISP/stubbin" "$DISP/jvmhome-home"
cat > "$DISP/stubbin/java" <<STUB
#!/bin/sh
echo "        user.home = $JH"
STUB
chmod +x "$DISP/stubbin/java"

# cache_case <label> <post-source-assignments> <want-rc>
# The assignments run AFTER the guard is sourced — the only vector that ever
# reached these variables — and nothing is unset on the way in.
cache_case() {
  local label="$1" assign="$2" want="$3" before after rc1 rc2
  local victim="$JH/.keliver-portal/apps/evil"
  before="$(find "$JH" | sort)"
  # SENTINEL FIRST. An assignment like `unset KELIVER_STAT_FMT` can make the
  # subshell die on an unbound expansion under set -u, and a dead subshell never
  # reaches its `echo $?` — so these files would still hold the PREVIOUS case's
  # status and the assertion would quietly grade the wrong run. Caught by this
  # suite reporting rc=0/0 for a case whose subshell had in fact been killed.
  # "died" is not 2, so a death still fails the assertion, which is correct: a
  # refusal that aborts reads to the caller exactly like an allowed one.
  echo died > "$DISP/cc.rc1"; echo died > "$DISP/cc.rc2"
  ( export PATH="$DISP/stubbin:$PATH" HOME="$DISP/jvmhome-home"; unset PORTAL_STORE
    eval "$assign"
    keliver_refuse_protected_parent "$victim" >/dev/null 2>&1; echo $? > "$DISP/cc.rc1"
    keliver_make_run_dir "$victim" probe >/dev/null 2>&1; echo $? > "$DISP/cc.rc2" )
  rc1="$(cat "$DISP/cc.rc1")"; rc2="$(cat "$DISP/cc.rc2")"
  after="$(find "$JH" | sort)"
  if [ "$rc1" = "$want" ] && [ "$rc2" = "$want" ]; then
    ok "$label"
  else
    bad "$label (refuse rc=$rc1, make_run_dir rc=$rc2, wanted $want)"
  fi
  # The property is BOTH halves: it must refuse, and the refusal must not write.
  if [ "$before" = "$after" ]; then
    ok "$label: and the protected tree is byte-identical"
  else
    bad "$label: it WROTE inside the protected root"
    diff <(echo "$before") <(echo "$after") | sed 's/^/        /'
  fi
  rm -rf "$victim"
}

cache_case "an inherited TRIED flag does not unprotect the JVM home" \
  "KELIVER_JVM_HOME_TRIED=1" 2
cache_case "nor a memo naming a directory that really exists" \
  "KELIVER_JVM_HOME_MEMO=\"$DISP/legit\"" 2
cache_case "nor both of them together" \
  "KELIVER_JVM_HOME_TRIED=1; KELIVER_JVM_HOME_MEMO=\"$DISP/legit\"" 2
cache_case "nor the '-' marker the tenth round was about" \
  "KELIVER_JVM_HOME_MEMO=-" 2
cache_case "nor one naming a directory that does not exist" \
  "KELIVER_JVM_HOME_MEMO=/nonexistent-keliver-probe" 2
cache_case "nor a relative one" \
  "KELIVER_JVM_HOME_MEMO=relative/path" 2
cache_case "nor either of them exported into the environment" \
  "export KELIVER_JVM_HOME_TRIED=1 KELIVER_JVM_HOME_MEMO=\"$DISP/legit\"" 2
# Not a path variable, but the same shape: a cache an unset can turn into a fatal
# expansion under set -u, which kills the subshell of a command substitution and
# reads to the caller as an empty answer rather than a refusal.
cache_case "nor unsetting the stat-format cache under set -u" \
  "unset KELIVER_STAT_FMT" 2
# ...and none of that may cost the legitimate case its rc=0.
rc=$( ( export PATH="$DISP/stubbin:$PATH" HOME="$DISP/jvmhome-home"; unset PORTAL_STORE
        KELIVER_JVM_HOME_TRIED=1
        keliver_refuse_protected_parent "$DISP/legit" >/dev/null 2>&1 ); echo $? )
[ "$rc" = 0 ] && ok "while a legitimate parent is still allowed" \
              || bad "a legitimate parent was refused (rc=$rc)"
# The JVM root is only worth protecting if it is genuinely SEPARATE from $HOME.
# If the stub were ignored this whole block would be asserting nothing, so prove
# the geometry it depends on: the victim is outside HOME, and still refused.
case "$JH" in
  "$DISP/jvmhome-home"/*) bad "the fake user.home is inside the fake HOME — these cases prove nothing";;
  *) ok "the fake user.home is outside the fake HOME, so these cases need the JVM root";;
esac

echo "--- discovery of the JVM user.home must SUCCEED, or the guard must refuse"
# THE EIGHTEENTH REVIEW'S FINDING, and the first that is not about a variable or
# a path spelling: it is about a LOST EXIT STATUS.
#
# keliver_effective_jvm_home was one pipeline, `java ... | awk ...`, so the
# command substitution carried AWK's status — and awk succeeds when it matches
# nothing. "java is not installed", "java crashed", "java printed no user.home"
# and a real answer were therefore indistinguishable to every caller: rc=0 and an
# empty string. keliver_protected_roots read the empty string as "there is no JVM
# root" and continued with the $HOME roots alone, which is the fallback the JVM
# root exists to avoid.
#
# MEASURED against 53ed0637d, with HOME on a disposable directory and the store
# under a DIFFERENT user.home — the macOS geometry, and the only geometry where
# this root matters — every one of these returned 0 AND created directories
# inside the protected store.
#
# Fixtures are a stub `java` on a controlled PATH, and for the absence case a
# PATH holding symlinks to the tools the guard needs and no java at all. Nothing
# real is consulted: $HOME here is a disposable directory too.
JB="$DISP/javabin"; mkdir -p "$JB"
JPURE="$DISP/purebin"; mkdir -p "$JPURE"
for t in awk basename dirname find head id mkdir mktemp rmdir sed stat; do
  src="$(command -v "$t" 2>/dev/null)" && ln -sf "$src" "$JPURE/$t"
done
# If that PATH cannot run the guard at all, the absence case would "refuse" for
# a reason that has nothing to do with java, and would prove nothing.
# EVERY tool, not two of them. Asserting only stat+mktemp was the same
# fix-one-operand error this file keeps recording: demonstrated, a PATH missing
# only `basename` still makes the guard return 2, for a reason that has nothing
# to do with java — so the absence row would have passed for the wrong reason if
# one ln -sf had quietly failed. The rows themselves also assert WHY they
# refused, below, which is the real backstop.
JPURE_OK=1
for t in awk basename dirname head mkdir mktemp rmdir sed stat; do
  ( export PATH="$JPURE"; command -v "$t" >/dev/null 2>&1 ) || { JPURE_OK=0; echo "        missing: $t"; }
done
( export PATH="$JPURE"; command -v java >/dev/null 2>&1 ) && JPURE_OK=0
[ "$JPURE_OK" = 1 ] && ok "the java-free fixture PATH has every tool the guard needs, and no java" \
                    || bad "the java-free fixture PATH is wrong, so the absence case proves nothing"

# java_case <label> <stub-body | ABSENT> <target> <want-rc> [reason-grep]
# Asserts BOTH halves, like cache_case: the status, and that the protected trees
# are byte-identical afterwards — plus, where a reason is given, that it refused
# for the reason under test rather than some unrelated one.
#
# BOTH protected trees. This watched only $JH, while two rows in the block point
# elsewhere — one at the $HOME tree, one at a legitimate parent — so for those
# two the "byte-identical" PASS asserted nothing at all. That is the same
# fix-one-operand-not-its-partner error this file exists to record, committed in
# the very assertions written to catch it. $DISP/legit is deliberately NOT
# watched: the allowed row is supposed to create a run directory there.
java_case() {
  local label="$1" body="$2" target="$3" want="$4" reason="${5:-}"
  local pathspec before after rc1 rc2
  if [ "$body" = "ABSENT" ]; then
    pathspec="$JPURE"
  else
    printf '#!/bin/sh\n%s\n' "$body" > "$JB/java"; chmod +x "$JB/java"
    pathspec="$JB:$JPURE"
  fi
  before="$(find "$JH" "$DISP/jvmhome-home" | sort)"
  echo died > "$DISP/jc.rc1"; echo died > "$DISP/jc.rc2"; : > "$DISP/jc.err"
  ( export PATH="$pathspec" HOME="$DISP/jvmhome-home"; unset PORTAL_STORE
    keliver_refuse_protected_parent "$target" >/dev/null 2>"$DISP/jc.err"; echo $? > "$DISP/jc.rc1"
    keliver_make_run_dir "$target" probe >/dev/null 2>&1; echo $? > "$DISP/jc.rc2" )
  rc1="$(cat "$DISP/jc.rc1")"; rc2="$(cat "$DISP/jc.rc2")"
  after="$(find "$JH" "$DISP/jvmhome-home" | sort)"
  if [ "$rc1" = "$want" ] && [ "$rc2" = "$want" ]; then
    ok "$label"
  else
    bad "$label (refuse rc=$rc1, make_run_dir rc=$rc2, wanted $want)"
  fi
  if [ "$before" = "$after" ]; then
    ok "$label: and both protected trees are byte-identical"
  else
    bad "$label: it WROTE inside a protected root"
    diff <(echo "$before") <(echo "$after") | sed 's/^/        /'
  fi
  # THE REASON, not just the status. A refusal is cheap to get by accident — a
  # fixture PATH missing one unrelated tool produces rc=2 too — so the rows that
  # are about discovery assert that discovery is what was named.
  if [ -n "$reason" ]; then
    if grep -q "$reason" "$DISP/jc.err"; then
      ok "$label: and named discovery as the reason"
    else
      bad "$label: refused, but not for the reason under test"
      head -3 "$DISP/jc.err" | sed 's/^/        /'
    fi
  fi
  # Derived from $target, not hardcoded: the hardcoded form left the $HOME-tree
  # row's directory behind for the next row's snapshot to trip over.
  rm -rf "$target"/keliver-probe-* 2>/dev/null
  case "$target" in
    "$JH"/*|"$DISP/jvmhome-home"/*) rm -rf "$target";;
  esac
}

VICTIM="$JH/.keliver-portal/apps/evil"
# The message every discovery failure must carry. The stub that exits 1 prints
# "no libjvm here", deliberately NOT containing this text, so the assertion
# cannot be satisfied by the stub's own output being echoed back.
WHY="could not be established"
java_case "java exits 127"                       'exit 127'                                "$VICTIM" 2 "$WHY"
java_case "java exits 1 with an error message"   'echo "no libjvm here" >&2; exit 1'        "$VICTIM" 2 "$WHY"
java_case "java prints no user.home line"        'echo "        java.version = 17"'         "$VICTIM" 2 "$WHY"
java_case "java prints an EMPTY user.home"       'echo "        user.home = "'              "$VICTIM" 2 "$WHY"
java_case "java prints a RELATIVE user.home"     'echo "        user.home = relative/nope"' "$VICTIM" 2 "$WHY"
java_case "java prints TWO different user.homes" \
  'echo "        user.home = /one"; echo "        user.home = /two"'                        "$VICTIM" 2 "$WHY"
java_case "java is absent from PATH entirely"    'ABSENT'                                   "$VICTIM" 2 "$WHY"

# VALID discovery, in the geometry that matters: HOME and user.home differ, and
# the store lives under user.home. The root has to come from java or not at all.
java_case "valid discovery protects a store under user.home" \
  "echo \"        user.home = $JH\"" "$VICTIM" 2
# ...and the control that makes every row above mean something. Without it, a
# guard that refused unconditionally would pass the whole block. MEASURED: it
# did — naming a local variable `status`, which is a read-only alias for $? in
# zsh, aborted the function, every caller read that as "discovery failed", and
# the first run of these assertions was green for exactly that reason. This row
# was the only one that caught it.
java_case "and a legitimate parent is still ALLOWED" \
  "echo \"        user.home = $JH\"" "$DISP/legit" 0
# The other direction of the same control: with discovery working, the $HOME
# roots must still bite, so a pass here is not "java answered, therefore allow".
java_case "while a store under HOME is still refused" \
  "echo \"        user.home = $JH\"" "$DISP/jvmhome-home/.keliver-portal/x" 2
rm -f "$JB/java"

echo "--- a path named after the old in-band marker is not a diagnosis"
# The "protected set is unusable" signal used to be a string in the data, so a
# directory named after it produced a refusal with a false explanation while the
# function reported success and a truncated list. It is a return status now.
MARKDIR="$DISP/KELIVER_PROTECTED_ROOTS_UNUSABLE/store"
mkdir -p "$MARKDIR"
rc=$( ( export HOME="$PS/realhome" PORTAL_STORE="$MARKDIR"; KELIVER_STAT_FMT=""
        keliver_refuse_protected_parent "$DISP/legit" >/dev/null 2>&1 ); echo $? )
[ "$rc" = 0 ] && ok "a path containing the old marker text is just a path" \
              || bad "a path named after the marker was read as a failure (rc=$rc)"

echo "--- a symlink to a directory is the same directory"
# stat follows no symlinks without -L while [ -d ] does, so keliver_same_dir
# compared the LINK's inode and answered "provably different" about one and the
# same directory — worse than the unknown case, because the caller acts on it.
mkdir -p "$DISP/samedir/real"
ln -s "$DISP/samedir/real" "$DISP/samedir/link"
( KELIVER_STAT_FMT=""; keliver_same_dir "$DISP/samedir/real" "$DISP/samedir/link" )
case $? in
  0) ok "a directory and a symlink to it compare as the same directory";;
  1) bad "a directory and a symlink to it compared as provably DIFFERENT";;
  *) bad "the comparison could not tell, where it should have been certain";;
esac

echo "--- the one script with no second refusal is pinned too"
# keliver-verify-signed-bundle.sh calls the refusal directly and has no
# keliver_make_run_dir behind it, so every leak found in this guard has landed
# there first. Nothing tested it: grepped, it appears in no workflow and in no
# check. It exits before any Gradle work, so asserting on it is cheap.
VSB="$ROOT/scripts/keliver-verify-signed-bundle.sh"
VSBH="$DISP/home-vsb"
mkdir -p "$VSBH/.gradle/caches" "$VSBH/root"
ln -s / "$VSBH/root/R"
for spelling in \
  "$VSBH/.keliver-portal" \
  "$VSBH/.gradle/caches/x" \
  "$VSBH/root/R$VSBH/.keliver-portal/apps/evil" ; do
  before="$(find "$VSBH" | LC_ALL=C sort)"
  ( export HOME="$VSBH"; unset PORTAL_STORE; "$VSB" "$spelling" ) >/dev/null 2>&1
  rc=$?
  after="$(find "$VSBH" | LC_ALL=C sort)"
  label="${spelling#"$VSBH"/}"
  [ "$rc" = 2 ] && ok "verify-signed-bundle refuses: $label" \
                 || bad "verify-signed-bundle ALLOWED (rc=$rc): $label"
  [ "$before" = "$after" ] && ok "and created nothing: $label" \
                           || { bad "it created something: $label"
                                diff <(printf '%s\n' "$before") <(printf '%s\n' "$after") | sed 's/^/        /'; }
done

# ...and the prologue PAST the refusal, which the rows above never reach: they
# all exit at rc=2 long before the port and JAVA_HOME checks. Running it from a
# copied "repo" with no keliver.portal.json exercises the port branch, and
# distinguishes its exit code from the refusal's — they were both 2, so a caller
# could not tell a refused parent from a missing toolchain.
FAKE="$DISP/fakerepo"
mkdir -p "$FAKE/scripts" "$DISP/vsb-ok"
cp "$VSB" "$ROOT/scripts/keliver-test-isolation-guard.sh" "$FAKE/scripts/"
( export HOME="$VSBH"; unset PORTAL_STORE
  "$FAKE/scripts/keliver-verify-signed-bundle.sh" "$DISP/vsb-ok" ) \
  >/dev/null 2>"$DISP/vsb-port.err"
rc=$?
[ "$rc" = 3 ] && ok "verify-signed-bundle exits 3, not the refusal's 2, on a bad config" \
               || bad "an unreadable config gave rc=$rc, indistinguishable from a refusal"
grep -q "could not read a port" "$DISP/vsb-port.err" \
  && ok "and names the config rather than blaming something else" \
  || { bad "it did not say the port could not be read"; head -2 "$DISP/vsb-port.err" | sed 's/^/        /'; }

echo "--- nothing in scripts/ can die on bash 3.2"
# Expanding an EMPTY array under `set -u` is fatal on bash 3.2, which is
# /bin/bash on macOS. CI runs ubuntu with bash 5 and structurally cannot catch a
# new one, so this is a lint rather than a behaviour test: it is the only thing
# standing between a fifth "${arr[@]}" and a guard that kills the caller's shell
# instead of returning.
# Only an array that CAN be empty matters: expanding a non-empty one is fine on
# 3.2, and ${#arr[@]} is fine either way. So the lint flags an expansion whose
# array is initialised empty somewhere in the same file — which is exactly the
# shape that bit us: an array declared empty, then expanded plainly.
# Scoped to keliver-*.sh: those are the scripts that source this guard and that
# this work owns. Anything found elsewhere is reported but does not fail the
# suite — widening the gate to unrelated scripts is not this block's call.
# One scan over every script, classifying each file rather than two near-copies.
# A scanner that cannot run must FAIL: measured, the earlier version reported
# PASS when python3 raised on an unreadable file, which is the one outcome a
# lint that CI cannot replace must never produce.
LINT="$ROOT/scripts/keliver-bash32-lint.py"
if ! command -v python3 >/dev/null 2>&1; then
  bad "no python3, so the bash 3.2 lint did not run"
elif [ ! -f "$LINT" ]; then
  bad "the bash 3.2 lint is missing: $LINT"
else
  # FIXTURES FIRST. The lint used to be a heredoc that only ever scanned the
  # live tree — which holds one guarded expansion and no line carrying a
  # guarded and an unguarded one, the single case its guard logic exists for.
  # Measured: replacing that logic with the crude per-line skip it had replaced,
  # or with a name-blind one, left the suite green. So the lint is now its own
  # file and it is run against shapes chosen to exercise it.
  FIX="$DISP/lint-fixtures"
  mkdir -p "$FIX"
  fixture() { printf '%s\n' "$2" > "$FIX/$1"; }
  # lint: bash32-fixtures-begin
  # Fatal on bash 3.2 — every one of these aborts the shell under set -u.
  fixture keliver-f01.sh 'x=()
f "${x[@]}"'
  fixture keliver-f02.sh 'g(){ local x=(); f "${x[@]}"; }'
  fixture keliver-f03.sh 'g(){ x=(); f "${x[@]}"; }'
  fixture keliver-f04.sh 'a=1
declare -a x
f "${x[@]}"'
  fixture keliver-f05.sh 'g(){ local -a x; f "${x[@]}"; }'
  fixture keliver-f06.sh 'x=()
f "${x[*]}"'
  fixture keliver-f07.sh 'g(){ local -a p=() q=(); f "${q[@]}"; }'
  fixture keliver-f08.sh 'x=(a)
unset x
f "${x[@]}"'
  fixture keliver-f09.sh 'a=()
if [ -n "${OPT:-}" ]; then a+=(z); fi
f "${a[@]}"'
  fixture keliver-f10.sh 'b=()
[ ${#b[@]} -gt 0 ]
f "${b[@]}"'
  fixture keliver-f11.sh 'c=()
f "${c[@]+"${c[@]}"}" "${c[@]}"'
  fixture keliver-f12.sh 'a=()
echo "a literal \${a[@]+ inside a string"
f "${a[@]}"'
  # Another array's guard must not cover this one. Verified fatal on bash 3.2:
  # with d non-empty the guard body IS expanded, and e is empty.
  fixture keliver-f13.sh 'd=(x)
e=()
f "${d[@]+${d[@]} ${e[@]}}"'
  # The escape and quote decoys must be on the SAME LINE as the expansion:
  # guarded_spans matches per line, so a decoy on its own line never reaches the
  # scan and the two branches it is meant to exercise stay unfalsifiable.
  fixture keliver-f14.sh 'a=()
echo "a literal \${a[@]+ x" ; f "${a[@]}"'
  fixture keliver-f15.sh 'b=()
echo '"'"'${b[@]+ x'"'"' ; f "${b[@]}"'
  # Safe — none of these may be reported.
  fixture keliver-s01.sh 'x=()
f ${x[@]+"${x[@]}"}'
  fixture keliver-s02.sh 'm=()
f "${m[@]+${#m[@]} ${m[@]}}"'
  fixture keliver-s03.sh 'p=()
f "${p[@]:-}"'
  fixture keliver-s05.sh 'echo "no arrays here at all"'
  # NOT a safe shape — it is fatal, and it is here to prove the pragma silences
  # a report. Listing it under "safe" said the opposite of what it tests.
  fixture keliver-s04.sh 'n=()
f "${n[@]}"  # lint: bash32-ok'
  fixture other-o01.sh 'z=()
f "${z[@]}"'

  # lint: bash32-fixtures-end
  FIXOUT="$(python3 "$LINT" "$FIX" 2>&1)"; FIXRC=$?
  MISSED=""; SPURIOUS=""
  for n in 01 02 03 04 05 06 07 08 09 10 11 12 13 14 15; do
    printf '%s\n' "$FIXOUT" | grep -q "^KELIVER|.*keliver-f$n\.sh:" || MISSED="$MISSED f$n"
  done
  for n in 01 02 03 04 05; do
    printf '%s\n' "$FIXOUT" | grep -q "keliver-s$n\.sh:" && SPURIOUS="$SPURIOUS s$n"
  done
  printf '%s\n' "$FIXOUT" | grep -q "^OTHER|.*other-o01\.sh:" || MISSED="$MISSED routing"
  if [ "$FIXRC" = 0 ] && [ -z "$MISSED" ]; then
    ok "the lint flags every shape that is fatal on bash 3.2"
  else
    bad "the lint missed a fatal shape:$MISSED (rc=$FIXRC)"
  fi
  if [ -z "$SPURIOUS" ]; then
    ok "and reports none of the safe ones"
  else
    bad "the lint flagged a safe shape:$SPURIOUS"
  fi

  # Then the live tree.
  LINT_OUT="$(python3 "$LINT" "$ROOT/scripts" 2>&1)"; LINT_RC=$?
  # A POSITIVE sentinel and the exit status. Absence of a failure marker is not
  # evidence the scan happened: measured, a plain raise inside the scanner
  # produced a traceback with no marker, $( ) discarded the status, and the
  # lint reported PASS.
  if [ "$LINT_RC" -ne 0 ] || ! printf '%s\n' "$LINT_OUT" | grep -q '^SCANNER-OK'; then
    bad "the bash 3.2 lint did not complete, so it proves nothing"
    printf '%s\n' "$LINT_OUT" | head -5 | sed 's/^/        /'
  else
    UNGUARDED="$(printf '%s\n' "$LINT_OUT" | grep '^KELIVER|' || true)"
    ELSEWHERE="$(printf '%s\n' "$LINT_OUT" | grep '^OTHER|' || true)"
    if [ -z "$UNGUARDED" ]; then
      ok "every array that can be empty is expanded safely for bash 3.2"
    else
      bad "an array that can be empty is expanded unguarded — fatal on bash 3.2"
      printf '%s\n' "$UNGUARDED" | sed 's/^/        /'
    fi
    [ -n "$ELSEWHERE" ] && {
      printf '        note: the same shape outside keliver-*.sh, not gated here:\n'
      printf '%s\n' "$ELSEWHERE" | sed 's/^/          /'
    }
  fi
fi

# /bin/bash is bash 3.2 on macOS and bash 5 on the Linux runner, so this is a
# real second parser only on macOS. Say which one ran rather than implying two.
if [ -x /bin/bash ]; then
  OLDBASH_V="$(/bin/bash --version 2>/dev/null | head -1 | sed 's/.*version \([0-9]*\).*/\1/')"
  BADPARSE=""
  for f in "$ROOT"/scripts/*.sh; do
    /bin/bash -n "$f" 2>/dev/null || BADPARSE="$BADPARSE $f"
  done
  if [ -n "$BADPARSE" ]; then
    bad "these do not parse under /bin/bash:$BADPARSE"
  elif [ "${OLDBASH_V:-0}" -lt 4 ] 2>/dev/null; then
    ok "and every script parses under /bin/bash, which here is bash ${OLDBASH_V}.x"
  else
    ok "every script parses under /bin/bash (bash ${OLDBASH_V:-?}.x — same parser as the shebang, so this is not a second opinion)"
  fi
else
  bad "no /bin/bash here, so the old-parser check did not run"
fi

echo
echo "passed: $PASS   failed: $FAIL"
# Clean up on success; keep the tree on failure, where it is evidence.
if [ "$FAIL" = 0 ]; then
  rm -rf "$DISP"
else
  echo "evidence: $DISP"
fi
[ "$FAIL" = 0 ]
