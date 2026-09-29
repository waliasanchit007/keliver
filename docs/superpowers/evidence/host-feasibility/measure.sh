#!/usr/bin/env bash
#
# Can a standalone Android app compile Keliver's production host against the
# PUBLISHED dependencies only? (docs/PRODUCTION_HOST_FEASIBILITY.md, "the
# measurement that should come before choosing".)
#
#   measure.sh <keliver-checkout> <empty-work-dir>
#
# Generates a throwaway Android project in <work-dir>: repositories are Maven
# Central, Google and the Gradle plugin portal ONLY — no mavenLocal, no project
# dependencies, no composite build. The three host files are copied byte for
# byte from the tools release's source commit (b5615637) and never edited.
#
# Two variants:
#   A  the three files alone.
#   B  the same, plus ONE added file declaring `HostApi` and `PortalPresenter`
#      by shape: they live in portal-device-guest, which is not published.
#
# Reads no portal store, embeds no key (the host reads its key from an asset at
# RUNTIME, so compiling needs none), signs nothing. The Gradle home and the
# JVM's user.home are inside <work-dir>; the Android SDK is read from
# ANDROID_HOME (or ~/Library/Android/sdk).
set -uo pipefail
KELIVER="$(cd "${1:?usage: $0 <keliver-checkout> <empty-work-dir>}" && pwd -P)"
WORK="${2:?usage: $0 <keliver-checkout> <empty-work-dir>}"
mkdir -p "$WORK" && WORK="$(cd "$WORK" && pwd -P)"
[ -z "$(ls -A "$WORK")" ] || { echo "$WORK is not empty" >&2; exit 2; }
SRC=b5615637
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -d "$SDK/platforms" ] || { echo "no Android SDK at $SDK" >&2; exit 2; }
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
export JAVA_HOME
export GRADLE_USER_HOME="$WORK/gradle-home"
export JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home"
mkdir -p "$GRADLE_USER_HOME" "$WORK/home"
# Behind a TLS-intercepting proxy a fresh Gradle home needs the proxy's trust
# store; copy those two lines (never printed) if the user's Gradle home has them.
grep -E '^systemProp\.javax\.net\.ssl\.trustStore' "$HOME/.gradle/gradle.properties" \
  > "$GRADLE_USER_HOME/gradle.properties" 2>/dev/null || true

H=dev/keliver/portaldevice/host
FILES="MainActivity.kt HostTrustPolicy.kt AndroidSqlHost.kt"

make_project() { # <dir> <variant>
  local d="$1" v="$2"
  mkdir -p "$d/app/src/main/kotlin/$H" "$d/gradle/wrapper"
  cp "$KELIVER/gradlew" "$d/"; cp "$KELIVER/gradle/wrapper/gradle-wrapper.jar" "$KELIVER/gradle/wrapper/gradle-wrapper.properties" "$d/gradle/wrapper/"
  printf 'sdk.dir=%s\n' "$SDK" > "$d/local.properties"
  cat > "$d/gradle.properties" <<'P'
android.useAndroidX=true
org.gradle.jvmargs=-Xmx4g
P
  cat > "$d/settings.gradle" <<'G'
pluginManagement {
  repositories { gradlePluginPortal(); google(); mavenCentral() }
}
dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories { google(); mavenCentral() }
}
rootProject.name = 'standalone-host'
include ':app'
G
  cat > "$d/build.gradle" <<'G'
plugins {
  id 'com.android.application' version '8.12.0' apply false
  id 'org.jetbrains.kotlin.android' version '2.2.0' apply false
  id 'org.jetbrains.kotlin.plugin.compose' version '2.2.0' apply false
  id 'org.jetbrains.compose' version '1.8.2' apply false
  id 'app.cash.zipline' version '1.22.0' apply false
}
G
  cat > "$d/app/build.gradle" <<'G'
