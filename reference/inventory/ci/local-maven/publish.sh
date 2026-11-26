#!/usr/bin/env bash
#
# The reference app's third route, local-maven: THIS checkout's Keliver libraries, published into a
# disposable local Maven repository that the app's guest, its Android host and its iOS host then
# build against (ci/local-maven/route.sh). The other two routes take the libraries from Maven
# Central.
#
#   ci/local-maven/publish.sh <maven-dir> <android|ios> <evidence-dir>
#
# <maven-dir> must not exist: a fresh directory (the workflows use the runner's temp space), never
# ~/.m2. Published, unsigned (-DRELEASE_SIGNING_ENABLED=false) and without Dokka:
#  - the modules the app names: its guest's build.gradle overlay (reference/inventory/app) and the
#    host templates' dependencies (production-host, ios-host), plus keliver-image, which the 0.5
#    line's hosts take Coil from (keliver-runtime-line.sh);
#  - every module of this build their published variants depend on (publish.init.gradle);
#  - only the publications the platform needs: the root (kotlinMultiplatform), js (the guest),
#    and androidRelease and jvm (android) or iosSimulatorArm64 (ios).
# The version is KELIVER_VERSION (RedwoodBuildPlugin.kt) on the line this checkout builds,
# "-local.", and this checkout's commit (route.sh's lm_publish_version): a version Maven Central
# never has, on the line whose Kotlin and Zipline this checkout's libraries are built with, so the
# scaffolders give the app that toolchain. 0.5.0-local.<commit> while KELIVER_VERSION is the 0.4.1
# release and main builds the 0.5 line. No CHANGELOG heading is read.
# The repository gets keliver-ref-app.env (KELIVER_LOCAL_VERSION, KELIVER_LOCAL_COMMIT,
# KELIVER_LOCAL_PLATFORM, KELIVER_LOCAL_SECONDS); route.sh reads it.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO="$(cd "$HERE/../../../.." && pwd -P)"
DIR="${1:?usage: $0 <maven-dir> <android|ios> <evidence-dir>}"
PLATFORM="${2:?}"
EV="${3:?}"
case "$PLATFORM" in
  android) PUBLICATIONS=kotlinMultiplatform,js,jvm,androidRelease,maven ;;
  ios)     PUBLICATIONS=kotlinMultiplatform,js,iosSimulatorArm64,maven ;;
  *) echo "publish.sh: the platform is android or ios, not $PLATFORM" >&2; exit 2 ;;
esac
[ ! -e "$DIR" ] || { echo "publish.sh: $DIR already exists; the repository must be a fresh directory" >&2; exit 2; }
case "$DIR" in /*) ;; *) echo "publish.sh: <maven-dir> must be an absolute path" >&2; exit 2 ;; esac
case "$DIR/" in "$HOME/.m2/"*) echo "publish.sh: never ~/.m2" >&2; exit 2 ;; esac
mkdir -p "$DIR" "$EV"
EV="$(cd "$EV" && pwd -P)"

COMMIT="$(git -C "$REPO" rev-parse HEAD)"
# shellcheck source=route.sh
. "$HERE/route.sh"
lm_publish_version "$REPO" "$COMMIT" || { echo "publish.sh: $LM_ERROR" >&2; exit 1; }
VERSION="$LM_PUBLISH_VERSION"

# The artifacts the app and its hosts name, as this checkout's project paths.
MODULES="$(
  { grep -hoE "dev\.keliver:[a-z0-9-]+:" "$REPO/reference/inventory/app/build.gradle" \
      "$REPO/scripts/templates/production-host/build.gradle" "$REPO/scripts/templates/ios-host/build.gradle"
    echo "dev.keliver:keliver-image:"
  } | cut -d: -f2 | sort -u | while read -r m; do
    if [ -f "$REPO/$m/build.gradle" ] || [ -f "$REPO/$m/build.gradle.kts" ]; then echo ":$m"; fi
  done | paste -sd, -
)"
[ -n "$MODULES" ] || { echo "publish.sh: found no modules to publish" >&2; exit 1; }

echo "==> local-maven: publishing this checkout's libraries ($PLATFORM) as $VERSION into $DIR"
echo "    roots: $MODULES"
t0=$(date +%s)
set +e
# No daemon and the Kotlin compiler in-process: nothing of this build stays in memory for the
# emulator or the simulator that follows it.
( cd "$REPO" && ./gradlew --console=plain --no-configuration-cache --no-daemon \
    -Pkotlin.compiler.execution.strategy=in-process \
    -I "$HERE/publish.init.gradle" \
    -PkeliverVersion="$VERSION" -PkeliverRefMavenDir="$DIR" \
    -PkeliverRefModules="$MODULES" -PkeliverRefPublications="$PUBLICATIONS" \
    -DRELEASE_SIGNING_ENABLED=false \
    keliverRefAppPublish ) > "$EV/local-maven-publish.log" 2>&1
rc=$?
set -e
secs=$(( $(date +%s) - t0 ))
if [ "$rc" -ne 0 ]; then
  echo "publish.sh: the publish failed (exit $rc, ${secs} s):" >&2
  grep -E "What went wrong|^e: |error:" -A4 "$EV/local-maven-publish.log" | head -40 >&2
  exit 1
fi
{
  echo "KELIVER_LOCAL_VERSION=$VERSION"
  echo "KELIVER_LOCAL_COMMIT=$COMMIT"
  echo "KELIVER_LOCAL_PLATFORM=$PLATFORM"
  echo "KELIVER_LOCAL_SECONDS=$secs"
} > "$DIR/keliver-ref-app.env"
poms="$(find "$DIR/dev/keliver" -name "*-$VERSION.pom" | wc -l | tr -d ' ')"
{
  echo "route: local-maven (this checkout's libraries, published for $PLATFORM)"
  echo "version: $VERSION"
  echo "commit: $COMMIT"
  echo "repository: $DIR"
  echo "publications: $PUBLICATIONS"
  echo "seconds: $secs"
  echo "poms: $poms"
  echo "modules ($(grep -c . "$DIR/keliver-ref-app-modules.txt")):"
  sed 's/^/  /' "$DIR/keliver-ref-app-modules.txt"
} | tee "$EV/local-maven-publish.txt"
[ "$poms" -gt 0 ] || { echo "publish.sh: no POM at $VERSION was written" >&2; exit 1; }
