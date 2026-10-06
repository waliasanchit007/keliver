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

Shipping to real users needs your own host: your application id, your
portal's public key, signature verification always on. Scaffold one into your
app from this bundle — no Keliver checkout:

```bash
bin/keliver-new-production-host.sh --bundle-server https://bundles.example.com
./gradlew -p host-android assembleDebug        # release: https:// servers + your signing config
```

Run it from your app's root. It writes `host-android/`, a standalone Gradle
build that resolves Keliver from Maven Central only, and that is yours to edit
from then on:

- **production only** — every bundle's manifest must verify against
  `src/main/assets/portal_ed25519.pub`; there is no development path (that is
  what the generic host above is for);
- **your key, committed** — the scaffolder copies the PUBLIC key from your
  portal store (`keys/ed25519.pub`; `bin/keliver-store-path.sh <app>` prints
  where that is), or from `--public-key-file`. It never reads the private key.
  A missing or malformed key fails the build;
- **your servers** — `--bundle-server` (the relay's `/bundles` API, or anything
  implementing it: `GET /bundles/latest?widgetVersion=&caps=` answers with the
  newest bundle compatible with those capabilities, which a static file server
  cannot do as-is) and, optionally, `--api-base-url`, which gives guests the
  `HostHttp` capability over real HTTP, confined to that base (no `..`, no
  redirects, no guest `Host` header). Both live in
  `host-android/gradle.properties`. An `http://` server turns on cleartext
  **app-wide, in debug builds only** — fine for an emulator reaching
  `http://10.0.2.2:8077`; a **release** build with an `http://` server fails;
- `--application-id` defaults to `<your package>.host`;
- **the key check** — the scaffolder refuses a file whose real path ends in
  `.priv`, and a key that is not this app's store's `ed25519.pub` whenever the
  store resolves. A private key has the same format as a public one, so without
  a store it cannot tell them apart: pass only `ed25519.pub`. It never opens
  `ed25519.priv`.

At runtime:

| embedded key | what happens |
|---|---|
| present and valid | the latest compatible bundle's manifest is verified against it; a bundle signed by any other key is refused |
| missing or unusable | **refused** with a message; nothing is fetched (and the build would already have failed) |

A production session is never downgraded to "load it anyway". On each launch
the host asks the bundle server for the newest compatible bundle first (5 s to
connect, 10 s in all) and follows a manifest URL only on that server's own
origin:

| lookup | what loads |
|---|---|
| answers | the newest compatible bundle, from the network; once it loads, Zipline pins it in its cache |
| fails, and a bundle loaded before | the bundle pinned in Zipline's cache — the last one that loaded from the network — with its manifest verified again against the key; it stays in use until the next launch |
| fails, nothing loaded before | a "No bundle" screen |

The cache is used only on that second row because of how Zipline 1.22 loads:
it reads its cache *before* the network and only if the `FreshnessChecker`
accepts the cached manifest, and it never falls back to the cache after a
network failure. Treehouse's default checker accepts nothing, so the host passes
one that accepts the cached bundle for that start alone. A cached manifest that
no longer verifies is never run: Zipline re-verifies it while reading it, with
either checker, and throws. What the app then shows is not measured. Because
that check runs before the network, the host keeps one cache per key (named by
the key's first 16 hex digits): after an update that changes the embedded key,
the new key starts with an empty cache instead of one it cannot verify. That
key-change path is reasoned from Zipline's source, not run on a device.

When the lookup answers but the bundle itself then fails to download or verify,
nothing falls back to the cache: that launch shows no bundle.

**Not protected: rollback.** Any bundle signed with your key is accepted,
including an older one. Whoever controls the bundle server can serve a previous
signed version (over `http://`, so can anyone on the path).

**Publishing the bundles it loads.** `keliver-new-publish-target.sh` (run once,
after `keliver-new-device-target.sh`) gives `keliver.portal.json` a
`publishTask`/`publishOutput` for your app and appends the signing block to your
`build.gradle`, below `kotlin {}`. `POST /publish` then stores a bundle only if
its manifest verifies against your store's public key: an unsigned or
foreign-signed bundle is refused and nothing is stored. **What you still set up
yourself:** your own signing config for a release APK.

The generic host above deliberately does not grow into that. It exists so that
a new adopter can see their screens on a device without writing Android code.
