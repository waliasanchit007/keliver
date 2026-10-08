# Running your screens on a device

Keliver guests are not Android or iOS application modules. To see your screens
on a device you need a **host**: a small native app that loads your compiled
guest bundle over HTTP and renders it with native widgets.

On Android you have two options; most adopters want the first while they
develop. §3 is the iOS production host.

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

## 2. Your own production Android host

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
- **your servers** — `--bundle-server`: the host reads
  `<server>/bundles/index.json` and picks the newest bundle it can run. That is
  the file `keliver-publish` writes, so any static server or CDN works; the
  relay serves it too. Only when the index is a 404 — a relay from tools 0.3.7
  or earlier — does the host ask the relay's
  `GET /bundles/latest?widgetVersion=&caps=` instead. (Hosts scaffolded before
  this change know only `/bundles/latest`, so they need the relay.) Optionally, `--api-base-url`, which gives guests the
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
nothing falls back to the cache: that launch shows no bundle. With a static
server, that includes:
- an index entry whose `manifestSha256` isn't the manifest served (a `v<N>/`
  overwritten, or a stale CDN copy);
- a `v<N>/` that isn't there yet because the index was uploaded first.

Upload `v<N>/` before `index.json`, and never overwrite a `v<N>/` (the adopter
guide's "Publish from CI to a static server"). Falling back to the last good
bundle after such a failure is planned (W5 in Keliver's
`docs/DELIVERY_PLAN.md`).

**Rollback protection.** Every bundle `keliver-publish` or the relay publishes
carries its sequence inside the signed manifest (`metadata.keliver.sequence`).
The host remembers the highest sequence it has run for its key, and refuses a
manifest below it, from the network and from the cache. It also refuses one
with no sequence once it has run a sequenced one. So whoever controls the
bundle server can no longer serve a previous signed version. The floor rises
only after a bundle has loaded, so after Zipline verified its signature.
**Not protected:** a reinstall or "clear data" resets the floor; a host that
never ran a sequenced bundle accepts unsequenced ones.

**The iOS twin** is §3. **Publishing the bundles it loads.** `keliver-new-publish-target.sh` (run once,
after `keliver-new-device-target.sh`) gives `keliver.portal.json` a
`publishTask`/`publishOutput` for your app and appends the signing block to your
`build.gradle`. The block signs the compiled manifest inside Gradle; the key is
never on a command line, in a build log or in `.gradle/` (U31; to upgrade a
0.3.6 block, run the command again). `POST /publish` then stores a bundle only if
its manifest verifies against your store's public key: an unsigned or
foreign-signed bundle is refused and nothing is stored. **What you still set up
yourself:** your own signing config for a release APK.

The generic host above deliberately does not grow into that. It exists so that
a new adopter can see their screens on a device without writing Android code.

## 3. Your own production iOS host

`keliver-new-ios-host.sh` (run from the app root; scaffolding needs bash and
python3, building needs macOS with Xcode) writes `host-ios/`:
- **a Kotlin framework** (`KeliverHost`), a standalone Gradle build on Maven
  Central only;
- **an Xcode app** whose build phase runs
  `./gradlew -p host-ios embedAndSignAppleFrameworkForXcode`. Its
  `PRODUCT_NAME` must differ from `KeliverHost`; the scaffolder sees to that.

```bash
keliver-new-ios-host.sh --bundle-server URL [--api-base-url URL] [--bundle-id ID] [--public-key-file PATH]
```

It behaves like the Android host in §2:
- **Production-only.** Every manifest must verify against the public key in
  `HostConfig.kt`. Without a valid key the host fetches nothing and shows a
  refusal. There is no unverified fallback.
- **Startup.** The lookup runs first (10 s), then Zipline's verified cache when
  the lookup fails, then "No bundle". As on Android, a bundle that the lookup
  names but that then fails to load (a download error, or a `manifestSha256`
  mismatch) shows "No bundle" even with a cached one; see §2. There is one
  Zipline cache per key, and manifest URLs are followed only on the bundle
  server's origin. (Zipline's downloads follow HTTP redirects, as on Android.)
- **`HostHttp`** goes over `NSURLSession` to your API base only, when one is
  set. Paths can't climb out of it; a method that isn't a plain token, or a
  header holding CR or LF, is refused; hop-by-hop headers are dropped; and
  redirects aren't followed. (That last one is the delegate's job. It is not
  yet measured: no running guest has used HostHttp.)
- **Guest SQL** (`HostSqlDriver`) is real SQLite in Application Support, bound
  through `src/nativeInterop/cinterop/sqlite3.def`.
- **Images** load over the network (Coil with Ktor's Darwin engine).

Build-time checks (`host-ios/build.gradle`):
- `checkHostConfig` fails the build on a malformed key or server;
- `checkReleaseUrls`, on every release framework link, refuses `http://`
  servers, and refuses an `Info.plist` that still has any App Transport
  Security exception.

URLs follow one grammar in the scaffolder, the build and the host: plain
ASCII, a host name or `[IPv6]` with no `user@`, an optional port and path,
and no query, fragment or `$`. They become Kotlin string literals, where `$`
would be a template.

For development, `Info.plist` gets an App Transport Security exception only
for the `http://` hosts you scaffolded with. There is none for `https://`, and
none in a release build.

**Measured** on iOS simulators (the evidence is in the Keliver repository,
`docs/superpowers/evidence/ios-host-*`, and its `ios-host.yml` CI runs):
- signed v1 loads;
- an edit published as v2 is followed (Inventory → Stockroom);
- a bundle signed with another app's key is refused;
- the host recovers when the right relay is back;
- it starts offline from the cache;
- no empty-URL load.

**Not measured:**
- a physical iPhone;
- a release or App Store build;
- HTTPS end to end;
- taps (the CI has no iOS tap driver);
- HostHttp, network images and guest SQL in a running app (the reference
  guest uses none of them; the SQLite driver was tested on a macOS harness).

**Rollback protection** is as on Android (§2): a per-key floor in
`NSUserDefaults`, checked on the network and on the cache start. A reinstall
resets it.

