#!/usr/bin/env bash
#
# keliver-store-home-check — which home the store resolver answers against.
#
#   scripts/keliver-store-home-check.sh <disposable-root>
#
# WHY THIS EXISTS (#78). keliver-store-path.sh is the shell mirror of
# PortalConfig.storeDir(), and the home it resolves against is part of the
# identity: it picks the default store, and it expands a "~/..." in
# keliver.portal.json. The store is where the signing key lives and where a host
# reads the public key it embeds, so the wrong home signs with one identity and
# verifies against another.
#
# The authority reads the JVM's System.getProperty("user.home"). The mirror used
# to read, in order: --home, then `java ... | awk ...`, then $HOME. Two defects,
# the second hiding the first — the pipeline carried AWK's status and awk
# succeeds on no match, so "java absent / crashed / said nothing" arrived as an
# empty string, which then took the $HOME fallback SILENTLY. On macOS user.home
# comes from the passwd entry and ignores $HOME, so those are routinely two
# different directories.
#
# EVERY CASE ASSERTS THE SELECTED IDENTITY, NOT THE PATH TEXT. Both fake homes
# hold a store for the same app, so the two paths end in the SAME
# <slug>-<hash> directory and differ only in their prefix; the assertion reads
# the key material inside the resolved store and checks WHICH key it got.
#
# Nothing real is touched: both homes, both stores and both keys are minted
# under <disposable-root>, and the key material is a fixed marker string, not a
# real key.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DISP_PARENT="${1:?usage: $0 <disposable-root>}"
RESOLVE="$ROOT/scripts/keliver-store-path.sh"

# shellcheck source=/dev/null
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "$DISP_PARENT" storehome)" || exit $?

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '  PASS  %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '  FAIL  %s\n' "$1"; }

HOME_A="$DISP/home-shell"      # what $HOME says
HOME_B="$DISP/home-jvm"        # what the JVM's user.home says
APP="$DISP/app"
mkdir -p "$HOME_A" "$HOME_B" "$APP"

# The stub java. Rewritten per case.
BIN="$DISP/bin"; mkdir -p "$BIN"
# A PATH holding everything the resolver needs and NO java, for the one case
# that is about java being absent. Prepending $BIN to the real PATH is not
# enough: deleting the stub then finds the REAL java, which answers with the
# developer's REAL user.home — measured, that case scored rc=0 and printed a
# path under the real store, which is both a false pass and a read this check
# has no business making.
PURE="$DISP/purebin"; mkdir -p "$PURE"
PURE_OK=1
# Everything the resolver AND the bundled callers below need. A missing tool
# makes a case "refuse" for a reason that has nothing to do with java — measured,
# omitting `cp` failed the adopt-legacy positive row for exactly that reason.
for t in bash python3 awk head sed cat basename dirname mkdir ls cp rm find date chmod stat; do
  src="$(command -v "$t" 2>/dev/null)" && ln -sf "$src" "$PURE/$t" || PURE_OK=0
done
java_says() { printf '#!/bin/sh\n%s\n' "$1" > "$BIN/java"; chmod +x "$BIN/java"; }
GOOD_JAVA="echo \"        user.home = $HOME_B\""

# Resolve with a KNOWN-GOOD home to learn the per-app directory name, then plant
# a differently-marked key under each home at that same name. Computing the name
# here by hand would be a fourth copy of the slug rules, which is the drift this
# whole contract exists to prevent.
java_says "$GOOD_JAVA"
seed_store() { # seed_store <home> <marker>
  local home="$1" marker="$2" path
  path="$( PATH="$BIN:$PATH" HOME="$home" "$RESOLVE" "$APP" --home "$home" )" || return 1
  mkdir -p "$path/keys"
  printf '%s' "$marker" > "$path/keys/ed25519.pub"
  printf '%s' "$path"
}
STORE_A="$(seed_store "$HOME_A" "KEY-FROM-SHELL-HOME")" || { echo "setup failed (A)"; exit 1; }
STORE_B="$(seed_store "$HOME_B" "KEY-FROM-JVM-HOME")"   || { echo "setup failed (B)"; exit 1; }

echo "=== which home the resolver answers against"
echo "    \$HOME        -> $HOME_A"
echo "    JVM user.home -> $HOME_B"
# If these collide the whole suite is vacuous: every assertion below would pass
# whichever home were chosen.
if [ "$STORE_A" = "$STORE_B" ]; then
  bad "the two fake homes resolve to the SAME store, so nothing here discriminates"
