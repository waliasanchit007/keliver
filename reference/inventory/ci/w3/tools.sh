#!/usr/bin/env bash
#
# keliver-publish from THIS checkout, laid out as the tools bundle lays it out
# (bin/keliver-publish beside relay/bin/keliver-publish-jvm), because no
# published tools release has it yet.
#
#   ci/w3/tools.sh <dir>     -> <dir>/bin/keliver-publish
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO="$(cd "$HERE/../../../.." && pwd -P)"
D="${1:?usage: $0 <dir>}"
( cd "$REPO" && ./gradlew --console=plain -q :portal-relay:installDist )
rm -rf "$D"; mkdir -p "$D/bin"
cp -R "$REPO/portal-relay/build/install/portal-relay" "$D/relay"
cp "$REPO/scripts/keliver-publish" "$REPO/scripts/keliver-store-path.sh" "$D/bin/"
chmod +x "$D/bin/keliver-publish" "$D/bin/keliver-store-path.sh"
test -x "$D/relay/bin/keliver-publish-jvm"
echo "$D/bin/keliver-publish"