plugins {
  id 'com.android.application'
  id 'org.jetbrains.kotlin.android'
  id 'org.jetbrains.kotlin.plugin.compose'
  id 'org.jetbrains.compose'
  id 'app.cash.zipline'
}
android {
  namespace 'dev.keliver.portaldevice.host'
  compileSdk 35
  defaultConfig {
    applicationId 'dev.example.standalonehost'
    minSdk 21
    versionCode 1
    versionName '1.0'
    // What HostTrustPolicy reads. A production host is not the dev-only one.
    buildConfigField 'boolean', 'DEV_ONLY', 'false'
  }
  buildFeatures { compose true; buildConfig true }
  compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
  implementation 'dev.keliver:keliver-treehouse-host:0.3.3'
  implementation 'dev.keliver:keliver-treehouse-host-composeui:0.3.3'
  implementation 'dev.keliver:keliver-material-composeui:0.3.3'
  implementation 'dev.keliver:keliver-material-protocol-host-web:0.3.3'
  implementation 'dev.keliver:portal-sql:0.3.3'
  implementation 'dev.keliver:keliver-http:0.3.3'
  implementation 'app.cash.zipline:zipline:1.22.0'
  implementation 'app.cash.zipline:zipline-loader:1.22.0'
  implementation 'com.squareup.okhttp3:okhttp:5.1.0'
  implementation 'androidx.activity:activity-compose:1.10.1'
  implementation 'androidx.core:core-ktx:1.16.0'
  implementation 'io.coil-kt.coil3:coil-compose-core:3.3.0'
  implementation 'io.coil-kt.coil3:coil-network-okhttp:3.3.0'
  implementation 'org.jetbrains.compose.runtime:runtime:1.8.2'
  implementation 'org.jetbrains.compose.foundation:foundation:1.8.2'
  implementation 'org.jetbrains.compose.material:material:1.8.2'
  implementation 'org.jetbrains.compose.ui:ui:1.8.2'
  implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2'
}
G
  git -C "$KELIVER" show "$SRC:portal-device-android/src/main/AndroidManifest.xml" > "$d/app/src/main/AndroidManifest.xml"
  for f in $FILES; do git -C "$KELIVER" show "$SRC:portal-device-android/src/main/kotlin/$H/$f" > "$d/app/src/main/kotlin/$H/$f"; done
  if [ "$v" = B ]; then
    mkdir -p "$d/app/src/main/kotlin/dev/keliver/portaldevice"
    cat > "$d/app/src/main/kotlin/dev/keliver/portaldevice/GuestContract.kt" <<'K'
package dev.keliver.portaldevice

// Declared BY SHAPE, as the scaffolded guest does: portal-device-guest, where
// these live, is not published. Same package, names and signatures as
// portal-device-guest/src/commonMain at b5615637.
import app.cash.zipline.ZiplineService
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.ZiplineTreehouseUi

interface HostApi : ZiplineService {
  suspend fun httpCall(url: String): String
}

interface PortalPresenter : AppService, ZiplineService {
  fun launch(): ZiplineTreehouseUi
}
K
  fi
}

verify_unchanged() { # <dir>: the three host files are byte-identical to b5615637
  local d="$1" f ok=0
  for f in $FILES; do
    cmp -s <(git -C "$KELIVER" show "$SRC:portal-device-android/src/main/kotlin/$H/$f") "$d/app/src/main/kotlin/$H/$f" || { echo "  CHANGED: $f"; ok=1; }
  done
  [ "$ok" = 0 ] && echo "  the three host files are byte-identical to $SRC"
}

echo "keliver checkout: $KELIVER ($(git -C "$KELIVER" rev-parse --short HEAD)); host files from $SRC"
echo "android sdk: $SDK (platforms: $(ls "$SDK/platforms" | tr '\n' ' '))"
for v in A B; do
  d="$WORK/variant-$v"
  echo "=== variant $v"
  make_project "$d" "$v"
  verify_unchanged "$d"
  ( cd "$d" && ./gradlew --console=plain :app:dependencies --configuration debugRuntimeClasspath > "$WORK/$v-dependencies.txt" 2>&1 ); echo "  dependency report: rc=$?"
  if grep -q "FAILED\|Could not resolve" "$WORK/$v-dependencies.txt"; then
    echo "  UNRESOLVED:"; grep -E "FAILED|Could not resolve" "$WORK/$v-dependencies.txt" | sort -u | head -20 | sed 's/^/    /'
  else
    echo "  every dependency resolved from the public repositories"
  fi
  ( cd "$d" && ./gradlew --console=plain :app:assembleDebug > "$WORK/$v-assemble.log" 2>&1 ); rc=$?
  echo "  assembleDebug: rc=$rc  $(grep -E 'BUILD (SUCCESSFUL|FAILED)' "$WORK/$v-assemble.log" | tail -1)"
  grep -E '^e: ' "$WORK/$v-assemble.log" | sed -e "s#file://$d/##" -e 's/^/    /' | head -30
  grep -A4 'What went wrong' "$WORK/$v-assemble.log" | head -8 | sed 's/^/    /'
  apk="$(find "$d/app/build/outputs/apk" -name '*.apk' 2>/dev/null | head -1)"
  if [ -n "$apk" ]; then
    echo "  APK: $(basename "$apk"), $(wc -c < "$apk" | tr -d ' ') bytes"
    unzip -l "$apk" | grep -q 'assets/portal_ed25519.pub' && echo "  it embeds a key (unexpected)" || echo "  it embeds no key (none was asked for)"
  fi
  ( cd "$d" && ./gradlew --stop > /dev/null 2>&1 )
done
