## F1 — scaffold lacks the serialization plugin (documented, not scaffolded)
`keliver-init` generates a build.gradle with the compose plugins only. Adding
any @Serializable API type needs `org.jetbrains.kotlin.plugin.serialization`.
SCREEN_ARCHITECTURE.md warns about this explicitly ("or @Serializable types get
no serializer on Kotlin/JS and you'll hit a runtime Serializer for class X not
found") — so it is known, and still not in the scaffold. Every adopter adding
an API-backed feature hits it. Cost here: caught by reading the docs first;
would have been a *runtime* error, not a compile error, if I hadn't.

## F2 — the serialization plugin also needs a pluginManagement version
Adding the plugin to build.gradle is not enough: settings.gradle pins every
plugin version in `pluginManagement.plugins`, and the serialization plugin is
absent there too. First build fails with "Plugin [id:
'org.jetbrains.kotlin.plugin.serialization'] was not found in any of the
following sources ... plugin dependency must include a version number".
Two edits in two files for what the docs present as one step. Both would be
eliminated by scaffolding the plugin (commented out) in keliver-init.

## F3 — the yarn/TLS trap recurred, on me, in this very session
`jsTest` pulls npm deps via yarn, which on a TLS-inspecting network reports a
rejected certificate as "Couldn't find package X on the npm registry". I had
documented this hours earlier and still lost a build cycle to it, because
NODE_EXTRA_CA_CERTS is per-shell and easy to omit while JAVA_TOOL_OPTIONS is
set. An adopter behind a corporate proxy hits this on their first `jsTest`,
with an error message pointing at a dependency problem that does not exist.
Strong argument for keliver-init emitting a gradle.properties or a documented
env block rather than leaving it to per-shell discipline.

## F4 — the scaffold has no device path at all
`keliver-init` generates a guest-only project: `js { browser() }`, and zero
references to android, iOS or a host anywhere in build.gradle or
settings.gradle. So an adopter who follows the documented path can build and
preview in a browser but **cannot run their screens on a device** without
hand-writing an Android or iOS host application, which the scaffolder does not
provide and GETTING_STARTED does not walk through for a new project (it points
at `sample/`, which lives in the Keliver repo).

For a framework whose pitch is "renders natively on Android and iOS", the
zero-to-device path for a new adopter currently runs through copying the
in-repo sample. That is a gap between the positioning and the scaffold.

## F5 — the device dev loop is hardcoded to the emulator
`portal-device-android/.../MainActivity.kt` and
`portal-device-guest/.../RealPortalPresenter.kt` hardcode `10.0.2.2` (the
Android emulator's alias for the host machine) for both the Zipline manifest
and the portal server. A physical device on the same LAN cannot use the live
dev loop without editing those constants and rebuilding. A real Samsung
SM-S938B was attached over network ADB during this run and could not be used
for that reason; the emulator was booted instead.

## F6 — the device overlay is gated on a version comparison that starts equal
The device shows the compiled bundle's own screens unless
`liveVersion > COMPILED_VERSION_main`. Both start at 1, so a freshly started
relay renders the *dogfood app's* screens on the device, not the app you are
editing, and nothing on screen explains why. One edit to any screen bumps the
document version to 2 and the overlay engages immediately (banner: "⚡ live
overlay — doc v2 (compiled v1, catching up…)").

Diagnosing this took reading three source files. A first-run adopter would
reasonably conclude the device loop was broken.

## F7 — `installDebug` fails when more than one device is attached
With a physical phone and an emulator both visible to adb,
`:portal-device-android:installDebug` fails with a bare
`com.android.builder.testing.api.DeviceException: InstallException` after a
6-minute build. `adb -s <serial> install -r <apk>` succeeded immediately. The
gradle task does not name which device it failed on.

## F8 — the iOS path could not be exercised
`xcode-select` points somewhere other than a full Xcode, so the simulator
tooling refuses to start:
  "Xcode is installed but not selected. Run
   sudo xcode-select -s /Applications/Xcode.app/Contents/Developer"
That needs the machine owner's password, so iOS is UNTESTED in this run.
