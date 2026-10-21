# Device proof (W7): the checklist for a physical-device session

W7 in [`DELIVERY_PLAN.md`](DELIVERY_PLAN.md) closes gap G11. It needs a
physical Android phone (arm64), a physical iPhone, a release-signed APK and IPA,
HTTPS with a real certificate, and then one app that is not ours. This page is
the order of work for that session, the exact commands, and what each step
records.

**Status of this page (2026-10-12): written and rehearsed without hardware.**
No phone or iPhone was attached when it was written. The Android harness's
physical-device route has been run only on the CI emulator, as a rehearsal of
the harness: `reference-app.yml` run 38202773284 (`device_route=physical`),
prepare 28/0, device 130/0, with E1–E10 and D3 SKIP. That is not a device
result. The iOS device script (`ci/w7/ios-device.sh`) has never run on a
device; only its settings and device lookup were tried, with no iPhone attached.

Everything runs from a checkout of this repository on the owner's Mac. The app
and its tools come from the **published tools 0.3.12** and Maven Central, as in
CI.

---

## 0. Before you start (about 15 minutes)

- [ ] `git pull` on `main`, and note the commit (`git rev-parse HEAD`). It goes
      in the evidence.
- [ ] **JDK 17:** `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- [ ] **Android SDK platform-tools:** `adb version`. Use the SDK copy
      (`~/Library/Android/sdk/platform-tools/adb`), not an old one on `PATH`.
- [ ] **Xcode 16 or later** with its command-line tools: `xcodebuild -version`.
      `ci/w7/ios-device.sh` uses `devicectl device process launch --console`,
      which Xcode 15's `devicectl` does not have.
- [ ] **The tools zip, downloaded once.** If this network blocks GitHub release
      assets, download it on another network and pass the path:
      ```bash
      mkdir -p ~/w7/dl && cd ~/w7/dl
      base=https://github.com/waliasanchit007/keliver/releases/download/portal-tools-v0.3.12
      curl -fLO "$base/keliver-portal-tools-0.3.12.zip" && curl -fLO "$base/keliver-portal-tools-0.3.12.zip.sha256"
      shasum -a 256 -c keliver-portal-tools-0.3.12.zip.sha256
      # must print 4c9aac7453114718dc96483de709e8dcc47e7e5dc98ba6afc82c9b1373783d42
      shasum -a 256 keliver-portal-tools-0.3.12.zip
      ```
- [ ] **A fresh work directory per run**, e.g. `~/w7/run1`. The harness keeps
      `user.home`, the portal store and its disposable keys inside it. It never
      reads `~/.keliver-portal`.
- [ ] **Disk:** a run needs about 6 GB: the app, six APKs (three of
      `host-android`, three of the embed fixture) and the Gradle caches.

## 1. Android phone over USB (the harness)

### 1a. The phone

- [ ] **Developer options:** Settings → About phone → tap *Build number* seven
      times.
- [ ] **Developer options:**
  - turn on **USB debugging**;
  - turn off **Verify apps over USB**, if the phone offers it (otherwise Play
    Protect may stop an install and wait for a tap);
  - on Xiaomi/MIUI, also turn on **Install via USB** and **USB debugging
    (Security settings)**, or `input tap` is refused.
- [ ] **Screen lock: None or Swipe** for the session.
  - `device.sh` keeps the screen on while USB is connected and dismisses a
    swipe lock. A PIN or pattern stops every screen check.
  - The settings `device.sh` changes (stay awake, auto-rotate) are restored
    when it exits.
- [ ] **Optional:** Developer options → set all three animation scales to off,
      as CI does. The checks wait on fixed sleeps, and animations only slow them
      down.
- [ ] **Plug in** and accept the RSA prompt on the phone, then run
      `adb devices -l`. Note the serial.
- [ ] **Record the phone:**
      ```bash
      S=<serial>
      adb -s $S shell getprop ro.product.cpu.abi          # arm64-v8a expected (G11 asks for arm64)
      adb -s $S shell getprop ro.build.version.release
      adb -s $S shell getprop ro.product.model
      ```
      `device.sh` also writes these, with the build fingerprint, to
      `device-facts.txt`.

### 1b. Run the harness, physical-device route

```bash
cd <this checkout>
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
W=~/w7/run1; E=~/w7/run1-evidence
KELIVER_DEVICE=physical bash reference/inventory/ci/prepare.sh "$W" "$E" ~/w7/dl/keliver-portal-tools-0.3.12.zip
bash reference/inventory/ci/device.sh "$W" "$E" <serial>
```

**`prepare.sh` with `KELIVER_DEVICE=physical`:**
- It builds every host for `127.0.0.1`, not the emulator's `10.0.2.2`.
- It builds the HTTPS test APKs with the run's throwaway CA inside them
  (`ci/w7/android-test-ca.sh`).
  - A stock phone's system store takes no CA without root.
  - The CA only goes into the harness's work copies. No template or fixture in
    the repository changes.
  - The CA's private key is deleted once the server certificate is signed.

**`device.sh`** reads the route from the work dir. Then it:
- writes `device-facts.txt`;
- refuses hosts built for an emulator if the device is a phone;
- runs `adb reverse tcp:8077` (the relay) and `tcp:8443` (the static HTTPS
  server), so the phone reaches this Mac as `127.0.0.1` over the cable, with no
  Wi-Fi, LAN IP or firewall involved;
- removes both when it exits.

**Expected result.** `device: passed N, failed 0`, with these SKIP lines:

| row | on a phone | why |
|---|---|---|
| E1–E10, D3 | **SKIP** | The generic *development* host from the tools bundle has `http://10.0.2.2:8080` compiled in, and a phone cannot reach that. P4 still shows the D2 edit, signed, in production. |
| D2 | runs | It needs only the relay, not the device. |
| P1–P7, V2 | run | Production host against the relay, through `adb reverse :8077`. |
| S2–S15, X1–X7, U1, U2, V1 | run | The static HTTPS route, through `adb reverse :8443`. The test APKs trust the throwaway CA. |
| device screenshots | `screencap` only | Outside the pass/fail count. The emulator's host-side capture does not exist on a phone. |

