# keliver-portal-tools

The keliver visual portal — server, editor, MCP agent surface, and scaffolder —
runnable against your own app repo without cloning keliver. Requires **Java 17+**
and **python3**. `VERSION.json` records this bundle's tools version, the source
commit it was built from, and the Maven dependency version its scaffolders wire
into new projects — those are two independent version lines.

```
bin/keliver-init <AppName> [dir]   # scaffold a new keliver SDUI project
bin/keliver-portal [app-dir]       # run the visual editor + server against an app
bin/keliver-portal stop [app-dir]  # stop a running instance
bin/keliver-portal status [app-dir]# is it up?
bin/keliver-new-screen.sh          # (app root) a portal-editable screen + its presenter
bin/keliver-new-component.sh       # (app root) a project component ("molecule")
bin/keliver-new-editor.sh          # (app root) the app's own editor, previewing its real presenters
bin/keliver-new-device-target.sh   # (app root) the bundle entry point a device host loads
bin/keliver-install-device-host.sh # install host/'s development device host APK
bin/keliver-new-publish-target.sh  # (app root) wire POST /publish + bundle signing
bin/keliver-publish                # publish a signed bundle to a static host/CDN, no relay
bin/keliver-new-production-host.sh # (app root) your own production Android host
bin/keliver-new-ios-host.sh        # (app root) your own production iOS host
bin/keliver-run-android.sh         # (app root) your screens on a phone or emulator: build, install, start
bin/keliver-store-path.sh          # print the document store an app resolves to
bin/keliver-store-recover.sh       # rebind a store to an app that moved or was renamed
bin/keliver-adopt-legacy-store.sh  # copy the old shared ~/.keliver-portal store into one app's
bin/keliver-record-http.sh         # local client for recording HostHttp fixtures
relay/bin/portal-relay             # the portal server alone
mcp/bin/portal-mcp                 # stdio MCP surface (for AI agents)
editor/                            # the wasm editor (static)
host/                              # the development-only device host APK (below)
templates/, wrapper/               # what the scaffolders copy into your app
```

