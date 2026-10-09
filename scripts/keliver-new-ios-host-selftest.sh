#!/usr/bin/env bash
#
# keliver-new-ios-host-selftest — the iOS production-host scaffolder refuses bad
# input without writing anything, and writes a complete host-ios/ otherwise.
#
#   scripts/keliver-new-ios-host-selftest.sh <disposable-parent> [--build]
#
# --build also builds the scaffolded app for the iOS simulator with xcodebuild
# (macOS with Xcode only), Keliver from Maven Central, in a disposable Gradle
# home, user.home and KONAN_DATA_DIR; and shows the build-time checks: a
# malformed key fails the build, and a release refuses http://. To reuse warm
# caches, set KELIVER_SELFTEST_GRADLE_HOME and KELIVER_SELFTEST_KONAN_DATA_DIR
# (disposable directories you created; never ~/.gradle or ~/.konan).
#
# Uses fixture apps and a fixture STORE (PORTAL_STORE, inside the run
# directory) holding a dummy key pair; the JVM's user.home is inside the run
# directory too, behind keliver_require_isolated_store, so the scaffolder's
# store lookup never reaches a real store. The dummy private key is never
# printed and exists only to prove it is refused.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
# KELIVER_IOS_HOST_SCAFFOLD runs a PACKAGED copy instead (bin/ of an
# unpacked tools bundle), which finds its templates at bin/../templates.
SCAFFOLD="${KELIVER_IOS_HOST_SCAFFOLD:-$ROOT/scripts/keliver-new-ios-host.sh}"
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "${1:?usage: $0 <disposable-parent> [--build]}" ios-host)" || exit $?
BUILD=0; [ "${2:-}" = "--build" ] && BUILD=1
pass=0; fail=0
echo "scaffolder: $SCAFFOLD"
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
snapshot() { ( cd "$1" && find . -type f ! -path './.gradle/*' -print0 | sort -z | xargs -0 shasum -a 256 ) 2>/dev/null; }

APP="$DISP/demo"
mkdir -p "$APP/src/jsMain/kotlin/screens" "$APP/src/jsMain/kotlin/logic"
printf '{ "screensDir": "src/jsMain/kotlin/screens" }\n' > "$APP/keliver.portal.json"
printf "rootProject.name = 'demo'\n" > "$APP/settings.gradle"
printf 'package com.example.demo.screens\n\nfun HomeScreen() {}\n' > "$APP/src/jsMain/kotlin/screens/home.kt"
cp "$ROOT/gradlew" "$APP/"; mkdir -p "$APP/gradle/wrapper"
cp "$ROOT/gradle/wrapper/gradle-wrapper.jar" "$ROOT/gradle/wrapper/gradle-wrapper.properties" "$APP/gradle/wrapper/"
STORE="$DISP/store"; mkdir -p "$STORE/keys" "$DISP/home"
KEY="$STORE/keys/ed25519.pub"; printf '%s\n' "$(printf 'ab%.0s' $(seq 1 32))" > "$KEY"
PRIV="$STORE/keys/ed25519.priv"; ( umask 077; printf '%s' "$(printf 'cd%.0s' $(seq 1 32))" > "$PRIV" )
BADKEY="$DISP/bad.pub"; printf 'not-a-key\n' > "$BADKEY"
RENAMED="$DISP/looks-public.key"; cp "$PRIV" "$RENAMED"
LINKED="$DISP/looks.pub"; ln -s "$PRIV" "$LINKED"
OTHER="$DISP/other.pub"; printf '%s\n' "$(printf 'ef%.0s' $(seq 1 32))" > "$OTHER"
SERVER=http://localhost:8077
export JAVA_TOOL_OPTIONS="-Duser.home=$DISP/home" PORTAL_STORE="$STORE"
[ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ] && export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
keliver_require_isolated_store "$DISP" "$APP" > /dev/null || { echo "isolation guard refused" >&2; exit 2; }

