# Keliver Portal — adopter guide

For teams using the **`keliver-portal-tools` package** on their own app. Every
command here comes from that package or from your app's Gradle wrapper.

> Contributors working inside the Keliver repository itself want
> [`PORTAL_USAGE.md`](PORTAL_USAGE.md) instead. That guide drives Keliver's own
> dev script, its own app module and its own Gradle tasks — none of which exist
> in your app. Nothing in this document depends on a Keliver checkout.

## Prerequisites

* **JDK 17 or later.** Verified on 17 and 21. If your default `java` is older,
  point `JAVA_HOME` at a 17+ JDK.
* **Python 3** — a few of the packaged scripts use it.
* The unpacked **`keliver-portal-tools`** package. The current release is
  **[tools 0.3.15](https://github.com/waliasanchit007/keliver/releases/tag/portal-tools-v0.3.15)**:

  ```bash
  curl -LO https://github.com/waliasanchit007/keliver/releases/download/portal-tools-v0.3.15/keliver-portal-tools-0.3.15.zip
  curl -LO https://github.com/waliasanchit007/keliver/releases/download/portal-tools-v0.3.15/keliver-portal-tools-0.3.15.zip.sha256
  shasum -a 256 -c keliver-portal-tools-0.3.15.zip.sha256   # sha256sum -c on Linux

  unzip keliver-portal-tools-0.3.15.zip
  export KP="$PWD/keliver-portal-tools-0.3.15/bin"
  ```

  The `.sha256` file is published beside the zip on the release page, so the
  check above verifies the download against what was actually released. (This
  document ships *inside* the bundle, so it deliberately does not quote the
  hash of its own container.)

  The tools version and the library version are **separate lines**: tools 0.3.15
  scaffolds projects against Maven libraries **`dev.keliver:*:0.4.1`**. The
  bundle records both in its `VERSION.json`.

* For the device route only: the **Android SDK platform-tools** (`adb`) and a
  running emulator or a connected device. An emulator reaches your machine at
  `10.0.2.2`, and a USB device through `adb reverse`; a **physical phone over
  Wi-Fi** needs the relay started with `KELIVER_RELAY_BIND=all` (it listens on
  this machine only otherwise), and then anyone on that network can edit and
  publish.
* To build a production Android host (`keliver-new-production-host.sh`): the
  **Android SDK** itself, found through `ANDROID_HOME` or an `sdk.dir` line in
  `host-android/local.properties`.

Network access to Maven Central is needed the first time you build, to fetch
`dev.keliver:*`.

## Scaffold and start an app

```bash
$KP/keliver-init MyApp          # creates ./myapp
cd myapp
$KP/keliver-portal .            # starts the portal for this app
```

`keliver-portal` prints the editor URL, the server URL, and the exact
`portal-mcp` command for AI agents. Stop it with `Ctrl-C`, or
`$KP/keliver-portal stop .` from another shell.

**Two apps at once.** The server listens on `"port"` in `keliver.portal.json`
(default 8077) and the editor on `"editorPort"` (default: `port` + 19, so 8096
beside 8077). Give a second app another `"port"` and both portals run side by
side; a port already taken is refused at start, naming which. The editor finds
its own app's server through the `?relay=<port>` in its URL: `keliver-portal`
prints the URL with it, and the editor's server adds it to a URL opened
without it, so a bookmark cannot open one app's editor on another app's
screens. Each app keeps its own document store. A setting that names the
editor's origin (`httpRecording.allowedOrigins`) must use that app's editor
port.

A fresh scaffold is a normal Gradle project:

```
myapp/
├── keliver.portal.json                    ← portal configuration
├── build.gradle, settings.gradle, gradlew ← ordinary Gradle
├── .gitignore                             ← ignores .gradle/, build/
└── src/jsMain/kotlin/
    ├── screens/home.kt                    ← PORTAL-OWNED (layout + bindings)
    └── logic/HomePresenter.kt             ← HAND-OWNED (your data and state)
```

After your first portal edit one more file appears beside the screen:

```
    screens/Compiled_home.kt   ← GENERATED version stamp. Portal-owned, do not
                                 edit; commit it along with the screen.
```

## Who owns which file

**The portal owns `screens/`.** Editing a screen — in the browser, in your
editor, or through an agent — produces a surgical diff in that one file.

**You own `logic/`.** The portal never writes there. A screen declares a
`Bindings` interface; your presenter implements it. That interface is the
round-trip boundary, and it is enforced by the compiler.

Both are ordinary source files in your git history. Commit them.

## MCP: connect an agent

`keliver-portal` prints the command; the same thing spelled out:

```jsonc
{ "mcpServers": { "keliver-portal": {
    "command": "<package>/mcp/bin/portal-mcp",
    "env": { "PORTAL_REPO": "/abs/path/to/myapp",
             "PORTAL_SERVER": "http://localhost:8077" } } } }
```

The relay must be running — the tools talk to it.

**Tool discovery.** Eleven tools are exposed. Depending on the client they may not
appear in the up-front tool list; discover them with whatever tool-search
mechanism your client provides, or name the server explicitly.

| tool | what it is for |
|---|---|
| `get_guide` | this document |
| `get_catalog` | every widget, prop, prop kind, and the op schema — call it first |
| `list_projects`, `list_screens` | what exists |
| `get_document` | one screen as a semantic tree |
| `apply_ops` | transactional edit (see below) |
| `undo`, `redo` | server-side, per session |
| `list_tokens` | the app's design tokens (constants), with the import a screen needs |
| `find_usages` | where a bound field, action or design token is used |
| `device_screenshot` | a frame from a connected device |

`device_screenshot` runs `adb` from the macOS default SDK location,
`~/Library/Android/sdk/platform-tools/adb`, and nowhere else: it does not read
`ANDROID_HOME` or `PATH`. With the SDK anywhere else (or on Linux) the tool
fails; take the screenshot with `adb exec-out screencap -p` yourself.

**Screen names are bare**, as `list_screens` returns them: `home`, not
`default/home`.

## Inspect a screen

`get_document {"screen": "home"}` returns the tree. Each node has a **stable
handle**; props are `Lit` (a literal), `Bind` (reads a bindings field) or
`Action` (calls a bindings function). For the scaffolded screen:

```
Column                                    handle 1
  StyledText  text=Lit "MyApp"            handle 2
  StyledText  text=Bind subtitle          handle 3
  Spacer                                  handle 4
  Repeat      items=Bind items            handle 5
    ListItem  headline=Bind item.title    handle 6
  Button      text=Lit "Refresh"
              onClick=Action refresh      handle 7
contract: fields {subtitle: String, items: List<Item>}, actions [refresh]
```

## One supported edit

Change the title. Ops target a **handle**, and the batch carries the
`baseVersion` you just read, so a stale edit is rejected rather than applied.

```jsonc
// apply_ops  { "screen": "home", "dryRun": "1", "batchJson": "<this, as a string>" }
{
  "baseVersion": 1,
  "envelope": { "session": "agent", "atMillis": 0 },
  "ops": [
    { "kind": "dev.keliver.portal.document.DocOp.SetProp",
      "target": 2, "name": "text",
      "value": { "kind": "dev.keliver.portal.document.PropValue.Lit",
                 "tag": "s", "s": "My Inbox" } }
  ]
}
```

`dryRun: "1"` validates and changes nothing — it answers `{"ok":true,...}`.
Drop it to commit; the reply carries the new version.

The field is **`target`**, not `handle`. Other ops: `InsertNode` (`parent`,
`after`, `node`), `DeleteNode`, `MoveNode`, `RemoveProp`, `SetModifier`,
`RemoveModifier`, `RenameId`, `ReplaceRaw` — all keyed by `target` except
`InsertNode`. New nodes use handle `0`; the server allocates. `Lit` tags are
`s`, `i`, `d`, `b`, `li`, `lf`. Call `get_catalog` for the authoritative list.

**The resulting source change** in `screens/home.kt`:

```diff
     StyledText(
-      text = "MyApp",
+      text = "My Inbox",
       fontSize = 28,
```

Nothing else in the file moves, and `logic/` is untouched. `undo` reverses it.

Two things to expect alongside it: the generated `Compiled_home.kt` stamp
appears (see above), and after a portal restart the document is read again from
the `.kt` (its version continues from that stamp) — so always read
`baseVersion` from the `get_document` you just made, never a remembered one.

## Working in the editor

The editor at `:8096` edits the same document as your IDE and an agent; every
change is written to the screen's `.kt` surgically, and an edit followed by its
undo gives the file back byte for byte.

**Canvas.**
- **🎯 Select** (the default): a click selects the innermost widget or component
  instance under it, and the app does not see the click. Click empty space to
  deselect.
- In a list, a click outlines the row you clicked; the list's other rows are
  outlined faintly, because they are the same node.
- **☝ Interact**: clicks, typing and scrolling go to the app, as on a device.
- The wheel scrolls a screen taller than the frame, and a horizontal wheel or
  drag scrolls a carousel.
- **− / + / Fit** zoom the frame, as do ⌘/Ctrl + wheel, a trackpad pinch,
  ⌘= / ⌘−, ⇧1 (fit) and ⇧0 (100%). Wheel and pinch zoom keep the point under
  the pointer in place. The device preset beside them sets the
  frame's size, and **⟲** rotates it.
- Double-click a text to edit it in place.

**Components.** Double-click an instance, press Enter on it, or use **Edit
component ↗** in its properties to open the component itself:
- The outline, canvas and State panel then show its body, with its parameters
  as sample data.
- Edits are written to the component's own file, and every screen using it
  redraws at once.
- A breadcrumb (`home › SummaryCard › StatTile`) leads back; Esc steps out one
  level.
- Undo steps your edits newest first, across the screen and the components you
  opened from it; back on the screen, it says when the next step is a
  component's.
- A component's parameters are its contract with every caller, so an edit that
  would change them is refused: change the signature in code.

**Outline and palette.**
- Drag a palette item onto the canvas or onto an outline row; a line shows
  where it will land.
  - On the canvas, a drop in the gap between two elements lands between them.
  - On an outline row, its top or bottom quarter drops before or after the
    row, and its middle drops inside it.
- Clicking a palette item inserts it inside the selected container, or after
  the selection.
- What you insert is selected.
- Keys:
  - ↑/↓ move the selection; ←/→ collapse, expand or go to the parent or child;
  - Delete removes the selection. Delete, ⌘D and ⌘V act only after you click
    the canvas or the outline, never while a property field had the keyboard;
  - ⌘D duplicates;
  - ⌘C/⌘V copy and paste;
  - Enter or F2 edits the text or opens the component.

**State.**
- The State panel lists every value the screen reads: the fields its `if`
  blocks test, its bound props, and its lists' row counts.
- It offers the scenarios the screen's flags allow (Loaded, Loading, Error,
  Empty), and sample values chosen by each field's name.
