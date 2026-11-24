#!/bin/bash
# keliver new-editor — stamp a consumer app's OWN portal editor (item ② recipe):
# a STANDALONE gradle build under <app>/editor/ that consumes keliver's
# PUBLISHED portal-editor shell and compiles the app's real presenters into the
# preview wasm (no dynamic linking on Kotlin/Wasm). No keliver source checkout
# and no composite build.
#
# Usage (from the APP repo root):
#   /path/to/keliver/scripts/keliver-new-editor.sh <AppName> [srcDir...]
#     <AppName>     UpperCamelCase, e.g. Acme  -> AcmePreview / main()
#     [srcDir...]   optional app source dirs compiled straight into the editor
#                   via kotlin.srcDir — e.g.
#                   guest/src/jsMain/kotlin/com/acme/guest/logic
#
#                   Pass every dir the presenters need to COMPILE, not just the
#                   presenters. A presenter returns its screen's Bindings
#                   interface, and in the keliver-init layout that interface is
#                   declared in screens/<name>.kt alongside the widget-using
#                   composable — so a logic-only editor fails with
#                   "Unresolved reference 'screens'" / "overrides nothing".
#                   A sibling screens/ dir is therefore added automatically.
#
# Set KELIVER_USE_MAVEN_LOCAL=1 only when validating an unpublished candidate.
# Normal adopters resolve exclusively from Maven Central.
#
# Requires: the app on the SAME Kotlin/Compose/Gradle versions as the consumed
# keliver artifacts. Only the 0.4 line is supported (Kotlin 2.2.0 / Compose 1.8.2 /
# Gradle 9.0.0 verified); an app on another line of keliver-runtime-line.sh's
# table is refused until the 0.5 editor ships (docs/DEVICE_HOST.md).
set -euo pipefail
KELIVER_VERSION_GIVEN="${KELIVER_VERSION:+yes}"
KELIVER_VERSION="${KELIVER_VERSION:-}" # default: the app's appRuntime.keliverVersion, below (no fixed fallback)
KELIVER="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$(pwd)"
# The app's own Keliver version (appRuntime.keliverVersion) unless
# KELIVER_VERSION is set, so an app on an older release gets a matching editor.
# A keliver.portal.json that is missing, cannot be read or parsed, or names no
# appRuntime.keliverVersion is refused (as the device and host scaffolders
# refuse it) rather than read as some default version.
if [ -z "$KELIVER_VERSION_GIVEN" ]; then
  [ -f "$APP/keliver.portal.json" ] || { echo "error: no keliver.portal.json here, so no Keliver version to build the editor against. Run from the app root, or set KELIVER_VERSION." >&2; exit 1; }
  KELIVER_VERSION="$(python3 -c "import json,sys
try:
    d = json.load(open(sys.argv[1], encoding='utf-8'))
except Exception as e:
    print('ERROR keliver.portal.json cannot be read or parsed (%s: %s). Fix it, or set KELIVER_VERSION.' % (type(e).__name__, e)); sys.exit(3)
v = d.get('appRuntime', {}).get('keliverVersion') if isinstance(d, dict) and isinstance(d.get('appRuntime'), dict) else None
if not isinstance(v, str) or not v.strip():
    print('ERROR keliver.portal.json names no appRuntime.keliverVersion, which picks the editor\'s Keliver libraries. Add it (as keliver-init writes it), or set KELIVER_VERSION.'); sys.exit(3)
