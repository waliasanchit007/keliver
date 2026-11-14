#!/usr/bin/env bash
#
# keliver-gateb-ios-device — Gate B part c's phone checkpoint: keliver-gateb-ios-sim.sh's comparison
# and first-frame timing on a real iPhone paired with this Mac, over USB or the network (docs/COMPOSE_GENERATOR_DESIGN.md §21).
#
#   scripts/keliver-gateb-ios-device.sh --device <CoreDevice identifier> --bundle-id <id>
#       [--team-file FILE | DEVELOPMENT_TEAM in the environment] [--out DIR] [--port PORT]
#       [--runs N] [--debug] [--skip-build] [--gate-wait MINUTES] [--preflight]
#       [--known-profile FILE.mobileprovision | --allow-new-app-id]
#
# It addresses ONLY the device named by --device and installs ONLY its own app, under --bundle-id
# (the app's own default, dev.keliver.gateb, needs an App ID on your team). Steps:
#   0. the phone's state and its installed apps (logs/apps-before.json); refuses if --bundle-id is
#      already installed (it would not be this run's to remove), here and again just before the
#      install. --preflight stops here.
#   1. Gradle (-Pkeliver.gatebIos -PgatebTarget=device): the signed guest bundle and the app for
#      iosArm64, release-optimised Kotlin/Native by default (--debug for a debug binary);
#   2. a development profile for the team and the bundle id: an Xcode-managed one already on this
#      Mac, valid and listing the phone, else one fetched by building a minimal wrapper project with
#      `xcodebuild -allowProvisioningUpdates` (in DIR/wrapper). Refuses, unless --allow-new-app-id,
#      when no profile for that App ID on that team has existed (none on this Mac, no
#      --known-profile such as an earlier build's embedded.mobileprovision). That does not prove the
#      App ID still exists: if it was removed since, the fetch registers it again. Automatic signing
#      may also create an Apple Development certificate on the team when none is in this Mac's
#      keychain. It never registers a device (no -allowProvisioningDeviceRegistration): the phone
#      must already be on the team. The app's Info.plist gets the bundle id, the iPhoneOS platform
#      and a local-network usage string; it is signed with that profile's certificate and the
#      wrapper's entitlements;
#   3. serves the bundle over http on this Mac's Wi-Fi address (en0), port PORT (default 8495; never
#      8077, 8096, 8478, 8091 or 8494). The app is a plain Kotlin/Native app with an ATS exception,
#      not a Keliver Release host, so http is accepted in a release-optimised binary;
#   4. installs; a native launch must start (owner gate 1: the developer certificate must be trusted
#      on the phone) and a guest launch must load the code (owner gate 2: Local Network allowed for
#      the app). On either gate it re-checks every 5 minutes for up to --gate-wait minutes (default
#      45) with the app left installed, then gives up;
#   5. pixels: per sample (orders, card, item) a native and a guest launch with GATEB_CAPTURE: the
#      app records its window's Compose content into a GraphicsLayer and writes a PNG into its
#      Documents 2 s after the first frame; copied out with devicectl and compared (0 differing
#      pixels expected; the control, orders vs card, must differ);
#   6. warm timing: N process-cold launches per route (terminate, 1 s, launch), alternating, of
#      orders (default 10). The guest's Zipline cache is warm, so a guest launch is the manifest
#      over Wi-Fi plus modules from the local cache;
#   7. empty-cache timing: N times, a `clear-cache` launch (the app deletes Library/Caches/gateb and
#      shows nothing), then a guest launch, which downloads every module as a fresh install does;
#   8. whatever happened once the install began (even an install that failed partway): the server
#      stopped, the app uninstalled, the phone's apps listed again.
# Results: DIR/results.md, DIR/*.png, DIR/logs/ (each launch's console; the app also writes its lines
# to Documents/gateb-<tag>.log, copied out when the console stays silent). Battery level, charging
# state and thermal state come from the app (a line after each first frame); the report lists them
# for the first and last launch of each route (native, guest, guest-empty-cache).
#
# Needs: macOS with Xcode 16+ (devicectl, xcodebuild, codesign, security, plutil), a phone with
# Developer Mode on, paired with this Mac, and an Xcode account for the team; python3; JDK 17.
set -euo pipefail
NAME=keliver-gateb-ios-device
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
fail() { echo "$NAME: $*" >&2; exit 1; }
say() { echo "$NAME: $*"; }