- A flag whose `if` block holds the loading (or error, or empty) blocks stays
  on in that scenario, and choosing a scenario scrolls the canvas to what it
  shows. Once you set a flag by hand, the state reads "Custom".
- What you set is kept per screen in this browser and is never written to
  source.

**Properties.** A property the call does not pass is marked *default*: the
widget's own default applies until you set it. A component's number parameter
that it passes on to a named setting (an alignment, an overflow) offers the
names. A side panel hidden by double-clicking its edge leaves a tab at the
window's edge that brings it back.

**Undo.** Undo and Redo are per editing session, and their tooltips count the
steps. Undoing back to a state the file was already in writes that exact
text (your argument order, imports and formatting), unless the file, a
component or a design token changed in the meantime; then the undo is merged
onto the file as it is now. ⌘Z inside a text field
undoes the field's typing, not the document.

**</> Code** shows the screen's (or opened component's) file exactly as it
is on disk, with Copy and Download.

The top bar says what each action does under its name: **▶ Live** *run its
logic*, **</> Code** *this screen's .kt*, **⬆ Publish** *to devices*. Next to
Publish, **On devices** names the newest bundle published, and when. A publish
reports in a small card (*Published v4*, or *Not published* and why); the full
build log is behind its **Log** button.

**Palette preview.** Hovering a palette row shows what it adds, drawn by the
canvas's own renderer: a project component with its sample values, a widget as
it is inserted, an empty container with three sample items.

