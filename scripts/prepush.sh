#!/usr/bin/env bash
# The local checks before a push, in one command (docs/PLAN_2026-11.md
# section 14): so CI rarely fails on something a laptop could have caught.
#
# From what the branch changes against origin/main (commits plus the working
# tree) it runs:
#   - scripts/docs-check.sh (links, tables, hygiene on added lines);
#   - actionlint on changed workflows and shellcheck on changed shell scripts
#     (each only if installed; shellcheck at --severity=error);
#   - for every changed Gradle module: its JVM/JS/Wasm test tasks and apiCheck,
#     whichever it has (iOS simulator tests only with --ios);
#   - :portal-schema-codegen:checkPortalCode when a schema or the codegen
#     changed (CI's "Portal generated code is fresh").
# It does not replace CI's full graph, device jobs or the scaffolder self-tests;
# it prints which of those the change will trigger.
#
# Usage: scripts/prepush.sh [--base <ref>] [--ios] [--dry-run]
# Env:   the usual Gradle environment (JAVA_HOME for JDK 17 etc.); extra Gradle
#        arguments in PREPUSH_GRADLE_ARGS.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

base="" ios=0 dry=0
while [ $# -gt 0 ]; do
  case "$1" in
    --base) base="$2"; shift 2 ;;
    --ios) ios=1; shift ;;
    --dry-run) dry=1; shift ;;
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
    *) echo "prepush: unknown argument $1" >&2; exit 2 ;;
  esac
done
[ -n "$base" ] || base="$(git merge-base origin/main HEAD)"
: "${JAVA_HOME:=$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
export JAVA_HOME

changed="$(
  { git diff --name-only "$base"; git ls-files --others --exclude-standard; } | sort -u
)"
if [ -z "$changed" ]; then echo "prepush: nothing changed against $base"; exit 0; fi
echo "prepush: $(echo "$changed" | wc -l | tr -d ' ') changed file(s) against $(git rev-parse --short "$base")"

failed=()
step(){ echo; echo "=== $*"; }

# 1. docs
step "docs-check"
bash scripts/docs-check.sh --base "$base" || failed+=("docs-check")

# 2. workflows and shell
wf="$(echo "$changed" | grep -E '^\.github/workflows/.*\.ya?ml$' | while read -r f; do [ -f "$f" ] && echo "$f"; done || true)"
if [ -n "$wf" ]; then
  step "actionlint"
  if command -v actionlint >/dev/null 2>&1; then
    # shellcheck disable=SC2086
    actionlint $wf || failed+=("actionlint")
  else echo "  (actionlint not installed: skipped)"; fi
fi
sh_files="$(echo "$changed" | grep -E '\.sh$' | while read -r f; do [ -f "$f" ] && echo "$f"; done || true)"
if [ -n "$sh_files" ]; then
  step "shellcheck --severity=error"
  if command -v shellcheck >/dev/null 2>&1; then
    # shellcheck disable=SC2086
    shellcheck --shell=bash --severity=error $sh_files || failed+=("shellcheck")
  else echo "  (shellcheck not installed: skipped)"; fi
fi

# 3. Gradle modules
modules="$(
  echo "$changed" | awk -F/ 'NF>1 {print $1}' | sort -u | while read -r d; do
    if grep -Eq "^include[ (]+['\"]:${d}['\"]" settings.gradle 2>/dev/null; then echo "$d"; fi
  done
)"
tasks=()
tmp_err="$(mktemp)"; trap 'rm -f "$tmp_err"' EXIT
if [ -n "$modules" ]; then
  step "which test tasks the changed modules have: $(echo "$modules" | tr '\n' ' ')"
  report_args=()
  for m in $modules; do report_args+=(":$m:tasks" "--all"); done
  # A build that cannot even configure is a failure, not "no tests".
  if ! listing="$(./gradlew -q --console=plain "${report_args[@]}" -DRELEASE_SIGNING_ENABLED=false 2>"$tmp_err")"; then  # lint: bash32-ok
    tail -20 "$tmp_err"; failed+=("gradle-configure")
  fi
  # Each report starts with "Tasks runnable from project ':<module>'".
  want="test jvmTest jsTest wasmJsTest apiCheck"
  [ "$ios" = 1 ] && want="$want iosSimulatorArm64Test"
  while read -r t; do [ -n "$t" ] && tasks+=("$t"); done < <(
    echo "$listing" | awk -v want="$want" '
      BEGIN { n = split(want, w, " "); for (i = 1; i <= n; i++) ok[w[i]] = 1 }
      /^Tasks runnable from project / { p = $NF; gsub(/\047/, "", p); next }
      { t = $1; if (p != "" && (t in ok)) print p ":" t }'
  )
fi
# Here-strings, not `echo | grep -q`: under pipefail a SIGPIPE'd echo reads as no match.
if grep -Eq '^(keliver-[^/]*schema[^/]*|portal-schema-codegen)/' <<<"$changed"; then
  tasks+=(":portal-schema-codegen:checkPortalCode")
fi
if grep -Eq '^(build-support[^/]*/|gradle/|build\.gradle$|settings\.gradle$|gradle\.properties$)' <<<"$changed"; then
  echo; echo "NOTE: the root build changed (build-support/, gradle/ or the root build files): run the full build"
  echo "      (./gradlew build apiCheck) or let CI's full graph check it; this script runs only module tests."
fi
if [ "${#tasks[@]}" -gt 0 ]; then  # lint: bash32-ok
  step "gradle ${tasks[*]}"  # lint: bash32-ok
  if [ "$dry" = 1 ]; then echo "  (dry run)"
  else
    # shellcheck disable=SC2086
    ./gradlew --console=plain --continue "${tasks[@]}" -DRELEASE_SIGNING_ENABLED=false ${PREPUSH_GRADLE_ARGS:-} || failed+=("gradle")  # lint: bash32-ok
  fi
fi

# 4. what CI will run beyond this
step "CI jobs this change triggers (not run here)"
grep -Eq '^(scripts/keliver-new-ios-host|scripts/templates/(ios-host|publish/)|reference/inventory/ci/(ios\.sh|ocr\.swift|w[2358]/|m0/)|reference/inventory/app/src/jsMain/kotlin/device/|reference/embed/ios/|scripts/keliver-publish|portal-relay/src/main/kotlin/(StaticIndex|KeliverPublish|PublishSignature|KeyRotation)\.kt|\.github/workflows/ios-host\.yml)' <<<"$changed" \
  && echo "  ios-host (simulator)"
grep -Eq '^(reference/|scripts/templates/(production-host|publish)|scripts/keliver-(new-production-host|new-publish-target|publish|run-android)|portal-relay/src/main/kotlin/(StaticIndex|KeliverPublish|PublishSignature|KeyRotation)\.kt|\.github/workflows/reference-app\.yml)' <<<"$changed" \
  && echo "  reference-app (emulator)"
if grep -Evq '\.md$' <<<"$changed" || grep -Eq '^docs/PORTAL_ADOPTER_GUIDE\.md$' <<<"$changed"; then
  echo "  ci.yml Build (full graph)"
else echo "  ci.yml docs fast path only"; fi

echo
if [ "${#failed[@]}" -gt 0 ]; then echo "prepush: FAILED: ${failed[*]}"; exit 1; fi  # lint: bash32-ok
echo "prepush: clean"
