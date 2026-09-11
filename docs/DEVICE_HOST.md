# Running your screens on an Android device

Keliver guests are not Android application modules. To see your screens on a
device you need a **host**: a small Android app that loads your compiled guest
bundle over HTTP and renders it with native widgets.

You have two options. Most adopters want the first.

## 1. The generic device host (zero Android code) — **development only**

`keliver-portal-tools` ships a prebuilt host APK in `host/`, plus an installer.

```bash
# once per device/emulator
bin/keliver-install-device-host.sh              # picks the only attached device
bin/keliver-install-device-host.sh --serial emulator-5554
```

**This APK is a locally built debug artifact that ships inside the tools
bundle.** It is not published to an app store, a Maven repository or a release
page, and the installer never downloads anything. Its SHA-256 sits beside it in
`host/keliver-device-host-<version>.apk.sha256`, and the installer verifies it.

**It is development-only, and it is built that way deliberately.** It carries
**no portal public key**, so it has no identity to verify a signed bundle
against, and it **refuses production mode**:

```
$ adb shell am start -n dev.keliver.portaldevice/...MainActivity --es mode prod
```

shows, on the device, "Production mode refused" and tells you to build your own
host. It does not fall back to loading the bundle unverified — an earlier build
did exactly that, logging a warning and continuing with no signature checks.

The key-free property is enforced by the build, not left to the build machine:
the bundle is compiled with `-Pkeliver.devOnlyHost=true`, and
`scripts/build-portal-tools.sh` refuses to package an APK that contains
`assets/portal_ed25519.pub` (KNOWN_BUGS U22).

Then, from your app:

```bash
bin/keliver-new-device-target.sh                # once, adds the device entry point
./gradlew serveDevelopmentZipline               # serves your bundle on :8080
adb shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity
```

After each Kotlin change: rebuild, then force-stop and restart the host.

```bash
adb shell am force-stop dev.keliver.portaldevice
adb shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity
```

### What the host gives your guest

It binds services your presenters can take by name:

| name | type | use |
|---|---|---|
| `HostHttp` | `HostHttpProvider` | your `KeliverHttp` calls; routed through the relay's `/http-replay`, so responses come from your `portal-fixtures/http` set |
| `HostSqlDriver` | `HostSqlDriver` | SQLite for `portal-sql` |

### Limits

- **Emulator only.** The host reaches your machine at `10.0.2.2`, the emulator's
  alias for the host loopback. A physical device on your LAN cannot use this
  loop without rebuilding the host against your machine's address.
- **Debug artifact.** Unsigned for release, and it loads UNSIGNED development
  bundles from `serveDevelopmentZipline`. It is a development tool, not a
  shipping container for your app.
- **No production mode.** See above: it refuses, by design.

## 2. Your own production host

When you need your own Activity, branding, embedded bundles, signature
verification or store distribution, copy `sample/host-android` from the Keliver
repository. That is a complete, buildable Android application wired to a guest,
and it is the starting point for a production host.

**A production host must carry your portal's public key, and keeps signature
verification on.** Build it on a machine whose portal store holds
`keys/ed25519.pub` — `bin/keliver-store-path.sh <app>` prints where that is —
and the key is embedded as `assets/portal_ed25519.pub`. At runtime:

| requested mode | embedded key | what happens |
|---|---|---|
| development | irrelevant | unsigned dev bundle from `serveDevelopmentZipline` |
| production | present and valid | manifests verified against that key |
| production | missing or unusable | **refused** with a message; nothing is fetched |

The last row is the point: a production session is never downgraded to "load it
anyway". If your host refuses, the key is missing from the build, not from the
bundle — rebuild it where the store lives.

The generic host above deliberately does not grow into that. It exists so that
a new adopter can see their screens on a device without writing Android code.
