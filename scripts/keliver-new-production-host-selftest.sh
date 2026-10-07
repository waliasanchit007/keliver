#!/usr/bin/env bash
#
# keliver-new-production-host-selftest — the production-host scaffolder refuses
# bad input without writing anything, and writes a complete module otherwise.
#
#   scripts/keliver-new-production-host-selftest.sh <disposable-parent> [--build]
#
# --build also compiles the scaffolded host (assembleDebug) against Maven
# Central, in a disposable Gradle home and user.home. That needs an Android SDK
# (ANDROID_HOME, or ~/Library/Android/sdk) and network access.
#
# Uses fixture apps and a fixture STORE (PORTAL_STORE, inside the run
# directory) holding a dummy key pair; the JVM's user.home is inside the run
# directory too, behind keliver_require_isolated_store, so the scaffolder's
# store lookup never reaches a real store. The dummy private key is never
# printed and exists only to prove it is refused.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
# KELIVER_PRODUCTION_HOST_SCAFFOLD runs a PACKAGED copy instead (bin/ of an
# unpacked tools bundle), which finds its templates at bin/../templates.
SCAFFOLD="${KELIVER_PRODUCTION_HOST_SCAFFOLD:-$ROOT/scripts/keliver-new-production-host.sh}"
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "${1:?usage: $0 <disposable-parent> [--build]}" prod-host)" || exit $?
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
SERVER=http://10.0.2.2:8077
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
refuses "a non-URL bundle server"          "must be an http"          --bundle-server "10.0.2.2:8077" --public-key-file "$KEY"
refuses "a bundle server with a quote"     "must be an http"          --bundle-server "http://x\"y" --public-key-file "$KEY"
refuses "a non-URL API base"               "api-base-url must be"     --bundle-server "$SERVER" --api-base-url "ftp://x" --public-key-file "$KEY"
refuses "a malformed application id"       "application-id must"      --bundle-server "$SERVER" --application-id "demo" --public-key-file "$KEY"
refuses "a key file that is not a key"     "not a 64-hex-digit"       --bundle-server "$SERVER" --public-key-file "$BADKEY"
refuses "a PRIVATE key file"               "refusing a private key"   --bundle-server "$SERVER" --public-key-file "$PRIV"
refuses "a .pub symlink to the private key" "refusing a private key"  --bundle-server "$SERVER" --public-key-file "$LINKED"
refuses "the private key renamed .key"     "is not this app's public key" --bundle-server "$SERVER" --public-key-file "$RENAMED"
refuses "another app's public key"         "is not this app's public key" --bundle-server "$SERVER" --public-key-file "$OTHER"
refuses "a bundle server with a query"     "without a query"          --bundle-server "$SERVER/?x=1" --public-key-file "$KEY"
refuses "a non-ASCII bundle server"        "plain ASCII"              --bundle-server "http://bündel.example" --public-key-file "$KEY"
refuses "a value holding a placeholder"    "which the templates use as placeholders" --bundle-server "http://h:8077/@@NAME@@" --public-key-file "$KEY"
KELIVER_HOST_KOTLIN_VERSION="1'x" refuses "a hostile version override" "not a version" --bundle-server "$SERVER" --public-key-file "$KEY"
refuses "a missing key file"               "no such file"             --bundle-server "$SERVER" --public-key-file "$DISP/nope.pub"
# An unprefixed KOTLIN_VERSION (common on CI images) must not change the build.
export KOTLIN_VERSION=1.9.0
refuses "an unknown option"                "unknown option"           --bundle-server "$SERVER" --frobnicate
mv "$APP/settings.gradle" "$APP/s.gradle"
out="$( cd "$APP" && "$SCAFFOLD" --bundle-server "$SERVER" --public-key-file "$KEY" 2>&1 )"; rc=$?
[ "$rc" = 0 ] && [ -d "$APP/host-android" ] && ok "no settings.gradle: still scaffolds (named after the directory), no silent exit" \
  || bad "no settings.gradle: rc=$rc $(printf '%s' "$out" | tail -1)"
