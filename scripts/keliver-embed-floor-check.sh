#!/usr/bin/env bash
#
# keliver-embed-floor-check — the dependency-floor report (K3) against an app
# on older libraries: what keliver-new-production-host.sh --embed prints before
# it writes anything is what Gradle then does.
#
#   scripts/keliver-embed-floor-check.sh <disposable-parent>
#
# 1. The floor: keliver-embed-floor.sh resolves the embed template's classpath
#    (or, with KELIVER_PRODUCTION_HOST_SCAFFOLD set to a packaged scaffolder,
#    the floor its tools bundle carries at templates/production-host-embed/).
# 2. The report: the scaffolder, run --embed into a copy of
#    scripts/fixtures/embed-older-compose (Compose BOM 2024.06.00, OkHttp 4,
#    ...), prints each library's floor beside the version the app declares,
#    before anything is written; a floor file it cannot read writes nothing.
#    Then, without a build, the declaration shapes it must not misread (strict
#    and forced versions, BOMs, catalogs, variables, comments, an older Kotlin),
#    and a report that breaks: a warning, and the module is still written.
# 3. The build: that app builds with the module, and its resolved
#    releaseRuntimeClasspath has every listed library at its floor or newer, and
#    the ones the report says it raises at exactly the floor.
#
# Needs an Android SDK and network access to Maven Central, Google and the
# Gradle plugin portal (or a Gradle home holding them). Gradle runs without a
# daemon. The scaffolder's store lookup is kept inside the run directory.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
SCAFFOLD="${KELIVER_PRODUCTION_HOST_SCAFFOLD:-$ROOT/scripts/keliver-new-production-host.sh}"
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "${1:?usage: $0 <disposable-parent>}" embed-floor)" || exit $?
pass=0; fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
snapshot() { ( cd "$1" && find . -type f ! -path './.gradle/*' -print0 | sort -z | xargs -0 shasum -a 256 ) 2>/dev/null; }
[ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ] && export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
echo "scaffolder: $SCAFFOLD"

echo "=== 1. the floor"
TEMPLATES="$(cd "$(dirname "$SCAFFOLD")" && pwd -P)"
for t in "$TEMPLATES/templates" "$TEMPLATES/../templates"; do
  [ -f "$t/production-host-embed/build.gradle" ] && { TEMPLATES="$(cd "$t" && pwd -P)"; break; }
done
EMBED_BUILD="$TEMPLATES/production-host-embed/build.gradle"
if [ -n "${KELIVER_PRODUCTION_HOST_SCAFFOLD:-}" ]; then
  FLOOR="$TEMPLATES/production-host-embed/dependency-floor.json"
  [ -f "$FLOOR" ] && ok "the packaged templates carry dependency-floor.json" || bad "no $FLOOR in the package"
  FLOOR_ENV=()
else
  FLOOR="$DISP/dependency-floor.json"
  KELIVER_PRODUCTION_HOST_SCAFFOLD="$SCAFFOLD" "$ROOT/scripts/keliver-embed-floor.sh" "$DISP/floor-work" "$FLOOR" > "$DISP/floor.log" 2>&1 \
    && ok "keliver-embed-floor.sh resolved the embed template's classpath" \
    || { bad "keliver-embed-floor.sh failed"; tail -20 "$DISP/floor.log"; }
  FLOOR_ENV=(KELIVER_EMBED_FLOOR="$FLOOR")
fi
[ -f "$FLOOR" ] || { echo "no floor; stopping. $pass passed, $((fail)) failed"; exit 1; }
python3 - "$FLOOR" "$EMBED_BUILD" > "$DISP/floor-check.txt" 2>&1 <<'PY'
import json, re, sys
f = json.load(open(sys.argv[1])); tmpl = open(sys.argv[2]).read()
rows = {r['name']: r for r in f['floor']}
want = ['Kotlin stdlib', 'kotlinx-coroutines', 'Compose runtime', 'Compose UI', 'Compose foundation',
        'Compose material', 'activity-compose', 'OkHttp', 'Coil', 'Zipline']
missing = [w for w in want if w not in rows]
assert not missing, 'rows missing: %s' % missing
resolved = set(f['resolved'])
for r in f['floor']:
    assert r['module'] + ':' + r['version'] in resolved, '%s %s is not in the resolved list' % (r['module'], r['version'])
