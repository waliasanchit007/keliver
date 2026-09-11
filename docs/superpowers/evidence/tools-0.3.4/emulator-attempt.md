# Device verification — attempted, then stopped. Still BLOCKED. (2026-09-11)

The bundled `keliver-device-host-0.3.4.apk` has **not** been installed or
launched. What follows is what was set up, what actually happened, and exactly
what is needed to finish.

## What was installed, and where

Entirely isolated. `~/Library/Android/sdk` was never read for this, never
modified, and nothing existing was deleted.

```
SDK   <scratch>/android-sdk-iso      (removed afterwards, see below)
AVD   <scratch>/avd-iso              (removed afterwards)
```

```bash
# command-line tools
curl -O https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip
# packages, into the isolated root
sdkmanager --sdk_root=<iso> platform-tools emulator \
           "system-images;android-33;aosp_atd;arm64-v8a"
avdmanager create avd -n keliver-u22 \
           -k "system-images;android-33;aosp_atd;arm64-v8a" -d pixel_5
```

`sdkmanager` could not reach `dl.google.com` until the machine's TLS-inspecting
proxy was accounted for — it reported *"Failed to download any source lists"*
and *"IO exception while downloading manifest"* until run with:

```bash
JAVA_OPTS="-Djavax.net.ssl.trustStore=$HOME/.android-certs/jssecacerts -Djavax.net.ssl.trustStorePassword=changeit"
```

AVD: `aosp_atd` (the smallest arm64 image, ~1.4 GB), `arm64-v8a`, `pixel_5`,
`hw.ramSize = 1536M`, API 33 — comfortably above the host's `minSdk 21`.

## What actually happened

The emulator refuses to start unless free disk is roughly 3.6x the userdata
partition size. At the AVD default of 6 GiB:

```
FATAL | Not enough space to create userdata partition.
        Available: 3907.48 MB ... need 7372.80 MB.
```

That requirement **is** reducible — an earlier note here said it was fixed, and
that was wrong. With `disk.dataPartition.size = 800M` the space check passes and
the emulator proceeds. Two separate things had confused the picture: the value
reverted to the 6 GiB default at one point, and one 800M trial was killed by a
timeout before it logged anything.

So the emulator was startable. It was **not** booted, by decision rather than by
obstacle: the machine's single APFS container was at 100% with ~3.3 GB free at
that moment, an 800 MB userdata leaves almost no headroom, and the packaged
acceptance's `--serial` path additionally runs Gradle inside a scaffolded app
(`compileKotlinJs`, `serveDevelopmentZipline`). Filling a user's boot volume to
run a verification is not a trade worth making.

The isolated SDK was then deleted rather than left holding 5.5 GB of a full
disk. The commands above restore it in a few minutes.

## What finishing this needs

1. **Disk**: ~10 GB free — about 7.4 GB for a default-sized AVD (or ~3 GB with
   `disk.dataPartition.size = 800M`), plus the scaffolded app's Gradle work.
2. Then, against the APK extracted from the verified ZIP — **not** a rebuilt one:
   - `keliver-adopter-acceptance.sh <parent> <zip> --serial <serial>`, which
     installs the bundled host and drives the development route;
   - `adb shell am start -n dev.keliver.portaldevice/...MainActivity --es mode prod`,
     expecting "Production mode refused" on screen, no manifest or bundle
     request in the relay log, and the development route still working after.

Neither APK inspection nor `HostTrustPolicyTest` substitutes for that: they
establish the packaging and the decision function, not the installed binary.

## Artifacts this would have been run against

| | sha256 |
|---|---|
| `keliver-portal-tools-0.3.4.zip` | `aecb3737c1d49591311fde2d9729bc5918b849b318a2dbaf76ab1d360a94788c` |
| `keliver-device-host-0.3.4.apk` (extracted from it) | `5ca96302df46fc4815a76d4487b0913ba5d160589f3f166f4defbb656b2c5a44` |