**If something fails:**
- *No device / unauthorized:* replug the cable and accept the prompt.
- *`INSTALL_FAILED_USER_RESTRICTED`:* see the MIUI switches above.
- *Screen checks fail but the logcat rows pass:* the screen was locked or off.
  Unlock it and run again from a fresh work dir. (The lock-screen warning is
  best effort: Android versions report the keyguard differently.)
- *Every row after a point fails at once:* the USB connection dropped, which
  also drops `adb reverse`. Replug, then start again from a fresh work dir.
- *`INSTALL_FAILED_UPDATE_INCOMPATIBLE`:* an app with the same id but another
  signing key is installed. On the physical route `device.sh` uninstalls
  `inventory.host` and `com.example.existing` first, because each work dir has
  its own debug key. Uninstall anything else by hand.
- *X4 or R1 (rotation):* some launchers ignore `user_rotation`. Record it as a
  device quirk rather than a pass.
- **Never** delete `<app>/.gradle/` in the work dir. It holds
  `keliver-store-path`, the app's binding to its store.

### 1c. A release-signed APK (not the harness)

The harness installs debug builds, plus one R8 release signed with the debug key
(X7). G11 asks for a **release-signed** APK of the production host, which needs:
- your keystore;
- an `https://` server with a real certificate (§3). A release build refuses
  `http://`: `checkReleaseUrls`.

1. **Keystore.** Generate it once and keep it out of git:
   ```bash
   keytool -genkeypair -v -keystore ~/w7/release.jks -alias keliver -keyalg RSA -keysize 2048 -validity 10000
   ```
2. **Signing config.** In the app's `host-android/build.gradle`, add a
   `signingConfigs.release` that reads the keystore path and passwords from
   environment variables (never commit them), and set
   `buildTypes.release.signingConfig` to it.
3. **Build** against the real-certificate server:
   ```bash
   ./gradlew -p host-android assembleRelease -Pkeliver.bundleServer=https://<your server>
   ```
