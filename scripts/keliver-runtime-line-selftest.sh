#!/usr/bin/env bash
#
# keliver-runtime-line-selftest — keliver-runtime-line.sh's one table of versions per line: each
# Keliver version gets its line's column, the 0.4 line's Gradle is exactly
# templates/app-wrapper/wrapper.properties (so a 0.4 app's wrapper is byte-identical to before), and
# the wrapper writers (keliver-app-wrapper.sh, keliver-init) give a 0.5 app the 0.5 line's Gradle.
#
#   scripts/keliver-runtime-line-selftest.sh <disposable-parent>
#
# Shell only, no network, no Gradle: a few seconds.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
PARENT="${1:?usage: $0 <disposable-parent>}"
mkdir -p "$PARENT"
RUN="$(mktemp -d "$(cd "$PARENT" && pwd -P)/keliver-runtime-line-XXXXXX")"
pass=0; fail=0
ok(){ echo "  PASS  $1"; pass=$((pass + 1)); }
bad(){ echo "  FAIL  $1"; fail=$((fail + 1)); }
TEMPLATE="$ROOT/scripts/templates/app-wrapper/wrapper.properties"
# shellcheck source=keliver-runtime-line.sh
. "$ROOT/scripts/keliver-runtime-line.sh"

echo "=== the table"
rows=0; malformed=""
while read -r key old new extra; do
  [ -n "$key" ] || continue
  rows=$((rows + 1))
  { [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] && [ -n "$old" ] && [ -n "$new" ] && [ -z "$extra" ]; } || malformed="$malformed $key"
done <<< "$KELIVER_TOOLCHAIN"
[ "$rows" -ge 12 ] && [ -z "$malformed" ] && ok "$rows keys, each with one value per line" || bad "the table: $rows rows, malformed:$malformed"

expect() { # <label> <version> <KEY=value>...
  local label="$1" v="$2" kv k want got wrong=""; shift 2
  unset TOOLCHAIN_AGP KELIVER_ZIPLINE_FORK
  keliver_zipline_line "$v" || { bad "$label: $v is refused"; return; }
  for kv in "$@"; do
    k="${kv%%=*}"; want="${kv#*=}"; got="$(eval "printf '%s' \"\${$k:-}\"")"
    [ "$got" = "$want" ] || wrong="$wrong $k=$got (want $want)"
  done
  [ -z "$wrong" ] && ok "$label" || bad "$label:$wrong"
}
OLD=(KELIVER_ZIPLINE_FORK=false ZIPLINE_GROUP=app.cash.zipline TOOLCHAIN_ZIPLINE=1.22.0 TOOLCHAIN_KOTLIN=2.2.0
  TOOLCHAIN_COMPOSE=1.8.2 TOOLCHAIN_AGP=8.12.0 TOOLCHAIN_COMPILE_SDK=35 TOOLCHAIN_TARGET_SDK=35 TOOLCHAIN_JDK=17
  TOOLCHAIN_GRADLE=9.0.0 TOOLCHAIN_GRADLE_MIN=8.13 TOOLCHAIN_AGP_MIN=-)
OLD+=(TOOLCHAIN_COROUTINES=1.10.2 TOOLCHAIN_SERIALIZATION=1.9.0 TOOLCHAIN_OKHTTP=5.1.0 TOOLCHAIN_COIL=3.3.0 TOOLCHAIN_KTOR=3.1.0)
NEW=(KELIVER_ZIPLINE_FORK=true ZIPLINE_GROUP=dev.keliver.zipline TOOLCHAIN_ZIPLINE=1.28.0-keliver.1 TOOLCHAIN_KOTLIN=2.4.20
  TOOLCHAIN_COMPOSE=1.12.2 TOOLCHAIN_AGP=9.3.3 TOOLCHAIN_AGP_MIN=9.1.0 TOOLCHAIN_COMPILE_SDK=37 TOOLCHAIN_TARGET_SDK=36
  TOOLCHAIN_JDK=17 TOOLCHAIN_GRADLE=9.7.1 TOOLCHAIN_GRADLE_MIN=9.5.0
  TOOLCHAIN_GRADLE_SHA256=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a
  TOOLCHAIN_COROUTINES=1.11.0 TOOLCHAIN_SERIALIZATION=1.13.0 TOOLCHAIN_OKHTTP=5.5.1 TOOLCHAIN_COIL=- TOOLCHAIN_KTOR=-)
