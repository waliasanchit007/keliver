# Getting started with Keliver

Get a server-driven screen running natively on Android + iOS, then write your
own — the short path. (For the exhaustive reference, see
[USAGE.md](USAGE.md); for the app architecture, see
[SCREEN_ARCHITECTURE.md](SCREEN_ARCHITECTURE.md).)

## What you're building

Your UI is authored once in **Kotlin/JS** (the "guest"), compiled to a
`.zipline` bundle, and rendered with **native widgets** on each platform (the
"host") via Zipline. You can ship a new bundle — new screens, new logic — without
an app-store release.

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
> are `dev.keliver:keliver-*:0.1.0` on Maven Central).

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

## Where to go next

- **[SCREEN_ARCHITECTURE.md](SCREEN_ARCHITECTURE.md)** — the app architecture (read this before your second screen).
- **[USAGE.md](USAGE.md)** — exhaustive setup, host boilerplate, the dev loop, every silent-failure shape.
- **[KNOWN_BUGS.md](KNOWN_BUGS.md)** — the punch list to internalize before you hit them.
- **[MIGRATION_FROM_REDWOOD.md](MIGRATION_FROM_REDWOOD.md)** — if you're coming from Redwood.