**Preview fidelity**, in the right panel:
- It says whether the app's Keliver runtime matches the editor's: an older app
  release with the same widget protocol is compatible.
- It lists any image host the canvas cannot load from (one that sends no CORS
  headers), with the fix: add the host to `previewAssetHosts` in
  `keliver.portal.json`. Such images are drawn as a crossed frame, never as
  empty space.

**Panels** resize by dragging their inner edge; double-click the edge to
collapse or restore. Widths are remembered.

## Build and run

Type-check at any time:

```bash
./gradlew compileKotlinJs
```

To see it running, add the device target once, install the generic host once,
then serve:

```bash
$KP/keliver-new-device-target.sh                 # once: adds device/Main.kt, edits build.gradle + settings.gradle
$KP/keliver-install-device-host.sh --serial <serial>   # once per device
./gradlew serveDevelopmentZipline &              # serves the bundle on :8080
adb -s <serial> shell am force-stop dev.keliver.portaldevice
adb -s <serial> shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity
```

`keliver-new-device-target.sh` is a one-time scaffolding step: besides
`src/jsMain/kotlin/device/Main.kt` it edits `build.gradle` and
`settings.gradle`. Review and commit those like any other change.

After changing Kotlin, rebuild **and** restart the host — the host loads the
bundle at launch. Read the screen with
`adb -s <serial> shell uiautomator dump /sdcard/ui.xml`.

The host reaches your machine at `10.0.2.2:8080`, which is an **emulator**
address. A physical device needs its own reachable host URL; see
[`DEVICE_HOST.md`](DEVICE_HOST.md).

## Ship to production

The generic host above is development-only and refuses production. Two
commands, each run once, give your app signed publishing and its own production
host. Both ship in the tools bundle from **0.3.6** on.

```bash
$KP/keliver-new-device-target.sh       # if you have not already: the bundle's entry point
$KP/keliver-new-publish-target.sh      # publishTask/publishOutput + the signing block
$KP/keliver-portal .                  # if not running; no restart: publish rereads publishTask/publishOutput
curl -X POST http://localhost:8077/publish        # "publish OK: bundle v1", signed with your key

$KP/keliver-new-production-host.sh --bundle-server http://10.0.2.2:8077   # an emulator reaching your relay
./gradlew -p host-android assembleDebug
```

**On a phone or emulator, in one command** (tools 0.3.15 and later): with the
portal running and a bundle published, connect the phone (USB, or wireless
debugging) and run

```bash
$KP/keliver-run-android.sh            # --serial <serial> when adb lists more than one device
```

It scaffolds `host-android/` if the app has none, builds a debug host that
looks for bundles at `http://localhost:<port>` (your `gradle.properties` is not
changed), runs `adb reverse tcp:<port>` so the phone reaches this machine's
relay without anything listening on your network, installs and starts it, and
waits until the host has loaded your bundle from the relay. Then edit and
**⬆ Publish**: the open app takes the new bundle (a host scaffolded from tools
0.3.13 on; below). Run it again only after changing the host itself.

A debug build also takes its bundle server from the device, so a new tunnel
URL needs no rebuild:
`adb shell run-as <applicationId> sh -c 'mkdir -p files/keliver && echo https://… > files/keliver/bundle-server'`,
then `adb shell am force-stop <applicationId>` and start it again (delete the
file to go back). Every bundle still has to verify against the embedded key; a
rollback floor raised by bundles from that server stays, so the built-in server
must then offer that sequence or newer. Only a debug build of a debuggable app
reads the file, never a release build. An `http://` value also needs a built-in
server that is `http://` (debug cleartext is allowed only then).

