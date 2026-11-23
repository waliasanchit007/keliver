#!/usr/bin/env bash
#
# Regression coverage for keliver-new-device-target.sh.
#
#   scripts/keliver-new-device-target-selftest.sh [--compile]
#
# --compile additionally builds the generated output for the single-screen and
# two-screen cases. That is the only assertion that proves the emitted Kotlin
# is valid; string inspection and exit codes are not sufficient, and an earlier
# version of this scaffolder exited 0 while writing a package declaration made
# of two filenames.
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INIT="$ROOT/scripts/keliver-init"
DEV="$ROOT/scripts/keliver-new-device-target.sh"
KELIVER_VERSION="${KELIVER_VERSION:-0.4.1}"
DO_COMPILE=0
[ "${1:-}" = "--compile" ] && DO_COMPILE=1

WORK="$(mktemp -d "${TMPDIR:-/tmp}/devtarget-selftest-XXXXXX")"
pass=0; fail=0

ok()   { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
chk()  { if [ "$2" = "$3" ]; then ok "$1 (exit $3)"; else bad "$1 — expected exit $2, got $3"; fi; }

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}"

scaffold() { # name -> echoes app dir
  local n="$1" d="$WORK/$1"
  mkdir -p "$d" && ( cd "$d" && env -u KELIVER_USE_MAVEN_LOCAL KELIVER_VERSION="$KELIVER_VERSION" "$INIT" "$n" >/dev/null 2>&1 )
  echo "$d/$(echo "$n" | tr '[:upper:]' '[:lower:]')"
}

add_second_screen() { # app dir, package
  local a="$1" p="$2"
  cat > "$a/src/jsMain/kotlin/screens/detail.kt" <<EOF
package ${p}.screens

import androidx.compose.runtime.Composable
import dev.keliver.material.compose.StyledText

@Composable
fun DetailScreen(b: DetailScreenBindings) {
  StyledText(text = b.body, fontSize = 14)
}

interface DetailScreenBindings { val body: String }
EOF
  cat > "$a/src/jsMain/kotlin/logic/DetailPresenter.kt" <<EOF
package ${p}.logic
import androidx.compose.runtime.Composable
import ${p}.screens.DetailScreenBindings
@Composable
fun DetailPresenter(): DetailScreenBindings = object : DetailScreenBindings { override val body = "detail" }
EOF
}

fingerprint() { ( cd "$1" && find . -path ./build -prune -o -path ./.gradle -prune -o -type f -print | sort | xargs shasum 2>/dev/null | shasum ); }

compile_app() { # app dir -> exit code
  ( cd "$1" && env -u KELIVER_USE_MAVEN_LOCAL \
      NODE_EXTRA_CA_CERTS="${NODE_EXTRA_CA_CERTS:-$HOME/.android-certs/full-ca-bundle.pem}" \
      ./gradlew compileKotlinJs --console=plain >/dev/null 2>&1 )
}

echo "keliver-new-device-target self-test   (compile checks: $([ $DO_COMPILE = 1 ] && echo on || echo off))"
echo "workdir: $WORK"

# --- 1. unchanged single-screen starter --------------------------------------
A="$(scaffold Single)"
"$DEV" >/dev/null 2>&1 <<<"" || true
( cd "$A" && "$DEV" >/dev/null 2>&1 ); rc=$?
chk "single-screen starter succeeds" 0 "$rc"
if [ -f "$A/src/jsMain/kotlin/device/Main.kt" ]; then
  n=$(grep -c '^package ' "$A/src/jsMain/kotlin/device/Main.kt")
  [ "$n" = "1" ] && ok "single-screen: exactly one package line" || bad "single-screen: $n package lines"
  grep -q '^package single\.device$' "$A/src/jsMain/kotlin/device/Main.kt" \
    && ok "single-screen: package is 'single.device'" || bad "single-screen: wrong package"
  grep -q '/' <<<"$(grep '^package ' "$A/src/jsMain/kotlin/device/Main.kt")" \
    && bad "single-screen: package contains a path" || ok "single-screen: package has no filename"
else
  bad "single-screen: no Main.kt generated"
