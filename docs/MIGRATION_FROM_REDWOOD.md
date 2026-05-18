# Migrating from Cash App Redwood to Konduit

This guide is for teams already using upstream
[Cash App Redwood](https://github.com/cashapp/redwood) (any version up
to `0.18.0`, the fork point) and considering Konduit as the maintained
successor for the Compose Multiplatform subset.

The short version:

- **Wire format and runtime semantics are unchanged.** Konduit doesn't
  fork the protocol; bundles built against Redwood widgets render the
  same way through the Konduit runtime.
- **API surface is identical except for renames.** Every Redwood type,
  function, and Gradle plugin has a Konduit equivalent at the same
  signature.
- **The migration is mostly `sed`.** A scripted rename handles 90%+ of
  the codebase change. The rest is deliberate trims (Phase 1.5 removed
  the View / UIView / DOM toolkits — see "What's removed" below).

> **Status:** Konduit is currently a private GitHub Packages fork while
> the integration validation work (issues
> [#8 iOS](https://github.com/waliasanchit007/konduit/issues/8),
> [#9 sample app](https://github.com/waliasanchit007/konduit/issues/9))
> finishes. Maven Central publishing is tracked in Phase 5 of
> [`PUBLIC_LAUNCH_ROADMAP.md`](../PUBLIC_LAUNCH_ROADMAP.md). If you're
> evaluating now, read this guide first — then talk to the maintainer
> about Phase 5 timing if your adoption needs Maven Central access.

---

## Why migrate

Upstream Redwood is no longer being actively developed by Cash App. The
last upstream release is `0.18.0` (August 2025). Konduit picks up where
that left off:

1. **Continued maintenance.** The fork's `1.0.0-caliclan.N` line ships
   regular fixes against real-world integration breakage. See
   [`CHANGELOG.md`](../CHANGELOG.md) for the full history.
2. **Production-hardening for silent-failure shapes** — `Spec.retain`,
   `Spec.bindWithTimeout`, `Spec.requireSerializerOf`, and others that
   turn the most painful Zipline / Treehouse adoption gotchas into
   actionable exceptions at bind time. See
   [`docs/KNOWN_BUGS.md`](./KNOWN_BUGS.md) for the catalog (11 of 12
   documented gotchas have a Konduit-side mitigation as of
   `1.0.0-caliclan.3`).
3. **Compose-Multiplatform focus.** Phase 1.5 dropped 13 upstream
   modules that aren't relevant to CMP (Android Views, iOS UIView,
   Web DOM, the test-app harness). Module count went 60 → 47.
4. **Ergonomic shims** — `konduit-host` / `konduit-guest` facades
   collapse the 8-module adopter dep block into one line; `konduit-vm`
   gives you a guest-side ViewModel equivalent; `konduit-http` /
   `konduit-storage` are generic network and persistence services
   adopters wire once and use everywhere.

## What's identical

If your project is already running on Redwood `0.18.0`, the following
require **zero code changes** to keep working under Konduit:

- The protocol wire format. Bundles built against Redwood widgets ship
  unchanged.
- `TreehouseApp` lifecycle and `TreehouseApp.Spec` extension surface
  (`bindServices`, `manifestUrl`, `dispatchers`, `serializersModule`).
- The `ZiplineService` interface contract (you still implement an
  interface, bind with `zipline.bind<T>("name", impl)`, take with
  `zipline.take<T>("name")`).
- `Modifier.Element` / `LayoutModifier` semantics for widget layout.
- Compose-style widget authoring on the guest (`@Composable
  override fun Content(navigator: Navigator)`).
- All schema-codegen primitives (`@Widget`, `@Property`, `@Modifier`,
  `@Children`).

## What's renamed

Every rename is mechanical — no semantic change.

### Maven coordinates

| Upstream Redwood | Konduit |
|---|---|
| `app.cash.redwood:redwood-runtime` | `dev.konduit:konduit-runtime` |
| `app.cash.redwood:redwood-compose` | `dev.konduit:konduit-compose` |
| `app.cash.redwood:redwood-widget` | `dev.konduit:konduit-widget` |
| `app.cash.redwood:redwood-protocol` | `dev.konduit:konduit-protocol` |
| `app.cash.redwood:redwood-protocol-host` | `dev.konduit:konduit-protocol-host` |
| `app.cash.redwood:redwood-protocol-guest` | `dev.konduit:konduit-protocol-guest` |
| `app.cash.redwood:redwood-treehouse` | `dev.konduit:konduit-treehouse` |
| `app.cash.redwood:redwood-treehouse-host` | `dev.konduit:konduit-treehouse-host` |
| `app.cash.redwood:redwood-treehouse-host-composeui` | `dev.konduit:konduit-treehouse-host-composeui` |
| `app.cash.redwood:redwood-treehouse-guest` | `dev.konduit:konduit-treehouse-guest` |
| `app.cash.redwood:redwood-treehouse-guest-compose` | `dev.konduit:konduit-treehouse-guest-compose` |
| `app.cash.redwood:redwood-schema` | `dev.konduit:konduit-schema` |

For most adopters the entire upper block collapses into:

```kotlin
// host module
implementation("dev.konduit:konduit-host:1.0.0-caliclan.4")
// guest module
implementation("dev.konduit:konduit-guest:1.0.0-caliclan.4")
```

See [`docs/USAGE.md`](./USAGE.md) "API calls from the guest" and
"ViewModel-like patterns" for the facade adoption pattern.

### Gradle plugins

| Upstream Redwood | Konduit |
|---|---|
| `app.cash.redwood.schema` | `dev.konduit.schema` |
| `app.cash.redwood.generator.compose` | `dev.konduit.generator.compose` |
| `app.cash.redwood.generator.widget` | `dev.konduit.generator.widget` |
| `app.cash.redwood.generator.protocol.host` | `dev.konduit.generator.protocol.host` |
| `app.cash.redwood.generator.protocol.guest` | `dev.konduit.generator.protocol.guest` |
| `app.cash.redwood.generator.modifiers` | `dev.konduit.generator.modifiers` |

In a typical `build.gradle.kts`:

```kotlin
plugins {
    // before
    alias(libs.plugins.redwood.generator.compose)

    // after
    alias(libs.plugins.konduit.generator.compose)
}
```

### Kotlin packages

| Upstream Redwood prefix | Konduit prefix |
|---|---|
| `app.cash.redwood.*` | `dev.konduit.*` |
| `app.cash.redwood.compose.*` | `dev.konduit.compose.*` |
| `app.cash.redwood.widget.*` | `dev.konduit.widget.*` |
| `app.cash.redwood.protocol.*` | `dev.konduit.protocol.*` |
| `app.cash.redwood.treehouse.*` | `dev.konduit.treehouse.*` |

Public type names (`TreehouseApp`, `TreehouseApp.Spec`, `TreehouseContent`,
`Modifier`, `LayoutModifier`, `Widget`, `WidgetSystem`, `ZiplineService`,
etc.) are unchanged — only the package paths shifted.

## Scripted rename helper

For most codebases, the migration is two `sed` invocations across your
project tree:

```bash
# 1. Replace Maven coordinates + package paths
find . -type f \( \
  -name '*.kt' -o -name '*.kts' -o -name '*.gradle' -o \
  -name '*.gradle.kts' -o -name '*.toml' -o -name '*.properties' \
\) -not -path '*/build/*' -not -path '*/.gradle/*' \
| xargs sed -i '' \
    -e 's|app\.cash\.redwood|dev.konduit|g' \
    -e 's|redwood-runtime|konduit-runtime|g' \
    -e 's|redwood-compose|konduit-compose|g' \
    -e 's|redwood-widget|konduit-widget|g' \
    -e 's|redwood-protocol|konduit-protocol|g' \
    -e 's|redwood-protocol-host|konduit-protocol-host|g' \
    -e 's|redwood-protocol-guest|konduit-protocol-guest|g' \
    -e 's|redwood-treehouse|konduit-treehouse|g' \
    -e 's|redwood-treehouse-host|konduit-treehouse-host|g' \
    -e 's|redwood-treehouse-host-composeui|konduit-treehouse-host-composeui|g' \
    -e 's|redwood-treehouse-guest|konduit-treehouse-guest|g' \
    -e 's|redwood-treehouse-guest-compose|konduit-treehouse-guest-compose|g' \
    -e 's|redwood-schema|konduit-schema|g'

# 2. Replace Gradle plugin IDs (if you used the `app.cash.redwood.*` IDs)
find . -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.toml' \) \
  -not -path '*/build/*' \
| xargs sed -i '' \
    -e 's|app\.cash\.redwood\.schema|dev.konduit.schema|g' \
    -e 's|app\.cash\.redwood\.generator|dev.konduit.generator|g'
```

(GNU `sed` users: drop the `''` after `-i`.)

Then re-run your build. Most failures will be remaining string literals
or comments referencing the upstream names — search-and-replace those
by hand.

## What's removed

Phase 1.5 (`1.0.0-caliclan.2`) dropped 13 upstream modules that don't
apply to Compose Multiplatform adopters. If your project depended on
any of these, **stay on upstream Redwood** or fork them separately:

- `redwood-layout-view`, `redwood-layout-uiview`, `redwood-layout-dom`
- `redwood-lazylayout-view`, `redwood-lazylayout-uiview`,
  `redwood-lazylayout-dom`
- `redwood-ui-basic-view`, `redwood-ui-basic-uiview`,
  `redwood-ui-basic-dom`
- `redwood-widget-view-test`, `redwood-widget-uiview-test`
- `redwood-dom-testing`
- `redwood-leak-detector-zipline-test`
- Upstream `test-app/` integration tests
- Upstream `samples/` (the Counter / EmojiSearch demo apps)

Module count went from 60 → 47. CMP-only adopters lose nothing.

## What's added in Konduit

These have no upstream-Redwood equivalent — they exist only in the
fork. Adopt incrementally; none of them are mandatory.

### Production-hardening helpers (in `konduit-treehouse-host`)

- **`Spec.retain(service)`** — strong-ref pass-through that keeps a
  service reachable for the Spec's lifetime. Use when binding inline
  anonymous service implementations:
  `zipline.bind<HostFoo>("foo", retain(object : HostFoo { … }))`.
  Closes the silent "no such service (service closed?)" failure that
  bit every inline-anon binder in Redwood.
- **`Spec.bindWithTimeout { … }`** — wraps a `bind` / `take` call
  with a 30s default deadline. Throws `ZiplineBindTimeoutException`
  with a diagnostic message instead of hanging silently. Catches the
  KNOWN_BUGS U1 (`suspend fun X(): List<@Serializable T>` bind hang)
  and U2 (Zipline plugin missing on the module) failure modes.
- **`Spec.requireSerializerOf<T>()`** — bind-time pre-flight check
  for `KSerializer<T>` resolvability. Throws
  `MissingSerializerException` if the kotlinx-serialization plugin
  isn't applied to the module declaring `T`. Catches KNOWN_BUGS U3
  at bind time instead of at first-call time.

See [`docs/KNOWN_BUGS.md`](./KNOWN_BUGS.md) U1–U11 for the full silent-
failure catalog these helpers mitigate.

### Compose Facade (`konduit-host`, `konduit-guest`)

Single-import meta-modules that re-export every type a typical adopter
needs. Replace the 8-line dep block with one line per side.

### Guest-side helpers

- **`konduit-vm`** — `KonduitViewModel` base + `konduitViewModel { }`
  Compose entry point. Mirrors native Android `ViewModel` ergonomics
  for the parts that port to the QuickJS guest. See
  [`docs/USAGE.md`](./USAGE.md) "ViewModel-like patterns in the guest".
- **`konduit-http`** — `HostHttpProvider` ZiplineService + `KonduitHttp`
  typed wrapper. Adopters wire their Ktor / Retrofit / OkHttp client
  ONCE; guest screens get typed `get<T>` / `post<Req, Res>` / etc.
  without per-endpoint `HostXxxProvider` services. See "API calls from
  the guest".
- **`konduit-storage`** — `HostStorage` ZiplineService + `KonduitStorage`
  typed wrapper. Adopters wire any KV backend (`DataStore`,
  `NSUserDefaults`, file blob); guest persists any `@Serializable` type
  with `set<T>` / `get<T>`. See "Key/value persistence from the guest".

## Side-by-side examples

### Host module Spec — upstream Redwood

```kotlin
// app.cash.redwood imports
import app.cash.redwood.treehouse.TreehouseApp
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService

class QuotesAppSpec : TreehouseApp.Spec<SduiAppService>() {
    override val name = "quotes"
    override val manifestUrl = quotesManifestUrlFlow.asStateFlow()
    override val serializersModule = SduiSerializersModule

    override suspend fun bindServices(treehouseApp, zipline) {
        zipline.bind<HostConsole>("console", StatusCraftHostConsole())
        val quotesProvider = RealHostQuotesProvider(quotesSource, appScope)
        zipline.bind<HostQuotesProvider>("quotes", quotesProvider)
    }

    override fun create(zipline: Zipline) = zipline.take<SduiAppService>("app")
}
```

### Same Spec — Konduit

```kotlin
// dev.konduit imports — package paths renamed, types and shapes identical
import dev.konduit.treehouse.TreehouseApp
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService

class QuotesAppSpec : TreehouseApp.Spec<SduiAppService>() {
    override val name = "quotes"
    override val manifestUrl = quotesManifestUrlFlow.asStateFlow()
    override val serializersModule = SduiSerializersModule

    override suspend fun bindServices(treehouseApp, zipline) {
        // bindWithTimeout: new in Konduit — turns Zipline's silent
        // bind hangs into actionable timeouts. Safe to wrap every bind.
        bindWithTimeout {
            zipline.bind<HostConsole>("console", retain(StatusCraftHostConsole()))
        }

        // requireSerializerOf: bind-time pre-flight for serializer resolution.
        requireSerializerOf<Quote>()

        val quotesProvider = RealHostQuotesProvider(quotesSource, appScope)
        bindWithTimeout {
            zipline.bind<HostQuotesProvider>("quotes", quotesProvider)
        }
    }

    override fun create(zipline: Zipline) = zipline.take<SduiAppService>("app")
}
```

The `bindWithTimeout`, `retain`, and `requireSerializerOf` helpers are
optional — your Redwood code keeps working without them. They're the
"opt in for clearer diagnostics" addition.

### Guest screen — unchanged

The guest-side authoring surface is identical:

```kotlin
// Same code on Redwood and Konduit — package import differs.
class QuotesScreen : Screen {
    @Composable override fun Content(navigator: Navigator) {
        val provider = HostQuotesProviderBridge.instance
        var quotes by remember { mutableStateOf<List<Quote>?>(null) }

        LaunchedEffect(provider) {
            quotes = provider?.getQuotes(filter = null) ?: emptyList()
        }

        Column {
            Text("Quotes")
            LazyColumn {
                quotes.orEmpty().forEach { LazyItem { QuoteCard(it) } }
            }
        }
    }
}
```

Upgrading the guest screen to use Konduit's new shims is optional:

```kotlin
class QuotesScreen : Screen {
    @Composable override fun Content(navigator: Navigator) {
        // konduit-vm + konduit-http
        val http = remember { KonduitHttp(HostHttpProviderBridge.instance!!) }
        val vm = konduitViewModel { QuotesViewModel(http) }
        val quotes by vm.state.collectAsState()

        Column {
            Text("Quotes")
            LazyColumn {
                quotes.forEach { LazyItem { QuoteCard(it) } }
            }
        }
    }
}
```

## Step-by-step migration checklist

1. Bump your version catalog to point at Konduit:

   ```toml
   [versions]
   konduit = "1.0.0-caliclan.4"

   [libraries]
   konduit-host  = { module = "dev.konduit:konduit-host",  version.ref = "konduit" }
   konduit-guest = { module = "dev.konduit:konduit-guest", version.ref = "konduit" }

   [plugins]
   konduit-schema             = { id = "dev.konduit.schema",             version.ref = "konduit" }
   konduit-generator-compose  = { id = "dev.konduit.generator.compose",  version.ref = "konduit" }
   konduit-generator-widget   = { id = "dev.konduit.generator.widget",   version.ref = "konduit" }
   konduit-generator-protocol-host  = { id = "dev.konduit.generator.protocol.host",  version.ref = "konduit" }
   konduit-generator-protocol-guest = { id = "dev.konduit.generator.protocol.guest", version.ref = "konduit" }
   konduit-generator-modifiers      = { id = "dev.konduit.generator.modifiers",      version.ref = "konduit" }
   ```

2. Add the Konduit repository while we're still on GitHub Packages
   (Maven Central is Phase 5 — see
   [`MAVEN_CENTRAL_SETUP.md`](./MAVEN_CENTRAL_SETUP.md)):

   ```kotlin
   // settings.gradle.kts
   dependencyResolutionManagement {
       repositories {
           maven {
               url = uri("https://maven.pkg.github.com/waliasanchit007/konduit")
               credentials {
                   username = providers.gradleProperty("gpr.user").get()
                   password = providers.gradleProperty("gpr.token").get()
               }
           }
       }
   }
   ```

3. Run the scripted rename above against your project tree.

4. Replace the upper module-list dep block with the two facade lines.

5. Drop any dependencies on the removed Phase 1.5 modules (the
   View / UIView / DOM toolkits).

6. Run a clean build. Surviving errors are usually:
   - String literals or comments that mention `redwood` — replace by hand.
   - Code that depended on now-removed View/UIView/DOM widgets — see
     "What's removed" for the affected modules.

7. (Optional, recommended) Add `Spec.bindWithTimeout { }` wrappers
   around every `zipline.bind` and `zipline.take` call. The default 30s
   timeout never fires in healthy operation but turns the silent
   bind-hang failure modes into clear exceptions.

8. (Optional) Adopt `konduit-vm` / `konduit-http` / `konduit-storage`
   as ergonomic upgrades over the bare-Zipline service pattern. None
   are mandatory; they're orthogonal to the migration.

## FAQ

**Do I have to migrate? Can I keep using upstream Redwood?**
Yes, you can keep using upstream Redwood — the source is still on
GitHub and Apache-licensed. But it's not being actively developed and
the silent-failure shapes catalogued in
[`KNOWN_BUGS.md`](./KNOWN_BUGS.md) are real. Konduit ships
mitigations for 11 of 12 documented gotchas as of `1.0.0-caliclan.3`.

**Is the wire format compatible?**
Yes. Bundles built against Redwood widgets render unchanged through
the Konduit runtime. If your `.zipline` bundles are built upstream and
your host is Konduit, things just work.

**What about the View / UIView / DOM widget toolkits I depend on?**
Phase 1.5 removed them as part of the CMP focus. Your options are:
(a) stay on upstream Redwood, (b) fork the removed modules yourself,
or (c) port to Compose Multiplatform widgets. Most production teams
have already moved to (c).

**Can I run both side-by-side during migration?**
No — the package namespace flip makes it confusing to import both at
the same time. Do the migration in one shot per module. If your
project has multiple host modules, do them one at a time.

**Will the namespace change again when Maven Central goes live?**
The Maven coordinate `groupId` will likely shift from `dev.konduit` to
`io.github.waliasanchit007` (Sonatype's GitHub-vanity flow — see
[`MAVEN_CENTRAL_SETUP.md`](./MAVEN_CENTRAL_SETUP.md)). The Kotlin
package paths in the JARs stay `dev.konduit.*` — only your Gradle
dep coordinate string changes.

**Where do I report issues?**
[`waliasanchit007/konduit`](https://github.com/waliasanchit007/konduit/issues/new/choose)
issue templates cover bug reports, feature requests, and integration
help. The integration-help template directs you to KNOWN_BUGS.md first
— most adoption pain is one of the documented silent-failure shapes.