A debug build of the production host (Android from tools 0.3.13, iOS from
0.3.14) picks
up a bundle you publish **while the app is open**: with `keliver.updates`
(Android) or `UPDATES` (iOS) empty its updates are `live`, so while a screen
shows it listens to the relay's `/bundles/events` and
applies the new bundle a second or two after **⬆ Publish** finishes (the
editor's devices chip says how many phones are listening). It is still the
signed, verified update path; the screen's state may restart with the new
code. Against a static bundle server, which has no event stream, it falls back
to checking when you switch back to the app (on-resume updates, at most one
lookup every 30 s), as debug hosts from tools 0.3.10 to 0.3.12 do. Any debug build also looks at once
on `adb shell am broadcast -a dev.keliver.debug.CHECK_FOR_UPDATE -p <applicationId>`
(the receiver needs the `DUMP` permission, which adb's shell has and ordinary apps do not). Every other build type looks only at the
next launch, except one made with `initWith(debug)` (or, for the embedded
library, one that falls back to `debug`), which takes debug's settings. `keliver.updates` in `host-android/gradle.properties` chooses for
every build type instead. Hosts from tools 0.3.9 and earlier wrote
`next-launch` there, and their `build.gradle` does not accept an empty value:
take this release's `build.gradle` before emptying it, or set `on-resume`.

The relay does not sign: your build does, with your store's private key, and
`/publish` keeps a bundle only if its manifest verifies against your store's
public key. Without the signing block the build still succeeds and `/publish`
says `publish REFUSED: the bundle is UNSIGNED` and stores nothing.

**Upgrading from tools 0.3.6: run `keliver-new-publish-target.sh` from these
tools (0.3.7 or later) again (U31).** The 0.3.6 command can't do this; it
answers "already wired".
The signing block 0.3.6 wrote gave your private key to Zipline's compile task,
which passes it to a child JVM on its command line. While a bundle compiled,
any local user could read the key in the process list. A `--info` or `--debug`
build printed it, and it was stored in `.gradle/<version>/executionHistory/`,
readable by others under the usual umask. Rerunning the command replaces
exactly that block with one that signs inside Gradle, after the compile, and
changes nothing else, `keliver.portal.json` included. Then:
- delete `.gradle/*/executionHistory/` in your app. **Keep
  `.gradle/keliver-store-path`**: it binds the app to its store, and without
  it a moved app can resolve to a new, empty store with a new key;
- no portal restart is needed: every `/publish` runs a fresh Gradle build, so
  it uses the new block straight away;
- if other users of the machine, shared CI logs or a cached `.gradle/` could
  have exposed the key, treat it as compromised. A new key means rebuilding
  and shipping your production hosts with its public key: follow
  [`KEY_ROTATION.md`](KEY_ROTATION.md) (at the root of the tools bundle), "If a key leaks".

It embeds your portal's public key (copied from your store — commit
`host-android/src/main/assets/keliver/portal_ed25519.pub`), verifies every bundle
against it, and loads the latest one your relay has published. Use an
`https://` bundle server for real users — a release build refuses `http://`;
`DEVICE_HOST.md` §2 (Android) has the options and what the host refuses.

**iOS.** `keliver-new-ios-host.sh` writes `host-ios/`: the same production
host, for iOS. It is a Kotlin framework built from Maven Central plus an Xcode
app around it. It ships in the tools bundle from **0.3.7** on. Scaffolding
needs only bash and python3; building and running it need macOS with Xcode.

```bash
$KP/keliver-new-ios-host.sh --bundle-server http://localhost:8077    # a simulator on this Mac reaching your relay
xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -configuration Debug CODE_SIGNING_ALLOWED=NO build                  # or open it in Xcode
```

Your portal's public key and the servers go into
`host-ios/src/iosMain/kotlin/<package>/HostConfig.kt`; commit that file. A
release build refuses `http://`. A debug build also takes the bundle server
from the `KELIVER_BUNDLE_SERVER` launch environment variable (for example a new
tunnel URL, without a rebuild); every bundle still has to verify against the
key in `HostConfig.kt`, a rollback floor it raises stays, an `http://` value
needs an App Transport Security exception for its host, and a release build
ignores the variable. A debug build also takes a newly published bundle when
the app comes back to the foreground, and looks at once on the Darwin
notification `<bundle id>.keliver.check-for-update` (`xcrun simctl spawn <udid>
notifyutil -p …`, or `xcrun devicectl device notification post --device <id>
--name …` on an iPhone); a release build looks only at the next launch.
`UPDATES` in `HostConfig.kt` chooses for every build instead. A device build needs your team and signing
(`host-ios/Configuration/Config.xcconfig`). `DEVICE_HOST.md` §3 has the
details.

### Embed in an existing app

The commands above write a standalone host app. To show Keliver screens inside
an app you already have, next to its native screens, add `--embed`. It writes
the same host as a module of your app. It edits none of your files: it prints
the lines to add. It ships in the tools bundle from **0.3.8** on.

**Android.** Run from your Keliver app's root (the one with
`keliver.portal.json`), pointing `--into` at your existing app's Gradle root:

```bash
$KP/keliver-new-production-host.sh --embed --into ../my-app --bundle-server https://bundles.example.com
```

