#!/usr/bin/env bash
#
# Re-runs every "measured" baseline in docs/PERFORMANCE.md against
# the current sample/ build. Output is plain key=value lines so it
# can be diffed across releases or piped into a CI artifact.
#
# Usage:
#   scripts/measure-baselines.sh                # build + measure
#   scripts/measure-baselines.sh --skip-build   # measure existing build artifacts
#   scripts/measure-baselines.sh --cold         # ./gradlew --stop + nuke build/ for cold-build timing
#
# Requirements: configured GH Packages auth (gpr.user / gpr.token in
# ~/.gradle/gradle.properties OR GITHUB_ACTOR / GITHUB_TOKEN env vars).
# See sample/README.md.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SAMPLE_DIR="$REPO_ROOT/sample"

skip_build=0
cold=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) skip_build=1 ;;
    --cold)       cold=1 ;;
    *)
      echo "unknown arg: $arg" >&2
      echo "usage: $0 [--skip-build] [--cold]" >&2
      exit 64
      ;;
  esac
done

cd "$SAMPLE_DIR"

if (( cold )); then
  echo "# cold build — stopping daemon + clearing build/ caches" >&2
  ../gradlew --stop >/dev/null 2>&1 || true
  find . -type d -name build -not -path '*/node_modules/*' -prune -exec rm -rf {} + 2>/dev/null || true
fi

if (( ! skip_build )); then
  echo "# building artifacts (this takes 2-5 min on first run)…" >&2
  ../gradlew \
    :host-android:assembleDebug \
    :host-android:assembleRelease \
    :guest:compileDevelopmentZipline \
    :guest:compileProductionExecutableKotlinJsZipline \
    :host-compose:linkDebugFrameworkIosSimulatorArm64 \
    >/dev/null
fi

# Resolve artifact paths and emit key=value lines.

apk_debug="host-android/build/outputs/apk/debug/host-android-debug.apk"
apk_release="host-android/build/outputs/apk/release/host-android-release-unsigned.apk"
zipline_dev="guest/build/zipline/Development"
zipline_prod="guest/build/zipline/Production"
ios_framework="host-compose/build/bin/iosSimulatorArm64/debugFramework/KonduitSampleHost.framework"

emit() { printf '%s=%s\n' "$1" "$2"; }

# Portable file-size helper. BSD `stat` (macOS) and GNU `stat` (Linux)
# have different flag spellings; abstract over both.
file_bytes() {
  if stat -f '%z' "$1" 2>/dev/null; then return; fi
  stat -c '%s' "$1"
}

# Portable directory-size (cumulative bytes). macOS `du` lacks `-b`,
# so fall back to `find -print0 | xargs wc -c`.
dir_bytes() {
  find "$1" -type f -print0 \
    | xargs -0 wc -c 2>/dev/null \
    | awk 'END{print $1+0}'
}

# --- artifact sizes ------------------------------------------------

[[ -f "$apk_debug" ]]   && emit android.apk.debug.bytes   "$(file_bytes "$apk_debug")"
[[ -f "$apk_release" ]] && emit android.apk.release.bytes "$(file_bytes "$apk_release")"

if [[ -d "$zipline_dev" ]]; then
  emit zipline.development.total.bytes "$(dir_bytes "$zipline_dev")"
fi

if [[ -d "$zipline_prod" ]]; then
  emit zipline.production.total.bytes "$(dir_bytes "$zipline_prod")"

  # konduit-only modules (konduit-konduit-* runtime + konduit-sample-* domain)
  konduit_total=$(find "$zipline_prod" \
      \( -name 'konduit-konduit-*.zipline' -o -name 'konduit-sample-*.zipline' \) \
      -type f -print0 \
    | xargs -0 wc -c 2>/dev/null \
    | awk 'END{print $1+0}')
  emit zipline.production.konduit_owncode.bytes "${konduit_total:-0}"
fi

if [[ -d "$ios_framework" ]]; then
  emit ios.framework.debug.bytes "$(dir_bytes "$ios_framework")"
fi

# --- build time ----------------------------------------------------
# Only meaningful in --cold mode; warm-runs are too cache-dependent
# to publish without per-machine context.

if (( cold )); then
  echo "# timing :host-android:assembleDebug (cold)…" >&2
  start=$(date +%s)
  ../gradlew :host-android:assembleDebug >/dev/null
  emit build.host_android_debug.cold.seconds "$(($(date +%s) - start))"
fi

echo "# done"
