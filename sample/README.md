# Konduit sample

Minimal end-to-end Konduit setup. The whole thing renders a single
`Text` widget reading **"Hello, Konduit!"** inside a `Box`, with the
guest tree authored in Kotlin/JS and the host rendering on both
Android and iOS.

It's deliberately small. The goal is to be the smallest faithful
demonstration of every required moving part: schema, codegen,
guest bundle, host renderer, and the cross-platform Compose mount
point. If you're new to Konduit, this is the file tree to copy.

## Module map

```
sample/
├── schema/                  # @Schema definition (Box + Text)
├── shared-modifier/         # Codegen: layout modifiers (empty here)
├── shared-widget/           # Codegen: host + guest widget interfaces
├── shared-protocol-host/    # Codegen: host-side protocol adapters
├── shared-protocol-guest/   # Codegen: guest-side widget system factory
├── shared/                  # Cross-platform AppService contract
├── guest/                   # Kotlin/JS bundle (Zipline-packaged)
├── host-compose/            # Compose Multiplatform host code:
│                            #   CmpWidgetFactory, SampleHostApp,
│                            #   iOS MainViewController. Builds an
│                            #   iOS framework named KonduitSampleHost.
└── host-android/            # Android application shell
```

`schema/` is pure JVM. Everything in `shared-*/` is multiplatform
codegen output — you don't write any source there, the Konduit
Gradle plugins do. `guest/` is Kotlin/JS. `host-compose/` targets
Android (`androidTarget`) and iOS (`iosArm64`, `iosSimulatorArm64`).
`host-android/` is a plain `com.android.application` shell.

## How the wire works

```
                   ┌─────────────────────────────┐
                   │ guest/ (Kotlin/JS)          │
                   │ ─ Compose @Composable tree  │
                   │ ─ binds SampleAppService    │
                   └────────────┬────────────────┘
                                │
                  Zipline       │ manifest.zipline.json + .zipline files
                  bundle  ──────┴────────────────────────────────┐
                                                                 ▼
        ┌────────────────────────────────────┐       ┌─────────────────────────┐
        │ host-android/MainActivity          │       │ Swift app  (your Xcode  │
        │ ─ OkHttp client                    │       │ project, embeds         │
        │ ─ TreehouseAppFactory(...)         │       │ KonduitSampleHost.framework)│
        │ ─ setContent { SampleHostApp }     │       │ ─ MainKt.MainViewController│
        └────────────────────────────────────┘       └─────────────────────────┘
                            │                                       │
                            └───── both call ──┬────────────────────┘
                                               ▼
                              host-compose/SampleHostApp
                              ─ TreehouseContent(...) mount
                              ─ CmpWidgetFactory (renderer)
```

Both hosts construct a `TreehouseApp<SampleAppService>` (the only
platform-specific code is HTTP client wiring) and feed it into the
same `SampleHostApp` composable.

## Prerequisites

1. **JDK 17+** on your PATH.
2. **Android SDK** (`ANDROID_HOME` set) for `:host-android`.
3. **GitHub Packages credentials** to resolve Konduit artifacts.
   The pre-release Konduit Maven repo
   (`maven.pkg.github.com/waliasanchit007/konduit`) requires a GitHub
   token with `read:packages` scope. Two ways to provide it:

   - **gradle.properties** (recommended for dev):
     ```properties
     gpr.user=your-github-username
     gpr.token=ghp_xxxxxxxxxxxxxxxxxxxxxx
     ```
     Put this in `~/.gradle/gradle.properties` (NOT in this repo —
     the included `sample/gradle.properties` has commented-out keys
     you can copy from).

   - **Environment variables** (for CI):
     ```sh
     export GITHUB_ACTOR=your-github-username
     export GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxxxx
     ```

4. (iOS only) **Xcode 15+** and CocoaPods / KMP-direct framework
   integration of your choice.

## Run it on Android

```sh
cd sample

# 1. Build the guest bundle.
./gradlew :guest:compileDevelopmentZipline

# 2. Serve the bundle. Konduit + Zipline don't ship a built-in
#    `serveDevelopmentZipline` Gradle task in this version; Python's
#    stdlib http.server is the smallest workaround. Run in another
#    terminal (or `&`-detach):
(cd guest/build/zipline/Development && python3 -m http.server 8080) &

# 3. Install the Android host on an emulator. The 10.0.2.2 manifest
#    URL in `DevConfig.MANIFEST_URL` points at the emulator-side
#    alias for the host machine's localhost. For a physical device,
#    edit it to your laptop's LAN IP.
./gradlew :host-android:installDebug

# 4. Launch:
adb shell am start -n dev.konduit.sample/dev.konduit.sample.host.MainActivity
```

