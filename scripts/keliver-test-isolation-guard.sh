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

# Print the JVM's effective user.home under the current environment.
keliver_effective_jvm_home() {
  local out
  out="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/^ *user\.home/ {print $2; exit}')"
  printf '%s' "${out%$'\r'}"
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
  jvm_home="$(keliver_effective_jvm_home)"
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
# any one script because there are NINE callers (six of which CI runs), and the
# last time a rule like this had one copy per caller the copies drifted.
#
# scripts/keliver-refusal-check.sh is its regression suite. It exists because
# this refusal failed open in three consecutive reviewed commits — on a
# non-existent ancestor, on a case-variant path, and on a .. segment — and every
# one of those was reachable by a ten-line harness that had not been written.
#
# BY IDENTITY, NOT BY NAME. The first version of this compared resolved path
# strings. On macOS the default filesystem is case-INSENSITIVE, so
# ~/.KELIVER-PORTAL is the very same directory as ~/.keliver-portal — same
# device, same inode — and every case-sensitive pattern missed it: MEASURED, a
# disposable ed25519.priv was written inside .keliver-portal through a
# case-variant path. So each existing ancestor of the parent is compared to each
# protected root by device+inode, which is immune to case, to symlinks and to
# any other spelling of the same directory. The name patterns remain only as a
# fallback for a path whose components do not exist yet, where there is no inode
# to compare.
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
  if [ -z "$KELIVER_STAT_FMT" ]; then
    for fmt in -c -f; do
      id="$(stat "$fmt" '%d:%i' / 2>/dev/null)"
      case "$id" in
        *[!0-9:]*|'') ;;
        *:*) KELIVER_STAT_FMT="$fmt"; break;;
      esac
    done
    [ -n "$KELIVER_STAT_FMT" ] || KELIVER_STAT_FMT="none"
  fi
  [ "$KELIVER_STAT_FMT" = "none" ] && return 1
  id="$(stat "$KELIVER_STAT_FMT" '%d:%i' "$1" 2>/dev/null)" || return 1
  case "$id" in
    *[!0-9:]*|'') return 1;;
    *:*) printf '%s' "$id"; return 0;;
  esac
  return 1
}

keliver_stat_usable() { KELIVER_STAT_FMT=""; keliver_stat_id / >/dev/null; }

keliver_same_dir() {
  [ -d "$1" ] && [ -d "$2" ] || return 1
  local a b
  a="$(keliver_stat_id "$1")" || return 1
  b="$(keliver_stat_id "$2")" || return 1
  [ "$a" = "$b" ]
}