assert rows['Kotlin stdlib']['version'] == f['plugins']['kotlin'], 'stdlib %s, Kotlin plugin %s' % (rows['Kotlin stdlib']['version'], f['plugins']['kotlin'])
assert rows['Zipline']['version'] == f['plugins']['zipline'], 'zipline %s vs plugin %s' % (rows['Zipline']['version'], f['plugins']['zipline'])
# The template's own pins are floors too: what it asks for is at most what resolved.
def key(v): return [int(x) for x in re.findall(r'\d+', v.split('-')[0])]
for g, n, v in re.findall(r"'([\w.-]+):([\w.-]+):(\d[\w.-]*)'", tmpl):
    got = [x for x in resolved if x.startswith(g + ':' + n + ':')]
    assert got, '%s:%s (the template asks for %s) did not resolve' % (g, n, v)
    assert key(got[0].rsplit(':', 1)[1]) >= key(v), '%s resolved below the template (%s)' % (got[0], v)
print('ok: %d rows, %d modules; the template\'s pins resolved at or above what it asks' % (len(rows), len(resolved)))
PY
[ $? = 0 ] && ok "the floor is complete and agrees with the template and its plugins: $(tail -1 "$DISP/floor-check.txt")" \
  || bad "the floor: $(tail -1 "$DISP/floor-check.txt")"

echo "=== 2. the report, before anything is written"
APP="$DISP/guest"; EX="$DISP/older"; STORE="$DISP/store"
mkdir -p "$APP/src/jsMain/kotlin/screens" "$STORE/keys" "$DISP/home"
printf '{ "screensDir": "src/jsMain/kotlin/screens" }\n' > "$APP/keliver.portal.json"
printf 'package dev.keliver.floor.screens\n\nfun HomeScreen() {}\n' > "$APP/src/jsMain/kotlin/screens/home.kt"
printf '%s\n' "$(printf 'ab%.0s' $(seq 1 32))" > "$STORE/keys/ed25519.pub"
cp -R "$ROOT/scripts/fixtures/embed-older-compose" "$EX"
cp "$ROOT/gradlew" "$EX/"; mkdir -p "$EX/gradle/wrapper"
cp "$ROOT/gradle/wrapper/gradle-wrapper.jar" "$ROOT/gradle/wrapper/gradle-wrapper.properties" "$EX/gradle/wrapper/"
printf 'sdk.dir=%s\n' "$SDK" > "$EX/local.properties"
export JAVA_TOOL_OPTIONS="-Duser.home=$DISP/home" PORTAL_STORE="$STORE"
keliver_require_isolated_store "$DISP" "$APP" > /dev/null || { echo "isolation guard refused" >&2; exit 2; }

printf '{ "floor": [' > "$DISP/broken.json"
before="$(snapshot "$EX")"
out="$( cd "$APP" && KELIVER_EMBED_FLOOR="$DISP/broken.json" "$SCAFFOLD" --embed --into "$EX" --bundle-server https://bundles.invalid 2>&1 )"; rc=$?
[ "$rc" != 0 ] && printf '%s' "$out" | grep -q "could not read the dependency floor" && [ "$before" = "$(snapshot "$EX")" ] \
  && ok "a floor file it cannot read: refused, and the app is byte-identical" \
  || bad "a broken floor file: rc=$rc, changed=$([ "$before" = "$(snapshot "$EX")" ] && echo no || echo YES): $(printf '%s' "$out" | tail -2 | tr '\n' ' ')"

