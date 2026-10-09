#!/usr/bin/env bash
#
# keliver new-ios-host — give a keliver-init app its own PRODUCTION iOS host:
# <app>/host-ios, a standalone Gradle build of a Kotlin framework plus an Xcode
# app around it, that renders your keliver-material screens from signed
# bundles, verified against your portal's public key, using only published
# dependencies (Maven Central). The iOS twin of keliver-new-production-host.sh.
#
#   keliver-new-ios-host.sh --bundle-server URL
#                           [--api-base-url URL]
#                           [--bundle-id ID]
#                           [--public-key-file PATH]
#                           [--channel NAME]
#   keliver-new-ios-host.sh --embed --into DIR [--module NAME]
#                           --bundle-server URL [--api-base-url URL]
#                           [--public-key-file PATH] [--channel NAME]
#
# Run from the APP repo root (the directory holding keliver.portal.json). Needs
# macOS with Xcode to build; scaffolding itself needs only bash and python3.
#
#   --bundle-server    where published bundles are served: the portal relay
#                      (http://localhost:8077 from a simulator on this Mac) or
#                      anything serving its /bundles API. http:// gets an App
#                      Transport Security exception for that host only, and a
#                      release framework refuses http://.
#   --api-base-url     optional: the base URL guests' HostHttp requests go to.
#                      Without it the host provides no HostHttp.
#   --bundle-id        default: <your app package>.host
#   --embed            W2: write only the host framework's Gradle build into an
#                      EXISTING iOS app's directory, as DIR/NAME (default
#                      keliver-host-ios): the same Kotlin as the standalone host,
#                      a Gradle wrapper, KeliverScreen.swift and EMBED.md (the
#                      Xcode build phase and settings to add). No Xcode project
#                      is written or edited.
#   --channel          default: stable. The release channel this host takes from
#                      bundles/index.json besides stable (W4.4), e.g. beta for
#                      testers: lower-case letters, digits and '-'.
#   --public-key-file  default: keys/ed25519.pub in this app's portal store,
#                      found by keliver-store-path.sh. The key is written into
#                      host-ios/src/iosMain/kotlin/.../HostConfig.kt (it is
#                      public and belongs in git), so building needs no store.
#
#                      A private key has the same format as a public one, so a
#                      file cannot prove which it is. This refuses a file whose
#                      real path (links resolved) ends in .priv, and — whenever
#                      this app's store resolves — a key that is not that
#                      store's ed25519.pub. Nothing here opens ed25519.priv.
#
# The host is production-ONLY: it verifies every bundle's signature, has no
# development path and no unverified fallback. Build for a simulator with
#   xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp \
#     -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
# or open host-ios/iosApp.xcodeproj in Xcode. A device build needs your team
# and signing (host-ios/Configuration/Config.xcconfig).
#
# Version overrides (normally unset): KELIVER_HOST_KELIVER_VERSION (default: the
# app's appRuntime.keliverVersion, else 0.3.3), KELIVER_HOST_ZIPLINE_VERSION,
# KELIVER_HOST_KOTLIN_VERSION, KELIVER_HOST_COMPOSE_VERSION.
#
# ALL-OR-NOTHING: everything is validated and staged before anything is written.
# A rejected input leaves the app byte-identical.
set -euo pipefail