# The absolute path, with the deepest EXISTING ancestor resolved and the
# remainder re-appended. A failed cd is a refusal, not an empty string: an
# unreadable ancestor used to truncate the answer and let the check pass.
keliver_abs_of() {
  local p rest cur resolved
  case "$1" in /*) p="$1";; *) p="$PWD/$1";; esac
  rest=""; cur="$p"
  while [ ! -d "$cur" ] && [ "$cur" != "/" ] && [ -n "$cur" ]; do
    rest="/$(basename "$cur")$rest"
    cur="$(dirname "$cur")"
    case "$cur" in /*) ;; *) return 1;; esac
  done
  [ -d "$cur" ] || { printf '%s\n' "$p"; return 0; }
  resolved="$(cd "$cur" 2>/dev/null && pwd -P)" || return 1
  [ -n "$resolved" ] || return 1
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
# keliver_effective_jvm_home SPAWNS A JVM, and this is consulted once per
# ancestor level, so it is resolved once per shell. If java cannot be run the
# JVM-home roots are simply not added; the $HOME ones still are, and those are
# the same path except where user.home and HOME disagree. Stated because a
# silently smaller protected set should not be a surprise.
KELIVER_JVM_HOME_MEMO=""
keliver_protected_roots() {
  local h abs
  if [ -z "$KELIVER_JVM_HOME_MEMO" ]; then
    KELIVER_JVM_HOME_MEMO="$(keliver_effective_jvm_home 2>/dev/null)"
    [ -n "$KELIVER_JVM_HOME_MEMO" ] || KELIVER_JVM_HOME_MEMO="-"
  fi
  for h in "${HOME:-}" "$KELIVER_JVM_HOME_MEMO"; do
    [ -n "$h" ] && [ "$h" != "-" ] || continue
    # HOME=/ must still protect /.keliver-portal: stripping the slash left an
    # empty prefix, which was then skipped entirely.
    [ "$h" = "/" ] || h="${h%/}"
    [ "$h" = "/" ] && h=""
    printf '%s\n%s\n' "$h/.keliver-portal" "$h/.gradle"
  done
  # PORTAL_STORE is a caller's environment, so it may be relative — and a
  # relative one used verbatim made the CURRENT DIRECTORY a protected root,
  # refusing legitimate parents.
  if [ -n "${PORTAL_STORE:-}" ]; then
    abs="$(keliver_abs_of "$PORTAL_STORE" 2>/dev/null)" || abs=""
    [ -n "$abs" ] && [ "$abs" != "/" ] && printf '%s\n' "${abs%/}"
  fi
  return 0
}

keliver_refuse_protected_parent() {
  local given="$1" abs cur root matched="" roots
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
  case "$given" in '~'|'~'/*)
    echo "keliver: refusing '''$given''' — ~ was not expanded (single quotes?)" >&2; return 2;;
  esac
  abs="$(keliver_abs_of "$given")" || {
    echo "keliver: refusing '''$given''' — its path could not be resolved, so it cannot be" >&2
    echo "  shown to be outside the real store." >&2
    return 2
  }
  # A .. segment past a component that does not exist yet survives into the
  # path mkdir -p later creates, and the kernel resolves it somewhere else
  # entirely: MEASURED, <home>/nope/../.keliver-portal was allowed here and then
  # created a run directory INSIDE the store. Nothing legitimate needs .., so it
  # is refused rather than normalised.
  case "/$abs/" in *"/../"*)
    echo "keliver: refusing '''$given''' — it contains a .. segment that cannot be resolved" >&2
    echo "  before the directory exists, and mkdir would resolve it elsewhere." >&2
    return 2;;
  esac
  # A dangling symlink passes every check below and then fails in mkdir with a
  # diagnostic about the wrong thing.
  if [ -L "$given" ] && [ ! -e "$given" ]; then
    echo "keliver: refusing '''$given''' — it is a symlink pointing at nothing." >&2
    return 2
  fi
  roots="$(keliver_protected_roots)"
  # By identity: every existing ancestor, against every protected root.
  cur="$abs"
  while : ; do
    while IFS= read -r root; do
      [ -n "$root" ] || continue
      if keliver_same_dir "$cur" "$root"; then matched="$root"; break; fi
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
  if [ -n "${HOME:-}" ] && keliver_same_dir "$abs" "$HOME"; then
    echo "keliver: refusing to use your home directory as a disposable run parent" >&2
    return 2
  fi
  return 0
}


# Usage:  RUN="$(keliver_make_run_dir "$PARENT" acceptance)"
keliver_make_run_dir() {
  local parent="$1" name="${2:-run}"
  [ -n "$parent" ] || { echo "keliver_make_run_dir: no parent given" >&2; return 2; }
  if [ -e "$parent" ] && [ ! -d "$parent" ]; then
    echo "keliver_make_run_dir: $parent exists and is not a directory" >&2; return 2
  fi
  keliver_refuse_protected_parent "$parent" || return 2
  mkdir -p "$parent" || return 1
  parent="$(cd "$parent" && pwd -P)" || return 1
  # AGAIN, on the path the kernel actually resolved. The first call runs on a
  # path that may not exist yet, so it reasons about spellings; this one runs on
  # the real directory, after mkdir -p and after every symlink and .. has been
  # resolved by cd. It is the only placement that cannot be out-spelled, and it
  # is cheap — the stat flavour and the JVM home are both already memoised.
  keliver_refuse_protected_parent "$parent" || return 2
  local dir
  dir="$(mktemp -d "$parent/keliver-$name-XXXXXX")" || return 1
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