4. **Record the signature:**
   ```bash
   $ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs host-android/build/outputs/apk/release/*.apk
   ```
   Keep the certificate's SHA-256 digest and the APK's sha256.
5. **Install, launch and keep the log.** Uninstall the harness's debug build
   first: it is signed with another key, so `install -r` over it fails.
   ```bash
   adb uninstall inventory.host
   adb install -r <apk>
   adb logcat -c
   # launch the app from the phone's launcher
   adb logcat -d > logcat-release.txt
   ```
   **Expected:**
   - `KeliverHost: verifying manifests with portal-ed25519 <first 8 of your key>…`
   - `loading https://<server>/bundles/v<N>/manifest.zipline.json`
   - `codeLoadSuccess`

   A release build is not debuggable: `run-as` does not work, and none of the
   harness's prefs checks apply.
6. **Screenshot:** `adb exec-out screencap -p > release.png`. A phone's
   compositor is real, so this should not be black, unlike CI's emulator.

## 2. iPhone over USB

**What the owner must provide** (an agent cannot):
- **An Apple developer team id** (10 characters).
  - Xcode → Settings → Accounts, signed in with the Apple ID.
  - A free personal team can install on your own phone, but the profile expires
    after 7 days.
- **A bundle id that team may use.** The scaffolder's default is
  `<app package>.host`. Automatic signing registers it under the team, and a
  free team refuses ids that are already taken. If that happens, change
  `PRODUCT_BUNDLE_IDENTIFIER` in `host-ios/Configuration/Config.xcconfig`.
- **The iPhone prepared:**
  - unlocked, and "Trust this computer" accepted;
  - **Developer Mode** on (Settings → Privacy & Security → Developer Mode,
    which restarts the phone);
  - after the first install, trust the developer certificate under Settings →
    General → VPN & Device Management.
- **A bundle server the phone can reach.**
  - The iPhone has no `adb reverse`, so `localhost` on the phone is the phone
    itself.
  - Use the §3 real-certificate server (recommended), or for a Debug build
    `http://<this Mac's LAN IP>:8077` on the same Wi-Fi. From tools 0.3.11
    (KNOWN_BUGS U35/U36), the relay listens on this Mac only
    unless started with `KELIVER_RELAY_BIND=all` (tools 0.3.10 and earlier
    listen on every interface anyway). With it, anyone on that Wi-Fi can reach
    the relay's edit and publish endpoints, so use it only on a network you
    trust and only for this step. macOS may ask whether Java may accept
    incoming connections: allow it, or the phone gets no answer.
  - With a LAN IP, scaffold with `--bundle-server http://<LAN IP>:8077`, so the
    ATS exception names that host. Accept the **Local Network** prompt on the
    first launch. That prompt appears inside the script's first 40-second
    window, so expect D3 to FAIL on the very first launch; tap Allow, then run
    the script again.
  - Whether ATS accepts an exception for a bare IP address is not verified here.
    If the load fails with an ATS error (`-1022`), use the https route.

**Team setting:** a host scaffolded by tools 0.3.7 or 0.3.8 ignores `TEAM_ID`
(**U32** in `KNOWN_BUGS.md`; fixed in tools 0.3.9; this checklist uses 0.3.12).
For those older hosts, do one of:
- add `DEVELOPMENT_TEAM=<team id>` to `host-ios/Configuration/Config.xcconfig`;
- set `DEVELOPMENT_TEAM` in the environment for `ci/w7/ios-device.sh`, which
  passes it to `xcodebuild`;
- pick the team in Xcode under Signing & Capabilities.

A host with the U32 fix takes `TEAM_ID` from the gitignored
`Configuration/Config.local.xcconfig`. Run `ci/w7/ios-device.sh` *without*
`DEVELOPMENT_TEAM` once to check that path.

### 2a. Build, install and first load (`ci/w7/ios-device.sh`, unexecuted)

**Scaffold the host in the app,** e.g. in `~/w7/run1/boot/inventory` after
§1b:

```bash
$KP/keliver-new-ios-host.sh --bundle-server https://<your server>
```