ZIPLINE_VERSION="${KELIVER_HOST_ZIPLINE_VERSION:-1.22.0}"
KOTLIN_VERSION="${KELIVER_HOST_KOTLIN_VERSION:-2.2.0}"
COMPOSE_VERSION="${KELIVER_HOST_COMPOSE_VERSION:-1.8.2}"
# Resolve a symlinked invocation to the real script, so the templates are found.
SELF="${BASH_SOURCE[0]}"
while [ -L "$SELF" ]; do
  link="$(readlink "$SELF")"; case "$link" in /*) SELF="$link" ;; *) SELF="$(dirname "$SELF")/$link" ;; esac
done
HERE="$(cd "$(dirname "$SELF")" && pwd -P)"

BUNDLE_SERVER=""; API_BASE_URL=""; APPLICATION_ID=""; KEY_FILE=""; CHANNEL="stable"
EMBED=false; INTO=""; MODULE="keliver-host-ios"
need() { [ "$2" -ge 2 ] || { echo "$1 needs a value" >&2; exit 2; }; }
while [ $# -gt 0 ]; do
  case "$1" in
    --bundle-server)   need "$1" $#; BUNDLE_SERVER="$2"; shift 2 ;;
    --api-base-url)    need "$1" $#; API_BASE_URL="$2"; shift 2 ;;
    --bundle-id)       need "$1" $#; APPLICATION_ID="$2"; shift 2 ;;
    --public-key-file) need "$1" $#; KEY_FILE="$2"; shift 2 ;;
    --channel)         need "$1" $#; CHANNEL="$2"; shift 2 ;;
    --embed)           EMBED=true; shift ;;
    --into)            need "$1" $#; INTO="$2"; shift 2 ;;
    --module)          need "$1" $#; MODULE="$2"; shift 2 ;;
    -h|--help)         sed -n '2,61p' "$SELF"; exit 0 ;;
    *)                 echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "keliver-new-ios-host: $*" >&2; echo "Nothing was written." >&2; exit 1; }

APP="$(pwd -P)"
[ -f "$APP/keliver.portal.json" ] || fail "no keliver.portal.json here — run from the app root."
if $EMBED; then
  [ -n "$INTO" ] || fail "--embed needs --into <the existing iOS app's directory>."
  [ -z "$APPLICATION_ID" ] || fail "--bundle-id is for the standalone host; an embedded host is a framework in YOUR app."
  [[ "$MODULE" =~ ^[A-Za-z][A-Za-z0-9_-]{0,63}$ ]] || fail "--module must be a plain directory name (got '$MODULE')."
  [ -d "$INTO" ] || fail "--into $INTO is not a directory."
  INTO="$(cd "$INTO" && pwd -P)"
  # The framework's module is KeliverHost; an app module of that name makes Swift
  # ignore `import KeliverHost` (measured).
  if grep -rqsiE 'PRODUCT_(NAME|MODULE_NAME) = "?KeliverHost"?;' --include=project.pbxproj "$INTO" 2>/dev/null; then
    fail "an Xcode project under $INTO names a product or module KeliverHost, the framework's module name; rename it first."
  fi
  TARGET="$INTO/$MODULE"
else
  [ -z "$INTO" ] || fail "--into is only for --embed."
  TARGET="$APP/host-ios"
fi
[ -e "$TARGET" ] && fail "$TARGET already exists — refusing to overwrite."
[ -x "$APP/gradlew" ] || fail "no executable ./gradlew in the app root: the Xcode build phase runs ./gradlew -p host-ios."

# The templates sit beside this script in the repository (scripts/templates) and
# one level up in the tools bundle (templates/).
TEMPLATES=""
for t in "$HERE/templates/ios-host" "$HERE/../templates/ios-host"; do
  [ -f "$t/build.gradle" ] && { TEMPLATES="$(cd "$t" && pwd -P)"; break; }
done
[ -n "$TEMPLATES" ] || fail "the iOS host templates are missing (looked in $HERE/templates and $HERE/../templates)."

# ONE URL grammar, shared with build.gradle and the host's Origins.kt: plain
# ASCII, a host name or [IPv6] (no user@), an optional port and path, and no
# query, fragment or '$'. The values become Kotlin string literals, where '$'
# would be a template, and both hosts append their own paths and queries.
URL_RE='^https?://(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9.-]+)(:[0-9]{1,5})?(/[A-Za-z0-9._~/%-]*)?$'
URL_RULE="an http:// or https:// URL: plain ASCII, a host name or [IPv6] (no user@), an optional port and path, without a query, fragment or '\$'"
[ -n "$BUNDLE_SERVER" ] || fail "--bundle-server is required (e.g. http://localhost:8077 for a simulator reaching this Mac's relay)."
[[ "$BUNDLE_SERVER" =~ $URL_RE ]] || fail "--bundle-server must be $URL_RULE (got '$BUNDLE_SERVER')."
[ -z "$API_BASE_URL" ] || [[ "$API_BASE_URL" =~ $URL_RE ]] || fail "--api-base-url must be $URL_RULE (got '$API_BASE_URL')."
[[ "$CHANNEL" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] || fail "--channel must be lower-case letters, digits and '-', at most 32 (got '$CHANNEL')."
VERSION_RE='^[0-9A-Za-z.+-]+$'
for v in "$ZIPLINE_VERSION" "$KOTLIN_VERSION" "$COMPOSE_VERSION"; do
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
ID_RE='^[A-Za-z][A-Za-z0-9-]*(\.[A-Za-z0-9-]+)+$'
[[ "$APPLICATION_ID" =~ $ID_RE ]] || fail "--bundle-id must look like com.example.app (got '$APPLICATION_ID')."
NAME=""
for f in "$APP/settings.gradle" "$APP/settings.gradle.kts"; do
  [ -f "$f" ] || continue
  NAME="$( { sed -n "s/^[[:space:]]*rootProject\.name[[:space:]]*=[[:space:]]*['\"]\([^'\"]*\)['\"].*/\1/p" "$f" || true; } | head -1)"
  [ -n "$NAME" ] && break
