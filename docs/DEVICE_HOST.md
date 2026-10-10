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
  `src/main/assets/keliver/portal_ed25519.pub`; there is no development path (that is
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
- a `v<N>/` that isn't there yet because the index was uploaded first;
- a newest entry whose manifest is signed for a sequence below the host's
  rollback floor (below).

Upload `v<N>/` before `index.json`, and never overwrite a `v<N>/` (the adopter
guide's "Publish from CI to a static server"). Falling back to the last good
bundle after such a failure is planned (W5 in Keliver's
`docs/DELIVERY_PLAN.md`).

**Rollback protection.** Every bundle `keliver-publish` or the relay publishes
carries its sequence inside the signed manifest (`metadata.keliver.sequence`).
The host remembers the highest sequence it has run for its key: its floor.
Below the floor, a manifest is refused:
- on the network;
- from the cache;
- on the cache start's own network fallback.

Once the floor is above 0, a manifest with no sequence is refused too. The
floor rises only after a bundle has loaded, so after Zipline has verified its
signature. Once a host has run a sequenced bundle, whoever controls the bundle
server can't make it run an older one.

**Not protected:**
- A reinstall, or "clear data", resets the floor.
- A host whose floor is still 0 accepts any bundle your key signed: a new
  install, or one that has only ever run unsequenced (pre-W4) bundles. A
  server that only ever serves old unsequenced bundles keeps it there.

**One key, one sequence space.** The floor is per key, across every server and
route the host has used. A newest entry below a host's floor shows **no
bundle**: the same no-fallback rule as above. So never let sequences go
backwards. Ways a publisher can do that by mistake:
- an `--init` into an empty directory (it restarts at 1);
- alternating relay publishing (sequence = relay version) and `keliver-publish`
  (the index sequence) with the same key;
- a host once pointed at a development relay that published a high sequence
  with the production key.

**Rolling back** is therefore publishing again, never serving an older index:
`keliver-publish --republish <v>` publishes v`<v>`'s unchanged modules as a new
`v<N>/` at the next sequence, signed by the app's `keliverResign` task. Hosts
take it because it is newer.

**Channels** (both hosts). A host is built for one channel, `--channel` on
either scaffolder (default `stable`; Android `keliver.channel` in
`gradle.properties`, iOS `CHANNEL` in `HostConfig.kt`). It takes that channel's
index entries and stable's, the newest sequence of them. `keliver-publish
--channel beta` reaches only beta hosts; `--promote <sequence> --channel stable`
then offers the same bundle to everyone. Channels are selection, in the
unsigned index: the floor is per key, not per channel, so a host moved to
another channel (a rebuild) keeps its floor.

**Staged rollouts and host-version gates** (both hosts). An index entry may
carry `constraints`:
- **`rollout` (0–100):** an install takes the entry when its bucket is below
  it. The bucket is the first four bytes of `sha256("<install id>:<sequence>")`,
  mod 100. The install id is random, made once on the device, and never sent
  anywhere. A rollout gates only sequences above the host's floor, so lowering
  or halting it never takes a bundle away from a host that ran it.
- **`minHostVersion` / `maxHostVersion`:** compared with the host build's
  version (Android `versionCode`, iOS `CFBundleVersion`). A host whose version
  is not an integer skips gated entries. Gates hold below the floor too, because
  they are about what the code needs.

A host skipping an entry takes the newest one it may run. An entry with a
constraint key the host doesn't know is skipped, so hosts from before W4.5 skip
every constrained entry.


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
keliver-new-ios-host.sh --bundle-server URL [--api-base-url URL] [--bundle-id ID] [--public-key-file PATH] [--channel NAME]
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


## 4. Embedding the host in an existing app

`--embed` on either scaffolder writes the host of §2 or §3 as a part of an app
you already have, instead of as an app of its own. Nothing of your app is
edited; the scaffolder prints what to add. Not in a released tools bundle yet.

```bash
keliver-new-production-host.sh --embed --into DIR [--module keliver-host] --bundle-server URL \
    [--api-base-url URL] [--public-key-file PATH] [--channel NAME]
keliver-new-ios-host.sh --embed --into DIR [--module keliver-host-ios] --bundle-server URL \
    [--api-base-url URL] [--public-key-file PATH] [--channel NAME]
```

Run them from your Keliver app's root (the one with `keliver.portal.json`), as
for §2 and §3: they read its package and its store's public key, and the iOS
one copies its Gradle wrapper. The key checks, URL rules and refusals are the
same, and so is the host's behaviour: production only, the lookup first, the
verified cache, the rollback floor, channels, rollouts and host-version gates.

**Android: a library module.** `DIR/keliver-host/` is a `com.android.library`
with the same host Kotlin as `host-android/` (the self-test compares them byte
for byte):
- **`KeliverHost`**: the host. It owns its coroutine scope, the one Treehouse
  app and Zipline, the SQL and HTTP hosts and the image loader, for the life
  of the process. Create it once, in your `Application`:
  `KeliverHost.create(this)`, from `keliver.properties` and the key asset, or
  `KeliverHost.create(this, KeliverConfig(...))`. A second host for the same
  key fails loudly, because two loaders must never share a Zipline cache.
  `start()` is idempotent: the first screen calls it, or call it at launch to
  warm up. `state` is a `StateFlow` the screens observe.
- **`KeliverScreen(host, modifier)`**, a Composable, and **`KeliverView`**, an
  `AbstractComposeView` for View layouts (set its `host`). They only observe
  the host, so any number of them, recreated on every configuration change,
  share the one lookup and the one load.
- **Settings**: `keliver.properties` in the module (a subproject's
  `gradle.properties` is not read), each overridable with `-Pkeliver.<name>`.
  The host version that `minHostVersion`/`maxHostVersion` gates compare with is
  your app's `versionCode`, from `PackageManager`.
- **The trust root**: `src/main/assets/keliver/portal_ed25519.pub`. It sits
  under `keliver/` in both modes, because an app asset of the same name would
  silently replace a library's.
- **The manifest** asks only for `INTERNET`. Everything application-level is
  yours: cleartext for an `http://` development server (debug only; a release
  build of the module refuses `http://`), the theme, and backups. The
  standalone host sets `allowBackup="false"`; a library can't, so if your app
  allows backups, exclude the host's shared preferences (`keliver-host.xml`:
  the floor and the install id) with `dataExtractionRules` and
  `fullBackupContent`, or a restored device starts with another device's floor
  and rollout bucket.
