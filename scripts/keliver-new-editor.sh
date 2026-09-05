#!/bin/bash
# keliver new-editor — stamp a consumer app's OWN portal editor (item ② recipe):
# a STANDALONE gradle build under <app>/editor/ that consumes keliver's
# PUBLISHED portal-editor shell and compiles the app's real presenters into the
# preview wasm (no dynamic linking on Kotlin/Wasm). No keliver source checkout
# and no composite build. Modeled 1:1 on the verified stashfin-sdui/editor.
#
# Usage (from the APP repo root):
#   /path/to/keliver/scripts/keliver-new-editor.sh <AppName> [srcDir...]
#     <AppName>     UpperCamelCase, e.g. Stashfin  -> StashfinPreview / main()
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
# keliver artifacts (2.2.0 / 1.8.2 / 9.0.0 verified).
set -euo pipefail
KELIVER_VERSION="${KELIVER_VERSION:-0.3.2}"
KELIVER="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$(pwd)"
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
  kotlin("multiplatform") version "2.2.0"
  kotlin("plugin.compose") version "2.2.0"
  id("org.jetbrains.compose") version "1.8.2"
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

# Gradle wrapper: reuse the app's (version-aligned), else keliver's.
if [ -f "$APP/gradlew" ]; then SRC="$APP"; else SRC="$KELIVER"; fi
cp "$SRC/gradlew" "$APP/editor/"
mkdir -p "$APP/editor/gradle/wrapper"
cp "$SRC/gradle/wrapper/gradle-wrapper.jar" "$SRC/gradle/wrapper/gradle-wrapper.properties" "$APP/editor/gradle/wrapper/"

echo "scaffolded $APP/editor for $NAME"
echo "next:"
echo "  1. fill ${NAME}Preview.screens (wrap your real presenters)"
echo "  2. cd editor && ./gradlew wasmJsBrowserDistribution"
echo "  3. serve build/dist/wasmJs/productionExecutable; relay: PORTAL_REPO=$APP"
