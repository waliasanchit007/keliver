# Embedding this Keliver host in your iOS app

`keliver-host-ios/` is a standalone Gradle build of a static framework,
`KeliverHost`: this app's production Keliver host (the same Kotlin as the
standalone host `keliver-new-ios-host.sh` writes). Its settings are in
`src/iosMain/kotlin/@@PKG_PATH@@/HostConfig.kt`, which the build checks.

## In your Xcode project (once)

1. **Build phase.** Add a *Run Script* phase **before Compile Sources**:

   ```sh
   if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then exit 0; fi
   cd "$SRCROOT/@@REL@@" && ./gradlew --console=plain embedAndSignAppleFrameworkForXcode
   ```

   Here `@@REL@@` is `keliver-host-ios/`'s path relative to your project
   directory (`$SRCROOT`); adjust it if you move either.
2. **Build settings** of your app target:
   - `ENABLE_USER_SCRIPT_SANDBOXING = NO` (the phase runs Gradle);
   - `OTHER_LDFLAGS` gains `-lsqlite3` (Zipline's cache and the host's SQL);
   - your `PRODUCT_NAME` must not be `KeliverHost`, the framework's module name.
3. **Info.plist:** add `CADisableMinimumFrameDurationOnPhone = YES` (Compose
   Multiplatform needs it on ProMotion devices). For an `http://` development
   server only, an App Transport Security exception for that host; a release
   build of the framework refuses both an `http://` server and any ATS exception
   in the Info.plist Xcode builds (`$SRCROOT/$INFOPLIST_FILE`).
4. **Add `KeliverScreen.swift`** (beside this file) to your app target.
5. **Timing marks (optional, M0):** set the build setting `KELIVER_TIMING = YES`
   in the configurations that should have them: your Debug one, and a
   `Benchmark` configuration (duplicate Release, then also set
   `KOTLIN_FRAMEWORK_BUILD_TYPE = release`) that is never shipped. Every other
   configuration compiles a no-op instead. `Keliver.shared.onTrace` gets each
   mark (docs/DEVICE_HOST.md, "Timing marks").
6. **The debug link (optional, ED3):** in your **Debug configuration only**,
   set `KELIVER_DEBUG_LINK = YES`, add `KELIVER_DEBUG_LINK` to
   `SWIFT_ACTIVE_COMPILATION_CONDITIONS`, and give that configuration's
   Info.plist a URL type whose scheme is the one in
   `src/debuglink/kotlin/@@PKG_PATH@@/KeliverDebugLink.kt` (`DEBUG_LINK_SCHEME`)
   plus `NSAllowsLocalNetworking` under `NSAppTransportSecurity`. Then, in your
   `App`:

   ```swift
   #if KELIVER_DEBUG_LINK
       .onOpenURL { url in _ = KeliverDebugLinkKt.openKeliverDebugLink(url: url.absoluteString) }
   #endif
   ```

   The editor's "On a device" QR code then points that build at the relay's
   device proxy. A release build of the framework refuses `KELIVER_DEBUG_LINK`
   and a `keliver-` URL scheme in the Info.plist Xcode builds. That check runs
   only on release framework builds, so a configuration you make by
   duplicating Debug (a tester "Staging" build) keeps the link: duplicate
   Release for those instead. Every install of these screens (this app and the
   standalone host) registers the same scheme; keep one on a device.

## In your code

```swift
VStack {
    Text("Your native header")
    KeliverScreen()          // the guest's screen
}
```

`Keliver.shared.start()` (for instance in your `App`'s `init`) starts the
lookup early, and the code load as soon as it answers; otherwise the first
`KeliverScreen` does. One host per process:
every `KeliverScreen` shares its one lookup and one load.

## Not covered here

- An app that already embeds another Kotlin framework would carry two Kotlin
  runtimes; put these sources in that framework's module instead.
- An XCFramework (`./gradlew assembleKeliverHostReleaseXCFramework`) avoids
  Gradle in your Xcode build, with the settings baked in; it is not checked on
  CI yet.
