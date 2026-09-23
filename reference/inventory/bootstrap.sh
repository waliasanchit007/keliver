#!/usr/bin/env bash
#
# Recreate the inventory reference app from the PUBLIC tools release.
#
#   reference/inventory/bootstrap.sh <parent-dir> [keliver-portal-tools-0.3.5.zip]
#
# With no zip, downloads the published tools 0.3.5 release and checks it against
# the release's own .sha256 AND the hash pinned below. With a zip, checks that
# zip against the pinned hash, so a rebuild cannot be substituted by accident.
#
# Then, in <parent-dir>:
#   tools/                     the unpacked bundle ($KP = tools/.../bin)
#   inventory/                 the app, a fresh git repository
#   inventory/hand-edits.diff  what differs from the scaffolders' own output
#
# The app is built by the adopter route, in order: keliver-init, the source in
# app/, keliver-new-device-target.sh, keliver-new-editor.sh — and then the
# files the scaffolders cannot produce are overlaid. hand-edits.diff records
# exactly those, because each one is a step an adopter would have to discover.
#
# This script scaffolds and copies. It does not start a relay, resolve a store,
# or create a key. The first relay start does that — see README.md for the
# isolation that run needs.
set -euo pipefail

TOOLS_VERSION=0.3.5
TOOLS_SHA256=4e1c3040c2e069ab2a503f4b45a7740a28ac243edfb233cdffc7bcb7e7eeb439
RELEASE=https://github.com/waliasanchit007/keliver/releases/download/portal-tools-v$TOOLS_VERSION
ZIPNAME=keliver-portal-tools-$TOOLS_VERSION.zip

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PARENT="${1:?usage: $0 <parent-dir> [$ZIPNAME]}"
ZIP="${2:-}"

sha256(){ if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

mkdir -p "$PARENT"
PARENT="$(cd "$PARENT" && pwd -P)"
[ ! -e "$PARENT/inventory" ] || { echo "refusing: $PARENT/inventory already exists" >&2; exit 2; }
[ ! -e "$PARENT/tools" ] || { echo "refusing: $PARENT/tools already exists" >&2; exit 2; }

# --- the tools bundle ---------------------------------------------------------
if [ -z "$ZIP" ]; then
  mkdir -p "$PARENT/download"
  ( cd "$PARENT/download"
    curl -fsSLO "$RELEASE/$ZIPNAME"
    curl -fsSLO "$RELEASE/$ZIPNAME.sha256"
    sha256 -c "$ZIPNAME.sha256" )
  ZIP="$PARENT/download/$ZIPNAME"
  echo "==> downloaded the public release: $RELEASE/$ZIPNAME"
fi
ZIP="$(cd "$(dirname "$ZIP")" && pwd -P)/$(basename "$ZIP")"
got="$(sha256 "$ZIP" | cut -d' ' -f1)"
[ "$got" = "$TOOLS_SHA256" ] || {
  echo "refusing: $ZIP has sha256 $got, not the released $TOOLS_SHA256" >&2; exit 3; }
echo "==> tools zip sha256 $got (the published 0.3.5 asset)"

mkdir -p "$PARENT/tools"
( cd "$PARENT/tools" && unzip -q "$ZIP" )
KP="$PARENT/tools/keliver-portal-tools-$TOOLS_VERSION/bin"
unzip -p "$ZIP" "keliver-portal-tools-$TOOLS_VERSION/VERSION.json"

# --- the adopter route --------------------------------------------------------
cd "$PARENT"
env -u KELIVER_USE_MAVEN_LOCAL "$KP/keliver-init" Inventory
APP="$PARENT/inventory"
cd "$APP"

# The scaffolded starter screen is replaced by this app's two screens.
rm src/jsMain/kotlin/screens/home.kt src/jsMain/kotlin/logic/HomePresenter.kt
cp "$HERE"/app/src/jsMain/kotlin/screens/*.kt src/jsMain/kotlin/screens/
cp "$HERE"/app/src/jsMain/kotlin/logic/*.kt src/jsMain/kotlin/logic/

# Two screens exist, so the device scaffolder has to be told which to wire.
"$KP/keliver-new-device-target.sh" --screen InventoryScreen --presenter InventoryPresenter
"$KP/keliver-new-editor.sh" Inventory src/jsMain/kotlin/logic src/jsMain/kotlin/screens

# Record the scaffolders' own output, then overlay what they cannot write.
git init -q
git add -A
git -c user.name=bootstrap -c user.email=bootstrap@invalid commit -qm \
  "scaffolded: keliver-init + device target + editor (tools $TOOLS_VERSION), inventory screens and logic"

for f in keliver.portal.json build.gradle settings.gradle \
         src/jsMain/kotlin/device/Main.kt \
         editor/src/wasmJsMain/kotlin/InventoryPreview.kt; do
  cp "$HERE/app/$f" "$f"
done
git diff > hand-edits.diff
git add -A
git -c user.name=bootstrap -c user.email=bootstrap@invalid commit -qm \
  "hand edits the scaffolders do not make (see hand-edits.diff)"

echo
echo "==> $APP"
echo "    KP=$KP"
echo "    hand edits over the scaffolders' output: $(grep -c '^+[^+]' hand-edits.diff) added lines in"
grep '^diff --git' hand-edits.diff | sed 's|^diff --git a/\([^ ]*\).*|      \1|'