done
[ -n "$NAME" ] || NAME="$(basename "$APP")"
[[ "$NAME" =~ ^[A-Za-z0-9._-]+$ ]] || NAME="app"
# The app's PRODUCT_NAME is also its Swift module name, so it must not be the
# framework's module name, KeliverHost (measured: the import is then ignored).
PRODUCT_NAME="$(python3 -c "import re,sys; n=re.sub(r'[^A-Za-z0-9]', '', sys.argv[1]) or 'App'; n=n[0].upper()+n[1:]; n=n if n[0].isalpha() else 'App'+n; p=n+'Host'; print(p+'App' if p.lower()=='keliverhost' else p)" "$NAME")"

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
for v in "$NAME" "$PACKAGE" "$APPLICATION_ID" "$BUNDLE_SERVER" "$API_BASE_URL" "$PRODUCT_NAME"; do
  case "$v" in *@@*) fail "a value contains '@@', which the templates use as placeholders: '$v'." ;; esac
done
STAGE="$(mktemp -d "$(dirname "$TARGET")/.$(basename "$TARGET").XXXXXX")"
chmod 755 "$STAGE"
trap 'rm -rf "$STAGE"' EXIT
PKG_PATH="${PACKAGE//.//}"
# The App Transport Security block: an exception for each http:// host given,
# nothing for https://. For development only: a release build refuses any of it
# (build.gradle: checkReleaseUrls).
ATS_BLOCK="$(python3 - "$BUNDLE_SERVER" "$API_BASE_URL" <<'PY'
import re, sys
hosts = []
for url in sys.argv[1:]:
    m = re.match(r'^http://(\[[^\]]+\]|[^/:?#]+)', url)
    if m and m.group(1).lower() not in hosts: hosts.append(m.group(1).lower())
if hosts:
    print('\t<key>NSAppTransportSecurity</key>\n\t<dict>\n\t\t<key>NSExceptionDomains</key>\n\t\t<dict>')
    for h in hosts:
        print('\t\t\t<key>%s</key>\n\t\t\t<dict>\n\t\t\t\t<key>NSExceptionAllowsInsecureHTTPLoads</key>\n\t\t\t\t<true/>\n\t\t\t</dict>' % h.strip('[]'))
    print('\t\t</dict>\n\t</dict>')
PY
)"
subst() { # <src> <dst>
  mkdir -p "$(dirname "$2")"
  python3 - "$1" "$2" "$NAME" "$PACKAGE" "$APPLICATION_ID" "$BUNDLE_SERVER" "$API_BASE_URL" \
    "$KELIVER_VERSION" "$ZIPLINE_VERSION" "$KOTLIN_VERSION" "$COMPOSE_VERSION" "$KEY_HEX" "$PRODUCT_NAME" \
    "$PKG_PATH" "$ATS_BLOCK" "$CHANNEL" <<'PY'
import sys
src, dst, name, pkg, bid, server, api, kv, zv, ktv, cv, key, product, pkgpath, ats, channel = sys.argv[1:17]
s = open(src, encoding='utf-8').read()
for k, v in {'NAME': name, 'PACKAGE': pkg, 'BUNDLE_ID': bid, 'BUNDLE_SERVER': server,
             'API_BASE_URL': api, 'KELIVER_VERSION': kv, 'ZIPLINE_VERSION': zv,
             'KOTLIN_VERSION': ktv, 'COMPOSE_VERSION': cv, 'KEY_HEX': key.lower(),
             'PRODUCT_NAME': product, 'PKG_PATH': pkgpath, 'CHANNEL': channel,
             'ATS_BLOCK': (ats + '\n') if ats else ''}.items():
    s = s.replace('@@' + k + '@@', v)
assert '@@' not in s, 'unsubstituted placeholder in ' + src
open(dst, 'w', encoding='utf-8').write(s)
PY
}
if $EMBED; then
  for f in settings.gradle gradle.properties build.gradle src/nativeInterop/cinterop/sqlite3.def; do
    subst "$TEMPLATES/$f" "$STAGE/$f"
  done
  EMBED_TEMPLATES="$(dirname "$TEMPLATES")/ios-host-embed"
  [ -f "$EMBED_TEMPLATES/KeliverScreen.swift" ] || fail "the iOS embed templates are missing ($EMBED_TEMPLATES)."
  cp "$EMBED_TEMPLATES/KeliverScreen.swift" "$STAGE/KeliverScreen.swift"
  REL="$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$TARGET" "$INTO")"
  python3 - "$EMBED_TEMPLATES/EMBED.md" "$STAGE/EMBED.md" "$PKG_PATH" "$REL" <<'PY'
