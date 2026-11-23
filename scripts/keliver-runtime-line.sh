# shellcheck shell=bash
# Copyright (C) 2026 Keliver contributors. Licensed under the Apache License, Version 2.0.
#
# keliver-runtime-line.sh: SOURCED (never run) by the scaffolders keliver-init,
# keliver-new-device-target.sh, keliver-new-production-host.sh and keliver-new-ios-host.sh, and by
# keliver-app-wrapper.sh. It sits beside them: in a Keliver checkout's scripts/ and in the tools
# bundle's bin/.
#
# Which Zipline an app builds on follows its Keliver version (appRuntime.keliverVersion), never a
# mix: the fork's modules declare upstream's capabilities, so Gradle refuses a classpath with both.
#  - Before 0.5.0 (the 0.4.x libraries on Maven Central, built against it): upstream Zipline,
#    app.cash.zipline 1.22.0 on Kotlin 2.2.0, QuickJS 2021.
#  - 0.5.0 and later (a 0.5.0 pre-release too): Keliver's Zipline fork (NF2; docs/GUEST_PROFILE.md),
#    dev.keliver.zipline 1.28.0-keliver.1, plugin id dev.keliver.zipline, on Kotlin 2.4.20, QuickJS
#    2026: the one engine, quickjs-2026. Guests link Kotlin's Long as a JavaScript BigInt
#    (-Xes-long-as-bigint), which only that engine runs.
# The rest of an app's toolchain follows the same line: KELIVER_TOOLCHAIN below, the ONE table of
# versions per line. No scaffolder keeps its own copy (docs/DEVICE_HOST.md, "Toolchain per line").
#
#   keliver_zipline_line <keliver version>
# sets KELIVER_ZIPLINE_FORK (true or false), ZIPLINE_GROUP and ZIPLINE_PLUGIN_ID, and TOOLCHAIN_<KEY>
# for every key of the table, from the line's column. Returns 1, and sets nothing, for a version that
# does not start with <major>.<minor>.<patch>.
#
#   keliver_line_wrapper_properties <gradle-wrapper.properties>
# (after keliver_zipline_line) points that file's distributionUrl and distributionSha256Sum at the
# line's Gradle. On the 0.4 line it leaves templates/app-wrapper/wrapper.properties byte-identical.
#
#   keliver_line_image_dependencies <android|ios> <keliver version>
# (after keliver_zipline_line) prints a host's image dependencies, one Gradle line each, for the
# templates' @@IMAGE_DEPENDENCIES@@. Where the line's COIL is '-': keliver-image, which brings Coil,
# its fetcher (OkHttp on Android; Ktor with the Darwin engine on iOS) and okio at the versions
# Keliver's libraries use. Otherwise Coil at COIL (and on iOS Ktor's Darwin engine at KTOR).
#
#   keliver_version_at_least <have> <want>
# true when version <have> is <want> or newer, by its numeric fields (9.7.0 >= 9.5.0, 8.13 < 9.0).

# The toolchain of each line. Key, the 0.4 line (before 0.5.0), the 0.5 line (0.5.0 and later).
# Evidence for the 0.5 line (measured 2026-11-22, docs/DEVICE_HOST.md, "Toolchain per line"):
#  - AGP 9.3.3: androidx.compose.ui/foundation/material 1.12.1 (what Compose Multiplatform 1.12.2
#    resolves to on Android) declare minAndroidGradlePluginVersion=9.1.0 in their AAR metadata; 9.3.3
#    is the AGP Keliver 0.5.0 itself builds with (gradle/libs.versions.toml; 9.3.1 can dex an
#    unverifiable class that crashes at launch, PR #300).
#  - compileSdk 37: those AARs, and the fork's zipline-android and zipline-loader-android, declare
#    minCompileSdk=37. targetSdk is not required by any of them: 36, the level Keliver's sample targets.
#  - Gradle 9.7.1: AGP 9.3 refuses a Gradle older than 9.5.0 (its VersionCheckPlugin's
#    GRADLE_MIN_VERSION; 8.13 for AGP 8.12.0). 9.7.1 is Keliver's own wrapper, sha256 from it.
#  - JDK 17: unchanged (AGP 8 and 9 both run on 17; the host compiles for 17).
#  - Kotlin, Compose Multiplatform, coroutines, serialization, OkHttp: Keliver's version catalog.
#    ZIPLINE: the fork's RELEASE_VERSION. keliver-runtime-line-selftest.sh fails when the newest
#    column disagrees with this checkout's catalog or Gradle wrapper.
#  - COIL and KTOR '-': the line's hosts take Coil, its network fetcher and (iOS) Ktor's Darwin
#    engine from dev.keliver:keliver-image at the app's Keliver version, so they always match what
#    Keliver's own libraries resolve (keliver_line_image_dependencies). The 0.4 line pins them, as
#    its templates always did.
# AGP_MIN is the oldest AGP the line's libraries accept (their AARs' minAndroidGradlePluginVersion;
# '-': not measured for the 0.4 line), for an embedded host, whose AGP is the app's. GRADLE_MIN is the
# line's AGP's own floor, for the warning about an app's older wrapper.
# shellcheck disable=SC2034 # read by keliver_zipline_line below
KELIVER_TOOLCHAIN='
ZIPLINE        1.22.0    1.28.0-keliver.1
KOTLIN         2.2.0     2.4.20
COMPOSE        1.8.2     1.12.2
COROUTINES     1.10.2    1.11.0
SERIALIZATION  1.9.0     1.13.0
OKHTTP         5.1.0     5.5.1
COIL           3.3.0     -
KTOR           3.1.0     -
AGP            8.12.0    9.3.3
AGP_MIN        -         9.1.0
COMPILE_SDK    35        37
TARGET_SDK     35        36
JDK            17        17
GRADLE         9.0.0     9.7.1
GRADLE_SHA256  8fad3d78296ca518113f3d29016617c7f9367dc005f932bd9d93bf45ba46072b acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a
GRADLE_MIN     8.13      9.5.0
'
# The first Keliver version built on the fork.
KELIVER_FORK_SINCE=0.5.0

