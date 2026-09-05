# Getting started with Keliver

Get a server-driven screen running natively on Android + iOS, edit it visually
in the browser, and then write your own — the short path. (For the exhaustive
reference, see [USAGE.md](USAGE.md); for the app architecture, see
[SCREEN_ARCHITECTURE.md](SCREEN_ARCHITECTURE.md).)

## See it before you install anything

**▶ [The Keliver Portal playground](http://keliver.me/keliver/)** — the real
visual editor running entirely in your browser, no checkout and no server. Drag
widgets, bind fields, hit **Export Kotlin** and read what comes out. That
exported Kotlin is the same thing steps 1–4 below put under git.

## What you're building

Your UI is authored once in **Kotlin/JS** (the "guest"), compiled to a
`.zipline` bundle, and rendered with **native widgets** on each platform (the
"host") via Zipline. You can ship a new bundle — new screens, new logic — without
an app-store release.

The screens are also **round-trippable**: the Keliver Portal edits the same
`.kt` files you edit by hand, so a visual change lands as a surgical git diff
rather than a generated blob. Step 5 covers that loop; it is the part of Keliver
that is hardest to get from the code alone.

## 1. The starter is `sample/`

The [`sample/`](../sample) directory **is** the starter: a complete, runnable
project with every moving part wired up. Copy it and rename, or just run it
first to see the loop.

```
sample/
├── schema/                 # @Schema: your widgets (Box, Text, Column, Button, …)
├── shared/                 # host↔guest service contracts (ZiplineService)
├── shared-*/               # codegen output (you don't edit these)
├── guest/                  # your screens (Kotlin/JS → .zipline bundle)
└── host-compose, host-android, iosApp   # the native hosts
```

> Adding Keliver to a *new* project instead? Copy the version catalog from
> [USAGE.md → "Copy-paste version catalog"](USAGE.md) (the published artifacts
> are `dev.keliver:keliver-*:0.3.3` on Maven Central).

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
2. **Your code editor** — edit `screens/main.kt` directly; the file-watcher
   ingests it and the portal and any attached devices update live.
3. **An AI agent** — the `portal-mcp` stdio server exposes `get_catalog`,
   `get_document`, `apply_ops` (transactional, with `dryRun`), `find_usages`.
   Every widget and prop is catalog-grounded, so the agent edits a validated
   document rather than guessing at text.

**The round-trip boundary:** the portal owns `screens/*.kt` — layout, bindings,
`if`/`forEach`. You own `logic/` and `PublishedEntry.kt`; the portal never
touches them. If a portal edit binds a new field, your presenter stops compiling
until you add it — the boundary enforcing itself.

Full workflow, including preview mock rows and the device loop, is in
**[PORTAL_USAGE.md](PORTAL_USAGE.md)**.

## Where to go next

- **[SCREEN_ARCHITECTURE.md](SCREEN_ARCHITECTURE.md)** — the app architecture (read this before your second screen).
- **[PORTAL_USAGE.md](PORTAL_USAGE.md)** — the visual editing loop in full: publishing, devices, preview fidelity.
- **[USAGE.md](USAGE.md)** — exhaustive setup, host boilerplate, the dev loop, every silent-failure shape.
- **[MIGRATION_FROM_REDWOOD.md](MIGRATION_FROM_REDWOOD.md)** — if you're coming from Redwood.
- **[KNOWN_BUGS.md](KNOWN_BUGS.md)** — a searchable reference for when something fails silently. Don't read it front-to-back; grep it when you hit a symptom.
