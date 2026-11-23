#!/usr/bin/env bash
# Copyright (C) 2026 Keliver contributors. Licensed under the Apache License, Version 2.0.
#
# build-portal-tools-selftest — build-portal-tools.sh refuses to build a tools zip whose bundled
# development host runs a different Zipline engine than the apps its scaffolders wire by default
# (NF2): the host is built from the checkout, so it runs the catalog's Zipline (dev.keliver.zipline:
# QuickJS 2026; app.cash.zipline: QuickJS 2021), and keliver-runtime-line.sh says which line the
# scaffolders' default Keliver version is on.
#
#   scripts/build-portal-tools-selftest.sh <disposable-parent>
#
# Runs build-portal-tools.sh with KELIVER_TOOLS_PREFLIGHT_ONLY=1 (its checks, no Gradle) in fixture
# copies of this checkout's scripts/, gradle/libs.versions.toml and build-support/portal-tools.version,
# four ways (catalog group x keliver-init's default), plus the refusals (an unknown group, no helper,
# a default past the helper's newest line, a sibling scaffolder with a fixed default) and this
# checkout as it stands.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PARENT="${1:?usage: build-portal-tools-selftest.sh <disposable-parent>}"
mkdir -p "$PARENT"
RUN="$(mktemp -d "$PARENT/build-portal-tools-selftest.XXXXXX")"
pass=0; fail=0
ok() { echo "ok   $*"; pass=$((pass + 1)); }
bad() { echo "FAIL $*"; fail=$((fail + 1)); }

SCRIPTS=(build-portal-tools.sh keliver-runtime-line.sh keliver-init keliver-new-editor.sh
  keliver-new-device-target.sh keliver-new-production-host.sh keliver-new-ios-host.sh)

