# Using Konduit in another Compose Multiplatform project

> This guide was originally written in the ServerDrivenUI reference
> integration. It now lives in the Konduit fork so docs travel with
> the artifact. Code samples reference DevoStatus / ServerDrivenUI by
> name, but the patterns apply to any CMP host. A reorganized docs
> site is planned post-launch (see
> [PUBLIC_LAUNCH_ROADMAP.md](../PUBLIC_LAUNCH_ROADMAP.md) Phase 7).

This guide is for "I have a separate Compose Multiplatform app and I want one
or more of its screens to be Server-Driven UI." It walks through vendoring
Konduit, the minimum host-side boilerplate, defining a new screen on the
guest side, and the gotchas that bite.

If you're working on Konduit itself, see
[CONTRIBUTING.md](../CONTRIBUTING.md) — this doc assumes you're a downstream
consumer.

---

## TL;DR

```bash
# 1. Vendor Konduit as a git submodule in your project root
git submodule add https://github.com/waliasanchit007/ServerDrivenUI third_party/konduit

# 2. In your top-level settings.gradle.kts, include the modules with explicit
#    projectDir (see Step 1 — `includeBuild` does NOT work here because the
#    Konduit modules don't publish to Maven coordinates).

# 3. Put gpr.user + gpr.token in ~/.gradle/gradle.properties (classic PAT
#    with read:packages scope). NOTE: KONDUIT_READ_TOKEN is only the CI
#    secret *name* — locally the gradle property must be `gpr.token`.
#    See §"GitHub Packages auth" below.

# 4. Merge the required keys from Konduit's gradle.properties into yours
#    (kotlin.native.cacheKind=none in particular — CMP-8845 workaround).

# 5. Set up a TreehouseApp.Spec in your activity / view controller (Step 2).

# 6. Write a guest .kt file that extends `Screen` using the schema widgets
#    (Step 4).
```

Prerequisites: **JDK 21** (matches CI), **Xcode 16+** for iOS, Android SDK
with API 36, Kotlin 2.1.0+ compatible AGP.

Production-readiness reality check before you commit to this path:

- **Android**: solid. End-to-end verified, hot reload works.
- **iOS**: solid as of 2026-05-13 (gotcha #12 fixed, dispatcher pattern
  enforced via constructor). Verified on iPhone 17 Pro sim.
- **Library distribution**: GitHub Packages today; Maven Central tracked
  in [MAVEN_CENTRAL_SETUP.md](./MAVEN_CENTRAL_SETUP.md). Git submodule
  vendoring is still supported during early adoption.
- **Compose Facade**: landed in `1.0.0-caliclan.4`. Adopters depend on
  a single `dev.konduit:konduit-host` artifact (host modules) and a
  single `dev.konduit:konduit-guest` artifact (guest modules) instead
  of wiring the 8+ underlying modules manually. The old multi-module
  setup still works for projects that want stricter dep control.
- **Production load**: only showcase-level traffic tested. No 60 Hz Flow / large-list stress runs yet.

---

## Silent-failure cheat sheet — read this BEFORE you write code

These five failure shapes account for most "looks like Konduit is broken"
debug sessions. All five are documented in detail in `KNOWN_BUGS.md`;
this section is the at-a-glance index so you can search for the symptom
and jump straight to the fix.

