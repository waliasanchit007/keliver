#!/usr/bin/env bash
#
# keliver-embed-floor — the dependency floor an embedded Keliver host imposes on
# an existing Android app (adoption item 3, K3).
#
#   scripts/keliver-embed-floor.sh <work-dir> <out.json>
#
# Scaffolds the --embed template (keliver-new-production-host.sh --embed, the
# same versions an adopter gets) into a throwaway app with no dependencies of
# its own, resolves the library module's releaseRuntimeClasspath with Gradle,
# and writes what it resolved: the versions Gradle will raise an app to when it
# adds the module. build-portal-tools.sh runs this and ships the result as
# templates/production-host-embed/dependency-floor.json; the scaffolder prints
# it, beside the app's declared versions, before it writes anything.
#
# Needs network access to Maven Central, Google and the Gradle plugin portal
# (or a Gradle home that already holds them), and an Android SDK (ANDROID_HOME,
# or ~/Library/Android/sdk). <work-dir> is created, and must be new or empty.
# Gradle runs without a daemon.
# KELIVER_PRODUCTION_HOST_SCAFFOLD runs another copy of the scaffolder.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
SCAFFOLD="${KELIVER_PRODUCTION_HOST_SCAFFOLD:-$ROOT/scripts/keliver-new-production-host.sh}"
WORK="${1:?usage: $0 <work-dir> <out.json>}"
OUT="${2:?usage: $0 <work-dir> <out.json>}"
fail() { echo "keliver-embed-floor: $*" >&2; exit 1; }
if [ -e "$WORK" ]; then
  [ -d "$WORK" ] && [ -z "$(ls -A "$WORK")" ] || fail "$WORK exists and is not an empty directory."
fi
mkdir -p "$WORK"; WORK="$(cd "$WORK" && pwd -P)"
[ -n "${JAVA_HOME:-}" ] || { [ -x /usr/libexec/java_home ] && export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; } || true
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
[ -d "$SDK" ] || fail "no Android SDK (set ANDROID_HOME)."

# A keliver-init-shaped app to run the scaffolder from, with a placeholder key
# (the floor does not depend on it) and a store of its own, so the scaffolder's
# store lookup never reaches a real one.
APP="$WORK/app"; EX="$WORK/probe"
mkdir -p "$APP/src/jsMain/kotlin/screens" "$WORK/store" "$WORK/home" "$EX"
printf '{ "screensDir": "src/jsMain/kotlin/screens" }\n' > "$APP/keliver.portal.json"
printf 'package dev.keliver.floor.screens\n\nfun HomeScreen() {}\n' > "$APP/src/jsMain/kotlin/screens/home.kt"
printf '%s\n' "$(printf '00%.0s' $(seq 1 32))" > "$WORK/placeholder.pub"

# The probe: an existing app with nothing of its own, declaring the plugins
# the embed asks the app for (the versions the scaffolder's defaults name).
eval "$(sed -nE 's/^(ZIPLINE_VERSION|KOTLIN_VERSION|AGP_VERSION|COMPOSE_VERSION)="\$\{KELIVER_HOST_[A-Z_]+:-([0-9A-Za-z.+-]+)\}"$/\1=\2/p' "$SCAFFOLD")"
[ -n "${ZIPLINE_VERSION:-}" ] && [ -n "${KOTLIN_VERSION:-}" ] && [ -n "${AGP_VERSION:-}" ] && [ -n "${COMPOSE_VERSION:-}" ] \
  || fail "could not read the plugin versions from $SCAFFOLD."
