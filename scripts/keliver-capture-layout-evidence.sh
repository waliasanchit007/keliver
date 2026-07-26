#!/usr/bin/env bash
#
# Capture the K3 layout-evidence screen from web, Android, and iOS.
#
# Environment overrides:
#   K3_ANDROID_AVD=Pixel_9
#   K3_IOS_UDID=2CC81833-A4F4-46D4-919E-0CAEC06EC9CA
#   K3_OUTPUT_DIR=/absolute/path
#
# The script reuses healthy services and already-booted target devices. It
# stops only the services/devices it started and restores the relay's active
# screen even when a capture fails.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export PORTAL_REPO="$ROOT"

readonly RELAY_PORT=8077
readonly EDITOR_PORT=8096
readonly ZIPLINE_PORT=8080
readonly ANDROID_AVD="${K3_ANDROID_AVD:-Pixel_9}"
readonly IOS_UDID="${K3_IOS_UDID:-2CC81833-A4F4-46D4-919E-0CAEC06EC9CA}"
readonly ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
readonly ADB="$ANDROID_HOME/platform-tools/adb"
readonly EMULATOR="$ANDROID_HOME/emulator/emulator"
readonly CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
readonly BUNDLED_NODE="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node"
readonly NODE="${K3_NODE:-$(command -v node 2>/dev/null || printf '%s' "$BUNDLED_NODE")}"
readonly KELIVER_VERSION="${K3_KELIVER_VERSION:-$(jq -r '.appRuntime.keliverVersion' keliver.portal.json)}"
readonly WIDGET_VERSION="$(jq -r '.appRuntime.widgetVersion' keliver.portal.json)"
readonly OUTPUT_DIR="${K3_OUTPUT_DIR:-$ROOT/docs/superpowers/evidence/k3/$KELIVER_VERSION}"
readonly WEB_PNG="$OUTPUT_DIR/layout-evidence-web.png"
readonly ANDROID_PNG="$OUTPUT_DIR/layout-evidence-android.png"
readonly IOS_PNG="$OUTPUT_DIR/layout-evidence-ios.png"
readonly MANIFEST="$OUTPUT_DIR/manifest.json"
readonly ANDROID_COMPONENT="dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity"
readonly IOS_BUNDLE_ID="dev.keliver.sample.KeliverSample"

owned_pids=()
relay_started=false
editor_started=false
zipline_started=false
android_started=false
ios_started=false
previous_project=""
previous_screen=""
android_serial=""

step() {
  printf "\n\033[1;35m▸ %s\033[0m\n" "$1"
}

fail() {
  printf "\n\033[1;31merror:\033[0m %s\n" "$*" >&2
  exit 1
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  set +e

  if [[ -n "$previous_project" && -n "$previous_screen" ]]; then
    curl -fsS -X POST \
      "http://127.0.0.1:$RELAY_PORT/active?project=$previous_project&screen=$previous_screen" \
      >/dev/null
  fi

  if [[ "$ios_started" == true ]]; then
    xcrun simctl shutdown "$IOS_UDID" >/dev/null 2>&1
  fi
  if [[ "$android_started" == true && -n "$android_serial" ]]; then
    "$ADB" -s "$android_serial" emu kill >/dev/null 2>&1
  fi

  for pid in "${owned_pids[@]:-}"; do
    kill -TERM "$pid" >/dev/null 2>&1
  done

  exit "$status"
}
trap cleanup EXIT INT TERM

require_tools() {
  [[ -x "$ADB" ]] || fail "adb not found at $ADB"
  [[ -x "$EMULATOR" ]] || fail "Android emulator not found at $EMULATOR"
  [[ -x "$CHROME" ]] || fail "Google Chrome not found at $CHROME"
  [[ -n "$NODE" && -x "$NODE" ]] || fail "Node.js 22+ is required (override with K3_NODE)"
  [[ "$("$NODE" -p 'typeof WebSocket')" == "function" ]] ||
    fail "Node.js 22+ with built-in WebSocket support is required"
  command -v jq >/dev/null || fail "jq is required"
  command -v xcrun >/dev/null || fail "Xcode command-line tools are required"
  command -v sips >/dev/null || fail "sips is required"
  command -v swift >/dev/null || fail "Swift is required for screenshot content validation"
}

