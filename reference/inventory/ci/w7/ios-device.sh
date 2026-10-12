#!/usr/bin/env bash
#
# W7: the iOS production host on a PHYSICAL iPhone, over USB. A first-load
# check, not ios.sh: build for iphoneos with YOUR team, install and launch with
# devicectl, and read the host's own log lines (its stdout, through
# `devicectl ... --console`). Screens are not read here (devicectl takes no
# screenshot): take them on the phone, as docs/DEVICE_PROOF.md says.
#
# NOT YET EXECUTED: written on 2026-10-12 with no iPhone attached and without
# the owner's Apple team. Its first run is the owner's.
#
#   DEVELOPMENT_TEAM=<team id> ci/w7/ios-device.sh <app-dir> <evidence-dir> <device udid> [Debug|Release]
#
# <app-dir> holds host-ios/ (keliver-new-ios-host.sh), with HostConfig.kt's
# BUNDLE_SERVER set to a server the PHONE can reach: an https:// URL with a real
# certificate, or http://<this Mac's LAN IP>:8077 for a Debug build (the iPhone
# has no adb reverse; the scaffolder writes the ATS exception for that host).
# The udid is `xcrun devicectl list devices`' Identifier.
#   D1  the app is signed by the given team, for this bundle id
#   D2  installed on the device, launched, and its log read
#   D3  the host verified with this app's key, looked a bundle up and loaded it
#       (codeLoadSuccess), with no refusal
#   D4  a second launch with the bundle server stopped starts from the cache
#       (only when KELIVER_IOS_OFFLINE_CHECK=1: you stop the server when asked)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
APP="$(cd "${1:?usage: DEVELOPMENT_TEAM=<team> $0 <app-dir> <evidence-dir> <udid> [Debug|Release]}" && pwd -P)"
EV="$(mkdir -p "${2:?}" && cd "$2" && pwd -P)"
UDID="${3:?device udid}"
CONFIG="${4:-Debug}"
TEAM="${DEVELOPMENT_TEAM:-}"
[ -n "$TEAM" ] || { echo "set DEVELOPMENT_TEAM to your Apple developer team id (Xcode > Settings > Accounts)" >&2; exit 2; }
H="$APP/host-ios"
[ -d "$H/iosApp.xcodeproj" ] || { echo "no host-ios/iosApp.xcodeproj under $APP" >&2; exit 2; }
pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); echo "PASS $1" >> "$EV/ios-device.results"; }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); echo "FAIL $1" >> "$EV/ios-device.results"; }
: > "$EV/ios-device.results"

CFG="$(find "$H/src/iosMain/kotlin" -name HostConfig.kt | head -1)"
[ -f "$CFG" ] || { echo "no HostConfig.kt under $H/src/iosMain/kotlin" >&2; exit 2; }
val(){ sed -n "s/^internal const val $1: String = \"\\(.*\\)\"\$/\\1/p" "$CFG"; }
KEY="$(val PORTAL_PUBLIC_KEY_HEX)"; SERVER="$(val BUNDLE_SERVER)"
BID="$(sed -n 's/^PRODUCT_BUNDLE_IDENTIFIER=//p' "$H/Configuration/Config.xcconfig")"
PRODUCT="$(sed -n 's/^PRODUCT_NAME=//p' "$H/Configuration/Config.xcconfig")"
echo "==> $BID ($PRODUCT), $CONFIG, team $TEAM, server $SERVER, key ${KEY:0:8}…, device $UDID"
case "$SERVER" in
  http://localhost*|http://127.*) bad "BUNDLE_SERVER is $SERVER: the phone's own loopback, not this Mac; use the LAN IP or an https:// server"; exit 1 ;;
esac

