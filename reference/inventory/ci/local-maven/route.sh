# shellcheck shell=bash
# shellcheck disable=SC2034 # KELIVER_LOCAL_*, LM_ERROR: set here, read by the scripts that source this
#
# The reference app's local-maven route: SOURCED (never run) by ci/prepare.sh and ci/ios.sh when
# KELIVER_LOCAL_MAVEN names the repository ci/local-maven/publish.sh wrote (this checkout's
# libraries), and by publish.sh for the version it publishes them at (lm_publish_version). The
# other two routes take the libraries from Maven Central, at the app's own version
# (reference/inventory/app/keliver.portal.json):
#  - published-only (KELIVER_SCAFFOLD_FROM=zip): the tools zip's scaffolders;
#  - repo-scaffold (KELIVER_SCAFFOLD_FROM=repo): this checkout's scaffolders.
# local-maven is repo-scaffold with this checkout's libraries: the scaffolders are this checkout's,
# and the app, its Android host and its iOS host resolve dev.keliver:* ONLY from the local
# repository (Gradle's exclusiveContent), at the version publish.sh gave it. That version is on the
# line of the next release (0.5.0-local.<commit> is on 0.5), so the app moves to that line's
# toolchain (keliver-runtime-line.sh): Kotlin, Compose, Keliver's Zipline fork, -Xes-long-as-bigint
# and the Gradle wrapper. The edits themselves are local_maven.py's.
#
#   lm_publish_version <checkout> <commit>
#                                   sets LM_PUBLISH_VERSION, the version publish.sh gives the libraries:
#                                   KELIVER_VERSION (RedwoodBuildPlugin.kt) on the line this checkout
#                                   builds, then -local.<commit's first 12>; 1 with LM_ERROR set
#   lm_load                         reads $KELIVER_LOCAL_MAVEN/keliver-ref-app.env (KELIVER_LOCAL_VERSION,
#                                   KELIVER_LOCAL_COMMIT, KELIVER_LOCAL_SECONDS) and the line's
#                                   toolchain; 1 with LM_ERROR set when it cannot
#   lm_route_label                  one line naming the route, for the results
#   lm_marker                       the line keliver-treehouse-host logs when a host starts
#                                   (HostBuild.kt): what the device checks look for
#   lm_publish_target               the publish wiring's scaffolder on this route (this checkout's)
#   lm_relay_kit <dir> <log>        this checkout's keliver-portal and relay, laid out in <dir> (its
#                                   bin/keliver-portal is the route's portal); 1 with LM_ERROR set
#   lm_move_app <app-dir>           moves a bootstrapped app onto KELIVER_LOCAL_VERSION and its line,
#                                   dev.keliver from the local repository only; 1 with LM_ERROR set
#   lm_add_repository <settings.gradle>
#                                   the same repository, first, in a scaffolded host's
#                                   dependencyResolutionManagement; 1 with LM_ERROR set
#   lm_check_artifacts <dependency-report> <out>
#                                   every dev.keliver artifact the report names is at
#                                   KELIVER_LOCAL_VERSION (the list goes to <out>); 1 with LM_ERROR set
LM_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
LM_REPO="$(cd "$LM_HERE/../../../.." && pwd -P)"
LM_ERROR=""

