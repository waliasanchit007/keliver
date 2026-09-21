#!/usr/bin/env bash
#
# keliver-resolve-store — resolve a store, or refuse. Sourceable.
#
#   . scripts/keliver-resolve-store.sh
#   STORE="$(keliver_require_store "<what this is for>" <resolver> <app> [args...])" || exit $?
#
# WHY (#78). keliver-store-path.sh used to answer whatever happened: when JVM
# discovery failed it fell back to $HOME. Callers were written against that, so
# most of them took its stdout and never looked at its exit code. Once the
# resolver started REFUSING, those callers received "" and carried on:
#
#   * keliver-adopt-legacy-store.sh built "/<rel>" destinations and tried to copy
#     a legacy PRIVATE SIGNING KEY to /keys/ed25519.priv, printed "copied", and
#     exited 0 — measured. macOS escaped it only because / is read-only.
#   * fingerprint helpers ran `find "" -type f` and compared empty to empty, so
#     "the store is unchanged" passed by having no evidence at all.
#   * `case "" in "$APP"/*)` does not match, so "the store is outside the app"
#     passed for an app whose store was never found.
#
# The common shape is an unchecked status turning MISSING EVIDENCE into a PASS.
# This makes the check one line and the same line everywhere.
#
# It deliberately does NOT redirect the resolver's stderr: the resolver prints
# the actionable part ("put a working java on PATH, or pass --home <dir>"), and a
# caller that hides that leaves the operator with "it failed" and nothing else.
#
# Not for the four scripts shipped in the tools bundle
# (keliver-store-path.sh, keliver-record-http.sh, keliver-adopt-legacy-store.sh,
# keliver-store-recover.sh) — those must stay self-contained, and carry the same
# check inline.

# keliver_require_store <what-for> <resolver> <app> [resolver-args...]
# Prints the resolved absolute path on stdout. On any failure prints a
# diagnostic on stderr and returns non-zero, having printed nothing on stdout.
keliver_require_store() {
  local what="$1"; shift
  local resolver="$1"; shift
  local out rc
  out="$("$resolver" "$@")"; rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "keliver: $what — the store could not be resolved (resolver exit $rc)." >&2
    echo "  The resolver's own diagnostic is above and says how to fix it." >&2
    echo "  Refusing to continue: every path derived from it would be meaningless." >&2
    return "$rc"
  fi
  # Exit 0 with nothing is not success. A caller that treats "" as a path either
  # writes to /<rel> or compares emptiness against emptiness and calls it a pass.
  if [ -z "$out" ]; then
    echo "keliver: $what — the resolver exited 0 but named no store. Refusing." >&2
    return 4
  fi
  case "$out" in
    /*) ;;
    *)  echo "keliver: $what — the resolver named a non-absolute store ('$out')," >&2
        echo "  which resolves against whatever directory the caller happens to be in." >&2
        echo "  Refusing." >&2
        return 4;;
  esac
  printf '%s' "$out"
}
