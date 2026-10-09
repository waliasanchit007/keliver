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

## In your code

```swift
VStack {
    Text("Your native header")
    KeliverScreen()          // the guest's screen
}
```

`Keliver.shared.start()` (for instance in your `App`'s `init`) starts the
lookup early; otherwise the first `KeliverScreen` does. One host per process:
every `KeliverScreen` shares its one lookup and one load.

## Not covered here

- An app that already embeds another Kotlin framework would carry two Kotlin
  runtimes; put these sources in that framework's module instead.
- An XCFramework (`./gradlew assembleKeliverHostReleaseXCFramework`) avoids
  Gradle in your Xcode build, with the settings baked in; it is not checked on
  CI yet.