`$KP` is `~/w7/run1/boot/tools/keliver-portal-tools-0.3.12/bin`. Run the
scaffolder with `JAVA_TOOL_OPTIONS="-Duser.home=$HOME/w7/run1/home"`, so it
finds the run's store and key.

**Find the phone:** `xcrun devicectl list devices` lists its Identifier (a
CoreDevice UUID). `xcodebuild -destination id=` needs the *hardware* UDID
(`00008xxx-…`; also in `xcrun xctrace list devices`). The script accepts either
and looks up the other through `devicectl list devices --json-output`.

**Run the script:**

```bash
DEVELOPMENT_TEAM=<team id> bash reference/inventory/ci/w7/ios-device.sh ~/w7/run1/boot/inventory ~/w7/ios-evidence <udid or identifier>
```

It does four things:
- **D1:** `xcodebuild … -destination id=<udid> -allowProvisioningUpdates`, then
  `codesign -dvv` to check the team and bundle id.
- **D2:** `devicectl device install app`, then
  `devicectl device process launch --console`, reading the host's stdout for
  40 s. Untested risk: if devicectl hands the app a pipe rather than a terminal,
  stdout may be buffered and lost when the script stops the launch. If D2 finds
  no `KeliverHost:` line although the screen shows the app, read the log in
  Console.app instead (select the iPhone, filter on `KeliverHost`) and record
  it by hand.
- **D3:** checks the log for verification with this app's key, a lookup at your
  server, `codeLoadSuccess`, and no refusal.
- **D4,** with `KELIVER_IOS_OFFLINE_CHECK=1`: you stop the server when asked,
  and it checks the start from the verified cache.

**Screenshots:** devicectl cannot take them. Take them on the phone
(side button + volume up) and AirDrop them into the evidence dir, or use
QuickTime's movie recording with the iPhone as the camera.

`ios.sh` (the simulator harness, 120 checks) is not ported to a device. Its
`simctl` steps (installing by container path, writing prefs, OCR on a
screenshot) have no devicectl equivalent here.

### 2b. A release-signed IPA

```bash
xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -configuration Release \
  -destination 'generic/platform=iOS' -archivePath ~/w7/host.xcarchive \
  DEVELOPMENT_TEAM=<team id> -allowProvisioningUpdates archive
cat > ~/w7/export.plist <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>debugging</string>
  <key>teamID</key><string>TEAMID</string>  <!-- replace with your team id -->
</dict></plist>
EOF
xcodebuild -exportArchive -archivePath ~/w7/host.xcarchive -exportPath ~/w7/ipa \
  -exportOptionsPlist ~/w7/export.plist -allowProvisioningUpdates
xcrun devicectl device install app --device <udid> ~/w7/ipa/*.ipa
```

- **Export method:**
  - `debugging` works with any team.
  - `release-testing` (ad hoc) or `app-store-connect` need a paid team. Name
    which one you used in the evidence: a development-signed IPA is not a
    distribution-signed one.
- **The Release configuration** runs the host's `checkReleaseUrls`, so it
  refuses `http://` servers and any ATS exception in the Info.plist. Re-scaffold
  for an `https://` server first.

## 3. HTTPS with a real certificate

The hosts take the server from a build setting:
- Android: `keliver.bundleServer`, or `-Pkeliver.bundleServer=…`;
- iOS: `BUNDLE_SERVER` in `HostConfig.kt`.