else
  ok "the two fake homes resolve to different stores, so identity is observable"
fi
# ...and the tails must match, so that a check comparing only the trailing
# directory name could not tell them apart either.
if [ "$(basename "$STORE_A")" = "$(basename "$STORE_B")" ]; then
  ok "and they share a <slug>-<hash>, so only the key inside distinguishes them"
else
  bad "the per-app directory names differ, so these cases are weaker than intended"
fi

# identity_of <resolved-path> -> the marker inside, or a diagnosis
identity_of() {
  local p="$1"
  [ -n "$p" ] || { printf '(no path)'; return; }
  [ -f "$p/keys/ed25519.pub" ] || { printf '(no key at %s)' "$p"; return; }
  cat "$p/keys/ed25519.pub"
}

# route <label> <java-body|GOODJAVA> <want-rc> <want-identity|REFUSED> [extra args...]
# Runs the resolver with NO --home unless extra args say otherwise, under a $HOME
# that is deliberately the WRONG answer.
route() {
  local label="$1" jbody="$2" want_rc="$3" want_id="$4"; shift 4
  local outp rc id
  [ "$jbody" = "GOODJAVA" ] && jbody="$GOOD_JAVA"
  local usepath="$BIN:$PATH"
  if [ "$jbody" = "ABSENT" ]; then
    # A java-free PATH, not a deleted stub in front of the real one.
    usepath="$PURE"
  else
    java_says "$jbody"
  fi
  outp="$( PATH="$usepath" HOME="$HOME_A" "$RESOLVE" "$APP" "$@" 2>"$DISP/err" )"; rc=$?
  if [ "$want_id" = "REFUSED" ]; then
    id="REFUSED"
    # A refusal must also print NOTHING on stdout: a caller doing
    # STORE="$(resolver ...)" without checking $? would otherwise use the text.
    if [ -n "$outp" ]; then
      id="REFUSED-BUT-PRINTED:$outp"
    fi
  else
    id="$(identity_of "$outp")"
  fi
  if [ "$rc" = "$want_rc" ] && [ "$id" = "$want_id" ]; then
    ok "$label"
  else
    bad "$label (rc=$rc wanted $want_rc; identity=$id wanted $want_id)"
    head -3 "$DISP/err" | sed 's/^/        /'
  fi
  java_says "$GOOD_JAVA"
}

# If that PATH cannot run the resolver at all, the absence case would "refuse"
# for a reason with nothing to do with java and would prove nothing.
if [ "$PURE_OK" = 1 ] && ! ( export PATH="$PURE"; command -v java >/dev/null 2>&1 ); then
  ok "the java-free fixture PATH has the resolver's tools and no java"
else
  bad "the java-free fixture PATH is wrong, so the absence case proves nothing"
fi

echo "--- direct CLI, no --home: discovery decides, and \$HOME must not"
route "working java selects the JVM home's identity" \
  GOODJAVA 0 "KEY-FROM-JVM-HOME"
route "java exits 127 -> refusal, not the \$HOME store" \
  'exit 127' 4 REFUSED
route "java exits 1 with a message -> refusal" \
  'echo "no libjvm here" >&2; exit 1' 4 REFUSED
route "java prints no user.home line -> refusal" \
  'echo "        java.version = 17"' 4 REFUSED
route "java prints an empty user.home -> refusal" \
  'echo "        user.home = "' 4 REFUSED
route "java prints a relative user.home -> refusal" \
  'echo "        user.home = relative/nope"' 4 REFUSED
route "java prints two different user.homes -> refusal" \
  'echo "        user.home = /one"; echo "        user.home = /two"' 4 REFUSED
route "java absent from PATH -> refusal" \
  ABSENT 4 REFUSED

echo "--- an explicit --home is the answer, and skips discovery entirely"
route "--home wins even with java completely broken" \
  'exit 127' 0 "KEY-FROM-JVM-HOME" --home "$HOME_B"
route "and --home can name the shell home, if that is what a caller means" \
  'exit 127' 0 "KEY-FROM-SHELL-HOME" --home "$HOME_A"
route "a RELATIVE --home is a usage error, not a cwd-relative store" \
  GOODJAVA 2 REFUSED --home "relative/home"