fi
T="$A/src/jsMain/kotlin/device/KeliverTiming.kt"
[ -f "$T" ] && grep -q '^package single\.device$' "$T" && grep -q 'take<KeliverTrace>("KeliverTrace")' "$T" && grep -q 'unavailable = true' "$T" \
  && ok "M0: device/KeliverTiming.kt marks through KeliverTrace, and stops when the host does not bind it" || bad "M0: device/KeliverTiming.kt"
grep -q '@@' "$T" && bad "M0: a template placeholder is left in KeliverTiming.kt" || ok "M0: no template placeholder left in KeliverTiming.kt"

# --- 2. two screens, explicit selection --------------------------------------
B="$(scaffold Twin)"; add_second_screen "$B" twin
( cd "$B" && "$DEV" --screen DetailScreen --presenter DetailPresenter >/dev/null 2>&1 ); rc=$?
chk "two screens with explicit selection succeeds" 0 "$rc"
if [ -f "$B/src/jsMain/kotlin/device/Main.kt" ]; then
  M="$B/src/jsMain/kotlin/device/Main.kt"
  [ "$(grep -c '^package ' "$M")" = "1" ] && ok "two-screen: exactly one package line" || bad "two-screen: multiple package lines"
  grep -q '^package twin\.device$' "$M" && ok "two-screen: package correct" || bad "two-screen: wrong package"
  grep -q 'import twin\.screens\.DetailScreen$' "$M" && ok "two-screen: imports the SELECTED screen" || bad "two-screen: wrong screen import"
  grep -q 'import twin\.logic\.DetailPresenter$' "$M" && ok "two-screen: imports the SELECTED presenter" || bad "two-screen: wrong presenter import"
  grep -q 'HomeScreen' "$M" && bad "two-screen: leaked the unselected screen" || ok "two-screen: unselected screen absent"
  grep -q 'fun launchRoute(route: String, screen: KeliverScreenHost): ZiplineTreehouseUi' "$M" && grep -q 'fun closeScreen()' "$B/src/jsMain/kotlin/device/KeliverScreenHost.kt" \
    && grep -q 'AppUi(route, screen).asZiplineTreehouseUi(appLifecycle)' "$M" \
    && grep -q '^import dev.keliver.hostkit.KeliverScreenHost$' "$M" \
    && grep -q '^package dev.keliver.hostkit$' "$B/src/jsMain/kotlin/device/KeliverScreenHost.kt" \
    && ok "two-screen: takes a route and a screen host from the host kit (launchRoute), and launch() as before" \
    || bad "two-screen: no launchRoute for the host kit's routes"
else
  bad "two-screen: no Main.kt generated"
fi

# --- 3. ambiguous selection rejected, app untouched --------------------------
C="$(scaffold Ambig)"; add_second_screen "$C" ambig
before="$(fingerprint "$C")"
( cd "$C" && "$DEV" >/dev/null 2>&1 ); rc=$?
chk "ambiguous selection rejected" 3 "$rc"
[ "$(fingerprint "$C")" = "$before" ] && ok "ambiguous: app left unchanged" || bad "ambiguous: app was modified"
[ -e "$C/src/jsMain/kotlin/device" ] && bad "ambiguous: device dir created anyway" || ok "ambiguous: no device dir"

