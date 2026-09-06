#!/usr/bin/env bash
#
# keliver-consumer-smoke — post-publication consumer acceptance check.
#
#   scripts/keliver-consumer-smoke.sh <version> [--scaffolder PATH] [--out DIR]
#
# Answers one question: can a consumer who has never seen this repo resolve
# `dev.keliver:*:<version>` from Maven Central and compile a scaffolded app?
#
# WHY THIS EXISTS, AND WHY THE WEAKER SIGNALS ARE NOT ENOUGH. Publishing
# 0.3.2/0.3.3 produced four signals that each looked conclusive and were each
# true well before a consumer could build anything:
#
#   * the publish workflow reported success        -> before ANY artifact synced
#   * maven-metadata.xml listed <release>0.3.3     -> with 4 of 7 coordinates 404
#   * keliver-host's POM returned 200              -> with 2 of 3 scaffold deps 404
#   * all five scaffold POMs returned 200          -> compilation not yet attempted
#
# POM availability is a PRELIMINARY signal. Successful consumer resolution and
# compilation is the acceptance gate. Upload success and consumer readiness are
# different facts and this script keeps them apart.
#
# Two hazards it defends against, both of which produce a SILENT false pass:
#
#   * KELIVER_USE_MAVEN_LOCAL=1 in the environment makes keliver-init inject
#     mavenLocal() into the generated settings.gradle AT SCAFFOLD TIME. A fresh
#     Gradle cache does not undo that — the repository list itself is wrong, and
#     the check then passes against locally published artifacts while claiming
#     to prove Central resolution. Documenting "must be unset" is not enough;
#     this script strips it with `env -u` and then VERIFIES the generated
#     settings.gradle.
#   * A warm Gradle cache serves a locally published build of the same version.
#     This script always uses a throwaway GRADLE_USER_HOME.
#
set -uo pipefail

VERSION="${1:-}"
shift || true
SCAFFOLDER=""
OUT_DIR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --scaffolder) [ $# -ge 2 ] || { echo "--scaffolder needs a value" >&2; exit 2; }
                  SCAFFOLDER="$2"; shift 2 ;;
    --out)        [ $# -ge 2 ] || { echo "--out needs a value" >&2; exit 2; }
                  OUT_DIR="$2"; shift 2 ;;
    -h|--help)    sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *)            echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

[ -n "$VERSION" ] || { echo "usage: $0 <version> [--scaffolder PATH] [--out DIR]" >&2; exit 2; }
case "$VERSION" in
  *SNAPSHOT*) echo "refusing: $VERSION is a snapshot; Central does not serve it from repo1" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${SCAFFOLDER:=$ROOT/scripts/keliver-init}"
: "${OUT_DIR:=${TMPDIR:-/tmp}/keliver-smoke-$VERSION-$(date +%Y%m%d-%H%M%S)}"

CENTRAL="https://repo1.maven.org/maven2/dev/keliver"
# Every coordinate keliver-init actually generates a dependency on, plus the
# host/guest entry points. keliver-host alone is NOT sufficient: 0.3.3 synced
# host before material-compose and portal-sql.
COORDS=(keliver-host keliver-guest keliver-material-compose keliver-layout-compose portal-sql)

mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/smoke.log"
RESULT="$OUT_DIR/result.txt"
STARTED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

say()  { printf '\n\033[1;35m==> %s\033[0m\n' "$1" | tee -a "$LOG"; }
info() { printf '    %s\n' "$1" | tee -a "$LOG"; }
run()  { printf '    $ %s\n' "$*" | tee -a "$LOG"; "$@" >>"$LOG" 2>&1; }

finish() {
  local code="$1" msg="$2"
  {
    echo "version:   $VERSION"
    echo "started:   $STARTED"
    echo "finished:  $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "scaffolder:$SCAFFOLDER"
    echo "workdir:   $OUT_DIR"
    echo "result:    $msg"
    echo "exit:      $code"
  } > "$RESULT"
  printf '\n%s\n' "$(cat "$RESULT")" | tee -a "$LOG"
  exit "$code"
}

say "keliver consumer smoke — $VERSION"
info "log:     $LOG"
info "workdir: $OUT_DIR"

# ---------------------------------------------------------------- prereqs --
say "prerequisites"
command -v curl >/dev/null || finish 1 "FAIL: curl not found"
[ -x "$SCAFFOLDER" ] || finish 1 "FAIL: scaffolder not executable at $SCAFFOLDER"
info "scaffolder: $SCAFFOLDER"

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  export JAVA_HOME
fi
[ -n "${JAVA_HOME:-}" ] || finish 1 "FAIL: JAVA_HOME unset and no JDK 17 found"
info "JAVA_HOME:  $JAVA_HOME"