- **R8**: the module's consumer rules keep Zipline's bridged services, which
  are called by name. A minified release of the reference app loads and
  renders (X7 below).
- **Your build supplies the plugins**: `com.android.library`,
  `org.jetbrains.kotlin.plugin.compose` and `app.cash.zipline` `1.22.0`, with
  Kotlin 2.2.0. The scaffolder warns when it can't find them.

**iOS: a framework build.** `DIR/keliver-host-ios/` is the `KeliverHost`
framework's Gradle build (the same Kotlin as `host-ios/`) with its own wrapper,
plus `KeliverScreen.swift` and `EMBED.md`:
- `public object Keliver { start(); viewController(safeArea:) }`, one per
  process; `KeliverScreen` is the SwiftUI wrapper around `viewController`.
  `MainViewController()` stays, as an alias, for the standalone app.
- The settings and key are in `src/iosMain/kotlin/<package>/HostConfig.kt`,
  checked by the build as in §3. `checkReleaseUrls` reads the Info.plist Xcode
  is building (`$SRCROOT/$INFOPLIST_FILE`), so a release build refuses an
  `http://` server or any App Transport Security exception in your app's
  Info.plist.
- `EMBED.md` has the Xcode edits: the Run Script phase (`embedAndSignAppleFrameworkForXcode`)
  before Compile Sources, `ENABLE_USER_SCRIPT_SANDBOXING = NO`, `-lsqlite3`,
  `CADisableMinimumFrameDurationOnPhone`, and adding `KeliverScreen.swift`.
- The scaffolder refuses an Xcode project that already names a product or
  module `KeliverHost`: Swift would then ignore `import KeliverHost`.

**When a load fails**, the Keliver view shows no guest screen, and nothing
else in your app is affected. A missing or invalid key shows the host's
refusal message in that view, as in §2. A bundle that the lookup named but
that then fails (another key's signature, a sha256 mismatch, a sequence below
the floor) leaves the view blank: there is no message for it yet, and no
fallback to the last good bundle (W5 in Keliver's `docs/DELIVERY_PLAN.md`).

**Measured** in Keliver's CI, on two plain apps standing in for an existing one
(`reference/embed/android`, a View-based app, and `reference/embed/ios`, a
SwiftUI app), each with only the documented edits, loading from a static HTTPS
server fed by `keliver-publish`:
- Android emulator: the native views and the guest's screen on one screen, the
  guest inside the `KeliverView` (X1); a signed load (X2); taps inside the
  embedded screen (X3); native navigation and a rotation with one lookup and
  one load (X4); the floor stored in the app's own data (X5); another key's
  bundle refused while the native views and the process stay (X6); an
  R8-minified release loading and rendering (X7);
- iOS simulator: the native views and the guest's screen in one screenshot
  (I1); a signed load (I2); the floor stored in the app's defaults (I4);
  another key's bundle refused while the native views stay (I5).

**Not measured:**
- native navigation on iOS (no tap driver on the simulator CI);
- an app on another Kotlin, AGP, Compose or OkHttp/Coil version than the one
  combination CI builds;
- an iOS app that already embeds a Kotlin framework (it would carry two Kotlin
  runtimes; put the host sources in that framework instead);
- an XCFramework instead of the build phase (documented in `EMBED.md`, not run);
- physical devices.

**Limits:**
- **Kotlin, on Android.** The module is compiled by your app's Kotlin, and
  Zipline 1.22.0's compiler plugin needs Kotlin 2.2.0. An app on another Kotlin
  can't embed it as source. A prebuilt, published host library would remove
  that; it isn't published.
- **The theme** is Material 1: your Material 3 theme doesn't reach the Keliver
  screen.
- **Dependency versions.** Compose, OkHttp and Coil versions resolve together
  with your app's, and a forced upgrade either way is not tested.
