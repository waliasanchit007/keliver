# Option X — faithful keliver-material Android render: precise build plan

> Branch: `spike/keliver-web` (konduit ROOT build). Approach de-risked; foundation
> (`portal-core` js target) committed. Relay (`:portal-relay:run`, :8077) + browser
> portal (:8096) already work and push the serialized tree. AVD: `Pixel_9`.
> All templates verified to exist in-repo.

## Why root build (not a separate app)

keliver schema codegen is FIR-**source**-based → can't codegen against the published
(classes-only) `keliver-material-schema`. The root build already has the generated
keliver-material protocol adapters (`web-spike-protocol-guest` =
`KeliverMaterialProtocolWidgetSystemFactory`, `web-spike-protocol-host` =
`KeliverMaterialHostProtocol`), the Zipline gradle plugin (used by
`keliver-treehouse-guest`), and a treehouse-guest template
(`test-app/presenter-treehouse`). All project deps; no version/publish/codegen risk.

## Module 1 — guest: `portal-device-guest` (new)

`settings.gradle`: `include ':portal-device-guest'`.

`portal-device-guest/build.gradle` (template = `test-app/presenter-treehouse/build.gradle`):
```groovy
apply plugin: 'org.jetbrains.kotlin.multiplatform'
apply plugin: 'org.jetbrains.kotlin.plugin.compose'
apply plugin: 'org.jetbrains.kotlin.plugin.serialization'
apply plugin: 'app.cash.zipline'

redwoodBuild { ziplineApplication('portal-device') }

kotlin {
  jvm() // so the android host can consume the HostApi/Presenter interfaces
  js { outputModuleName = 'portal-device-guest'; browser(); binaries.executable() }
  sourceSets {
    commonMain { dependencies { api projects.keliverTreehouse; api libs.zipline } }
    jsMain {
      dependencies {
        api projects.keliverTreehouseGuest
        implementation projects.webSpikeProtocolGuest   // KeliverMaterialProtocolWidgetSystemFactory
        implementation projects.keliverMaterialCompose   // StyledBox/StyledText/Button/...
        implementation projects.keliverLayoutCompose      // Column/Row/Spacer
        implementation projects.portalCore                // WidgetNode + deserializeTree
        implementation libs.jetbrains.compose.runtime
        implementation libs.kotlinx.coroutines.core
      }
    }
  }
}
```

`commonMain/.../HostApi.kt` (template = test-app `HostApi.kt`):
```kotlin
package dev.keliver.portaldevice
import app.cash.zipline.ZiplineService
interface HostApi : ZiplineService { suspend fun httpCall(url: String): String }
```

`commonMain/.../PortalPresenter.kt`:
```kotlin
package dev.keliver.portaldevice
import app.cash.zipline.ZiplineService
import dev.keliver.treehouse.ZiplineTreehouseUi
interface PortalPresenter : ZiplineService { fun launch(): ZiplineTreehouseUi }
```

`jsMain/.../RenderNode.kt` — COPY `web-spike/src/wasmJsMain/kotlin/RenderNode.kt` verbatim
(it only uses keliver-material/layout compose + portal-core, all available here). Add `package dev.keliver.portaldevice` + import `dev.keliver.portal.*`.

