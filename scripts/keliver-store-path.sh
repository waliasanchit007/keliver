#!/usr/bin/env bash
#
# keliver-store-path — print the document store an app repo resolves to.
#
#   scripts/keliver-store-path.sh <app-dir> [--home DIR]
#
# THE STORE CONTRACT, in one place. Every consumer must agree on it: the relay
# (which generates the signing keys), the publisher (which signs with them),
# the device hosts (which embed the public key), and the recording client
# (which reads the per-process token). They disagreed once — the relay moved to
# a per-app store while the others still read ~/.keliver-portal/keys — and the
# result is a bundle signed by one identity and verified against another.
#
# Resolution order:
#   1. $PORTAL_STORE                                   (explicit, one run)
#   2. "store" in <app>/keliver.portal.json            (explicit, committed)
#   3. <app>/.gradle/keliver-store-path                (pointer the relay writes)
#   4. ~/.keliver-portal/apps/<slug>-<hash of repo>    (the default)
#
# The authoritative implementation is PortalConfig.storeDir(); StoreContractTest
# asserts this script agrees with it.
#
set -uo pipefail
APP="${1:?usage: $0 <app-dir> [--home DIR]}"
HOME_DIR=""
shift
while [ $# -gt 0 ]; do
  case "$1" in
    --home) HOME_DIR="${2:?--home needs a value}"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$HOME_DIR" ] || HOME_DIR="$(java -XshowSettings:properties -version 2>&1 \
  | awk -F'= ' '/^ *user\.home/ {print $2; exit}')"
[ -n "$HOME_DIR" ] || HOME_DIR="$HOME"

python3 - "$APP" "$HOME_DIR" "${PORTAL_STORE:-}" <<'PY'
import json, os, sys, hashlib
app, home, env = os.path.abspath(sys.argv[1]), sys.argv[2].strip(), sys.argv[3]

def expand(s):
    if s.startswith("~/"):
        return os.path.join(home, s[2:])
    return s if os.path.isabs(s) else os.path.join(app, s)

if env:
    print(os.path.abspath(env)); raise SystemExit

cfg = os.path.join(app, "keliver.portal.json")
if os.path.isfile(cfg):
    try:
        store = json.load(open(cfg)).get("store")
    except Exception:
        store = None
    if store:
        print(expand(store)); raise SystemExit

pointer = os.path.join(app, ".gradle", "keliver-store-path")
if os.path.isfile(pointer):
    p = open(pointer).read().strip()
    if p:
        print(p); raise SystemExit

real = os.path.realpath(app)
h = hashlib.sha256(real.encode()).hexdigest()[:8]
slug = "".join(c if c.isalnum() or c in "._-" else "-" for c in os.path.basename(real).lower()) or "app"
print(os.path.join(home, ".keliver-portal", "apps", f"{slug}-{h}"))
PY
