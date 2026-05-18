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

# 1. Build the guest bundle. This compiles the Kotlin/JS source and
#    packages it into manifest.zipline.json + a set of .zipline
#    module files under guest/build/zipline/Development/.
./gradlew :guest:compileDevelopmentExecutableKotlinJs :guest:serveDevelopmentZipline &

# 2. Install the Android host on an emulator (the 10.0.2.2 manifest
#    URL in DevConfig points at the dev server above; for a physical
#    device, edit DevConfig.MANIFEST_URL to your laptop's LAN IP).
./gradlew :host-android:installDebug

# 3. Launch:
adb shell am start -n dev.konduit.sample/dev.konduit.sample.host.MainActivity
```

You should see a centered "Hello, Konduit!" — the guest's
`@Composable fun Show() { Box { Text(...) } }` rendered on Android.

## Run it on iOS

`host-compose` produces a framework named `KonduitSampleHost`. The
build outputs land under:

```
host-compose/build/bin/iosArm64/debugFramework/KonduitSampleHost.framework
host-compose/build/bin/iosSimulatorArm64/debugFramework/KonduitSampleHost.framework
```

Build the simulator framework with:

```sh
./gradlew :host-compose:linkDebugFrameworkIosSimulatorArm64
```

Then in your Xcode iOS project:

1. Add the produced `KonduitSampleHost.framework` to your target
   (Build Phases → Link Binary With Libraries → drag the
   `.framework` from the path above; mark "Embed & Sign").
2. In Swift, host the Compose view controller via
   `UIViewControllerRepresentable`:

   ```swift
   import SwiftUI
   import KonduitSampleHost

   @main
   struct KonduitSampleApp: App {
     var body: some Scene {
       WindowGroup { KonduitHostView() }
     }
   }

   struct KonduitHostView: UIViewControllerRepresentable {
     func makeUIViewController(context: Context) -> UIViewController {
       MainKt.MainViewController()
     }
     func updateUIViewController(_ vc: UIViewController, context: Context) {}
   }
   ```

3. Make sure the same guest dev server is running. The simulator
   reaches `http://localhost:8080` directly (no `10.0.2.2` alias
   needed); for a physical device, change `IosDevConfig.manifestUrl`
   in `MainViewController.kt` to your laptop's LAN IP.

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
