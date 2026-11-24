# shellcheck shell=bash
# Copyright (C) 2026 Keliver contributors. Licensed under the Apache License, Version 2.0.
#
# keliver-runtime-line.sh: SOURCED (never run) by the scaffolders keliver-init,
# keliver-new-device-target.sh, keliver-new-production-host.sh and keliver-new-ios-host.sh. It sits
# beside them: in a Keliver checkout's scripts/ and in the tools bundle's bin/.
#
# Which Zipline an app builds on follows its Keliver version (appRuntime.keliverVersion), never a
# mix: the fork's modules declare upstream's capabilities, so Gradle refuses a classpath with both.
#  - Before 0.5.0 (the 0.4.x libraries on Maven Central, built against it): upstream Zipline,
#    app.cash.zipline 1.22.0 on Kotlin 2.2.0, QuickJS 2021. The scaffolders' own defaults.
#  - 0.5.0 and later (a 0.5.0 pre-release too): Keliver's Zipline fork (NF2; docs/GUEST_PROFILE.md),
#    dev.keliver.zipline 1.28.0-keliver.1, plugin id dev.keliver.zipline, on Kotlin 2.4.21, QuickJS
#    2026: the one engine, quickjs-2026. Guests link Kotlin's Long as a JavaScript BigInt
#    (-Xes-long-as-bigint), which only that engine runs.
#
#   keliver_zipline_line <keliver version>
# sets KELIVER_ZIPLINE_FORK (true or false), ZIPLINE_GROUP and ZIPLINE_PLUGIN_ID, and the fork line's
# defaults FORK_ZIPLINE_VERSION, FORK_KOTLIN_VERSION, FORK_COMPOSE_VERSION, FORK_COROUTINES_VERSION
# and FORK_SERIALIZATION_VERSION (Keliver's gradle/libs.versions.toml at the switch). Returns 1, and
# sets nothing, for a version that does not start with <major>.<minor>.<patch>.

# The fork line's versions (FORK_*): Keliver's version catalog when the libraries moved to the fork.
# shellcheck disable=SC2034 # read by the scaffolders that source this file
FORK_ZIPLINE_VERSION=1.28.0-keliver.1
# shellcheck disable=SC2034
FORK_KOTLIN_VERSION=2.4.21
# shellcheck disable=SC2034
FORK_COMPOSE_VERSION=1.12.2
# shellcheck disable=SC2034
FORK_COROUTINES_VERSION=1.11.0
# shellcheck disable=SC2034
FORK_SERIALIZATION_VERSION=1.13.0
# The first Keliver version built on the fork.
KELIVER_FORK_SINCE=0.5.0

keliver_zipline_line() {
  local v="${1:-}" major minor patch smajor sminor spatch
  [[ "$v" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)([-.+][A-Za-z0-9.+-]*)?$ ]] || return 1
  major=$((10#${BASH_REMATCH[1]})); minor=$((10#${BASH_REMATCH[2]})); patch=$((10#${BASH_REMATCH[3]}))
  [[ "$KELIVER_FORK_SINCE" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || return 1
  smajor=${BASH_REMATCH[1]}; sminor=${BASH_REMATCH[2]}; spatch=${BASH_REMATCH[3]}
  # By major.minor.patch only: 0.5.0-SNAPSHOT or 0.5.0-rc1 is built on the fork as 0.5.0 is.
  if [ "$major" -gt "$smajor" ] \
    || { [ "$major" -eq "$smajor" ] && [ "$minor" -gt "$sminor" ]; } \
    || { [ "$major" -eq "$smajor" ] && [ "$minor" -eq "$sminor" ] && [ "$patch" -ge "$spatch" ]; }; then
    KELIVER_ZIPLINE_FORK=true
    ZIPLINE_GROUP=dev.keliver.zipline
    ZIPLINE_PLUGIN_ID=dev.keliver.zipline
  else
    KELIVER_ZIPLINE_FORK=false
    ZIPLINE_GROUP=app.cash.zipline
    ZIPLINE_PLUGIN_ID=app.cash.zipline
  fi
  return 0
}