print(v.strip())" "$APP/keliver.portal.json")" || { echo "error: ${KELIVER_VERSION#ERROR }" >&2; exit 1; }
fi
[[ "$KELIVER_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9.]+)?$ ]] || { echo "the Keliver version '$KELIVER_VERSION' is not a version" >&2; exit 1; }
# The version's line (keliver-runtime-line.sh): a version past the newest line this bundle knows is
# refused there. The editor's own Kotlin and Compose are still the 0.4 line's (below), not the table's:
# a 0.5 editor needs portal-editor 0.5.0 on Maven Central to be checked against. Until it ships, any
# other line is refused here, because Kotlin 2.2.0 cannot read the newer libraries' metadata.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$HERE/keliver-runtime-line.sh" ] || { echo "error: keliver-runtime-line.sh is missing beside this script ($HERE)" >&2; exit 1; }
# shellcheck source=keliver-runtime-line.sh
. "$HERE/keliver-runtime-line.sh"
keliver_zipline_line "$KELIVER_VERSION" || { echo "error: $KELIVER_LINE_ERROR" >&2; exit 1; }
EDITOR_LINE=0.4 EDITOR_KOTLIN=2.2.0 EDITOR_COMPOSE=1.8.2
if [ "$KELIVER_LINE" != "$EDITOR_LINE" ]; then
  echo "error: Keliver $KELIVER_VERSION is on the $KELIVER_LINE line; the editor supports $EDITOR_LINE apps until the $KELIVER_LINE editor ships (it builds on Kotlin $EDITOR_KOTLIN and Compose $EDITOR_COMPOSE, which cannot compile against $KELIVER_LINE libraries). See docs/DEVICE_HOST.md, \"Toolchain per line\"." >&2
  exit 1
fi
NAME="${1:?usage: keliver-new-editor.sh <AppName> [srcDir...]}"
shift || true
SRC_DIRS=("$@")
[[ "$NAME" =~ ^[A-Z][A-Za-z0-9]*$ ]] || { echo "AppName must be UpperCamelCase (got: $NAME)"; exit 1; }

# A presenter's return type is its screen's Bindings interface, which the
# keliver-init layout declares in screens/ next to the composable. Compiling
# logic/ alone therefore fails. Pull in the sibling screens/ dir when it exists.
for d in "${SRC_DIRS[@]:-}"; do
  [ -n "$d" ] || continue
  case "$d" in */logic|logic) ;; *) continue ;; esac
  sib="$(dirname "$d")/screens"; [ "$sib" = "./screens" ] && sib="screens"
  [ -d "$APP/$sib" ] || continue
  already=false
  for e in "${SRC_DIRS[@]}"; do [ "$e" = "$sib" ] && already=true; done
  $already || { SRC_DIRS+=("$sib"); echo "note: also compiling $sib (it declares the Bindings contracts your presenters return)"; }
done

# Any app source dir may reference the widget libraries, so the editor needs
# them on its compile classpath — portal-editor does not bring them in.
APP_SRC_DEPS=""
if [ "${#SRC_DIRS[@]}" -gt 0 ] && [ -n "${SRC_DIRS[0]:-}" ]; then
  APP_SRC_DEPS=$(printf '        implementation("dev.keliver:keliver-material-compose:%s")\n        implementation("dev.keliver:keliver-layout-compose:%s")\n' "$KELIVER_VERSION" "$KELIVER_VERSION")
fi
[ -f "$APP/keliver.portal.json" ] || echo "note: no keliver.portal.json here — the relay will use defaults"
[ -e "$APP/editor" ] && { echo "refusing to overwrite $APP/editor"; exit 1; }

MAVEN_LOCAL=""
if [ "${KELIVER_USE_MAVEN_LOCAL:-0}" = "1" ]; then
  MAVEN_LOCAL="mavenLocal(); "
  echo "resolving keliver from mavenLocal first (KELIVER_USE_MAVEN_LOCAL=1)"
fi

LOWER="$(echo "$NAME" | tr '[:upper:]' '[:lower:]')"
mkdir -p "$APP/editor/src/wasmJsMain/kotlin" "$APP/editor/src/wasmJsMain/resources"

cat > "$APP/editor/settings.gradle.kts" <<EOF
/*
 * $NAME's portal editor — a STANDALONE build consuming keliver's PUBLISHED
 * portal-editor shell. No keliver checkout, no composite build. Keep
 * Kotlin/Compose/Gradle aligned with the keliver artifacts you consume.
 */
rootProject.name = "$LOWER-editor"

pluginManagement {
  repositories {
    ${MAVEN_LOCAL}
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") } }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    ${MAVEN_LOCAL}
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") } }
    mavenCentral()
  }
}

EOF

cat > "$APP/editor/build.gradle.kts" <<EOF
/*
 * Build:  ./gradlew wasmJsBrowserDistribution   (add --rerun-tasks if build/dist
 *         stays empty — known webpack task-skip gotcha)
 * Serve:  build/dist/wasmJs/productionExecutable behind any static server;
 *         point it at your relay with ?relay=<port> (default 8077).
 */
plugins {
  kotlin("multiplatform") version "$EDITOR_KOTLIN"
  kotlin("plugin.compose") version "$EDITOR_KOTLIN"
  id("org.jetbrains.compose") version "$EDITOR_COMPOSE"
}

kotlin {
  wasmJs {
    browser { commonWebpackConfig { outputFileName = "$LOWER-editor.js" } }
    binaries.executable()
  }
  sourceSets {
    val wasmJsMain by getting {
$( for d in "${SRC_DIRS[@]:-}"; do [ -n "$d" ] && printf '      kotlin.srcDir("../%s")\n' "$d"; done )
      dependencies {
$( [ -n "$APP_SRC_DEPS" ] && printf '%s' "$APP_SRC_DEPS" )
        implementation("dev.keliver:portal-editor:$KELIVER_VERSION")
        // Apps compile flow{} declarations into the editor alongside their
        // presenters. portal-flow is deliberately independent of the shell.
        implementation("dev.keliver:portal-flow:$KELIVER_VERSION")
        implementation(compose.runtime)
      }
    }
  }
}
EOF

