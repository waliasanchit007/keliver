#!/usr/bin/env bash
# route.sh's helpers against the files they edit (the app's overlay, the hosts' settings templates)
# and the cases they must refuse. No Gradle, no network: the local-maven jobs run it before they
# publish anything, and ci/prepare.sh and ci/ios.sh run it again on that route. Prints one line;
# exit 0 when every case holds.
#   bash ci/local-maven/route-test.sh
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REF="$(cd "$HERE/../.." && pwd -P)"
REPO="$(cd "$REF/../.." && pwd -P)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
pass=0; fail=0; failed=""
case_ok(){ pass=$((pass+1)); }
case_bad(){ fail=$((fail+1)); failed="$failed; $1"; }
has(){ grep -qF -- "$2" "$1"; }

V=0.5.0-local.0123456789ab
mkdir -p "$T/m2"
printf 'KELIVER_LOCAL_VERSION=%s\nKELIVER_LOCAL_COMMIT=0123456789abcdef\nKELIVER_LOCAL_PLATFORM=android\nKELIVER_LOCAL_SECONDS=1\n' "$V" > "$T/m2/keliver-ref-app.env"
# shellcheck source=route.sh
. "$HERE/route.sh"

# No repository, or one without publish.sh's file: refused.
KELIVER_LOCAL_MAVEN="$T/none" lm_load && case_bad "lm_load: a directory without keliver-ref-app.env was taken" || case_ok
export KELIVER_LOCAL_MAVEN="$T/m2"
if lm_load; then case_ok; else case_bad "lm_load: $LM_ERROR"; fi
[ "$KELIVER_LOCAL_VERSION" = "$V" ] && [ "$KELIVER_LINE" = 0.5 ] && [ "$ZIPLINE_GROUP" = dev.keliver.zipline ] \
  && [ "$KELIVER_ZIPLINE_FORK" = true ] && case_ok || case_bad "lm_load: $KELIVER_LOCAL_VERSION on line ${KELIVER_LINE:-?} ($ZIPLINE_GROUP)"
case "$(lm_route_label)" in "local-maven: "*"$V"*"$T/m2"*) case_ok ;; *) case_bad "lm_route_label: $(lm_route_label)" ;; esac
[ "$(lm_marker)" = "keliver-treehouse-host $V" ] && case_ok || case_bad "lm_marker: $(lm_marker)"
# The marker is the line the library logs (HostBuild.kt), so the two cannot drift apart.
has "$REPO/keliver-treehouse-host/src/commonMain/kotlin/dev/keliver/treehouse/HostBuild.kt" \
  'HOST_BUILD_LINE: String = "keliver-treehouse-host $KELIVER_HOST_VERSION"' && case_ok || case_bad "HostBuild.kt's line is not lm_marker's"

# An app as bootstrap.sh leaves it: the overlay files, on the 0.4 line's wrapper.
A="$T/app"; mkdir -p "$A/gradle/wrapper"
cp "$REF/app/build.gradle" "$REF/app/settings.gradle" "$REF/app/keliver.portal.json" "$A/"
cp "$REPO/scripts/templates/app-wrapper/wrapper.properties" "$A/gradle/wrapper/gradle-wrapper.properties"
if lm_move_app "$A"; then case_ok; else case_bad "lm_move_app: $LM_ERROR"; fi
[ "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["appRuntime"]["keliverVersion"])' "$A/keliver.portal.json")" = "$V" ] \
  && case_ok || case_bad "keliver.portal.json's appRuntime.keliverVersion is not $V"
S="$A/settings.gradle"; B="$A/build.gradle"
for want in "id 'org.jetbrains.kotlin.multiplatform' version '$TOOLCHAIN_KOTLIN'" \
            "id 'org.jetbrains.kotlin.plugin.compose' version '$TOOLCHAIN_KOTLIN'" \
            "id 'org.jetbrains.kotlin.plugin.serialization' version '$TOOLCHAIN_KOTLIN'" \
            "id 'org.jetbrains.compose' version '$TOOLCHAIN_COMPOSE'" \
            "id 'dev.keliver.zipline' version '$TOOLCHAIN_ZIPLINE'" \
            "url = new File('$T/m2').toURI()" "includeGroup('dev.keliver')"; do
  has "$S" "$want" && case_ok || case_bad "settings.gradle lacks: $want"
done
for want in "id 'dev.keliver.zipline'" "'dev.keliver.zipline:zipline:$TOOLCHAIN_ZIPLINE'" \
            "'dev.keliver:keliver-treehouse-guest:$V'" "'dev.keliver:portal-sql:$V'" \
            "'org.jetbrains.compose.runtime:runtime:$TOOLCHAIN_COMPOSE'" \
            "'org.jetbrains.kotlinx:kotlinx-coroutines-core:$TOOLCHAIN_COROUTINES'" \
            "'org.jetbrains.kotlinx:kotlinx-serialization-json:$TOOLCHAIN_SERIALIZATION'" \
            "compilerOptions.freeCompilerArgs.add('-Xes-long-as-bigint')"; do
  has "$B" "$want" && case_ok || case_bad "build.gradle lacks: $want"