# fixture <name> <catalog group> <scaffold default>: a root whose catalog names <group> and whose
# keliver-init defaults to <scaffold default> (the same edit a tools release makes). keliver-init is
# the only scaffolder with a default: the others follow the app's own Keliver version, and
# build-portal-tools.sh refuses a sibling that falls back to a fixed one.
fixture() {
  local d="$RUN/$1" group="$2" v="$3" s
  mkdir -p "$d/scripts" "$d/gradle" "$d/build-support"
  for s in "${SCRIPTS[@]}"; do cp "$ROOT/scripts/$s" "$d/scripts/"; done
  cp "$ROOT/build-support/portal-tools.version" "$d/build-support/"
  sed -E "s/^(zipline(-[A-Za-z]+)?[[:space:]]*=[[:space:]]*\{[[:space:]]*module[[:space:]]*=[[:space:]]*\")[^:\"]+:/\1$group:/" \
    "$ROOT/gradle/libs.versions.toml" > "$d/gradle/libs.versions.toml"
  sed -i.bak -E "s/^KELIVER_VERSION=\"\\\$\{KELIVER_VERSION:-[^}]+\}\"/KELIVER_VERSION=\"\${KELIVER_VERSION:-$v}\"/" "$d/scripts/keliver-init"
  grep -qF "KELIVER_VERSION=\"\${KELIVER_VERSION:-$v}\"" "$d/scripts/keliver-init" \
    || { echo "fixture $1: could not set keliver-init's default to $v" >&2; exit 1; }
  rm -f "$d"/scripts/*.bak
  echo "$d"
}

# run_case <label> <root> <expected rc> [<text the output must contain>]
run_case() {
  local label="$1" d="$2" want="$3" text="${4:-}" out rc=0
  out="$(KELIVER_TOOLS_PREFLIGHT_ONLY=1 bash "$d/scripts/build-portal-tools.sh" 2>&1)" || rc=$?
  if [ "$rc" = "$want" ] && { [ -z "$text" ] || grep -qF -- "$text" <<<"$out"; }; then
    ok "$label"
  else
    bad "$label (rc=$rc, wanted $want${text:+ and \"$text\"}): $(tail -3 <<<"$out" | tr '\n' ' ')"
  fi
  # Preflight never reaches Gradle, so nothing is staged. Checked in fixtures only: a real
  # checkout may hold an earlier build's directory (review of #294, L2).
  [ "$d" = "$ROOT" ] || [ ! -e "$d/build/portal-tools" ] || bad "$label: preflight staged $d/build/portal-tools"
}

echo "=== the bundled host's engine must match the scaffolders' default line"
run_case "fork host, scaffolders on 0.4.1: refused, naming both lines" \
  "$(fixture fork-041 dev.keliver.zipline 0.4.1)" 1 "REFUSING to build: the bundled development host would not run"
run_case "fork host, scaffolders on 0.4.1: the message names QuickJS 2026 and keliver 0.4.1" \
  "$RUN/fork-041" 1 "runs QuickJS 2026 (Keliver's Zipline fork)"
run_case "fork host, scaffolders on 0.4.1: the message names the fork's line from the table" \
  "$RUN/fork-041" 1 "the fork line is 0.5"
run_case "fork host, scaffolders on 0.5.0: built" \
  "$(fixture fork-050 dev.keliver.zipline 0.5.0)" 0 "matches"
run_case "fork host, scaffolders on 0.5.0-rc1 (a pre-release is the fork line): built" \
  "$(fixture fork-050rc dev.keliver.zipline 0.5.0-rc1)" 0 "matches"
run_case "upstream host, scaffolders on 0.4.1: built" \
  "$(fixture up-041 app.cash.zipline 0.4.1)" 0 "matches"
run_case "upstream host, scaffolders on 0.5.0: refused" \
  "$(fixture up-050 app.cash.zipline 0.5.0)" 1 "runs QuickJS 2021 (upstream Zipline)"
d="$(fixture nogroup example.invalid 0.4.1)"
run_case "a catalog Zipline group that is neither: refused" "$d" 1 "cannot tell which engine"
d="$(fixture noline dev.keliver.zipline 0.5.0)"; rm "$d/scripts/keliver-runtime-line.sh"
run_case "no keliver-runtime-line.sh: refused" "$d" 1 "keliver-runtime-line.sh is missing"
# A default past the table's newest line: the helper's own reason, not an unbound variable.
run_case "scaffolders on 0.9.0 (past the newest line): refused with the helper's reason" \
  "$(fixture past dev.keliver.zipline 0.9.0)" 1 "needs a newer tools bundle"
d="$(fixture sibling dev.keliver.zipline 0.5.0)"
# shellcheck disable=SC2016 # the ${...} is the script's text, not this shell's
sed -i.bak -E 's/^KELIVER_VERSION="\$\{KELIVER_VERSION:-\}"/KELIVER_VERSION="${KELIVER_VERSION:-0.5.0}"/' "$d/scripts/keliver-new-device-target.sh"
rm -f "$d"/scripts/*.bak
run_case "a sibling scaffolder with a fixed default: refused" "$d" 1 "keliver-new-device-target.sh falls back to a fixed keliver version"

echo "=== this checkout as it stands"
expect_rc=0
if grep -qE '^zipline[[:space:]]*=[[:space:]]*\{[[:space:]]*module[[:space:]]*=[[:space:]]*"dev\.keliver\.zipline:' "$ROOT/gradle/libs.versions.toml" \
  && grep -qE '^KELIVER_VERSION="\$\{KELIVER_VERSION:-0\.[0-4]\.' "$ROOT/scripts/keliver-init"; then
  expect_rc=1  # the fork catalog with a pre-0.5 scaffold default: what NF2's switch leaves until the tools release
fi
run_case "this checkout (expected rc $expect_rc)" "$ROOT" "$expect_rc"

rm -rf "$RUN"
echo "build-portal-tools-selftest: $pass passed, $fail failed"
[ "$fail" = 0 ]
