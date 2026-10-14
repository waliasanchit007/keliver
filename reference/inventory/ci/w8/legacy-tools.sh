#!/usr/bin/env bash
# W8's K3 and K6 need a host as the field has it: scaffolded from before key
# rotation (one key, trusted only as portal-ed25519). When the tools under test
# already have W8 (0.3.9 on), that host comes from the PUBLISHED tools 0.3.8
# zip, checked against its pinned hash.
#
#   ci/w8/legacy-tools.sh <dir>     prints the bin/ of tools 0.3.8 unpacked in <dir>
#
# KELIVER_LEGACY_TOOLS_ZIP names a local copy instead of downloading it (for a
# network that blocks release assets); it is checked against the same hash.
# Exits non-zero, printing nothing on stdout, when it cannot provide it.
set -uo pipefail
DIR="${1:?usage: $0 <dir>}"
VERSION=0.3.8
SHA256=aafed0d810243bf0a44e800f4ad3e958eda443ee138af49bbae432b2b0be912d
ZIPNAME="keliver-portal-tools-$VERSION.zip"
sha(){ if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
mkdir -p "$DIR"
if [ -x "$DIR/keliver-portal-tools-$VERSION/bin/keliver-new-production-host.sh" ]; then
  echo "$DIR/keliver-portal-tools-$VERSION/bin"; exit 0
fi
if [ -n "${KELIVER_LEGACY_TOOLS_ZIP:-}" ]; then
  cp "$KELIVER_LEGACY_TOOLS_ZIP" "$DIR/$ZIPNAME" || exit 1
else
  curl -fsSL --max-time 300 -o "$DIR/$ZIPNAME" \
    "https://github.com/waliasanchit007/keliver/releases/download/portal-tools-v$VERSION/$ZIPNAME" >&2 || exit 1
fi
[ "$(sha "$DIR/$ZIPNAME")" = "$SHA256" ] || { echo "legacy-tools: $ZIPNAME is not the published $VERSION ($SHA256)" >&2; exit 1; }
( cd "$DIR" && unzip -q "$ZIPNAME" ) || exit 1
echo "$DIR/keliver-portal-tools-$VERSION/bin"