| Symptom | Likely cause | Quick fix |
|---|---|---|
| `bindServices` hangs forever, no log | **U1** — `suspend fun X(...): List<@Serializable T>` | Drop `suspend` from the method; wrap suspect binds in `bindWithTimeout { … }` so you see a clear `ZiplineBindTimeoutException` after 30s instead of a frozen build. |
| `bindServices` hangs forever, no log | **U2** — Zipline Gradle plugin missing on this module | Add `alias(libs.plugins.zipline)` to `plugins {}`. `bindWithTimeout` catches this too — message names both U1 and U2 as candidates. |
| First guest call fails with `Serializer for class 'X' is not found` | **U3** — kotlinx-serialization plugin missing on the module declaring `X` | Add `alias(libs.plugins.kotlinSerialization)` to that module. Or call `requireSerializerOf<X>()` at the top of `bindServices` to catch the same failure at bind time instead. |
| `AsyncImage` with HTTP URL renders blank, no exception | **U5** — Coil 3 default ImageLoader has no network fetcher | Call `setSingletonImageLoaderFactory { … }` at host startup with a platform-appropriate fetcher. See [§"⚠️ If you use AsyncImage" below](#-if-you-use-asyncimage-register-a-coil-3-imageloader-yourself). |
| Host service callback runs (log fires) but the UI doesn't react | **U8** — host service body runs on the Zipline dispatcher, not the UI thread | Take `uiDispatcher = treehouseApp.dispatchers.ui` as a constructor param; `scope.launch(uiDispatcher) { … }` around `NavController.navigate` / Compose state mutations. |

Production-readiness note: U1, U2, U3 ship with mitigations as of
Konduit `1.0.0-caliclan.3` (the `Spec.bindWithTimeout` /
`Spec.requireSerializerOf` helpers). U5 is still doc-level. U8 is
already actionable via `dispatchers.ui`.

---

## Architecture, in 30 seconds

```
┌─────────────────┐    Zipline RPC over QuickJS    ┌─────────────────┐
│  Your host app  │ ◄────────────────────────────► │  Guest .zipline │
│  (Android/iOS)  │                                 │  (compiled JS)   │
│                 │                                 │                  │
│ TreehouseApp +  │     widget-tag protocol         │  Tier1ShowcaseS- │
│ M3 widgets +    │     (kotlinx-serialization)     │  creen, your     │
│ HostX services  │                                 │  screens, etc.   │
└─────────────────┘                                 └─────────────────┘
```

- **Host** holds the schema-host protocol, the M3 widget implementations, and
  any "HostX" services (HostConsole, HostSnackbar, anything you bind via
  `zipline.bind`).
- **Guest** is a Kotlin/JS module that emits a `.zipline` bundle. The guest
  uses generated `schema.compose.*` widgets which look exactly like normal
  Compose widgets but emit protocol messages instead of rendering directly.
- **Dev server** (in this repo's `dev-server/` module) serves the latest
  `.zipline` bundle over HTTP + hot-reload over WebSocket.

The host never imports guest code; the guest never imports host code. They
communicate through the schema (in `schema/`) and the generated protocol
modules.

---

## Step 1 — Vendor Konduit

Add it as a git submodule:

```bash
git submodule add https://github.com/waliasanchit007/ServerDrivenUI third_party/konduit
git submodule update --init --recursive
```

In your top-level `settings.gradle.kts`, include each module with its
`projectDir` pointing inside the submodule. (`includeBuild` does not work
here — the Konduit modules don't apply `maven-publish`, so there are no
Maven coordinates to substitute.)

```kotlin
include(":shared")
project(":shared").projectDir = file("third_party/konduit/shared")

include(":shared-widget")
project(":shared-widget").projectDir = file("third_party/konduit/shared-widget")

include(":shared-modifier")
project(":shared-modifier").projectDir = file("third_party/konduit/shared-modifier")

include(":shared-protocol-host")
project(":shared-protocol-host").projectDir = file("third_party/konduit/shared-protocol-host")

include(":shared-protocol-guest")
project(":shared-protocol-guest").projectDir = file("third_party/konduit/shared-protocol-guest")

include(":schema")
project(":schema").projectDir = file("third_party/konduit/schema")

include(":schema-types")
project(":schema-types").projectDir = file("third_party/konduit/schema-types")

include(":presenter")
project(":presenter").projectDir = file("third_party/konduit/presenter")
```

You will also need to copy the `pluginManagement {}` and
`dependencyResolutionManagement {}` blocks from
`third_party/konduit/settings.gradle.kts` (or merge them with yours) — they
declare the Konduit Maven repo and the Compose dev repo that the modules
expect.

### Modules — what to include

**Host side** (always):

| Module                    | Why                                                            |
|---------------------------|----------------------------------------------------------------|
| `shared`                  | `HostConsole`, `HostSnackbar`, `SduiAppService`, `RealHostSnackbar` |
| `shared-widget`           | M3 widget implementations (Box, Column, Button, ...)           |
| `shared-modifier`         | Generated modifier interfaces (transitive — needed at link time)|
| `shared-protocol-host`    | `SduiSchemaHostProtocol.Factory` for TreehouseApp              |
| `schema`                  | Schema sources + `SduiSerializersModule`                       |
| `schema-types`            | Color/typography enums (KMP) used inside `@Modifier` properties |

**Guest side** (always):

| Module                    | Why                                                            |
|---------------------------|----------------------------------------------------------------|
| `presenter`               | The guest Kotlin/JS module — copy wholesale and rename, or model your own on it (see Step 4)|
| `shared-protocol-guest`   | Guest-side protocol counterpart                                |

**Optional**:

| Module                    | Why                                                            |
|---------------------------|----------------------------------------------------------------|
| `composeApp`              | The reference KMP host module — use as-is to skip writing your own iOS framework target, or read it as a template |
| `dev-server`              | Local hot-reload dev server (Ktor + WebSocket)                 |

### gradle.properties — merge these into yours

Konduit's `gradle.properties` has a few keys you MUST merge into the
consumer project, or builds will fail in confusing ways:

```properties
# Workaround for Compose Multiplatform linker issue CMP-8845 — without
# this, iOS link fails. Mandatory.
kotlin.native.cacheKind=none

# Konduit's presenter relies on this being off; turning it on breaks
# JS codegen.
kotlin.incremental.js.ir=false

# Recommended for headroom — Konduit codegen is memory-heavy.
org.gradle.jvmargs=-Xmx4096M -Dfile.encoding=UTF-8

# Android — required by AGP namespace + Compose Compiler integration.
android.nonTransitiveRClass=true
android.useAndroidX=true
```

### GitHub Packages auth (this is the #1 first-build failure)

The host modules pull `dev.konduit:konduit-*` artifacts from GitHub
Packages in the private `waliasanchit007/konduit` repo. The submodule's
`settings.gradle.kts` reads credentials from these four sources, in this
order:

1. Gradle property `gpr.user`  (local — `~/.gradle/gradle.properties`)
2. Env var `GITHUB_ACTOR`      (CI fallback)
3. Gradle property `gpr.token` (local)
4. Env var `GITHUB_TOKEN`      (CI fallback)

You need a **classic** GitHub PAT (fine-grained PATs do NOT work — GitHub
Packages Maven only accepts classic) with `read:packages` scope.

**Local setup (CLI builds)** — put this in `~/.gradle/gradle.properties`
(NOT your project's `gradle.properties`, and NOT named
`KONDUIT_READ_TOKEN`):
```
gpr.user=your-github-username
gpr.token=ghp_your_classic_pat_here
```
Then `chmod 600 ~/.gradle/gradle.properties` so the token isn't world-
readable.

**Local setup (Android Studio only)** — AS lets you set env vars per-run
via Run > Edit Configurations > Environment variables. Setting
`GITHUB_ACTOR` + `GITHUB_TOKEN` there works for IDE-driven builds, but
the values won't be visible to `./gradlew` invocations from your
Terminal (or from CI, or from this doc's reader who's likely using
CLI builds). For headless integration testing, you still need the
`~/.gradle/gradle.properties` entries above.

**CI** — `KONDUIT_READ_TOKEN` is just the secret name we happen to use in
GitHub Actions; the workflow exports it as `GITHUB_TOKEN`. From
`.github/workflows/ci.yml`:
```yaml
env:
  GITHUB_ACTOR: ${{ github.actor }}
  GITHUB_TOKEN: ${{ secrets.KONDUIT_READ_TOKEN }}
```

**Symptom of missing token**: Gradle's dependency resolution will 401
against `maven.pkg.github.com` with a stack trace that buries the auth
failure several frames deep. If your first `./gradlew help` shows
`Received status code 401 from server: Unauthorized`, this is the cause.

### Mandatory: restrict the Konduit Maven repo to dev.konduit

When you declare the Konduit Maven repo in `dependencyResolutionManagement`,
add a `content {}` filter so Gradle only queries it for `dev.konduit.*`
artifacts. Without this, Gradle will hit GH Packages for *every*
transitive dep (androidx, kotlin, kotlinx, etc.) — and GH Packages
responds slowly enough to non-existent paths that the build hangs for
minutes before falling through to google()/mavenCentral():

```kotlin
maven {
    url = uri("https://maven.pkg.github.com/waliasanchit007/konduit")
    credentials {
        username = (providers.gradleProperty("gpr.user").orNull
            ?: System.getenv("GITHUB_ACTOR")).orEmpty()
        password = (providers.gradleProperty("gpr.token").orNull
            ?: System.getenv("GITHUB_TOKEN")).orEmpty()
    }
    content {                                       // ← required
        includeGroup("dev.konduit")
        includeGroupByRegex("dev\\.konduit\\..*")
    }
}
```

Konduit's own builds don't hit this because their Gradle caches already
have every androidx/kotlin coordinate they'll ever ask for. A fresh
integrator project doesn't have those caches and will see 10+ minute
build hangs that look like network errors. Add the filter from day one.

---

## Step 2 — Host boilerplate (Android)

The reference `MainActivity` is `androidApp/src/main/kotlin/com/example/serverdrivenui/MainActivity.kt`
(308 lines, includes hot-reload + dev controller wiring). Stripped to
essentials, the minimum looks like this:

```kotlin
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.remember
import app.cash.zipline.Zipline
import app.cash.zipline.loader.LoaderEventListener
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.asZiplineHttpClient
import com.example.serverdrivenui.TreehouseHelper                      // Java helper (Android factory)
import com.example.serverdrivenui.schema.SduiSerializersModule
import com.example.serverdrivenui.schema.protocol.host.SduiSchemaHostProtocol
import com.example.serverdrivenui.schema.widget.SduiSchemaWidgetSystem
import com.example.serverdrivenui.shared.HostConsole
import com.example.serverdrivenui.shared.HostSnackbar
import com.example.serverdrivenui.shared.RealHostSnackbar
import com.example.serverdrivenui.shared.SduiAppService
import dev.konduit.treehouse.TreehouseApp
import dev.konduit.treehouse.composeui.TreehouseContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    private val manifestUrlFlow = MutableStateFlow("https://your-cdn/manifest.zipline.json")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android uses the Android-specific TreehouseAppFactory overload
        // (it needs a Context for cache storage). The repo wraps that in a
        // small Java helper because the Kotlin extension is awkward to call
        // from Kotlin (KT-50800-ish). Either copy `TreehouseHelper.java`
        // from this repo into your app, or call `TreehouseAppFactoryAndroidKt
        // .TreehouseAppFactory(...)` directly with `applicationContext`.
        // The underlying TreehouseAppFactory's loaderEventListener
        // parameter is non-null at the Kotlin layer — passing null
        // crashes at first use with `Parameter specified as non-null
        // is null`. Use a no-op base instance, or override methods like
        // cacheStorageFailed for production observability.
        val loaderListener = object : LoaderEventListener() {}

        val factory = TreehouseHelper.createTreehouseAppFactory(
            /* context = */ applicationContext,
            /* httpClient = */ OkHttpClient().asZiplineHttpClient(),
            /* manifestVerifier = */ ManifestVerifier.NO_SIGNATURE_CHECKS,
            /* loaderEventListener = */ loaderListener,
            /* hostProtocolFactory = */ SduiSchemaHostProtocol.Factory,
        )

        val spec = object : TreehouseApp.Spec<SduiAppService>() {
            override val name = "myapp"
            override val manifestUrl = manifestUrlFlow.asStateFlow()
            override val serializersModule = SduiSerializersModule

            // Hold strong refs to bound host services so Zipline's
            // GC-monitored proxy doesn't lose them. See HANDOVER gotcha #6.
            //
            // `hostSnackbar` is `lateinit var` because its constructor
            // needs `treehouseApp.dispatchers.zipline`, which only exists
            // once the factory has created the TreehouseApp. Populating
            // it inside bindServices guarantees correct wiring before any
            // guest call can fire. (See HANDOVER gotcha #12.)
            //
            // `AndroidRealHostConsole` is NOT exported from a Konduit
            // module — copy the 5-line class from this repo's
            // `MainActivity.kt` or write your own `: HostConsole` that
            // routes to your logger.
            private val hostConsole = AndroidRealHostConsole()
            private lateinit var hostSnackbar: RealHostSnackbar

            override suspend fun bindServices(
                treehouseApp: TreehouseApp<SduiAppService>,
                zipline: Zipline,
            ) {
                zipline.bind<HostConsole>("console", hostConsole)
                hostSnackbar = RealHostSnackbar(
                    ziplineDispatcher = treehouseApp.dispatchers.zipline,
                )
                zipline.bind<HostSnackbar>("snackbar", hostSnackbar)
            }

            override fun create(zipline: Zipline) = zipline.take<SduiAppService>("app")
        }

        val treehouseApp = factory.create(appScope = lifecycleScope, spec = spec)

        setContent {
            MaterialTheme {
                val widgetSystem = remember { SduiSchemaWidgetSystem(CmpWidgetFactory) }
                val contentSource = remember { SduiContentSource() }
                TreehouseContent(
                    treehouseApp = treehouseApp,
                    widgetSystem = widgetSystem,
                    contentSource = contentSource,
                )
            }
        }
    }
}
```

For the full reference (hot reload over WebSocket, retry callbacks, dev
controller, curated event listener) see `androidApp/.../MainActivity.kt`
in this repo. `CmpWidgetFactory` and `SduiContentSource` come from
`composeApp/src/commonMain/.../App.kt`.

### ⚠️ Remember stability — DO NOT skip this section

The single biggest silent footgun in Konduit integration: holding a
`TreehouseApp` in a `remember(...)` whose key list contains an
**unstable reference**. This was DevoStatus's `docs/KNOWN_BUGS.md` #9
— a day of debugging — and it's still the easiest mistake to make.

**The pattern that breaks.** Anonymous lambdas at the call site get a
NEW identity on every recomposition:

```kotlin
@Composable
fun MyScreen(
    onItemTap: (String) -> Unit,       // anonymous lambda from caller → unstable
    quotesFlow: Flow<List<Quote>>,     // .map { ... } inline at caller → unstable
) {
    val app = remember(activity, onItemTap, quotesFlow) {   // ← KEY INSTABILITY
        createTreehouseApp(...)
    }
    // … TreehouseContent(app, …)
}
```

Every recomposition above this composable creates a new `onItemTap` /
`quotesFlow` identity, `remember` invalidates, the factory block runs
again, a brand-new `TreehouseApp` is built. The OLD one is still
alive (nobody closed it) and the two race for the Zipline runtime.
Symptom: the second `TreehouseApp.Spec.bindServices` silently no-ops
its later binds (`console` + `snackbar` are visible to the guest, but
your domain services like `HostQuotesProvider` are not), and the
guest's `take<>` proxies later error with `no such service (service
closed?)`. **Nothing in any log says "your remember key was
unstable" — that's why this gotcha is invisible.**

**The fix.** Wrap unstable lambdas in `rememberUpdatedState`, then
pass adapter lambdas that delegate via the always-current State:

```kotlin
@Composable
fun MyScreen(
    onItemTap: (String) -> Unit,
    quotesFlow: Flow<List<Quote>>,
) {
    val currentOnItemTap by rememberUpdatedState(onItemTap)

    val app = rememberKonduitApp(activity, quotesFlow) {     // ← STABLE KEYS ONLY
        createTreehouseApp(
            onItemTap = { id -> currentOnItemTap(id) },      // adapter, stable identity
            quotesFlow = quotesFlow,
        )
    }
    // … TreehouseContent(app, …)
}
```

[`rememberKonduitApp`][rkapp] (in `:composeApp` `commonMain`,
`com.example.serverdrivenui.shared.RememberKonduitApp.kt`) is a tiny
helper that wraps `remember(...)` with one extra behavior: if the
factory block runs more than once for the same call site, it logs a
LOUD warning the second time. So if you accidentally pass an unstable
key, you'll see it in `logcat` / Xcode console immediately rather than
debug the gotcha #9 ghost. Substitute your own `remember(...)` if you
prefer — the contract is identical, just lose the warning.

Quick checklist when you write a `rememberKonduitApp(…)`:

  - ✅ Activity / ViewController reference: stable
  - ✅ Stable `Flow` from the caller's own `remember { … }`: stable
  - ✅ ViewModel instance: stable for the screen's lifetime
  - ❌ Anonymous lambdas (`{ x -> … }` inline at the call site): UNSTABLE
  - ❌ Inline `.map { … }` on a Flow: UNSTABLE
  - ❌ New `data class` instances built inside the Composable body: UNSTABLE
  - ❌ `mutableStateOf(…)` values: stable identity but if you key on
        the `.value`, that's unstable

When in doubt, wrap with `rememberUpdatedState` and pass an adapter.
The cost is one extra State allocation per recomposition — negligible.

[rkapp]: ../composeApp/src/commonMain/kotlin/com/example/serverdrivenui/shared/RememberKonduitApp.kt

### Step 2 — Dependencies your module needs

### ⚠️ MANDATORY: apply the Zipline Gradle plugin to your host module

Any module that calls `zipline.bind<T>(...)` or `zipline.take<T>(...)`
**must** apply the Zipline Kotlin compiler plugin. Zipline uses a
compiler plugin to rewrite those calls at compile time so they use
generated serializers. **Without the plugin:**

- `zipline.bind<T>(...)` **silently hangs forever** — no error, no
  exception, no log line. Your host's `bindServices` enters but never
  returns. The screen stays blank and you'll be debugging for hours.
- `zipline.take<T>(...)` throws `IllegalStateException: unexpected
  call to Zipline.take: is the Zipline plugin configured?`

This is the most painful integration footgun in Konduit. Apply the
plugin in your host module's `build.gradle.kts`:

```kotlin
plugins {
    // ... your other plugins ...
    alias(libs.plugins.zipline)   // ← REQUIRED
}
```

`:composeApp`, `:presenter`, and Konduit's own `androidApp` all apply
it. New host modules don't get it by default — you have to remember.

The reason Konduit's own `androidApp/build.gradle.kts` is so short is
that it calls `App(treehouseApp = ...)` from `:composeApp` — and
`:composeApp` hides several deps as `implementation`. If you skip that
wrapper and call `TreehouseContent` directly (as Step 2 above does),
you need to declare those deps yourself. Minimum for the snippet above
to compile:

```kotlin
dependencies {
    // Konduit framework — single facade artifact pulls in all the
    // host-side runtime (TreehouseApp, Spec, dispatchers,
    // TreehouseContent, widget interfaces, the modifier base, the
    // protocol modules, plus Zipline + zipline-loader).
    implementation(libs.konduit.host)

    // ServerDrivenUI reference modules from the submodule — these are
    // application-specific (your schema, your HostConsole / HostSnackbar
    // impls). External adopters writing their own schema replace these
    // with their own equivalents.
    implementation(project(":composeApp"))             // M3 widget facade + helpers
    implementation(project(":shared"))                 // HostConsole, HostSnackbar, RealHostSnackbar
    implementation(project(":shared-protocol-host"))   // SduiSchemaHostProtocol.Factory

    // Android
    implementation(libs.androidx.activity.compose)
    implementation(libs.okhttp)

    // Compose
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
}
```

The `libs.konduit.host` reference assumes a version catalog entry:

```toml
# gradle/libs.versions.toml
[versions]
konduit = "1.0.0-caliclan.4"

