#!/usr/bin/env bash
#
# keliver release preflight — everything that must be green before an
# IRREVERSIBLE publish to Maven Central.
#
#   scripts/keliver-release-preflight.sh [version]
#
# Version defaults to KELIVER_VERSION in RedwoodBuildPlugin.kt. Pass one
# explicitly to preflight a candidate before bumping the constant.
#
# This script is the single definition of the release gate: run it locally
# before tagging, and the publish workflow runs THIS FILE as its preflight job
# body. Do not fork the task list into the workflow — two copies drift, and the
# copy that drifts is the one guarding an irreversible operation.
#
# Environment overrides:
#   PREFLIGHT_ALLOW_DIRTY=1   skip the clean-tree check (local iteration only)
#   PREFLIGHT_SKIP_CONSUMER=1 skip the zero-checkout consumer proof
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# The repo requires Java 17. Do not preserve an inherited JAVA_HOME blindly:
# Android Studio shells commonly export JDK 11, which lets the preflight start
# but makes Gradle fail before the first real gate.
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"

VERSION_CONST_FILE="build-support/src/main/kotlin/dev/keliver/buildsupport/RedwoodBuildPlugin.kt"
VERSION="${1:-$(grep 'KELIVER_VERSION' "$VERSION_CONST_FILE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')}"

# The gradle-plugin lint fixtures (FixtureTest > lintMpp*) spin up a sub-build
# containing an Android module, which needs an SDK. GitHub's macOS runners
# pre-set ANDROID_HOME; a local shell usually does not, and the resulting
# failure ("SDK location not found") surfaces inside a nested TestKit build two
# minutes into the run, which reads like a real regression. Detect it, and fail
# immediately and legibly if we can't.
if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /usr/local/share/android-sdk; do
    [ -d "$candidate" ] && { export ANDROID_HOME="$candidate"; break; }
  done
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  echo "ERROR: ANDROID_HOME is unset and no SDK found in the usual locations." >&2
  echo "       :keliver-gradle-plugin:test's lintMpp* fixtures need it." >&2
  exit 1
fi

# Same class of trap, different cause. On a network with a TLS-inspecting proxy
# (Netskope/Zscaler), the FixtureTest sub-builds fetch their own Kotlin plugin
# from Maven Central in a FRESH JVM that does not inherit the outer build's
# already-warm Gradle cache. They fail with
#   PKIX path building failed: unable to find valid certification path
# and it surfaces as a dozen failing :keliver-gradle-plugin:test cases — which
# reads like a real regression in the plugin, not a certificate problem. The
# outer build can be perfectly green because everything it needs is cached.
# Probe an uncached coordinate directly and say so up front.
if ! curl -sf -m 15 -o /dev/null https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml 2>/dev/null; then
  echo "WARNING: cannot reach Maven Central over HTTPS — the fixture sub-builds will fail." >&2
elif [ -z "${JAVA_TOOL_OPTIONS:-}" ] && ! "$JAVA_HOME/bin/keytool" -list \
       -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit >/dev/null 2>&1; then
  echo "WARNING: default JDK truststore unreadable; if you are behind a TLS-inspecting" >&2
  echo "         proxy, export JAVA_TOOL_OPTIONS with -Djavax.net.ssl.trustStore=… so the" >&2
  echo "         nested TestKit builds trust it. See docs/CI_RUNNER_SETUP.md." >&2
fi

# Every gradle invocation goes through this. --console=plain is not cosmetic:
# the rich console collapses `e:` compiler lines and has produced false greens
# in this repo. `set -e` plus gradle's exit code is what actually gates.
#
# -PkeliverVersion is passed to EVERY invocation, not just the publish: mixing
# versions across steps re-stamps jars/poms between them, and would mean the
# tests ran against a different version than the one staged for the consumer
# proof.
run_gradle() {
  local label="$1"; shift
  echo
  echo "==> $label"
  ./gradlew --console=plain \
    -DRELEASE_SIGNING_ENABLED=false \
    -PkeliverVersion="$VERSION" \
    "$@"
}

echo "=============================================="
echo " keliver release preflight — version $VERSION"
echo " java:    $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
echo " android: $ANDROID_HOME"
echo "=============================================="

# ---------------------------------------------------------------- 0. clean tree
# A release must be reproducible from the tagged ref. Uncommitted work means
# what you validated is not what gets published.
if [ "${PREFLIGHT_ALLOW_DIRTY:-0}" != "1" ]; then
  if [ -n "$(git status --porcelain)" ]; then
    echo "ERROR: working tree is dirty. Commit or stash first," >&2
    echo "       or set PREFLIGHT_ALLOW_DIRTY=1 for local iteration." >&2
    git status --short >&2
    exit 1
  fi
  echo "==> working tree clean"
fi

# ------------------------------------------------------- 1. build + stage local
# Signing is a -D system property, NOT a -P project property. Getting this wrong
# fails late, inside the publish task.
run_gradle "build + publishToMavenLocal @ $VERSION" \
  publishToMavenLocal \
  -x test -x allTests

