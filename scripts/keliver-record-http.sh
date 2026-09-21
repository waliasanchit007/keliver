#!/usr/bin/env bash
#
# Explicit local client for H2 HTTP recording. The per-process token is read
# from the relay store and is never printed or passed on the command line.
#
#   keliver-record-http.sh start <upstream-id> <fixture-set>
#   keliver-record-http.sh record <session-id> <HostHttpRequest.json>
#   keliver-record-http.sh close <session-id>
set -euo pipefail

# The APP is the working directory, not the script's parent. Shipped in a
# bundle, "the script's parent" is the bundle root, and this looked for the
# app's keliver.portal.json inside keliver-portal-tools.
ROOT="${KELIVER_APP_DIR:-$PWD}"
# The resolver sits next to this script in a bundle (bin/) and one level up in
# the repository (scripts/). Find it either way.
keliver_store_path_script() {
  local here; here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
  if [ -x "$here/keliver-store-path.sh" ]; then echo "$here/keliver-store-path.sh"
  elif [ -x "$here/../scripts/keliver-store-path.sh" ]; then echo "$here/../scripts/keliver-store-path.sh"
  else echo "keliver-store-path.sh not found next to $here" >&2; return 1; fi
}

CONFIG="$ROOT/keliver.portal.json"
[ -r "$CONFIG" ] || {
  echo "no keliver.portal.json in $ROOT — run this from the app directory, or set KELIVER_APP_DIR" >&2
  exit 66
}
PORTAL_URL="${PORTAL_URL:-http://127.0.0.1:$(python3 -c \
  "import json; print(json.load(open('$CONFIG')).get('port', 8077))")}"
# One store contract: ask the resolver, never re-derive it here. This used to
# default to ~/.keliver-portal, which stopped being the store once the relay
# moved to a per-app directory — the token was then looked for in the wrong
# place and recording appeared to be off.
# CHECKED, and inline rather than through scripts/keliver-resolve-store.sh:
# this script SHIPS IN THE TOOLS BUNDLE and must stay self-contained.
#
# Unchecked, a resolver refusal (#78) arrived as STORE="" and TOKEN_FILE became
# "/http-record.token" — which does not exist, so every request went out
# unauthenticated and the failure surfaced as an HTTP error from the relay. "I
# could not work out where your token lives" and "the relay rejected you" are
# different problems with different fixes, and the second is the wrong thing to
# tell someone.
#
# Skipped entirely when PORTAL_HTTP_RECORD_TOKEN_FILE names the token directly:
# the store is then not consulted, so demanding it would refuse a caller who has
# already said where the token is.
if [ -n "${PORTAL_HTTP_RECORD_TOKEN_FILE:-}" ]; then
  TOKEN_FILE="$PORTAL_HTTP_RECORD_TOKEN_FILE"
else
  STORE="$("$(keliver_store_path_script)" "$ROOT")" || {
    echo "keliver-record-http: this app's store could not be resolved (see above), so the" >&2
    echo "  recording token cannot be located. This is NOT an authentication failure and" >&2
    echo "  not a response from the relay — nothing was sent." >&2
    echo "  Fix the resolver, or set PORTAL_HTTP_RECORD_TOKEN_FILE to the token's path." >&2
    exit 4
  }
  case "$STORE" in
    /*) ;;
    '') echo "keliver-record-http: the resolver exited 0 but named no store; nothing sent." >&2
        exit 4;;
    *)  echo "keliver-record-http: the resolver named a non-absolute store ('$STORE');" >&2
        echo "  nothing sent." >&2
        exit 4;;
  esac
  TOKEN_FILE="$STORE/http-record.token"
fi

usage() {
  echo "usage:" >&2
  echo "  $0 start <upstream-id> <fixture-set>" >&2
  echo "  $0 record <session-id> <HostHttpRequest.json>" >&2
  echo "  $0 close <session-id>" >&2
  exit 64
}

[[ -r "$TOKEN_FILE" ]] || {
  echo "recording token not found; start the relay with PORTAL_HTTP_RECORD=1" >&2
  exit 69
}

TOKEN="$(<"$TOKEN_FILE")"
command="${1:-}"

portal_curl() {
  curl --fail-with-body --silent --show-error --config - "$@" <<EOF
header = "X-Portal-Record-Token: $TOKEN"
EOF
}

case "$command" in
  start)
    [[ $# -eq 3 ]] || usage
    body="$(python3 - "$2" "$3" <<'PY'
import json
import sys
print(json.dumps({"upstream": sys.argv[1], "fixtureSet": sys.argv[2]}))
PY
)"
    portal_curl \
      -H "Content-Type: application/json" \
      --data-binary "$body" \
      "$PORTAL_URL/http-record/sessions"
    ;;
  record)
    [[ $# -eq 3 && -r "$3" ]] || usage
    portal_curl \
      -H "Content-Type: application/json" \
      --data-binary "@$3" \
      "$PORTAL_URL/http-record?session=$2"
    ;;
  close)
    [[ $# -eq 2 ]] || usage
    portal_curl \
      --data-binary "" \
      "$PORTAL_URL/http-record/close?session=$2"
    ;;
  *)
    usage
    ;;
esac

echo