You should see "Hello, Konduit!" rendered at top-start — the
guest's `@Composable fun Show() { Box { Text("Hello, Konduit!") } }`
made it through Zipline and the host's `CmpWidgetFactory` painted
it. Verify in `adb logcat` under the `KonduitSample` tag — the
expected sequence is `manifestReady` → `ziplineCreated` →
`mainFunctionStart`/`End` → `codeLoadSuccess` → `takeService
name=app`.

> **Tested working** against a Pixel 9 emulator (API 37). The
> sample's first end-to-end run surfaced 5 latent bugs/gaps between
> the README and what actually shipped; full debugging log is in
> [TESTING.md](TESTING.md). All five are fixed in tree.

## Run it on iOS

The sample ships a ready-made Xcode project at
[`iosApp/`](iosApp/) that links against the
`KonduitSampleHost.framework` produced by `:host-compose`. The
Run Script Build Phase in the Xcode project invokes
`./gradlew :host-compose:embedAndSignAppleFrameworkForXcode`
automatically — you don't build the framework manually.

```sh
cd sample

# 1. Build the guest bundle. Same step as Android.
./gradlew :guest:compileDevelopmentZipline

# 2. Serve. The iOS simulator reaches `localhost` directly (no
#    10.0.2.2 alias needed). For a physical device, edit
#    IosDevConfig.manifestUrl in MainViewController.kt to your
#    laptop's LAN IP.
(cd guest/build/zipline/Development && python3 -m http.server 8080) &

# 3. Boot a simulator (pick any iPhone from `xcrun simctl list`).
SIM_UDID=$(xcrun simctl list devices iPhone 2>&1 | grep "iPhone 17" | head -1 | sed -E 's/.*\(([0-9A-F-]+)\).*/\1/')
xcrun simctl boot "$SIM_UDID"
open -a Simulator

# 4. Build the iOS app (the Run Script will compile the framework).
cd iosApp
xcodebuild \
  -project iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$SIM_UDID" \
  -derivedDataPath build/ \
  CODE_SIGN_IDENTITY="" CODE_SIGNING_REQUIRED=NO build

# 5. Install + launch with --console to capture EventListener output.
xcrun simctl install "$SIM_UDID" \
  build/Build/Products/Debug-iphonesimulator/KonduitSample.app
xcrun simctl launch --console "$SIM_UDID" dev.konduit.sample.KonduitSample
```

You'll see "Hello, Konduit!" render in the simulator and the same
`manifestReady` → `ziplineCreated` → `mainFunctionStart` →
`codeLoadSuccess` → `takeService name=app` sequence in the
console.