refuses() { # <label> <expected message fragment> <args...>
  local label="$1" want="$2"; shift 2
  local before after out rc
  before="$(snapshot "$APP")"
  out="$( cd "$APP" && "$SCAFFOLD" "$@" 2>&1 )"; rc=$?
  after="$(snapshot "$APP")"
  if [ "$rc" != 0 ] && printf '%s' "$out" | grep -q -- "$want" && [ "$before" = "$after" ]; then
    ok "refuses $label, and the app is byte-identical"
  else
    bad "$label: rc=$rc, changed=$([ "$before" = "$after" ] && echo no || echo YES), output: $(printf '%s' "$out" | head -2 | tr '\n' ' ')"
  fi
}

echo "=== refusals"
refuses "without --bundle-server"          "bundle-server is required" --public-key-file "$KEY"
refuses "a non-URL bundle server"          "must be an http"          --bundle-server "localhost:8077" --public-key-file "$KEY"
refuses "a bundle server with a quote"     "must be an http"          --bundle-server "http://x\"y" --public-key-file "$KEY"
refuses "a malformed channel (W4.4)"       "channel must be"          --bundle-server "$SERVER" --channel "Beta" --public-key-file "$KEY"
refuses "a non-URL API base"               "api-base-url must be"     --bundle-server "$SERVER" --api-base-url "ftp://x" --public-key-file "$KEY"
refuses "a malformed bundle id"            "bundle-id must"           --bundle-server "$SERVER" --bundle-id "demo" --public-key-file "$KEY"
refuses "a key file that is not a key"     "not a 64-hex-digit"       --bundle-server "$SERVER" --public-key-file "$BADKEY"
refuses "a PRIVATE key file"               "refusing a private key"   --bundle-server "$SERVER" --public-key-file "$PRIV"
refuses "a .pub symlink to the private key" "refusing a private key"  --bundle-server "$SERVER" --public-key-file "$LINKED"
refuses "the private key renamed .key"     "is not this app's public key" --bundle-server "$SERVER" --public-key-file "$RENAMED"
refuses "another app's public key"         "is not this app's public key" --bundle-server "$SERVER" --public-key-file "$OTHER"
refuses "a bundle server with a query"     "without a query"          --bundle-server "$SERVER/?x=1" --public-key-file "$KEY"
refuses "a non-ASCII bundle server"        "plain ASCII"              --bundle-server "http://bündel.example" --public-key-file "$KEY"
refuses "a placeholder in a URL (the grammar refuses '@')" "must be an http" --bundle-server "http://h:8077/@@NAME@@" --public-key-file "$KEY"
refuses "a '\$' in a URL (a Kotlin template)" "must be an http" --bundle-server "$SERVER" --api-base-url 'https://api.example.com/$v1' --public-key-file "$KEY"
refuses "a URL with user@"                 "no user@"                 --bundle-server "http://a@localhost:8077" --public-key-file "$KEY"
refuses "an API base with a query"         "without a query"          --bundle-server "$SERVER" --api-base-url "https://api.example.com/v1?k=1" --public-key-file "$KEY"
refuses "a host with '&'"                  "must be an http"          --bundle-server "http://a&b:8077" --public-key-file "$KEY"
KELIVER_HOST_KOTLIN_VERSION="1'x" refuses "a hostile version override" "not a version" --bundle-server "$SERVER" --public-key-file "$KEY"
refuses "a missing key file"               "no such file"             --bundle-server "$SERVER" --public-key-file "$DISP/nope.pub"
export KOTLIN_VERSION=1.9.0
refuses "an unknown option"                "unknown option"           --bundle-server "$SERVER" --frobnicate
mv "$APP/gradlew" "$APP/gw"
refuses "an app without ./gradlew"         "no executable ./gradlew"  --bundle-server "$SERVER" --public-key-file "$KEY"
mv "$APP/gw" "$APP/gradlew"
mv "$APP/keliver.portal.json" "$APP/k.json"
refuses "outside an app root"              "no keliver.portal.json"   --bundle-server "$SERVER" --public-key-file "$KEY"
mv "$APP/k.json" "$APP/keliver.portal.json"
printf 'package elsewhere\n\nfun OtherScreen() {}\n' > "$APP/src/jsMain/kotlin/screens/other.kt"
refuses "screens in two packages"          "must all declare one"     --bundle-server "$SERVER" --public-key-file "$KEY"
rm "$APP/src/jsMain/kotlin/screens/other.kt"