for v in 0.4.1 0.3.18 0.4.99-SNAPSHOT; do expect "$v: the 0.4 line (upstream Zipline, AGP 8.12.0, compileSdk 35, Gradle 9.0.0, Coil 3.3.0)" "$v" "${OLD[@]}"; done
for v in 0.5.0 0.5.0-SNAPSHOT 0.5.1 1.0.0; do expect "$v: the 0.5 line (the fork, AGP 9.3.3, compileSdk 37, Gradle 9.7.1, Coil from keliver-image)" "$v" "${NEW[@]}"; done
unset TOOLCHAIN_AGP
keliver_zipline_line "0.5" && bad "a version without a patch is accepted" || { [ -z "${TOOLCHAIN_AGP:-}" ] && ok "a version without a patch is refused, and nothing is set" || bad "a refused version set TOOLCHAIN_AGP"; }

# The table's 0.4 Gradle is the template's: the two cannot drift apart.
keliver_zipline_line 0.4.1
grep -qx "distributionUrl=https\\\\://services.gradle.org/distributions/gradle-$TOOLCHAIN_GRADLE-bin.zip" "$TEMPLATE" \
  && grep -qx "distributionSha256Sum=$TOOLCHAIN_GRADLE_SHA256" "$TEMPLATE" \
  && ok "the 0.4 line's Gradle and sha256 are templates/app-wrapper/wrapper.properties'" || bad "the 0.4 line's Gradle is not the template's"

# The newest column is this checkout's own toolchain (what its libraries are built with): it moves
# with gradle/libs.versions.toml and the Gradle wrapper, or this fails. Only in a checkout (the tools
# bundle carries neither file).
CATALOG="$ROOT/gradle/libs.versions.toml"; WRAPPER="$ROOT/gradle/wrapper/gradle-wrapper.properties"
if [ -f "$CATALOG" ] && [ -f "$WRAPPER" ]; then
  cat_version() { sed -nE "s/^$1 = \"([^\"]+)\".*/\\1/p" "$CATALOG" | head -1; }
  cat_library() { sed -nE "s/^$1 = \\{ module = \"[^\"]+\", version = \"([^\"]+)\" \\}.*/\\1/p" "$CATALOG" | head -1; }
  keliver_zipline_line 99.0.0
  drift=""
  for pair in "KOTLIN=$(cat_version kotlin)" "COMPOSE=$(cat_version jbCompose)" \
      "COROUTINES=$(cat_version kotlinx-coroutines)" "SERIALIZATION=$(cat_version kotlinx-serialization)" \
      "ZIPLINE=$(cat_version zipline)" "OKHTTP=$(cat_library okhttp)" "AGP=$(cat_library androidGradlePlugin)" \
      "GRADLE=$(sed -nE 's#^distributionUrl=.*/gradle-([0-9.]+)-bin\.zip$#\1#p' "$WRAPPER")" \
      "GRADLE_SHA256=$(sed -nE 's/^distributionSha256Sum=//p' "$WRAPPER")"; do
    k="${pair%%=*}"; want="${pair#*=}"; got="$(eval "printf '%s' \"\${TOOLCHAIN_$k:-}\"")"
    { [ -n "$want" ] && [ "$got" = "$want" ]; } || drift="$drift $k=$got (checkout: ${want:-unread})"
  done
  [ -z "$drift" ] && ok "the newest line's toolchain is this checkout's catalog and wrapper" || bad "the newest line drifted from this checkout:$drift"
  # COIL/KTOR '-' means keliver-image brings them: it must, on each platform the hosts build.
  IMG="$ROOT/keliver-image/build.gradle"
  missing=""
  for dep in coil.compose.full coil.core okio coil.network.okhttp coil.network.ktor3 ktor.client.darwin; do
    grep -qE "^[[:space:]]*api libs\\.${dep//./\\.}\$" "$IMG" || missing="$missing $dep"
  done
  [ -z "$missing" ] && ok "keliver-image exports Coil, okio, the OkHttp fetcher and the Ktor fetcher with the Darwin engine" || bad "keliver-image does not export:$missing"
