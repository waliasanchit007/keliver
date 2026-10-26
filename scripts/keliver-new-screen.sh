#!/bin/bash
# keliver new-screen — scaffold a portal-editable screen + hand-owned presenter.
# Usage, from the app's root (where keliver.portal.json is):
#   keliver-new-screen.sh <ScreenName>   (e.g. Profile)
# The APP is the working directory (or KELIVER_APP_DIR), never the script's
# parent: shipped in the tools bundle's bin/, the parent is the bundle.
set -euo pipefail
ROOT="${KELIVER_APP_DIR:-$PWD}"
NAME="${1:?usage: keliver-new-screen.sh <ScreenName>}"
[[ "$NAME" =~ ^[A-Z][A-Za-z0-9]*$ ]] || { echo "ScreenName must be UpperCamelCase (got: $NAME)"; exit 1; }
[ -f "$ROOT/keliver.portal.json" ] || {
  echo "no keliver.portal.json in $ROOT — run this from the app's root, or set KELIVER_APP_DIR" >&2
  exit 1
}
lower="$(echo "${NAME:0:1}" | tr '[:upper:]' '[:lower:]')${NAME:1}"
# The relay's default when the key is absent (PortalConfig.screensDir).
SCREENS_REL="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("screensDir","portal-app-lib/src/jsMain/kotlin/screens"))' "$ROOT/keliver.portal.json")"
SCREENS_DIR="$ROOT/$SCREENS_REL"
LOGIC_DIR="$(dirname "$SCREENS_DIR")/logic"
# The package of the app's existing screens, else one derived from the path
# after kotlin/ — never this repository's own package.
pkg_from() { grep -m1 -E '^package ' "$1" 2>/dev/null | sed 's/^package //'; }
SCREENS_PKG=""
for f in "$SCREENS_DIR"/*.kt; do
  [ -e "$f" ] || continue
  SCREENS_PKG="$(pkg_from "$f")"; [ -n "$SCREENS_PKG" ] && break
done
[ -n "$SCREENS_PKG" ] || SCREENS_PKG="$(echo "$SCREENS_REL" | sed -E 's#.*/kotlin/##; s#/+$##; s#/#.#g')"
case "$SCREENS_PKG" in *.screens) LOGIC_PKG="${SCREENS_PKG%.screens}.logic" ;; *) LOGIC_PKG="$SCREENS_PKG.logic" ;; esac
SCREEN_FILE="$SCREENS_DIR/$lower.kt"
PRESENTER_FILE="$LOGIC_DIR/${NAME}Presenter.kt"
[ -e "$SCREEN_FILE" ] && { echo "refusing to overwrite $SCREEN_FILE"; exit 1; }
[ -e "$PRESENTER_FILE" ] && { echo "refusing to overwrite $PRESENTER_FILE"; exit 1; }
mkdir -p "$SCREENS_DIR" "$LOGIC_DIR"

cat > "$SCREEN_FILE" <<EOF
package $SCREENS_PKG

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledText

/** Scaffolded by keliver-new-screen — every node below is portal-editable. */
@Composable
fun ${NAME}Screen(b: ${NAME}ScreenBindings) {
  Column {
    StyledText(
      text = b.title,
      fontSize = 24,
      bold = true,
    )
  }
}

/** The round-trip boundary: implement this by hand; the portal never touches it. */
interface ${NAME}ScreenBindings {
  val title: String
}
EOF

cat > "$PRESENTER_FILE" <<EOF
package $LOGIC_PKG

import androidx.compose.runtime.Composable
import $SCREENS_PKG.${NAME}ScreenBindings

/** HAND-OWNED: produce ${NAME}Screen's bindings (Style B presenter). */
@Composable
fun ${NAME}Presenter(): ${NAME}ScreenBindings {
  return object : ${NAME}ScreenBindings {
    override val title: String = "$NAME"
  }
}
EOF

echo "created  ${SCREEN_FILE#$ROOT/}   (package $SCREENS_PKG)"
echo "created  ${PRESENTER_FILE#$ROOT/}   (package $LOGIC_PKG)"
echo ""
echo "next:"
echo "  • show it where your app shows screens:  ${NAME}Screen(${NAME}Presenter())"
echo "    (device/Main.kt in an app made by keliver-init)"
echo "  • it is already editable: with keliver-portal running, pick '$lower' in the editor"
