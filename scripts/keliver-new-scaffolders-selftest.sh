#!/usr/bin/env bash
#
# keliver-new-scaffolders-selftest — keliver-new-screen.sh and
# keliver-new-component.sh write into the APP they are run from, in the app's
# own packages, never into the directory the script lives in.
#
#   scripts/keliver-new-scaffolders-selftest.sh <disposable-parent> [--package <unpacked tools bundle>]
#
# Without --package it copies both scripts into a fake bundle's bin/ (what
# build-portal-tools.sh does), so the script's parent is NOT the app — the shape
# that wrote into the bundle before 2026-10-27. With --package it runs the
# copies in a real unpacked bundle. Uses only a fixture app in the run directory.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
PARENT="${1:?usage: $0 <disposable-parent> [--package <unpacked tools bundle>]}"
mkdir -p "$PARENT"
RUN="$(mktemp -d "$(cd "$PARENT" && pwd -P)/keliver-scaffolders-XXXXXX")"
if [ "${2:-}" = "--package" ]; then
  BIN="$(cd "${3:?--package needs a directory}" && pwd -P)/bin"
else
  BIN="$RUN/bundle/bin"
  mkdir -p "$BIN"
  cp "$ROOT/scripts/keliver-new-screen.sh" "$ROOT/scripts/keliver-new-component.sh" "$BIN/"
fi
pass=0; fail=0
ok(){ echo "  PASS  $1"; pass=$((pass + 1)); }
bad(){ echo "  FAIL  $1"; fail=$((fail + 1)); }
listing(){ ( cd "$1" && find . -type f | sort ); }

# A fixture app laid out as keliver-init lays one out: screens in its own package.
APP="$RUN/acme"
mkdir -p "$APP/src/jsMain/kotlin/screens"
cat > "$APP/keliver.portal.json" <<'EOF'
{ "screensDir": "src/jsMain/kotlin/screens" }
EOF
cat > "$APP/src/jsMain/kotlin/screens/home.kt" <<'EOF'
package acme.screens

fun placeholder() = Unit
EOF
bundle_before="$(listing "$(dirname "$BIN")")"

echo "=== keliver-new-screen.sh, run from the app's root"
( cd "$APP" && bash "$BIN/keliver-new-screen.sh" Profile ) > "$RUN/screen.log" 2>&1; rc=$?
S="$APP/src/jsMain/kotlin/screens/profile.kt"; L="$APP/src/jsMain/kotlin/logic/ProfilePresenter.kt"
[ "$rc" = 0 ] && [ -f "$S" ] && [ -f "$L" ] \
  && ok "the screen and its presenter are in the app (screens/profile.kt, logic/ProfilePresenter.kt)" \
  || { bad "the screen was not written into the app (rc=$rc)"; cat "$RUN/screen.log"; }
grep -qx 'package acme.screens' "$S" 2>/dev/null && grep -qx 'package acme.logic' "$L" 2>/dev/null \
  && grep -q 'import acme.screens.ProfileScreenBindings' "$L" \
  && ok "they take the app's packages (acme.screens, acme.logic), not Keliver's own" \
  || bad "wrong packages: $(grep -h '^package' "$S" "$L" 2>/dev/null | tr '\n' ' ')"
( cd "$APP" && bash "$BIN/keliver-new-screen.sh" Profile ) > "$RUN/screen-again.log" 2>&1
[ $? -ne 0 ] && grep -q "refusing to overwrite" "$RUN/screen-again.log" \
  && ok "a second run refuses to overwrite the screen" || bad "a second run did not refuse"

echo "=== keliver-new-component.sh, leaf and --slot"
( cd "$APP" && bash "$BIN/keliver-new-component.sh" MenuRow && bash "$BIN/keliver-new-component.sh" --slot SectionCard ) > "$RUN/component.log" 2>&1; rc=$?
C="$APP/src/jsMain/kotlin/components"
[ "$rc" = 0 ] && grep -qx 'package acme.components' "$C/MenuRow.kt" 2>/dev/null \
  && grep -q 'content: @Composable () -> Unit' "$C/SectionCard.kt" 2>/dev/null \
  && ok "both components are in the app's components/ (package acme.components; the slot one takes content)" \
  || { bad "the components were not written into the app (rc=$rc)"; cat "$RUN/component.log"; }

echo "=== nothing written next to the scripts; no app, no write"
[ "$(listing "$(dirname "$BIN")")" = "$bundle_before" ] \
  && ok "the directory the scripts live in is unchanged" \
  || bad "files appeared beside the scripts: $(diff <(echo "$bundle_before") <(listing "$(dirname "$BIN")") | grep '^>' | head -3 | tr '\n' ' ')"
NOAPP="$RUN/not-an-app"; mkdir -p "$NOAPP"
( cd "$NOAPP" && bash "$BIN/keliver-new-screen.sh" Orphan ) > "$RUN/noapp-screen.log" 2>&1; r1=$?
( cd "$NOAPP" && bash "$BIN/keliver-new-component.sh" Orphan ) > "$RUN/noapp-component.log" 2>&1; r2=$?
[ "$r1" -ne 0 ] && [ "$r2" -ne 0 ] && [ -z "$(listing "$NOAPP")" ] \
  && grep -q "no keliver.portal.json" "$RUN/noapp-screen.log" && grep -q "no keliver.portal.json" "$RUN/noapp-component.log" \
  && ok "outside an app both refuse, say why, and write nothing" \
  || bad "outside an app: screen rc=$r1, component rc=$r2, files: $(listing "$NOAPP" | tr '\n' ' ')"
( cd "$NOAPP" && KELIVER_APP_DIR="$APP" bash "$BIN/keliver-new-screen.sh" Settings ) > "$RUN/appdir.log" 2>&1
[ -f "$APP/src/jsMain/kotlin/screens/settings.kt" ] && [ -z "$(listing "$NOAPP")" ] \
  && ok "KELIVER_APP_DIR names the app from anywhere" || bad "KELIVER_APP_DIR was not used"

echo ""
echo "scaffolders: $pass passed, $fail failed   ($RUN)"
[ "$fail" -eq 0 ]