[libraries]
konduit-host  = { module = "dev.konduit:konduit-host",  version.ref = "konduit" }
konduit-guest = { module = "dev.konduit:konduit-guest", version.ref = "konduit" }
```

Migration from the pre-facade setup: replace
`libs.redwood.treehouse.host`, `libs.redwood.treehouse.host.composeui`,
`libs.redwood.compose`, `libs.redwood.widget`, `libs.redwood.treehouse`,
`libs.redwood.protocol.host`, `libs.redwood.protocol`,
`libs.zipline.loader`, and `libs.zipline` (host side) with the single
`libs.konduit.host` line. The pre-facade `libs.redwood.*` catalog
entries still work — the facade is additive.
```

### ⚠️ If you use AsyncImage: register a Coil 3 ImageLoader yourself

Konduit's `AsyncImage` widget is backed by **Coil 3**. Coil 3's
default singleton ImageLoader has **no network fetcher** — `AsyncImage`
with an HTTP URL silently fails (no exception, just an empty image
slot). Konduit's own `App()` composable registers one via
`setSingletonImageLoaderFactory`. If you skip `App()` and call
`TreehouseContent` directly, you must do this yourself:

```kotlin
@Composable
fun YourKonduitScreen(...) {
    setSingletonImageLoaderFactory { ctx ->
        ImageLoader.Builder(ctx)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
    }
    // ... then TreehouseContent(...)
}
```