echo "=== a successful scaffold (the key from the store, by default)"
out="$( cd "$APP" && "$SCAFFOLD" --bundle-server "$SERVER" --api-base-url "https://api.example.com/v1" 2>&1 )"; rc=$?
[ "$rc" = 0 ] && ok "scaffolded (exit 0)" || { bad "scaffold failed: $out"; }
printf '%s' "$out" | grep -q "matches this app's store" && ok "the key was checked against this app's store" || bad "no store match in: $out"
H="$APP/host-ios"
K="$H/src/iosMain/kotlin/com/example/demo/host"
missing=0
for f in settings.gradle build.gradle gradle.properties .gitignore src/nativeInterop/cinterop/sqlite3.def \
         iosApp/iOSApp.swift iosApp/ContentView.swift iosApp/Info.plist iosApp.xcodeproj/project.pbxproj \
         Configuration/Config.xcconfig; do
  [ -f "$H/$f" ] || { bad "missing $f"; missing=1; }
done
for f in HostConfig.kt MainViewController.kt ProductionTrust.kt GuestContract.kt Origins.kt IosHttp.kt IosSqlHost.kt BundleIndex.kt; do
  [ -f "$K/$f" ] || { bad "missing $K/$f"; missing=1; }
done
[ "$missing" = 0 ] && ok "every expected file is there"
m="$(stat -c %a "$H" 2>/dev/null || stat -f %Lp "$H")"; [ "$m" = 755 ] && ok "host-ios/ is 755" || bad "host-ios/ is $m"
grep -q "id 'org.jetbrains.kotlin.multiplatform' version '2.2.0'" "$H/build.gradle" && ! grep -q "version '1.9.0'" "$H/build.gradle" \
  && ok "an unprefixed KOTLIN_VERSION in the environment does not change the build" || bad "KOTLIN_VERSION leaked into the build"
unset KOTLIN_VERSION
grep -rl '@@' "$H" >/dev/null 2>&1 && bad "an unsubstituted @@placeholder@@ remains" || ok "no placeholder left"
grep -q "PORTAL_PUBLIC_KEY_HEX: String = \"$(tr -d '[:space:]' < "$KEY")\"" "$K/HostConfig.kt" \
  && ok "HostConfig.kt embeds exactly the store's public key" || bad "the embedded key differs"
grep -q "BUNDLE_SERVER: String = \"$SERVER\"" "$K/HostConfig.kt" && grep -q 'API_BASE_URL: String = "https://api.example.com/v1"' "$K/HostConfig.kt" \
  && ok "the servers are recorded in HostConfig.kt" || bad "HostConfig.kt servers"
grep -q '^package com.example.demo.host$' "$K/MainViewController.kt" && grep -q '^PRODUCT_BUNDLE_IDENTIFIER=com.example.demo.host$' "$H/Configuration/Config.xcconfig" \
  && ok "package and bundle id default to <app package>.host" || bad "package/bundle id"
pn="$(sed -n 's/^PRODUCT_NAME=//p' "$H/Configuration/Config.xcconfig")"
[ -n "$pn" ] && [ "$(printf '%s' "$pn" | tr '[:upper:]' '[:lower:]')" != keliverhost ] && grep -q "baseName = 'KeliverHost'" "$H/build.gradle" \
  && ok "the app's PRODUCT_NAME ($pn) differs from the framework's module, KeliverHost" || bad "PRODUCT_NAME '$pn'"
