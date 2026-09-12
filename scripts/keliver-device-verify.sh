#!/usr/bin/env bash
#
# keliver-device-verify — runtime verification of a PACKAGED device host APK on
# an attached device or emulator.
#
#   scripts/keliver-device-verify.sh <package.zip> <work-dir> <evidence-dir> [--serial S]
#
# This is the check a green build cannot substitute for: it installs the APK
# that actually ships inside the bundle and drives it.
#
#   1. install    the bundle's own installer, so the packaged sha256 sidecar
#                 gate is exercised too. Nothing is rebuilt.
#   2. refuse     `--es mode prod` on a COLD host: the refusal must be logged
#                 AND on screen, and no manifest or bundle may be requested.
#   3. develop    the full documented adopter route with --serial, ending in a
#                 rendered guest screen. Proves the refusal did not latch the
#                 host into a broken state.
#   4. refuse     `--es mode prod` again, now WARM (a dev session and its dev
#                 Zipline cache exist). U22 is about never silently downgrading
#                 signature verification; a populated dev cache must not become
#                 a way in.
#
# NOTE this file is invoked as `bash scripts/keliver-device-verify.sh` and not
# inlined into the workflow: reactivecircus/android-emulator-runner runs its
# `script:` under /usr/bin/sh, which on Ubuntu is dash and rejects
# `set -o pipefail`.
#
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ZIP="${1:?usage: $0 <package.zip> <work-dir> <evidence-dir> [--serial S]}"
WORK="${2:?usage: $0 <package.zip> <work-dir> <evidence-dir> [--serial S]}"
EV="${3:?usage: $0 <package.zip> <work-dir> <evidence-dir> [--serial S]}"
shift 3
SERIAL=""
while [ $# -gt 0 ]; do
  case "$1" in --serial) SERIAL="${2:?}"; shift 2 ;; *) echo "unknown: $1" >&2; exit 2 ;; esac
done

ZIP="$(cd "$(dirname "$ZIP")" && pwd -P)/$(basename "$ZIP")"
[ -f "$ZIP" ] || { echo "no package at $ZIP" >&2; exit 2; }
mkdir -p "$WORK" "$EV"
EV="$(cd "$EV" && pwd -P)"

command -v adb >/dev/null || { echo "adb is not on PATH" >&2; exit 2; }
if [ -z "$SERIAL" ]; then
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
[ -n "$SERIAL" ] || { echo "no adb device is available" >&2; exit 2; }

PKGID=dev.keliver.portaldevice
ACT="$PKGID/dev.keliver.portaldevice.host.MainActivity"

sha256(){ if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }

echo "==> device verification"
echo "    package: $ZIP"
echo "    serial:  $SERIAL"
adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r' | sed 's/^/    api:     /'
adb -s "$SERIAL" shell getprop ro.product.cpu.abi  | tr -d '\r' | sed 's/^/    abi:     /'
df -h . 2>/dev/null | sed 's/^/    disk:    /'
echo

# --- 1. install the packaged APK ---------------------------------------------
mkdir -p "$WORK/pkg"
( cd "$WORK/pkg" && unzip -q -o "$ZIP" )
PKG="$(ls -d "$WORK/pkg"/keliver-portal-tools-*)"
APK="$(ls "$PKG/host/"keliver-device-host-*.apk | head -1)"
echo "    apk:     $APK"
sha256 "$APK" | tee "$EV/apk-under-test.sha256"
# The thing under test must be development-only and key-free at install time.
if unzip -l "$APK" | grep -q 'assets/portal_ed25519.pub'; then
  echo "::error::the APK being installed embeds a portal key"; exit 1
fi
ok "the packaged APK carries no embedded portal key"

adb -s "$SERIAL" uninstall "$PKGID" >/dev/null 2>&1 || true
if "$PKG/bin/keliver-install-device-host.sh" --serial "$SERIAL" > "$EV/install.log" 2>&1; then
  ok "the bundle's own installer installed the packaged APK"
else
  bad "the packaged APK did not install"; cat "$EV/install.log"; exit 1
fi
sed 's/^/        /' "$EV/install.log"

# --- a reusable production-mode attempt ---------------------------------------
# $1 = a label used for the evidence filenames and the check text.
# $1 = tag, $2 = destination file. uiautomator refuses while a window animates,
# so retry. The REMOTE path is unique per tag and deleted first: reusing one
# path means a dump that silently failed would be read back as the previous
# screen, and the check would pass on stale evidence.
ui_dump(){
  local tag="$1" dest="$2" remote="/sdcard/keliver-ui-$1.xml" i
  : > "$dest"
  adb -s "$SERIAL" shell rm -f "$remote" >/dev/null 2>&1 || true
  for i in 1 2 3; do
    if adb -s "$SERIAL" shell uiautomator dump "$remote" >/dev/null 2>&1; then
      adb -s "$SERIAL" shell cat "$remote" > "$dest" 2>/dev/null || true
      [ -s "$dest" ] && return 0
    fi
    sleep 3
  done
  return 0
}