Before it writes anything, it prints the module's **dependency floor**. These
are the versions the module resolves to of Kotlin, coroutines,
kotlinx-serialization, Compose (runtime, UI, foundation, material),
activity-compose, AndroidX core, lifecycle and browser, OkHttp, Okio, Coil and
Zipline. Each sits beside the version your build files declare. The report
reads direct coordinates, version catalogs (`gradle/*.versions.toml`), simple
version variables, the Kotlin plugin and BOMs (a BOM named `<x>-bom` versions
that group's `<x>…` modules). It looks at most four directories deep and skips
comments. Each row gets a verdict:
- **RAISED:** you declare an older version. Gradle keeps one version of each
  library, so your app gets the floor instead. The report counts these, and
  says when one is a new major version (OkHttp 4 to 5, for example). Build and
  test your app with the raised versions before you ship it.
- **PINNED below the floor:** a `strictly`, `!!`, `force`, `force = true`,
  `isForce = true` or `enforcedPlatform` version older than the floor. Gradle
  fails, or keeps your version, which the module was not built with. Lift the
  pin first.
- **The Kotlin plugin is older than the module's Kotlin:** the module will not
  compile in your app.
- **A Compose BOM:** its versions are dates, so check that BOM's Compose
  version yourself.
- **`-`:** the build files it reads do not declare that library. It may come
  from a BOM it cannot map, a plugin or a convention plugin.

The floor comes with the tools release, from tools 0.3.15 on. It is in
`templates/production-host-embed/dependency-floor.json`, with the full resolved
list. It describes the module the scaffolder writes by default; if your app
asks for another Keliver version, the report says that the floor may differ.

It writes `../my-app/keliver-host/`, a `com.android.library` module with the
same host Kotlin as `host-android/`. Then, in your app:
- `settings.gradle`: `include ':keliver-host'`;
- the root `build.gradle`: the library's plugins, `com.android.library`,
  `org.jetbrains.kotlin.plugin.compose` and `app.cash.zipline` `1.22.0`. The
  module requests them without versions, so your build applies them. Zipline
  1.22.0's compiler plugin needs **Kotlin 2.2.0**, so your app must build with
  it;
- your app's `build.gradle`: `implementation project(':keliver-host')`;
- your `Application`: `val keliver by lazy { KeliverHost.create(this) }`. Make
  one host per process: a second `create()` for the same key throws;
- a screen: `KeliverScreen(keliver, Modifier.fillMaxSize())` in Compose, or
  `KeliverView(context).apply { host = keliver }` in a View layout (in a
  `ComponentActivity` or a `Fragment`);
- the fallback: pass `onUnavailable = { replaceWithWhatThisReplaced() }` to
  `KeliverScreen` (or set `KeliverView.onUnavailable`). It is called when the
  host cannot show a screen (no bundle, a refused build, a bundle that did not
  load): replace the Keliver screen there with your web page or native screen
  (finish or pop it; a screen you return to calls it again). Only a debug
  build of a debuggable app shows the host's own text, which can name
  addresses and paths;
- data, through your own signed-in client (from tools 0.3.15): with an API
  base (`--api-base-url`), guests call your API through `HostHttp`. Give the
  host your app's `OkHttpClient`, with its authentication interceptor and
  certificate pinning, and the allow-list it may be used for:

  ```kotlin
  // In your Application; <host package> is the one the scaffolder printed.
  import <host package>.KeliverConfig
  import <host package>.KeliverHost
  import <host package>.RequestPolicy
  import okhttp3.HttpUrl.Companion.toHttpUrl

  val keliver by lazy {
    KeliverHost.create(this, KeliverConfig.fromBuild(this,
      requestPolicy = RequestPolicy(
        origin = "https://api.example.com/".toHttpUrl(),
        allowed = setOf(RequestPolicy.Rule("GET", "orders/recent")),
        allowCleartext = BuildConfig.DEBUG,
      ),
      httpClient = myAuthenticatedClient,
    ))
  }
  ```

  The policy is checked before your client sees a request: one origin (the API
  base's), exact `(method, path)` pairs, and state-changing calls only with
  `allowStateChanging`. Without a policy, `httpClient` is refused. The origin
  must be the API base's, so a debug build against a local `http://` API needs
  that origin there (and `allowCleartext`). Your client is used only for
  HostHttp, never for bundles. The URL that goes out is checked again after
  your interceptors and on each network attempt, and redirects are not
  followed (an `Authenticator` retry to another path that your client's cache
  answers is not caught): an
  interceptor of yours must change a request with `newBuilder()`, and one
  that rewrites the path (a `/v2` prefix) makes the request refused. A
  guest's own `Authorization`, `Proxy-Authorization` and `Cookie` headers are
  dropped, and a response's `Set-Cookie` is not handed back. Other credentials,
  such as an API-key header or a token in the query, are for your interceptor
  to set with `header()` or to remove. From the tools release after 0.3.15,
  `TRACE`, `TRACK` and `CONNECT` are refused through your client (a `TRACE`
  would echo your headers back to the guest); a host scaffolded earlier keeps
  sending them until it is scaffolded again or patched.

The settings are in `keliver-host/keliver.properties` (`-Pkeliver.*` overrides
them), and the key is `keliver-host/src/main/assets/keliver/portal_ed25519.pub`.
Commit both. Your app keeps its own manifest decisions: cleartext for an
`http://` development server, backups, the theme. A minified release keeps
what Zipline needs through the module's consumer R8 rules.

**iOS.**

```bash
$KP/keliver-new-ios-host.sh --embed --into ../MyApp --bundle-server https://bundles.example.com
```

It writes `../MyApp/keliver-host-ios/`, the `KeliverHost` framework's Gradle
build with its own wrapper, plus `KeliverScreen.swift` and `EMBED.md`. Your
Xcode project is not changed. `EMBED.md` lists the edits:
- a Run Script phase before Compile Sources that builds the framework;
- `ENABLE_USER_SCRIPT_SANDBOXING = NO` and `-lsqlite3`;
- `CADisableMinimumFrameDurationOnPhone` in your Info.plist;
- adding `KeliverScreen.swift` to your target.

Then `KeliverScreen()` goes in any SwiftUI view. `Keliver.shared.start()` in
your `App`'s `init` starts the lookup early, and (from the tools release
after 0.3.15) the code load right after it.

Guests' data goes through your API with your user's credentials (from the
tools release after 0.3.15): set the allow-list and a header callback before
the host starts.

```swift
Keliver.shared.requestPolicy = RequestPolicy(
  origin: "https://api.example.com",
  allowed: [RequestPolicy.Rule(method: "GET", path: "orders/recent")],
  allowStateChanging: false, allowCleartext: false)
Keliver.shared.requestHeaders = { method, path, done in
  session.accessToken { token in done(token.map { ["Authorization": "Bearer \($0)"] }) }
}
```

The policy is checked first; the callback is then asked, on the main thread,
for each allowed request. `done(nil)` refuses the request (nothing is sent),
and so does no answer within 30 s, so don't block the main thread in it.
`TRACE`, `TRACK` and `CONNECT` are refused. Without a policy, every request is
refused. A guest's own `Authorization`, `Proxy-Authorization` and `Cookie`
headers are dropped, and a response's `Set-Cookie` is not handed back.

On both platforms, every Keliver screen in the process shares one lookup and
one load: navigating natively or rotating doesn't load again, and a newer
bundle is picked up on the next process start. If a bundle doesn't verify, the
host falls back to the last good one it ran; with none, the Keliver screen calls
`onUnavailable` (Android) and, in a debug build, also says "Bundle did not load".
Your native screens keep working either way.
`DEVICE_HOST.md` §4 has the details and the limits. The main limit is on
Android: the module compiles with your app's Kotlin, and only 2.2.0 works. On
iOS the framework builds with its own Gradle and Kotlin, but an app that
already embeds a Kotlin framework would carry two Kotlin runtimes.

### Publish from CI to a static server

`keliver-publish` does what `POST /publish` does, without the relay:
1. It builds your `publishTask`.
2. It refuses a bundle that isn't signed with your key, or is missing a
   module. In that case it writes nothing.
3. It writes `bundles/v<N>/` plus `bundles/index.json` into a directory.

Serve that directory from any static host or CDN (S3, GCS, GitHub Pages,
nginx) over HTTPS. Point `--bundle-server` at it. Hosts from the current
scaffolders read `bundles/index.json`.

It ships in the tools bundle from **0.3.8** on, as `$KP/keliver-publish`
(its JVM half is `relay/bin/keliver-publish-jvm`).

**The live index is the state.** The next `v<N>` and `sequence` come from the
`bundles/` directory you publish into. So:
- **Download the live `bundles/` first:** `index.json` and the `v<N>/`
  directories. Publishing into an empty directory would start again at v1, and
  uploading the result would overwrite the v1 hosts already load. Without an
  `index.json`, `keliver-publish` refuses.
- **`--init` only for the very first publish, run once by hand.** Never let CI
  add it, for instance because a download failed: that is exactly the case it
  exists to stop. A restarted sequence would also sit below every host's
  rollback floor, and those hosts would then show no bundle.
- **Upload `v<N>/` first, then `index.json`.** Never delete or overwrite a
  `v<N>/`. Skip dotfiles: `.publish.lock`, and any `.staging-*` or
  `.index.json.tmp-*` an interrupted run left.
- **One publish at a time.** The lock in `bundles/` only covers one machine; in
  CI use a `concurrency` group.

**Rolling back is publishing again.** Hosts refuse a sequence below the highest
they have run, so serving an older index, or an older `v<N>/` as newest, makes
them show no bundle. To go back to v`<N>`'s code, republish it:

```bash
KELIVER_SIGNING_KEY_FILE=... $KP/keliver-publish . --out site --republish 3 --public-key-file host-android/src/main/assets/keliver/portal_ed25519.pub
```

It copies `v3/`'s modules unchanged into the next `v<M>/` and has your app's
`keliverResign` task (in the same signing block, with the same key) sign the
copy for the next sequence. Nothing is compiled. The entry keeps `v3`'s
capabilities, widget version and constraints, and its channel (or takes
`--channel`), and records `"republishOf": 3`. Upload it like any other publish. It refuses a version
the index has no entry for, an entry hosts would read differently once
rewritten (a missing capabilities list, say), a `v<N>/` that is not what the
index names, and a bundle that does not verify against your key. A signing block from before
republishing has no `keliverResign`: run `keliver-new-publish-target.sh` to
replace it.

**Channels.** `--channel beta` publishes to beta only: hosts scaffolded with
`--channel beta` take it (plus everything on stable); stable hosts don't. When
it is ready for everyone, promote it. Nothing is built or signed, so this step
needs no private key (only the app's `keliver.portal.json` and the public key):

```bash
$KP/keliver-publish . --out site --promote 7 --channel stable --public-key-file host-android/src/main/assets/keliver/portal_ed25519.pub
```

**Staged rollouts and host-version gates.** Publish with `--rollout 10` and
about one install in ten takes the new bundle. Each install's bucket is fixed
for a given sequence. The rest stay on the newest bundle they may run. Raise
the rollout, or halt it with 0, by editing the index only (no key, no build):

```bash
$KP/keliver-publish . --out site --set-rollout 7 --rollout 100
```

A halted rollout stops new installs, and hosts that already ran it keep it.
`--set-rollout` only changes an entry that already has constraints: hosts
scaffolded before staged rollouts skip any constrained entry, so adding one to
a live bundle would hide it from them. Stage a rollout when you publish. On a
promoted sequence, set the rollout on every channel it is on: beta hosts also
take stable's entry. A `--republish` does not copy a rollout, because a new
sequence draws new buckets; pass `--rollout` to stage it. Host-version gates
are copied, and a republish or promotion can narrow them but never widen them.
The install id is backed up with the app's data (Android Auto Backup, iCloud),
so a restored install keeps its bucket.
`--min-host-version N` / `--max-host-version N` limit an entry to host builds
whose version (Android `versionCode`, iOS `CFBundleVersion`) is in range. Use
them for code that needs a newer host. Hosts scaffolded before these
constraints skip any entry that has them.

A first publish, by hand:

```bash
$KP/keliver-publish . --out site --init --public-key-file host-android/src/main/assets/keliver/portal_ed25519.pub
```

Every later one, in GitHub Actions, with S3 as the example. With GCS use
`gsutil -m rsync -r`; with any other server, copy the same files in the same
order.

```yaml
concurrency: keliver-publish            # never two publishes at once
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      # ... JDK 17, cloud credentials, keliver-portal-tools as $KP
      - name: The live bundles/, which the next version and sequence come from
        run: |
          mkdir -p site/bundles
          aws s3 cp "s3://$BUCKET/bundles/index.json" site/bundles/index.json   # fails the job if it is missing
          aws s3 sync "s3://$BUCKET/bundles" site/bundles
      - name: Build, verify, write v<N>/ and index.json
        run: |
          umask 077
          printf '%s' "$KELIVER_SIGNING_KEY" > "$RUNNER_TEMP/keliver.priv"
          KELIVER_SIGNING_KEY_FILE="$RUNNER_TEMP/keliver.priv" \
            $KP/keliver-publish . --out site --public-key-file host-android/src/main/assets/keliver/portal_ed25519.pub
          rm -f "$RUNNER_TEMP/keliver.priv"
        env:
          KELIVER_SIGNING_KEY: ${{ secrets.KELIVER_SIGNING_KEY }}
      - name: Upload v<N>/ first, then the index
        run: |
          N="$(jq -r '.entries | max_by(.sequence) | .version' site/bundles/index.json)"
          aws s3 cp --recursive "site/bundles/v$N" "s3://$BUCKET/bundles/v$N" \
            --cache-control "public, max-age=31536000, immutable"
          aws s3 cp site/bundles/index.json "s3://$BUCKET/bundles/index.json" --cache-control "no-cache"
      - name: Remove the key file, and Gradle's task history with it
        if: always()
        run: rm -rf .gradle/*/executionHistory "$RUNNER_TEMP/keliver.priv"
```

**Keep the signing key out of logs and caches.** The signing block signs
inside Gradle, after the compile. The key is never on a command line, in a
`--info` or `--debug` log, or in `.gradle/` (U31, fixed in tools 0.3.7).
Still:
- never cache `.gradle/` with `actions/cache`;
- delete the key file when the job ends, as above.

Store the private key (the hex in your store's `keys/ed25519.priv`) as a
secret. `KELIVER_SIGNING_KEY_FILE` is read by the signing block that
`keliver-new-publish-target.sh` writes from tools 0.3.8 on. Blocks written
by 0.3.6 or 0.3.7 don't know this variable (nor the publish sequence, nor
`keliverResign`): run `keliver-new-publish-target.sh` again. It replaces exactly
either of those blocks and changes nothing else.

Exit status:
- 0: published.
- 2: usage.
- 3: the build failed.
- 4: refused, with nothing written. If it lost the lock to another publish,
  `bundles/.publish.lock` may have been created.
- 5: an I/O error while writing. `index.json` is then unchanged or complete,
  never half-written, but a `v<N>/` that no entry names may be left behind.
  Hosts never load it as current, and its number is never reused.

Caching:
- Serve `bundles/index.json` with `Cache-Control: no-cache`, or a short
  `max-age`: it is the only file that changes, and a CDN that keeps an old copy
  delays every update.
- Everything under `v<N>/` is never rewritten, so it can be cached for as long
  as you like. That is why a `v<N>/` must never be overwritten.
- Don't let the CDN cache a 404 for long.

**When the bundle a host is told about fails to load, the host falls back to
the last good bundle**, the verified one in its cache, if one has run before.
A new install has none and shows "Bundle did not load". This happens when:
- an index entry's `manifestSha256` doesn't match the manifest served (a
  `v<N>/` overwritten, or a stale CDN copy);
- a `v<N>/` is missing because the index was uploaded first;
- any download of that bundle fails.

The upload rules above prevent the first two cases. Hosts scaffolded before
the fall-back (tools 0.3.7 and earlier) have none: they show no bundle instead.

The index is not signed. Every manifest is, and hosts verify every manifest.
Each index entry records its manifest's sha256, and the host checks it. That
check catches a mismatched manifest, but it doesn't protect against anyone who
can rewrite the index. Integrity rests on the manifest signatures, not on the
server, its origin or its redirects.

Rollback protection: each manifest carries its sequence in its signed
metadata, and a host refuses a sequence below the highest it has run, both on
the network and from its cache (`DEVICE_HOST.md` §2). So an older signed
bundle served again is refused, once a host has run a sequenced one. To roll
back on purpose, rebuild the older source and publish it: it gets a new, higher
sequence. A reinstall resets a
host's floor. One key is one sequence space: never let sequences go backwards.

## Preview mocks are not runtime values

The editor's preview and the running app show different things, deliberately.

* **Out of the box** the preview renders the **bundled generic editor**: real
  layout and real widgets, but **placeholder values** for every `Bind` — a
  `Bind` to `tally` draws literally as `{tally}` — and no presenter at all.
* **The running app** uses your presenter. That is the only place a `Bind`
  shows a real value and an `Action` runs your code.

**A screenshot of the preview is not evidence that behaviour is correct.**

### Previewing your real presenters

This takes four steps, not one. `keliver-new-editor.sh` scaffolds an editor
whose screen map is **empty** — every entry is commented out — so on its own it
previews nothing.

**1. Scaffold**, passing every source directory the presenters need to compile.
A presenter returns its screen's `Bindings` interface, and that interface is
declared in `screens/<name>.kt`, so a logic-only editor fails to compile:

```bash
$KP/keliver-new-editor.sh MyApp src/jsMain/kotlin/logic src/jsMain/kotlin/screens
```

**2. Register the screen and presenter** in
`editor/src/wasmJsMain/kotlin/MyAppPreview.kt`. The map key is the **portal
screen name** — the `.kt` basename the relay ingests, so `home` for
`screens/home.kt`. Map each contract field to its current value, and route each
action back into the same bindings object:

```kotlin
import dev.keliver.portal.render.AppPreviewEntry
import dev.keliver.portal.render.PreviewFrame
import dev.keliver.portal.render.ScreenPreview
import dev.keliver.portal.render.putRows
import myapp.logic.HomePresenter

object MyAppPreview : AppPreviewEntry {
  override val label = "myapp (real presenter)"

  override val screens: Map<String, ScreenPreview> = mapOf(
    "home" to ScreenPreview { env ->
      val b = HomePresenter()                   // your real presenter
      PreviewFrame(
        values = buildMap {                     // contract field -> current value
          put("subtitle", b.subtitle)
          putRows("items", "item", b.items.map { mapOf("title" to it.title) })
        },
        dispatch = { action, _ ->               // portal action -> your bindings
          when (action) {
            "refresh" -> b.refresh()
            else -> env.log("unhandled action: $action")
          }
        },
      )
    },
  )
}
```

This is the `home` screen `keliver-init` scaffolds (`subtitle`, the `items`
list iterated as `item`, and `refresh`). Values are **strings**, matching the
mock transport. For a list field use `putRows(field, itemVar, rows)` instead of
a plain entry, with the loop variable the screen uses as `itemVar`.

**3. Build the editor:**

```bash
cd editor && ./gradlew wasmJsBrowserDistribution && cd ..
```

It resolves `dev.keliver:portal-editor` from Maven Central, so this needs
network access the first time. Expect a couple of minutes.

**4. Launch.** `keliver-portal .` detects `<app>/editor` and serves **your**
editor instead of the bundled one. It prints
`Preview → …/editor/build/dist/… (your real presenters)` when it does.

**5. Press ▶ Live.** The editor opens in **mock mode even when your editor is
loaded** — the fidelity panel says *"Mock mode — press ▶ Live to run real
logic"*. Only after pressing ▶ Live does the canvas show presenter values; the
panel then reads *"real presenter — <your label>"*, the state inspector lists
each contract field with its live value, and the action console logs
`⚡ <action> → real presenter` for every tap.

### Multi-step behaviour in the preview

Repeated actions accumulate, so a three-tap sequence reads `0 → 1 → 2 → 3` in
the preview, the same as on the device.

### Presenter state that arrives later

State your presenter writes from a coroutine — a load that completes, a
`LaunchedEffect` that finishes — shows up on its own. A screen that starts on
`Loading…` and resolves reaches the canvas and the State Inspector with no
click, no selection change and no document edit, and an action that *starts* a
request still shows the response when it lands, long after the action's own
render settled. Stopping Live cancels work in flight; a restarted session, or a
different screen or persona, never inherits a value from the previous one.

Verified from a scaffolded app in Chrome — see
`docs/superpowers/evidence/adopter-preview-route/U19-ASYNC.md` for the exact
sequence and its limits.

**Which build you need.** At least the published
`dev.keliver:portal-editor:0.3.3` — a minimum, not a pin. `keliver-new-editor.sh`
uses your app's `appRuntime.keliverVersion`, so the editor of an app scaffolded
by these tools gets 0.4.1. Both behaviours were measured
against 0.3.3 directly; 0.3.4 did not
change the editor's preview path. An earlier revision of this guide said the published editor
had a defect here and that an unreleased fix was required; that was wrong, and
the fix in question turned out to change nothing observable
(`KNOWN_BUGS.md` U19). If a preview value ever does look stuck, hard-reload
first — the editor's loader has a constant filename, so a browser can keep
running a stale one — and confirm on the device before hunting for a bug in your
presenter.

Independently of that, the live preview remains a **wiring** check. It runs
your presenter, but a passing preview is not a guarantee of runtime
correctness, and the device remains the place to confirm real behaviour.

## The document store

Your documents, signing keys and published bundles live **outside** your source
tree, in a store owned by exactly one app:

```
~/.keliver-portal/apps/<app>-<hash of the app's path>/
```

Ask for the resolved path with `$KP/keliver-store-path.sh <app-dir>`.

Resolution order: `PORTAL_STORE` → `"store"` in `keliver.portal.json` → the
pointer the relay writes to `<app>/.gradle/keliver-store-path` → the default
above. The pointer is how the publisher and the device host find your signing
keys; it is machine-specific and the scaffolded `.gitignore` excludes it.

**One store, one app.** A store records its owner and refuses to serve a second
app, because two apps sharing a store delete each other's documents and can
write one app's screens into the other's source tree.

Both halves of the default name come from the app's **canonical** path, so a
symlink and the real directory are one app with one store and one signing
identity. (Before 0.3.5 the hash was canonical and the readable half was not:
launching through `~/work/current -> ~/work/app-v2` gave you a second store and
a second identity, chosen by which path you typed.)

### If you move or rename an app

Your identity lives in the store, and the store records the path it was claimed
from. Move or rename the directory and the portal **refuses to start**, names
the store, and tells you the command below. Nothing is deleted and no new
signing key is created:

```bash
$KP/keliver-store-recover.sh /abs/path/to/the/app
```

**Stop the portal first.** The command takes a lock the relay also takes at
startup, so a relay starting during a recovery is held off — but one that is
already running will go on serving a store it no longer owns.

That rewrites the store's owner marker and the app's pointer, then **runs the
resolver and checks it selects that store**. If either write or the check
fails — or you interrupt it with Ctrl-C — the previous binding is put back,
byte for byte, and verified before the command says so; it exits non-zero. So a
success message means the binding works, not merely that two files were
written. If the restoration itself cannot be completed, it keeps a backup under
`<app>/.gradle/` and prints exactly what to copy back. A `kill -9` or a power
cut cannot be rolled back; the same backup is what is left to work from. It
never reads, copies or regenerates key material — it prints the public-key
fingerprint before and after so you can see the identity is the same one — and
it never merges two stores. Bundles signed before the move still verify
afterwards.

It refuses, changing nothing, if the recorded owner still exists and still uses
that store (that is two apps, not a move), if this app already has a store of
its own holding an identity or documents, or if `keliver.portal.json` pins it to
a different store — a committed setting outranks this command, so change the
file instead. `PORTAL_STORE` is ignored here and says so: a one-run override
must not decide a permanent binding.

If the directory is a **copy** rather than a move, delete the pointer it
inherited instead and it will start its own store:

```bash
rm /abs/path/to/the/copy/.gradle/keliver-store-path
```

An owner path that no longer exists does not release the store. Absence is not
proof of ownership, so the store is never handed over automatically — you run
the command above and name both sides.

### If the portal reports a store "split"

If the same app was launched through both a symlink and the real directory
before 0.3.5, it has two stores and two signing identities. The portal refuses
to start and lists them with their public-key fingerprints. Pick the one your
published bundles verify against and name it:

```bash
$KP/keliver-store-recover.sh /abs/path/to/the/app --store ~/.keliver-portal/apps/<the one to keep>
```

The other store is not read, moved or deleted. **Picking again is not a
re-run of the same command**, though: once the app is bound to one of them, that
store holds the identity, and recovery refuses to abandon it. To change your
mind, move the one you bound aside first:

```bash
mv ~/.keliver-portal/apps/<the one you bound> ~/.keliver-portal/apps/<same name>.abandoned
$KP/keliver-store-recover.sh /abs/path/to/the/app --store ~/.keliver-portal/apps/<the other one>
```

### Coming from an older Keliver

Older versions kept everything in a single shared `~/.keliver-portal`. Nothing
there is moved or deleted, and it is **not** adopted automatically — a shared
directory cannot be attributed to one app. On startup the relay reports what it
still holds. To give it to one app, deliberately:

```bash
$KP/keliver-adopt-legacy-store.sh /abs/path/to/myapp
```

That copies the signing identity, bundles and documents into that app's store,
per file, never overwriting anything already there without `--force`, and never
touching the legacy directory.

## Your own version of this guide

If your app has `docs/PORTAL_USAGE.md`, `get_guide` returns **that** instead of
this document — so a team can document its own conventions and have agents read
them.