> **Tested working** against an iPhone 17 Pro simulator (iOS 26.3.1,
> Xcode 26.3). The first iOS run surfaced 3 additional bugs/gaps;
> all fixed in tree. See [TESTING.md § iOS case study](TESTING.md#ios-case-study).

### iOS adopter notes

If you're integrating Konduit into your own Xcode project rather
than using the bundled `iosApp/`:

1. **Run Script Build Phase**: add a "Compile Kotlin Framework"
   Run Script that does
   `cd "$SRCROOT/../path-to-konduit-host-module" && ./gradlew :your-module:embedAndSignAppleFrameworkForXcode`.
2. **Bundle ID**: your Swift target's bundle ID is independent
   of the framework. The framework just exposes
   `MainKt.MainViewController()`.
3. **Stdout capture**: `xcrun simctl launch --console <UDID> <bundle-id>`
   to see Kotlin's `println` output. `os_log` queries via
   `simctl log show` don't pick up plain `println`.
4. **NSLog format strings**: avoid `NSLog("%@", kotlinString)` in
   Kotlin/Native code — it crashes the app with `EXC_BAD_ACCESS`
   on Xcode 26.3. Use `println` or wrap NSLog through a Swift
   helper. Details in TESTING.md § iOS-#3.

## What to copy when you adopt Konduit

The five steps an adopter typically follows when forking this sample
into a real product:

1. **Rename `SampleSchema` to your-domain schema.** Add new
   `@Widget(N)` data classes; bump `widgetVersion` in the guest's
   `StandardAppLifecycle` whenever you ship a non-additive change.

2. **Implement the renderer.** Add an `override fun YourWidget()`
   to `CmpWidgetFactory`, then write the matching `CmpYourWidget`
   class in `host-compose/.../CmpWidgetFactory.kt`.

3. **Replace `EmptySerializersModule()` in both host Spec impls.**
   If you add a `@Serializable` value class as a `@Property`, you'll
   need its serializer registered in the Spec's `serializersModule`
   (and in the guest's `Json { serializersModule = ... }`).

4. **Bind host services.** Inside `Spec.bindServices`, call
   `zipline.bind<YourService>("name", impl)` for any host capability
   the guest needs (HTTP, console, navigation callbacks). Hold
   strong references to the impls in Spec fields — Zipline doesn't
   retain bound services internally and inline anonymous classes
   get GC'd before the guest's first call. See the comment in
   `host-android/MainActivity.kt`.

5. **Swap dev manifest URL for prod.** Replace
   `DevConfig.MANIFEST_URL` with an HTTPS CDN URL and swap
   `ManifestVerifier.NO_SIGNATURE_CHECKS` for a real
   `SignatureChecks(...)` instance keyed off your production
   verifying key.

## Gotchas you'll hit on day one

These five caught us during the sample's first end-to-end run and
matter to any adopter writing their own Konduit project. Full
debugging log lives in [TESTING.md](TESTING.md).

1. **Apply the Zipline plugin to every module with `take`/`bind`
   calls** — not just the service-defining module. The IR plugin
   rewrites call sites; without it you get
   `IllegalStateException: unexpected call to Zipline.take`. The
   sample applies it to `:shared`, `:guest`, `:host-android`, and
   `:host-compose`.

2. **`:shared` also needs the `kotlinSerialization` plugin** for
   Zipline's adapter codegen to emit working `.serializer()`
   lookups. Without it: `Serializer for class 'X' is not found`.

3. **Interfaces that extend `AppService` need a manual
   `Adapter`** — see [Zipline #765](https://github.com/cashapp/zipline/issues/765).
   The sample's `ManualSampleAppServiceAdapter.kt` shows the
   pattern. Symptom of skipping it:
   `codeLoadFailed: Constructor 'Adapter.<init>' can not be called`.
   When you add a new method to `SampleAppService`, add a matching
   `ReturningZiplineFunction` block AND a delegating override in
   the `outboundService(...)` anonymous object, keeping call IDs
   stable across host + guest.

4. **Always wire an `EventListener`** in `TreehouseAppFactory.create(...)`,
   even just a logging one. Without it, every Zipline failure
   (`codeLoadFailed`, `uncaughtException`, `manifestParseFailed`)
   is silent — blank screen, no clue in logcat. The sample's
   `LoggingEventListenerFactory` in `MainActivity.kt` is the
   minimum useful baseline.

5. **No `serveDevelopmentZipline` Gradle task exists** in this
   Zipline plugin version. Use `python3 -m http.server 8080` from
   `guest/build/zipline/Development/`, or wire up your own
   serving (Ktor, nginx, etc.). The dev server is intentionally
   out of scope for the sample.

## What's intentionally missing

To keep the sample focused, none of these are wired up — but each is
documented in the main Konduit repo under `docs/` if you need them:

- **Hot reload** (WebSocket-driven manifest refetch). See
  ServerDrivenUI's `HotReloadManager` for the reference impl;
  pairs with `dev.konduit:konduit-dev-server` artifact.
- **Dev overlay** (error fallback, retry buttons, etc.). See
  `KonduitDevController` / `KonduitDevOverlay` in ServerDrivenUI.
- **Host-side services** (HostConsole, HostSnackbar, HostHttp).
  All bundled in `dev.konduit:konduit-host` — add the dep and bind
  them in `Spec.bindServices`.
- **Image loading** (`konduit-image` + Coil). The schema doesn't
  declare an `@Widget(N) Image` here; if you add one, plug in the
  matching `CmpImage` impl.

## Standalone vs. monorepo

This sample is a standalone Gradle build inside the Konduit repo —
its `settings.gradle.kts` is independent of the parent. To work
on it without publishing Konduit first, you can either:

- Run `./gradlew publishToMavenLocal` from the Konduit root (one
  level up) so the sample resolves `dev.konduit:*` from your local
  Maven cache.
- Or rely on the live `1.0.0-caliclan.4-SNAPSHOT` artifacts in the
  GitHub Packages repo — that's what the `gpr.user / gpr.token`
  setup above is for.