done
grep -qE "['\"]app\.cash\.zipline['\":]" "$S" "$B" && case_bad "upstream Zipline is still named" || case_ok
[ "$(grep -oE "'dev\.keliver:[a-z0-9-]+:[^']+'" "$B" | grep -vc ":$V'")" = 0 ] && case_ok || case_bad "a Keliver library is not at $V"
has "$A/gradle/wrapper/gradle-wrapper.properties" "gradle-$TOOLCHAIN_GRADLE-bin.zip" && case_ok || case_bad "the wrapper is not Gradle $TOOLCHAIN_GRADLE"
# Run twice, the same files: nothing is added again (one repository, one BigInt line).
for f in "$S" "$B" "$A/keliver.portal.json"; do cp "$f" "$f.once"; done
lm_move_app "$A" && cmp -s "$S" "$S.once" && cmp -s "$B" "$B.once" && cmp -s "$A/keliver.portal.json" "$A/keliver.portal.json.once" \
  && case_ok || case_bad "a second lm_move_app changed the app ($LM_ERROR)"
# An app without one of the anchors is refused, with the reason.
sed "/kotlinx-coroutines-core/d" "$REF/app/build.gradle" > "$T/b.gradle"
A2="$T/app2"; mkdir -p "$A2/gradle/wrapper"; cp "$REF/app/settings.gradle" "$REF/app/keliver.portal.json" "$A2/"; cp "$T/b.gradle" "$A2/build.gradle"
cp "$REPO/scripts/templates/app-wrapper/wrapper.properties" "$A2/gradle/wrapper/gradle-wrapper.properties"
if lm_move_app "$A2"; then case_bad "an app without the coroutines line was moved"
else case "$LM_ERROR" in *kotlinx-coroutines-core*) case_ok ;; *) case_bad "the refusal does not say why: $LM_ERROR" ;; esac; fi

# The hosts' settings, as the scaffolders write them: the repository goes first, the rest stays.
for t in production-host ios-host; do
  cp "$REPO/scripts/templates/$t/settings.gradle" "$T/$t.settings.gradle"
  if lm_add_repository "$T/$t.settings.gradle"; then case_ok; else case_bad "$t: $LM_ERROR"; fi
  has "$T/$t.settings.gradle" "includeGroup('dev.keliver')" && has "$T/$t.settings.gradle" "google(); mavenCentral() }" \
    && has "$T/$t.settings.gradle" "repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS" && case_ok || case_bad "$t: the settings are not as expected"
  [ "$(grep -c "exclusiveContent" "$T/$t.settings.gradle")" = 1 ] && case_ok || case_bad "$t: not exactly one exclusiveContent"
  lm_add_repository "$T/$t.settings.gradle" && [ "$(grep -c "exclusiveContent" "$T/$t.settings.gradle")" = 1 ] && case_ok || case_bad "$t: added twice"
done
printf "rootProject.name = 'x'\n" > "$T/bare.gradle"
lm_add_repository "$T/bare.gradle" && case_bad "a settings file with no dependencyResolutionManagement was edited" || case_ok
[ "$(cat "$T/bare.gradle")" = "rootProject.name = 'x'" ] && case_ok || case_bad "the refused settings file changed"

# The dependency report: every dev.keliver artifact at the route's version, and the Zipline fork is not Keliver's library.
printf '+--- dev.keliver:keliver-treehouse-host:%s\n|    +--- dev.keliver.zipline:zipline:1.28.0-keliver.1\n\\--- dev.keliver:portal-sql:%s (*)\n' "$V" "$V" > "$T/deps-good"
if lm_check_artifacts "$T/deps-good" "$T/out"; then case_ok; else case_bad "a report at $V: $LM_ERROR"; fi
[ "$(grep -c . "$T/out")" = 2 ] && case_ok || case_bad "the report's artifacts: $(tr '\n' ' ' < "$T/out")"
printf '+--- dev.keliver:keliver-treehouse-host:%s\n+--- dev.keliver:keliver-http:0.3.3\n' "$V" > "$T/deps-central"
if lm_check_artifacts "$T/deps-central" "$T/out"; then case_bad "a report with Keliver 0.3.3 was taken"
else case "$LM_ERROR" in *keliver-http:0.3.3*) case_ok ;; *) case_bad "the refusal does not name keliver-http:0.3.3: $LM_ERROR" ;; esac; fi
: > "$T/deps-empty"
lm_check_artifacts "$T/deps-empty" "$T/out" && case_bad "an empty report was taken" || case_ok

if [ "$fail" -eq 0 ]; then
  echo "local-maven route-test: $pass cases hold"
else
  echo "local-maven route-test: $fail of $((pass + fail)) cases FAILED:${failed#;}"
  exit 1
fi
