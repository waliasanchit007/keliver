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
for name in ('Compose UI', 'Compose material', 'Coil', 'Zipline'):
    assert row(name)[1] == '-', '%s: %r' % (name, row(name)[1])
assert re.search(r'Compose BOM 2024\.06\.00 \(app/build\.gradle\)', log), 'the Compose BOM is not named'
assert re.search(r"\b5 of this app's declared versions will be RAISED", log), 'the RAISED count'
print('ok: the report precedes the write; 5 raised (OkHttp a new major), Kotlin ok, the BOM named')
PY
[ $? = 0 ] && ok "the report: $(tail -1 "$DISP/report-check.txt")" || bad "the report: $(tail -1 "$DISP/report-check.txt")"

echo "=== 3. the app builds, and Gradle resolves what the report said"
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