The full adopter guide — setup, the edit loops, devices, publishing, production
hosts — is
[`docs/PORTAL_ADOPTER_GUIDE.md`](https://github.com/waliasanchit007/keliver/blob/main/docs/PORTAL_ADOPTER_GUIDE.md)
on GitHub; an agent connected to `mcp/bin/portal-mcp` gets the same guide from
its `get_guide` tool.

`keliver-portal` waits for the server to actually answer before reporting
success, and prints the server log if it doesn't — it will not hand you a URL
for a server that failed to boot. If the app has its own editor (see
`keliver-new-editor.sh`), it is rebuilt and served so the preview runs your
**real presenters**; if that build fails the bundled generic editor is served
instead and the failure is reported. Add `--rerun-tasks` to force a clean editor
rebuild when webpack serves a stale distribution, or `--no-editor-build` to skip
it. Run state and logs live under `$TMPDIR/keliver-portal/<hash of app dir>`,
which is how `stop` shuts down exactly the processes this app started — a port
held by an unrelated project is reported, never killed.

## Quick start

```bash
export PATH="$PWD/bin:$PATH"
keliver-init Acme && cd acme
keliver-portal .                   # open http://localhost:8096
```

`keliver-init` creates a standalone Gradle project whose screens
(`src/jsMain/kotlin/screens/`) are real Kotlin Compose against the published
`dev.keliver:*:0.4.1` artifacts — edit them in your IDE (native completion) or
visually in the browser; both stay in sync via `keliver.portal.json`.

No install at all? The hosted playground: **http://keliver.me/keliver/**

## What this bundle does / doesn't do

- **Does:** the web portal loop (author screens visually or in code, live
  preview, the op engine, `.kt` write-back, MCP) against any app dir.
- **Also:** wire signed publishing into your app
  (`bin/keliver-new-publish-target.sh`; the signing runs in your app's own
  Gradle, and `POST /publish` keeps only bundles signed with your key), and
  scaffold your own production hosts for Android
  (`bin/keliver-new-production-host.sh`) and iOS (`bin/keliver-new-ios-host.sh`).
- **Also (from 0.3.8):** publish without the relay
  (`bin/keliver-publish`) to a static host or CDN (`bundles/index.json`), with
  rollback protection in the hosts it scaffolds.
- **Also (from 0.3.8):** `--embed` on both host scaffolders, to show Keliver
  screens inside an app you already have.
- **Also (from 0.3.8), in the hosts it scaffolds:** updates while the app
  runs (`checkForUpdate`, or on resume), the fall-back to the last good bundle,
  and host reports. The relay can collect those reports during development.
- **Key rotation (W8, from 0.3.9):** a host trusts this app's key and, during a rotation, the next one; the
  signing block signs with both; `keliver-publish` guards against a key change by mistake
  ([`KEY_ROTATION.md`](KEY_ROTATION.md)).
- **From 0.3.10:** new apps get the 0.3.4 libraries, and the scaffolders follow
  an existing app's `appRuntime.keliverVersion`; publishing builds
  Production bundles by default; debug hosts check for updates on resume, take
  "check now" from adb or a Darwin notification, and take their bundle server
  at launch; timing marks in debug builds and a benchmark build
  (`DEVICE_HOST.md`, "Timing marks"); the relay keeps every publish's full
  Gradle output and rereads `keliver.portal.json` per publish.
- **From 0.3.11:** the relay and the editor's file server listen on this
  machine only (`KELIVER_RELAY_BIND=all` opts the relay in to every interface),
  and the relay refuses requests from other sites' pages; the editor treats
  layout modifiers and `Margin`, design tokens, conditions and conditional
  values, string templates and lists with references as editable grammar
  instead of RawCode; the canvas can load app images through the relay
  (`previewAssetHosts`) and forwards a component's `modifier`; write-back
  refuses a change it cannot make in place (or as a lossless export) instead
  of overwriting the author's code.
- **From 0.3.12:** new apps get the 0.4.0 libraries; equality and compound
  conditions (`==`/`!=`, `&&`-only or `||`-only), derived Boolean and Int
  locals in a component body, Int arithmetic (`size / 2`,
  `b.columns * 48 + 8`) and Ints used as Dps (`Dp(gap.toDouble())`,
  `(b.gap + 4).dp`) are portal grammar instead of RawCode (the editor shows
  the condition or expression read-only and edits around it); a per-app
  editor on the 0.4.0 libraries can draw opaque components with the app's
  compiled code (`compiledComponents` in `keliver.portal.json`;
  `keliver-portal` writes the adapters before it builds that editor);
  contract write-back no longer redeclares a field or action the screen's
  interface inherits.
- **From 0.3.13:** new apps get the 0.4.1 libraries; the editor you work in
  (selection and hover boxes, components opened and edited in place, typed
  property controls, undo across documents, a palette preview, a compact
  publish card); a debug production host (Android) picks up a publish while
  the app is open (`keliver.updates=live`, the debug default; the relay's
  `GET /bundles/events`); `keliver-new-screen.sh` and
  `keliver-new-component.sh` write into the app they are run from;
  `keliver-portal` puts the editor on `editorPort` (default port + 19) so two
  apps' portals can run at once, and serves it uncached.
- **From 0.3.14:** still the 0.4.1 libraries; a debug iOS host picks up a
  publish while the app is open too (`UPDATES = "live"`); the host kit for an
  app that embeds Keliver screens, in both host templates: a typed status and
  `onUnavailable`, no host text in a release build, `RequestPolicy` for
  HostHttp, routes (`KeliverRoute`, the guest's `launchRoute`) and close, and
  on Android `ExternalLinkPolicy` and `screen-failed` (a new dependency,
  `androidx.browser:browser:1.8.0`); an existing guest needs `launchRoute`
  and `device/KeliverScreenHost.kt` (as `keliver-new-device-target.sh` now
  writes them) for routes.
- **From 0.3.15:** still the 0.4.1 libraries; `bin/keliver-run-android.sh`
  puts your screens on a phone or emulator in one command; `--embed` prints
  the module's dependency floor beside your app's versions before it writes;
  `screen-failed` on the iOS host too; and on Android, guests' `HostHttp`
  through your app's own authenticated `OkHttpClient`
  (`KeliverConfig.httpClient`), which needs a `RequestPolicy` and is used for
  nothing else (a guest's credential headers are dropped). On every host,
  the path-override headers are dropped and the URL going out is checked
  again after the client's interceptors. A host scaffolded earlier gets none
  of it until it is scaffolded again.
- **From the release after 0.3.15 (Android):** `TRACE`, `TRACK` and `CONNECT`
  are never sent through your app's own client, even when the
  `RequestPolicy` allows them (a `TRACE` would echo its credentials back to
  the guest). A host scaffolded earlier keeps sending them.

### `host/` — the device host is DEVELOPMENT-ONLY

`host/keliver-device-host-<version>.apk` lets you see your screens on an
emulator without writing any Android code. It carries **no portal public key**,
loads only the unsigned bundle your own `serveDevelopmentZipline` is serving,
and **refuses production mode** (`--es mode prod`) with an on-device message
rather than loading a production bundle unverified.

Shipping to real users needs **your own production host**:
`bin/keliver-new-production-host.sh` scaffolds one into your app (below), with
your portal's public key embedded and signature verification always on.
`host/README.md` has what it does and what it refuses. This separation is
deliberate — a generic binary that belongs to nobody has nothing to verify your
bundles against.

### bin/keliver-new-production-host.sh

```bash
bin/keliver-new-production-host.sh --bundle-server URL [--api-base-url URL] \
    [--application-id ID] [--public-key-file PATH] [--channel NAME]
./gradlew -p host-android assembleDebug
```

Writes `host-android/`: your app's production Android host, a standalone build
on Maven Central only. Production-only, verifying every bundle against the
public key it copies from your portal store; it refuses a `.priv` file and, when
your store resolves, any key that is not its `ed25519.pub`. Refuses without
changing anything if an input is wrong or `host-android/` exists. A release
build needs `https://` servers and your own signing config.

```bash
bin/keliver-new-production-host.sh --embed --into DIR [--module keliver-host] --bundle-server URL ...
```

With `--embed` (from 0.3.8) it writes the same host as a library module,
`DIR/keliver-host/`, into an existing Android app instead: `KeliverHost` (one
per process, made in your `Application`), the `KeliverScreen` Composable and
`KeliverView`. It edits none of your files and prints the lines to add. Your
build must supply Kotlin 2.2.0, its Compose plugin and `app.cash.zipline`
1.22.0. `host/README.md` §4 has the details.
From 0.3.15 it first prints the module's dependency floor: Kotlin,
coroutines, Compose, activity-compose, OkHttp, Coil, Zipline and the rest, as
the module resolves them (`templates/production-host-embed/dependency-floor.json`),
beside the versions your build declares. A declared version below the floor
is marked RAISED: that is the version your app will get.

### bin/keliver-run-android.sh (from 0.3.15)

```bash
bin/keliver-run-android.sh [--serial SERIAL] [app-dir]
```

Your published screens on a connected Android phone (USB or wireless
debugging) or emulator, in one command, with the portal running and a bundle
published. It scaffolds `host-android/` if the app has none, builds a debug
host that looks for bundles at `http://localhost:<port>` (a `-P` property:
your `gradle.properties` is not changed), runs `adb reverse` for that port
(nothing listens on your network), installs and starts it, and waits for the
host to load your bundle from the relay. A debug host scaffolded from tools
0.3.13 on then updates live on each publish. It never publishes and touches only the device you choose.

### bin/keliver-new-ios-host.sh

```bash
bin/keliver-new-ios-host.sh --bundle-server URL [--api-base-url URL] [--bundle-id ID] [--public-key-file PATH] [--channel NAME]
xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

Writes `host-ios/`: your app's production iOS host, a Kotlin framework on Maven
Central plus an Xcode app. It applies the same key checks and refusals as
`keliver-new-production-host.sh`. `host/README.md` §3 has what it does.
With `--embed --into DIR` (from 0.3.8) it writes only the framework's Gradle
build, `DIR/keliver-host-ios/`, for an existing Xcode app, plus
`KeliverScreen.swift` and `EMBED.md` with the Xcode edits; `host/README.md` §4.

### bin/keliver-publish (from 0.3.8)

```bash
keliver-publish [app-dir] --out DIR [--public-key-file PATH] [--channel stable] [--skip-build] [--init]
keliver-publish [app-dir] --out DIR [--public-key-file PATH] --republish VERSION [--channel NAME]
keliver-publish [app-dir] --out DIR [--public-key-file PATH] --promote SEQUENCE --channel NAME
keliver-publish [app-dir] --out DIR --set-rollout SEQUENCE --rollout PERCENT [--channel NAME]
```

Publishes without the relay, for CI and for static or CDN hosting. `DIR` must
hold the live `bundles/index.json` and `bundles/v<N>/`, downloaded from your
bundle server: without an index it refuses, unless `--init` marks the very
first publish. The adopter guide's "Publish from CI to a static server" has
the upload order.
1. It builds the app's `publishTask`.
2. It refuses the bundle and writes nothing (exit 4) unless the manifest is
   signed with the app's key and every module is present with its signed
   sha256.
3. It writes `DIR/bundles/v<N>/` and `DIR/bundles/index.json`, with the
   next sequence number.

The public key comes from:
- `--public-key-file`;
- else `KELIVER_PUBLIC_KEY_HEX`;
- else the app's store.

In CI, `KELIVER_SIGNING_KEY_FILE` names a file holding the private key. The
signing block reads it; nothing prints it.

`--republish VERSION` is a rollback. Hosts refuse a sequence below the highest
they have run, so older code goes out again as a new sequence: `vVERSION/`'s
modules are copied unchanged, the app's `keliverResign` task signs the copy for
the next sequence, and it becomes the next `v<N>/` and index entry
(`republishOf`). Nothing is compiled. Exit 3 if the re-sign fails.

Channels: `--channel beta` publishes to beta only. A host takes its own
channel (the scaffolders' `--channel`, default `stable`) and stable, so a beta
host is never behind stable. `--promote SEQUENCE --channel stable` offers an
already published bundle on stable too: a second index entry for the same
`v<N>/`, with nothing built or signed and no private key. It refuses when
that channel's hosts already take a higher sequence that asks no more of them.

Constraints, on the entry a publish, `--republish` or `--promote` writes:
`--rollout PERCENT` (a staged rollout), `--min-host-version N` and
`--max-host-version N` (the host build's integer version: Android
`versionCode`, iOS `CFBundleVersion`). `--set-rollout SEQUENCE --rollout
PERCENT` changes a rollout later: raise it, or set 0 to halt it. Hosts that ran
it keep it. It needs no key. Hosts from before these constraints skip any entry
that has them.

### bin/keliver-new-publish-target.sh

```bash
bin/keliver-new-device-target.sh      # first, if you have not: the bundle's entry point
bin/keliver-new-publish-target.sh
```

Wires `POST /publish` for your app: `publishTask`/`publishOutput` in
`keliver.portal.json` (the Production Zipline bundle: about half the size of the
Development one and faster to load; `--development` writes the unminified
Development pair instead, and an app that already has either pair keeps it.
Tools 0.3.9 and earlier wrote the Development pair), and a signing block
appended to `build.gradle` that signs the compiled manifest with your store's
`keys/ed25519.priv`, inside Gradle. The key is never passed on a command line,
logged, or stored in `.gradle/` (U31). The relay keeps a published bundle only
if it verifies against your store's public key, and refuses an unsigned one.
Refuses without changing anything if the app has no device target, configures
`signingKeys` itself, or sets a different `publishTask`. **An app wired by 0.3.6:
run it again** (from 0.3.7 or later): it replaces exactly the old block and
nothing else. Then delete the app's `.gradle/*/executionHistory/`, where the
old block left the key, but keep `.gradle/keliver-store-path`. No portal restart
is needed for this: the relay rereads `publishTask` and `publishOutput` from
`keliver.portal.json` on every publish. Any other key it reads at start, so a
change to one of those does need `keliver-portal stop` and a fresh start (a
publish names such keys in its response).

### bin/keliver-new-component.sh, bin/keliver-new-screen.sh

Run both from your app's root (the directory holding `keliver.portal.json`).

Scaffold a project component ("molecule") built from keliver primitives:
`keliver-new-component.sh MenuRow`. It writes `<componentsDir>/MenuRow.kt`
(`componentsDir` from keliver.portal.json, default: a `components/` directory
next to `screensDir`), derives the package from existing sources, and refuses
to overwrite. It appears under "Project components" in the editor palette and
is callable from any screen. Add `--slot` (`--slot SectionCard`) to scaffold a
container with one required editable trailing content slot.

`keliver-new-screen.sh Profile` is its companion for screens: it writes
`<screensDir>/profile.kt` (the screen and its Bindings interface) and a
hand-owned `ProfilePresenter.kt` in the `logic/` directory next to
`screensDir`, refusing to overwrite either.
