#!/bin/bash
# Assembles the keliver-portal-tools release bundle: the portal server (relay),
# the MCP agent surface, the wasm editor, and the keliver-portal / keliver-init
# launchers — everything a developer needs to run the portal against their OWN
# app repo, WITHOUT cloning keliver. Consumed by the portal-tools release
# workflow (attaches the zip to the GitHub release) and runnable locally.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"

VERSION="${1:-$(grep 'KELIVER_VERSION' build-support/src/main/kotlin/dev/keliver/buildsupport/RedwoodBuildPlugin.kt | head -1 | sed -E 's/.*"([^"]+)".*/\1/')}"
OUT="$ROOT/build/portal-tools"
STAGE="$OUT/keliver-portal-tools-$VERSION"
echo "==> building keliver-portal-tools $VERSION"

echo "==> gradle: relay + mcp installDist, editor wasm dist, device host APK"
./gradlew -q \
  :portal-relay:installDist \
  :portal-mcp:installDist \
  :web-spike:wasmJsBrowserDistribution \
  :portal-device-android:assembleDebug

rm -rf "$STAGE"
mkdir -p "$STAGE/relay" "$STAGE/mcp" "$STAGE/editor" "$STAGE/bin" "$STAGE/wrapper/gradle/wrapper" "$STAGE/host"

cp -R portal-relay/build/install/portal-relay/. "$STAGE/relay/"
cp -R portal-mcp/build/install/portal-mcp/. "$STAGE/mcp/"
cp -R web-spike/build/dist/wasmJs/productionExecutable/. "$STAGE/editor/"
cp scripts/keliver-portal scripts/keliver-init "$STAGE/bin/"
# Scaffolders so external app repos get the same DX (C1 new-component; ② new-editor).
cp scripts/keliver-new-screen.sh scripts/keliver-new-component.sh scripts/keliver-new-editor.sh \
   scripts/keliver-new-device-target.sh scripts/keliver-install-device-host.sh "$STAGE/bin/"
chmod +x "$STAGE/bin/keliver-portal" "$STAGE/bin/keliver-init" \
  "$STAGE/bin/keliver-new-screen.sh" "$STAGE/bin/keliver-new-component.sh" "$STAGE/bin/keliver-new-editor.sh" \
  "$STAGE/bin/keliver-new-device-target.sh" "$STAGE/bin/keliver-install-device-host.sh"

# The device host APK, so `keliver-new-device-target.sh` has somewhere to run.
# This is a LOCALLY BUILT artifact shipped inside this bundle — it is NOT
# published anywhere, and nothing fetches it from a store or a release page.
cp portal-device-android/build/outputs/apk/debug/portal-device-android-debug.apk \
   "$STAGE/host/keliver-device-host-$VERSION.apk"
( cd "$STAGE/host" && shasum -a 256 "keliver-device-host-$VERSION.apk" > "keliver-device-host-$VERSION.apk.sha256" )
cp docs/DEVICE_HOST.md "$STAGE/host/README.md"
# The gradle wrapper so `keliver-init` scaffolds immediately-buildable projects.
cp gradlew gradlew.bat "$STAGE/wrapper/" 2>/dev/null || true
cp gradle/wrapper/* "$STAGE/wrapper/gradle/wrapper/"
cp docs/PORTAL_TOOLS_README.md "$STAGE/README.md"

( cd "$OUT" && zip -qr "keliver-portal-tools-$VERSION.zip" "keliver-portal-tools-$VERSION" )
echo "==> bundle: $OUT/keliver-portal-tools-$VERSION.zip"
ls -lh "$OUT/keliver-portal-tools-$VERSION.zip" | awk '{print "    "$5}'