wait_for_url() {
  local url=$1
  local attempts=${2:-90}
  local i
  for ((i = 1; i <= attempts; i++)); do
    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  fail "timed out waiting for $url"
}

start_services() {
  step "Building the relay, editor, and development guest"
  ./gradlew -q \
    :portal-relay:installDist \
    :web-spike:wasmJsBrowserDevelopmentExecutableDistribution \
    :portal-device-guest:compileDevelopmentZipline

  if ! curl -fsS --max-time 2 "http://127.0.0.1:$RELAY_PORT/projects" >/dev/null 2>&1; then
    step "Starting relay on :$RELAY_PORT"
    "$ROOT/portal-relay/build/install/portal-relay/bin/portal-relay" \
      >"$ROOT/build/k3-relay.log" 2>&1 &
    owned_pids+=("$!")
    relay_started=true
  fi
  wait_for_url "http://127.0.0.1:$RELAY_PORT/projects"

  if ! curl -fsS --max-time 2 "http://127.0.0.1:$EDITOR_PORT/" >/dev/null 2>&1; then
    step "Serving evidence-mode editor on :$EDITOR_PORT"
    (
      cd "$ROOT/web-spike/build/dist/wasmJs/developmentExecutable"
      python3 -m http.server "$EDITOR_PORT"
    ) >"$ROOT/build/k3-editor.log" 2>&1 &
    owned_pids+=("$!")
    editor_started=true
  fi
  wait_for_url "http://127.0.0.1:$EDITOR_PORT/"

  if ! curl -fsS --max-time 2 "http://127.0.0.1:$ZIPLINE_PORT/manifest.zipline.json" >/dev/null 2>&1; then
    step "Serving development guest on :$ZIPLINE_PORT"
    (
      cd "$ROOT/portal-device-guest/build/zipline/Development"
      python3 -m http.server "$ZIPLINE_PORT"
    ) >"$ROOT/build/k3-zipline.log" 2>&1 &
    owned_pids+=("$!")
    zipline_started=true
  fi
  wait_for_url "http://127.0.0.1:$ZIPLINE_PORT/manifest.zipline.json"
}

select_evidence_screen() {
  local previous
  previous="$(curl -fsS "http://127.0.0.1:$RELAY_PORT/active")"
  previous_project="$(jq -r '.project' <<<"$previous")"
  previous_screen="$(jq -r '.screen' <<<"$previous")"

  curl -fsS -X POST \
    "http://127.0.0.1:$RELAY_PORT/active?project=default&screen=layout_evidence" \
    >/dev/null

  local tree
  tree="$(curl -fsS \
    "http://127.0.0.1:$RELAY_PORT/draft?project=default&screen=layout_evidence")"
  [[ "$tree" != "{}" ]] || fail "layout_evidence draft is empty"
  if jq -e '.. | objects | select(.type? == "RawCode")' <<<"$tree" >/dev/null; then
    fail "layout_evidence contains RawCode"
  fi
}

capture_web() {
  step "Capturing web at 402 × 874 CSS pixels"
  local profile="$ROOT/build/k3-chrome-profile-$$"
  local chrome_pid devtools_port i
  mkdir -p "$profile"
  "$CHROME" \
    --headless=new \
    --disable-gpu \
    --hide-scrollbars \
    --no-first-run \
    --force-device-scale-factor=1 \
    --window-size=402,874 \
    --remote-debugging-port=0 \
    "--remote-allow-origins=*" \
    --user-data-dir="$profile" \
    "http://127.0.0.1:$EDITOR_PORT/?evidence=1&width=402&height=874" \
    >"$ROOT/build/k3-chrome.log" 2>&1 &
  chrome_pid=$!
  owned_pids+=("$chrome_pid")

  for ((i = 1; i <= 30; i++)); do
    [[ -s "$profile/DevToolsActivePort" ]] && break
    sleep 1
  done
  [[ -s "$profile/DevToolsActivePort" ]] || fail "Chrome DevTools endpoint did not start"
  devtools_port="$(head -1 "$profile/DevToolsActivePort")"

  "$NODE" - "$devtools_port" "$WEB_PNG" <<'NODE'
const fs = require("node:fs");

(async () => {
const port = process.argv[2];
const output = process.argv[3];
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const pages = await fetch(`http://127.0.0.1:${port}/json`).then((response) => response.json());
const page = pages.find((candidate) => candidate.type === "page");
if (!page) throw new Error("Chrome did not expose a page target");

const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener("open", resolve, {once: true});
  socket.addEventListener("error", reject, {once: true});
});

let nextId = 0;
const pending = new Map();
socket.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  if (!message.id || !pending.has(message.id)) return;
  const {resolve, reject} = pending.get(message.id);
  pending.delete(message.id);
  if (message.error) reject(new Error(JSON.stringify(message.error)));
  else resolve(message.result);
});
const command = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++nextId;
  pending.set(id, {resolve, reject});
  socket.send(JSON.stringify({id, method, params}));
});

