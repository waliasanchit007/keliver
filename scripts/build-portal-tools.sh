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

# The tools bundle has its OWN version line. It ships launchers, the relay, the
# MCP binary and an editor build; the Maven libraries an adopter compiles
# against are versioned separately and are NOT rebuilt or republished by this
# script. Bumping one must never force a bump of the other — naming a ZIP is not
# a reason to move a published library coordinate.
TOOLS_VERSION="${1:-$(tr -d ' \n' < "$ROOT/build-support/portal-tools.version")}"
# The Maven version this bundle's scaffolders wire new projects to.
MAVEN_VERSION="$(grep 'KELIVER_VERSION' build-support/src/main/kotlin/dev/keliver/buildsupport/RedwoodBuildPlugin.kt | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
SOURCE_COMMIT="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
SOURCE_DIRTY="$(git -C "$ROOT" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
VERSION="$TOOLS_VERSION"
OUT="$ROOT/build/portal-tools"
STAGE="$OUT/keliver-portal-tools-$TOOLS_VERSION"
echo "==> building keliver-portal-tools $TOOLS_VERSION"
echo "    maven dependency version: $MAVEN_VERSION (unchanged by this script)"
echo "    source commit: $SOURCE_COMMIT${SOURCE_DIRTY:+ (+$SOURCE_DIRTY uncommitted)}"

echo "==> gradle: relay + mcp installDist, editor wasm dist, device host APK"
# U22: the bundled APK is the GENERIC DEVELOPMENT HOST. -Pkeliver.devOnlyHost=true
# is what makes it one: no portal key is embedded and BuildConfig.DEV_ONLY is
# true, so it refuses production mode instead of running it unverified. This is
# an explicit build input — it must NOT depend on whether the build machine
# happens to have a portal store, which is how a developer's own key ended up
# inside a candidate bundle.
./gradlew -q -Pkeliver.devOnlyHost=true \
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
# The store contract has to travel with the tools. keliver-record-http.sh asks
# keliver-store-path.sh where this app's store is; without both, an adopter's
# recording client looks for its token in a directory that stopped being the
# store. keliver-adopt-legacy-store.sh is the documented upgrade route.
# keliver-store-recover.sh is the supported answer when an app has been moved,
# renamed, or split across a symlink and its real path — the relay refuses to
# start in those cases and names this command, so it must be in the bundle the
# refusal is printed from.
cp scripts/keliver-store-path.sh scripts/keliver-record-http.sh \
   scripts/keliver-adopt-legacy-store.sh scripts/keliver-store-recover.sh "$STAGE/bin/"
chmod +x "$STAGE/bin/keliver-portal" "$STAGE/bin/keliver-init" \
  "$STAGE/bin/keliver-new-screen.sh" "$STAGE/bin/keliver-new-component.sh" "$STAGE/bin/keliver-new-editor.sh" \
  "$STAGE/bin/keliver-new-device-target.sh" "$STAGE/bin/keliver-install-device-host.sh" \
  "$STAGE/bin/keliver-store-path.sh" "$STAGE/bin/keliver-record-http.sh" \
  "$STAGE/bin/keliver-adopt-legacy-store.sh" "$STAGE/bin/keliver-store-recover.sh"

# The device host APK, so `keliver-new-device-target.sh` has somewhere to run.
# This is a LOCALLY BUILT artifact shipped inside this bundle — it is NOT
# published anywhere, and nothing fetches it from a store or a release page.
APK_SRC=portal-device-android/build/outputs/apk/debug/portal-device-android-debug.apk
# Fail the build rather than ship a host carrying somebody's portal identity.
if unzip -l "$APK_SRC" | grep -q 'assets/portal_ed25519.pub'; then
  echo "REFUSING to package: $APK_SRC embeds assets/portal_ed25519.pub." >&2
  echo "The bundled host must be development-only and key-free (U22)." >&2
  exit 1
fi
cp "$APK_SRC" "$STAGE/host/keliver-device-host-$VERSION.apk"
( cd "$STAGE/host" && shasum -a 256 "keliver-device-host-$VERSION.apk" > "keliver-device-host-$VERSION.apk.sha256" )
cp docs/DEVICE_HOST.md "$STAGE/host/README.md"
# The gradle wrapper so `keliver-init` scaffolds immediately-buildable projects.
cp gradlew gradlew.bat "$STAGE/wrapper/" 2>/dev/null || true
cp gradle/wrapper/* "$STAGE/wrapper/gradle/wrapper/"
cp docs/PORTAL_TOOLS_README.md "$STAGE/README.md"

# What this package IS, recorded inside it. An adopter holding only the zip can
# answer: which tools build is this, what source produced it, and which Maven
# coordinate will my scaffolded project depend on.
cat > "$STAGE/VERSION.json" <<JSON
{
  "toolsVersion": "$TOOLS_VERSION",
  "sourceCommit": "$SOURCE_COMMIT",
  "sourceDirtyFiles": $SOURCE_DIRTY,
  "mavenDependencyVersion": "$MAVEN_VERSION",
  "builtBy": "scripts/build-portal-tools.sh"
}
JSON
printf 'keliver-portal-tools %s\nsource commit %s\nmaven dependency version %s\n' \
  "$TOOLS_VERSION" "$SOURCE_COMMIT" "$MAVEN_VERSION" > "$STAGE/VERSION"

# zip UPDATES an existing archive, so a stale zip keeps entries that no longer
# exist in the staging tree. Remove it first; $STAGE is already rebuilt above.
rm -f "$OUT/keliver-portal-tools-$VERSION.zip"
( cd "$OUT" && zip -qr "keliver-portal-tools-$VERSION.zip" "keliver-portal-tools-$VERSION" )
echo "==> bundle: $OUT/keliver-portal-tools-$VERSION.zip"
ls -lh "$OUT/keliver-portal-tools-$VERSION.zip" | awk '{print "    "$5}'
