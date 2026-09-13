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
# Usage:  RUN="$(keliver_make_run_dir "$PARENT" acceptance)"
keliver_make_run_dir() {
  local parent="$1" name="${2:-run}"
  [ -n "$parent" ] || { echo "keliver_make_run_dir: no parent given" >&2; return 2; }
  if [ -e "$parent" ] && [ ! -d "$parent" ]; then
    echo "keliver_make_run_dir: $parent exists and is not a directory" >&2; return 2
  fi
  mkdir -p "$parent" || return 1
  parent="$(cd "$parent" && pwd -P)" || return 1
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