# The version is KELIVER_VERSION, the build's own default (RedwoodBuildPlugin.kt; no CHANGELOG
# heading is read), without a suffix. Its number is only a label; its LINE is what matters, since
# the scaffolders and lm_load give the app that line's toolchain (keliver-runtime-line.sh). So the
# line must be the one this checkout's libraries are built with: the one whose Kotlin and Zipline
# are gradle/libs.versions.toml's. KELIVER_VERSION is the LAST release until a release bumps it
# (0.4.1, on the 0.4 line, while main builds the 0.5 line), so when its line's toolchain is not the
# catalog's the version is the first of the newest line that has it: 0.5.0-local.<commit> today.
lm_publish_version() {
  local root="${1:?checkout}" commit="${2:?commit}" kv kotlin zipline base="" i
  local -a lines
  LM_ERROR=""; LM_PUBLISH_VERSION=""
  kv="$(sed -nE 's/^private const val KELIVER_VERSION = "([^"]+)".*/\1/p' \
    "$root/build-support/src/main/kotlin/dev/keliver/buildsupport/RedwoodBuildPlugin.kt" 2>/dev/null | head -1)"
  kv="${kv%%[-+]*}"
  kotlin="$(sed -nE 's/^kotlin = "([^"]+)".*/\1/p' "$root/gradle/libs.versions.toml" 2>/dev/null | head -1)"
  zipline="$(sed -nE 's/^zipline = "([^"]+)".*/\1/p' "$root/gradle/libs.versions.toml" 2>/dev/null | head -1)"
  if [ -z "$kv" ]; then LM_ERROR="$root names no KELIVER_VERSION in build-support's RedwoodBuildPlugin.kt"; return 1; fi
  if [ -z "$kotlin" ] || [ -z "$zipline" ]; then
    LM_ERROR="$root/gradle/libs.versions.toml names no kotlin or zipline version"; return 1
  fi
  # shellcheck source=../../../../scripts/keliver-runtime-line.sh
  . "$LM_REPO/scripts/keliver-runtime-line.sh"
  if keliver_zipline_line "$kv" 2>/dev/null && [ "$TOOLCHAIN_KOTLIN" = "$kotlin" ] && [ "$TOOLCHAIN_ZIPLINE" = "$zipline" ]; then
    base="$kv"
  else
    read -r -a lines <<< "$KELIVER_LINES"
    for (( i = ${#lines[@]} - 1; i >= 0; i-- )); do
      if keliver_zipline_line "${lines[$i]}.0" 2>/dev/null && [ "$TOOLCHAIN_KOTLIN" = "$kotlin" ] \
          && [ "$TOOLCHAIN_ZIPLINE" = "$zipline" ]; then
        base="${lines[$i]}.0"; break
      fi
    done
  fi
  if [ -z "$base" ]; then
    LM_ERROR="this checkout builds with Kotlin $kotlin and Zipline $zipline, and no line of scripts/keliver-runtime-line.sh ($KELIVER_LINES) has them: add the next line's column"
    return 1
  fi
  LM_PUBLISH_VERSION="$base-local.${commit:0:12}"
  return 0
}

lm_load() {
  local env="${KELIVER_LOCAL_MAVEN:-}/keliver-ref-app.env"
  LM_ERROR=""
  if [ -z "${KELIVER_LOCAL_MAVEN:-}" ] || [ ! -f "$env" ]; then
    LM_ERROR="KELIVER_LOCAL_MAVEN (${KELIVER_LOCAL_MAVEN:-unset}) has no keliver-ref-app.env: run ci/local-maven/publish.sh first"
    return 1
  fi
  KELIVER_LOCAL_VERSION="$(sed -n 's/^KELIVER_LOCAL_VERSION=//p' "$env")"
  KELIVER_LOCAL_COMMIT="$(sed -n 's/^KELIVER_LOCAL_COMMIT=//p' "$env")"
  KELIVER_LOCAL_SECONDS="$(sed -n 's/^KELIVER_LOCAL_SECONDS=//p' "$env")"
  [ -n "$KELIVER_LOCAL_VERSION" ] || { LM_ERROR="$env names no KELIVER_LOCAL_VERSION"; return 1; }
  # shellcheck source=../../../../scripts/keliver-runtime-line.sh
  . "$LM_REPO/scripts/keliver-runtime-line.sh"
  keliver_zipline_line "$KELIVER_LOCAL_VERSION" || { LM_ERROR="$KELIVER_LINE_ERROR"; return 1; }
  return 0
}

lm_route_label() {
  echo "local-maven: this checkout's scripts/, relay (portal-relay) and libraries (dev.keliver:*:$KELIVER_LOCAL_VERSION from $KELIVER_LOCAL_MAVEN; the $KELIVER_LINE line: $ZIPLINE_GROUP $TOOLCHAIN_ZIPLINE, Kotlin $TOOLCHAIN_KOTLIN)"
}

lm_marker() {
  echo "keliver-treehouse-host $KELIVER_LOCAL_VERSION"
}

# The publish wiring's scaffolder on this route: this checkout's. The published 0.3.18 zip's
# keliver-new-publish-target.sh accepts only upstream Zipline's plugin (app.cash.zipline) and
# refuses an app on the fork's line.
lm_publish_target() {
  echo "$LM_REPO/scripts/keliver-new-publish-target.sh"
}

# The relay on this route: this checkout's keliver-portal and portal-relay, laid out as the tools
# bundle lays them out (bin/, relay/, editor/) in <dir>. The published 0.3.18 relay predates NF2:
# its bundles/index.json names no engine, which a host on the fork's line (quickjs-2026) reads as
# quickjs-2021 and skips (BundleIndex.kt's DEFAULT_ENGINE), so nothing would load. This relay
# copies the signed keliver.engine into the index. No editor is built: editor/ holds a placeholder
# page (the harness starts the portal with --no-editor-build). Gradle runs without a daemon, so
# nothing of it stays in memory for the emulator or the simulator.
lm_relay_kit() {
  local d="${1:?dir}" log="${2:?log}"
  LM_ERROR=""
  ( cd "$LM_REPO" && ./gradlew --console=plain --no-daemon -q :portal-relay:installDist ) > "$log" 2>&1 \
    || { LM_ERROR="this checkout's portal-relay did not build (:portal-relay:installDist, $log)"; return 1; }
  rm -rf "$d" && mkdir -p "$d/bin" "$d/editor" \
    && cp -R "$LM_REPO/portal-relay/build/install/portal-relay" "$d/relay" \
    && cp "$LM_REPO/scripts/keliver-portal" "$LM_REPO/scripts/keliver-store-path.sh" "$LM_REPO/scripts/keliver-publish" "$d/bin/" \
    && chmod +x "$d/bin/keliver-portal" "$d/bin/keliver-store-path.sh" "$d/bin/keliver-publish" \
    && printf '<!doctype html><title>keliver</title><p>No editor on the local-maven route.</p>\n' > "$d/editor/index.html" \
    || { LM_ERROR="could not lay out the relay kit in $d"; return 1; }
  [ -x "$d/relay/bin/portal-relay" ] || { LM_ERROR="$d has no relay/bin/portal-relay"; return 1; }
  return 0
}

lm_move_app() {
  local app="${1:?app dir}" out
  LM_ERROR=""
  out="$(python3 "$LM_HERE/local_maven.py" move-app "$app" "$KELIVER_LOCAL_VERSION" "$KELIVER_LOCAL_MAVEN" \
      "$KELIVER_ZIPLINE_FORK" "$ZIPLINE_GROUP" "$ZIPLINE_PLUGIN_ID" "$TOOLCHAIN_ZIPLINE" "$TOOLCHAIN_KOTLIN" \
      "$TOOLCHAIN_COMPOSE" "$TOOLCHAIN_COROUTINES" "$TOOLCHAIN_SERIALIZATION" 2>&1)" \
    || { LM_ERROR="${out:-local_maven.py move-app failed}"; return 1; }
  # The line's Gradle: the line's AGP (TOOLCHAIN_AGP, 9.3.3 on 0.5) refuses a Gradle older than
  # TOOLCHAIN_GRADLE_MIN (9.5.0).
  keliver_line_wrapper_properties "$app/gradle/wrapper/gradle-wrapper.properties" \
    || { LM_ERROR="could not point the app's wrapper at Gradle $TOOLCHAIN_GRADLE"; return 1; }
  return 0
}

lm_add_repository() {
  local f="${1:?settings.gradle}" out
  LM_ERROR=""
  out="$(python3 "$LM_HERE/local_maven.py" add-repo "$f" "$KELIVER_LOCAL_MAVEN" 2>&1)" \
    || { LM_ERROR="${out:-local_maven.py add-repo failed}"; return 1; }
  return 0
}

lm_check_artifacts() {
  local report="${1:?dependency report}" out="${2:?out}" others
  LM_ERROR=""
  grep -oE 'dev\.keliver:[a-z0-9-]+:[0-9][0-9A-Za-z.+-]*' "$report" | sort -u > "$out"
  [ -s "$out" ] || { LM_ERROR="the dependency report names no dev.keliver artifact"; return 1; }
  others="$(grep -v ":$KELIVER_LOCAL_VERSION\$" "$out" || true)"
  [ -z "$others" ] || { LM_ERROR="not at $KELIVER_LOCAL_VERSION: $(echo "$others" | tr '\n' ' ')"; return 1; }
  return 0
}