ZIPLINE_VERSION="${KELIVER_HOST_ZIPLINE_VERSION:-$ZIPLINE_VERSION}"
KOTLIN_VERSION="${KELIVER_HOST_KOTLIN_VERSION:-$KOTLIN_VERSION}"
AGP_VERSION="${KELIVER_HOST_AGP_VERSION:-$AGP_VERSION}"
COMPOSE_VERSION="${KELIVER_HOST_COMPOSE_VERSION:-$COMPOSE_VERSION}"
cat > "$EX/settings.gradle" <<EOF
pluginManagement {
  repositories { gradlePluginPortal(); google(); mavenCentral() }
}
dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories { google(); mavenCentral() }
}
rootProject.name = 'floor-probe'
EOF
cat > "$EX/build.gradle" <<EOF
plugins {
  id 'com.android.library' version '$AGP_VERSION' apply false
  id 'org.jetbrains.kotlin.android' version '$KOTLIN_VERSION' apply false
  id 'org.jetbrains.kotlin.plugin.compose' version '$KOTLIN_VERSION' apply false
  id 'app.cash.zipline' version '$ZIPLINE_VERSION' apply false
}
EOF
printf 'android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\n' > "$EX/gradle.properties"
printf 'sdk.dir=%s\n' "$SDK" > "$EX/local.properties"
cp "$ROOT/gradlew" "$EX/"; mkdir -p "$EX/gradle/wrapper"
cp "$ROOT/gradle/wrapper/gradle-wrapper.jar" "$ROOT/gradle/wrapper/gradle-wrapper.properties" "$EX/gradle/wrapper/"

( cd "$APP" && HOME="$WORK/home" PORTAL_STORE="$WORK/store" KELIVER_EMBED_FLOOR=none \
    "$SCAFFOLD" --embed --into "$EX" --module keliver-host \
    --bundle-server https://bundles.invalid --public-key-file "$WORK/placeholder.pub" ) > "$WORK/scaffold.log" 2>&1 \
  || { cat "$WORK/scaffold.log" >&2; fail "the scaffolder failed."; }
printf "include ':keliver-host'\n" >> "$EX/settings.gradle"

# Every module version in the library's release runtime classpath, after
# Gradle's conflict resolution: what it would bring into an app that had none.
cat > "$WORK/floor.init.gradle" <<'EOF'
allprojects {
  if (path == ':keliver-host') {
    afterEvaluate {
      tasks.register('keliverFloor') {
        def cfg = configurations.getByName('releaseRuntimeClasspath')
        def out = file(providers.gradleProperty('keliver.floorOut').get())
        doLast {
          // A dependency that did not resolve would drop out of the list with
          // everything under it, and the floor would be too low: fail instead.
          def unresolved = cfg.incoming.resolutionResult.allDependencies
            .findAll { it instanceof org.gradle.api.artifacts.result.UnresolvedDependencyResult }
          if (unresolved) {
            throw new GradleException('unresolved: ' + unresolved.collect { "${it.requested.displayName} (${it.failure.message})" }.join('; '))
          }
          def mods = cfg.incoming.resolutionResult.allComponents
            .findAll { it.id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .collect { "${it.moduleVersion.group}:${it.moduleVersion.name}:${it.moduleVersion.version}" }
          out.text = mods.unique().sort().join('\n') + '\n'
        }
      }
    }
  }
}
EOF
( cd "$EX" && ./gradlew --console=plain --no-daemon --no-configuration-cache -q --init-script "$WORK/floor.init.gradle" \
    :keliver-host:keliverFloor -Pkeliver.floorOut="$WORK/resolved.txt" ) > "$WORK/gradle.log" 2>&1 \
  || { tail -30 "$WORK/gradle.log" >&2; fail "Gradle could not resolve the embed's classpath (log: $WORK/gradle.log)."; }
grep -q ":" "$WORK/resolved.txt" || fail "Gradle resolved nothing."

KELIVER_VERSION="$(sed -nE "s/.*'dev\.keliver:keliver-treehouse-host:([^']+)'.*/\1/p" "$EX/keliver-host/build.gradle")"
python3 - "$WORK/resolved.txt" "$OUT" "$KELIVER_VERSION" "$KOTLIN_VERSION" "$ZIPLINE_VERSION" "$AGP_VERSION" "$COMPOSE_VERSION" \
  "$(sed -nE 's/^[[:space:]]*compileSdk[[:space:]]+([0-9]+).*/\1/p' "$EX/keliver-host/build.gradle")" \
  "$(sed -nE 's/^[[:space:]]*minSdk[[:space:]]+([0-9]+).*/\1/p' "$EX/keliver-host/build.gradle")" <<'PY'
