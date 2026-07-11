#!/bin/bash
# keliver new-screen — scaffold a portal-editable screen + hand-owned presenter.
# Usage: scripts/keliver-new-screen.sh <ScreenName>   (e.g. Profile)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NAME="${1:?usage: keliver-new-screen.sh <ScreenName>}"
[[ "$NAME" =~ ^[A-Z][A-Za-z0-9]*$ ]] || { echo "ScreenName must be UpperCamelCase (got: $NAME)"; exit 1; }
lower="$(echo "${NAME:0:1}" | tr '[:upper:]' '[:lower:]')${NAME:1}"
SCREENS_REL="$(python3 -c "import json;print(json.load(open('$ROOT/keliver.portal.json')).get('screensDir','portal-app-lib/src/jsMain/kotlin/screens'))" 2>/dev/null || echo portal-app-lib/src/jsMain/kotlin/screens)"
SCREENS_DIR="$ROOT/$SCREENS_REL"
LOGIC_DIR="$(dirname "$SCREENS_DIR")/logic"
SCREEN_FILE="$SCREENS_DIR/$lower.kt"
PRESENTER_FILE="$LOGIC_DIR/${NAME}Presenter.kt"
[ -e "$SCREEN_FILE" ] && { echo "refusing to overwrite $SCREEN_FILE"; exit 1; }
[ -e "$PRESENTER_FILE" ] && { echo "refusing to overwrite $PRESENTER_FILE"; exit 1; }
mkdir -p "$SCREENS_DIR" "$LOGIC_DIR"

cat > "$SCREEN_FILE" <<EOF
package dev.keliver.portalpublished.screens

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
package dev.keliver.portalpublished.logic

import androidx.compose.runtime.Composable
import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.portalpublished.screens.${NAME}ScreenBindings

/** HAND-OWNED: produce ${NAME}Screen's bindings (Style B presenter). */
@Composable
fun ${NAME}Presenter(sql: HostSqlDriver?): ${NAME}ScreenBindings {
  return object : ${NAME}ScreenBindings {
    override val title: String = "$NAME"
  }
}
EOF

echo "created  ${SCREEN_FILE#$ROOT/}"
echo "created  ${PRESENTER_FILE#$ROOT/}"
echo ""
echo "wire it in PublishedEntry.kt:  ${NAME}Screen(${NAME}Presenter(sql))"
echo "it is already live for editing: scripts/keliver-dev.sh, then select '$lower' in the editor"
