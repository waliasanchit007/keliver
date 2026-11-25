# shellcheck shell=bash
# shellcheck disable=SC2034 # KELIVER_LOCAL_*, LM_ERROR: set here, read by the scripts that source this
#
# The reference app's local-maven route: SOURCED (never run) by ci/prepare.sh and ci/ios.sh when
# KELIVER_LOCAL_MAVEN names the repository ci/local-maven/publish.sh wrote (this checkout's
# libraries). The other two routes take the libraries from Maven Central, at the app's own version
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
#   lm_load                         reads $KELIVER_LOCAL_MAVEN/keliver-ref-app.env (KELIVER_LOCAL_VERSION,
#                                   KELIVER_LOCAL_COMMIT, KELIVER_LOCAL_SECONDS) and the line's
#                                   toolchain; 1 with LM_ERROR set when it cannot
#   lm_route_label                  one line naming the route, for the results
#   lm_marker                       the line keliver-treehouse-host logs when a host starts
#                                   (HostBuild.kt): what the device checks look for
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
  echo "local-maven: this checkout's scripts/ and libraries (dev.keliver:*:$KELIVER_LOCAL_VERSION from $KELIVER_LOCAL_MAVEN; the $KELIVER_LINE line: $ZIPLINE_GROUP $TOOLCHAIN_ZIPLINE, Kotlin $TOOLCHAIN_KOTLIN)"
}

lm_marker() {
  echo "keliver-treehouse-host $KELIVER_LOCAL_VERSION"
}

lm_move_app() {
  local app="${1:?app dir}" out
  LM_ERROR=""
  out="$(python3 "$LM_HERE/local_maven.py" move-app "$app" "$KELIVER_LOCAL_VERSION" "$KELIVER_LOCAL_MAVEN" \
      "$KELIVER_ZIPLINE_FORK" "$ZIPLINE_GROUP" "$ZIPLINE_PLUGIN_ID" "$TOOLCHAIN_ZIPLINE" "$TOOLCHAIN_KOTLIN" \
      "$TOOLCHAIN_COMPOSE" "$TOOLCHAIN_COROUTINES" "$TOOLCHAIN_SERIALIZATION" 2>&1)" \
    || { LM_ERROR="${out:-local_maven.py move-app failed}"; return 1; }
  # The line's Gradle: AGP 9.3 refuses a Gradle older than 9.5.0.
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