So the server must exist, with its final URL, **before** the release builds.
Publish into a directory with `keliver-publish` (the adopter guide, "Publish
from CI to a static server"), then serve that directory's `bundles/` over
HTTPS. Pick one option:

- **A static host at the root of an origin** (the shape CI tests: the index at
  `https://<host>/bundles/index.json`):
  - a GitHub Pages user or organisation site, or a custom domain;
  - Netlify, Cloudflare Pages, or an S3 bucket behind CloudFront.

  A GitHub Pages *project* site puts a path prefix before `bundles/`. The hosts
  append `bundles/index.json` to the server URL, but a prefix is **untested**.
  The bundles are signed code, readable by anyone who has the URL.
- **A tunnel to this Mac** (fastest; no account for a Cloudflare quick tunnel):
  ```bash
  python3 -m http.server 8000 --bind 127.0.0.1 -d <publish out dir>
  cloudflared tunnel --url http://127.0.0.1:8000
  ```
  - This prints an `https://<random>.trycloudflare.com` URL with a publicly
    trusted certificate.
  - The URL changes on every start, so build the hosts after starting it. A
    **debug** iOS build can instead take it at launch, without a rebuild:
    `xcrun devicectl device process launch --device <udid> --environment-variables
    '{"KELIVER_BUNDLE_SERVER":"https://<random>.trycloudflare.com"}' <bundle id>`
    (on a simulator, `SIMCTL_CHILD_KELIVER_BUNDLE_SERVER=… xcrun simctl launch`).
    A debug Android build reads it from the device: `adb shell run-as <application id>
    sh -c 'mkdir -p files/keliver && echo https://<random>.trycloudflare.com >
    files/keliver/bundle-server'`, then `adb shell am force-stop <application id>` and
    start it. Release builds ignore both. Every bundle still verifies against the
    built-in key, and a rollback floor raised from the tunnel stays.
  - A managed network (Netskope) may block the tunnel. A phone on mobile data
    or another Wi-Fi then works better.
- **A LAN server with a CA you install on the phones** (`mkcert`, or the
  harness's throwaway CA) works for testing, but it is **not** "a real
  certificate". Don't record it as one.

**Record:**
- the URL;
- the issuer: `openssl s_client -connect <host>:443 -servername <host> </dev/null | openssl x509 -noout -issuer -dates`;
- that each host loaded from it (its `loading https://…` line).

## 4. The real-app proof (W2 embed, then an adopter)

- **Embedding into an existing app from the published tools (0.3.9 on)** is in the adopter
  guide, "Ship to production → Embed in an existing app", and `DEVICE_HOST.md`
  §4.
  - Android: `keliver-new-production-host.sh --embed --into <app root>`.
  - iOS: `keliver-new-ios-host.sh --embed --into <app dir>`, plus the edits in
    `EMBED.md`.
- **The CI fixture** for the same steps is `reference/embed/{android,ios}`.
  Their `embed.diff` files hold exactly the documented edits, so compare your
  app's edits against them.
- **The Android limit:** the module compiles with *your* app's Kotlin, and only
  2.2.0 works (Zipline 1.22's compiler plugin). Check that first.
- **What counts as adoption:** an app the owner did not write for Keliver, on a
  physical device, with its own key and server. `reference/inventory`,
  `reference/embed` and `sample/` are ours: they are dogfooding.

## 5. What to record, and where

Commit **text** evidence only. `*.png` is LFS-tracked, so keep screenshots out
of the commit, or add them with `git lfs` deliberately. Use
`docs/superpowers/evidence/w7-<platform>-<date>/`, with:

- the checkout commit, the tools zip's sha256, and `device-facts.txt` (or the
  iPhone's `ios-device-details.txt`);
- `device.results` / `prepare.results` / `ios-device.results`, and the logcat or
  console files the rows cite;
- for the release builds: the APK or IPA sha256, the signing certificate's
  digest (`apksigner` / `codesign -dvv`), the export method, and the server's
  certificate issuer;
- a short `README.md` saying which rows passed, which were SKIP and why, and
  anything done by hand (screenshots, a launcher tap).

Then update the W7 row in `DELIVERY_PLAN.md`'s Status table with the run's
facts, and `REFERENCE_APP.md` if the harness changed.

## 6. What this does not prove

- **One phone model and one Android version** is one data point, not device
  coverage. The same goes for one iPhone.
- **The harness's HTTPS rows use a throwaway CA built into test APKs.** They
  prove the hosts' behaviour over TLS, not a public PKI deployment. §3 is what
  proves that.
- **The development route (E1–E10, D3) does not run on a phone.** The generic
  development host would need its server address as a setting.
- **No store distribution:** Play Store or TestFlight review, App Store signing,
  and staged store rollouts are outside this checklist.
- **The reference and embed apps are ours.** Until an outside app embeds Keliver,
  "external adoption" stays unproven.
