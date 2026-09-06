# Local candidate distribution, verified end to end (2026-09-06)

`scripts/build-portal-tools.sh 0.3.3-local` now ships the device path.

## What was added to the bundle

| addition | why |
|---|---|
| `bin/keliver-new-device-target.sh` | was missing entirely from the bundle |
| `bin/keliver-install-device-host.sh` | "install the generic host once" had no acquisition path |
| `host/keliver-device-host-<v>.apk` | the host itself, built by `:portal-device-android:assembleDebug` |
| `host/…apk.sha256` | identity, verified by the installer before it installs |
| `host/README.md` (`docs/DEVICE_HOST.md`) | the documented route and its limits |

**The APK is a locally built debug artifact shipped inside the bundle.** It is
not published to an app store, a Maven repository or a release page, and the
installer never downloads anything. This is stated in the script header, in
`DEVICE_HOST.md`, and in the installer's own output.

Framework dependencies remain **released** coordinates (`dev.keliver:*:0.3.3`
from Central) with `mavenLocal` disabled — the *tooling and host* are the local
candidate, not the framework.

## Verified from a clean directory outside the Keliver checkout

Device state was wiped first, so nothing depended on a preinstalled host:

```
adb uninstall dev.keliver.portaldevice   → device 'emulator-5554' not found
emulator … -wipe-data                    → booted
portaldevice installed?                  → 0
```

Then, using only the candidate bundle:

| step | result |
|---|---|
| 1. `bin/keliver-init Depot` | scaffolded |
| 2. `bin/keliver-new-device-target.sh` | `package: depot`, `HomeScreen` + `HomePresenter` |
| 3. `bin/keliver-install-device-host.sh --serial emulator-5554` | checksum matched, `Success`, `dev.keliver.portaldevice (versionName 1.0)` |
| 4. `./gradlew serveDevelopmentZipline` | `mavenLocal` occurrences in build files: **0**; bundle: 40 modules, `depot` module present |
| 5. launch | on screen: `Depot`, the starter subtitle, `First item`, `Second item`, `Refresh`; `codeLoadSuccess modules=40` |

## Identity

- host APK sha256 `b7903760d164ae99d7d57120e56c6301822e3861da14b564e814c3e0ca31e94b`
  (`keliver-device-host-0.3.3-local.apk.sha256`), verified by the installer
- installed package `dev.keliver.portaldevice`, versionName `1.0`
- guest manifest sha256 (first 32) `e0688869fb6add5e5cb1f38a607c39f4`, 40 modules
- screenshot `candidate-depot.png`, accessibility text `candidate-depot.txt`

## Scope

Publication remains out of scope: this is a reviewable, locally verified
distribution. The generic host stays deliberately small — `sample/host-android`
is the route to a real production host, and `DEVICE_HOST.md` says so rather
than growing this one.