cat > "$APP/editor/src/wasmJsMain/kotlin/${NAME}Preview.kt" <<EOF
/*
 * $NAME's per-app preview entry: map each portal screen name (the .kt basename
 * the relay ingests) to a ScreenPreview running the REAL presenter. See
 * keliver docs/ROADMAP.md item ② + docs/SCREEN_ARCHITECTURE.md §8.
 */
import dev.keliver.portal.render.AppPreviewEntry
import dev.keliver.portal.render.PreviewFrame
import dev.keliver.portal.render.ScreenPreview

object ${NAME}Preview : AppPreviewEntry {
  override val label = "$LOWER"

  override val screens: Map<String, ScreenPreview> = mapOf(
    // "MyScreen" to ScreenPreview { env ->
    //   val b = MyPresenter(onAction = { env.log("→ \$it") })
    //   PreviewFrame(
    //     values = mapOf("field" to b.field),           // + putRows(...) for lists
    //     dispatch = { action, arg -> /* route to b.* */ },
    //   )
    // },
  )

  // Canvas fidelity: put each screen inside what your app puts around it on a
  // device, so a Theme the app applies outside the screen file shows here too.
  // override val screenWrapper = dev.keliver.portal.render.PreviewWrapper { content -> AppTheme { content() } } // guest side: your keliver Theme
  // override val hostWrapper = dev.keliver.portal.render.PreviewWrapper { content -> MaterialTheme(appColorScheme) { content() } } // your native host's scheme

  // Compiled component preview (needs Keliver libraries >= 0.4.0 and tools
  // >= 0.3.12): draw OPAQUE components with your compiled code instead of a
  // placeholder. The components must compile into this editor: pass their dir
  // to keliver-new-editor.sh (or add it to kotlin.srcDir in build.gradle.kts).
  // Add to keliver.portal.json
  //   "compiledComponents": {"out": "editor/src/wasmJsMain/kotlin/AppCompiledComponents.kt", "sameModule": true}
  // (keliver-portal writes that file before it builds this editor; restart it
  // after adding, removing or changing the signature of a component, or when
  // one becomes opaque or transparent), then:
  // override val compiledComponents = AppCompiledComponents
}
EOF

cat > "$APP/editor/src/wasmJsMain/kotlin/Main.kt" <<EOF
// $NAME's portal-editor executable = keliver's shell + this app's entries.
// Flows (#13): fun main() = runPortalEditor(${NAME}Preview, flows = ${NAME}Flows)
fun main() = runPortalEditor(${NAME}Preview)
EOF

cat > "$APP/editor/src/wasmJsMain/resources/index.html" <<EOF
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>$NAME — portal editor</title>
  <style>
    html, body { margin: 0; height: 100%; background: #f5f6f8; }
    #ComposeTarget { width: 420px; height: 320px; display: block; margin: 40px auto; }
  </style>
</head>
<body>
  <canvas id="ComposeTarget"></canvas>
  <script src="$LOWER-editor.js"></script>
</body>
</html>
EOF

cat > "$APP/editor/gradle.properties" <<EOF
# The production wasm compile (whole Compose/skiko graph) OOMs with
# "GC overhead limit exceeded" on the JVM default heap.
org.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8
org.gradle.caching=true
EOF

printf 'build/\n.gradle/\n' > "$APP/editor/.gitignore"

# Gradle wrapper: reuse the app's (version-aligned), else the one for the
# toolchain this template pins (scripts/templates/app-wrapper), never the
# checkout's own build wrapper.
if [ -f "$APP/gradlew" ]; then
  cp "$APP/gradlew" "$APP/editor/"
  mkdir -p "$APP/editor/gradle/wrapper"
  cp "$APP/gradle/wrapper/gradle-wrapper.jar" "$APP/gradle/wrapper/gradle-wrapper.properties" "$APP/editor/gradle/wrapper/"
else
  bash "$KELIVER/scripts/keliver-app-wrapper.sh" "$APP/editor"
fi

echo "scaffolded $APP/editor for $NAME"
echo "next:"
echo "  1. fill ${NAME}Preview.screens (wrap your real presenters)"
echo "  2. cd editor && ./gradlew wasmJsBrowserDistribution"
echo "  3. serve build/dist/wasmJs/productionExecutable; relay: PORTAL_REPO=$APP"
