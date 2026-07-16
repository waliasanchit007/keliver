#!/bin/bash
# keliver new-component — scaffold a portal-recognizable project component
# ("molecule") built from keliver primitives. Its Kotlin SIGNATURE is its
# portal spec; screens can call it and edit/preview it in the portal.
# Usage: scripts/keliver-new-component.sh <ComponentName>   (e.g. MenuRow)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NAME="${1:?usage: keliver-new-component.sh <ComponentName>}"
[[ "$NAME" =~ ^[A-Z][A-Za-z0-9]*$ ]] || { echo "ComponentName must be UpperCamelCase (got: $NAME)"; exit 1; }

# Resolve componentsDir from keliver.portal.json (default: sibling of screensDir).
read -r SCREENS_REL COMPS_REL < <(python3 - "$ROOT/keliver.portal.json" <<'PY'
import json, sys, os
try:
    c = json.load(open(sys.argv[1]))
except Exception:
    c = {}
screens = c.get("screensDir", "portal-app-lib/src/jsMain/kotlin/screens")
comps = c.get("componentsDir")
if not comps:
    parent = os.path.dirname(screens)
    comps = (parent + "/components") if parent else "components"
print(screens, comps)
PY
)
COMPS_DIR="$ROOT/$COMPS_REL"
FILE="$COMPS_DIR/$NAME.kt"
[ -e "$FILE" ] && { echo "refusing to overwrite $FILE"; exit 1; }
mkdir -p "$COMPS_DIR"

# Derive the package from an existing sibling component, else from a screen, else
# from the components path — never hardcode a foreign package.
pkg_from() { grep -m1 -E '^package ' "$1" 2>/dev/null | sed 's/^package //'; }
PKG=""
for f in "$COMPS_DIR"/*.kt; do [ -e "$f" ] && { PKG="$(pkg_from "$f")"; break; }; done
if [ -z "$PKG" ]; then
  # sibling screen package with the trailing segment swapped to .components
  for f in "$ROOT/$SCREENS_REL"/*.kt; do
    [ -e "$f" ] || continue
    sp="$(pkg_from "$f")"; [ -n "$sp" ] && { PKG="${sp%.*}.components"; break; }
  done
fi
[ -z "$PKG" ] && PKG="$(echo "$COMPS_REL" | sed -E 's#.*/kotlin/##; s#/#.#g')"

cat > "$FILE" <<EOF
package $PKG

import androidx.compose.runtime.Composable
import dev.keliver.material.compose.ListItem

/**
 * Scaffolded by keliver-new-component. The SIGNATURE is the portal spec:
 * scalar params (String/Int/Boolean/Double) become editable props, and
 * () -> Unit / (T) -> Unit params become events. Keep the body inside the
 * portal grammar (primitives, other components, if/forEach) to stay
 * transparently previewable; anything else makes it opaque (still renders on
 * devices, just not expanded in the portal).
 */
@Composable
fun $NAME(
  title: String,
  subtitle: String,
  icon: String = "Star",
  onClick: () -> Unit,
) {
  ListItem(
    headline = title,
    supporting = subtitle,
    leadingIcon = icon,
    trailingIcon = "KeyboardArrowRight",
    onClick = onClick,
  )
}
EOF

echo "created  ${FILE#$ROOT/}   (package $PKG)"
echo ""
echo "next:"
echo "  • use it in a screen:  $NAME(title = b.x, subtitle = \"...\", onClick = { b.go() })"
echo "  • it appears under 'Project components' in the editor palette"
echo "  • edit its definition (this file) — every instance preview updates live"
