#!/usr/bin/env bash
#
# keliver-store-path — print the document store an app repo resolves to.
#
#   scripts/keliver-store-path.sh <app-dir> [--home DIR] [--default] [--explain]
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
#   3. <app>/.gradle/keliver-store-path                (the binding pointer)
#   4. ~/.keliver-portal/apps/<slug>-<hash of repo>    (the default)
#
# --default reports step 4 only, ignoring 1-3: "what store would this app take
# if it had no binding?". Used by StoreContractTest; the recovery command asks
# --explain instead, because WHICH rule decided is what it branches on.
#
# --explain prints "<step>\t<path>" instead of the bare path, where <step> is
# env, config, pointer, default or adopted. keliver-store-recover.sh needs it:
# "the app resolves somewhere else" and "the app is PINNED somewhere else by a
# committed setting" call for different answers, and re-deriving the precedence
# in a second place is how the rules drifted the first time.
#
# The authoritative implementation is PortalConfig.storeDir(); StoreContractTest
# asserts this script agrees with it. The rules the two of them share are in
# docs/STORE_IDENTITY.md.
#
# Exit codes: 0 resolved, 2 usage, 3 the app has more than one existing store
# (a split identity — see U25.1 and keliver-store-recover.sh).
#
set -uo pipefail
APP="${1:?usage: $0 <app-dir> [--home DIR] [--default]}"
HOME_DIR=""
ONLY_DEFAULT=0
EXPLAIN=0
shift
while [ $# -gt 0 ]; do
  case "$1" in
    --home) HOME_DIR="${2:?--home needs a value}"; shift 2 ;;
    --default) ONLY_DEFAULT=1; shift ;;
    --explain) EXPLAIN=1; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$HOME_DIR" ] || HOME_DIR="$(java -XshowSettings:properties -version 2>&1 \
  | awk -F'= ' '/^ *user\.home/ {print $2; exit}')"
[ -n "$HOME_DIR" ] || HOME_DIR="$HOME"

python3 - "$APP" "$HOME_DIR" "${PORTAL_STORE:-}" "$ONLY_DEFAULT" "$EXPLAIN" <<'PY'
import json, os, sys, hashlib
# realpath, not abspath: abspath collapses ".." LEXICALLY, while the JVM (and
# the kernel) resolve the symlink first. For $W/x/link/.. that is two different
# app directories, and therefore two identities.
app, home, env = os.path.realpath(sys.argv[1]), sys.argv[2].strip(), sys.argv[3]
only_default = sys.argv[4] == "1"
explain = sys.argv[5] == "1"

def answer(step, path):
    print("%s\t%s" % (step, path) if explain else path)
    raise SystemExit

def expand(s):
    if s.startswith("~/"):
        return os.path.join(home, s[2:])
    return s if os.path.isabs(s) else os.path.join(app, s)

if not only_default:
    if env:
        answer("env", os.path.abspath(env))

    cfg = os.path.join(app, "keliver.portal.json")
    if os.path.isfile(cfg):
        try:
            store = json.load(open(cfg)).get("store")
        except Exception:
            store = None
        # `.strip()`: Kotlin drops a blank value as "not set", and a
        # whitespace-only one used to be kept here and expanded to a directory
        # INSIDE the app tree — the same failure as `""`, with the two sides
        # swapped.
        if store and store.strip():
            answer("config", expand(store))

    pointer = os.path.join(app, ".gradle", "keliver-store-path")
    if os.path.isfile(pointer):
        p = open(pointer).read().strip()
        if p:
            answer("pointer", p)

# --- step 4: the default -----------------------------------------------------
# Both halves derive from the CANONICAL path. The slug is computed over UTF-8
# BYTES, with runs of '-' collapsed, so that Kotlin (which used to map each
# UTF-16 code unit) and Python (which maps each code point) cannot produce two
# different directory names for one app. See PortalConfig.storeSlug.
SAFE = set(b"abcdefghijklmnopqrstuvwxyz0123456789._-")

def slug(name):
    out = bytearray()
    for b in name.encode("utf-8"):
        if 0x41 <= b <= 0x5A:      # ASCII upper -> lower
            b += 32
        out.append(b if b in SAFE else ord("-"))
    s = out.decode("ascii")
    while "--" in s:
        s = s.replace("--", "-")
    s = s.strip("-")
    return s if s and s not in (".", "..") else "app"

real = app  # already realpath'd above
h = hashlib.sha256(real.encode()).hexdigest()[:8]
apps = os.path.join(home, ".keliver-portal", "apps")
preferred = os.path.join(apps, "%s-%s" % (slug(os.path.basename(real)), h))

# An existing store for this app can differ from the name computed today only
# in its slug — the hash was always canonical. Adopt it rather than mint a
# second identity; refuse when there is more than one to choose from. The scan
# runs even when `preferred` exists: after a symlink split BOTH names are
# present and one of them IS the canonical one, so returning it on sight would
# silently pick between two identities.
try:
    existing = sorted(
        os.path.join(apps, n) for n in os.listdir(apps)
        if n.endswith("-" + h) and os.path.isdir(os.path.join(apps, n))
    )
except OSError:
    existing = []

if not existing:
    answer("default", preferred)
elif len(existing) == 1:
    answer("default" if existing[0] == preferred else "adopted", existing[0])
else:
    sys.stderr.write(
        "portal store split: %d stores exist for %s.\n" % (len(existing), real)
        # Full, symlink-resolved paths: keliver-store-recover.sh matches
        # --store against these lines, and a basename can collide.
        + "".join("  - %s\n" % os.path.realpath(e) for e in existing)
        + "  the name a first boot would choose today is %s\n" % os.path.basename(preferred)
        + "Choose the one to keep (its identity is the one your published bundles\n"
        + "verify against) and bind this app to it:\n"
        + "  keliver-store-recover.sh %s --store <one of the above>\n" % real
    )
    raise SystemExit(3)
PY