await command("Page.enable");
await command("Runtime.enable");
await command("Emulation.setDeviceMetricsOverride", {
  width: 402,
  height: 874,
  deviceScaleFactor: 1,
  mobile: false,
});
await command("Runtime.evaluate", {
  expression: `new Promise((resolve, reject) => {
    const deadline = Date.now() + 30000;
    const poll = () => {
      const canvas = document.querySelector("canvas");
      if (canvas && canvas.width > 0 && canvas.height > 0) resolve(true);
      else if (Date.now() > deadline) reject(new Error("Compose canvas did not mount"));
      else setTimeout(poll, 100);
    };
    poll();
  })`,
  awaitPromise: true,
  returnByValue: true,
});
await sleep(8000);
const capture = await command("Page.captureScreenshot", {
  format: "png",
  fromSurface: true,
  captureBeyondViewport: false,
});
fs.writeFileSync(output, Buffer.from(capture.data, "base64"));
socket.close();
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
NODE

  kill -TERM "$chrome_pid" >/dev/null 2>&1 || true
}

find_android_serial() {
  local serial
  while read -r serial _; do
    [[ "$serial" == emulator-* ]] || continue
    if [[ "$("$ADB" -s "$serial" emu avd name 2>/dev/null | tr -d '\r' | head -1)" == "$ANDROID_AVD" ]]; then
      printf '%s' "$serial"
      return 0
    fi
  done < <("$ADB" devices | tail -n +2)
  return 1
}