#### Pick the right Coil network fetcher for your app

Konduit's `App()` uses `coil-network-ktor2`. **That breaks if your host
app pulls in Ktor 3** (e.g. via Supabase 3.x, modern Ktor server, etc.)
because Coil's ktor2 fetcher imports Ktor 2 classes that aren't on the
classpath when Ktor 3 wins resolution:

```
java.lang.NoClassDefFoundError: Failed resolution of:
Lio/ktor/utils/io/jvm/nio/WritingKt;
```

For a host with Ktor 3, register `coil-network-okhttp` instead (no Ktor
dependency at all):

```kotlin
dependencies {
    implementation(libs.coil.compose)
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    // NOT coil-network-ktor2 in a Ktor-3 host
}
```

…and use `OkHttpNetworkFetcherFactory()` in the ImageLoader builder
shown above. OkHttp is already on the classpath via Zipline's loader.

### Step 2b — Host boilerplate (iOS)

iOS has more surface area: you provide your own `ZiplineHttpClient`
backed by `NSURLSession`, you wire `embedAndSignAppleFrameworkForXcode`
as a Run Script build phase, and you add `NSAppTransportSecurity`
exceptions for dev-server URLs.

The reference is `composeApp/src/iosMain/.../MainViewController.kt`
(343 lines). Copy it wholesale and rename, or model your own on it —
the `TreehouseApp.Spec` shape is identical to Android's, with these
iOS-only differences:

- **HTTP client**: `IosZiplineHttpClient(NSURLSession.sharedSession)`,
  defined in the same file. There is no published Konduit-provided iOS
  client; you own this class.
- **HostConsole impl**: `IosRealHostConsole` (also in
  `MainViewController.kt` — `println("JS: $message")`). Copy or rewrite
  to route to your logger.
- **`TreehouseAppFactory` constructor**: iOS uses the pure-Kotlin
  constructor directly (no Context). Requires
  `@OptIn(dev.konduit.leaks.RedwoodLeakApi::class)` at file or call
  site.

### iOS framework wiring (Xcode side)

This trips up most first integrations:

1. **Framework `baseName`** is `"ComposeApp"` (set in
   `composeApp/build.gradle.kts`). In Swift you `import ComposeApp` and
   call `MainViewControllerKt.MainViewController()`. If you rename the
   `composeApp` module, update both sides.
2. **Link SQLite** — Zipline's bundle cache uses SQLite. Add
   `linkerOpts("-lsqlite3")` inside the `binaries.framework { … }` block
   for each iOS target. Missing this gives an obscure linker error from
   Zipline's loader.
3. **`embedAndSignAppleFrameworkForXcode` build phase** — your Xcode
   target needs a Run Script build phase that invokes
   `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`. See
   `iosApp/iosApp.xcodeproj/project.pbxproj` in this repo for the exact
   script body.
4. **`Info.plist` ATS exceptions** for the dev server. The repo's
   `iosApp/iosApp/Info.plist` has:
   - `NSAllowsLocalNetworking = true`
   - `NSExceptionDomains` for `127.0.0.1` and `localhost`
     (`NSExceptionAllowsInsecureHTTPLoads = true`,
     `NSIncludesSubdomains = true`)
   - `NSLocalNetworkUsageDescription` text (required by iOS 14+)
   Without these, the iOS sim 401/blocks plaintext HTTP to the dev server.

---

## Step 3 — Wire the SnackbarHost (host side)

If you bind `HostSnackbar`, you also need to anchor a `SnackbarHost`
somewhere in your Compose tree to render the actual snackbars. The
`RealHostSnackbar` writes into a singleton `SnackbarHub.state`; anchor
once at the root:

```kotlin
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.remember
import com.example.serverdrivenui.schema.widget.SduiSchemaWidgetSystem
import com.example.serverdrivenui.shared.CmpWidgetFactory
import com.example.serverdrivenui.shared.SduiContentSource
import com.example.serverdrivenui.shared.SnackbarHub
import dev.konduit.treehouse.composeui.TreehouseContent

@Composable
fun App(treehouseApp: TreehouseApp<SduiAppService>) {
    Box(modifier = Modifier.fillMaxSize()) {
        val widgetSystem = remember { SduiSchemaWidgetSystem(CmpWidgetFactory) }
        val contentSource = remember { SduiContentSource() }
        TreehouseContent(
            treehouseApp = treehouseApp,
            widgetSystem = widgetSystem,
            contentSource = contentSource,
            modifier = Modifier.fillMaxSize(),
        )
        SnackbarHost(
            hostState = SnackbarHub.state,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
```