import sys
src, dst, pkgpath, rel = sys.argv[1:5]
s = open(src, encoding='utf-8').read().replace('@@PKG_PATH@@', pkgpath).replace('@@REL@@', rel)
open(dst, 'w', encoding='utf-8').write(s)
PY
  # Its own wrapper: an iOS app's repository has none. The guest app's (the
  # Gradle that builds its bundles) is the one this host is known to build with.
  cp "$APP/gradlew" "$STAGE/gradlew" && cp -R "$APP/gradle" "$STAGE/gradle" && chmod 755 "$STAGE/gradlew" \
    || fail "could not copy this app's Gradle wrapper (gradlew, gradle/)."
  rm -f "$STAGE/gradle/libs.versions.toml"
else
  for f in settings.gradle gradle.properties build.gradle iosApp/iOSApp.swift iosApp/ContentView.swift \
           iosApp/Info.plist iosApp.xcodeproj/project.pbxproj Configuration/Config.xcconfig \
           src/nativeInterop/cinterop/sqlite3.def; do
    subst "$TEMPLATES/$f" "$STAGE/$f"
  done
fi
for f in "$TEMPLATES"/src/iosMain/kotlin/*.kt; do
  subst "$f" "$STAGE/src/iosMain/kotlin/$PKG_PATH/$(basename "$f")"
done
cat > "$STAGE/.gitignore" <<'GI'
/build/
/.gradle/
/.kotlin/
xcuserdata/
/Configuration/Config.local.xcconfig
GI
# mkdir is atomic: if the target appeared since the check above, this fails
# instead of moving the stage INSIDE it.
mkdir "$TARGET" 2>/dev/null || fail "$TARGET appeared while scaffolding — refusing to overwrite."
( cd "$STAGE" && tar cf - . ) | ( cd "$TARGET" && tar xf - ) \
  || { rm -rf "$TARGET"; fail "could not write $TARGET."; }
rm -rf "$STAGE"
trap - EXIT

if $EMBED; then
  echo "created $MODULE/ in $INTO — this app's Keliver host framework (KeliverHost), for your Xcode app"
  echo "  package          $PACKAGE"
  echo "  bundle server    $BUNDLE_SERVER"
  echo "  channel          $CHANNEL$([ "$CHANNEL" = stable ] || echo ' (and stable)')"
  echo "  HostHttp         ${API_BASE_URL:-not provided (no --api-base-url)}"
  echo "  trusts the key   ${KEY_HEX:0:8}… from $KEY_ORIGIN"
  echo "                   commit $MODULE/src/iosMain/kotlin/$PKG_PATH/HostConfig.kt"
  echo "next, in YOUR Xcode project (nothing of it was changed): $MODULE/EMBED.md"
  echo "  a Run Script phase before Compile Sources: cd \"\$SRCROOT/$REL\" && ./gradlew --console=plain embedAndSignAppleFrameworkForXcode"
  echo "  ENABLE_USER_SCRIPT_SANDBOXING = NO; OTHER_LDFLAGS += -lsqlite3; Info.plist CADisableMinimumFrameDurationOnPhone = YES"
  echo "  add $MODULE/KeliverScreen.swift to your target; then KeliverScreen() in any SwiftUI view"
  [ -n "$ATS_BLOCK" ] && echo "  an http:// server needs an App Transport Security exception in YOUR Info.plist (development only)"
  exit 0
fi

echo "created host-ios/ — this app's production iOS host"
echo "  package          $PACKAGE"
echo "  app              $PRODUCT_NAME ($APPLICATION_ID)"
echo "  bundle server    $BUNDLE_SERVER"
echo "  channel          $CHANNEL$([ "$CHANNEL" = stable ] || echo ' (and stable)')"
echo "  HostHttp         ${API_BASE_URL:-not provided (no --api-base-url)}"
echo "  trusts the key   ${KEY_HEX:0:8}… from $KEY_ORIGIN"
echo "                   commit host-ios/src/iosMain/kotlin/$PKG_PATH/HostConfig.kt"
echo "next:"
echo "  xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \\"
echo "    -configuration Debug CODE_SIGNING_ALLOWED=NO build      (or open it in Xcode)"
echo "  a release build needs https:// servers, and a device build your team and signing"
echo "  (host-ios/Configuration/Config.xcconfig)."