python3 - "$H/iosApp/Info.plist" <<'PY' && ok "Info.plist is a valid plist; its only ATS exception is http:// to localhost (development; a release refuses it)" || bad "Info.plist ATS"
import plistlib, sys
p = plistlib.load(open(sys.argv[1], 'rb'))
ats = p['NSAppTransportSecurity']
assert sorted(ats) == ['NSExceptionDomains'], ats
assert list(ats['NSExceptionDomains']) == ['localhost'], ats
assert ats['NSExceptionDomains']['localhost'] == {'NSExceptionAllowsInsecureHTTPLoads': True}, ats
PY
# W3: the lookup reads bundles/index.json, holds the manifest to its sha256,
# and falls back to the relay's bundles/latest only on a 404.
grep -q '/bundles/index.json"' "$K/MainViewController.kt" && grep -q 'status == 404' "$K/MainViewController.kt" \
  && grep -q 'ManifestPinningHttpClient(NSURLSessionZiplineHttpClient(), manifestUrl, manifestSha256, floor)' "$K/MainViewController.kt" \
  && ok "the lookup reads bundles/index.json, pins the manifest's sha256, and falls back only on a 404" \
  || bad "the host does not look up through bundles/index.json"
# W2: one host per process (Keliver), started once; every view controller only observes it.
grep -q '^public object Keliver {' "$K/MainViewController.kt" && grep -q 'if (started) return' "$K/MainViewController.kt" \
  && grep -q 'public fun MainViewController(): UIViewController = Keliver.viewController()' "$K/MainViewController.kt" \
  && grep -q 'state.collectAsState()' "$K/MainViewController.kt" \
  && ok "W2: Keliver is the one host (started once); MainViewController() only shows it" || bad "W2: the iOS host is not one per process"
# W4.2: the rollback floor is read per key, guards the network load and the cache
# start, and rises only from a verified successful load.
grep -q 'integerForKey(floorKey(trust))' "$K/MainViewController.kt" && grep -q 'AcceptCachedBundle(floor)' "$K/MainViewController.kt" \
  && grep -q 'manifestSequence(manifest.metadata)' "$K/MainViewController.kt" && grep -q 'sequence?.let(onSequence)' "$K/MainViewController.kt" \
  && ok "W4.2: the rollback floor guards the network load and the cache start, and rises after a load" \
  || bad "W4.2: the rollback floor is not wired"
grep -q 'DEVELOPMENT_TEAM' "$H/iosApp.xcodeproj/project.pbxproj" && bad "the Xcode project names a development team" \
  || ok "the Xcode project names no development team"
grep -q 'gradlew -p host-ios --console=plain embedAndSignAppleFrameworkForXcode' "$H/iosApp.xcodeproj/project.pbxproj" \
  && ok "the Xcode build phase builds the framework with the app's ./gradlew -p host-ios" || bad "the build phase"
grep -v '^[[:space:]]*//' "$H/settings.gradle" "$H/build.gradle" | grep -q "mavenLocal\|includeBuild\|project(\|maven {" && bad "the host build reaches beyond Maven Central/Google" \
  || ok "the host build uses only Maven Central, Google and the plugin portal"