Notes on stability:
- `widgetSystem` and `contentSource` MUST be `remember`-ed. If they're
  reconstructed each recomposition, `TreehouseContent` tears down the
  guest session and reconnects on every frame.
- `SduiContentSource` is a class (not an object). The `remember { ... }`
  block is doing real work, not just deduplication.

---

## Step 4 — Define a guest screen

The guest side is a Kotlin/JS module that compiles to a `.zipline`
bundle. Konduit's `:presenter` is the canonical reference; the minimum
for a new integrator is a 3-file scaffold:

### 4a. The module's build.gradle.kts

Copy `third_party/konduit/presenter/build.gradle.kts` essentially
verbatim and just rename the package paths:

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.zipline)
    alias(libs.plugins.redwood.generator.compose)
    alias(libs.plugins.composeMultiplatform)
}

redwoodSchema {
    source = project(":schema")
    type = "com.example.serverdrivenui.schema.SduiSchema"
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            // Konduit framework — single facade pulls in compose
            // runtime, widget interfaces, treehouse-guest /
            // -guest-compose, protocol-guest, and Zipline.
            implementation(libs.konduit.guest)

            // ServerDrivenUI reference modules — replace with your
            // own schema definitions when adopting Konduit standalone.
            implementation(project(":shared"))
        }
        val jsMain by getting {
            dependencies {
                implementation(project(":shared-protocol-guest"))
                implementation(project(":shared-widget"))
            }
        }
    }
}
```

Migration from the pre-facade setup: the five guest-side
`libs.redwood.*` + `libs.zipline` entries collapse into the single
`libs.konduit.guest` line. The pre-facade `libs.redwood.*` catalog
entries still work — the facade is additive.

The `redwood.generator.compose` plugin + `redwoodSchema { source =
project(":schema") }` are load-bearing — they generate the
`com.example.serverdrivenui.schema.compose.Column` / `Text` / `Button`
etc. composables from the schema. Without those, guest screen code
won't resolve.

### 4b. main.kt — bind the SduiAppService

```kotlin
package yourapp.guest

import androidx.compose.runtime.Composable
import app.cash.zipline.Zipline
import com.example.serverdrivenui.schema.SduiSerializersModule
import com.example.serverdrivenui.schema.protocol.guest.SduiSchemaProtocolWidgetSystemFactory
import com.example.serverdrivenui.shared.SduiAppService
import dev.konduit.treehouse.StandardAppLifecycle
import dev.konduit.treehouse.TreehouseUi
import dev.konduit.treehouse.ZiplineTreehouseUi
import dev.konduit.treehouse.asZiplineTreehouseUi
import kotlinx.serialization.json.Json

class YourAppService : SduiAppService {
    override val appLifecycle = StandardAppLifecycle(
        protocolWidgetSystemFactory = SduiSchemaProtocolWidgetSystemFactory,
        json = Json { serializersModule = SduiSerializersModule },
        widgetVersion = 1U,
    )

    override fun launch(): ZiplineTreehouseUi {
        val ui = object : TreehouseUi {
            @Composable override fun Show() { MyScreen() }
        }
        return ui.asZiplineTreehouseUi(appLifecycle)
    }

    override fun close() {}
}

fun main() {
    Zipline.get().bind<SduiAppService>("app", YourAppService())
}
```

### 4c. MyScreen.kt

```kotlin
import com.example.serverdrivenui.schema.SchemaArrangement
import com.example.serverdrivenui.schema.SchemaColor
import com.example.serverdrivenui.schema.SchemaHorizontalAlignment
import com.example.serverdrivenui.schema.SchemaTextStyle
import com.example.serverdrivenui.schema.compose.Column
import com.example.serverdrivenui.schema.compose.Text

@Composable
fun MyScreen() {
    Column(
        verticalArrangement = SchemaArrangement.Start,
        horizontalAlignment = SchemaHorizontalAlignment.Start,
    ) {
        Text(
            text = "Hello from the server",
            style = SchemaTextStyle.HeadlineMedium,
            color = SchemaColor.Primary,
        )
    }
}
```

### Build it

```bash
./gradlew :yourGuestModule:compileDevelopmentExecutableKotlinJsZipline
# → build/zipline/Development/manifest.zipline.json + yourGuestModule.zipline
```

Point your dev-server at that directory and your host's `manifestUrl`
at the dev-server, and the guest renders.

### Gotcha — schema composables have NO default values

`@Property` fields on schema widgets are required at the call site —
the codegen emits constructor parameters without defaults. So:

- `Column(...)` requires `verticalArrangement` AND `horizontalAlignment`.
- `Text(...)` requires `text`, `style`, AND `color`.
- `Button(...)` requires `text`, `onClick`, AND whatever else the
  schema declares.

This is the opposite of stock M3 Compose, where most props have
sensible defaults. Don't be misled by IDE auto-completion suggesting
"shorter" signatures — those are the schema's underlying class
properties, not the codegen'd call. Hit `Ctrl-P` on the call site to
see what's actually required.

Caught empirically with `e: HelloScreen.kt: No value passed for
parameter 'verticalArrangement'`. The fix is just to fill in every
property — there's no workaround inside the schema today.

### Guest-side helpers (showHostSnackbar, navigation)

The fancier guest-side ergonomics — host snackbar bridge, navigator,
console proxy — live in `third_party/konduit/presenter/src/jsMain/`
(`Main.kt` + `Navigator.kt`). They're not part of any library;
copy-paste whichever helpers you need into your guest module.

### ViewModel-like patterns in the guest

Konduit ships a `KonduitViewModel` base class + `konduitViewModel { ... }`
Compose entry point that mirror native Android's `ViewModel` API for the
ergonomics that port across to the QuickJS guest.

Re-exported through `dev.konduit:konduit-guest`, so adopters on the facade
get it transparently. What you get:

- **`viewModelScope`** — a `CoroutineScope` defaulting to
  `Dispatchers.Main` (= Zipline dispatcher in the guest). Cancels when the
  hosting `@Composable` leaves the tree.
- **`onCleared()`** — override for cleanup; always call `super.onCleared()`.
- **Same instance across recomposition** — guaranteed by `remember`.

What you don't get (and why): no configuration-change survival (guest
re-runs the whole bundle), no `NavBackStackEntry`-scoped lifetime (the
guest's `Navigator` is opaque today), no DI integration (Kotlin/JS;
adopters pass deps through the factory lambda).

```kotlin
import dev.konduit.vm.KonduitViewModel
import dev.konduit.vm.konduitViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class QuotesViewModel(
    private val quotes: HostQuotesProvider,
) : KonduitViewModel() {
    val state = MutableStateFlow<List<Quote>>(emptyList())
    init {
        viewModelScope.launch {
            state.value = quotes.getQuotes(filter = null)
        }
    }
}

