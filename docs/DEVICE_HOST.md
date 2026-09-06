# Running your screens on an Android device

Keliver guests are not Android application modules. To see your screens on a
device you need a **host**: a small Android app that loads your compiled guest
bundle over HTTP and renders it with native widgets.

You have two options. Most adopters want the first.

## 1. The generic device host (zero Android code)

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
- **Debug artifact.** Unsigned for release, no verification of your bundle. It
  is a development tool, not a shipping container for your app.

## 2. Your own host

When you need your own Activity, branding, embedded bundles, signature
verification or store distribution, copy `sample/host-android` from the Keliver
repository. That is a complete, buildable Android application wired to a guest,
and it is the starting point for a production host.

The generic host above deliberately does not grow into that. It exists so that
a new adopter can see their screens on a device without writing Android code.
