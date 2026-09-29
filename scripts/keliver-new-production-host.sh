#!/usr/bin/env bash
#
# keliver new-production-host — give a keliver-init app its own PRODUCTION
# Android host: a standalone Gradle build in <app>/host-android that renders your
# keliver-material screens from signed bundles, verified against your portal's
# public key, using only published dependencies (Maven Central).
#
#   keliver-new-production-host.sh --bundle-server URL
#                                  [--api-base-url URL]
#                                  [--application-id ID]
#                                  [--public-key-file PATH]
#
# Run from the APP repo root (the directory holding keliver.portal.json).
#
#   --bundle-server    where published bundles are served: the portal relay
#                      (http://<host>:8077) or anything serving its /bundles API.
#                      http:// allows cleartext traffic; use https:// for real.
#   --api-base-url     optional: the base URL guests' HostHttp requests go to.
#                      Without it the host provides no HostHttp.
#   --application-id   default: <your app package>.host
#   --public-key-file  default: keys/ed25519.pub in this app's portal store,
#                      found by keliver-store-path.sh. Only the PUBLIC key is
#                      read, ever. It is copied into the module (it is public and
#                      belongs in git), so building the host needs no store.
#
# The host is production-ONLY: it verifies every bundle's signature and has no
# development path — the tools bundle's generic development host is for that,
# and it keeps refusing production. Build with
#   ./gradlew -p host-android assembleRelease        (or assembleDebug)
#
# ALL-OR-NOTHING: everything is validated and staged before anything is written.
# A rejected input leaves the app byte-identical.
set -euo pipefail

KELIVER_VERSION="${KELIVER_VERSION:-0.3.3}"
ZIPLINE_VERSION="${ZIPLINE_VERSION:-1.22.0}"
KOTLIN_VERSION="${KOTLIN_VERSION:-2.2.0}"
COMPOSE_VERSION="${COMPOSE_VERSION:-1.8.2}"
AGP_VERSION="${AGP_VERSION:-8.12.0}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"

BUNDLE_SERVER=""; API_BASE_URL=""; APPLICATION_ID=""; KEY_FILE=""
need() { [ "$2" -ge 2 ] || { echo "$1 needs a value" >&2; exit 2; }; }
while [ $# -gt 0 ]; do
  case "$1" in
    --bundle-server)   need "$1" $#; BUNDLE_SERVER="$2"; shift 2 ;;
    --api-base-url)    need "$1" $#; API_BASE_URL="$2"; shift 2 ;;
    --application-id)  need "$1" $#; APPLICATION_ID="$2"; shift 2 ;;
    --public-key-file) need "$1" $#; KEY_FILE="$2"; shift 2 ;;
    -h|--help)         sed -n '2,33p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *)                 echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "keliver-new-production-host: $*" >&2; echo "Nothing was written." >&2; exit 1; }

APP="$(pwd -P)"
[ -f "$APP/keliver.portal.json" ] || fail "no keliver.portal.json here — run from the app root."
[ -e "$APP/host-android" ] && fail "$APP/host-android already exists — refusing to overwrite."

# The templates sit beside this script in the repository (scripts/templates) and
# one level up in the tools bundle (templates/).
TEMPLATES=""
for t in "$HERE/templates/production-host" "$HERE/../templates/production-host"; do
  [ -f "$t/build.gradle" ] && { TEMPLATES="$(cd "$t" && pwd -P)"; break; }
done
[ -n "$TEMPLATES" ] || fail "the production-host templates are missing (looked in $HERE/templates and $HERE/../templates)."

URL_RE='^https?://[^[:space:]"'"'"']+$'
[ -n "$BUNDLE_SERVER" ] || fail "--bundle-server is required (e.g. http://10.0.2.2:8077 for an emulator reaching this machine's relay)."
[[ "$BUNDLE_SERVER" =~ $URL_RE ]] || fail "--bundle-server must be an http:// or https:// URL (got '$BUNDLE_SERVER')."
[ -z "$API_BASE_URL" ] || [[ "$API_BASE_URL" =~ $URL_RE ]] || fail "--api-base-url must be an http:// or https:// URL (got '$API_BASE_URL')."

# The app's package: every screen declares <root>.screens (the keliver-init
# layout, the same rule keliver-new-device-target.sh uses).
ROOT_PKG="$(python3 - "$APP" <<'PY'
import glob, os, re, sys
app = sys.argv[1]
pkgs = set()
for f in glob.glob(os.path.join(app, 'src/jsMain/kotlin/screens/*.kt')):
    m = re.search(r'^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$', open(f, encoding='utf-8').read(), re.M)
    if m: pkgs.add(m.group(1))
if len(pkgs) != 1 or not next(iter(pkgs)).endswith('.screens'):
    print("ERROR screens under src/jsMain/kotlin/screens/ must all declare one '<package>.screens' (found: %s)" % (sorted(pkgs) or 'none'))
    raise SystemExit(3)
