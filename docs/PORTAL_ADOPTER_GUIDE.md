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
  **[tools 0.3.4](https://github.com/waliasanchit007/keliver/releases/tag/portal-tools-v0.3.4)**:

  ```bash
  curl -LO https://github.com/waliasanchit007/keliver/releases/download/portal-tools-v0.3.4/keliver-portal-tools-0.3.4.zip
  curl -LO https://github.com/waliasanchit007/keliver/releases/download/portal-tools-v0.3.4/keliver-portal-tools-0.3.4.zip.sha256
  shasum -a 256 -c keliver-portal-tools-0.3.4.zip.sha256   # sha256sum -c on Linux

  unzip keliver-portal-tools-0.3.4.zip
  export KP="$PWD/keliver-portal-tools-0.3.4/bin"
  ```

  The `.sha256` file is published beside the zip on the release page, so the
  check above verifies the download against what was actually released. (This
  document ships *inside* the bundle, so it deliberately does not quote the
  hash of its own container.)

  The tools version and the library version are **separate lines**: tools 0.3.4
  scaffolds projects against Maven libraries **`dev.keliver:*:0.3.3`**. The
  bundle records both in its `VERSION.json`.

* For the device route only: the **Android SDK platform-tools** (`adb`) and a
  running emulator or a connected device.

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

**One portal at a time.** The server port comes from `"port"` in
`keliver.portal.json`, but the **editor port is fixed at 8096**, so two apps
cannot run their portals simultaneously even with different server ports — the
second reports `port 8096 is already in use`. Stop one before starting the
other. Their document stores are still fully separate; this is only a port
collision.

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

**Tool discovery.** Ten tools are exposed. Depending on the client they may not
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
| `find_usages` | where a bound field or action is used |
| `device_screenshot` | a frame from a connected device |

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
appears (see above), and after a portal restart the document version numbering
starts again from the freshly ingested `.kt` — so always read `baseVersion`
from the `get_document` you just made, never a remembered one.

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
import myapp.logic.HomePresenter

object MyAppPreview : AppPreviewEntry {
  override val label = "myapp (real presenter)"

  override val screens: Map<String, ScreenPreview> = mapOf(
    "home" to ScreenPreview { env ->
      val b = HomePresenter()                   // your real presenter
      PreviewFrame(
        values = mapOf("tally" to b.tally),     // contract field -> current value
        dispatch = { action, _ ->               // portal action -> your bindings
          when (action) {
            "add" -> b.add()
            else -> env.log("unhandled action: $action")
          }
        },
      )
    },
  )
}
```

Values are **strings**, matching the mock transport. For a list field use
`putRows(field, itemVar, rows)` instead of a plain entry.

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

**Which build you need.** The published `dev.keliver:portal-editor:0.3.3` — the
version an adopter gets from Maven Central today. Both behaviours were measured
against it directly. An earlier revision of this guide said the published editor
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