echo "--- documented precedence is unchanged"
PS_STORE="$DISP/explicit-store"; mkdir -p "$PS_STORE/keys"
printf '%s' "KEY-FROM-PORTAL-STORE" > "$PS_STORE/keys/ed25519.pub"
rc=0
outp="$( PATH="$BIN:$PATH" HOME="$HOME_A" PORTAL_STORE="$PS_STORE" "$RESOLVE" "$APP" 2>/dev/null )" || rc=$?
[ "$rc" = 0 ] && [ "$(identity_of "$outp")" = "KEY-FROM-PORTAL-STORE" ] \
  && ok "PORTAL_STORE still outranks the discovered home" \
  || bad "PORTAL_STORE precedence changed (rc=$rc, identity=$(identity_of "$outp"))"
# ...and it must outrank discovery FAILURE too: an explicit store needs no home.
rc=0
java_says 'exit 127'
outp="$( PATH="$BIN:$PATH" HOME="$HOME_A" PORTAL_STORE="$PS_STORE" "$RESOLVE" "$APP" 2>/dev/null )" || rc=$?
# An explicit store is step 1 and is answered before any home is read, so it
# must NOT require a working java. The first version of this fix demanded the
# home unconditionally and refused here — strictness that buys nothing and
# breaks an explicit configuration on a box without java.
[ "$rc" = 0 ] && [ "$(identity_of "$outp")" = "KEY-FROM-PORTAL-STORE" ] \
  && ok "an explicit PORTAL_STORE answers even when discovery cannot" \
  || bad "an explicit PORTAL_STORE was refused for want of a home (rc=$rc)"
# But --default IS the home-derived step, so there it must still refuse.
rc=0
outp="$( PATH="$BIN:$PATH" HOME="$HOME_A" PORTAL_STORE="$PS_STORE" "$RESOLVE" "$APP" --default 2>/dev/null )" || rc=$?
[ "$rc" = 4 ] && [ -z "$outp" ] \
  && ok "--default still refuses without a home, PORTAL_STORE or not" \
  || bad "--default answered without a home (rc=$rc, out=$outp)"
java_says "$GOOD_JAVA"

# A "~/..." in keliver.portal.json expands against the RESOLVED home, so this is
# the second place the wrong home selects the wrong identity.
mkdir -p "$HOME_A/tilde-store/keys" "$HOME_B/tilde-store/keys"
printf '%s' "TILDE-UNDER-SHELL-HOME" > "$HOME_A/tilde-store/keys/ed25519.pub"
printf '%s' "TILDE-UNDER-JVM-HOME"   > "$HOME_B/tilde-store/keys/ed25519.pub"
printf '{"store": "~/tilde-store"}\n' > "$APP/keliver.portal.json"
route "a ~/ store in keliver.portal.json expands against the JVM home" \
  GOODJAVA 0 "TILDE-UNDER-JVM-HOME"
route "and refuses rather than expanding ~ against \$HOME" \
  'exit 127' 4 REFUSED
rm -f "$APP/keliver.portal.json"

echo "--- split-store refusal keeps its own exit code"
SPLIT_APP="$DISP/splitapp"; mkdir -p "$SPLIT_APP"
# "Split" means two directories in the SAME apps/ whose names both end in
# -<hash>, differing only in slug. A "-legacy" SUFFIX does not end in the hash
# and is not a split at all — the first version of this fixture built one of
# those and scored a failure against a refusal that was working correctly.
p1="$( PATH="$BIN:$PATH" HOME="$HOME_B" "$RESOLVE" "$SPLIT_APP" --home "$HOME_B" )"
SPLIT_HASH="${p1##*-}"
mkdir -p "$p1" "$(dirname "$p1")/legacyname-$SPLIT_HASH"
# Prove the fixture really is split before asserting anything about it.
SPLIT_N="$(ls -1d "$(dirname "$p1")"/*-"$SPLIT_HASH" 2>/dev/null | wc -l | tr -d ' ')"
[ "$SPLIT_N" = 2 ] && ok "the split fixture really has two stores for one app" \
                   || bad "the split fixture has $SPLIT_N store(s), so the next row proves nothing"
rc=0
outp="$( PATH="$BIN:$PATH" HOME="$HOME_A" "$RESOLVE" "$SPLIT_APP" 2>"$DISP/split.err" )" || rc=$?
case "$rc" in
  3) ok "a split store still exits 3, not 4" ;;
  0) bad "a split store resolved silently (rc=0) — the refusal is gone" ;;
  *) # Only meaningful if this fixture really is split; say so rather than
     # scoring a pass for an unrelated refusal.
     bad "a split store exited $rc, not 3 — $(head -1 "$DISP/split.err")" ;;