keliver_zipline_line() {
  local v="${1:-}" major minor patch smajor sminor spatch fork key old new
  [[ "$v" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)([-.+][A-Za-z0-9.+-]*)?$ ]] || return 1
  major=$((10#${BASH_REMATCH[1]})); minor=$((10#${BASH_REMATCH[2]})); patch=$((10#${BASH_REMATCH[3]}))
  [[ "$KELIVER_FORK_SINCE" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || return 1
  smajor=${BASH_REMATCH[1]}; sminor=${BASH_REMATCH[2]}; spatch=${BASH_REMATCH[3]}
  # By major.minor.patch only: 0.5.0-SNAPSHOT or 0.5.0-rc1 is built on the fork as 0.5.0 is.
  if [ "$major" -gt "$smajor" ] \
    || { [ "$major" -eq "$smajor" ] && [ "$minor" -gt "$sminor" ]; } \
    || { [ "$major" -eq "$smajor" ] && [ "$minor" -eq "$sminor" ] && [ "$patch" -ge "$spatch" ]; }; then
    fork=true
  else
    fork=false
  fi
  while read -r key old new; do
    [ -n "$key" ] || continue
    if $fork; then printf -v "TOOLCHAIN_$key" '%s' "$new"; else printf -v "TOOLCHAIN_$key" '%s' "$old"; fi
  done <<< "$KELIVER_TOOLCHAIN"
  KELIVER_ZIPLINE_FORK=$fork
  if $fork; then
    ZIPLINE_GROUP=dev.keliver.zipline
    ZIPLINE_PLUGIN_ID=dev.keliver.zipline
  else
    ZIPLINE_GROUP=app.cash.zipline
    ZIPLINE_PLUGIN_ID=app.cash.zipline
  fi
  return 0
}

keliver_line_wrapper_properties() {
  local f="${1:?gradle-wrapper.properties}" tmp
  [ -n "${TOOLCHAIN_GRADLE:-}" ] && [ -n "${TOOLCHAIN_GRADLE_SHA256:-}" ] || return 1
  [ -f "$f" ] || return 1
  tmp="$f.tmp.$$"
  sed -e "s#^distributionUrl=.*#distributionUrl=https\\\\://services.gradle.org/distributions/gradle-${TOOLCHAIN_GRADLE}-bin.zip#" \
      -e "s#^distributionSha256Sum=.*#distributionSha256Sum=${TOOLCHAIN_GRADLE_SHA256}#" "$f" > "$tmp" \
    && mv "$tmp" "$f" || { rm -f "$tmp"; return 1; }
}

keliver_line_image_dependencies() {
  local platform="${1:-}" kv="${2:-}"
  [ -n "${TOOLCHAIN_COIL:-}" ] && [ -n "$kv" ] || return 1
  if [ "$TOOLCHAIN_COIL" = - ]; then
    case "$platform" in
      android) echo "implementation 'dev.keliver:keliver-image:$kv' // Coil and its OkHttp fetcher, at the versions Keliver's libraries use" ;;
      ios) echo "implementation 'dev.keliver:keliver-image:$kv' // Coil, its Ktor fetcher and Ktor's Darwin engine, at Keliver's versions" ;;
      *) return 1 ;;
    esac
    return 0
  fi
  case "$platform" in
    android)
      echo "implementation 'io.coil-kt.coil3:coil-compose-core:$TOOLCHAIN_COIL'"
      echo "implementation 'io.coil-kt.coil3:coil-network-okhttp:$TOOLCHAIN_COIL'" ;;
    ios)
      [ -n "${TOOLCHAIN_KTOR:-}" ] && [ "$TOOLCHAIN_KTOR" != - ] || return 1
      echo "implementation 'io.coil-kt.coil3:coil-compose-core:$TOOLCHAIN_COIL'"
      echo "implementation 'io.coil-kt.coil3:coil-network-ktor3:$TOOLCHAIN_COIL' // images over the network"
      echo "implementation 'io.ktor:ktor-client-darwin:$TOOLCHAIN_KTOR'           // coil's network engine on iOS" ;;
    *) return 1 ;;
  esac
}

keliver_version_at_least() {
  local have="${1:-}" want="${2:-}" i h w
  local -a hs ws
  IFS=. read -r -a hs <<< "${have%%[-+]*}"; IFS=. read -r -a ws <<< "${want%%[-+]*}"
  for i in 0 1 2; do
    h="${hs[$i]:-0}"; w="${ws[$i]:-0}"
    [[ "$h" =~ ^[0-9]+$ && "$w" =~ ^[0-9]+$ ]] || return 1
    h=$((10#$h)); w=$((10#$w))
    [ "$h" -gt "$w" ] && return 0
    [ "$h" -lt "$w" ] && return 1
  done
  return 0
}
