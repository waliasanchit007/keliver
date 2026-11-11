#!/usr/bin/env bash
#
# keliver-app-wrapper — install the Gradle wrapper for an app built on the
# scaffolder templates' toolchain into <dir>.
#
#   scripts/keliver-app-wrapper.sh <dir>
#
# Writes <dir>/gradlew, <dir>/gradlew.bat and <dir>/gradle/wrapper/: the
# launcher and its jar from this checkout's root, and the properties from
# scripts/templates/app-wrapper/wrapper.properties, which pin the Gradle the
# templates' AGP/Kotlin/Compose pins run on. That is deliberately independent
# of this repository's own wrapper (see scripts/templates/app-wrapper/README.md).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DEST="${1:?usage: $0 <dir>}"
PROPS="$ROOT/scripts/templates/app-wrapper/wrapper.properties"
[ -f "$PROPS" ] || { echo "keliver-app-wrapper: $PROPS is missing" >&2; exit 1; }
mkdir -p "$DEST/gradle/wrapper"
cp "$ROOT/gradlew" "$DEST/gradlew"
[ -f "$ROOT/gradlew.bat" ] && cp "$ROOT/gradlew.bat" "$DEST/gradlew.bat"
cp "$ROOT/gradle/wrapper/gradle-wrapper.jar" "$DEST/gradle/wrapper/gradle-wrapper.jar"
cp "$PROPS" "$DEST/gradle/wrapper/gradle-wrapper.properties"
chmod 755 "$DEST/gradlew"