# --------------------------------------------------------- 2. codegen freshness
# The committed GeneratedCatalog/GeneratedExporter/GeneratedRenderNode must match
# what the schemas produce. If this fails: regenerate with
# `./gradlew :portal-schema-codegen:generatePortalCode` and commit. Never
# hand-edit generated output — fix the emitter.
run_gradle "portal generated code is fresh" \
  :portal-schema-codegen:checkPortalCode

# ------------------------------------------------------------ 3. tests + api
run_gradle "test + apiCheck" \
  test apiCheck

# Multiplatform portal modules register jsTest/wasmJsTest/jvmTest and NO `test`,
# so the aggregate above skips them entirely. Keep this list in sync with the
# "Test (portal multiplatform modules)" step in ci.yml. portal-render's
# wasmJsTest is the K1 preview-vs-device parity gate.
run_gradle "portal multiplatform tests (incl. K1 parity)" \
  :portal-render:wasmJsTest \
  :portal-render:jsTest \
  :portal-document:jvmTest \
  :portal-document:jsTest \
  :portal-editor:wasmJsTest \
  :portal-app-lib:wasmJsTest \
  :portal-sql:jvmTest

# ------------------------------------------------------------- 4. wasm render
# publishToMavenLocal compiles each library's wasmJs klib, but not the generic
# web host that transitively links the whole render chain.
run_gradle "compile Compose-for-Web (Wasm) host" \
  :web-spike:compileKotlinWasmJs

# --------------------------------------------------- 5. zero-checkout consumer
# The claim the release makes is "an adopter with no keliver checkout can build
# both a guest and its portal editor against these coordinates." Prove it
# against the CANDIDATE staged in mavenLocal, not against whatever is already
# on Central. The full editor distribution links portal-editor and its five new
# transitive artifacts; keliver-new-editor also depends on the deliberately
# standalone portal-flow, so this exercises all seven 0.3.1 additions.
if [ "${PREFLIGHT_SKIP_CONSUMER:-0}" != "1" ]; then
  CONSUMER_DIR="$(mktemp -d)/preflight"
  trap 'rm -rf "$(dirname "$CONSUMER_DIR")"' EXIT

  echo
  echo "==> zero-checkout consumer proof @ $VERSION"
  KELIVER_USE_MAVEN_LOCAL=1 KELIVER_VERSION="$VERSION" \
    scripts/keliver-init Preflight "$CONSUMER_DIR"

  ( cd "$CONSUMER_DIR" && ./gradlew --console=plain compileKotlinJs )

  (
    cd "$CONSUMER_DIR"
    KELIVER_USE_MAVEN_LOCAL=1 KELIVER_VERSION="$VERSION" \
      "$ROOT/scripts/keliver-new-editor.sh" Preflight
  )
  if grep -Rqs 'includeBuild' "$CONSUMER_DIR/editor"; then
    echo "ERROR: generated editor uses a composite build; consumer proof is not zero-checkout." >&2
    exit 1
  fi
  DEPENDENCY_REPORT="$CONSUMER_DIR/editor-dependencies.txt"
  (
    cd "$CONSUMER_DIR/editor"
    ./gradlew --console=plain dependencies --configuration wasmJsCompileClasspath
  ) | tee "$DEPENDENCY_REPORT"
  for artifact in \
    portal-core \
    portal-document \
    portal-render \
    portal-flow \
    portal-editor \
    keliver-material-protocol-host-web \
    keliver-material-protocol-guest-web
  do
    if ! grep -Fq "dev.keliver:$artifact:$VERSION" "$DEPENDENCY_REPORT"; then
      echo "ERROR: candidate editor graph did not resolve dev.keliver:$artifact:$VERSION." >&2
      exit 1
    fi
  done
  (
    cd "$CONSUMER_DIR/editor"
    ./gradlew --console=plain wasmJsBrowserDistribution
  )
  test -f "$CONSUMER_DIR/editor/build/dist/wasmJs/productionExecutable/preflight-editor.js"
  echo "==> guest + full editor distribution linked against dev.keliver:*:$VERSION"
fi

echo
echo "=============================================="
echo " PREFLIGHT GREEN — $VERSION"
echo "=============================================="
echo
case "$VERSION" in
  *-SNAPSHOT)
    echo "$VERSION is a SNAPSHOT — this validated the build, not a release."
    echo "To preflight an actual release, bump KELIVER_VERSION in"
    echo "$VERSION_CONST_FILE and re-run with that version."
    ;;
  *)
    echo "Publishing to Maven Central is IRREVERSIBLE and stays user-triggered."
    echo "Next, if you intend to release:"
    echo "  1. Ensure KELIVER_VERSION in RedwoodBuildPlugin.kt == $VERSION"
    echo "  2. git tag v$VERSION && git push origin v$VERSION"
    echo "  3. GH_REPO=waliasanchit007/keliver gh workflow run \\"
    echo "       publish-maven-central.yml -f ref=v$VERSION"
    ;;
esac