rm -rf "$APP/host-android"; mv "$APP/s.gradle" "$APP/settings.gradle"
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
H="$APP/host-android"
missing=0
for f in settings.gradle build.gradle gradle.properties .gitignore src/main/AndroidManifest.xml \
         src/main/assets/portal_ed25519.pub \
         src/main/kotlin/com/example/demo/host/MainActivity.kt \
         src/main/kotlin/com/example/demo/host/ProductionTrust.kt \
         src/main/kotlin/com/example/demo/host/AndroidSqlHost.kt \
         src/main/kotlin/com/example/demo/host/OkHttpHostHttp.kt \
         src/main/kotlin/com/example/demo/host/GuestContract.kt \
         src/main/kotlin/com/example/demo/host/BundleIndex.kt; do
  [ -f "$H/$f" ] || { bad "missing $f"; missing=1; }
done
[ "$missing" = 0 ] && ok "every expected file is there"
m="$(stat -c %a "$H" 2>/dev/null || stat -f %Lp "$H")"; [ "$m" = 755 ] && ok "host-android/ is 755" || bad "host-android/ is $m"
grep -q "version '2.2.0'" "$H/build.gradle" && ! grep -q "1.9.0" "$H/build.gradle" \
  && ok "an unprefixed KOTLIN_VERSION in the environment does not change the build" || bad "KOTLIN_VERSION leaked into the build"
unset KOTLIN_VERSION
grep -rl '@@' "$H" >/dev/null 2>&1 && bad "an unsubstituted @@placeholder@@ remains" || ok "no placeholder left"
cmp -s <(tr -d '[:space:]' < "$KEY") <(tr -d '[:space:]' < "$H/src/main/assets/portal_ed25519.pub") \
  && ok "the embedded key is the given public key" || bad "the embedded key differs"
grep -q "applicationId 'com.example.demo.host'" "$H/build.gradle" && grep -q "namespace 'com.example.demo.host'" "$H/build.gradle" \
  && ok "package and application id default to <app package>.host" || bad "package/application id"
grep -q "^keliver.bundleServer=$SERVER$" "$H/gradle.properties" && grep -q "^keliver.apiBaseUrl=https://api.example.com/v1$" "$H/gradle.properties" \
  && ok "the servers are recorded in gradle.properties" || bad "gradle.properties servers"
grep -v '^[[:space:]]*//' "$H/settings.gradle" "$H/build.gradle" | grep -q "mavenLocal\|includeBuild\|project(\|maven {" && bad "the host build reaches beyond Maven Central/Google" \
  || ok "the host build uses only Maven Central, Google and the plugin portal"
