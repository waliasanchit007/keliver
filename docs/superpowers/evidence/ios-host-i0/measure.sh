#!/usr/bin/env bash
#
# W1 I0 — does an iOS PRODUCTION host compile and link from published artifacts
# only? Copies proj/ (settings.gradle, build.gradle and the host Kotlin) into
# <work>, adds this repository's Gradle wrapper, and links a debug
# iosSimulatorArm64 framework with a disposable Gradle home, user.home and
# KONAN_DATA_DIR. No portal store is involved: the key is a dummy constant.
#
#   docs/superpowers/evidence/ios-host-i0/measure.sh <empty work dir>
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd "$HERE/../../../.." && pwd -P)"
W="${1:?usage: $0 <empty work dir>}"; mkdir -p "$W"; W="$(cd "$W" && pwd -P)"
[ -z "$(ls -A "$W")" ] || { echo "refusing: $W is not empty" >&2; exit 2; }
cp -R "$HERE/proj" "$W/proj"
cp "$ROOT/gradlew" "$W/proj/"; mkdir -p "$W/proj/gradle/wrapper"
cp "$ROOT/gradle/wrapper/gradle-wrapper.jar" "$ROOT/gradle/wrapper/gradle-wrapper.properties" "$W/proj/gradle/wrapper/"
mkdir -p "$W/gradle-home" "$W/home" "$W/konan"
grep -E '^systemProp\.javax\.net\.ssl\.trustStore' "$HOME/.gradle/gradle.properties" > "$W/gradle-home/gradle.properties" 2>/dev/null || true
export GRADLE_USER_HOME="$W/gradle-home" KONAN_DATA_DIR="$W/konan" JAVA_TOOL_OPTIONS="-Duser.home=$W/home"
[ -n "${JAVA_HOME:-}" ] || export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
cd "$W/proj"
./gradlew --console=plain linkDebugFrameworkIosSimulatorArm64
./gradlew --console=plain -q dependencies --configuration iosSimulatorArm64CompileKlibraries \
  | grep -oE 'dev\.keliver:[a-z0-9-]+:[0-9.]+' | sort -u
grep -n 'MainViewController' build/bin/iosSimulatorArm64/debugFramework/KeliverHost.framework/Headers/KeliverHost.h
