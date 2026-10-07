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
relay/bin/portal-relay             # the portal server alone
mcp/bin/portal-mcp                 # stdio MCP surface (for AI agents)
editor/                            # the wasm editor (static)
```

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
`dev.keliver:*:0.3.3` artifacts — edit them in your IDE (native completion) or
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
- **Doesn't (yet):** publish without the local relay (no headless CLI), serve
  bundles from a static host or CDN, or protect against rollback.

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
    [--application-id ID] [--public-key-file PATH]
./gradlew -p host-android assembleDebug
```

Writes `host-android/`: your app's production Android host, a standalone build
on Maven Central only. Production-only, verifying every bundle against the
public key it copies from your portal store; it refuses a `.priv` file and, when
your store resolves, any key that is not its `ed25519.pub`. Refuses without
changing anything if an input is wrong or `host-android/` exists. A release
build needs `https://` servers and your own signing config.

### bin/keliver-new-ios-host.sh

```bash
bin/keliver-new-ios-host.sh --bundle-server URL [--api-base-url URL] [--bundle-id ID] [--public-key-file PATH]
xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

Writes `host-ios/`: your app's production iOS host, a Kotlin framework on Maven
Central plus an Xcode app. It applies the same key checks and refusals as
`keliver-new-production-host.sh`. `host/README.md` §3 has what it does.

### bin/keliver-new-publish-target.sh

```bash
bin/keliver-new-device-target.sh      # first, if you have not: the bundle's entry point
bin/keliver-new-publish-target.sh
```

Wires `POST /publish` for your app: `publishTask`/`publishOutput` in
`keliver.portal.json` (the development Zipline bundle), and a signing block
appended to `build.gradle`, below `kotlin {}`, that signs with your store's
`keys/ed25519.priv`. The relay keeps a published bundle only if it verifies
against your store's public key, and refuses an unsigned one. Refuses without
changing anything if the app has no device target, already configures
`signingKeys`, or sets a different `publishTask`. Restart the portal afterwards:
the relay reads `keliver.portal.json` at start.

### bin/keliver-new-component.sh

Scaffold a project component ("molecule") built from keliver primitives:
`bin/keliver-new-component.sh MenuRow`. Reads `componentsDir` from
keliver.portal.json, derives the package from existing sources, and refuses to
overwrite. It appears under "Project components" in the editor palette and is
callable from any screen. Add `--slot` (`--slot SectionCard`) to scaffold a
container with one required editable trailing content slot. (Companion to
`bin/keliver-new-screen.sh`.)