`jsMain/.../RealPortalPresenter.kt` (template = `RealTestAppPresenter.kt` + `TestAppTreehouseUi.kt`):
```kotlin
package dev.keliver.portaldevice
import androidx.compose.runtime.*
import dev.keliver.material.compose.StyledText
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.portal.deserializeTree
import dev.keliver.treehouse.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

private const val RELAY = "http://10.0.2.2:8077/tree"

class RealPortalPresenter(private val hostApi: HostApi, json: Json) : PortalPresenter {
  override val /* if interface has it */ appLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
    json = json, widgetVersion = 1U,
  )
  override fun launch(): ZiplineTreehouseUi = PortalTreehouseUi(hostApi).asZiplineTreehouseUi(appLifecycle)
}

class PortalTreehouseUi(private val hostApi: HostApi) : TreehouseUi {
  @Composable override fun Show() {
    var json by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
      while (true) { json = runCatching { hostApi.httpCall(RELAY) }.getOrNull(); delay(1000) }
    }
    val j = json
    if (j != null) RenderNode(deserializeTree(j))
    else StyledText(text = "connecting to portal…", fontSize = 16)
  }
}
```
(Note: `PortalPresenter` needs `appLifecycle` if `asZiplineTreehouseUi`/host needs it — match test-app's `TestAppPresenter` which exposes `appLifecycle`. Add `val appLifecycle: StandardAppLifecycle` to the interface, as test-app does.)

`jsMain/.../presentersJs.kt` (template = test-app `presentersJs.kt`):
```kotlin
package dev.keliver.portaldevice
import app.cash.zipline.Zipline
private val zipline by lazy { Zipline.get() }
@OptIn(ExperimentalJsExport::class) @JsExport
fun preparePresenters() {
  val hostApi = zipline.take<HostApi>("HostApi")
  zipline.bind<PortalPresenter>("PortalPresenter", RealPortalPresenter(hostApi, zipline.json))
}
```

**Verify M1:** `./gradlew :portal-device-guest:compileProductionExecutableKotlinJs` green; `:portal-device-guest:ziplineApplication`-style task produces a bundle (check the `redwoodBuild.ziplineApplication` task name via `:portal-device-guest:tasks`).

## Module 2 — Android host: `portal-device-android` (new, com.android.application)

`settings.gradle`: `include ':portal-device-android'`.

Template = `sample/host-android` MainActivity (the Explore-mapped one). Key deps
(project): `keliver-treehouse-host`, `keliver-treehouse-host-composeui` (TreehouseAppFactory,
TreehouseContent), `web-spike-protocol-host` (KeliverMaterialHostProtocol),
`keliver-material-composeui` (ComposeUiKeliverMaterialWidgetSystem), `portal-device-guest`
(HostApi/PortalPresenter interfaces, jvm variant), `libs.okhttp`, activity-compose.

`MainActivity` essentials:
- `OkHttpClient().asZiplineHttpClient()`.
- `TreehouseAppFactory(context, httpClient, ManifestVerifier.NO_SIGNATURE_CHECKS, KeliverMaterialHostProtocol.Factory, cacheName, cacheMaxSize)`.
- `Spec`: `name="portal-device"`, `manifestUrl = MutableStateFlow("http://10.0.2.2:8080/manifest.zipline.json")`,
  `bindServices { zipline.bind<HostApi>("HostApi", RealHostApi(okHttp)) }`,
  `create { zipline.take<PortalPresenter>("PortalPresenter") }`.
- mount: `TreehouseContent(treehouseApp, widgetSystem = ComposeUiKeliverMaterialWidgetSystem(ImageLoader.Builder(ctx).components{ add(...) }.build()), contentSource = { app -> app.launch() })`.
- `RealHostApi`: `suspend fun httpCall(url) = okHttp.newCall(Request(url)).execute().body!!.string()` (on Dispatchers.IO).

`AndroidManifest.xml`: a launcher activity + `<uses-permission android:name="android.permission.INTERNET"/>` + `android:usesCleartextTraffic="true"` (for http 10.0.2.2).

**Verify M2:** `:portal-device-android:assembleDebug` green.

## Run + verify (with emulator)

```
# terminal A (konduit): relay already running on :8077
# terminal B: serve the guest bundle
./gradlew :portal-device-guest:serveDevelopmentZipline    # serves manifest on :8080
# terminal C: boot + install
$ANDROID_HOME/emulator/emulator @Pixel_9 -no-window -no-snapshot -gpu swiftshader_indirect &
./gradlew :portal-device-android:installDebug
adb shell am start -n dev.keliver.portaldevice/.MainActivity
```
Then edit text in the browser portal (:8096) → the emulator's native keliver-material
screen updates within ~1s (the poll). `adb exec-out screencap -p > /tmp/dev.png` to capture.

## Likely snags (pre-noted)

- `ziplineApplication` task name + the dev-server task (`serveDevelopmentZipline`) — confirm via `:portal-device-guest:tasks`.
- `TreehouseAppFactory` / `TreehouseContent` exact signatures — read `keliver-treehouse-host-composeui` (the sample's MainActivity is the truth).
- `com.android.application` in the root build — the `RedwoodBuildPlugin.configureCommonAndroid` disables release for app modules; debug should work. May need `android { namespace, compileSdk, defaultConfig{applicationId,minSdk} }`.
- `ManifestVerifier.NO_SIGNATURE_CHECKS` name — confirm in keliver-treehouse-host.
- The host `ComposeUiKeliverMaterialWidgetSystem` needs a Coil `ImageLoader` (+ the browser-fetch fetcher is web-only; on Android Coil's default network works, add `coil-network-okhttp`).

## Done when

Edit in browser portal → Pixel_9 emulator native render updates within a poll, faithful
to the web preview (same keliver-material widgets).