grep -q "NO_SIGNATURE_CHECKS\|DevelopmentUnsigned\|10\.0\.2\.2\|http-replay" "$H"/src/main/kotlin/com/example/demo/host/*.kt \
  && bad "the host sources carry a development path, an emulator address or the replay fixture" \
  || ok "no development path, emulator address or replay fixture in the sources"
# W3: the lookup reads bundles/index.json, holds the manifest to its sha256,
# and falls back to the relay's bundles/latest only on a 404.
M="$H/src/main/kotlin/com/example/demo/host/MainActivity.kt"
grep -q 'addPathSegments("bundles/index.json")' "$M" && grep -q 'response.code == 404' "$M" \
  && grep -q 'ManifestPinningHttpClient(zipline, latest.manifestUrl' "$M" \
  && ok "the lookup reads bundles/index.json, pins the manifest's sha256, and falls back only on a 404" \
  || bad "the host does not look up through bundles/index.json"
refuses "a second run over an existing host-android" "already exists" --bundle-server "$SERVER" --public-key-file "$KEY"
ls -a "$APP" | grep -q '^\.host-android\.' && bad "a staging directory was left behind" || ok "no staging directory left behind"

if [ "$BUILD" = 1 ]; then
  echo "=== the scaffolded host compiles against Maven Central"
  SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  if [ ! -d "$SDK/platforms" ]; then
    bad "no Android SDK at $SDK"
  else
    [ -n "${JAVA_HOME:-}" ] || { [ -x /usr/libexec/java_home ] && export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; }
    export GRADLE_USER_HOME="$DISP/gradle-home" JAVA_TOOL_OPTIONS="-Duser.home=$DISP/home"
    mkdir -p "$GRADLE_USER_HOME" "$DISP/home"
    grep -E '^systemProp\.javax\.net\.ssl\.trustStore' "$HOME/.gradle/gradle.properties" > "$GRADLE_USER_HOME/gradle.properties" 2>/dev/null || true
    printf 'sdk.dir=%s\n' "$SDK" > "$H/local.properties"
    ( cd "$APP" && ./gradlew --console=plain -p host-android assembleDebug > "$DISP/assemble.log" 2>&1 ); rc=$?
    if [ "$rc" = 0 ]; then
      apk="$(find "$H/build/outputs/apk" -name '*.apk' | head -1)"
      ok "assembleDebug succeeded: $(basename "$apk"), $(wc -c < "$apk" | tr -d ' ') bytes"
      unzip -p "$apk" assets/portal_ed25519.pub | tr -d '[:space:]' | cmp -s - <(tr -d '[:space:]' < "$KEY") \
        && ok "the APK embeds exactly the scaffolded public key" || bad "the APK's embedded key differs"
      unzip -l "$apk" | grep -q ' AndroidManifest.xml$' && ok "the APK has a manifest" || bad "the APK has no manifest"
    else
      bad "assembleDebug failed (rc=$rc): $(grep -E '^e: |What went wrong' -A2 "$DISP/assemble.log" | head -6 | tr '\n' ' ')"
    fi
    # A release build with an http:// server is refused.
    ( cd "$APP" && ./gradlew --console=plain -p host-android assembleRelease > "$DISP/assemble-release.log" 2>&1 ); rc=$?
    [ "$rc" != 0 ] && grep -q "A release build needs https:// servers" "$DISP/assemble-release.log" \
      && ok "a release build with an http:// server fails" || bad "release with http server: rc=$rc"
    # A key that is not a key fails the BUILD.
    printf 'garbage\n' > "$H/src/main/assets/portal_ed25519.pub"
    ( cd "$APP" && ./gradlew --console=plain -p host-android assembleDebug > "$DISP/assemble-badkey.log" 2>&1 ); rc=$?
    [ "$rc" != 0 ] && grep -q "not a 64-hex-digit Ed25519 public key" "$DISP/assemble-badkey.log" \
      && ok "a malformed embedded key fails the build" || bad "a malformed key did not fail the build (rc=$rc)"
    ( cd "$APP" && ./gradlew -p host-android --stop > /dev/null 2>&1 )
  fi
fi

echo "=== without a store: accepted, and said so"
unset PORTAL_STORE
APP2="$DISP/demo2"; mkdir -p "$APP2/src/jsMain/kotlin/screens"
cp "$APP/keliver.portal.json" "$APP/settings.gradle" "$APP2/"; cp "$APP/src/jsMain/kotlin/screens/home.kt" "$APP2/src/jsMain/kotlin/screens/"
keliver_require_isolated_store "$DISP" "$APP2" > /dev/null || { echo "isolation guard refused" >&2; exit 2; }
out="$( cd "$APP2" && "$SCAFFOLD" --bundle-server "$SERVER" --public-key-file "$OTHER" 2>&1 )"; rc=$?
[ "$rc" = 0 ] && printf '%s' "$out" | grep -q "NOT checked against a store" \
  && ok "with no store, a key file is accepted and marked unchecked" || bad "no-store scaffold: rc=$rc $(printf '%s' "$out" | tail -2 | tr '\n' ' ')"
grep -rqF -f "$PRIV" "$DISP"/demo*/host-android 2>/dev/null && bad "the private key is in a scaffolded module" \
  || ok "the private key is in no scaffolded module"

echo
echo "production-host scaffold: $pass passed, $fail failed   ($DISP)"
[ "$fail" -eq 0 ]
