#!/usr/bin/env bash
# #77 boundary 2, framework half: plant a PUBLIC function (exported, so link-time
# DCE cannot remove it), keep generatePortalKey UP-TO-DATE, link the framework,
# and look for the planted symbol in the framework's header and binary.
set -uo pipefail
I="$(cd "$(dirname "$0")" && pwd -P)"
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export GRADLE_USER_HOME="$I/gh"
export JAVA_TOOL_OPTIONS="-Duser.home=$I/home"
unset PORTAL_STORE
cd "$I/wt"
OUT=portal-device-ios/build/generated/portalKeys/kotlin
printf 'package dev.keliver.portaldevice.ios\n\npublic fun plantedMarker(): String = "PLANTED_MARKER_77"\n' > "$OUT/Planted.kt"
ls "$OUT"
./gradlew :portal-device-ios:linkDebugFrameworkIosSimulatorArm64 -Pkeliver.portalStore="$I/store" --console=plain > "$I/link-ios.log" 2>&1; echo "rc=$?"
grep -E 'generatePortalKey|linkDebugFramework|BUILD|e: ' "$I/link-ios.log" | tail -6
F="$(find portal-device-ios/build/bin -name 'PortalDeviceHost.framework' -type d | head -1)"; echo "framework: $F"
grep -rn 'plantedMarker' "$F/Headers" 2>/dev/null | head -3
printf 'PLANTED_MARKER_77 in binary: '; strings -a "$F/PortalDeviceHost" 2>/dev/null | grep -c 'PLANTED_MARKER_77'
./gradlew --stop > /dev/null 2>&1