boot_android() {
  android_serial="$(find_android_serial || true)"
  if [[ -z "$android_serial" ]]; then
    step "Booting Android AVD $ANDROID_AVD"
    "$EMULATOR" \
      -avd "$ANDROID_AVD" \
      -no-snapshot-save \
      -no-audio \
      -no-boot-anim \
      -gpu swiftshader_indirect \
      >"$ROOT/build/k3-android-emulator.log" 2>&1 &
    owned_pids+=("$!")
    android_started=true

    local i
    for ((i = 1; i <= 180; i++)); do
      android_serial="$(find_android_serial || true)"
      if [[ -n "$android_serial" ]] &&
        [[ "$("$ADB" -s "$android_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        break
      fi
      sleep 1
    done
  fi

  [[ -n "$android_serial" ]] || fail "Android AVD $ANDROID_AVD did not attach"
  [[ "$("$ADB" -s "$android_serial" shell getprop sys.boot_completed | tr -d '\r')" == "1" ]] ||
    fail "Android AVD $ANDROID_AVD did not finish booting"
}

capture_android() {
  boot_android
  step "Installing and capturing Android on $ANDROID_AVD ($android_serial)"

  "$ADB" -s "$android_serial" shell settings put system font_scale 1.0
  "$ADB" -s "$android_serial" shell settings put global window_animation_scale 0
  "$ADB" -s "$android_serial" shell settings put global transition_animation_scale 0
  "$ADB" -s "$android_serial" shell settings put global animator_duration_scale 0
  "$ADB" -s "$android_serial" shell cmd uimode night no >/dev/null

  ./gradlew -q :portal-device-android:installDebug

  "$ADB" -s "$android_serial" logcat -c
  "$ADB" -s "$android_serial" shell am force-stop dev.keliver.portaldevice
  "$ADB" -s "$android_serial" shell am start -n "$ANDROID_COMPONENT" >/dev/null

  local i
  for ((i = 1; i <= 90; i++)); do
    if "$ADB" -s "$android_serial" logcat -d -s PortalDevice:D '*:S' |
      grep -q "codeLoadSuccess"; then
      sleep 3
      local android_log resumed_activity
      android_log="$("$ADB" -s "$android_serial" logcat -d)"
      if grep -Eq "codeLoadFailed|uncaughtException|FATAL EXCEPTION|IllegalStateException" \
        <<<"$android_log"; then
        printf '%s\n' "$android_log" >"$ROOT/build/k3-android-log.txt"
        fail "Android guest reported a post-load failure (see build/k3-android-log.txt)"
      fi
      resumed_activity="$("$ADB" -s "$android_serial" shell dumpsys activity activities |
        grep -m1 "mResumedActivity" || true)"
      [[ "$resumed_activity" == *"dev.keliver.portaldevice"* ]] ||
        fail "Android evidence app is not the resumed activity: $resumed_activity"
      "$ADB" -s "$android_serial" exec-out screencap -p >"$ANDROID_PNG"
      return
    fi
    sleep 1
  done
  "$ADB" -s "$android_serial" logcat -d -s PortalDevice:D '*:S' \
    >"$ROOT/build/k3-android-log.txt"
  fail "Android guest did not report codeLoadSuccess"
}

ios_runtime_identifier() {
  xcrun simctl list devices -j |
    jq -r --arg udid "$IOS_UDID" \
      '.devices | to_entries[] | select(any(.value[]; .udid == $udid)) | .key'
}

boot_ios() {
  local state
  state="$(xcrun simctl list devices -j |
    jq -r --arg udid "$IOS_UDID" \
      '.devices[][] | select(.udid == $udid) | .state')"
  [[ -n "$state" ]] || fail "iOS simulator $IOS_UDID was not found"

  if [[ "$state" != "Booted" ]]; then
    step "Booting iOS simulator $IOS_UDID"
    xcrun simctl boot "$IOS_UDID"
    ios_started=true
  fi
  xcrun simctl bootstatus "$IOS_UDID" -b
}

capture_ios() {
  boot_ios
  step "Building and capturing iOS"

  xcrun simctl ui "$IOS_UDID" appearance light
  xcrun simctl status_bar "$IOS_UDID" override \
    --time "09:41" \
    --dataNetwork wifi \
    --wifiMode active \
    --wifiBars 3 \
    --cellularMode active \
    --cellularBars 4 \
    --batteryState charged \
    --batteryLevel 100

  xcodebuild \
    -project "$ROOT/portal-device-ios-app/iosApp.xcodeproj" \
    -scheme iosApp \
    -configuration Debug \
    -sdk iphonesimulator \
    -destination "platform=iOS Simulator,id=$IOS_UDID" \
    -derivedDataPath "$ROOT/build/k3-ios-derived" \
    CODE_SIGN_IDENTITY="" \
    CODE_SIGNING_REQUIRED=NO \
    build \
    >"$ROOT/build/k3-ios-build.log"

  local app="$ROOT/build/k3-ios-derived/Build/Products/Debug-iphonesimulator/KeliverSample.app"
  [[ -d "$app" ]] || fail "iOS app was not produced at $app"
  xcrun simctl install "$IOS_UDID" "$app"
  xcrun simctl terminate "$IOS_UDID" "$IOS_BUNDLE_ID" >/dev/null 2>&1 || true

  local stdout_log="$ROOT/build/k3-ios-stdout.log"
  local stderr_log="$ROOT/build/k3-ios-stderr.log"
  : >"$stdout_log"
  : >"$stderr_log"
  local launch_result ios_pid
  launch_result="$(xcrun simctl launch \
    --stdout="$stdout_log" \
    --stderr="$stderr_log" \
    --terminate-running-process \
    "$IOS_UDID" \
    "$IOS_BUNDLE_ID" \
    -AppleLanguages "(en)" \
    -AppleLocale "en_US" \
    )"
  ios_pid="${launch_result##*: }"

  local i
  for ((i = 1; i <= 90; i++)); do
    if grep -q "codeLoadSuccess" "$stdout_log" "$stderr_log" 2>/dev/null; then
      sleep 3
      if grep -Eq \
        "codeLoadFailed|Uncaught Kotlin exception|IllegalStateException|fatal error" \
        "$stdout_log" "$stderr_log"; then
        fail "iOS guest reported a post-load failure (see build/k3-ios-*.log)"
      fi
      ps -p "$ios_pid" >/dev/null ||
        fail "iOS evidence app exited after codeLoadSuccess"
      xcrun simctl io "$IOS_UDID" screenshot "$IOS_PNG" >/dev/null
      return
    fi
    sleep 1
  done
  fail "iOS guest did not report codeLoadSuccess (see build/k3-ios-*.log)"
}

