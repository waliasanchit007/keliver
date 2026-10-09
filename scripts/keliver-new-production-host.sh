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
#                                  [--channel NAME]
#   keliver-new-production-host.sh --embed --into DIR [--module NAME]
#                                  --bundle-server URL [--api-base-url URL]
#                                  [--public-key-file PATH] [--channel NAME]
#
# Run from the APP repo root (the directory holding keliver.portal.json).
#
#   --bundle-server    where published bundles are served: the portal relay
#                      (http://<host>:8077) or anything serving its /bundles API.
#                      http:// allows cleartext traffic; use https:// for real.
#   --api-base-url     optional: the base URL guests' HostHttp requests go to.
#                      Without it the host provides no HostHttp.
#   --application-id   default: <your app package>.host
#   --channel          default: stable. The release channel this host takes from
#                      bundles/index.json besides stable (W4.4), e.g. beta for
#                      testers: lower-case letters, digits and '-'.
#   --embed            W2: write the host as a LIBRARY module into an EXISTING
#                      Android app instead: DIR/NAME (default keliver-host),
#                      with KeliverHost, KeliverScreen and KeliverView, the
#                      same Kotlin as the standalone host. DIR is that app's
#                      root (it has a settings.gradle). Nothing of the existing
#                      app is edited: this prints the lines to add. Your app
#                      supplies the plugins: Kotlin 2.2.x with its Compose
#                      plugin and app.cash.zipline 1.22.0.
#   --public-key-file  default: keys/ed25519.pub in this app's portal store,
#                      found by keliver-store-path.sh. The key is copied into
#                      the module (it is public and belongs in git), so building
#                      the host needs no store.
#
#                      A private key has the same format as a public one, so a
#                      file cannot prove which it is. This refuses a file whose
#                      real path (links resolved) ends in .priv, and — whenever
#                      this app's store resolves — a key that is not that
#                      store's ed25519.pub. Without a store it cannot tell: pass
#                      only your ed25519.pub. Nothing here opens ed25519.priv.
#
# The host is production-ONLY: it verifies every bundle's signature and has no
# development path — the tools bundle's generic development host is for that,
# and it keeps refusing production. Build with
#   ./gradlew -p host-android assembleDebug
# assembleRelease needs https:// servers and YOUR signing config in
# host-android/build.gradle before the APK will install.
#
# Version overrides (normally unset): KELIVER_HOST_KELIVER_VERSION (default: the
# app's appRuntime.keliverVersion, else 0.3.3), KELIVER_HOST_ZIPLINE_VERSION,
# KELIVER_HOST_KOTLIN_VERSION, KELIVER_HOST_COMPOSE_VERSION, KELIVER_HOST_AGP_VERSION.
#
# ALL-OR-NOTHING: everything is validated and staged before anything is written.
# A rejected input leaves the app byte-identical.
set -euo pipefail

