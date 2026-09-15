#!/usr/bin/env bash
#
# keliver-test-isolation-guard — refuse to start a test relay that could touch
# the developer's real state.
#
#   source scripts/keliver-test-isolation-guard.sh
#   keliver_require_isolated_store <disposable-root> <app-dir> [relay-bin]
#
# WHY. A previous reproduction set HOME to a disposable directory and assumed
# that redirected the document store. It does not: the store expands "~/"
# through the JVM's `user.home` system property, which macOS derives from the
# passwd entry and ignores $HOME. That run wrote into the developer's real
# ~/.keliver-portal.
#
# This guard checks the EFFECTIVE values — the JVM's own user.home, and the
# store the relay would actually resolve — and fails BEFORE startup if either
# is outside the disposable root. It never reads key material.
#
set -uo pipefail

# Print the JVM's effective user.home under the current environment, or FAIL.
#
# THE STATUS IS THE POINT. This used to be one pipeline —
#
#   out="$(java -XshowSettings:properties -version 2>&1 | awk ...)"
#
# — so the substitution carried AWK's status, and awk succeeds when it matches
# nothing. "java is not installed", "java crashed", "java printed no user.home"
# and "java said /Users/me" were therefore the SAME answer to every caller: rc=0
# and an empty string. keliver_protected_roots read that empty string as "there
# is no JVM root" and carried on with the $HOME ones alone.
#
# MEASURED at 53ed0637d, with HOME on a disposable directory and the store under
# a DIFFERENT user.home — the macOS geometry this root exists for — all five of
# these returned 0 from the refusal and then created directories inside the
# protected store: java exiting 127, java exiting 1 with a message, java printing
# a relative user.home, java printing no user.home line, and java absent from
# PATH entirely.
#
# So: no pipe around the invocation, the exit status is kept and checked, and the
# answer must be exactly one non-empty absolute path. Anything else is a failure
# the caller must refuse on — never an empty string it can mistake for "no root".
keliver_effective_jvm_home() {
  # NOT named `status`: that is a read-only alias for $? in zsh, and this file is
  # sourced by name from whatever shell a developer happens to be in. MEASURED:
  # under zsh the assignment aborted the function, which returned non-zero, which
  # every caller reads as "discovery failed" — so the first run of the new
  # assertions refused EVERYTHING, including legitimate parents, and looked like
  # a pass. The legitimate-parent case is the only assertion that caught it.
  local raw jstatus out
  # Unpiped, so $? is java's own. A missing binary is 127 here; it was 0 before.
  raw="$(java -XshowSettings:properties -version 2>&1)"; jstatus=$?
  if [ "$jstatus" -ne 0 ]; then
    echo "keliver: java exited $jstatus when asked for user.home." >&2
    printf '%s\n' "$raw" | head -5 | sed 's/^/  java: /' >&2
    return 1
  fi
  # UNAMBIGUOUS, not just present: collect every user.home line, and answer only
  # if they agree on one non-empty value. Two different answers is not something
  # to pick a winner from. awk's own exit 1 reaches this substitution because
  # there is no command after it in the pipeline to mask it.
  out="$(printf '%s\n' "$raw" | awk '
    /^[ \t]*user\.home[ \t]*=/ {
      v = substr($0, index($0, "=") + 1)
      gsub(/^[ \t]+/, "", v); gsub(/[ \t\r]+$/, "", v)
      if (v != "") seen[v] = 1
    }
    END { n = 0; for (k in seen) { n++; last = k }
          if (n == 1) print last; else exit 1 }
  ')" || {
    echo "keliver: java ran, but did not report exactly one user.home." >&2
    return 1
  }
  case "$out" in
    /*) ;;
    *)  echo "keliver: java reported user.home='$out', which is not an absolute path." >&2
        return 1;;
  esac
  printf '%s' "$out"
}

# Print the store the relay would resolve for <app-dir>, without starting it.
#
# Delegates to keliver-store-path.sh rather than deriving the path again. This
# used to be a third copy of the resolution rules and it had already drifted:
# it did not know about <app>/.gradle/keliver-store-path, so it vouched for a
# store that was not the one the relay would open.
keliver_effective_store() {
  local app="$1" home="$2" here script
  if [ -n "${PORTAL_STORE:-}" ]; then printf '%s' "$PORTAL_STORE"; return; fi
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
  for script in "$here/keliver-store-path.sh" "$here/../scripts/keliver-store-path.sh"; do
    [ -x "$script" ] || continue
    "$script" "$app" --home "$home"
    return $?
  done
  echo "guard: keliver-store-path.sh not found next to $here" >&2
  return 1
}

# The gate. Exits non-zero (and says why) rather than letting a test run.
keliver_require_isolated_store() {
  local root="$1" app="$2"
  local fail=0
  root="$(cd "$root" 2>/dev/null && pwd -P)" || { echo "guard: disposable root does not exist: $1" >&2; return 1; }

  local jvm_home store
  # Same requirement as keliver_protected_roots: an unknown user.home used to
  # arrive here as an empty string and be passed to the store resolver as the
  # home to resolve against, which is a different store from the real one.
  if ! jvm_home="$(keliver_effective_jvm_home)"; then
    echo "guard: the JVM's user.home could not be established (see above), so the store" >&2
    echo "guard: this app resolves cannot be named. Refusing to start a test relay." >&2
    return 1
  fi
  if ! store="$(keliver_effective_store "$app" "$jvm_home")"; then
    echo "guard: the store could not be resolved for $app (see above)." >&2
    echo "guard: refusing to start a test relay." >&2
    return 1
  fi

  case "$jvm_home" in
    "$root"|"$root"/*) ;;
    *) echo "guard: the JVM's effective user.home is OUTSIDE the disposable root." >&2
       echo "         user.home = $jvm_home" >&2
       echo "         root      = $root" >&2
       echo "       Setting HOME is not enough. Add -Duser.home=<root> to JAVA_TOOL_OPTIONS." >&2
       fail=1 ;;
  esac
  case "$store" in
    "$root"|"$root"/*) ;;
    *) echo "guard: the resolved document store is OUTSIDE the disposable root." >&2
       echo "         store = $store" >&2
       echo "         root  = $root" >&2
       fail=1 ;;
  esac
  # Belt and braces: never let a test resolve to the real global store.
  local real_home; real_home="$(cd ~ && pwd -P)"
  case "$store" in
    "$real_home"/.keliver-portal*)
       echo "guard: the resolved store is the developer's REAL global store: $store" >&2
       fail=1 ;;
  esac

  if [ "$fail" -ne 0 ]; then
    echo "guard: refusing to start a test relay." >&2
    return 1
  fi
  echo "  guard ok: user.home=$jvm_home"
  echo "  guard ok: store=$store"
  return 0
}

# --- run directories ---------------------------------------------------------
#
# Create a UNIQUE run directory beneath a caller-selected parent, and never
# erase anything the caller supplied.
#
# The acceptance script used to begin with `rm -rf "$DISP"` on its first
# argument — before any isolation check. A mistyped or reused path (a home
# directory, a work tree, a directory holding earlier evidence) was deleted
# outright, and it happened before the guard that exists to prevent exactly
# that class of damage.
#
# It also REFUSES a parent that lies inside a tree these checks must never write
# into. Every one of them mints throwaway identities — stores, public keys,
# signing keys — beneath the parent it is handed, so a mistyped argument is a
# key written into the developer's real store. This lives here rather than in
# any one script because ELEVEN scripts mint identities this way — ten through
# keliver_make_run_dir and keliver-verify-signed-bundle.sh calling the refusal
# directly, its layout being fixed — of which SEVEN run in CI, and the
# last time a rule like this had one copy per caller the copies drifted.
#
# scripts/keliver-refusal-check.sh is its regression suite. It exists because
# this refusal failed open in three consecutive reviewed commits — on a
# non-existent ancestor, on a case-variant path, and on a .. segment — and every
# one of those was reachable by a ten-line harness that had not been written.
#
# Same DIRECTORY, by device+inode. Comparing resolved path strings was wrong
# twice: on macOS the default filesystem is case-INSENSITIVE, so
# ~/.KELIVER-PORTAL is the very same directory as ~/.keliver-portal — same
# device, same inode — and every case-sensitive pattern missed it.
#
# The stat flavour is PROBED, not assumed. `stat -f FMT` is the BSD/macOS
# spelling; on GNU coreutils -f is --file-system and takes no operand, so the
# BSD-first order silently produced garbage that never compared equal — the
# identity check would have been inert on the Linux runner, which is where CI
# runs. BSD stat rejects -c outright, so probing GNU first disambiguates. A stat
# that answers neither way makes this function REFUSE to guess; see
# keliver_refuse_protected_parent, which then refuses to run at all rather than
# degrade to name matching.
KELIVER_STAT_FMT=""
keliver_stat_id() {
  local id fmt
  if [ -z "${KELIVER_STAT_FMT:-}" ]; then
    for fmt in -c -f; do
      id="$(stat -L "$fmt" '%d:%i' / 2>/dev/null)"
      case "$id" in
        *[!0-9:]*|'') ;;
        *:*) KELIVER_STAT_FMT="$fmt"; break;;
      esac
    done
    [ -n "$KELIVER_STAT_FMT" ] || KELIVER_STAT_FMT="none"
  fi
  [ "$KELIVER_STAT_FMT" = "none" ] && return 1
  # -L, because neither flavour follows symlinks without it while [ -d ] does.
  # MEASURED: without it, a directory and a symlink to that same directory came
  # back with different inodes, so keliver_same_dir answered "provably
  # DIFFERENT" about one and the same directory — worse than the unknown case
  # the three-valued result exists to handle, because the caller acts on it.
  id="$(stat -L "$KELIVER_STAT_FMT" '%d:%i' "$1" 2>/dev/null)" || return 1
  case "$id" in
    *[!0-9:]*|'') return 1;;
    *:*) printf '%s' "$id"; return 0;;
  esac
  return 1
}

# Does NOT reset the memo — an earlier version did, so the flavour was re-probed
# on every refusal while a comment claimed it was cached.
keliver_stat_usable() { keliver_stat_id / >/dev/null; }

# 0 = the same directory, 1 = provably different, 2 = COULD NOT TELL.
# Collapsing the third into the second is how a guard silently degrades to
# matching names: both directories exist, stat fails for one of them, and
# "different" is the answer the caller acts on.
keliver_same_dir() {
  [ -d "$1" ] && [ -d "$2" ] || return 1
  local a b
  a="$(keliver_stat_id "$1")" || return 2
  b="$(keliver_stat_id "$2")" || return 2
  [ "$a" = "$b" ]
}

# The absolute path, with the deepest EXISTING ancestor resolved and the
# remainder re-appended. A failed cd is a refusal, not an empty string: an
# unreadable ancestor used to truncate the answer and let the check pass.
keliver_abs_of() {
  local p rest cur resolved
  [ -n "${1:-}" ] || return 1
  case "$1" in /*) p="$1";; *) p="${PWD:-$(pwd)}/$1";; esac
  # Collapse `//` and drop `.` segments. Dropping `.` is always safe — unlike
  # `..`, which is refused rather than normalised because it can only be
  # resolved against a directory that may not exist yet. It is also NECESSARY:
  # rmdir fails with EINVAL when the basename is `.`, for reasons that have
  # nothing to do with the directory being occupied, and MEASURED, that aborted
  # the undo at the first level and left the whole tree — the store included —
  # on disk while the call reported a clean refusal. `./scratch` is an ordinary
  # thing for a caller to pass, so refusing `.` outright would be wrong.
  while :; do
    case "$p" in
      *//*)  p="${p%%//*}/${p#*//}";;
      */./*) p="${p%%/./*}/${p#*/./}";;
      *) break;;
    esac
  done
  case "$p" in */.) p="${p%/.}";; esac
  [ -n "$p" ] || p="/"
  rest=""; cur="$p"
  while [ ! -d "$cur" ] && [ "$cur" != "/" ] && [ -n "$cur" ]; do
    rest="/$(basename "$cur")$rest"
    cur="$(dirname "$cur")"
    case "$cur" in /*) ;; *) return 1;; esac
  done
  [ -d "$cur" ] || { printf '%s\n' "$p"; return 0; }
  resolved="$(cd -P "$cur" 2>/dev/null && pwd -P)" || return 1
  [ -n "$resolved" ] || return 1
  # A DOUBLED LEADING SLASH. POSIX leaves a leading `//` implementation-defined
  # and bash's pwd -P preserves it, so a path reached through a symlink to `/`
  # comes back as //private/tmp/... where getcwd(), /bin/pwd -P and realpath all
  # say /private/tmp/... . Everything downstream then compares strings against a
  # prefix that cannot match: MEASURED, a parent spelled through such a symlink
  # was ALLOWED, and keliver-verify-signed-bundle.sh — the one caller with no
  # second refusal — created its store inside the Gradle home.
  while :; do case "$resolved" in //*) resolved="${resolved#/}";; *) break;; esac; done
  if [ "$resolved" = "/" ]; then
    printf '%s\n' "${rest:-/}"
  else
    printf '%s%s\n' "$resolved" "$rest"
  fi
}

# The trees no check may write into: the real portal store under either the
# shell's HOME or the JVM's user.home — which differ on macOS, where user.home
# comes from the passwd entry and ignores HOME — an explicit PORTAL_STORE, and
# the Gradle home.
#
# keliver_effective_jvm_home SPAWNS A JVM, and this function asks it once per
# call. If java cannot be run the JVM-home roots are simply not added; the $HOME
# ones still are, and those are the same path except where user.home and HOME
# disagree. Stated because a silently smaller protected set should not be a
# surprise.
#
# THERE IS NO CACHE HERE ANY MORE, AND THAT IS THE FIX. Eleven rounds of this
# guard failing open produced ten path spellings and then one variable; the
# eleventh and twelfth were two more spellings of that same variable, and the
# shape they all share is "something a caller can set decides whether a
# protected root exists". Deleting the shape ends the series; guarding the next
# spelling of it does not.
#
# What was here: KELIVER_JVM_HOME_MEMO cached the answer and KELIVER_JVM_HOME_TRIED
# recorded that java had been asked. Both were ordinary globals, wiped at source
# time — so an EXPORTED value could not reach them — but assignable by any caller
# after sourcing, which is exactly what scripts/keliver-refusal-check.sh did for
# speed. MEASURED at c11e81643, with HOME on a disposable directory and a fake
# user.home holding the store:
#
#   KELIVER_JVM_HOME_TRIED=1  (memo unset)  -> java never asked, JVM root absent,
#                                              refuse rc=0, make_run_dir rc=0 and
#                                              TWO directories created inside it
#   KELIVER_JVM_HOME_MEMO=<any existing dir> -> honoured, and an honoured memo
#                                              REPLACES the JVM root rather than
#                                              adding to it: same rc=0, same writes
#
# The round that introduced the validation checked the memo for SHAPE — absolute,
# and a directory that exists — which rejects the three shapes that are invalid
# as paths and accepts the one that is a valid path and still a lie. Provenance,
# not shape, was the property, and a shell global cannot carry provenance.
#
# The cache also never cached. keliver_protected_roots is only ever called as
# `roots="$(keliver_protected_roots)"` — a command substitution — so both globals
# were subshell-local and never reached the caller. MEASURED: two consecutive
# keliver_refuse_protected_parent calls spawned java TWICE and left both globals
# empty in the parent shell. The only thing the memo ever sped up was the one
# caller that set it by hand, and that caller was the hole.
keliver_protected_roots() {
  local h abs leaf resolved_leaf jvm_home
  # ${...:-} on every expansion: an UNSET variable under `set -u` aborts the
  # function, and an aborted refusal reads to the caller exactly like an allowed
  # one. jvm_home is assigned before it is read, so it cannot be unset here.
  # REQUIRED, not best-effort. The JVM user.home is a protected root in its own
  # right — on macOS it comes from the passwd entry and ignores $HOME, which is
  # the entire reason it is consulted — so "I could not find out where it is" is
  # not "there is nothing there to protect". Falling back to the $HOME roots
  # alone hands the protected set back to the variable this root exists because
  # it does not trust.
  #
  # The stderr is NOT swallowed here any more: it used to be `2>/dev/null`, so
  # the one explanation of why the protected set was short never reached anyone.
  #
  # CONSEQUENCE, stated because it is a real cost: a machine with no working
  # java cannot run these checks at all. That is deliberate. Every caller mints
  # throwaway stores and signing keys next to a real one, and refusing to run is
  # recoverable while writing into the real store is not.
  if ! jvm_home="$(keliver_effective_jvm_home)"; then
    echo "keliver: the JVM's user.home could not be established (see above), and it is" >&2
    echo "  a protected root in its own right — on macOS it differs from \$HOME. Refusing" >&2
    echo "  rather than protecting only \$HOME." >&2
    return 3
  fi
  # DEFENCE IN DEPTH, deliberately not independently reachable — labelled, like
  # the raw PORTAL_STORE spelling below, rather than left looking like coverage
  # it no longer has. keliver_effective_jvm_home already guarantees either one
  # absolute path or a non-zero status, so nothing can arrive here that this
  # rejects. It stays for a future edit to that function that relaxes the
  # guarantee without noticing this caller depends on it.
  case "$jvm_home" in
    /*) ;;
    *)  echo "keliver: java reported a user.home that is not an absolute path. Refusing." >&2
        return 3;;
  esac
  # BOTH SPELLINGS OF EVERY ROOT. The candidate is normalised and
  # symlink-resolved before it is compared; the roots were not, so the name
  # comparison had one resolved operand and one raw one. MEASURED, that let
  # through: HOME spelled with a doubled slash, HOME reached through a symlink,
  # and a ~/.gradle or ~/.keliver-portal that is itself a symlink onto another
  # volume — an ordinary developer setup. The raw spelling is kept as well,
  # because the comparison against the GIVEN path needs it.
  for h in "${HOME:-}" "$jvm_home"; do
    [ -n "$h" ] || continue
    # HOME=/ must still protect /.keliver-portal: stripping the slash left an
    # empty prefix, which was then skipped entirely.
    [ "$h" = "/" ] || h="${h%/}"
    [ "$h" = "/" ] && h=""
    for leaf in "$h/.keliver-portal" "$h/.gradle"; do
      printf '%s\n' "$leaf"
      resolved_leaf="$(keliver_abs_of "$leaf" 2>/dev/null)" || resolved_leaf=""
      # A protected root that resolves to "/" has no useful answer: protect it
      # and every directory on the machine is inside it, so nothing can run;
      # skip it and the one tree that must be protected is not. Say which it is
      # and refuse, rather than denying everything with no explanation or
      # protecting nothing in silence. Roots were raw strings before they were
      # resolved here, so this only became reachable with that fix.
      if [ "$resolved_leaf" = "/" ]; then
        echo "keliver: $leaf resolves to the filesystem root, so 'inside the protected" >&2
        echo "  tree' would mean everywhere. Refusing rather than guessing." >&2
        return 3
      fi
      [ -n "$resolved_leaf" ] && [ "$resolved_leaf" != "$leaf" ] \
        && printf '%s\n' "$resolved_leaf"
    done
  done
  # PORTAL_STORE is a caller's environment, so it may be relative — and a
  # relative one used verbatim made the CURRENT DIRECTORY a protected root,
  # refusing legitimate parents.
  if [ -n "${PORTAL_STORE:-}" ]; then
    # An unexpanded ~ — single quotes in a Makefile, a CI yaml, an .envrc —
    # resolves to a literal "~" directory under $PWD, so the REAL store ends up
    # protected by nothing at all. The given argument is already refused for
    # this; the root was not, and MEASURED, PORTAL_STORE='~/store' let a run
    # directory be created inside the real store. Say so rather than silently
    # protecting the wrong path.
    # ~user/... too: it is the same unexpanded tilde one character along, and
    # MEASURED, PORTAL_STORE='~someuser/store' walked straight through the
    # check that had just been added for '~/store'.
    case "$PORTAL_STORE" in '~'|'~'/*|'~'[!/]*)
      echo "keliver: PORTAL_STORE is '$PORTAL_STORE' — the ~ was never expanded, so it names" >&2
      echo "  a literal '~' directory and protects nothing. Refusing." >&2
      return 3;;
    esac
    # Both spellings, like the $HOME leaves above — and the same answer for a
    # PORTAL_STORE that resolves to "/", which used to be a silent skip while
    # the documentation said otherwise.
    abs="$(keliver_abs_of "$PORTAL_STORE" 2>/dev/null)" || abs=""
    if [ "${PORTAL_STORE%/}" = "" ] || [ "$abs" = "/" ]; then
      echo "keliver: PORTAL_STORE is '$PORTAL_STORE', which resolves to the filesystem" >&2
      echo "  root, so 'inside the store' would mean everywhere. Refusing." >&2
      return 3
    fi
    # The raw spelling is DEFENCE IN DEPTH and deliberately not independently
    # reachable: the candidate is compared in both its given and its resolved
    # form, so the resolved root already matches everything the raw one would.
    # It is here for a future keliver_abs_of that cannot resolve a root, where
    # the raw spelling is all there is. Labelled rather than left looking like
    # coverage it does not have.
    printf '%s\n' "${PORTAL_STORE%/}"
    [ -n "$abs" ] && [ "${abs%/}" != "${PORTAL_STORE%/}" ] && printf '%s\n' "${abs%/}"
  fi
  return 0
}

keliver_refuse_protected_parent() {
  local given="$1" abs cur root matched="" roots home_abs
  # A guard that cannot establish identity must not quietly fall back to
  # matching names.
  keliver_stat_usable || {
    echo "keliver: stat cannot report device+inode here, so this check cannot prove its" >&2
    echo "  run directory is outside the real portal store. Refusing." >&2
    return 2
  }
  # No HOME means the paths that must be protected cannot even be named.
  [ -n "${HOME:-}" ] || {
    echo "keliver: HOME is not set, so this check cannot tell whether it was pointed at" >&2
    echo "  the real portal store. These checks mint throwaway keys; refusing." >&2
    return 2
  }
  # An unexpanded literal ~ is a quoting mistake; acting on it creates a
  # directory called "~" in the caller's cwd.
  case "$given" in '~'|'~'/*|'~'[!/]*)
    echo "keliver: refusing '$given' — ~ was not expanded (single quotes?)" >&2; return 2;;
  esac
  abs="$(keliver_abs_of "$given")" || {
    echo "keliver: refusing '$given' — its path could not be resolved, so it cannot be" >&2
    echo "  shown to be outside the real store." >&2
    return 2
  }
  # A .. segment past a component that does not exist yet survives into the
  # path mkdir -p later creates, and the kernel resolves it somewhere else
  # entirely: MEASURED, <home>/nope/../.keliver-portal was allowed here and then
  # created a run directory INSIDE the store. Nothing legitimate needs .., so it
  # is refused rather than normalised.
  #
  # Checked against BOTH the given spelling and the resolved one. Bash's `cd` is
  # LOGICAL by default: it cancels `link/..` textually, so a .. that traverses a
  # symlink was already gone by the time this looked, and the answer disagreed
  # with the kernel's — MEASURED, safe/link/../keys resolved here to safe/keys
  # while realpath said <store>/keys. keliver_abs_of uses `cd -P` now, but the
  # given spelling is checked first regardless: it is the thing the caller
  # actually asked for.
  #
  # The agreement with the kernel is bounded: a symlink to a FILE, or a dangling
  # one, as a MID-path component still resolves differently here than realpath
  # would. Neither is exploitable — mkdir -p fails ENOTDIR or ENOENT on both, and
  # a dangling symlink as the whole argument is refused below — but the claim is
  # "agrees for directories", not "agrees always".
  case "/$given/" in *"/../"*)
    echo "keliver: refusing '$given' — it contains a .. segment that cannot be resolved" >&2
    echo "  before the directory exists, and mkdir would resolve it elsewhere." >&2
    return 2;;
  esac
  # DEFENCE IN DEPTH, and deliberately not independently reachable: every ..
  # in the given spelling is caught above, and pwd -P never emits one, so no
  # input reaches here with a .. that the previous check missed. Mutation
  # testing confirms deleting it changes no assertion. It is kept as a guard
  # against a future keliver_abs_of that introduces one, and it is labelled
  # rather than left looking like coverage it does not have.
  case "/$abs/" in *"/../"*)
    echo "keliver: refusing '$given' — it contains a .. segment that cannot be resolved" >&2
    echo "  before the directory exists, and mkdir would resolve it elsewhere." >&2
    return 2;;
  esac
  # The filesystem root is not a disposable parent. It is in no protected set,
  # so without this it was simply allowed, and mktemp -d would scatter run
  # directories at / — EPERM under SIP, but fine as root in a container.
  if [ "$abs" = "/" ]; then
    echo "keliver: refusing / as a disposable run parent" >&2
    return 2
  fi
  # A dangling symlink passes every check below and then fails in mkdir with a
  # diagnostic about the wrong thing.
  if [ -L "$given" ] && [ ! -e "$given" ]; then
    echo "keliver: refusing '$given' — it is a symlink pointing at nothing." >&2
    return 2
  fi
  # By STATUS, not by a marker in the data. The marker was an unanchored
  # substring match over user-controlled paths, so a directory that happened to
  # be named after it produced a refusal with a false explanation — and
  # returning 0 after emitting it meant the function reported success while
  # handing back a truncated list.
  if ! roots="$(keliver_protected_roots)"; then
    echo "keliver: refusing to run — the protected set could not be established." >&2
    return 2
  fi
  # By identity: every existing ancestor, against every protected root.
  cur="$abs"
  while : ; do
    while IFS= read -r root; do
      [ -n "$root" ] || continue
      keliver_same_dir "$cur" "$root"; case $? in
        0) matched="$root"; break;;
        2) matched="$root (identity could not be established)"; break;;
      esac
    done <<EOF
$roots
EOF
    [ -n "$matched" ] && break
    [ "$cur" = "/" ] && break
    cur="$(dirname "$cur")"
    case "$cur" in /*) ;; *) break;; esac
  done
  if [ -n "$matched" ]; then
    echo "keliver: refusing to run under $matched — these checks mint throwaway keys" >&2
    return 2
  fi
  # By name, for components that do not exist yet and so have no inode.
  while IFS= read -r root; do
    [ -n "$root" ] || continue
    case "$abs" in "$root"|"$root"/*)
      echo "keliver: refusing to run under $root — these checks mint throwaway keys" >&2
      return 2;;
    esac
    case "$given" in "$root"|"$root"/*)
      echo "keliver: refusing to run under $root — these checks mint throwaway keys" >&2
      return 2;;
    esac
  done <<EOF
$roots
EOF
  # The home directory itself is not a disposable parent.
  if [ -n "${HOME:-}" ]; then
    home_abs="$(keliver_abs_of "$HOME" 2>/dev/null)" || home_abs="$HOME"
    [ "$abs" = "$home_abs" ] && {
      echo "keliver: refusing to use your home directory as a disposable run parent" >&2
      return 2
    }
    keliver_same_dir "$abs" "$home_abs"
    case $? in 0|2)
      echo "keliver: refusing to use your home directory as a disposable run parent" >&2
      return 2;;
    esac
  fi
  return 0
}


# Remove exactly the directories this call created, innermost first. The list is
# RECORDED during the pre-mkdir scan and passed in, rather than re-derived by
# walking `dirname` from the parent afterwards: the walk's only stop condition
# was string equality with the deepest pre-existing level, so any spelling that
# broke the chain either left the tree behind or — demonstrated in isolation —
# kept climbing past it. A list cannot climb past anything.
#
# rmdir and never rm -rf: it removes only EMPTY directories, so anything with
# contents stops it, and it says so — "refused" while the store sits on disk is
# the wrong story to tell. An empty directory someone else created in the
# meantime WOULD be removed; rmdir cannot distinguish it.
keliver_undo_created() {
  local d
  for d in "$@"; do
    [ -n "$d" ] && [ -d "$d" ] || continue
    if ! rmdir "$d" 2>/dev/null; then
      echo "keliver: could not remove $d, which this call created — it is not empty" >&2
      return 0
    fi
  done
}

# Usage:  RUN="$(keliver_make_run_dir "$PARENT" acceptance)"
keliver_make_run_dir() {
  local parent="$1" name="${2:-run}"
  [ -n "$parent" ] || { echo "keliver_make_run_dir: no parent given" >&2; return 2; }
  if [ -e "$parent" ] && [ ! -d "$parent" ]; then
    echo "keliver_make_run_dir: $parent exists and is not a directory" >&2; return 2
  fi
  keliver_refuse_protected_parent "$parent" || return 2
  # The first refusal reasons about a path that may not exist yet, so it reasons
  # about SPELLINGS. The second runs on the real directory, after mkdir -p and
  # after cd has resolved every symlink and .. — the only place a spelling
  # cannot hide. But mkdir -p has to happen in between, and MEASURED, it did:
  # a parent spelled $HOME/.KELIVER-PORTAL/apps/live/x on a case-insensitive
  # filesystem passed the first check, mkdir -p created four directories INSIDE
  # the real store, and only then was it refused — leaving them behind. A guard
  # whose contract is "never write into the real store" had created it.
  #
  # So remember what existed first, and on refusal remove exactly what this call
  # created, innermost first. rmdir, never rm -rf: it removes only empty
  # directories, so it stops at anything that was already there or that anyone
  # else put there in the meantime.
  # Work from the NORMALISED path from here on: `.` segments and doubled slashes
  # are gone, so what mkdir creates and what the undo removes are the same
  # strings.
  parent="$(keliver_abs_of "$parent")" || return 2
  # Record what does not exist YET, deepest first. This list is the only thing
  # the undo is allowed to remove.
  local -a keliver_created=()
  local probe="$parent"
  while [ ! -d "$probe" ] && [ "$probe" != "/" ] && [ -n "$probe" ]; do
    keliver_created+=("$probe")
    probe="$(dirname "$probe")"
  done
  # EVERY exit from here undoes, and that enumeration is the claim: refusal,
  # failed mkdir, failed cd, failed mktemp. mkdir -p can fail partway — an
  # over-long component, ENOSPC, a read-only volume — and MEASURED, it then left
  # .KELIVER-PORTAL and .KELIVER-PORTAL/apps behind, which on a case-folding
  # filesystem IS the store: the same leak as the refusal path, through a
  # different return. mktemp was a fourth such exit, found by the tenth review
  # while three comments claimed every exit was covered.
  if ! mkdir -p "$parent"; then
    keliver_undo_created ${keliver_created[@]+"${keliver_created[@]}"}
    return 1
  fi
  local resolved
  if ! resolved="$(cd -P "$parent" && pwd -P)"; then
    keliver_undo_created ${keliver_created[@]+"${keliver_created[@]}"}
    return 1
  fi
  if ! keliver_refuse_protected_parent "$resolved"; then
    keliver_undo_created ${keliver_created[@]+"${keliver_created[@]}"}
    return 2
  fi
  parent="$resolved"
  local dir
  if ! dir="$(mktemp -d "$parent/keliver-$name-XXXXXX")"; then
    keliver_undo_created ${keliver_created[@]+"${keliver_created[@]}"}
    return 1
  fi
  printf '%s' "$dir"
}

# keliver_port_free_or_die PORT — refuse a port this invocation does not own.
#
# Piping a port lookup straight into a kill stops whatever is there, ours or
# not. On the default 8077 that is most likely the developer's own portal: the
# script then
# talks to the wrong app and stops their session, while still reporting PASS.
# That is the U21 defect. Refuse instead.
keliver_port_free_or_die() {
  local port="${1:?keliver_port_free_or_die needs a port}"
  # Without lsof this function would return 0 for every port — the U21 gate
  # silently off, and keliver_kill_own unable to stop the relay it started.
  # Refuse rather than degrade.
  command -v lsof >/dev/null 2>&1 || {
    echo "lsof is not available; this check cannot tell whether a port is in use" >&2
    return 1
  }
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "port $port is already in use; stop that process or free the port and re-run" >&2
    return 1
  fi
  return 0
}

# keliver_kill_own PORT PID — stop only what this invocation started: the
# launcher subshell AND the JVM it spawned (killing the subshell alone leaves
# the JVM holding the port). The listener is looked up only to kill OUR relay,
# after keliver_port_free_or_die established nothing else was there.
keliver_kill_own() {
  local port="$1" pid="$2" listener
  # `|| true`: with pipefail, lsof finding nothing propagates nonzero through
  # head, and a bare assignment would abort a `set -e` caller. No caller uses
  # -e today; this is a shared helper that will outlive them.
  listener="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | head -1)" || true
  [ -n "$listener" ] && kill "$listener" 2>/dev/null
  [ -n "$pid" ] && kill "$pid" 2>/dev/null
  return 0
}