# ------------------------------------------------- preliminary: POM checks --
# Preliminary only. Availability is necessary, never sufficient.
say "artifact availability (preliminary signal)"
missing=()
for a in "${COORDS[@]}"; do
  url="$CENTRAL/$a/$VERSION/$a-$VERSION.pom"
  code=""
  for attempt in 1 2 3; do
    code="$(curl -s -o /dev/null -m 30 -w '%{http_code}' "$url" || echo 000)"
    [ "$code" = "200" ] && break
    [ $attempt -lt 3 ] && sleep 10
  done
  printf '    %-28s %s\n' "$a" "$code" | tee -a "$LOG"
  [ "$code" = "200" ] || missing+=("$a")
done
if [ ${#missing[@]} -gt 0 ]; then
  finish 1 "FAIL: not on Central: ${missing[*]}"
fi

# ------------------------------------------------------------- scaffolding --
say "scaffold (KELIVER_USE_MAVEN_LOCAL stripped, version pinned)"
cd "$OUT_DIR"
if ! env -u KELIVER_USE_MAVEN_LOCAL KELIVER_VERSION="$VERSION" \
       "$SCAFFOLDER" Smoke >>"$LOG" 2>&1; then
  finish 1 "FAIL: scaffolder exited nonzero"
fi
APP="$OUT_DIR/smoke"
[ -d "$APP" ] || finish 1 "FAIL: scaffolder produced no app dir at $APP"

# The load-bearing assertion. If this passes while mavenLocal() is present,
# every later step proves nothing.
say "verify generated repository configuration"
if grep -rn "mavenLocal" "$APP" >>"$LOG" 2>&1; then
  finish 1 "FAIL: generated project contains mavenLocal() — resolution would not prove Central"
fi
info "no mavenLocal() in the generated project"
if ! grep -q "dev.keliver:keliver-material-compose:$VERSION" "$APP/build.gradle"; then
  finish 1 "FAIL: generated build.gradle does not pin $VERSION"
fi
info "dependencies pinned to $VERSION"

# ------------------------------------------------------ resolve + compile --
say "resolve and compile from Central (fresh GRADLE_USER_HOME)"
# A UNIQUE cache, not $OUT_DIR/gradle-home. `mkdir -p` accepts an existing
# directory, so re-running with the same --out could reuse previously
# downloaded dependencies while the script still claimed cold resolution from
# Central. mktemp -d cannot collide with a previous run.
GUH="$(mktemp -d "${TMPDIR:-/tmp}/keliver-smoke-cache-XXXXXX")"
[ -z "$(ls -A "$GUH" 2>/dev/null)" ] || finish 1 "FAIL: cache dir $GUH is not empty; refusing to claim cold resolution"
info "GRADLE_USER_HOME: $GUH (fresh, empty)"
cd "$APP"
if ! env -u KELIVER_USE_MAVEN_LOCAL GRADLE_USER_HOME="$GUH" \
       ./gradlew compileKotlinJs --console=plain --no-daemon >>"$LOG" 2>&1; then
  finish 1 "FAIL: compileKotlinJs failed — see $LOG"
fi

# -------------------------------------------------------- output assertion --
# "BUILD SUCCESSFUL" is not proof a compilation happened. Assert the artifact.
say "verify compilation output"
KLIB_DIR="$APP/build/classes/kotlin/js/main"
[ -f "$KLIB_DIR/default/manifest" ] || finish 1 "FAIL: no KLIB manifest at $KLIB_DIR/default/manifest"
IR_FILES="$(find "$KLIB_DIR/default/ir" -type f 2>/dev/null | wc -l | tr -d ' ')"
[ "${IR_FILES:-0}" -gt 0 ] || finish 1 "FAIL: KLIB manifest present but no IR output"
info "KLIB manifest + $IR_FILES IR files"
if ! grep -q "keliver-material-compose" "$KLIB_DIR/default/manifest"; then
  finish 1 "FAIL: KLIB manifest does not depend on keliver-material-compose"
fi
info "manifest resolves the expected dependency graph"

# Independent corroboration that resolution really came from Central: the
# throwaway cache must now hold the artifacts.
CACHED="$(find "$GUH/caches/modules-2/files-2.1/dev.keliver" -maxdepth 2 -type d -name "$VERSION" 2>/dev/null | wc -l | tr -d ' ')"
info "dev.keliver modules downloaded into the fresh cache: ${CACHED:-0}"
[ "${CACHED:-0}" -gt 0 ] || finish 1 "FAIL: no dev.keliver artifacts in the fresh cache — resolution source unproven"

cp "$APP/settings.gradle" "$OUT_DIR/generated-settings.gradle" 2>/dev/null || true
cp "$KLIB_DIR/default/manifest" "$OUT_DIR/klib-manifest" 2>/dev/null || true

finish 0 "PASS: $VERSION resolves from Central and compiles ($CACHED modules cached, $IR_FILES IR files)"
