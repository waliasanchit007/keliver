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
KELIVER_VERSION="${KELIVER_VERSION:-0.3.3}"
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