print(next(iter(pkgs))[: -len('.screens')])
PY
)" || fail "${ROOT_PKG#ERROR }"
PACKAGE="$ROOT_PKG.host"
[ -n "$APPLICATION_ID" ] || APPLICATION_ID="$PACKAGE"
ID_RE='^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$'
[[ "$APPLICATION_ID" =~ $ID_RE ]] || fail "--application-id must look like com.example.app (got '$APPLICATION_ID')."
NAME="$(sed -n "s/^[[:space:]]*rootProject\.name[[:space:]]*=[[:space:]]*['\"]\([^'\"]*\)['\"].*/\1/p" "$APP/settings.gradle" 2>/dev/null | head -1)"
[ -n "$NAME" ] || NAME="$(basename "$APP")"
[[ "$NAME" =~ ^[A-Za-z0-9._-]+$ ]] || NAME="app"

# The public key: an explicit file, or this app's store. Only ed25519.pub.
if [ -z "$KEY_FILE" ]; then
  RESOLVE="$HERE/keliver-store-path.sh"
  [ -x "$RESOLVE" ] || fail "keliver-store-path.sh not found next to this script; pass --public-key-file."
  STORE="$("$RESOLVE" "$APP")" || fail "this app's portal store could not be resolved (see above); pass --public-key-file."
  case "$STORE" in /*) ;; *) fail "the store resolver named a non-absolute path ('$STORE')." ;; esac
  KEY_FILE="$STORE/keys/ed25519.pub"
  [ -f "$KEY_FILE" ] || fail "no public key at $KEY_FILE. Start this app's portal once (keliver-portal) so it creates its signing identity, or pass --public-key-file."
fi
[ -f "$KEY_FILE" ] || fail "no such file: $KEY_FILE"
case "$(basename "$KEY_FILE")" in *.priv) fail "refusing to read a private key ($KEY_FILE); the host needs the PUBLIC key." ;; esac
KEY_HEX="$(tr -d '[:space:]' < "$KEY_FILE")"
[[ "$KEY_HEX" =~ ^[0-9a-fA-F]{64}$ ]] || fail "$KEY_FILE is not a 64-hex-digit Ed25519 public key."

# --- stage everything, then move it into place in one step -----------------
STAGE="$(mktemp -d "$APP/.host-android.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
PKG_PATH="${PACKAGE//.//}"
subst() { # <src> <dst>
  mkdir -p "$(dirname "$2")"
  python3 - "$1" "$2" "$NAME" "$PACKAGE" "$APPLICATION_ID" "$BUNDLE_SERVER" "$API_BASE_URL" \
    "$KELIVER_VERSION" "$ZIPLINE_VERSION" "$KOTLIN_VERSION" "$COMPOSE_VERSION" "$AGP_VERSION" <<'PY'
import sys
src, dst, name, pkg, appid, server, api, kv, zv, ktv, cv, agp = sys.argv[1:13]
s = open(src, encoding='utf-8').read()
for k, v in {'NAME': name, 'PACKAGE': pkg, 'APPLICATION_ID': appid, 'BUNDLE_SERVER': server,
             'API_BASE_URL': api, 'KELIVER_VERSION': kv, 'ZIPLINE_VERSION': zv,
             'KOTLIN_VERSION': ktv, 'COMPOSE_VERSION': cv, 'AGP_VERSION': agp}.items():
    s = s.replace('@@' + k + '@@', v)
assert '@@' not in s, 'unsubstituted placeholder in ' + src
open(dst, 'w', encoding='utf-8').write(s)
PY
}
for f in settings.gradle gradle.properties build.gradle src/main/AndroidManifest.xml; do
  subst "$TEMPLATES/$f" "$STAGE/$f"
done
for f in "$TEMPLATES"/src/main/kotlin/*.kt; do
  subst "$f" "$STAGE/src/main/kotlin/$PKG_PATH/$(basename "$f")"
done
mkdir -p "$STAGE/src/main/assets"
printf '%s\n' "$KEY_HEX" > "$STAGE/src/main/assets/portal_ed25519.pub"
cat > "$STAGE/.gitignore" <<'EOF'
/build/
/.gradle/
/local.properties
EOF
mv "$STAGE" "$APP/host-android"
trap - EXIT

echo "created host-android/ — this app's production Android host"
echo "  package          $PACKAGE"
echo "  application id   $APPLICATION_ID"
echo "  bundle server    $BUNDLE_SERVER"
echo "  HostHttp         ${API_BASE_URL:-not provided (no --api-base-url)}"
echo "  trusts the key   ${KEY_HEX:0:8}… (from $KEY_FILE) — commit host-android/src/main/assets/portal_ed25519.pub"
echo "next:"
echo "  ./gradlew -p host-android assembleDebug     (needs an Android SDK: ANDROID_HOME, or host-android/local.properties)"
echo "  publish a bundle (the relay's POST /publish), then install and launch the host."