# --- 4. missing declarations --------------------------------------------------
D="$(scaffold Nopres)"; rm -f "$D"/src/jsMain/kotlin/logic/*.kt
before="$(fingerprint "$D")"
( cd "$D" && "$DEV" >/dev/null 2>&1 ); rc=$?
chk "missing presenter rejected" 3 "$rc"
[ "$(fingerprint "$D")" = "$before" ] && ok "missing presenter: app unchanged" || bad "missing presenter: app modified"

E="$(scaffold Noscreen)"; rm -f "$E"/src/jsMain/kotlin/screens/*.kt
( cd "$E" && "$DEV" >/dev/null 2>&1 ); rc=$?
chk "missing screen rejected" 3 "$rc"

# --- 5. unsupported build-file structure -------------------------------------
F="$(scaffold Weird)"
python3 - "$F/build.gradle" <<'PY'
import re,sys
p=sys.argv[1]; s=open(p).read()
s=re.sub(r"\n(\s*)js \{ browser\(\) \}", "\n\\1js { nodejs() }", s, count=1)
open(p,'w').write(s)
PY
before="$(fingerprint "$F")"
( cd "$F" && "$DEV" >/dev/null 2>&1 ); rc=$?
chk "unsupported build-file structure rejected" 3 "$rc"
[ "$(fingerprint "$F")" = "$before" ] && ok "unsupported structure: app unchanged" || bad "unsupported structure: app modified"

# --- 6. named screen that does not exist -------------------------------------
G="$(scaffold Named)"
( cd "$G" && "$DEV" --screen NoSuchScreen >/dev/null 2>&1 ); rc=$?
chk "unknown --screen rejected" 3 "$rc"

# --- 7. refuses to overwrite an existing device dir --------------------------
( cd "$A" && "$DEV" >/dev/null 2>&1 ); rc=$?
chk "existing device dir not overwritten" 1 "$rc"

# --- 7b. the Keliver version follows the app's appRuntime.keliverVersion ------
set_app_version() { python3 -c 'import json,sys
p=sys.argv[1]; d=json.load(open(p)); d["appRuntime"]["keliverVersion"]=sys.argv[2]; json.dump(d,open(p,"w"),indent=2)' "$1/keliver.portal.json" "$2"; }
O="$(KELIVER_VERSION=0.3.3 scaffold Older)"   # keliver-init for an app on 0.3.3
( cd "$O" && env -u KELIVER_VERSION "$DEV" >/dev/null 2>&1 ); rc=$?
chk "an app pinned to 0.3.3 scaffolds" 0 "$rc"
grep -q 'keliver-treehouse-guest:0\.3\.3' "$O/build.gradle" && ! grep -q 'dev\.keliver:[a-z-]*:0\.4\.1' "$O/build.gradle" \
  && ok "an app pinned to 0.3.3 gets 0.3.3 guest dependencies, none of 0.4.1" || bad "an app pinned to 0.3.3 got another version: $(grep -o 'dev\.keliver:[a-z-]*:[0-9.]*' "$O/build.gradle" | sort -u | tr '\n' ' ')"
V="$(scaffold Override)"; set_app_version "$V" 0.3.3
( cd "$V" && KELIVER_VERSION=0.3.4 "$DEV" >/dev/null 2>&1 ); rc=$?
grep -q 'keliver-treehouse-guest:0\.3\.4' "$V/build.gradle" && ok "KELIVER_VERSION overrides the app's version" || bad "KELIVER_VERSION did not override the app's version (exit $rc)"
X="$(scaffold Badver)"; set_app_version "$X" 'x"y'; before="$(fingerprint "$X")"
( cd "$X" && env -u KELIVER_VERSION "$DEV" >/dev/null 2>&1 ); rc=$?
chk "a malformed appRuntime.keliverVersion is refused" 1 "$rc"
[ "$(fingerprint "$X")" = "$before" ] && ok "the refused app is byte-identical" || bad "the refused app was changed"

# --- 7b1. L3 (review of #299): no fixed fallback version ----------------------
# The app's Keliver version picks the whole toolchain (keliver-runtime-line.sh), so the guest-side
# scaffolders refuse a keliver.portal.json that cannot be read or parsed, or names no
# appRuntime.keliverVersion, as the host scaffolders do; KELIVER_VERSION still overrides it.
ED="$ROOT/scripts/keliver-new-editor.sh"
portal_refused() { # <label> <portal.json content> <message fragment>
  local label="$1" json="$2" want="$3" app out rc before
  app="$(scaffold "Nover$((pass + fail))")"; printf '%s' "$json" > "$app/keliver.portal.json"; before="$(fingerprint "$app")"
  out="$( cd "$app" && env -u KELIVER_VERSION "$DEV" 2>&1 )"; rc=$?
  [ "$rc" = 1 ] && [[ "$out" == *"$want"* ]] && [[ "$out" == *"or set KELIVER_VERSION"* ]] && [ "$(fingerprint "$app")" = "$before" ] \
    && ok "device target: $label is refused, byte-identical" || bad "device target: $label (exit $rc): $(printf '%s' "$out" | tail -1)"
  out="$( cd "$app" && env -u KELIVER_VERSION "$ED" Nover 2>&1 )"; rc=$?
  [ "$rc" = 1 ] && [[ "$out" == *"$want"* ]] && [ ! -e "$app/editor" ] \
    && ok "editor: $label is refused, no editor/ written" || bad "editor: $label (exit $rc): $(printf '%s' "$out" | tail -1)"
}
portal_refused "a keliver.portal.json that does not parse" '{ "screensDir": ' "keliver.portal.json cannot be read or parsed (JSONDecodeError"
portal_refused "a keliver.portal.json without appRuntime" '{ "screensDir": "src/jsMain/kotlin/screens" }' "names no appRuntime.keliverVersion"
portal_refused "an appRuntime that is not an object" '{ "screensDir": "src/jsMain/kotlin/screens", "appRuntime": "0.4.1" }' "names no appRuntime.keliverVersion"
portal_refused "an empty appRuntime.keliverVersion" '{ "appRuntime": { "widgetVersion": 1, "keliverVersion": " " } }' "names no appRuntime.keliverVersion"
portal_refused "a keliver.portal.json that is a JSON array" '[]' "names no appRuntime.keliverVersion"
# KELIVER_VERSION overrides a broken file (the documented way out), for both.
K1="$(scaffold Brokenok)"; printf '{ "screensDir": ' > "$K1/keliver.portal.json"
( cd "$K1" && KELIVER_VERSION=0.4.1 "$DEV" >/dev/null 2>&1 ); rc=$?
[ "$rc" = 0 ] && grep -q 'keliver-treehouse-guest:0\.4\.1' "$K1/build.gradle" \
  && ok "device target: KELIVER_VERSION=0.4.1 scaffolds an app whose keliver.portal.json does not parse" || bad "device target: KELIVER_VERSION with a broken keliver.portal.json (exit $rc)"
( cd "$K1" && KELIVER_VERSION=0.4.1 "$ED" Brokenok >/dev/null 2>&1 ); rc=$?
[ "$rc" = 0 ] && grep -q 'dev.keliver:portal-editor:0.4.1' "$K1/editor/build.gradle.kts" \
  && ok "editor: KELIVER_VERSION=0.4.1 scaffolds an app whose keliver.portal.json does not parse" || bad "editor: KELIVER_VERSION with a broken keliver.portal.json (exit $rc)"
# The editor follows the app's version, and refuses outside an app root unless KELIVER_VERSION is set.
K2="$(KELIVER_VERSION=0.3.3 scaffold Edolder)"
( cd "$K2" && env -u KELIVER_VERSION "$ED" Edolder >/dev/null 2>&1 ); rc=$?
[ "$rc" = 0 ] && grep -q 'dev.keliver:portal-editor:0.3.3' "$K2/editor/build.gradle.kts" \
  && ok "editor: an app pinned to 0.3.3 gets a 0.3.3 editor" || bad "editor: the 0.3.3 app's editor (exit $rc)"
mkdir -p "$WORK/notanapp"
out="$( cd "$WORK/notanapp" && env -u KELIVER_VERSION "$ED" Bare 2>&1 )"; rc=$?
[ "$rc" = 1 ] && [[ "$out" == *"no keliver.portal.json here"*"or set KELIVER_VERSION"* ]] && [ ! -e "$WORK/notanapp/editor" ] \
  && ok "editor: no keliver.portal.json is refused (no fixed fallback version)" || bad "editor: no keliver.portal.json (exit $rc): $out"
grep -q 'kotlin("multiplatform") version "2.2.0"' "$K1/editor/build.gradle.kts" && grep -q 'kotlin("plugin.compose") version "2.2.0"' "$K1/editor/build.gradle.kts" \
  && grep -q 'id("org.jetbrains.compose") version "1.8.2"' "$K1/editor/build.gradle.kts" \
  && ok "editor: a 0.4.1 app's editor builds on Kotlin 2.2.0 and Compose 1.8.2" || bad "editor: the 0.4.1 editor's toolchain: $(grep -E 'version "' "$K1/editor/build.gradle.kts" | tr -s ' ' | tr '\n' '|')"
# M1 (review of #299): the editor still writes the 0.4 line's Kotlin and Compose, so it refuses an app
# on any other line of keliver-runtime-line.sh's table (until the 0.5 editor ships), and a version
# past the newest line as the other scaffolders do; message checked, nothing written.
editor_refused() { # <label> <app version, or "env:<KELIVER_VERSION>"> <message fragment>
  local label="$1" v="$2" want="$3" app out rc
  app="$(KELIVER_VERSION=0.5.0 scaffold "Edref$((pass + fail))")"
  if [ "${v#env:}" != "$v" ]; then
    out="$( cd "$app" && KELIVER_VERSION="${v#env:}" "$ED" Edref 2>&1 )"; rc=$?
  else
    set_app_version "$app" "$v"
    out="$( cd "$app" && env -u KELIVER_VERSION "$ED" Edref 2>&1 )"; rc=$?
  fi
  [ "$rc" = 1 ] && [[ "$out" == *"$want"* ]] && [ ! -e "$app/editor" ] \
    && ok "editor: $label is refused, no editor/ written" || bad "editor: $label (exit $rc): $(printf '%s' "$out" | tail -1)"
}
editor_refused "an app on 0.5.0" 0.5.0 "Keliver 0.5.0 is on the 0.5 line; the editor supports 0.4 apps until the 0.5 editor ships"
editor_refused "an app on 0.5.0-SNAPSHOT" 0.5.0-SNAPSHOT "Keliver 0.5.0-SNAPSHOT is on the 0.5 line; the editor supports 0.4 apps until the 0.5 editor ships"
editor_refused "KELIVER_VERSION=0.5.3 over a 0.5.0 app" env:0.5.3 "the editor supports 0.4 apps until the 0.5 editor ships"
editor_refused "an app on 1.0.0 (past the newest line)" 1.0.0 "knows Keliver lines up to 0.5; Keliver 1.0.0 needs a newer tools bundle"
editor_refused "KELIVER_VERSION=1.0.0" env:1.0.0 "knows Keliver lines up to 0.5; Keliver 1.0.0 needs a newer tools bundle"

# --- 7b2. NF2: the Zipline line follows the Keliver version ------------------
# Before 0.5.0 upstream Zipline; from 0.5.0 (a pre-release too) Keliver's Zipline fork, with the
# guest's Long as a JavaScript BigInt; never both in one app.
nameds() { grep -cE "['\"]$2['\":]" "$1" 2>/dev/null; }
grep -q "id 'app.cash.zipline' version '1.22.0'" "$A/settings.gradle" && grep -q "implementation 'app.cash.zipline:zipline:1.22.0'" "$A/build.gradle" \
  && [ "$(nameds "$A/settings.gradle" 'dev\.keliver\.zipline')$(nameds "$A/build.gradle" 'dev\.keliver\.zipline')" = 00 ] \
  && ! grep -q -- '-Xes-long-as-bigint' "$A/build.gradle" \
  && ok "NF2: an app on 0.4.1 keeps upstream Zipline 1.22.0, without Long as BigInt" || bad "NF2: the 0.4.1 app's Zipline changed"
# A version past the newest line this bundle knows (keliver-runtime-line.sh) is refused, not given
# the 0.5 line's toolchain (M1, review of #299); keliver-init refuses it the same way.
F="$(KELIVER_VERSION=0.5.0 scaffold Past)"; set_app_version "$F" 1.0.0; before="$(fingerprint "$F")"
out="$( cd "$F" && env -u KELIVER_VERSION "$DEV" 2>&1 )"; rc=$?
[ "$rc" != 0 ] && printf '%s' "$out" | grep -q "knows Keliver lines up to 0.5; Keliver 1.0.0 needs a newer tools bundle" && [ "$(fingerprint "$F")" = "$before" ] \
  && ok "NF2: an app on 1.0.0 (past the newest line) is refused, byte-identical" || bad "NF2: an app on 1.0.0 (exit $rc): $(printf '%s' "$out" | tail -1)"
for v in 0.5.0 0.5.0-SNAPSHOT 0.5.3; do
  P="$(KELIVER_VERSION=$v scaffold "Fork${v//[.-]/}")"
  ( cd "$P" && env -u KELIVER_VERSION "$DEV" >/dev/null 2>&1 ); rc=$?
  if [ "$rc" = 0 ] && grep -q "id 'dev.keliver.zipline' version '1.28.0-keliver.1'" "$P/settings.gradle" \
    && grep -q "^  id 'dev.keliver.zipline'$" "$P/build.gradle" \
    && grep -q "implementation 'dev.keliver.zipline:zipline:1.28.0-keliver.1'" "$P/build.gradle" \
    && [ "$(nameds "$P/settings.gradle" 'app\.cash\.zipline')$(nameds "$P/build.gradle" 'app\.cash\.zipline')" = 00 ] \
    && [ "$(grep -c "compilerOptions.freeCompilerArgs.add('-Xes-long-as-bigint')" "$P/build.gradle")" = 1 ] \
    && python3 -c 'import re,sys; b=open(sys.argv[1]).read(); m=re.search(r"\n(\s*)js \{\n(.*?)\n\1\}", b, re.S); sys.exit(0 if m and "binaries.executable()" in m.group(2) and "-Xes-long-as-bigint" in m.group(2) else 1)' "$P/build.gradle" \
    && grep -q "id 'org.jetbrains.kotlin.multiplatform' version '2.4.21'" "$P/settings.gradle"; then
    ok "NF2: an app on $v gets Keliver's Zipline fork (plugin, library) and Long as BigInt in its js block, Kotlin 2.4.21"
  else
    bad "NF2: an app on $v (exit $rc): $(grep -hE "zipline|bigint|kotlin.multiplatform" "$P/settings.gradle" "$P/build.gradle" | tr -s ' ' | tr '\n' '|')"
  fi
done
# An app already on the other line's Zipline is refused, byte-identical.
M1="$(KELIVER_VERSION=0.5.0 scaffold MixUp)"
python3 -c 'import re,sys
p=sys.argv[1]; s=open(p).read()
s=re.sub(r"(\n(\s*)id .org\.jetbrains\.compose. version .[^\x27]+.)", "\\1\n\\2id \x27app.cash.zipline\x27 version \x271.22.0\x27", s, count=1)
open(p,"w").write(s)' "$M1/settings.gradle"
before="$(fingerprint "$M1")"
( cd "$M1" && env -u KELIVER_VERSION "$DEV" >/dev/null 2>&1 ); rc=$?
chk "NF2: an app on 0.5.0 whose settings name app.cash.zipline is refused" 3 "$rc"
[ "$(fingerprint "$M1")" = "$before" ] && ok "NF2: the refused 0.5.0 app is byte-identical" || bad "NF2: the refused 0.5.0 app was changed"
M2="$(scaffold MixDown)"
printf "\n// dependencies { implementation 'dev.keliver.zipline:zipline:1.28.0-keliver.1' }\n" >> "$M2/build.gradle"
before="$(fingerprint "$M2")"
( cd "$M2" && env -u KELIVER_VERSION "$DEV" >/dev/null 2>&1 ); rc=$?
chk "NF2: an app on 0.4.1 whose build names dev.keliver.zipline is refused" 3 "$rc"
[ "$(fingerprint "$M2")" = "$before" ] && ok "NF2: the refused 0.4.1 app is byte-identical" || bad "NF2: the refused 0.4.1 app was changed"
# A fork-line app whose js block is already an executable gets the flag once, under it.
E5="$(KELIVER_VERSION=0.5.0 scaffold Already)"
python3 -c 'import re,sys
p=sys.argv[1]; s=open(p).read()
s=re.sub(r"\n(\s*)js \{ browser\(\) \}", "\n\\1js {\n\\1  browser()\n\\1  binaries.executable()\n\\1}", s, count=1)
open(p,"w").write(s)' "$E5/build.gradle"
( cd "$E5" && env -u KELIVER_VERSION "$DEV" >/dev/null 2>&1 ); rc=$?
[ "$rc" = 0 ] && [ "$(grep -c -- "-Xes-long-as-bigint')" "$E5/build.gradle")" = 1 ] \
  && grep -A2 'binaries.executable()' "$E5/build.gradle" | grep -q -- '-Xes-long-as-bigint' \
  && ok "NF2: an executable js block on 0.5.0 gets Long as BigInt once, under binaries.executable()" || bad "NF2: the executable js block on 0.5.0 (exit $rc)"
# The class names in the signing block (app.cash.zipline.gradle....) are not a Zipline coordinate.
S1="$(KELIVER_VERSION=0.5.0 scaffold Signed)"
printf '\ntasks.withType(app.cash.zipline.gradle.ZiplineCompileTask).configureEach { }\n' >> "$S1/build.gradle"
( cd "$S1" && env -u KELIVER_VERSION "$DEV" >/dev/null 2>&1 ); rc=$?
chk "NF2: app.cash.zipline class names (the signing block) on 0.5.0 are not refused" 0 "$rc"

# --- 7c. the tools bundle's layout (bin/ beside templates/), a symlink, no template
KP="$WORK/bundle"; mkdir -p "$KP/bin" "$KP/templates" "$WORK/links"
cp "$DEV" "$ROOT/scripts/keliver-runtime-line.sh" "$KP/bin/"; cp -R "$ROOT/scripts/templates/guest" "$KP/templates/"
ln -s "$KP/bin/keliver-new-device-target.sh" "$WORK/links/kndt"
L1="$(scaffold Linked)"
( cd "$L1" && "$WORK/links/kndt" >/dev/null 2>&1 ); rc=$?
chk "the bundle layout, through a symlink" 0 "$rc"
grep -q '^package linked\.device$' "$L1/src/jsMain/kotlin/device/KeliverTiming.kt" 2>/dev/null \
  && ok "the bundle layout: KeliverTiming.kt from templates/guest" || bad "the bundle layout: no KeliverTiming.kt"
rm -r "$KP/templates/guest"
N="$(scaffold Notmpl)"; before="$(fingerprint "$N")"
( cd "$N" && "$KP/bin/keliver-new-device-target.sh" >/dev/null 2>&1 ); rc=$?
chk "no templates/guest is refused" 1 "$rc"
[ "$(fingerprint "$N")" = "$before" ] && ok "no templates/guest: app unchanged" || bad "no templates/guest: the app was changed"
cp -R "$ROOT/scripts/templates/guest" "$KP/templates/"; rm "$KP/bin/keliver-runtime-line.sh"
N2="$(scaffold Noline)"; before="$(fingerprint "$N2")"
( cd "$N2" && "$KP/bin/keliver-new-device-target.sh" >/dev/null 2>&1 ); rc=$?
chk "NF2: no keliver-runtime-line.sh beside it is refused" 1 "$rc"
[ "$(fingerprint "$N2")" = "$before" ] && ok "NF2: no keliver-runtime-line.sh: app unchanged" || bad "NF2: no keliver-runtime-line.sh: the app was changed"
cp "$ROOT/scripts/keliver-new-editor.sh" "$KP/bin/"
out="$( cd "$N2" && env -u KELIVER_VERSION "$KP/bin/keliver-new-editor.sh" Noline 2>&1 )"; rc=$?
[ "$rc" = 1 ] && [[ "$out" == *"keliver-runtime-line.sh is missing beside this script"* ]] && [ ! -e "$N2/editor" ] \
  && ok "editor: no keliver-runtime-line.sh beside it is refused, no editor/ written" || bad "editor: no keliver-runtime-line.sh beside it (exit $rc): $(printf '%s' "$out" | tail -1)"

# --- 8. the generated output actually compiles -------------------------------
if [ $DO_COMPILE = 1 ]; then
  compile_app "$A"; chk "single-screen generated output COMPILES" 0 "$?"
  compile_app "$B"; chk "two-screen generated output COMPILES" 0 "$?"
else
  echo "  SKIP  compile checks (pass --compile)"
fi

echo
echo "passed: $pass   failed: $fail"
echo "artifacts: $WORK"
[ "$fail" -eq 0 ]
