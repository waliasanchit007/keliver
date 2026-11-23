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
#  - The 0.5 line (a 0.5.0 pre-release too): Keliver's Zipline fork (NF2; docs/GUEST_PROFILE.md),
#    dev.keliver.zipline 1.28.0-keliver.1, plugin id dev.keliver.zipline, on Kotlin 2.4.21, QuickJS
#    2026: the one engine, quickjs-2026. Guests link Kotlin's Long as a JavaScript BigInt
#    (-Xes-long-as-bigint), which only that engine runs.
# The rest of an app's toolchain follows the same line: KELIVER_TOOLCHAIN below, the ONE table of
# versions per line. No scaffolder keeps its own copy (docs/DEVICE_HOST.md, "Toolchain per line").
#
#   keliver_zipline_line <keliver version>
# picks the version's line: the newest of KELIVER_LINES whose <major>.<minor> is not newer than the
# version's (a version older than the oldest line takes the oldest: 0.3.x builds as 0.4.x does). It
# sets KELIVER_LINE (e.g. 0.5), KELIVER_ZIPLINE_FORK (true or false), ZIPLINE_GROUP and
# ZIPLINE_PLUGIN_ID, and TOOLCHAIN_<KEY> for every key of the table, from the line's column.
# A version past the newest line is REFUSED: this bundle cannot know that line's toolchain, and a
# guess would give the app an older line's Kotlin and Zipline against newer libraries. The one
# exception is a -SNAPSHOT of the minor right after the newest line (0.6.0-SNAPSHOT while 0.5 is the
# newest), which a checkout builds before its line is added: it takes the newest line, with a warning
# on stderr (also in KELIVER_LINE_WARNING). On refusal it returns 1 and sets KELIVER_LINE_ERROR (the
# message for the caller to print) and nothing else.
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

# The toolchain of each line: one row per key, one column per line, the lines named in the LINE row
# (oldest first; KELIVER_LINES is read from it). A line is a <major>.<minor>, and every Keliver
# version on it builds with its column. Once a version on a line is released its column does not
# change: a new toolchain is a new column. keliver-runtime-line-selftest.sh compares the column of
# the version this checkout builds next (CHANGELOG.md's "Unreleased (X.Y.Z)") with its catalog.
#  - 0.4 (and older): the 0.4.x libraries on Maven Central, upstream Zipline.
#  - 0.5: Keliver's Zipline fork (NF2).
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
#    ZIPLINE: the fork's RELEASE_VERSION, ZIPLINE_GROUP its group (and Gradle plugin id).
#  - COIL and KTOR '-': the line's hosts take Coil, its network fetcher and (iOS) Ktor's Darwin
#    engine from dev.keliver:keliver-image at the app's Keliver version, so they always match what
#    Keliver's own libraries resolve (keliver_line_image_dependencies). The 0.4 line pins them, as
#    its templates always did.
# AGP_MIN is the oldest AGP the line's libraries accept (their AARs' minAndroidGradlePluginVersion;
# '-': not measured for the 0.4 line), for an embedded host, whose AGP is the app's. GRADLE_MIN is the
# line's AGP's own floor, for the warning about an app's older wrapper.
# shellcheck disable=SC2034 # read by keliver_zipline_line below
KELIVER_TOOLCHAIN='
LINE           0.4       0.5
ZIPLINE_GROUP  app.cash.zipline dev.keliver.zipline
ZIPLINE        1.22.0    1.28.0-keliver.1
KOTLIN         2.2.0     2.4.21
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
# The line names, oldest first, from the table's LINE row.
KELIVER_LINES="$(sed -n 's/^LINE[[:space:]]*//p' <<< "$KELIVER_TOOLCHAIN" | tr -s ' \t' ' ')"

keliver_zipline_line() {
  local v="${1:-}" major minor suffix line lmajor lminor idx=0 i last newest
  local -a lines fields
  KELIVER_LINE_ERROR=""; KELIVER_LINE_WARNING=""
  if ! [[ "$v" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)([-.+][A-Za-z0-9.+-]*)?$ ]]; then
    KELIVER_LINE_ERROR="the Keliver version '$v' does not start with <major>.<minor>.<patch>."
    return 1
  fi
  major=$((10#${BASH_REMATCH[1]})); minor=$((10#${BASH_REMATCH[2]})); suffix="${BASH_REMATCH[4]}"
  read -r -a lines <<< "$KELIVER_LINES"
  [ "${#lines[@]}" -gt 0 ] || { KELIVER_LINE_ERROR="keliver-runtime-line.sh: the table names no line."; return 1; }
  # The newest line not newer than the version, by <major>.<minor> (0.5.0-SNAPSHOT is on 0.5).
  for i in "${!lines[@]}"; do
    line="${lines[$i]}"; lmajor=$((10#${line%%.*})); lminor=$((10#${line#*.}))
    if [ "$major" -gt "$lmajor" ] || { [ "$major" -eq "$lmajor" ] && [ "$minor" -ge "$lminor" ]; }; then idx=$i; fi
  done
  last=$((${#lines[@]} - 1)); newest="${lines[$last]}"
  lmajor=$((10#${newest%%.*})); lminor=$((10#${newest#*.}))
  if [ "$idx" -eq "$last" ] && { [ "$major" -ne "$lmajor" ] || [ "$minor" -ne "$lminor" ]; }; then
    if [ "$major" -eq "$lmajor" ] && [ "$minor" -eq $((lminor + 1)) ] && [ "$suffix" = -SNAPSHOT ]; then
      KELIVER_LINE_WARNING="warning: Keliver $v is past the newest line this tools bundle knows ($newest): it gets the $newest line's toolchain. Scaffold again with a tools bundle that knows $major.$minor before releasing it."
      echo "$KELIVER_LINE_WARNING" >&2
    else
      KELIVER_LINE_ERROR="this tools bundle knows Keliver lines up to $newest; Keliver $v needs a newer tools bundle (it cannot know that line's toolchain)."
      return 1
    fi
  fi
  KELIVER_LINE="${lines[$idx]}"
  while read -r -a fields; do
    [ "${#fields[@]}" -gt 0 ] && [ "${fields[0]}" != LINE ] || continue
    printf -v "TOOLCHAIN_${fields[0]}" '%s' "${fields[$((idx + 1))]:-}"
  done <<< "$KELIVER_TOOLCHAIN"
  ZIPLINE_GROUP="$TOOLCHAIN_ZIPLINE_GROUP"
  ZIPLINE_PLUGIN_ID="$TOOLCHAIN_ZIPLINE_GROUP"
  if [ "$ZIPLINE_GROUP" = app.cash.zipline ]; then KELIVER_ZIPLINE_FORK=false; else KELIVER_ZIPLINE_FORK=true; fi
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
