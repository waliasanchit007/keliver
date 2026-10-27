# Getting started with Keliver

Get a server-driven screen running natively on Android + iOS, edit it visually
in the browser, and then write your own — the short path. (For a new app of
your own, the adopter path is
[PORTAL_ADOPTER_GUIDE.md](PORTAL_ADOPTER_GUIDE.md); for the app architecture,
see [SCREEN_ARCHITECTURE.md](SCREEN_ARCHITECTURE.md).)

## See it before you install anything

**▶ [The Keliver Portal playground](http://keliver.me/keliver/)** — the real
visual editor running entirely in your browser, no checkout and no server. Drag
widgets, bind fields, hit **</> Code** and read what comes out. That
Kotlin is the same thing steps 1–4 below put under git.

## What you're building

Your UI is authored once in **Kotlin/JS** (the "guest"), compiled to a
`.zipline` bundle, and rendered with **native widgets** on each platform (the
"host") via Zipline. You can ship a new bundle — new screens, new logic — without
an app-store release.

The screens are also **round-trippable**: the Keliver Portal edits the same
`.kt` files you edit by hand, so a visual change lands as a surgical git diff
rather than a generated blob. Step 5 covers that loop; it is the part of Keliver
that is hardest to get from the code alone.

## 1. Get the code — and pick your starting point

Install **git-lfs before cloning**: `*.png` is LFS-tracked, and a clone
without it fails checkout partway.

```bash
git lfs install
git clone https://github.com/waliasanchit007/keliver.git
cd keliver
```

**A new app does not start from this repo.** It starts with `keliver-init`
from the portal tools bundle, which scaffolds a standalone Gradle project on
the published `dev.keliver:*:0.4.0` libraries (Maven Central) and the
`keliver-material` widgets, with no Keliver checkout. Download
`keliver-portal-tools-0.3.12.zip` from the
[tools 0.3.12 release](https://github.com/waliasanchit007/keliver/releases/tag/portal-tools-v0.3.12),
unzip it, and follow [PORTAL_ADOPTER_GUIDE.md](PORTAL_ADOPTER_GUIDE.md):

```bash
keliver-portal-tools-0.3.12/bin/keliver-init MyApp && cd myapp
```

**[`sample/`](../sample) is a runnable demo of the runtime**, with every moving
part wired up by hand: its **own widget schema** (not `keliver-material`), the
codegen, a guest bundle and native hosts. It is its own Gradle build, pinned to
an old library release (`keliver = "0.1.0"` in
`sample/gradle/libs.versions.toml`). Run it to see the loop, and copy its shape
only if you are building your own widget schema.

```
sample/
├── schema/                 # @Schema: your widgets (Box, Text, Column, Button, …)
├── shared/                 # host↔guest service contracts (ZiplineService)
├── shared-*/               # codegen output (you don't edit these)
├── guest/                  # your screens (Kotlin/JS → .zipline bundle)
└── host-compose, host-android, iosApp   # the native hosts
```

> Adding Keliver to a *new* project instead? Use `keliver-init` (above); the
> published artifacts are `dev.keliver:*:0.4.0` on Maven Central.

## 2. Run it

> **JDK 17 is required.** If `./gradlew` complains about the JVM version:
> `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`

**Android** (emulator running):
```bash
cd sample
./gradlew :guest:serveDevelopmentZipline --continuous &   # serve + hot-reload
./gradlew :host-android:installDebug
adb shell am start -n dev.keliver.sample/dev.keliver.sample.host.MainActivity
```

**iOS** (simulator):
```bash
cd sample
./gradlew :guest:serveDevelopmentZipline --continuous &
# build + run sample/iosApp from Xcode, or via xcodebuild against a booted sim
```

You should see the **Workouts** screen — a list fetched from an API, with
favorite toggles backed by a host database, and live search. Edit a guest file
and it hot-reloads.

## 3. Write your first screen

Keliver screens read like native Android — a **Repository** (data), a
**Presenter** (the "ViewModel": logic + state), and a **Screen** (UI). The full
pattern, both styles, and when to use each are in
**[SCREEN_ARCHITECTURE.md](SCREEN_ARCHITECTURE.md)**; the `sample`'s `Workouts*`
files are the worked example, with a green unit test
(`./gradlew :guest:jsTest`).

The 3-file shape:
```kotlin
class MyRepository(http: KeliverHttp, store: MyHostStore) { /* data + logic */ }
@Composable fun MyPresenter(events: Flow<MyEvent>, repo: MyRepository): MyModel { /* state */ }
@Composable fun MyScreen(model: MyModel, onEvent: (MyEvent) -> Unit) { /* UI */ }
```

## 4. Add data — API + database

- **API:** the host binds one generic `HostHttpProvider` (your HttpClient + base
  URL + auth); the guest defines endpoints + `@Serializable` types and calls
  them with `KeliverHttp.get/post`. New endpoints ship in the bundle.
- **Database / persistence:** the guest can't open SQLite, so expose your host
  store as a `ZiplineService` and `take()` it in the guest.

Both are wired in the sample (`SampleHostServices`, `HostFavoritesStore`,
`keliver-http`); the recipe + gotchas (the serialization plugin, the suspend/U1
rule) are in [SCREEN_ARCHITECTURE.md](SCREEN_ARCHITECTURE.md).

## 5. Edit a screen visually — the Portal

The portal is a visual editor that reads and writes your real `screens/*.kt`.
Which command you use depends on where you are:

**In this repo**, to see the loop against Keliver's own dogfood app
(`portal-app-lib` — note this is a different app from `sample/`):

```bash
scripts/keliver-dev.sh            # portal-server + editor + live device bundle
scripts/keliver-dev.sh --android  # …and install + launch the Android host
```

**In your own app repo**, using the published tools bundle — no Keliver
checkout:

```bash
keliver-portal .          # server + editor against this app
keliver-portal status .   # is it up?
keliver-portal stop .     # shut it down
```

Either way, open **http://localhost:8096**. Ctrl-C stops everything.

Three ways to edit, all landing in the same file:

1. **The portal UI** — drag widgets, tweak props, bind fields with `@`, wire
   actions, add `if`/`forEach` from the Logic palette.
2. **Your code editor** — edit the screen file directly (`screens/home.kt` in a
   `keliver-init` app, `screens/main.kt` in `portal-app-lib`); the
   file-watcher ingests it and the portal and any attached devices update live.
3. **An AI agent** — the `portal-mcp` stdio server exposes `get_catalog`,
   `get_document`, `apply_ops` (transactional, with `dryRun`), `find_usages`.
   Every widget and prop is catalog-grounded, so the agent edits a validated
   document rather than guessing at text.

**The round-trip boundary:** the portal owns `screens/*.kt` — layout, bindings,
`if`/`forEach`. You own `logic/` and the bundle's entry point (`device/Main.kt`,
which `keliver-new-device-target.sh` adds to a `keliver-init` app;
`PublishedEntry.kt` in `portal-app-lib`); the portal never touches them. If a portal edit binds a new field, your presenter stops compiling
until you add it — the boundary enforcing itself.

Full workflow, including preview mock rows and the device loop: for your own
app, **[PORTAL_ADOPTER_GUIDE.md](PORTAL_ADOPTER_GUIDE.md)**; inside this repo
(contributors, `portal-app-lib`), **[PORTAL_USAGE.md](PORTAL_USAGE.md)**.

## Where to go next

- **[SCREEN_ARCHITECTURE.md](SCREEN_ARCHITECTURE.md)** — the app architecture (read this before your second screen).
- **[PORTAL_ADOPTER_GUIDE.md](PORTAL_ADOPTER_GUIDE.md)** — your own app on the tools bundle: scaffold, edit, devices, publishing, production hosts.
- **[PORTAL_USAGE.md](PORTAL_USAGE.md)** — the same loop inside this repo, for contributors.
- **[USAGE.md](USAGE.md)** — the older, pre-portal integration guide: its setup steps are historical, but its runtime/Zipline silent-failure material still applies.
- **[MIGRATION_FROM_REDWOOD.md](MIGRATION_FROM_REDWOOD.md)** — if you're coming from Redwood.
- **[KNOWN_BUGS.md](KNOWN_BUGS.md)** — a searchable reference for when something fails silently. Don't read it front-to-back; grep it when you hit a symptom.