( cd "$APP" && env ${FLOOR_ENV[@]+"${FLOOR_ENV[@]}"} "$SCAFFOLD" --embed --into "$EX" --bundle-server https://bundles.invalid ) > "$DISP/scaffold.log" 2>&1; rc=$?
[ "$rc" = 0 ] && [ -f "$EX/keliver-host/build.gradle" ] && ok "--embed wrote keliver-host/ into the older app" \
  || { bad "--embed failed (rc=$rc)"; tail -15 "$DISP/scaffold.log"; }
sed 's/^/    | /' "$DISP/scaffold.log" | sed -n '1,30p'
python3 - "$DISP/scaffold.log" "$FLOOR" > "$DISP/report-check.txt" 2>&1 <<'PY'
import json, re, sys
log = open(sys.argv[1]).read(); f = json.load(open(sys.argv[2]))
v = {r['name']: r['version'] for r in f['floor']}
head = log.index('dependency floor of the embedded host')
assert head < log.index('created keliver-host/'), 'the report comes after the write'
def row(name):
    m = re.search(r'^  ' + re.escape(name) + r'\s{2,}(\S+)\s+(.*?)\s*$', log, re.M)
    assert m, 'no row for ' + name
    return m.group(1), m.group(2)
expect_raised = {'OkHttp': '4.12.0 (app/build.gradle)', 'activity-compose': '1.8.2 (app/build.gradle)',
                 'androidx.core': '1.12.0 (app/build.gradle)', 'androidx.lifecycle': '2.6.2 (app/build.gradle)',
                 'kotlinx-coroutines': '1.7.3 (app/build.gradle)'}
for name, declared in expect_raised.items():
    floor, rest = row(name)
    assert floor == v[name], '%s: floor %s, expected %s' % (name, floor, v[name])
    assert rest.startswith(declared) and ('RAISED to ' + v[name]) in rest, '%s: %r' % (name, rest)
assert 'a new major version' in row('OkHttp')[1], 'OkHttp 4 -> 5 is not called a new major version'
floor, rest = row('Kotlin stdlib')
assert rest.startswith('2.2.0 (build.gradle, the Kotlin plugin)') and rest.endswith('ok'), 'Kotlin: %r' % rest
for name in ('Compose UI', 'Compose material'):
    rest = row(name)[1]
    assert rest.startswith('Compose BOM 2024.06.00 (app/build.gradle)') and "check this BOM's Compose version" in rest, '%s: %r' % (name, rest)
for name in ('Coil', 'Zipline'):
    assert row(name)[1] == '-', '%s: %r' % (name, row(name)[1])
assert 'this floor was resolved for' not in log, 'the floor and the module written disagree on versions'
assert 'warning:' not in log, 'the report warned'
assert re.search(r"\b5 of this app's declared versions will be RAISED", log), 'the RAISED count'
print('ok: the report precedes the write; 5 raised (OkHttp a new major), Kotlin ok, Compose through the BOM')
PY
[ $? = 0 ] && ok "the report: $(tail -1 "$DISP/report-check.txt")" || bad "the report: $(tail -1 "$DISP/report-check.txt")"

# The report on the declaration shapes it must not misread (not built): a
# catalog with a strict rich version, a single-quoted version, a plugin in
# string notation and an OkHttp BOM; a Groovy ext { } block; a nested Kotlin
# module with comments, a typed val, rootProject.extra, a force() and a !!.
# The directory name holds glob characters.
SH="$DISP/shapes [old]"; mkdir -p "$SH/gradle" "$SH/feature/ui"
printf 'rootProject.name = "shapes"\ninclude(":feature:ui")\n' > "$SH/settings.gradle.kts"
cat > "$SH/gradle/libs.versions.toml" <<'EOF'
[versions]
kotlin = "1.9.22"
coroutines = { strictly = "1.7.3" }
activity = '1.8.2'

[libraries]
coroutines = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
activity = { group = "androidx.activity", name = "activity-compose", version.ref = "activity" }
okhttp-bom = { module = "com.squareup.okhttp3:okhttp-bom", version = "4.12.0" }
okhttp = { module = "com.squareup.okhttp3:okhttp" }

[plugins]
kotlin-android = "org.jetbrains.kotlin.android:1.9.22"
EOF
cat > "$SH/build.gradle" <<'EOF'
ext {
  core_version = '1.12.0'
}
EOF
cat > "$SH/feature/ui/build.gradle.kts" <<'EOF'
// implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.0.0")
/* implementation("io.coil-kt.coil3:coil-compose:3.0.0") */
val lifecycleVersion: String = "2.6.2"
dependencies {
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion") // https://example.com/notes
  implementation("androidx.core:core-ktx:${rootProject.extra["core_version"]}")
  implementation("com.squareup.okio:okio:99.0.0!!")
  implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.6.4"))
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
}
configurations.all { resolutionStrategy { force("androidx.browser:browser:1.4.0") } }
EOF
( cd "$APP" && env ${FLOOR_ENV[@]+"${FLOOR_ENV[@]}"} "$SCAFFOLD" --embed --into "$SH" --module kh --bundle-server https://bundles.invalid ) > "$DISP/shapes.log" 2>&1; rc=$?
sed 's/^/    | /' "$DISP/shapes.log" | sed -n '1,26p'
python3 - "$DISP/shapes.log" "$FLOOR" > "$DISP/shapes-check.txt" 2>&1 <<'PY'
import json, re, sys
log = open(sys.argv[1]).read(); f = json.load(open(sys.argv[2]))
v = {r['name']: r['version'] for r in f['floor']}
def row(name):
    m = re.search(r'^  ' + re.escape(name) + r'\s{2,}(\S+)\s+(.*?)\s*$', log, re.M)
    assert m, 'no row for ' + name
    return m.group(2)
cases = {
    'Kotlin stdlib': ('1.9.22 (gradle/libs.versions.toml, the Kotlin plugin)', 'will not compile'),
    'kotlinx-coroutines': ('1.7.3, strict (gradle/libs.versions.toml)', 'PINNED below the floor'),
    'activity-compose': ('1.8.2 (gradle/libs.versions.toml)', 'RAISED to ' + v['activity-compose']),
    'OkHttp': ('4.12.0 (okhttp-bom, gradle/libs.versions.toml)', 'RAISED to %s, a new major version' % v['OkHttp']),
    'androidx.core': ('1.12.0 (feature/ui/build.gradle.kts)', 'RAISED to ' + v['androidx.core']),
    'androidx.lifecycle': ('2.6.2 (feature/ui/build.gradle.kts)', 'RAISED to ' + v['androidx.lifecycle']),
    'androidx.browser': ('1.4.0, strict (feature/ui/build.gradle.kts)', 'PINNED below the floor'),
    'Okio': ('99.0.0, strict (feature/ui/build.gradle.kts)', 'ok'),
}
for name, (declared, verdict) in cases.items():
    rest = row(name)
    assert rest.startswith(declared) and verdict in rest, '%s: %r' % (name, rest)
assert row('Coil') == '-', 'a commented-out Coil was read: %r' % row('Coil')
assert '1.6.4' not in row('kotlinx-serialization'), 'the coroutines BOM versioned serialization: %r' % row('kotlinx-serialization')
assert re.search(r"\b4 of this app's declared versions will be RAISED", log), 'the RAISED count'
assert re.search(r'\b2 strict or forced version', log), 'the PINNED count'
assert 'module will not compile in it' in log, 'the Kotlin gap is not named'
assert 'report stopped' not in log and 'report failed' not in log, 'the report broke'
print('ok: strict, forced, BOM, ext, typed val, rootProject.extra, comments and the Kotlin plugin read as written')
PY
[ $? = 0 ] && [ "$rc" = 0 ] && ok "the report on other shapes: $(tail -1 "$DISP/shapes-check.txt")" \
  || bad "the report on other shapes (rc=$rc): $(tail -1 "$DISP/shapes-check.txt")"

# More shapes: '/*' and '**/' inside strings and a '//' in a string are not
# comments; one library's BOM does not version another; enforcedPlatform, a
# force() of two and { force = true } are pins; a Kotlin BOM does not hide an
# older Kotlin plugin.
SH2="$DISP/shapes2"; mkdir -p "$SH2/app"
printf "rootProject.name = 'shapes2'\ninclude ':app'\n" > "$SH2/settings.gradle"
cat > "$SH2/build.gradle" <<'EOF'
plugins { id 'org.jetbrains.kotlin.android' version '1.9.22' apply false }
EOF
cat > "$SH2/app/build.gradle" <<'EOF'
// TODO: drop the libs/* jars
android {
  packagingOptions { exclude 'META-INF/*.kotlin_module' }
}
def p = "a//b" ; dependencies { implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.6.2' }
dependencies {
  implementation platform('org.jetbrains.kotlin:kotlin-bom:1.9.22')
  implementation platform('org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.7.3')
  implementation 'org.jetbrains.kotlinx:kotlinx-serialization-json'
  implementation enforcedPlatform('com.squareup.okhttp3:okhttp-bom:4.12.0')
  implementation('androidx.browser:browser:1.4.0') { force = true }
  /* an old block */
}
configurations.all {
  resolutionStrategy.force('androidx.core:core-ktx:1.9.0', 'androidx.activity:activity-compose:1.7.0')
}
def coverage = ['**/R.class', '**/R$*.class']
def script = """
  cp build/outputs/*.apk dist/
"""
configurations.all { resolutionStrategy { force 'androidx.browser:browser:1.4.0' } }; dependencies { implementation 'com.squareup.okio:okio:3.0.0' }
configurations.all { resolutionStrategy { force 'io.coil-kt.coil3:coil-compose:3.0.0', 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0' } }
EOF
( cd "$APP" && env ${FLOOR_ENV[@]+"${FLOOR_ENV[@]}"} "$SCAFFOLD" --embed --into "$SH2" --module kh --bundle-server https://bundles.invalid ) > "$DISP/shapes2.log" 2>&1; rc=$?
python3 - "$DISP/shapes2.log" "$FLOOR" > "$DISP/shapes2-check.txt" 2>&1 <<'PY'
import json, re, sys
log = open(sys.argv[1]).read(); f = json.load(open(sys.argv[2]))
v = {r['name']: r['version'] for r in f['floor']}
def row(name):
    m = re.search(r'^  ' + re.escape(name) + r'\s{2,}(\S+)\s+(.*?)\s*$', log, re.M)
    assert m, 'no row for ' + name
    return m.group(2)
cases = {
    'Kotlin stdlib': ('1.9.22 (build.gradle, the Kotlin plugin)', 'will not compile'),
    'kotlinx-coroutines': ('1.7.3 (kotlinx-coroutines-bom, app/build.gradle)', 'RAISED to ' + v['kotlinx-coroutines']),
    'OkHttp': ('4.12.0, strict (okhttp-bom, app/build.gradle)', 'PINNED below the floor'),
    'androidx.browser': ('1.4.0, strict (app/build.gradle)', 'PINNED below the floor'),
    'androidx.core': ('1.9.0, strict (app/build.gradle)', 'PINNED below the floor'),
    'activity-compose': ('1.7.0, strict (app/build.gradle)', 'PINNED below the floor'),
    'androidx.lifecycle': ('2.6.2 (app/build.gradle)', 'RAISED to ' + v['androidx.lifecycle']),
    'Okio': ('3.0.0 (app/build.gradle)', 'RAISED to ' + v['Okio']),
    'Coil': ('3.0.0, strict (app/build.gradle)', 'PINNED below the floor'),
    'kotlinx-serialization': ('1.5.0, strict (app/build.gradle)', 'PINNED below the floor'),
}
for name, (declared, verdict) in cases.items():
    rest = row(name)
    assert rest.startswith(declared) and verdict in rest, '%s: %r' % (name, rest)
assert 'report stopped' not in log and 'report failed' not in log, 'the report broke'
print('ok: /* and // in strings (and a triple-quoted one) kept, a force ends at its statement, BOMs by name, enforcedPlatform / force() of two / force a, b / force = true pinned, a Kotlin BOM does not hide Kotlin 1.9')
PY
[ $? = 0 ] && [ "$rc" = 0 ] && ok "the report on more shapes: $(tail -1 "$DISP/shapes2-check.txt")" \
  || { bad "the report on more shapes (rc=$rc): $(tail -1 "$DISP/shapes2-check.txt")"; sed -n '1,22p' "$DISP/shapes2.log"; }

# A build file it cannot read is skipped and named; a report that breaks (a
# floor without its keliverVersion, which the scaffolder's own validation does
# not ask for) is a warning. Neither refuses the module.
mkdir -p "$DISP/unreadable/mod"; printf "rootProject.name = 'u'\n" > "$DISP/unreadable/settings.gradle"
ln -s "$DISP/does-not-exist" "$DISP/unreadable/mod/build.gradle"
( cd "$APP" && env ${FLOOR_ENV[@]+"${FLOOR_ENV[@]}"} "$SCAFFOLD" --embed --into "$DISP/unreadable" --bundle-server https://bundles.invalid ) > "$DISP/unreadable.log" 2>&1; rc=$?
[ "$rc" = 0 ] && grep -q "(could not read mod/build.gradle: skipped)" "$DISP/unreadable.log" && grep -q "^dependency floor of the embedded host" "$DISP/unreadable.log" \
  && [ -d "$DISP/unreadable/keliver-host" ] \
  && ok "a build file it cannot read is skipped and named; the report and the module still come" \
  || bad "an unreadable build file: rc=$rc $(tail -2 "$DISP/unreadable.log" | tr '\n' ' ')"
python3 -c "import json,sys; f=json.load(open(sys.argv[1])); del f['keliverVersion']; json.dump(f, open(sys.argv[2],'w'))" "$FLOOR" "$DISP/no-version.json"
mkdir -p "$DISP/warned"; printf "rootProject.name = 'w'\n" > "$DISP/warned/settings.gradle"
( cd "$APP" && KELIVER_EMBED_FLOOR="$DISP/no-version.json" "$SCAFFOLD" --embed --into "$DISP/warned" --bundle-server https://bundles.invalid ) > "$DISP/warned.log" 2>&1; rc=$?
[ "$rc" = 0 ] && grep -q "^warning: the dependency-floor report stopped" "$DISP/warned.log" && [ -d "$DISP/warned/keliver-host" ] \
  && ok "a report that breaks warns, and the module is still written" \
  || bad "a broken report: rc=$rc $(tail -2 "$DISP/warned.log" | tr '\n' ' ')"

echo "=== 3. the app builds, and Gradle resolves what the report said"
# Gradle keeps the real user.home (its default Gradle home); the isolation
# above is for the scaffolder's store lookup.
unset JAVA_TOOL_OPTIONS
( cd "$EX" && ./gradlew --console=plain --no-daemon :app:assembleDebug ) > "$DISP/build.log" 2>&1 \
  && ok "the older app builds with the embedded host (assembleDebug)" \
  || { bad "the older app did not build"; grep -E '^e: |What went wrong' -A4 "$DISP/build.log" | head -25; }
cat > "$DISP/resolve.init.gradle" <<'EOF'
allprojects {
  if (path == ':app') {
    afterEvaluate {
      tasks.register('keliverResolved') {
        def cfg = configurations.getByName('releaseRuntimeClasspath')
        def out = file(providers.gradleProperty('keliver.resolvedOut').get())
        doLast {
          def unresolved = cfg.incoming.resolutionResult.allDependencies
            .findAll { it instanceof org.gradle.api.artifacts.result.UnresolvedDependencyResult }
          if (unresolved) throw new GradleException('unresolved: ' + unresolved.collect { it.requested.displayName }.join(', '))
          out.text = cfg.incoming.resolutionResult.allComponents
            .findAll { it.id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .collect { "${it.moduleVersion.group}:${it.moduleVersion.name}:${it.moduleVersion.version}" }
            .unique().sort().join('\n') + '\n'
        }
      }
    }
  }
}
EOF
( cd "$EX" && ./gradlew --console=plain --no-daemon --no-configuration-cache -q --init-script "$DISP/resolve.init.gradle" \
    :app:keliverResolved -Pkeliver.resolvedOut="$DISP/app-resolved.txt" ) > "$DISP/resolve.log" 2>&1 \
  || { bad "could not resolve the app's classpath"; tail -15 "$DISP/resolve.log"; }
python3 - "$DISP/app-resolved.txt" "$FLOOR" "$DISP/scaffold.log" > "$DISP/resolved-check.txt" 2>&1 <<'PY'
import json, re, sys
res = {}
for line in open(sys.argv[1]):
    g, n, v = line.strip().split(':'); res[g + ':' + n] = v
f = json.load(open(sys.argv[2])); log = open(sys.argv[3]).read()
def key(v): return [int(x) for x in re.findall(r'\d+', v.split('-')[0])]
lines = []
for r in f['floor']:
    got = res.get(r['module'])
    assert got, '%s is not in the app\'s classpath' % r['module']
    assert key(got) >= key(r['version']), '%s resolved %s, below the floor %s' % (r['module'], got, r['version'])
    if re.search(r'^  ' + re.escape(r['name']) + r'\s.*RAISED to ', log, re.M):
        assert got == r['version'], '%s: reported raised to %s, resolved %s' % (r['name'], r['version'], got)
        lines.append('%s %s' % (r['name'], got))
assert res.get('androidx.compose.ui:ui') == next(r['version'] for r in f['floor'] if r['name'] == 'Compose UI'), \
    'the BOM\'s Compose UI was not raised to the floor: %s' % res.get('androidx.compose.ui:ui')
print('ok: every row at or above its floor; raised as reported: ' + ', '.join(lines) + '; Compose UI (BOM 1.6.8) at the floor')
PY
[ $? = 0 ] && ok "resolved: $(tail -1 "$DISP/resolved-check.txt")" || bad "resolved: $(tail -1 "$DISP/resolved-check.txt")"

echo "embed floor check: $pass passed, $fail failed  (run directory: $DISP)"
[ "$fail" = 0 ]