# The on-screen check. NOTE `adb exec-out screencap` returns an all-black frame
# on a -no-window emulator — verified in run 34669956183, where the three PNGs
# were byte-identical and empty — so the view hierarchy, not a screenshot, is
# what says whether something is actually on screen.
#
# uiautomator quotes an attribute with ' when the value contains ", and the
# refusal message contains an apostrophe, so match on the value text and never
# assume a leading text=".
on_screen(){ grep -qiF "$2" "$1"; }

attempt_prod(){
  local tag="$1"
  adb -s "$SERIAL" logcat -c || true
  adb -s "$SERIAL" shell am force-stop "$PKGID"
  adb -s "$SERIAL" shell am start -n "$ACT" --es mode prod >/dev/null
  sleep 12
  adb -s "$SERIAL" logcat -d > "$EV/prod-$tag.log"
  ui_dump "prod-$tag" "$EV/prod-$tag.xml"

  grep -q "refusing production mode" "$EV/prod-$tag.log" \
    && ok "[$tag] the host logged a production refusal" \
    || bad "[$tag] no refusal was logged"

  # The dump must be this app's, so a dump of some other window cannot pass.
  grep -q 'package="dev.keliver.portaldevice"' "$EV/prod-$tag.xml" \
    && ok "[$tag] the foreground window belongs to the host" \
    || { bad "[$tag] the foreground window is not the host"; head -c 400 "$EV/prod-$tag.xml"; echo; }

  on_screen "$EV/prod-$tag.xml" "Production mode refused" \
    && ok "[$tag] the refusal heading is on screen" \
    || { bad "[$tag] the refusal heading is not on screen"; head -c 800 "$EV/prod-$tag.xml"; echo; }

  # A heading alone is not a usable refusal: the actionable reason must render.
  on_screen "$EV/prod-$tag.xml" "development-only and cannot run production mode" \
    && ok "[$tag] the actionable reason is on screen, not just the heading" \
    || bad "[$tag] the refusal message body did not render"

  # Nothing may be fetched for a session that was refused.
  grep -qE "prod mode: loading|prod mode: verifying|manifest\.zipline\.json" "$EV/prod-$tag.log" \
    && { bad "[$tag] a manifest or bundle was requested for a refused session"; \
         grep -E "prod mode|manifest" "$EV/prod-$tag.log" | head; } \
    || ok "[$tag] no manifest or bundle was requested"

  # And it must not have quietly proceeded down the normal path either.
  grep -q "codeLoadSuccess" "$EV/prod-$tag.log" \
    && bad "[$tag] guest code was loaded during a refused session" \
    || ok "[$tag] no guest code was loaded"
}

# --- 2. production mode, cold -------------------------------------------------
echo
echo "--- production mode on a cold host"
attempt_prod cold

# --- 3. the full documented development route --------------------------------
echo
echo "--- the documented adopter route, on the device"
if "$ROOT/scripts/keliver-adopter-acceptance.sh" "$WORK/acceptance" "$ZIP" --serial "$SERIAL" \
     > "$EV/acceptance.log" 2>&1; then
  ok "the packaged adopter acceptance passed with --serial"
else
  bad "the packaged adopter acceptance failed with --serial"
fi
tail -80 "$EV/acceptance.log" | sed 's/^/        /'
grep -E "the edited title is on the device|the device does not show the edit" "$EV/acceptance.log" \
  | sed 's/^/        /' || true

# --- 4. production mode, warm ------------------------------------------------
echo
echo "--- production mode again, after a successful development session"
attempt_prod warm

# --- and the development route still starts ----------------------------------
adb -s "$SERIAL" logcat -c || true
adb -s "$SERIAL" shell am force-stop "$PKGID"
adb -s "$SERIAL" shell am start -n "$ACT" >/dev/null
sleep 10
adb -s "$SERIAL" logcat -d > "$EV/dev-after.log"
ui_dump "dev-after" "$EV/dev-after.xml"
grep -q "mode=dev" "$EV/dev-after.log" \
  && ok "the development route still enters the development path after a refusal" \
  || bad "the development route did not start after a refusal"
grep -q "refusing production mode" "$EV/dev-after.log" \
  && bad "the development route was itself refused" \
  || ok "the development route was not refused"
# Guard the assertion below: an EMPTY dump would satisfy "the refusal is gone"
# without anything being on screen at all.
grep -q 'package="dev.keliver.portaldevice"' "$EV/dev-after.xml" \
  && ok "the development route's foreground window belongs to the host" \
  || bad "no host window was dumped on the development route"
on_screen "$EV/dev-after.xml" "Production mode refused" \
  && bad "the host is still showing the refusal screen on the development route" \
  || ok "the refusal screen is gone on the development route"
echo "    development route, on screen:"
grep -oE "text=[\"'][^\"']+[\"']" "$EV/dev-after.xml" | sort -u | sed 's/^/        /'

echo
df -h . 2>/dev/null | sed 's/^/    disk:    /'
echo "passed: $pass   failed: $fail"
[ "$fail" -eq 0 ]