else
  echo "  (no catalog here: the newest line's checkout comparison is skipped)"
fi

echo "=== keliver_line_image_dependencies"
keliver_zipline_line 0.4.1
[ "$(keliver_line_image_dependencies android 0.4.1)" = "implementation 'io.coil-kt.coil3:coil-compose-core:3.3.0'
implementation 'io.coil-kt.coil3:coil-network-okhttp:3.3.0'" ] && ok "0.4 line, Android: Coil 3.3.0 pinned, as the templates were" || bad "0.4 line, Android: $(keliver_line_image_dependencies android 0.4.1)"
[ "$(keliver_line_image_dependencies ios 0.4.1)" = "implementation 'io.coil-kt.coil3:coil-compose-core:3.3.0'
implementation 'io.coil-kt.coil3:coil-network-ktor3:3.3.0' // images over the network
implementation 'io.ktor:ktor-client-darwin:3.1.0'           // coil's network engine on iOS" ] && ok "0.4 line, iOS: Coil 3.3.0 and Ktor 3.1.0 pinned, as the templates were" || bad "0.4 line, iOS: $(keliver_line_image_dependencies ios 0.4.1)"
keliver_zipline_line 0.5.0-SNAPSHOT
a="$(keliver_line_image_dependencies android 0.5.0-SNAPSHOT)"; i="$(keliver_line_image_dependencies ios 0.5.0-SNAPSHOT)"
{ [[ "$a" == "implementation 'dev.keliver:keliver-image:0.5.0-SNAPSHOT' //"* ]] && [[ "$i" == "implementation 'dev.keliver:keliver-image:0.5.0-SNAPSHOT' //"* ]] \
  && [ "$(printf '%s\n%s\n' "$a" "$i" | wc -l | tr -d ' ')" = 2 ] && ! printf '%s%s' "$a" "$i" | grep -qE 'coil-kt|io\.ktor'; } \
  && ok "0.5 line: one keliver-image line at the app's version on each platform, no Coil or Ktor pin" || bad "0.5 line: $a / $i"
keliver_line_image_dependencies web 0.5.0 > /dev/null && bad "an unknown platform is accepted" || ok "an unknown platform is refused"

echo "=== keliver_version_at_least"
cmpv() { # <have> <want> <0|1>
  keliver_version_at_least "$1" "$2"; local rc=$?
  [ "$rc" = "$3" ] || bad "keliver_version_at_least $1 $2 returned $rc (want $3)"
}
before=$fail
cmpv 9.7.0 9.5.0 0; cmpv 9.5.0 9.5.0 0; cmpv 9.0.0 9.5.0 1; cmpv 8.13 9.5.0 1; cmpv 9.0.0 8.13 0
cmpv 8.13 8.13 0; cmpv 8.12.1 8.13 1; cmpv 10.0 9.5.0 0; cmpv 9.10.0 9.5.0 0; cmpv 9.7.0-rc-1 9.5.0 0
cmpv x 9.5.0 1
[ "$fail" = "$before" ] && ok "11 comparisons (numeric fields, 9.10 > 9.5, a missing field is 0, junk is not newer)"

echo "=== the wrapper, per line"
W="$RUN/w"; mkdir -p "$W"
cp "$TEMPLATE" "$W/a.properties"; keliver_zipline_line 0.4.1; keliver_line_wrapper_properties "$W/a.properties"
cmp -s "$TEMPLATE" "$W/a.properties" && ok "0.4 line: the template's properties stay byte-identical" || bad "0.4 line: the properties changed"
cp "$TEMPLATE" "$W/b.properties"; keliver_zipline_line 0.5.0; keliver_line_wrapper_properties "$W/b.properties"
diff <(grep -Ev '^distribution(Url|Sha256Sum)=' "$TEMPLATE") <(grep -Ev '^distribution(Url|Sha256Sum)=' "$W/b.properties") > /dev/null \
  && grep -qx 'distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip' "$W/b.properties" \
  && grep -qx 'distributionSha256Sum=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a' "$W/b.properties" \
  && ok "0.5 line: Gradle 9.7.1 and its sha256, every other line as the template's" || bad "0.5 line: $(grep distribution "$W/b.properties" | tr '\n' ' ')"
( unset TOOLCHAIN_GRADLE; keliver_line_wrapper_properties "$W/b.properties" ) && bad "the writer ran without a line" || ok "the writer refuses before keliver_zipline_line"

bash "$ROOT/scripts/keliver-app-wrapper.sh" "$RUN/aw-none" > /dev/null 2>&1 \
  && cmp -s "$TEMPLATE" "$RUN/aw-none/gradle/wrapper/gradle-wrapper.properties" \
  && ok "keliver-app-wrapper.sh without a version: the template (what build-portal-tools.sh stages)" || bad "keliver-app-wrapper.sh without a version"
bash "$ROOT/scripts/keliver-app-wrapper.sh" "$RUN/aw-04" 0.4.1 > /dev/null 2>&1 \
  && cmp -s "$TEMPLATE" "$RUN/aw-04/gradle/wrapper/gradle-wrapper.properties" \
  && ok "keliver-app-wrapper.sh 0.4.1: the template" || bad "keliver-app-wrapper.sh 0.4.1"
bash "$ROOT/scripts/keliver-app-wrapper.sh" "$RUN/aw-05" 0.5.0-SNAPSHOT > /dev/null 2>&1 \
  && cmp -s "$W/b.properties" "$RUN/aw-05/gradle/wrapper/gradle-wrapper.properties" \
  && ok "keliver-app-wrapper.sh 0.5.0-SNAPSHOT: the 0.5 line's Gradle" || bad "keliver-app-wrapper.sh 0.5.0-SNAPSHOT"
bash "$ROOT/scripts/keliver-app-wrapper.sh" "$RUN/aw-bad" 'x' > /dev/null 2>&1 && bad "keliver-app-wrapper.sh accepted 'x'" || ok "keliver-app-wrapper.sh refuses a version that is not one"

echo "=== keliver-init writes its line's wrapper"
for v in 0.4.1 0.5.0; do
  ( cd "$RUN" && KELIVER_VERSION="$v" bash "$ROOT/scripts/keliver-init" "Demo${v//./}" "init-$v" > "$RUN/init-$v.log" 2>&1 ) \
    || bad "keliver-init on $v failed: $(tail -1 "$RUN/init-$v.log")"
done
cmp -s "$TEMPLATE" "$RUN/init-0.4.1/gradle/wrapper/gradle-wrapper.properties" \
  && ok "keliver-init 0.4.1: the template's wrapper, byte-identical" || bad "keliver-init 0.4.1's wrapper"
cmp -s "$W/b.properties" "$RUN/init-0.5.0/gradle/wrapper/gradle-wrapper.properties" \
  && grep -q "id 'org.jetbrains.kotlin.multiplatform' version '2.4.20'" "$RUN/init-0.5.0/settings.gradle" \
  && ok "keliver-init 0.5.0: Gradle 9.7.1 (the 0.5 line's AGP needs 9.5.0+) and Kotlin 2.4.20" || bad "keliver-init 0.5.0's wrapper or Kotlin"

echo
echo "runtime line: $pass passed, $fail failed   ($RUN)"
[ "$fail" -eq 0 ]