# Prefixed on purpose: KOTLIN_VERSION and friends are common on CI images, and
# an unrelated one silently changed the generated build.
ZIPLINE_VERSION="${KELIVER_HOST_ZIPLINE_VERSION:-1.22.0}"
KOTLIN_VERSION="${KELIVER_HOST_KOTLIN_VERSION:-2.2.0}"
COMPOSE_VERSION="${KELIVER_HOST_COMPOSE_VERSION:-1.8.2}"
AGP_VERSION="${KELIVER_HOST_AGP_VERSION:-8.12.0}"
# Resolve a symlinked invocation to the real script, so the templates are found.
SELF="${BASH_SOURCE[0]}"
while [ -L "$SELF" ]; do
  link="$(readlink "$SELF")"; case "$link" in /*) SELF="$link" ;; *) SELF="$(dirname "$SELF")/$link" ;; esac
done
HERE="$(cd "$(dirname "$SELF")" && pwd -P)"

BUNDLE_SERVER=""; API_BASE_URL=""; APPLICATION_ID=""; KEY_FILE=""; CHANNEL="stable"
EMBED=false; INTO=""; MODULE="keliver-host"
need() { [ "$2" -ge 2 ] || { echo "$1 needs a value" >&2; exit 2; }; }
while [ $# -gt 0 ]; do
  case "$1" in
    --bundle-server)   need "$1" $#; BUNDLE_SERVER="$2"; shift 2 ;;
    --api-base-url)    need "$1" $#; API_BASE_URL="$2"; shift 2 ;;
    --application-id)  need "$1" $#; APPLICATION_ID="$2"; shift 2 ;;
    --public-key-file) need "$1" $#; KEY_FILE="$2"; shift 2 ;;
    --channel)         need "$1" $#; CHANNEL="$2"; shift 2 ;;
    --embed)           EMBED=true; shift ;;
    --into)            need "$1" $#; INTO="$2"; shift 2 ;;
    --module)          need "$1" $#; MODULE="$2"; shift 2 ;;
    -h|--help)         sed -n '2,60p' "$SELF"; exit 0 ;;
    *)                 echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "keliver-new-production-host: $*" >&2; echo "Nothing was written." >&2; exit 1; }

APP="$(pwd -P)"
[ -f "$APP/keliver.portal.json" ] || fail "no keliver.portal.json here — run from the app root."
if $EMBED; then
  [ -n "$INTO" ] || fail "--embed needs --into <the existing Android app's root>."
  [ -z "$APPLICATION_ID" ] || fail "--application-id is for the standalone host; an embedded host is a library in YOUR app."
  [[ "$MODULE" =~ ^[A-Za-z][A-Za-z0-9_-]{0,63}$ ]] || fail "--module must be a plain module name (got '$MODULE')."
  [ -d "$INTO" ] || fail "--into $INTO is not a directory."
  INTO="$(cd "$INTO" && pwd -P)"
  [ -f "$INTO/settings.gradle" ] || [ -f "$INTO/settings.gradle.kts" ] \
    || fail "$INTO has no settings.gradle(.kts): --into must be the existing app's Gradle root."
  TARGET="$INTO/$MODULE"
else
  [ -z "$INTO" ] || fail "--into is only for --embed."
  TARGET="$APP/host-android"
fi
[ -e "$TARGET" ] && fail "$TARGET already exists — refusing to overwrite."

# The templates sit beside this script in the repository (scripts/templates) and
# one level up in the tools bundle (templates/).
TEMPLATES=""
for t in "$HERE/templates/production-host" "$HERE/../templates/production-host"; do
  [ -f "$t/build.gradle" ] && { TEMPLATES="$(cd "$t" && pwd -P)"; break; }
done
[ -n "$TEMPLATES" ] || fail "the production-host templates are missing (looked in $HERE/templates and $HERE/../templates)."
EMBED_TEMPLATES="$(dirname "$TEMPLATES")/production-host-embed"
if $EMBED && [ ! -f "$EMBED_TEMPLATES/build.gradle" ]; then
  fail "the embed templates are missing ($EMBED_TEMPLATES)."
fi

# ASCII URL characters only (gradle.properties is ISO-8859-1 with escapes); the
# bundle server takes no query or fragment, since the host appends a path.
SERVER_RE='^https?://[][A-Za-z0-9._~:/@!$&()*+,;=%-]+$'
API_RE='^https?://[][A-Za-z0-9._~:/?#@!$&()*+,;=%-]+$'
[ -n "$BUNDLE_SERVER" ] || fail "--bundle-server is required (e.g. http://10.0.2.2:8077 for an emulator reaching this machine's relay)."
[[ "$BUNDLE_SERVER" =~ $SERVER_RE ]] || fail "--bundle-server must be an http:// or https:// URL of plain ASCII, without a query (got '$BUNDLE_SERVER')."
[ -z "$API_BASE_URL" ] || [[ "$API_BASE_URL" =~ $API_RE ]] || fail "--api-base-url must be an http:// or https:// URL of plain ASCII (got '$API_BASE_URL')."
[[ "$CHANNEL" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] || fail "--channel must be lower-case letters, digits and '-', at most 32 (got '$CHANNEL')."
VERSION_RE='^[0-9A-Za-z.+-]+$'
for v in "$ZIPLINE_VERSION" "$KOTLIN_VERSION" "$COMPOSE_VERSION" "$AGP_VERSION"; do
  [[ "$v" =~ $VERSION_RE ]] || fail "a KELIVER_HOST_*_VERSION override is not a version: '$v'."
done

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
# The Keliver version the GUEST uses, so host and guest do not drift apart.
KELIVER_VERSION="${KELIVER_HOST_KELIVER_VERSION:-$(python3 -c "import json,sys
try:
    v = json.load(open(sys.argv[1]))['appRuntime']['keliverVersion']
    print(v if isinstance(v, str) and v.strip() else '0.3.3')
except Exception: print('0.3.3')" "$APP/keliver.portal.json")}"
[[ "$KELIVER_VERSION" =~ $VERSION_RE ]] || fail "the Keliver version '$KELIVER_VERSION' is not a version."
[ -n "$APPLICATION_ID" ] || APPLICATION_ID="$PACKAGE"
ID_RE='^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$'
[[ "$APPLICATION_ID" =~ $ID_RE ]] || fail "--application-id must look like com.example.app (got '$APPLICATION_ID')."
NAME=""
for f in "$APP/settings.gradle" "$APP/settings.gradle.kts"; do
  [ -f "$f" ] || continue
  NAME="$( { sed -n "s/^[[:space:]]*rootProject\.name[[:space:]]*=[[:space:]]*['\"]\([^'\"]*\)['\"].*/\1/p" "$f" || true; } | head -1)"
  [ -n "$NAME" ] && break
