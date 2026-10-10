# An "existing" SwiftUI app that embeds a Keliver screen (W2)

A plain SwiftUI app (`ExistingApp`, bundle id `com.example.existingios`) standing
in for an adopter's. The lines marked `KELIVER EMBED` are what
`keliver-host-ios/EMBED.md` tells an adopter to do: the *Compile Kotlin
Framework* Run Script phase (`cd "$SRCROOT/keliver-host-ios" && ./gradlew
embedAndSignAppleFrameworkForXcode`), `ENABLE_USER_SCRIPT_SANDBOXING = NO` and
`-lsqlite3`, `CADisableMinimumFrameDurationOnPhone`, `Keliver.shared.start()`
and a `KeliverScreen()` in the layout. The project file is the iOS host
template's, with that build phase; its sources are the `iosApp/` folder.

`reference/inventory/ci/w2/ios-embed.sh` scaffolds `keliver-host-ios/` into a
copy with `keliver-new-ios-host.sh --embed`, adds its `KeliverScreen.swift` to
`iosApp/` (EMBED.md step 4), builds it with `xcodebuild` and checks it on the
simulator.