import json, sys
resolved_file, out, keliver, kotlin, zipline, agp, compose, compile_sdk, min_sdk = sys.argv[1:10]
resolved = {}
for line in open(resolved_file):
    if not line.strip():
        continue
    g, n, v = line.strip().split(':')
    resolved[g + ':' + n] = v
# The libraries an app most often pins itself: the module whose resolved
# version is the floor (the first one present), and the modules an app declares
# that move with it (the scaffolder compares those).
ROWS = [
    ('Kotlin stdlib', ['org.jetbrains.kotlin:kotlin-stdlib'],
     ['org.jetbrains.kotlin:kotlin-stdlib', 'org.jetbrains.kotlin:kotlin-stdlib-jdk8', 'org.jetbrains.kotlin:kotlin-stdlib-jdk7']),
    ('kotlinx-coroutines', ['org.jetbrains.kotlinx:kotlinx-coroutines-android', 'org.jetbrains.kotlinx:kotlinx-coroutines-core'],
     ['org.jetbrains.kotlinx:kotlinx-coroutines-android', 'org.jetbrains.kotlinx:kotlinx-coroutines-core']),
    ('kotlinx-serialization', ['org.jetbrains.kotlinx:kotlinx-serialization-core'],
     ['org.jetbrains.kotlinx:kotlinx-serialization-core', 'org.jetbrains.kotlinx:kotlinx-serialization-json']),
    ('Compose runtime', ['androidx.compose.runtime:runtime'], ['androidx.compose.runtime:runtime']),
    ('Compose UI', ['androidx.compose.ui:ui'], ['androidx.compose.ui:ui']),
    ('Compose foundation', ['androidx.compose.foundation:foundation'], ['androidx.compose.foundation:foundation']),
    ('Compose material', ['androidx.compose.material:material'], ['androidx.compose.material:material']),
    ('activity-compose', ['androidx.activity:activity-compose'],
     ['androidx.activity:activity-compose', 'androidx.activity:activity', 'androidx.activity:activity-ktx']),
    ('androidx.core', ['androidx.core:core'], ['androidx.core:core', 'androidx.core:core-ktx']),
    ('androidx.lifecycle', ['androidx.lifecycle:lifecycle-runtime'],
     ['androidx.lifecycle:lifecycle-runtime', 'androidx.lifecycle:lifecycle-runtime-ktx']),
    ('androidx.browser', ['androidx.browser:browser'], ['androidx.browser:browser']),
    ('OkHttp', ['com.squareup.okhttp3:okhttp'], ['com.squareup.okhttp3:okhttp', 'com.squareup.okhttp3:logging-interceptor']),
    ('Okio', ['com.squareup.okio:okio'], ['com.squareup.okio:okio']),
    ('Coil', ['io.coil-kt.coil3:coil-compose-core'],
     ['io.coil-kt.coil3:coil-compose-core', 'io.coil-kt.coil3:coil-compose', 'io.coil-kt.coil3:coil']),
    ('Zipline', ['app.cash.zipline:zipline'], ['app.cash.zipline:zipline', 'app.cash.zipline:zipline-loader']),
]
rows = []
for label, mods, declared_as in ROWS:
    m = next((m for m in mods if m in resolved), None)
    if m is None:
        sys.exit('keliver-embed-floor: %s (%s) is not in the resolved classpath' % (label, ' or '.join(mods)))
    rows.append({'name': label, 'module': m, 'version': resolved[m], 'declaredAs': declared_as})
json.dump({
    'generatedBy': 'scripts/keliver-embed-floor.sh',
    'configuration': 'releaseRuntimeClasspath',
    'keliverVersion': keliver,
    'composeVersion': compose,
    'plugins': {'kotlin': kotlin, 'zipline': zipline, 'androidGradlePlugin': agp},
    'compileSdk': int(compile_sdk), 'minSdk': int(min_sdk),
    'floor': rows,
    'resolved': [k + ':' + resolved[k] for k in sorted(resolved)],
}, open(out, 'w'), indent=1)
open(out, 'a').write('\n')
print('dependency floor (%d modules resolved), keliver %s:' % (len(resolved), keliver))
for r in rows:
    print('  %-22s %-12s %s' % (r['name'], r['version'], r['module']))
PY