# The device, as devicectl sees it (kept as evidence).
xcrun devicectl list devices > "$EV/ios-devices.txt" 2>&1
grep -q "$UDID" "$EV/ios-devices.txt" || { bad "device $UDID is not in devicectl list devices (paired, unlocked, Developer Mode on?)"; exit 1; }
xcrun devicectl device info details --device "$UDID" > "$EV/ios-device-details.txt" 2>&1 || true

# D1: build and sign for the device. -allowProvisioningUpdates lets Xcode create
# the development profile for this bundle id under your team.
DD="$EV/../ios-device-derived"   # outside the evidence dir: not evidence
xcodebuild -project "$H/iosApp.xcodeproj" -scheme iosApp -configuration "$CONFIG" \
  -destination "id=$UDID" -derivedDataPath "$DD" DEVELOPMENT_TEAM="$TEAM" -allowProvisioningUpdates build \
  > "$EV/ios-device-build.log" 2>&1 \
  && ok "D1: xcodebuild built and signed iosApp for the device ($CONFIG)" \
  || { bad "D1: the device build failed: $(grep -E 'error:' "$EV/ios-device-build.log" | head -3 | tr '\n' ' ')"; exit 1; }
APPB="$DD/Build/Products/$CONFIG-iphoneos/$PRODUCT.app"
codesign -dvv "$APPB" > "$EV/ios-device-codesign.txt" 2>&1
grep -q "^TeamIdentifier=$TEAM$" "$EV/ios-device-codesign.txt" && grep -q "^Identifier=$BID$" "$EV/ios-device-codesign.txt" \
  && ok "D1: signed by team $TEAM for $BID ($(grep -m1 '^Authority=' "$EV/ios-device-codesign.txt"))" \
  || bad "D1: the signature is not team $TEAM / $BID"

# D2 + D3: install, launch with the console attached for 40 s, read the log.
launch(){  # $1 = label
  xcrun devicectl device process launch --device "$UDID" --terminate-existing --console "$BID" > "$EV/$1.console.txt" 2>&1 &
  local pid=$!
  sleep 40
  kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
}
xcrun devicectl device install app --device "$UDID" "$APPB" > "$EV/ios-device-install.log" 2>&1 \
  && ok "D2: installed on the device" || { bad "D2: install failed: $(tail -3 "$EV/ios-device-install.log" | tr '\n' ' ')"; exit 1; }
launch ios-device-first
L="$EV/ios-device-first.console.txt"
grep -q "KeliverHost:" "$L" && ok "D2: launched; the host's log was read through devicectl --console" \
  || bad "D2: no KeliverHost line in the console (see $L)"
grep -q "KeliverHost: verifying manifests with portal-ed25519 ${KEY:0:8}" "$L" \
  && ok "D3: the host verifies with this app's key (${KEY:0:8}…)" || bad "D3: no verification with this app's key"
grep -q "KeliverHost: loading ${SERVER%/}/bundles/v" "$L" && ok "D3: it looked a bundle up at $SERVER: $(grep -m1 -o 'loading [^ ]*' "$L")" \
  || bad "D3: no bundle lookup at $SERVER"
grep -q "codeLoadSuccess" "$L" && ok "D3: the signed bundle loaded (codeLoadSuccess)" || bad "D3: no codeLoadSuccess"
grep -q "KeliverHost: refusing" "$L" && bad "D3: the host refused: $(grep -m1 'refusing' "$L")" || ok "D3: no refusal"

if [ "${KELIVER_IOS_OFFLINE_CHECK:-0}" = 1 ]; then
  printf '\nStop the bundle server now (or put the phone in airplane mode), then press Enter: '; read -r _
  launch ios-device-offline
  L="$EV/ios-device-offline.console.txt"
  grep -q "KeliverHost: lookup failed; starting from the cached bundle" "$L" && grep -q "codeLoadSuccess" "$L" \
    && ok "D4: offline, the host started from its verified cache" || bad "D4: no cached start offline"
fi
echo "ios-device: passed $pass, failed $fail   (NOT a simulator: device $UDID)"
[ "$fail" -eq 0 ]