png_width() {
  sips -g pixelWidth "$1" 2>/dev/null | awk '/pixelWidth/ {print $2}'
}

png_height() {
  sips -g pixelHeight "$1" 2>/dev/null | awk '/pixelHeight/ {print $2}'
}

validate_png() {
  local file=$1
  [[ -f "$file" ]] || fail "missing capture: $file"
  [[ "$(xxd -p -l 8 "$file")" == "89504e470d0a1a0a" ]] || fail "not a PNG: $file"
  [[ "$(stat -f %z "$file")" -ge 20000 ]] || fail "capture is implausibly small: $file"
  [[ "$(png_width "$file")" -ge 320 ]] || fail "capture width is implausible: $file"
  [[ "$(png_height "$file")" -ge 480 ]] || fail "capture height is implausible: $file"
}

validate_evidence_colors() {
  local file=$1
  swift - "$file" <<'SWIFT'
import CoreGraphics
import Foundation
import ImageIO

let path = CommandLine.arguments[1]
let url = URL(fileURLWithPath: path) as CFURL
guard
  let source = CGImageSourceCreateWithURL(url, nil),
  let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
else {
  fputs("cannot decode \(path)\n", stderr)
  exit(2)
}

let width = image.width
let height = image.height
let bytesPerRow = width * 4
var pixels = [UInt8](repeating: 0, count: height * bytesPerRow)
guard
  let context = CGContext(
    data: &pixels,
    width: width,
    height: height,
    bitsPerComponent: 8,
    bytesPerRow: bytesPerRow,
    space: CGColorSpaceCreateDeviceRGB(),
    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
  )
else {
  fputs("cannot allocate bitmap context for \(path)\n", stderr)
  exit(2)
}
context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))

let targets: [(String, Int, Int, Int)] = [
  ("blue", 0x25, 0x63, 0xEB),
  ("purple", 0x93, 0x33, 0xEA),
  ("rose", 0xE1, 0x1D, 0x48),
  ("navy", 0x0F, 0x17, 0x2A),
]
var counts = [Int](repeating: 0, count: targets.count)
for offset in stride(from: 0, to: pixels.count, by: 4) {
  for (index, target) in targets.enumerated() {
    if abs(Int(pixels[offset]) - target.1) <= 16,
       abs(Int(pixels[offset + 1]) - target.2) <= 16,
       abs(Int(pixels[offset + 2]) - target.3) <= 16 {
      counts[index] += 1
    }
  }
}

for (index, target) in targets.enumerated() where counts[index] < 50 {
  fputs(
    "\(path) lacks the K3 \(target.0) sentinel (found \(counts[index]) pixels)\n",
    stderr
  )
  exit(1)
}
SWIFT
}

