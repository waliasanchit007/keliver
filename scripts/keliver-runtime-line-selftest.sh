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
[ "$KELIVER_LINES" = "0.4 0.5" ] && ok "the lines, oldest first: $KELIVER_LINES" || bad "the lines: '$KELIVER_LINES' (want '0.4 0.5')"
read -r -a LINES <<< "$KELIVER_LINES"
rows=0; malformed=""; sorted=true
for i in "${!LINES[@]}"; do
  [[ "${LINES[$i]}" =~ ^[0-9]+\.[0-9]+$ ]] || malformed="$malformed line:${LINES[$i]}"
  [ "$i" -eq 0 ] || keliver_version_at_least "${LINES[$i]}" "${LINES[$((i - 1))]}.1" || sorted=false
done
while read -r -a fields; do
  [ "${#fields[@]}" -gt 0 ] || continue
  rows=$((rows + 1))
  { [[ "${fields[0]}" =~ ^[A-Z][A-Z0-9_]*$ ]] && [ "${#fields[@]}" -eq $((${#LINES[@]} + 1)) ]; } || malformed="$malformed ${fields[0]}"
done <<< "$KELIVER_TOOLCHAIN"
[ "$rows" -ge 14 ] && [ -z "$malformed" ] && $sorted && ok "$rows rows (LINE and one per key), each with one value per line, lines ascending" \
  || bad "the table: $rows rows, sorted=$sorted, malformed:$malformed"

expect() { # <label> <version> <KEY=value>...
  local label="$1" v="$2" kv k want got wrong="" err; shift 2
  unset TOOLCHAIN_AGP KELIVER_ZIPLINE_FORK KELIVER_LINE
  err="$RUN/expect.err"
  keliver_zipline_line "$v" 2> "$err" || { bad "$label: $v is refused ($KELIVER_LINE_ERROR)"; return; }
  [ -s "$err" ] && wrong=" a warning: $(head -1 "$err")"
  for kv in "$@"; do
    k="${kv%%=*}"; want="${kv#*=}"; got="$(eval "printf '%s' \"\${$k:-}\"")"
    [ "$got" = "$want" ] || wrong="$wrong $k=$got (want $want)"
  done
  [ -z "$wrong" ] && ok "$label" || bad "$label:$wrong"
}
OLD=(KELIVER_LINE=0.4 KELIVER_ZIPLINE_FORK=false ZIPLINE_GROUP=app.cash.zipline ZIPLINE_PLUGIN_ID=app.cash.zipline TOOLCHAIN_ZIPLINE=1.22.0 TOOLCHAIN_KOTLIN=2.2.0
  TOOLCHAIN_COMPOSE=1.8.2 TOOLCHAIN_AGP=8.12.0 TOOLCHAIN_COMPILE_SDK=35 TOOLCHAIN_TARGET_SDK=35 TOOLCHAIN_JDK=17
  TOOLCHAIN_GRADLE=9.0.0 TOOLCHAIN_GRADLE_MIN=8.13 TOOLCHAIN_AGP_MIN=-)
OLD+=(TOOLCHAIN_COROUTINES=1.10.2 TOOLCHAIN_SERIALIZATION=1.9.0 TOOLCHAIN_OKHTTP=5.1.0 TOOLCHAIN_COIL=3.3.0 TOOLCHAIN_KTOR=3.1.0)
NEW=(KELIVER_LINE=0.5 KELIVER_ZIPLINE_FORK=true ZIPLINE_GROUP=dev.keliver.zipline ZIPLINE_PLUGIN_ID=dev.keliver.zipline TOOLCHAIN_ZIPLINE=1.28.0-keliver.1 TOOLCHAIN_KOTLIN=2.4.21
  TOOLCHAIN_COMPOSE=1.12.2 TOOLCHAIN_AGP=9.3.3 TOOLCHAIN_AGP_MIN=9.1.0 TOOLCHAIN_COMPILE_SDK=37 TOOLCHAIN_TARGET_SDK=36
  TOOLCHAIN_JDK=17 TOOLCHAIN_GRADLE=9.7.1 TOOLCHAIN_GRADLE_MIN=9.5.0
  TOOLCHAIN_GRADLE_SHA256=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a
  TOOLCHAIN_COROUTINES=1.11.0 TOOLCHAIN_SERIALIZATION=1.13.0 TOOLCHAIN_OKHTTP=5.5.1 TOOLCHAIN_COIL=- TOOLCHAIN_KTOR=-)
for v in 0.4.1 0.3.18 0.4.99-SNAPSHOT 0.0.1; do expect "$v: the 0.4 line (upstream Zipline, AGP 8.12.0, compileSdk 35, Gradle 9.0.0, Coil 3.3.0)" "$v" "${OLD[@]}"; done
for v in 0.5.0 0.5.0-SNAPSHOT 0.5.0-rc1 0.5.1 0.5.99; do expect "$v: the 0.5 line (the fork, AGP 9.3.3, compileSdk 37, Gradle 9.7.1, Coil from keliver-image)" "$v" "${NEW[@]}"; done

refused() { # <label> <version> <message fragment>
  local label="$1" v="$2" want="$3"
  unset TOOLCHAIN_AGP KELIVER_LINE KELIVER_ZIPLINE_FORK ZIPLINE_GROUP
  if keliver_zipline_line "$v" 2> "$RUN/refused.err"; then
    bad "$label: $v is accepted (line ${KELIVER_LINE:-?})"
  elif [ -n "${TOOLCHAIN_AGP:-}${KELIVER_LINE:-}${KELIVER_ZIPLINE_FORK:-}${ZIPLINE_GROUP:-}" ]; then
    bad "$label: refused, but set TOOLCHAIN_AGP=${TOOLCHAIN_AGP:-} KELIVER_LINE=${KELIVER_LINE:-}"
  elif [[ "$KELIVER_LINE_ERROR" != *"$want"* ]]; then
    bad "$label: the message is '$KELIVER_LINE_ERROR' (want '$want')"
  else
    ok "$label: refused, nothing set (\"$KELIVER_LINE_ERROR\")"
  fi
}
refused "a version without a patch" 0.5 "does not start with <major>.<minor>.<patch>"
refused "not a version" x "does not start with"
# M1 (review of #299): a version past the newest line is not given the newest line's toolchain.
PAST="this tools bundle knows Keliver lines up to 0.5; Keliver"
refused "1.0.0, past the newest line" 1.0.0 "$PAST 1.0.0 needs a newer tools bundle"
refused "0.6.0, the next line's release" 0.6.0 "$PAST 0.6.0 needs a newer tools bundle"
refused "0.6.0-rc1, the next line's pre-release (not a SNAPSHOT)" 0.6.0-rc1 "$PAST 0.6.0-rc1"
refused "0.7.0-SNAPSHOT, two lines ahead" 0.7.0-SNAPSHOT "$PAST 0.7.0-SNAPSHOT"
refused "1.0.0-SNAPSHOT, the next major" 1.0.0-SNAPSHOT "$PAST 1.0.0-SNAPSHOT"
# The one exception: a SNAPSHOT of the next minor (a checkout built before its line is added)
# takes the newest line, and says so on stderr.
unset TOOLCHAIN_AGP KELIVER_LINE
if keliver_zipline_line 0.6.0-SNAPSHOT 2> "$RUN/snapshot.err"; then
  grep -qx "warning: Keliver 0.6.0-SNAPSHOT is past the newest line this tools bundle knows (0.5): it gets the 0.5 line's toolchain. Scaffold again with a tools bundle that knows 0.6 before releasing it." "$RUN/snapshot.err" \
    && [ "$KELIVER_LINE" = 0.5 ] && [ "$TOOLCHAIN_AGP" = 9.3.3 ] && [ "$KELIVER_LINE_WARNING" = "$(cat "$RUN/snapshot.err")" ] \
    && ok "0.6.0-SNAPSHOT: the 0.5 line, with a warning on stderr" || bad "0.6.0-SNAPSHOT: line ${KELIVER_LINE:-?}, stderr: $(cat "$RUN/snapshot.err")"
else
  bad "0.6.0-SNAPSHOT is refused: $KELIVER_LINE_ERROR"
fi
out="$(KELIVER_VERSION=0.6.1-SNAPSHOT bash -c '. "$1"; keliver_zipline_line 0.6.1-SNAPSHOT' _ "$ROOT/scripts/keliver-runtime-line.sh" 2>&1 >/dev/null)"
[[ "$out" == "warning: Keliver 0.6.1-SNAPSHOT is past the newest line"* ]] && ok "a later SNAPSHOT of the next minor (0.6.1-SNAPSHOT) also passes with the warning" || bad "0.6.1-SNAPSHOT: $out"
# The scaffolders print the refusal and stop.
out="$(bash "$ROOT/scripts/keliver-app-wrapper.sh" "$RUN/aw-past" 1.0.0 2>&1)"; rc=$?
[ "$rc" != 0 ] && [[ "$out" == *"knows Keliver lines up to 0.5; Keliver 1.0.0 needs a newer tools bundle"* ]] && [ ! -e "$RUN/aw-past/gradle/wrapper/gradle-wrapper.properties" ] \
  && ok "keliver-app-wrapper.sh 1.0.0: refused with the line message" || bad "keliver-app-wrapper.sh 1.0.0 (rc=$rc): $out"

# Lines are general: a third column is picked by <major>.<minor>, and the past-the-newest rule
# follows the newest (a copy of the table with an invented 0.6 column).
(
  KELIVER_TOOLCHAIN="$(sed -E -e 's/^(LINE[[:space:]].*)$/\1 0.6/' -e 's/^(ZIPLINE_GROUP[[:space:]].*)$/\1 dev.keliver.zipline/' -e 't' \
    -e 's/^([A-Z][A-Z0-9_]*[[:space:]].*[^[:space:]])$/\1 x6/' <<< "$KELIVER_TOOLCHAIN")"
  KELIVER_LINES="0.4 0.5 0.6"
  r=""
  keliver_zipline_line 0.6.2 2>/dev/null && [ "$KELIVER_LINE" = 0.6 ] && [ "$TOOLCHAIN_AGP" = x6 ] || r="$r 0.6.2"
  keliver_zipline_line 0.5.3 2>/dev/null && [ "$KELIVER_LINE" = 0.5 ] && [ "$TOOLCHAIN_AGP" = 9.3.3 ] || r="$r 0.5.3"
  keliver_zipline_line 0.3.0 2>/dev/null && [ "$KELIVER_LINE" = 0.4 ] && [ "$TOOLCHAIN_AGP" = 8.12.0 ] || r="$r 0.3.0"
  keliver_zipline_line 0.7.0-SNAPSHOT 2>/dev/null && [ "$KELIVER_LINE" = 0.6 ] || r="$r 0.7.0-SNAPSHOT"
  keliver_zipline_line 0.7.0 2>/dev/null && r="$r 0.7.0(accepted)"
  [ -z "$r" ]
) && ok "a three-line table: 0.6.2 -> 0.6, 0.5.3 -> 0.5, 0.3.0 -> 0.4, 0.7.0-SNAPSHOT -> 0.6, 0.7.0 refused" || bad "a three-line table picks the wrong lines"

# The table's 0.4 Gradle is the template's: the two cannot drift apart.
keliver_zipline_line 0.4.1
grep -qx "distributionUrl=https\\\\://services.gradle.org/distributions/gradle-$TOOLCHAIN_GRADLE-bin.zip" "$TEMPLATE" \
  && grep -qx "distributionSha256Sum=$TOOLCHAIN_GRADLE_SHA256" "$TEMPLATE" \
  && ok "the 0.4 line's Gradle and sha256 are templates/app-wrapper/wrapper.properties'" || bad "the 0.4 line's Gradle is not the template's"

# The column of the version this checkout builds next is this checkout's own toolchain (what its
# libraries are built with): it moves with gradle/libs.versions.toml and the Gradle wrapper, or this
# fails. That version is CHANGELOG.md's "## Unreleased (X.Y.Z)" (KELIVER_VERSION in
# RedwoodBuildPlugin.kt is the last release until the release bumps it), else KELIVER_VERSION. Its
# line must be one the table names (no SNAPSHOT warning): a toolchain bump for the next minor adds
# that line's column and leaves released columns alone. Only in a checkout (the tools bundle carries
# none of these files).
CATALOG="$ROOT/gradle/libs.versions.toml"; WRAPPER="$ROOT/gradle/wrapper/gradle-wrapper.properties"
if [ -f "$CATALOG" ] && [ -f "$WRAPPER" ]; then
  cat_version() { sed -nE "s/^$1 = \"([^\"]+)\".*/\\1/p" "$CATALOG" | head -1; }
  cat_library() { sed -nE "s/^$1 = \\{ module = \"[^\"]+\", version = \"([^\"]+)\" \\}.*/\\1/p" "$CATALOG" | head -1; }
  next_line_guard() { # <CHANGELOG.md>
  NEXT="$(sed -nE 's/^## Unreleased \(([0-9]+\.[0-9]+\.[0-9]+[A-Za-z0-9.+-]*)\)[[:space:]]*$/\1/p' "$1" 2>/dev/null | head -1)"
  NEXT_FROM="CHANGELOG.md's Unreleased heading"
  # Said in every failure below when the heading is not there: the fallback is the LAST release, so a
  # drift reported against its line may only mean the heading is missing (RELEASING.md, step 2).
  NO_HEADING=""
  if [ -z "$NEXT" ]; then
    NEXT="$(sed -nE 's/^private const val KELIVER_VERSION = "([^"]+)".*/\1/p' "$ROOT/build-support/src/main/kotlin/dev/keliver/buildsupport/RedwoodBuildPlugin.kt" 2>/dev/null | head -1)"
    NEXT_FROM="KELIVER_VERSION in RedwoodBuildPlugin.kt"
    NO_HEADING=" NOTE: CHANGELOG.md has no '## Unreleased (X.Y.Z)' heading this check can read (missing, or not worded exactly so), so it fell back to KELIVER_VERSION ${NEXT:-(unread)}, the last release. If the next release is on another line, restore the heading naming it (RELEASING.md, step 2) before changing the table."
    echo "  note: no '## Unreleased (X.Y.Z)' heading in CHANGELOG.md; comparing Keliver ${NEXT:-(unread)}'s line (KELIVER_VERSION, the last release)"
  fi
  unset TOOLCHAIN_AGP
  if [ -n "$NEXT" ] && keliver_zipline_line "$NEXT" 2> "$RUN/next.err" && [ ! -s "$RUN/next.err" ]; then
    ok "this checkout builds Keliver $NEXT next ($NEXT_FROM): the table names its line, $KELIVER_LINE"
  else
    bad "this checkout's next version '${NEXT:-unread}' ($NEXT_FROM) has no line in the table: ${KELIVER_LINE_ERROR:-$(cat "$RUN/next.err" 2>/dev/null)} Add its line's column (a released line's column does not change).$NO_HEADING"
  fi
  drift=""
  for pair in "KOTLIN=$(cat_version kotlin)" "COMPOSE=$(cat_version jbCompose)" \
      "COROUTINES=$(cat_version kotlinx-coroutines)" "SERIALIZATION=$(cat_version kotlinx-serialization)" \
      "ZIPLINE=$(cat_version zipline)" "OKHTTP=$(cat_library okhttp)" "AGP=$(cat_library androidGradlePlugin)" \
      "GRADLE=$(sed -nE 's#^distributionUrl=.*/gradle-([0-9.]+)-bin\.zip$#\1#p' "$WRAPPER")" \
      "GRADLE_SHA256=$(sed -nE 's/^distributionSha256Sum=//p' "$WRAPPER")"; do
    k="${pair%%=*}"; want="${pair#*=}"; got="$(eval "printf '%s' \"\${TOOLCHAIN_$k:-}\"")"
    { [ -n "$want" ] && [ "$got" = "$want" ]; } || drift="$drift $k=$got (checkout: ${want:-unread})"
  done
  [ -z "$drift" ] && ok "the $KELIVER_LINE line's toolchain (Keliver $NEXT's) is this checkout's catalog and wrapper" \
    || bad "the ${KELIVER_LINE:-?} line (Keliver ${NEXT:-?}'s) drifted from this checkout:$drift. If the next release is a new minor, add its column and name it in CHANGELOG.md's Unreleased heading; a released line's column does not change.$NO_HEADING"
  }
  next_line_guard "$ROOT/CHANGELOG.md"
  # L1 (review of #299): without the heading the guard compares the last release's line, and its
  # failure says so and names the version it fell back to (here the heading is removed from a copy;
  # the checkout's catalog is the 0.5 line's, so the fallback, 0.4.x, drifts).
  grep -v '^## Unreleased (' "$ROOT/CHANGELOG.md" > "$RUN/CHANGELOG-removed.md"
  sed 's/^## Unreleased (\(.*\))$/## Unreleased: \1/' "$ROOT/CHANGELOG.md" > "$RUN/CHANGELOG-reworded.md"
  last="$(sed -nE 's/^private const val KELIVER_VERSION = "([^"]+)".*/\1/p' "$ROOT/build-support/src/main/kotlin/dev/keliver/buildsupport/RedwoodBuildPlugin.kt" | head -1)"
  for variant in removed reworded; do
    out="$(pass=0; fail=0; next_line_guard "$RUN/CHANGELOG-$variant.md"; echo "fails=$fail")"
    if ! keliver_version_at_least "$last" 0.5.0 2>/dev/null; then
      [[ "$out" == *"FAIL"*"NOTE: CHANGELOG.md has no '## Unreleased (X.Y.Z)' heading"*"fell back to KELIVER_VERSION $last, the last release"* ]] \
        && [[ "$out" == *"note: no '## Unreleased (X.Y.Z)' heading"* ]] && [[ "$out" != *"fails=0" ]] \
        && ok "a CHANGELOG with its Unreleased heading $variant: the guard fails, says the heading is missing and that it fell back to $last" \
        || bad "a CHANGELOG with its Unreleased heading $variant: $out"
    else
      # After 0.5.0 is released the fallback is on the 0.5 line itself: only the note is required.
      [[ "$out" == *"note: no '## Unreleased (X.Y.Z)' heading"*"$last"* ]] \
        && ok "a CHANGELOG with its Unreleased heading $variant: the guard notes the fallback to $last" \
        || bad "a CHANGELOG with its Unreleased heading $variant: $out"
    fi
  done
  # COIL/KTOR '-' means keliver-image brings them: it must, on each platform the hosts build.
  IMG="$ROOT/keliver-image/build.gradle"
  missing=""
  for dep in coil.compose.full coil.core okio coil.network.okhttp coil.network.ktor3 ktor.client.darwin; do
    grep -qE "^[[:space:]]*api libs\\.${dep//./\\.}\$" "$IMG" || missing="$missing $dep"
  done
  [ -z "$missing" ] && ok "keliver-image exports Coil, okio, the OkHttp fetcher and the Ktor fetcher with the Darwin engine" || bad "keliver-image does not export:$missing"
else
  echo "  (no catalog here: the next release's line is not compared with a checkout)"
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
  && grep -q "id 'org.jetbrains.kotlin.multiplatform' version '2.4.21'" "$RUN/init-0.5.0/settings.gradle" \
  && ok "keliver-init 0.5.0: Gradle 9.7.1 (the 0.5 line's AGP needs 9.5.0+) and Kotlin 2.4.21" || bad "keliver-init 0.5.0's wrapper or Kotlin"

echo
echo "runtime line: $pass passed, $fail failed   ($RUN)"
[ "$fail" -eq 0 ]