done
[ -n "$NAME" ] || NAME="$(basename "$APP")"
[[ "$NAME" =~ ^[A-Za-z0-9._-]+$ ]] || NAME="app"

# The public key: an explicit file, or this app's store's ed25519.pub.
STORE_PUB=""; RESOLVE_WHY=""; RESOLVE_ERR="$(mktemp "${TMPDIR:-/tmp}/keliver-resolve.XXXXXX")"
RESOLVE="$HERE/keliver-store-path.sh"
STORE=""
if [ -x "$RESOLVE" ]; then
  # Status checked: a refusal or an error leaves STORE empty, never a guess.
  STORE="$("$RESOLVE" "$APP" 2>"$RESOLVE_ERR")" || STORE=""
fi
case "$STORE" in
  /*) [ -f "$STORE/keys/ed25519.pub" ] && STORE_PUB="$STORE/keys/ed25519.pub"
      [ -n "$STORE_PUB" ] || RESOLVE_WHY="the store ($STORE) has no keys/ed25519.pub yet" ;;
  *)  RESOLVE_WHY="the store did not resolve: $(tr '\n' ' ' < "$RESOLVE_ERR" 2>/dev/null | cut -c1-300)" ;;
esac
rm -f "$RESOLVE_ERR"
if [ -z "$KEY_FILE" ]; then
  [ -n "$STORE_PUB" ] || fail "no public key from this app's portal store ($RESOLVE_WHY). Start this app's portal once (keliver-portal) so it creates its signing identity, or pass --public-key-file."
  KEY_FILE="$STORE_PUB"
fi
[ -f "$KEY_FILE" ] || fail "no such file: $KEY_FILE"
REAL_KEY="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$KEY_FILE")"
case "$(basename "$KEY_FILE")|$(basename "$REAL_KEY")" in
  *.priv\|*|*\|*.priv) fail "refusing a private key ($KEY_FILE -> $REAL_KEY); the host needs the PUBLIC key." ;;
esac
KEY_HEX="$(tr -d '[:space:]' < "$KEY_FILE")"
[[ "$KEY_HEX" =~ ^[0-9a-fA-F]{64}$ ]] || fail "$KEY_FILE is not a 64-hex-digit Ed25519 public key."
KEY_ORIGIN="$KEY_FILE"
if [ -n "$STORE_PUB" ]; then
  if [ "$KEY_HEX" != "$(tr -d '[:space:]' < "$STORE_PUB")" ]; then
    fail "$KEY_FILE is not this app's public key ($STORE_PUB differs). A host must trust the key this app's portal signs with — and a file that is not ed25519.pub may be a private key under another name."
  fi
  KEY_ORIGIN="$KEY_FILE (matches this app's store)"
else
  echo "note: $KEY_FILE could not be checked against this app's portal store ($RESOLVE_WHY)." >&2
  echo "      A private key looks exactly like a public one; make sure this is ed25519.pub." >&2
  KEY_ORIGIN="$KEY_FILE (NOT checked against a store)"
fi

# --- stage everything, then move it into place in one step -----------------
for v in "$NAME" "$PACKAGE" "$APPLICATION_ID" "$BUNDLE_SERVER" "$API_BASE_URL"; do
  case "$v" in *@@*) fail "a value contains '@@', which the templates use as placeholders: '$v'." ;; esac
done
STAGE="$(mktemp -d "$(dirname "$TARGET")/.$(basename "$TARGET").XXXXXX")"
chmod 755 "$STAGE"
trap 'rm -rf "$STAGE"' EXIT
PKG_PATH="${PACKAGE//.//}"
subst() { # <src> <dst>
  mkdir -p "$(dirname "$2")"
  python3 - "$1" "$2" "$NAME" "$PACKAGE" "$APPLICATION_ID" "$BUNDLE_SERVER" "$API_BASE_URL" \
    "$KELIVER_VERSION" "$ZIPLINE_VERSION" "$KOTLIN_VERSION" "$COMPOSE_VERSION" "$AGP_VERSION" "$CHANNEL" <<'PY'
import sys
src, dst, name, pkg, appid, server, api, kv, zv, ktv, cv, agp, channel = sys.argv[1:14]
s = open(src, encoding='utf-8').read()
for k, v in {'NAME': name, 'PACKAGE': pkg, 'APPLICATION_ID': appid, 'BUNDLE_SERVER': server,
             'API_BASE_URL': api, 'KELIVER_VERSION': kv, 'ZIPLINE_VERSION': zv,
             'KOTLIN_VERSION': ktv, 'COMPOSE_VERSION': cv, 'AGP_VERSION': agp, 'CHANNEL': channel}.items():
    s = s.replace('@@' + k + '@@', v)
assert '@@' not in s, 'unsubstituted placeholder in ' + src
open(dst, 'w', encoding='utf-8').write(s)
PY
}
if $EMBED; then
  for f in build.gradle keliver.properties consumer-rules.pro src/main/AndroidManifest.xml; do
    subst "$EMBED_TEMPLATES/$f" "$STAGE/$f"
  done
else
  for f in settings.gradle gradle.properties build.gradle src/main/AndroidManifest.xml; do
    subst "$TEMPLATES/$f" "$STAGE/$f"
  done
fi
# The same Kotlin in both modes; only the app shell (its Application and its
# activity) stays out of the library.
for f in "$TEMPLATES"/src/main/kotlin/*.kt; do
  if $EMBED; then case "$(basename "$f")" in HostApp.kt|MainActivity.kt) continue ;; esac; fi
  subst "$f" "$STAGE/src/main/kotlin/$PKG_PATH/$(basename "$f")"
done
mkdir -p "$STAGE/src/main/assets/keliver"
printf '%s\n' "$KEY_HEX" > "$STAGE/src/main/assets/keliver/portal_ed25519.pub"
cat > "$STAGE/.gitignore" <<'EOF'
/build/
/.gradle/
/local.properties
EOF
# mkdir is atomic: if the target appeared since the check above, this fails
# instead of moving the stage INSIDE it.
mkdir "$TARGET" 2>/dev/null || fail "$TARGET appeared while scaffolding — refusing to overwrite."
( cd "$STAGE" && tar cf - . ) | ( cd "$TARGET" && tar xf - ) \
  || { rm -rf "$TARGET"; fail "could not write $TARGET."; }
rm -rf "$STAGE"
trap - EXIT

if $EMBED; then
  # What the existing app must supply; warned, not refused (its build may declare
  # them in ways this cannot see).
  found(){ grep -rqsE "$1" "$INTO"/settings.gradle* "$INTO"/build.gradle* "$INTO"/gradle/libs.versions.toml 2>/dev/null; }
  found 'app\.cash\.zipline' || echo "warning: no app.cash.zipline plugin found in $INTO's settings, build or version catalog: the library needs it (1.22.0 for Kotlin 2.2.0)." >&2
  found 'kotlin\.plugin\.compose|plugin-compose|compose-compiler' || echo "warning: no Kotlin Compose compiler plugin found in $INTO's build: the library needs org.jetbrains.kotlin.plugin.compose." >&2
  found '2\.2\.[0-9]' || echo "warning: no Kotlin 2.2.x found in $INTO's build: Zipline 1.22.0's compiler plugin needs Kotlin 2.2.0." >&2
  echo "created $MODULE/ in $INTO — this app's Keliver host, as a library module"
  echo "  package          $PACKAGE"
  echo "  bundle server    $BUNDLE_SERVER"
  echo "  channel          $CHANNEL$([ "$CHANNEL" = stable ] || echo ' (and stable)')"
  echo "  HostHttp         ${API_BASE_URL:-not provided (no --api-base-url)}"
  echo "  trusts the key   ${KEY_HEX:0:8}… from $KEY_ORIGIN"
  echo "                   commit $MODULE/src/main/assets/keliver/portal_ed25519.pub"
  echo "next, in YOUR app (nothing of it was changed):"
  echo "  settings.gradle:      include ':$MODULE'"
  echo "  app/build.gradle:     implementation project(':$MODULE')"
  echo "  your Application:     val keliver by lazy { $PACKAGE.KeliverHost.create(this) }   (one per process)"
  echo "  a Compose screen:     $PACKAGE.KeliverScreen(keliver, Modifier.fillMaxSize())"
  echo "  or a View layout:     $PACKAGE.KeliverView(context).apply { host = keliver }"
  echo "  settings: $MODULE/keliver.properties (or -Pkeliver.bundleServer=...); an http:// server needs YOUR app's cleartext permission in debug"
  exit 0
fi

echo "created host-android/ — this app's production Android host"
echo "  package          $PACKAGE"
echo "  application id   $APPLICATION_ID"
echo "  bundle server    $BUNDLE_SERVER"
echo "  channel          $CHANNEL$([ "$CHANNEL" = stable ] || echo ' (and stable)')"
echo "  HostHttp         ${API_BASE_URL:-not provided (no --api-base-url)}"
echo "  trusts the key   ${KEY_HEX:0:8}… from $KEY_ORIGIN"
echo "                   commit host-android/src/main/assets/keliver/portal_ed25519.pub"
echo "next:"
echo "  ./gradlew -p host-android assembleDebug     (needs an Android SDK: ANDROID_HOME, or host-android/local.properties)"
echo "  a release build needs https:// servers and your own signing config in host-android/build.gradle"
echo "  publish a bundle (the relay's POST /publish), then install and launch the host."