grep -q "NO_SIGNATURE_CHECKS\|DevelopmentUnsigned\|10\.0\.2\.2\|http-replay\|HostApi" "$K"/*.kt \
  && bad "the host sources carry a development path, an emulator address or the replay fixture" \
  || ok "no development path, emulator address or replay fixture in the sources"
# W4.4: the host takes one channel besides stable, stable by default.
grep -q 'CHANNEL: String = "stable"' "$K/HostConfig.kt" && grep -q "configValue('CHANNEL')" "$H/build.gradle" \
  && grep -q 'pickFromIndex(body.utf8(), capabilities, channel = CHANNEL' "$K/MainViewController.kt" \
  && ok "W4.4: the channel defaults to stable and selects the index entries" || bad "W4.4: the channel is not wired"
# W4.5: constraints are checked against this install's id, CFBundleVersion and the floor.
grep -q 'HostFacts(installId(), hostVersion(), floor())' "$K/MainViewController.kt" && grep -q 'facts = facts' "$K/MainViewController.kt" \
  && grep -q 'setOf("rollout", "minHostVersion", "maxHostVersion")' "$K/BundleIndex.kt" \
  && ok "W4.5: the lookup checks rollout and host-version constraints (install id, CFBundleVersion, floor)" || bad "W4.5: constraints are not wired"
refuses "a second run over an existing host-ios" "already exists" --bundle-server "$SERVER" --public-key-file "$KEY"
ls -a "$APP" | grep -q '^\.host-ios\.' && bad "a staging directory was left behind" || ok "no staging directory left behind"
# W4.4: --channel beta, over a removed host-ios (the build below then builds a beta host).
rm -rf "$H"
out="$( cd "$APP" && "$SCAFFOLD" --bundle-server "$SERVER" --api-base-url "https://api.example.com/v1" --channel beta 2>&1 )"; rc=$?
[ "$rc" = 0 ] && grep -q 'CHANNEL: String = "beta"' "$K/HostConfig.kt" && printf '%s' "$out" | grep -q 'channel          beta (and stable)' \
  && ok "W4.4: --channel beta is recorded in HostConfig.kt" || bad "W4.4: --channel beta: rc=$rc"

echo "=== https only: no App Transport Security exception"
APP3="$DISP/demo3"; mkdir -p "$APP3/src/jsMain/kotlin/screens"
cp "$APP/keliver.portal.json" "$APP/settings.gradle" "$APP/gradlew" "$APP3/"; cp "$APP/src/jsMain/kotlin/screens/home.kt" "$APP3/src/jsMain/kotlin/screens/"
keliver_require_isolated_store "$DISP" "$APP3" > /dev/null || { echo "isolation guard refused" >&2; exit 2; }
( cd "$APP3" && "$SCAFFOLD" --bundle-server "https://bundles.example.com" --public-key-file "$KEY" > /dev/null 2>&1 ) \
  && python3 -c "import plistlib,sys; p=plistlib.load(open(sys.argv[1],'rb')); assert 'NSAppTransportSecurity' not in p" "$APP3/host-ios/iosApp/Info.plist" \
  && ok "an https:// bundle server gets no ATS exception" || bad "https scaffold or its Info.plist"

echo "=== --embed: the host framework for an existing iOS app (W2)"
EX="$DISP/existing-ios"; mkdir -p "$EX/MyApp.xcodeproj"
printf 'PRODUCT_NAME = "MyApp";\n' > "$EX/MyApp.xcodeproj/project.pbxproj"
embed_refuses() { # <label> <expected> <args...>: refused, and neither app changed
  local label="$1" want="$2"; shift 2
  local b1 b2 out rc
  b1="$(snapshot "$APP")$(snapshot "$EX")"
  out="$( cd "$APP" && "$SCAFFOLD" "$@" 2>&1 )"; rc=$?
  b2="$(snapshot "$APP")$(snapshot "$EX")"
  if [ "$rc" != 0 ] && printf '%s' "$out" | grep -q -- "$want" && [ "$b1" = "$b2" ]; then
    ok "--embed refuses $label; both apps byte-identical"
  else
    bad "--embed $label: rc=$rc, changed=$([ "$b1" = "$b2" ] && echo no || echo YES): $(printf '%s' "$out" | head -2 | tr '\n' ' ')"
  fi
}
embed_refuses "without --into" "needs --into" --embed --bundle-server "$SERVER" --public-key-file "$KEY"
embed_refuses "--bundle-id" "is for the standalone host" --embed --into "$EX" --bundle-id com.x.y --bundle-server "$SERVER" --public-key-file "$KEY"
embed_refuses "a bad module name" "plain directory name" --embed --into "$EX" --module "../x" --bundle-server "$SERVER" --public-key-file "$KEY"
embed_refuses "--into without --embed" "only for --embed" --into "$EX" --bundle-server "$SERVER" --public-key-file "$KEY"
mkdir -p "$DISP/clash/Clash.xcodeproj"; printf 'PRODUCT_NAME = KeliverHost;\n' > "$DISP/clash/Clash.xcodeproj/project.pbxproj"
out="$( cd "$APP" && "$SCAFFOLD" --embed --into "$DISP/clash" --bundle-server "$SERVER" --public-key-file "$KEY" 2>&1 )"; rc=$?
[ "$rc" != 0 ] && printf '%s' "$out" | grep -q "names a product or module KeliverHost" && [ ! -e "$DISP/clash/keliver-host-ios" ] \
  && ok "--embed refuses a project whose product is named KeliverHost (the framework's module)" || bad "--embed KeliverHost clash: rc=$rc"
out="$( cd "$APP" && "$SCAFFOLD" --embed --into "$EX" --bundle-server "https://bundles.example.com" --channel beta 2>&1 )"; rc=$?
L="$EX/keliver-host-ios"; LK="$L/src/iosMain/kotlin/com/example/demo/host"
[ "$rc" = 0 ] && [ -d "$L" ] && ok "--embed wrote $EX/keliver-host-ios (exit 0)" || bad "--embed failed: rc=$rc $(printf '%s' "$out" | tail -3 | tr '\n' ' ')"
[ -x "$L/gradlew" ] && [ -f "$L/gradle/wrapper/gradle-wrapper.properties" ] && [ -f "$L/settings.gradle" ] && [ -f "$L/build.gradle" ] \
  && [ -f "$L/KeliverScreen.swift" ] && [ -f "$L/EMBED.md" ] && [ ! -e "$L/iosApp" ] && [ ! -e "$L/iosApp.xcodeproj" ] \
  && ok "a framework build with its own wrapper, KeliverScreen.swift and EMBED.md; no Xcode app" || bad "the embedded framework's files"
grep -q 'Keliver.shared.viewController(safeArea: safeArea)' "$L/KeliverScreen.swift" && grep -q 'cd "$SRCROOT/keliver-host-ios"' "$L/EMBED.md" \
  && ! grep -q '@@' "$L/EMBED.md" && ok "KeliverScreen shows Keliver.shared; EMBED.md names the build phase with this layout's path" || bad "KeliverScreen.swift / EMBED.md"
grep -q 'CHANNEL: String = "beta"' "$LK/HostConfig.kt" && grep -q 'BUNDLE_SERVER: String = "https://bundles.example.com"' "$LK/HostConfig.kt" \
  && ok "the settings are in HostConfig.kt, as for the standalone host" || bad "HostConfig.kt"
[ "$(cat "$EX/MyApp.xcodeproj/project.pbxproj")" = 'PRODUCT_NAME = "MyApp";' ] && ok "--embed edited no file of the existing app" || bad "--embed changed the existing project"
same=1; for f in MainViewController.kt ProductionTrust.kt GuestContract.kt Origins.kt IosHttp.kt IosSqlHost.kt BundleIndex.kt; do
  cmp -s "$LK/$f" "$K/$f" || { same=0; bad "$f differs between the embedded framework and the standalone host"; }
done
[ "$same" = 1 ] && ok "the host Kotlin is byte-identical in the embedded framework and the standalone host"
embed_refuses "a second run over the module" "already exists" --embed --into "$EX" --bundle-server "$SERVER" --public-key-file "$KEY"

if [ "$BUILD" = 1 ]; then
  echo "=== the scaffolded app builds for the iOS simulator from Maven Central"
  if ! command -v xcodebuild >/dev/null 2>&1; then
    bad "no xcodebuild (macOS with Xcode is required for --build)"
  else
    [ -n "${JAVA_HOME:-}" ] || { [ -x /usr/libexec/java_home ] && export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; }
    export GRADLE_USER_HOME="${KELIVER_SELFTEST_GRADLE_HOME:-$DISP/gradle-home}" KONAN_DATA_DIR="${KELIVER_SELFTEST_KONAN_DATA_DIR:-$DISP/konan}"
    export JAVA_TOOL_OPTIONS="-Duser.home=$DISP/home"
    mkdir -p "$GRADLE_USER_HOME" "$KONAN_DATA_DIR" "$DISP/home"
    [ -f "$GRADLE_USER_HOME/gradle.properties" ] || \
      grep -E '^systemProp\.javax\.net\.ssl\.trustStore' "$HOME/.gradle/gradle.properties" > "$GRADLE_USER_HOME/gradle.properties" 2>/dev/null || true
    ( cd "$APP" && xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -configuration Debug \
        -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath "$DISP/dd" \
        CODE_SIGNING_ALLOWED=NO build > "$DISP/xcodebuild.log" 2>&1 ); rc=$?
    appb="$(find "$DISP/dd/Build/Products/Debug-iphonesimulator" -maxdepth 1 -name '*.app' 2>/dev/null | head -1)"
    if [ "$rc" = 0 ] && [ -n "$appb" ]; then
      ok "xcodebuild built $(basename "$appb") for the simulator"
      [ "$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$appb/Info.plist" 2>/dev/null)" = com.example.demo.host ] \
        && ok "the app's bundle id is com.example.demo.host" || bad "the built app's bundle id"
    else
      bad "xcodebuild failed (rc=$rc): $(grep -E '^e: |error:' "$DISP/xcodebuild.log" | head -3 | tr '\n' ' ')"
    fi
    # A release framework refuses http:// — the check exists, fails here, and is wired to every release link.
    ( cd "$APP" && ./gradlew --console=plain -p host-ios checkReleaseUrls > "$DISP/release-urls.log" 2>&1 ); rc=$?
    [ "$rc" != 0 ] && grep -q "A release build needs https:// servers" "$DISP/release-urls.log" \
      && ok "checkReleaseUrls refuses http://" || bad "checkReleaseUrls: rc=$rc"
    # https:// servers, but an ATS exception left in Info.plist: still refused.
    cp "$K/HostConfig.kt" "$DISP/HostConfig.kt.keep"
    sed -i.bak -e 's#BUNDLE_SERVER: String = "http://#BUNDLE_SERVER: String = "https://#' "$K/HostConfig.kt" && rm -f "$K/HostConfig.kt.bak"
    ( cd "$APP" && ./gradlew --console=plain -p host-ios checkReleaseUrls > "$DISP/release-ats.log" 2>&1 ); rc=$?
    [ "$rc" != 0 ] && grep -q "must not ship App Transport Security exceptions" "$DISP/release-ats.log" \
      && ok "checkReleaseUrls refuses an Info.plist that keeps a cleartext exception" || bad "checkReleaseUrls (ATS): rc=$rc"
    cp "$DISP/HostConfig.kt.keep" "$K/HostConfig.kt"
    wired=0
    for link in linkReleaseFrameworkIosArm64 linkReleaseFrameworkIosSimulatorArm64; do
      ( cd "$APP" && ./gradlew --console=plain -p host-ios --dry-run "$link" > "$DISP/wiring-$link.log" 2>&1 )
      grep -q ':checkReleaseUrls SKIPPED' "$DISP/wiring-$link.log" && wired=$((wired+1))
    done
    [ "$wired" = 2 ] && ok "both release framework links (device, simulator) depend on checkReleaseUrls" \
      || bad "checkReleaseUrls is wired to $wired of 2 release links"
    # A key that is not a key fails the BUILD.
    sed -i.bak 's/PORTAL_PUBLIC_KEY_HEX: String = "[0-9a-f]*"/PORTAL_PUBLIC_KEY_HEX: String = "garbage"/' "$K/HostConfig.kt" && rm -f "$K/HostConfig.kt.bak"
    ( cd "$APP" && ./gradlew --console=plain -p host-ios compileKotlinIosSimulatorArm64 > "$DISP/badkey.log" 2>&1 ); rc=$?
    [ "$rc" != 0 ] && grep -q "not a 64-hex-digit Ed25519" "$DISP/badkey.log" \
      && ok "a malformed key in HostConfig.kt fails the build" || bad "a malformed key did not fail the build (rc=$rc)"
    ( cd "$APP" && ./gradlew -p host-ios --stop > /dev/null 2>&1 )
  fi
fi

grep -rqF -f "$PRIV" "$DISP"/demo*/host-ios 2>/dev/null && bad "the private key is in a scaffolded host" \
  || ok "the private key is in no scaffolded host"

echo
echo "ios-host scaffold: $pass passed, $fail failed   ($DISP)"
[ "$fail" -eq 0 ]