@Composable
override fun Content(navigator: Navigator) {
    val vm = konduitViewModel {
        QuotesViewModel(HostQuotesProviderBridge.instance!!)
    }
    val quotes by vm.state.collectAsState()  // from kotlinx-coroutines-compose
    LazyColumn { quotes.forEach { LazyItem { QuoteCard(it) } } }
}
```

Cross-tab persistence (state surviving the tab being unmounted) is a
separate concern — see the heart-save pattern in
`docs/KNOWN_BUGS.md` and DevoStatus's `HostExploreSaver`.

---

## Step 4½ — Data services (Provider + Navigator + Observer)

If your guest screen needs **data from the host** (a quote feed, a list
of products, the user's saved items, etc.), you want the three-service
pattern. It's the recipe DevoStatus's Quotes tab uses today — wire it
once and you get filtering, tap navigation, and reactive updates from
the host's view model into the guest screen.

The contract:

| Service              | Direction         | When it runs                           |
|----------------------|-------------------|----------------------------------------|
| `HostXProvider`      | Guest → Host call | Guest calls `getX(filter)` synchronously |
| `HostXNavigator`     | Guest → Host call | Guest calls when the user taps an item |
| `HostXObserver`      | Host → Guest call | Host calls when its data changes       |

`HostQuotesProvider` / `HostQuoteNavigator` / `HostQuotesObserver` in
`shared/Protocol.kt` are the live reference. Copy the shape for your
own data.

### 4½.a — Define the services

```kotlin
// shared/src/commonMain/kotlin/.../Protocol.kt
@Serializable
data class Product(val id: String, val name: String, val priceMinor: Int)

interface HostProductsProvider : ZiplineService {
    // Non-suspend — Zipline 1.26 hangs bind<>() on suspend methods that
    // return @Serializable lists. See gotcha #14.
    fun getProducts(categoryFilter: String?): List<Product>
    fun observe(observer: HostProductsObserver)
}

interface HostProductSelectionNavigator : ZiplineService {
    fun onProductSelected(productId: String)
}

interface HostProductsObserver : ZiplineService {
    fun onProductsChanged()
}
```

### 4½.b — Implement the services on the host

```kotlin
// host module — Android example, mirror this in iosMain.
private class RealHostProductsProvider(
    private val source: (String?) -> List<Product>,
    private val flow: Flow<List<Product>>?,   // host's data flow
    private val scope: CoroutineScope,
) : HostProductsProvider {
    private var observer: HostProductsObserver? = null
    private var observerJob: Job? = null

    override fun getProducts(filter: String?) = source(filter)

    override fun observe(observer: HostProductsObserver) {
        // Replace previous observer + cancel its job.
        observerJob?.cancel()
        this.observer?.close()
        this.observer = observer
        val f = flow ?: return
        observerJob = scope.launch {
            // drop(1) — the first emission is the value the guest already
            // saw in its initial getProducts() call.
            f.drop(1).collect { observer.onQuotesChanged() }
        }
    }
}
```

Then bind in your `TreehouseApp.Spec.bindServices`:

```kotlin
override suspend fun bindServices(
    treehouseApp: TreehouseApp<MyAppService>,
    zipline: Zipline,
) {
    zipline.bind<HostConsole>("console", hostConsole)
    zipline.bind<HostSnackbar>("snackbar", hostSnackbar)
    zipline.bind<HostProductsProvider>("products", productsProvider)
    zipline.bind<HostProductSelectionNavigator>("product-nav", productNav)
    // No bind for HostProductsObserver — that one is implemented and
    // passed BY the guest. See 4½.c.
}
```

### 4½.c — Consume the services on the guest

```kotlin
// presenter/src/jsMain/kotlin/.../ProductsScreen.kt
class ProductsScreen : Screen {
    @Composable
    override fun Content(navigator: Navigator) {
        val provider = HostProductsProviderBridge.instance
        val productNav = HostProductSelectionNavigatorBridge.instance

        var filter by remember { mutableStateOf<String?>(null) }
        var products by remember { mutableStateOf<List<Product>?>(null) }
        var refreshTick by remember { mutableStateOf(0) }

        // Wire the observer once per provider — graceful fallback if the
        // host is older and didn't ship observe().
        LaunchedEffect(provider) {
            if (provider == null) return@LaunchedEffect
            val observer = object : HostProductsObserver {
                override fun onProductsChanged() { refreshTick++ }
            }
            try {
                provider.observe(observer)
            } catch (t: Throwable) {
                // Old host without observe() → snapshot-only mode.
            }
        }

        // Re-fetch whenever the filter changes OR the host signals.
        LaunchedEffect(filter, refreshTick, provider) {
            products = provider?.getProducts(filter)
        }

        // … render products …
    }
}
```

You'll also need a `HostProductsProviderBridge` object in the presenter
module — see `presenter/src/jsMain/.../Main.kt` for the
`HostQuotesProviderBridge` pattern.

### 4½.d — Make the provider/navigator drive routing (optional)

Have `presenter/RootUi` route to your screen automatically when its
provider is bound:

```kotlin
val firstScreen = when {
    HostProductsProviderBridge.instance != null -> ProductsScreen()
    HostQuotesProviderBridge.instance != null   -> QuotesScreen()
    else                                         -> Tier1ShowcaseScreen()
}
```

This way the same guest bundle can serve multiple screens depending on
which services the host binds. DevoStatus's `:konduit-host` uses this
trick — its Quotes tab and its Demo tab both ship the same bundle, just
with different `bindServices(...)` impls.

### Why three services, not one fat one?

- Provider, navigator, and observer have **different lifecycles**: the
  observer outlives any single getProducts() call, the navigator fires
  on tap, the provider's getProducts() is request/response. Splitting
  them keeps each interface small.
- Provider is **host-owned** (guest calls in), navigator is
  **host-owned** (guest calls in), observer is **guest-owned** (host
  calls in). Different ownership = different services. This is the same
  pattern as `HostSnackbar` + `SnackbarResultCallback`.
- Old guests can ignore observer entirely and still work — the host
  just won't get reactive notifications. Adding observer to an existing
  Provider as a new method is wire-additive (per the rationale on
  `HostSnackbar.showWithResult`).

---

## Step 5 — Dev loop

The dev server in this repo (`dev-server/`) serves the latest `.zipline`
bundle and pushes hot-reload notifications. To run:

```bash
./gradlew :dev-server:run
# or, for the full one-shot dev experience:
./gradlew konduitDev
```

Your host app's `manifestUrl` should point at the dev server:
- **Android emulator** → `http://10.0.2.2:8080/manifest.zipline.json`
  (the emulator's alias for the host's loopback).
