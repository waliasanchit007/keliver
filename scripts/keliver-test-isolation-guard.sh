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
keliver_effective_store() {
  local app="$1" home="$2"
  if [ -n "${PORTAL_STORE:-}" ]; then printf '%s' "$PORTAL_STORE"; return; fi
  python3 - "$app" "$home" <<'PY'
import json, os, sys, hashlib
app, home = os.path.abspath(sys.argv[1]), sys.argv[2]
cfg = os.path.join(app, "keliver.portal.json")
store = None
if os.path.isfile(cfg):
    try:
        store = json.load(open(cfg)).get("store")
    except Exception:
        store = None
if store:
    if store.startswith("~/"):
        print(os.path.join(home, store[2:]))
    elif os.path.isabs(store):
        print(store)
    else:
        print(os.path.join(app, store))
else:
    real = os.path.realpath(app)
    h = hashlib.sha256(real.encode()).hexdigest()[:8]
    slug = "".join(c if c.isalnum() or c in "._-" else "-" for c in os.path.basename(real).lower()) or "app"
    print(os.path.join(home, ".keliver-portal", "apps", f"{slug}-{h}"))
PY
}

# The gate. Exits non-zero (and says why) rather than letting a test run.
keliver_require_isolated_store() {
  local root="$1" app="$2"
  local fail=0
  root="$(cd "$root" 2>/dev/null && pwd -P)" || { echo "guard: disposable root does not exist: $1" >&2; return 1; }

  local jvm_home store
  jvm_home="$(keliver_effective_jvm_home)"
  store="$(keliver_effective_store "$app" "$jvm_home")"

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
