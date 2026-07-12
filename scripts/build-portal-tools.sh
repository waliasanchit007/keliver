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

echo "==> gradle: relay + mcp installDist, editor wasm dist"
./gradlew -q \
  :portal-relay:installDist \
  :portal-mcp:installDist \
  :web-spike:wasmJsBrowserDistribution

rm -rf "$STAGE"
mkdir -p "$STAGE/relay" "$STAGE/mcp" "$STAGE/editor" "$STAGE/bin" "$STAGE/wrapper/gradle/wrapper"

cp -R portal-relay/build/install/portal-relay/. "$STAGE/relay/"
cp -R portal-mcp/build/install/portal-mcp/. "$STAGE/mcp/"
cp -R web-spike/build/dist/wasmJs/productionExecutable/. "$STAGE/editor/"
cp scripts/keliver-portal scripts/keliver-init "$STAGE/bin/"
chmod +x "$STAGE/bin/keliver-portal" "$STAGE/bin/keliver-init"
# The gradle wrapper so `keliver-init` scaffolds immediately-buildable projects.
cp gradlew gradlew.bat "$STAGE/wrapper/" 2>/dev/null || true
cp gradle/wrapper/* "$STAGE/wrapper/gradle/wrapper/"
cp docs/PORTAL_TOOLS_README.md "$STAGE/README.md"

( cd "$OUT" && zip -qr "keliver-portal-tools-$VERSION.zip" "keliver-portal-tools-$VERSION" )
echo "==> bundle: $OUT/keliver-portal-tools-$VERSION.zip"
ls -lh "$OUT/keliver-portal-tools-$VERSION.zip" | awk '{print "    "$5}'