esac

echo "--- a bundled caller must not PROCEED on a refusal"
# THE ONE THE REFUSAL CREATED. keliver-adopt-legacy-store.sh took the resolver's
# stdout without checking its status. Once the resolver started refusing instead
# of falling back to $HOME, a refusal arrived as TARGET="" — and `cd ""` SUCCEEDS
# in bash, returning the cwd, so the "already uses the legacy store" guard could
# not fire either. Every destination became "/<rel>": MEASURED, it tried to copy
# the legacy PRIVATE SIGNING KEY to /keys/ed25519.priv, printed "copied", and
# exited 0. macOS only escaped because / is read-only under SIP.
#
# This script is shipped to adopters in the tools bundle, and it is the caller
# that copies key material, so it gets its own row rather than being covered by
# "the resolver refuses".
ADOPT="$ROOT/scripts/keliver-adopt-legacy-store.sh"
LEG="$DISP/legacy"; mkdir -p "$LEG/keys" "$LEG/default"
printf 'MARKER-NOT-A-REAL-PRIVATE-KEY\n' > "$LEG/keys/ed25519.priv"
printf 'MARKER-PUB\n'                    > "$LEG/keys/ed25519.pub"
printf '{}\n'                            > "$LEG/default/x.json"
ADOPT_APP="$DISP/adoptapp"; mkdir -p "$ADOPT_APP"

if [ -x "$ADOPT" ]; then
  # NEGATIVE: no java at all. Must refuse, and must not claim to have copied.
  rc=0
  ( export PATH="$PURE" HOME="$HOME_A"; unset PORTAL_STORE
    "$ADOPT" "$ADOPT_APP" --legacy "$LEG" ) > "$DISP/adopt-neg.log" 2>&1 || rc=$?
  [ "$rc" != 0 ] && ok "adopt-legacy refuses when the store cannot be resolved (rc=$rc)" \
                 || bad "adopt-legacy proceeded with an unresolvable store (rc=0)"
  if grep -q 'copied:' "$DISP/adopt-neg.log"; then
    bad "adopt-legacy reported copying something while refusing"
    grep -m3 'copied:' "$DISP/adopt-neg.log" | sed 's/^/        /'
  else
    ok "and it did not report copying anything"
  fi
  # The tell for the original bug: it announced an EMPTY destination and carried on.
  if grep -qE '^adopting into: *$' "$DISP/adopt-neg.log"; then
    bad "adopt-legacy announced an empty destination and continued"
  else
    ok "and it never announced an empty destination"
  fi

  # POSITIVE: with discovery working, it must still adopt — into the JVM home's
  # store, which the stub controls, so nothing real is ever the destination.
  java_says "$GOOD_JAVA"
  rc=0
  ( export PATH="$BIN:$PURE" HOME="$HOME_A"; unset PORTAL_STORE
    "$ADOPT" "$ADOPT_APP" --legacy "$LEG" ) > "$DISP/adopt-pos.log" 2>&1 || rc=$?
  ADOPT_DEST="$( PATH="$BIN:$PATH" HOME="$HOME_A" "$RESOLVE" "$ADOPT_APP" )"
  if [ "$rc" = 0 ] && [ -f "$ADOPT_DEST/keys/ed25519.priv" ]; then
    ok "and with discovery working it adopts into the JVM home's store"
  else
    bad "adopt-legacy failed with a working resolver (rc=$rc, dest=$ADOPT_DEST)"
    tail -4 "$DISP/adopt-pos.log" | sed 's/^/        /'
  fi
  # The destination must be under the fixture, never anywhere else. This is the
  # assertion the original bug would have tripped on Linux.
  case "$ADOPT_DEST" in
    "$HOME_B"/*) ok "and that destination is inside the fake JVM home, not elsewhere";;
    *)           bad "adopt-legacy resolved outside the fixture: $ADOPT_DEST";;
  esac
else
  bad "keliver-adopt-legacy-store.sh is missing, so the bundled-caller rows prove nothing"
fi

echo
printf 'passed: %d   failed: %d\n' "$PASS" "$FAIL"
echo "evidence: $DISP"
[ "$FAIL" -eq 0 ]
