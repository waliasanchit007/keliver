#!/usr/bin/env bash
# #77 boundary 1 — task-level only. Isolated Gradle home + user.home; a disposable
# store holding a dummy public key. No Kotlin/Native compile is requested.
set -uo pipefail
I="$(cd "$(dirname "$0")" && pwd -P)"
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export GRADLE_USER_HOME="$I/gh"
export JAVA_TOOL_OPTIONS="-Duser.home=$I/home"
unset PORTAL_STORE
S="$I/store"
cd "$I/wt"
OUT=portal-device-ios/build/generated/portalKeys/kotlin
echo "== run 1"; ./gradlew :portal-device-ios:generatePortalKey -Pkeliver.portalStore="$S" --console=plain > "$I/run1.log" 2>&1; echo "rc=$?"
grep -E 'generatePortalKey|BUILD' "$I/run1.log"
ls -la "$OUT"
printf 'package dev.keliver.portaldevice.ios\ninternal const val PLANTED = true\n' > "$OUT/Planted.kt"
echo "== run 2 (after planting)"; ./gradlew :portal-device-ios:generatePortalKey -Pkeliver.portalStore="$S" --console=plain > "$I/run2.log" 2>&1; echo "rc=$?"
grep -E 'generatePortalKey|BUILD' "$I/run2.log"
ls -la "$OUT"
echo "== the iOS source set includes that directory:"
grep -n 'kotlin.srcDir(portalKeyDir)' portal-device-ios/build.gradle
./gradlew --stop > /dev/null 2>&1