DEVICE=""; BID=""; TEAM_FILE=""; OUT="$ROOT/build/$NAME"; PORT=8495; RUNS=10; TYPE=release
SKIP_BUILD=0; GATE_WAIT=45; NEW_APP_ID=0; PREFLIGHT=0; KNOWN_PROFILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --device) DEVICE="${2:?--device needs an identifier}"; shift 2 ;;
    --bundle-id) BID="${2:?--bundle-id needs an id}"; shift 2 ;;
    --team-file) TEAM_FILE="${2:?--team-file needs a file}"; shift 2 ;;
    --out) OUT="${2:?--out needs a directory}"; shift 2 ;;
    --port) PORT="${2:?--port needs a number}"; shift 2 ;;
    --runs) RUNS="${2:?--runs needs a number}"; shift 2 ;;
    --debug) TYPE=debug; shift ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --gate-wait) GATE_WAIT="${2:?--gate-wait needs minutes}"; shift 2 ;;
    --allow-new-app-id) NEW_APP_ID=1; shift ;;
    --known-profile) KNOWN_PROFILE="${2:?--known-profile needs a .mobileprovision}"; shift 2 ;;
    --preflight) PREFLIGHT=1; shift ;;
    -h|--help) sed -n '2,53p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "$NAME: unknown argument $1 (see --help)" >&2; exit 2 ;;
  esac
done
[ -n "$DEVICE" ] || fail "--device is required (xcrun devicectl list devices)"
[ -n "$BID" ] || fail "--bundle-id is required"
case "$BID" in *[!A-Za-z0-9.-]*) fail "--bundle-id has characters a bundle id cannot have" ;; esac
case "$PORT" in ''|*[!0-9]*) fail "--port must be a number" ;; esac
case "$RUNS" in ''|*[!0-9]*|0) fail "--runs must be a positive number" ;; esac
case "$GATE_WAIT" in ''|*[!0-9]*) fail "--gate-wait must be a number of minutes" ;; esac
case "$PORT" in 8077|8096|8478|8091|8494) fail "port $PORT is taken by the owner's portal, the iOS CI relay or another harness; choose another" ;; esac
if [ -n "$TEAM_FILE" ]; then
  [ -f "$TEAM_FILE" ] || fail "no team file $TEAM_FILE"
  # shellcheck disable=SC1090
  DEVELOPMENT_TEAM="$(set +u; . "$TEAM_FILE" >/dev/null 2>&1; printf '%s' "${DEVELOPMENT_TEAM:-}")"
fi
TEAM="${DEVELOPMENT_TEAM:-}"
case "$TEAM" in [A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9]) ;;
  *) fail "no team: pass --team-file FILE (setting DEVELOPMENT_TEAM) or set DEVELOPMENT_TEAM (10 characters)" ;; esac
for tool in xcrun python3 codesign security plutil curl ipconfig; do command -v "$tool" >/dev/null || fail "$tool is needed"; done
SAMPLES="orders card item"

