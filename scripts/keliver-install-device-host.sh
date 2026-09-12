#!/usr/bin/env bash
#
# keliver install-device-host — install the Keliver device host APK.
#
#   keliver-install-device-host.sh [--serial SERIAL] [--apk PATH]
#
# The host is a small generic Android app that loads YOUR guest bundle over
# HTTP and renders it. You install it once per device/emulator; after that,
# `./gradlew serveDevelopmentZipline` plus a host restart is the whole loop.
#
# THE APK SHIPS INSIDE THIS BUNDLE (../host/). It is a locally built debug
# artifact. It is NOT published to any app store, Maven repository or release
# page, and this script never downloads anything.
#
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERIAL=""
APK=""
while [ $# -gt 0 ]; do
  case "$1" in
    --serial) [ $# -ge 2 ] || { echo "--serial needs a value" >&2; exit 2; }; SERIAL="$2"; shift 2 ;;
    --apk)    [ $# -ge 2 ] || { echo "--apk needs a value" >&2; exit 2; }; APK="$2"; shift 2 ;;
    -h|--help) sed -n '2,16p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$APK" ]; then
  APK="$(ls "$HERE/../host/"keliver-device-host-*.apk 2>/dev/null | head -1 || true)"
fi
[ -n "$APK" ] && [ -f "$APK" ] || { echo "error: no host APK found (looked in $HERE/../host/). Pass --apk PATH." >&2; exit 1; }

command -v adb >/dev/null || { echo "error: adb not on PATH (install Android platform-tools)" >&2; exit 1; }

if [ -z "$SERIAL" ]; then
  mapfile -t devs < <(adb devices | awk 'NR>1 && $2=="device" {print $1}') 2>/dev/null || \
    devs=($(adb devices | awk 'NR>1 && $2=="device" {print $1}'))
  case "${#devs[@]}" in
    0) echo "error: no adb device. Start an emulator or attach a device." >&2; exit 1 ;;
    1) SERIAL="${devs[0]}" ;;
    *) echo "error: multiple devices (${devs[*]}) — pass --serial" >&2; exit 1 ;;
  esac
fi

SUM="$(shasum -a 256 "$APK" | awk '{print $1}')"
echo "==> host APK: $APK"
echo "    sha256:   $SUM"
echo "    device:   $SERIAL"
if [ -f "$APK.sha256" ]; then
  EXPECT="$(awk '{print $1}' < "$APK.sha256")"
  [ "$SUM" = "$EXPECT" ] || { echo "error: sha256 mismatch (expected $EXPECT)" >&2; exit 1; }
  echo "    checksum: matches $APK.sha256"
fi

adb -s "$SERIAL" install -r "$APK"
VER="$(adb -s "$SERIAL" shell dumpsys package dev.keliver.portaldevice 2>/dev/null | awk -F= '/versionName/{print $2; exit}' | tr -d '\r')"
echo "==> installed dev.keliver.portaldevice ${VER:+(versionName $VER)} on $SERIAL"
cat <<TXT

next, from your app directory:
  ./gradlew serveDevelopmentZipline                      # serves your bundle on :8080
  adb -s $SERIAL shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity

An emulator reaches your machine at 10.0.2.2, which is what the host uses.
A physical device on your LAN cannot use this dev loop without rebuilding the
host against your machine's address.
TXT
