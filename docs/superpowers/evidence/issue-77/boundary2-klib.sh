#!/usr/bin/env bash
# #77 boundary 2 — does the planted source reach the compiled iOS output?
set -uo pipefail
I="$(cd "$(dirname "$0")" && pwd -P)"
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export GRADLE_USER_HOME="$I/gh"
export JAVA_TOOL_OPTIONS="-Duser.home=$I/home"
unset PORTAL_STORE
cd "$I/wt"
ls portal-device-ios/build/generated/portalKeys/kotlin/
echo "== compileKotlinIosSimulatorArm64"
./gradlew :portal-device-ios:compileKotlinIosSimulatorArm64 -Pkeliver.portalStore="$I/store" --console=plain > "$I/compile-ios.log" 2>&1; echo "rc=$?"
grep -E 'generatePortalKey|compileKotlinIosSimulatorArm64|BUILD|e: ' "$I/compile-ios.log" | tail -8
echo "== klibs produced by the module"
find portal-device-ios/build -name '*.klib' -newer "$I/run.sh" 2>/dev/null | head
for k in $(find portal-device-ios/build -name '*.klib' 2>/dev/null); do
  printf '%s: PLANTED occurrences = ' "$k"; unzip -p "$k" 2>/dev/null | grep -a -c 'PLANTED' || true
done
find portal-device-ios/build/classes -maxdepth 6 -type d 2>/dev/null | head -10
grep -r -a -l 'PLANTED' portal-device-ios/build/classes 2>/dev/null | head -5
./gradlew --stop > /dev/null 2>&1
