#!/usr/bin/env bash
#
# keliver-app-wrapper — install the Gradle wrapper for an app built on the
# scaffolder templates' toolchain into <dir>.
#
#   scripts/keliver-app-wrapper.sh <dir> [keliver version]
#
# Writes <dir>/gradlew, <dir>/gradlew.bat and <dir>/gradle/wrapper/: the
# launcher and its jar from this checkout's root, and the properties from
# scripts/templates/app-wrapper/wrapper.properties, which pin the Gradle the
# templates' AGP/Kotlin/Compose pins run on. That is deliberately independent
# of this repository's own wrapper (see scripts/templates/app-wrapper/README.md).
# With a Keliver version, the Gradle is that version's line's
# (keliver-runtime-line.sh): the file as it is before 0.5.0, a newer Gradle from 0.5.0.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DEST="${1:?usage: $0 <dir> [keliver version]}"
VERSION="${2:-}"
PROPS="$ROOT/scripts/templates/app-wrapper/wrapper.properties"
[ -f "$PROPS" ] || { echo "keliver-app-wrapper: $PROPS is missing" >&2; exit 1; }
# The version's line first: a version this bundle has no line for writes nothing.
if [ -n "$VERSION" ]; then
  # shellcheck source=keliver-runtime-line.sh
  . "$ROOT/scripts/keliver-runtime-line.sh"
  keliver_zipline_line "$VERSION" || { echo "keliver-app-wrapper: $KELIVER_LINE_ERROR" >&2; exit 1; }
fi
mkdir -p "$DEST/gradle/wrapper"
cp "$ROOT/gradlew" "$DEST/gradlew"
[ -f "$ROOT/gradlew.bat" ] && cp "$ROOT/gradlew.bat" "$DEST/gradlew.bat"
cp "$ROOT/gradle/wrapper/gradle-wrapper.jar" "$DEST/gradle/wrapper/gradle-wrapper.jar"
cp "$PROPS" "$DEST/gradle/wrapper/gradle-wrapper.properties"
chmod 755 "$DEST/gradlew"
if [ -n "$VERSION" ]; then
  keliver_line_wrapper_properties "$DEST/gradle/wrapper/gradle-wrapper.properties" \
    || { echo "keliver-app-wrapper: could not write Gradle $TOOLCHAIN_GRADLE" >&2; exit 1; }
fi