- **Android physical device on USB** → `http://127.0.0.1:8080/...` after
  `adb reverse tcp:8080 tcp:8080` exposes the host port. (Or use the
  host's LAN IP if Wi-Fi.)
- **iOS sim** → `http://127.0.0.1:8080/...` directly — the simulator
  shares the host's loopback, no port-forwarding needed. (`adb reverse`
  is Android-only; do not look for an iOS equivalent.)
- **iOS physical device** → host's LAN IP, or an ngrok HTTPS tunnel.
  IP-literal HTTP URLs require the `NSExceptionDomains` ATS entry — see
  Step 2b.

On each guest source change, recompile:
```bash
./gradlew :presenter:compileDevelopmentExecutableKotlinJsZipline
```
…and the dev server pushes a hot-reload event over WebSocket. The host
re-fetches and swaps in the new bundle without restarting.

---

## Gotchas you should internalize before writing your first screen

These are pulled from `HANDOVER.md`'s gotcha list — read that doc for full
detail. The TL;DR:

1. **Add new enums to `SduiSerializersModule`** if you use them as a field
   on a `@Modifier`. Otherwise the whole protocol batch silently fails and
   you get a white screen on the host.
2. **No lambda-typed properties on `@Modifier` classes.** Click handlers
   live on widgets, never modifiers. The codegen will accept a lambda
   modifier property at compile time and silently fail at runtime on JS.
3. **Outbound calls to `ZiplineService` proxies MUST happen on
   `treehouseApp.dispatchers.zipline`.** This includes `callback.onResult`,
   `callback.close()`, `flow.value`. Wire the dispatcher into your HostX
   service constructor (see `RealHostSnackbar`'s ctor) and `withContext`
   around proxy touches. JVM tolerates the wrong thread by luck; iOS K/N
   crashes with `QuickJsException: stack overflow`.
4. **Hold strong refs to bound host services** as `lateinit var` properties
   of your `TreehouseApp.Spec`. Anonymous inline arguments to `zipline.bind`
   GC out from under Zipline and the next guest call errors with "no such
   service".
5. **Use the right manifest URL per platform.** Android emulator →
   `10.0.2.2`. iOS sim → `127.0.0.1` directly (no `adb reverse` — that's
   Android-only). Android USB device → `127.0.0.1` after
   `adb reverse tcp:8080 tcp:8080`. Physical iOS → LAN IP or ngrok HTTPS.
   iOS additionally needs `NSExceptionDomains` in `Info.plist` for
   IP-literal URLs.

The full list of 12 gotchas is in `HANDOVER.md` §"Gotchas / requirements
that aren't obvious". Read it before debugging anything mysterious.

---

## When something breaks

1. **Blank screen on host, no exception**: 95% of the time it's gotcha #10
   (an enum on a `@Modifier` not registered in `SduiSerializersModule`).
   Check the guest console (host → `HostConsole.log`) for SerializationException.
2. **"no such service (service closed?)"**: gotcha #11. You're binding
   inline; hold a strong ref in your Spec.
3. **`QuickJsException: stack overflow` on iOS**: gotcha #12. An outbound
   call to a ZiplineService proxy happened off the zipline dispatcher.
4. **App builds but `take<T>("foo")` returns a null bridge**: your guest
   uses a raw lambda-typed parameter on a ZiplineService method. Wrap it
   in a `ZiplineService` callback type (see `SnackbarResultCallback`).

For anything else, search `HANDOVER.md`'s gotchas list with the failure
mode keywords (the section was written to be greppable).

---

## What's NOT yet in this distribution

If you need any of these, expect to either help land them or work around
them:

- **No Compose Facade**: you import from 6+ modules. Tracked as Phase 2
  in [PUBLIC_LAUNCH_ROADMAP.md](../PUBLIC_LAUNCH_ROADMAP.md) — collapses
  the multi-module wiring into a single `dev.konduit:konduit` import.
- **No Maven Central publishing**: submodule only for now.
- **No production load testing**: only showcase-level traffic verified.
- **iOS verified only on sim**: physical iOS devices not yet tested
  end-to-end on this branch.
- **No StateFlow / Flow patterns demonstrated**: would need the same
  dispatcher pattern as gotcha #12. Pattern works but no example yet.
