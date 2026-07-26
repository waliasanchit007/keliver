#!/usr/bin/env bash
#
# Explicit local client for H2 HTTP recording. The per-process token is read
# from the relay store and is never printed or passed on the command line.
#
#   keliver-record-http.sh start <upstream-id> <fixture-set>
#   keliver-record-http.sh record <session-id> <HostHttpRequest.json>
#   keliver-record-http.sh close <session-id>
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG="$ROOT/keliver.portal.json"
PORTAL_URL="${PORTAL_URL:-http://127.0.0.1:$(python3 -c \
  "import json; print(json.load(open('$CONFIG')).get('port', 8077))")}"
STORE="$(python3 -c \
  "import json; print(json.load(open('$CONFIG')).get('store', '~/.keliver-portal'))")"
STORE="${STORE/#\~/$HOME}"
TOKEN_FILE="${PORTAL_HTTP_RECORD_TOKEN_FILE:-$STORE/http-record.token}"

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