mkdir -p "$OUT/logs"
OUT="$(cd "$OUT" && pwd -P)"
for sample in $SAMPLES; do rm -f "$OUT/$sample-native.png" "$OUT/$sample-guest.png" "$OUT/$sample-diff.png"; done
rm -f "$OUT/timing.txt" "$OUT/results.md" "$OUT/gates.log" "$OUT"/logs/*.log "$OUT"/logs/*.json
GATES="$OUT/gates.log"
stamp() { date -u +%FT%TZ; }

dc() { xcrun devicectl "$@"; }

# 0. The phone and its apps.
dc list devices --json-output "$OUT/logs/devices.json" >/dev/null 2>&1 || fail "devicectl list devices failed"
DEVINFO="$(python3 -I - "$OUT/logs/devices.json" "$DEVICE" <<'PY'
import json, sys
for d in json.load(open(sys.argv[1]))["result"]["devices"]:
    if d.get("identifier") == sys.argv[2]:
        h, p, c = d.get("hardwareProperties", {}), d.get("deviceProperties", {}), d.get("connectionProperties", {})
        print("\t".join(str(x) for x in (h.get("udid", ""), h.get("marketingName", "?"), p.get("osVersionNumber", "?"),
              p.get("developerModeStatus", "?"), c.get("pairingState", "?"), c.get("tunnelState", "?"), c.get("transportType", "?"))))
        break
PY
)"
[ -n "$DEVINFO" ] || fail "device $DEVICE is not known to devicectl"
IFS=$'\t' read -r HWUDID MODEL OSVER DEVMODE PAIRING TUNNEL TRANSPORT <<< "$DEVINFO"
say "device: $MODEL, iOS $OSVER, developer mode $DEVMODE, pairing $PAIRING, tunnel $TUNNEL, transport $TRANSPORT"
[ "$DEVMODE" = enabled ] || fail "Developer Mode is not enabled on the device ($DEVMODE)"

apps() { # json-out: the installed (user) apps' bundle ids, one per line
  dc device info apps --device "$DEVICE" --json-output "$1" >/dev/null 2>&1 || return 1
  python3 -I -c 'import json, sys; print("\n".join(sorted(a.get("bundleIdentifier", "?") for a in json.load(open(sys.argv[1]))["result"]["apps"])))' "$1"
}
BEFORE="$(apps "$OUT/logs/apps-before.json")" || fail "could not list the device's apps (is it unlocked and connected?)"
say "the device has $(printf '%s\n' "$BEFORE" | grep -c .) apps installed before this run"
if printf '%s\n' "$BEFORE" | grep -qxF "$BID"; then
  fail "$BID is already installed on the device: it is not this run's to replace or remove. Choose another --bundle-id or remove it yourself."
fi
[ "$PREFLIGHT" = 1 ] && { say "preflight only: nothing built, installed or launched"; exit 0; }

INSTALLED=0; SERVER_PID=""; CONSOLE_PID=""
cleanup() {
  set +e
  [ -n "$CONSOLE_PID" ] && kill "$CONSOLE_PID" 2>/dev/null
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null && say "stopped the bundle server"
  if [ "$INSTALLED" = 1 ]; then
    if dc device uninstall app --device "$DEVICE" "$BID" >/dev/null 2>&1; then say "uninstalled $BID"; else echo "$NAME: UNINSTALL FAILED for $BID: remove it by hand" >&2; fi
    local after
    after="$(apps "$OUT/logs/apps-after.json")"
    if [ "$after" = "$BEFORE" ]; then say "the device's apps are as before this run"; else echo "$NAME: the device's apps differ from before this run: $(diff <(printf '%s\n' "$BEFORE") <(printf '%s\n' "$after") | grep '^[<>]' | tr '\n' ' ')" >&2; fi
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# 1. Build.
GRADLE_APP="$ROOT/keliver-compose-gatea-sample-ios/build/gateb-device/$TYPE/GateB.app"
if [ "$SKIP_BUILD" = 0 ]; then
  say "building the guest bundle and the $TYPE iosArm64 app (Gradle; log in $OUT/logs/gradle.log)"
  ( cd "$ROOT" && ./gradlew --console=plain -Pkeliver.gatebIos -PgatebTarget=device -PgatebBuildType="$TYPE" \
      -DRELEASE_SIGNING_ENABLED=false -Pkotlin.native.disableCompilerDaemon=true -Pkotlin.native.jvmArgs=-Xmx6g \
      :keliver-compose-gatea-sample-guest:compileProductionExecutableKotlinJsZipline \
      :keliver-compose-gatea-sample-ios:assembleGateBApp ) > "$OUT/logs/gradle.log" 2>&1 \
    || { grep -E '^e: |What went wrong' -A4 "$OUT/logs/gradle.log" | head -30 >&2; fail "the build failed; see $OUT/logs/gradle.log"; }
fi
[ -x "$GRADLE_APP/GateB" ] || fail "no app at $GRADLE_APP"
lipo -archs "$GRADLE_APP/GateB" 2>/dev/null | grep -qw arm64 || fail "$GRADLE_APP/GateB is not an arm64 binary"
BUNDLE="$(dirname "$(find "$ROOT/keliver-compose-gatea-sample-guest/build/zipline" -name manifest.zipline.json -path '*roduction*' | head -1)")"
[ -f "$BUNDLE/manifest.zipline.json" ] || fail "no production manifest under keliver-compose-gatea-sample-guest/build/zipline"
grep -q '"gateb-ed25519"' "$BUNDLE/manifest.zipline.json" || fail "the manifest is not signed with the scratch key"
BUNDLE_BYTES="$(find "$BUNDLE" -name '*.zipline' -exec cat {} + | wc -c | tr -d ' ')"

# 2. A profile for TEAM.BID, and the signed app.
rm -rf "$OUT/sign"; mkdir -p "$OUT/sign"
# Prints the path of an Xcode-managed profile for TEAM.BID: "valid" ones (unexpired, listing the
# phone) first, else "known" (a profile for the App ID has existed on the team) or nothing.
find_profile() {
  python3 -I - "$TEAM" "$BID" "$HWUDID" "$KNOWN_PROFILE" "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles" "$HOME/Library/MobileDevice/Provisioning Profiles" <<'PY'
import datetime, glob, os, plistlib, subprocess, sys
team, bid, udid, extra, *dirs = sys.argv[1:]
known, valid = None, None
for d in dirs + [extra]:
    for path in ([d] if d.endswith(".mobileprovision") else sorted(glob.glob(os.path.join(d, "*.mobileprovision")))) if d else []:
        try:
            raw = subprocess.run(["security", "cms", "-D", "-i", path], capture_output=True, check=True).stdout
            p = plistlib.loads(raw)
        except Exception:
            continue
        if team not in p.get("TeamIdentifier", []) or p.get("Entitlements", {}).get("application-identifier") != f"{team}.{bid}":
            continue
        known = known or path
        unexpired = p["ExpirationDate"] > datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None) + datetime.timedelta(hours=1)
        lists = udid in p.get("ProvisionedDevices", [])
        print(f"profile for the bundle id: expires {p['ExpirationDate']:%Y-%m-%d %H:%M} UTC, lists the phone: {lists}", file=sys.stderr)
        if unexpired and lists:
            valid = path
print(("valid\t" + valid) if valid else ("known\t" + known) if known else "none\t")
PY
}
IFS=$'\t' read -r PSTATE PPATH < <(find_profile)
say "a profile for the bundle id on the team: $PSTATE"
if [ "$PSTATE" = none ] && [ "$NEW_APP_ID" = 0 ]; then
  fail "no profile for $BID on the team has existed on this Mac (or in --known-profile), so its App ID may not exist and fetching one could register a new App ID. Pass --allow-new-app-id if that is intended."
fi
ENTITLEMENTS="$OUT/sign/entitlements.plist"
if [ "$PSTATE" != valid ]; then
  # A minimal wrapper app (one Objective-C file, no storyboard) that Xcode signs automatically for
  # TEAM and BID: that fetches (or renews) the development profile and gives the entitlements. It
  # may create a development certificate on the team (when none is in this keychain) or register
  # the App ID again if it was removed since its last profile. Never with
  # -allowProvisioningDeviceRegistration: this script does not add the phone to the team.
  W="$OUT/wrapper"; rm -rf "$W"; mkdir -p "$W/Wrapper.xcodeproj"
  printf '#import <UIKit/UIKit.h>\nint main(int argc, char *argv[]) { return UIApplicationMain(argc, argv, nil, nil); }\n' > "$W/main.m"
  cat > "$W/Wrapper.xcodeproj/project.pbxproj" <<'PBX'
// !$*UTF8*$!
{
	archiveVersion = 1;
	classes = {
	};
	objectVersion = 56;
	objects = {
		0A0000000000000000000001 = {isa = PBXBuildFile; fileRef = 0A0000000000000000000002; };
		0A0000000000000000000002 = {isa = PBXFileReference; lastKnownFileType = sourcecode.c.objc; path = main.m; sourceTree = "<group>"; };
		0A0000000000000000000003 = {isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = Wrapper.app; sourceTree = BUILT_PRODUCTS_DIR; };
		0A0000000000000000000004 = {isa = PBXGroup; children = (0A0000000000000000000002, 0A0000000000000000000005, ); sourceTree = "<group>"; };
		0A0000000000000000000005 = {isa = PBXGroup; children = (0A0000000000000000000003, ); name = Products; sourceTree = "<group>"; };
		0A0000000000000000000006 = {isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = (0A0000000000000000000001, ); runOnlyForDeploymentPostprocessing = 0; };
		0A0000000000000000000007 = {isa = PBXNativeTarget; buildConfigurationList = 0A0000000000000000000009; buildPhases = (0A0000000000000000000006, ); buildRules = (); dependencies = (); name = Wrapper; productName = Wrapper; productReference = 0A0000000000000000000003; productType = "com.apple.product-type.application"; };
		0A0000000000000000000008 = {isa = PBXProject; attributes = {LastUpgradeCheck = 1600; TargetAttributes = {0A0000000000000000000007 = {CreatedOnToolsVersion = 16.0; }; }; }; buildConfigurationList = 0A000000000000000000000A; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base, ); mainGroup = 0A0000000000000000000004; productRefGroup = 0A0000000000000000000005; projectDirPath = ""; projectRoot = ""; targets = (0A0000000000000000000007, ); };
		0A0000000000000000000009 = {isa = XCConfigurationList; buildConfigurations = (0A000000000000000000000B, ); defaultConfigurationIsVisible = 0; defaultConfigurationName = Debug; };
		0A000000000000000000000A = {isa = XCConfigurationList; buildConfigurations = (0A000000000000000000000C, ); defaultConfigurationIsVisible = 0; defaultConfigurationName = Debug; };
		0A000000000000000000000B = {isa = XCBuildConfiguration; buildSettings = {GENERATE_INFOPLIST_FILE = YES; INFOPLIST_KEY_UILaunchScreen_Generation = YES; PRODUCT_NAME = Wrapper; TARGETED_DEVICE_FAMILY = 1; }; name = Debug; };
		0A000000000000000000000C = {isa = XCBuildConfiguration; buildSettings = {IPHONEOS_DEPLOYMENT_TARGET = 16.0; SDKROOT = iphoneos; }; name = Debug; };
	};
	rootObject = 0A0000000000000000000008;
}
PBX
  say "fetching a development profile for the bundle id through a wrapper project (log in $OUT/logs/wrapper.log)"
  # Owner gate 0: Xcode needs an account for the team signed in (Xcode > Settings > Accounts).
  # Re-checked every 5 minutes for up to --gate-wait minutes, like the phone's gates below.
  deadline=$(( $(date +%s) + GATE_WAIT * 60 ))
  until ( cd "$W" && xcodebuild -project Wrapper.xcodeproj -target Wrapper -configuration Debug -sdk iphoneos \
      -allowProvisioningUpdates SYMROOT="$W/build" OBJROOT="$W/obj" \
      DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_STYLE=Automatic PRODUCT_BUNDLE_IDENTIFIER="$BID" build ) > "$OUT/logs/wrapper.log" 2>&1; do
    grep -E 'error:' "$OUT/logs/wrapper.log" | sed "s/$TEAM/<team>/g" | head -10 >&2
    grep -q 'No Accounts' "$OUT/logs/wrapper.log" || fail "the wrapper did not build and sign; see $OUT/logs/wrapper.log"
    echo "$(stamp) wrapper: OWNER GATE 0: Xcode has no account signed in (Xcode > Settings > Accounts: add the team's Apple account), so it cannot fetch a profile" | tee -a "$GATES" >&2
    [ "$(( $(date +%s) + 300 ))" -le "$deadline" ] || { echo "$(stamp) wrapper: gave up after $GATE_WAIT minutes" | tee -a "$GATES" >&2; fail "no profile: Xcode has no account for the team"; }
    say "re-checking in 5 minutes (until $(date -r "$deadline" +%H:%M))"
    sleep 300
  done
  WAPP="$W/build/Debug-iphoneos/Wrapper.app"
  [ -f "$WAPP/embedded.mobileprovision" ] || fail "the wrapper has no embedded profile"
  PPATH="$WAPP/embedded.mobileprovision"
  codesign -d --entitlements - --xml "$WAPP" > "$ENTITLEMENTS" 2>/dev/null || fail "could not read the wrapper's entitlements"
fi
cp "$PPATH" "$OUT/sign/profile.mobileprovision"
security cms -D -i "$OUT/sign/profile.mobileprovision" > "$OUT/sign/profile.plist" 2>/dev/null || fail "could not decode the profile"
# The profile must be the team's, for exactly this bundle id, listing the phone, unexpired; the
# identity is the one of its certificates whose private key is in this Mac's keychain.
IDENTITY="$(python3 -I - "$OUT/sign/profile.plist" "$TEAM" "$BID" "$HWUDID" "$ENTITLEMENTS" "$(security find-identity -v -p codesigning)" <<'PY'
import datetime, hashlib, os, plistlib, sys
path, team, bid, udid, ents, identities = sys.argv[1:7]
p = plistlib.load(open(path, "rb"))
def no(why): sys.exit("profile check: " + why)
if p.get("TeamIdentifier") != [team]: no("not the team's profile")
e = p.get("Entitlements", {})
if e.get("application-identifier") != f"{team}.{bid}": no("not for this bundle id")
if udid not in p.get("ProvisionedDevices", []): no("the phone is not in the profile")
if p["ExpirationDate"] < datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None): no("expired")
if not os.path.exists(ents) or os.path.getsize(ents) == 0:
    # A valid managed profile was found without the wrapper: the development entitlements it grants.
    plistlib.dump({"application-identifier": f"{team}.{bid}", "com.apple.developer.team-identifier": team,
                   "get-task-allow": bool(e.get("get-task-allow", True))}, open(ents, "wb"))
w = plistlib.load(open(ents, "rb"))
if w.get("application-identifier") != f"{team}.{bid}": no("the entitlements are not for this bundle id")
for cert in p.get("DeveloperCertificates", []):
    sha = hashlib.sha1(cert).hexdigest().upper()
    if sha in identities:
        print(sha); break
else:
    no("none of its certificates has a signing identity in this Mac's keychain")
PY
)" || fail "the profile cannot be used (see above)"
APP="$OUT/sign/GateB.app"
cp -R "$GRADLE_APP" "$APP"
plutil -replace CFBundleIdentifier -string "$BID" "$APP/Info.plist"
plutil -replace CFBundleSupportedPlatforms -json '["iPhoneOS"]' "$APP/Info.plist"
plutil -replace NSLocalNetworkUsageDescription -string "Loads the Gate B guest bundle from the development Mac on this network." "$APP/Info.plist"
cp "$OUT/sign/profile.mobileprovision" "$APP/embedded.mobileprovision"
codesign --force --sign "$IDENTITY" --entitlements "$ENTITLEMENTS" --generate-entitlement-der "$APP" > "$OUT/logs/codesign.log" 2>&1 \
  || { cat "$OUT/logs/codesign.log" >&2; fail "signing the app failed"; }
codesign --verify --strict "$APP" 2>> "$OUT/logs/codesign.log" || fail "the signed app does not verify"
codesign -dvv "$APP" 2>&1 | grep -qx "TeamIdentifier=$TEAM" || fail "the app is not signed by the team"
say "signed the $TYPE app for $BID with the team's development profile"

# 3. Serve the bundle on the Wi-Fi address.
IP="$(ipconfig getifaddr en0 || true)"
[ -n "$IP" ] || fail "this Mac has no address on en0 (Wi-Fi); the phone reaches the bundle server over it"
python3 -I -m http.server "$PORT" --bind "$IP" --directory "$BUNDLE" > "$OUT/logs/server.log" 2>&1 &
SERVER_PID=$!
MANIFEST="http://$IP:$PORT/manifest.zipline.json"
for _ in $(seq 1 50); do curl -sf -m 1 -o /dev/null "$MANIFEST" && break; sleep 0.2; done
curl -sf -m 2 "$MANIFEST" | cmp -s - "$BUNDLE/manifest.zipline.json" || fail "the bundle server did not serve the manifest on $PORT (is the port taken?)"
kill -0 "$SERVER_PID" 2>/dev/null || { SERVER_PID=""; fail "the bundle server exited (port $PORT taken?)"; }
say "serving $BUNDLE_BYTES bytes of modules on the Wi-Fi address, port $PORT"

# 4. Install. The build and owner gate 0 may have taken long since step 0, so the apps are listed
# again: $BID must still be absent. INSTALLED is set before the install, so an install that lands on
# the phone but still exits non-zero (a dropped network tunnel, a timeout) is uninstalled as well.
NOW="$(apps "$OUT/logs/apps-before-install.json")" || fail "could not list the device's apps again before installing"
if printf '%s\n' "$NOW" | grep -qxF "$BID"; then
  fail "$BID was installed on the device during this run, not by it: it is not this run's to replace or remove"
fi
BEFORE="$NOW"
INSTALLED=1
dc device install app --device "$DEVICE" "$APP" > "$OUT/logs/install.log" 2>&1 || { tail -5 "$OUT/logs/install.log" >&2; fail "install failed"; }
say "installed $BID"

# The installed bundle's location (file:///…/GateB.app/), from the phone's own entry for $BID.
# stop_app terminates only a process whose executable is inside it: every build of this prototype
# is a GateB.app, so the executable's name could also match another bundle id's copy. Without the
# location, only devicectl's --terminate-existing (which targets $BID) stops the previous launch.
APP_URL=""
if dc device info apps --device "$DEVICE" --json-output "$OUT/logs/apps-installed.json" >/dev/null 2>&1; then
  APP_URL="$(python3 -I -c 'import json, sys
for a in json.load(open(sys.argv[1]))["result"]["apps"]:
    if a.get("bundleIdentifier") == sys.argv[2] and a.get("url"):
        print(a["url"].rstrip("/") + "/"); break' "$OUT/logs/apps-installed.json" "$BID" || true)"
fi
[ -n "$APP_URL" ] || say "could not read where $BID is installed; launches rely on --terminate-existing alone"
pid_of_app() {
  [ -n "$APP_URL" ] || return 0
  dc device info processes --device "$DEVICE" --json-output "$OUT/logs/ps.json" >/dev/null 2>&1 || return 0
  python3 -I -c 'import json, sys
def norm(u): return u.replace("file:///private/var/", "file:///var/")
url = norm(sys.argv[2])
for p in json.load(open(sys.argv[1]))["result"]["runningProcesses"]:
    if norm(p.get("executable", "")).startswith(url): print(p["processIdentifier"]); break' "$OUT/logs/ps.json" "$APP_URL"
}
stop_app() {
  if [ -n "$CONSOLE_PID" ]; then kill "$CONSOLE_PID" 2>/dev/null || true; wait "$CONSOLE_PID" 2>/dev/null || true; CONSOLE_PID=""; fi
  local pid; pid="$(pid_of_app)"
  if [ -n "$pid" ]; then dc device process terminate --device "$DEVICE" --pid "$pid" >/dev/null 2>&1 || true; fi
}

# The app's own copy of its lines (Documents/gateb-<tag>.log, one file per launch), for when
# devicectl's console does not carry its stdout: copied into logs/<tag>.app.log.
CONSOLE_SILENT=0
copy_app_log() { # tag
  dc device copy from --device "$DEVICE" --domain-type appDataContainer --domain-identifier "$BID" \
    --source "Documents/gateb-$1.log" --destination "$OUT/logs/$1.app.log" >/dev/null 2>&1 || true
}

# Launches the app process-cold (terminate, 1 s, launch) with its console in logs/<tag>.log and
# waits for WAIT_FOR (default: the first-frame line in the launched mode). Sets LAUNCH_MS to the
# first-frame milliseconds (not printed: the console job must belong to this shell, so callers do
# not run it in a command substitution). Returns 2 on owner gate 1 (untrusted), 3 on owner gate 2
# (no network to this Mac), 1 otherwise.
LAUNCH_MS=""
launch() { # mode sample tag [capture file name]
  local log="$OUT/logs/$3.log" want="${WAIT_FOR:-first-frame}"
  local applog="$OUT/logs/$3.app.log"
  # GATEB_TAG names the app's own log file; GATEB_CAPTURE (only on capture launches) its PNG.
  local env="{\"GATEB_TAG\":\"$3\"}"
  [ -z "${4:-}" ] || env="{\"GATEB_TAG\":\"$3\",\"GATEB_CAPTURE\":\"$4\"}"
  LAUNCH_MS=""
  stop_app
  sleep 1
  : > "$log"; rm -f "$applog"
  dc device process launch --device "$DEVICE" --console --terminate-existing --environment-variables "$env" \
    "$BID" "$1" "$2" "$MANIFEST" > "$log" 2>&1 &
  CONSOLE_PID=$!
  local i rc
  for i in $(seq 1 450); do # 90 s: an empty-cache launch downloads every module over Wi-Fi
    # With a console known to be silent, the app's own file is read every 2 s.
    if [ "$CONSOLE_SILENT" = 1 ] && [ $((i % 10)) = 0 ]; then copy_app_log "$3"; fi
    rc=0; judge_launch "$1" "$log" "$applog" "$want" || rc=$?
    [ "$rc" = 4 ] || break
    if [ "$CONSOLE_SILENT" = 0 ] && [ -z "$(jobs -rp | grep -x "$CONSOLE_PID" || true)" ] && ! grep -q 'GATEB ' "$log"; then
      sleep 1; grep -qi 'explicitly trusted\|profile has not been\|invalid code signature' "$log" && return 2
      rc=1; break # devicectl gave up before the app said anything (its own file is read below)
    fi
    sleep 0.2
  done
  if [ "$rc" != 0 ] && [ "$rc" != 2 ] && [ "$rc" != 3 ] && ! grep -q 'GATEB ' "$log"; then
    # Nothing from the app on the console: its own file may say more (and switches to reading it).
    copy_app_log "$3"
    if grep -q 'GATEB ' "$applog" 2>/dev/null; then
      [ "$CONSOLE_SILENT" = 1 ] || echo "$(stamp) $3: the console carried none of the app's lines; reading the app's own log file from now on" | tee -a "$GATES" >&2
      CONSOLE_SILENT=1
      rc=0; judge_launch "$1" "$log" "$applog" "$want" || rc=$?
    fi
  fi
  # A launch's log holds the app's lines, from whichever source carried them.
  if [ -s "$applog" ] && ! grep -q 'GATEB ' "$log"; then tr -d '\r' < "$applog" >> "$log"; fi
  case "$rc" in 0|2|3) return "$rc" ;; esac
  echo "$NAME: no ${want} in mode $1 for $2; its log:" >&2; tail -30 "$log" >&2; return 1
}

# Judges a launch from what is in its logs so far: 0 done (LAUNCH_MS set for a first frame), 2 owner
# gate 1, 3 owner gate 2, 1 failed, 4 nothing yet.
judge_launch() { # mode console-log app-log want
  local lines line ms
  lines="$(cat "$2" "$3" 2>/dev/null | tr -d '\r' | grep 'GATEB ' || true)"
  if [ "$4" = first-frame ]; then
    line="$(printf '%s\n' "$lines" | grep -m1 'GATEB first-frame' || true)"
    if [ -n "$line" ]; then
      ms="$(printf '%s\n' "$line" | sed -n "s/.*GATEB first-frame mode=$1 ms=\([0-9][0-9]*\)\$/\1/p")"
      [ -n "$ms" ] && { LAUNCH_MS="$ms"; return 0; }
      return 1
    fi
  elif printf '%s\n' "$lines" | grep -q "$4"; then
    return 0
  fi
  if grep -qi 'explicitly trusted\|profile has not been\|untrusted developer\|invalid code signature' "$2"; then return 2; fi
  # Owner gate 2 only from the app's own code-load failure: devicectl's errors when the phone drops
  # off the network also say "offline", and say nothing about the app's Local Network permission.
  if printf '%s\n' "$lines" | grep 'GATEB codeLoadFailed' | grep -qi 'offline\|Code=-1009\|local network'; then return 3; fi
  printf '%s\n' "$lines" | grep -q 'codeLoadFailed\|uncaughtException\|manifestParseFailed\|unknownWidget\|capture failed' && return 1
  return 4
}

check_guest_log() { # tag
  local log="$OUT/logs/$1.log"
  grep -q 'manifestVerified key=gateb-ed25519 ' "$log" || { tail -30 "$log" >&2; fail "$1: the manifest was not verified with the scratch key"; }
  grep -q 'codeLoadSuccess' "$log" || { tail -30 "$log" >&2; fail "$1: no codeLoadSuccess"; }
  ! grep -q 'unknownWidget' "$log" || { tail -30 "$log" >&2; fail "$1: an unknown widget"; }
}

# Owner gates: a launch that needs the owner (trust the certificate; allow Local Network) is
# retried every 5 minutes for up to GATE_WAIT minutes, with the app left installed.
GATED_TAG=""
gated_launch() { # mode tag
  local deadline=$(( $(date +%s) + GATE_WAIT * 60 )) rc attempt=0
  while :; do
    attempt=$((attempt + 1))
    rc=0; launch "$1" orders "$2-$attempt" || rc=$?
    case "$rc" in
      0) GATED_TAG="$2-$attempt"; [ "$attempt" -gt 1 ] && echo "$(stamp) $2: passed on attempt $attempt" >> "$GATES"; return 0 ;;
      2) echo "$(stamp) $2: OWNER GATE 1: the developer certificate is not trusted on the phone (Settings > General > VPN & Device Management)" | tee -a "$GATES" >&2 ;;
      3) echo "$(stamp) $2: OWNER GATE 2: the app has no network to this Mac: allow Local Network for it (Settings > Privacy & Security > Local Network)" | tee -a "$GATES" >&2 ;;
      *) return 1 ;;
    esac
    stop_app
    [ "$(( $(date +%s) + 300 ))" -le "$deadline" ] || { echo "$(stamp) $2: gave up after $GATE_WAIT minutes" | tee -a "$GATES" >&2; return "$rc"; }
    say "re-checking in 5 minutes (until $(date -r "$deadline" +%H:%M))"
    sleep 300
  done
}

# 4b. The gates. The first guest launch after the install is also the first with an empty cache.
gated_launch native gate-native || fail "the app does not launch on the device (see $GATES and $OUT/logs/gate-native-*.log)"
gated_launch guest gate-guest || fail "the guest does not load on the device (see $GATES and $OUT/logs/gate-guest-*.log)"
check_guest_log "$GATED_TAG"
say "launches native and guest: ok"

# 5. Captures and pixels.
for sample in $SAMPLES; do
  for mode in native guest; do
    tag="shot-$sample-$mode"
    WAIT_FOR="GATEB capture saved $tag.png" launch "$mode" "$sample" "$tag" "$tag.png" \
      || fail "the $mode $sample capture did not finish"
    if [ "$mode" = guest ]; then check_guest_log "$tag"; fi
    dc device copy from --device "$DEVICE" --domain-type appDataContainer --domain-identifier "$BID" \
      --source "Documents/$tag.png" --destination "$OUT/$sample-$mode.png" > "$OUT/logs/copy-$tag.log" 2>&1 \
      || { cat "$OUT/logs/copy-$tag.log" >&2; fail "could not copy $tag.png out of the app's container"; }
    say "$sample/$mode: $(grep -o 'capture saved.*' "$OUT/logs/$tag.log" | tr -d '\r')"
  done
done

# 6. Warm timing, alternating.
: > "$OUT/timing.txt"
for i in $(seq 1 "$RUNS"); do
  for mode in native guest; do
    launch "$mode" orders "time-$mode-$i" || fail "timing launch $i ($mode) did not draw"
    if [ "$mode" = guest ]; then check_guest_log "time-$mode-$i"; fi
    echo "$mode $LAUNCH_MS" >> "$OUT/timing.txt"
  done
  say "warm run $i: $(tail -2 "$OUT/timing.txt" | tr '\n' ' ')"
done

# 7. Empty-cache timing.
for i in $(seq 1 "$RUNS"); do
  WAIT_FOR="GATEB cache cleared" launch clear-cache orders "clear-$i" || fail "clear-cache launch $i failed"
  grep -q 'cache cleared existed=true removed=true' "$OUT/logs/clear-$i.log" || fail "clear-cache launch $i did not remove a cache"
  launch guest orders "time-guest-empty-cache-$i" || fail "empty-cache launch $i did not draw"
  check_guest_log "time-guest-empty-cache-$i"
  echo "guest-empty-cache $LAUNCH_MS" >> "$OUT/timing.txt"
  say "empty-cache run $i: $LAUNCH_MS ms"
done
stop_app

# 8. The report (the cleanup trap uninstalls and stops the server).
DEVICE_LINE="$MODEL, iOS $OSVER, over $TRANSPORT; bundle over Wi-Fi http"
python3 -I "$ROOT/scripts/keliver-gateb-report.py" "$OUT" "Gate B part c: the phone run" "$DEVICE_LINE" \
  "$TYPE (Kotlin/Native iosArm64)" "$BUNDLE_BYTES" "$SAMPLES" \
  "Process-cold launches (terminate, 1 s, launch). native and guest alternate with a warm Zipline cache (a guest launch fetches the manifest over Wi-Fi and loads modules from the local cache); guest-empty-cache follows a launch that deleted the cache, so it downloads every module over Wi-Fi. Images: the app's own capture of its Compose content (a GraphicsLayer, as PNG), not a screenshot: no status bar." \
  || REPORT_FAILED=1
{
  echo "Device state (the app's line after each first frame; level in %, -1 if unknown), for the first and last launch of each route:"
  echo
  for route in native guest guest-empty-cache; do
    for f in $(printf '%s\n' 1 "$RUNS" | sort -un | sed "s#.*#$OUT/logs/time-$route-&.log#"); do
      [ -f "$f" ] && echo "- $(basename "$f" .log): $(grep -m1 -o 'GATEB device .*' "$f" | tr -d '\r' | sed 's/^GATEB device //')"
    done
  done
  echo
  [ -s "$GATES" ] && { echo "Owner gates:"; echo; sed 's/^/- /' "$GATES"; echo; }
} >> "$OUT/results.md"
tail -12 "$OUT/results.md"
[ -z "${REPORT_FAILED:-}" ] || fail "the comparison failed (see $OUT/results.md)"
say "results in $OUT/results.md"
