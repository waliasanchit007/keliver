# Keliver Portal — Usage Guide

The portal's loop: **design a screen in the browser → see it live everywhere →
publish it as compiled, signed Kotlin → implement the data/logic contract by
hand**. This guide covers where the exported code lives, what you're allowed to
edit, and how changes reach web, Android, and iOS.

## 1. Start the stack

```bash
cd konduit
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

# The portal server (:8077): projects/screens/drafts, publish, bundle store.
./gradlew :portal-relay:installDist
PORTAL_REPO=$PWD portal-relay/build/install/portal-relay/bin/portal-relay &

# The editor (:8096).
./gradlew :web-spike:wasmJsBrowserDevelopmentExecutableDistribution
(cd web-spike/build/dist/wasmJs/developmentExecutable && python3 -m http.server 8096) &

# The DEV guest bundle server (:8080) — what dev-mode devices load.
./gradlew :portal-device-guest:serveDevelopmentZipline &
```

Open **http://localhost:8096**. Everything you author is autosaved per
project/screen under `~/.keliver-portal/`.

## 2. Where the exported code is

| Where | What | Edit it? |
|---|---|---|
| Editor → **Export Kotlin** button | The current screen as Kotlin source, in an overlay — copy-paste into any keliver app. | It's a snapshot; paste-and-own if you take it elsewhere. |
| `portal-published-guest/src/jsMain/kotlin/generated/` | `PublishedScreen.kt` + `PublishedEntry.kt`, written by every **Publish**. This is what actually compiles and ships. | **Never** — regenerated on every publish. |
| `portal-published-guest/src/jsMain/kotlin/impl/` | `PublishedBindings.kt` — the hand-written implementation of the generated `PublishedScreenBindings` interface (data fields + action handlers). | **Yes — this is your file.** The publish step never touches it. |

That split is the round-trip boundary: the portal owns layout + bindings (the
tree), engineers own the implementations behind the generated interface. If a
publish changes the contract (say you bind a new field), your impl stops
compiling until you add it — that's the system working.

## 3. Changing things and seeing it everywhere

### Layout / styling / bindings → edit in the PORTAL (the tree is the source of truth)

1. Edit in the editor: add widgets from the palette, tweak properties, attach
   modifiers, bind props with **@** (then give them mock values in the
   Bindings panel), wire events to action names.
2. **Web:** the canvas preview updates as you type.
3. **Android (dev mode):** the app mirrors the active screen within ~1s:
   ```bash
   ./gradlew :portal-device-android:installDebug
   adb shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity
   ```
4. **iOS (dev mode):** same, live:
   ```bash
   (cd portal-device-ios-app && xcodebuild -project iosApp.xcodeproj -scheme iosApp \
     -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build)
   xcrun simctl install booted <path-to-built .app>
   xcrun simctl launch booted dev.keliver.sample.KeliverSample
   ```

No rebuild, no publish — dev devices poll the draft.

Don't hand-edit exported layout Kotlin expecting the portal to pick it up:
there is deliberately no Kotlin→tree parser. Layout changes go through the
editor (or through `~/.keliver-portal/<project>/<screen>.json` if you must).

### Data / logic → edit `impl/PublishedBindings.kt` (your Kotlin)

1. Change field values / action handlers in
   `portal-published-guest/src/jsMain/kotlin/impl/PublishedBindings.kt`
   (or wire them to real services — it's ordinary guest Kotlin).
2. Hit **Publish** in the editor (or `curl -X POST localhost:8077/publish`).
   This re-exports the active screen, compiles it together with your impl,
   **signs** the manifest, and stores it as the next bundle version.
3. **Prod devices** pick it up on next launch (they resolve
   `/bundles/latest?widgetVersion=…` at startup):
   ```bash
   # Android
   adb shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity --es mode prod
   # iOS
   xcrun simctl launch booted dev.keliver.sample.KeliverSample prod
   ```

The publish log (shown in the editor overlay) is your compile feedback: a
contract without an implementation fails right there with the Kotlin error.

## 4. Mental model

```
            EDIT (instant)                        SHIP (deliberate)
 editor ──► draft tree ──► web preview       Publish ──► exportKotlin
                       └─► dev Android/iOS            └─► + impl/ (yours)
                           (poll, ~1s)                 └─► compile + Ed25519 sign
                                                       └─► bundles/vN (gated)
                                                       └─► prod Android/iOS (verified)
```

- **Dev mode** devices interpret the tree — instant, unsigned, for authoring.
- **Prod mode** devices run compiled Kotlin only, verify the signature, and
  reject anything tampered (`codeLoadFailed: checksum mismatch`).
- Mock values in the Bindings panel exist only in the editor preview; dev
  devices show bound props as defaults, prod devices show your impl's data.