write_manifest() {
  local android_model android_api android_density android_locale android_font_scale
  local ios_runtime_id ios_runtime ios_model chrome_version source_commit captured_at

  android_model="$("$ADB" -s "$android_serial" shell getprop ro.product.model | tr -d '\r')"
  android_api="$("$ADB" -s "$android_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
  android_density="$("$ADB" -s "$android_serial" shell wm density | tail -1 | awk '{print $NF}')"
  android_locale="$("$ADB" -s "$android_serial" shell getprop persist.sys.locale | tr -d '\r')"
  android_font_scale="$("$ADB" -s "$android_serial" shell settings get system font_scale | tr -d '\r')"
  ios_runtime_id="$(ios_runtime_identifier)"
  ios_runtime="${ios_runtime_id##*.iOS-}"
  ios_runtime="iOS ${ios_runtime//-/.}"
  ios_model="$(xcrun simctl list devices -j |
    jq -r --arg udid "$IOS_UDID" '.devices[][] | select(.udid == $udid) | .name')"
  chrome_version="$("$CHROME" --version)"
  source_commit="$(git rev-parse HEAD)"
  captured_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

  jq -n \
    --arg capturedAt "$captured_at" \
    --arg keliverVersion "$KELIVER_VERSION" \
    --argjson widgetVersion "$WIDGET_VERSION" \
    --arg sourceCommit "$source_commit" \
    --arg chromeVersion "$chrome_version" \
    --arg androidAvd "$ANDROID_AVD" \
    --arg androidSerial "$android_serial" \
    --arg androidModel "$android_model" \
    --arg androidApi "$android_api" \
    --arg androidDensity "$android_density" \
    --arg androidLocale "${android_locale:-en-US}" \
    --arg androidFontScale "$android_font_scale" \
    --arg iosModel "$ios_model" \
    --arg iosRuntime "$ios_runtime" \
    --arg iosUdid "$IOS_UDID" \
    --arg webSha "$(shasum -a 256 "$WEB_PNG" | awk '{print $1}')" \
    --arg androidSha "$(shasum -a 256 "$ANDROID_PNG" | awk '{print $1}')" \
    --arg iosSha "$(shasum -a 256 "$IOS_PNG" | awk '{print $1}')" \
    --argjson webWidth "$(png_width "$WEB_PNG")" \
    --argjson webHeight "$(png_height "$WEB_PNG")" \
    --argjson androidWidth "$(png_width "$ANDROID_PNG")" \
    --argjson androidHeight "$(png_height "$ANDROID_PNG")" \
    --argjson iosWidth "$(png_width "$IOS_PNG")" \
    --argjson iosHeight "$(png_height "$IOS_PNG")" \
    '{
      formatVersion: 1,
      capturedAt: $capturedAt,
      runtime: {
        keliverVersion: $keliverVersion,
        widgetVersion: $widgetVersion
      },
      source: {
        commit: $sourceCommit,
        screen: "portal-app-lib/src/commonMain/kotlin/screens/layout_evidence.kt",
        project: "default",
        relayScreen: "layout_evidence"
      },
      environment: {
        theme: "light",
        locale: "en-US",
        fontScale: 1.0,
        animations: "disabled where host controls permit"
      },
      captures: {
        web: {
          file: "layout-evidence-web.png",
          sha256: $webSha,
          browser: $chromeVersion,
          viewportCssPixels: {width: 402, height: 874},
          screenshotPixels: {width: $webWidth, height: $webHeight},
          deviceScaleFactor: 1
        },
        android: {
          file: "layout-evidence-android.png",
          sha256: $androidSha,
          avd: $androidAvd,
          serial: $androidSerial,
          model: $androidModel,
          api: $androidApi,
          screenshotPixels: {width: $androidWidth, height: $androidHeight},
          densityDpi: $androidDensity,
          locale: $androidLocale,
          theme: "light",
          fontScale: $androidFontScale
        },
        ios: {
          file: "layout-evidence-ios.png",
          sha256: $iosSha,
          model: $iosModel,
          runtime: $iosRuntime,
          udid: $iosUdid,
          screenshotPixels: {width: $iosWidth, height: $iosHeight},
          locale: "en-US",
          theme: "light"
        }
      },
      assumptions: [
        "All hosts render the relay-recognized layout_evidence tree.",
        "The image has fixed geometry; image payload timing does not affect layout.",
        "Review compares labeled geometry, not font rasterization or raw pixel equality."
      ]
    }' >"$MANIFEST"
}

main() {
  require_tools
  mkdir -p "$OUTPUT_DIR" "$ROOT/build"
  start_services
  select_evidence_screen

  capture_web
  capture_android
  capture_ios

  step "Validating captures and writing manifest"
  validate_png "$WEB_PNG"
  validate_png "$ANDROID_PNG"
  validate_png "$IOS_PNG"
  validate_evidence_colors "$WEB_PNG"
  validate_evidence_colors "$ANDROID_PNG"
  validate_evidence_colors "$IOS_PNG"
  write_manifest

  printf "\n\033[1;32m✓ K3 evidence captured\033[0m\n"
  printf "  %s\n" "$OUTPUT_DIR"
}

main "$@"
