# Keliver

Server-driven UI for Compose Multiplatform. Render Kotlin Compose UI on
Android and iOS from JavaScript bundles delivered over the network —
ship UI updates without an app-store release.

> **Status: public on Maven Central — `0.2.0`.** Add `mavenCentral()` and
> depend on `dev.keliver:keliver-host:0.2.0` (host) /
> `dev.keliver:keliver-guest:0.2.0` (guest) — no GitHub PAT, no extra repo.
> `0.2.0` adds **`keliver-material`**, a ~60-widget Compose/Material3-parity
> library so you can build server-driven screens without authoring a schema.
> See [`docs/USAGE.md`](./docs/USAGE.md). `0.2.x` is the current line; `0.1.0`
> was the first public release (pre-`1.0`, API may still evolve); the wire
> format is stable within a line.

---

## What it is

Keliver is a [Cash App Redwood](https://github.com/cashapp/redwood) fork.
Upstream Redwood is great but is no longer being actively developed by
Cash App. Keliver picks up the foundation — `redwood-runtime`,
`redwood-compose`, `redwood-treehouse`, `redwood-treehouse-host`,
`redwood-treehouse-host-composeui` — and ships it under the `dev.keliver`
namespace with:

> Already running on upstream Redwood? See
> [`docs/MIGRATION_FROM_REDWOOD.md`](./docs/MIGRATION_FROM_REDWOOD.md)
> — the migration is mostly a scripted rename.


- **Production-hardening fixes** for the silent-failure shapes that hit
  every new integrator. See [`docs/KNOWN_BUGS.md`](./docs/KNOWN_BUGS.md)
  for the full list — 11 of 12 documented gotchas now have a Keliver-side
  mitigation as of `1.0.0-caliclan.3`.
- **A focused module set.** Phase 1.5 dropped 13 upstream modules that
  aren't relevant to Compose Multiplatform (View/UIView/DOM widgets, the
  test-app harness, etc.). Module count went from 60 → 47.
- **Continued maintenance.** Active integration with at least one
  production Compose Multiplatform app ([DevoStatus](https://github.com/waliasanchit007/DevoStatus),
  private) drives the gotcha backlog. Bugs surface in real adoption and
  get fixed; the changelog is honest about what's still broken.

## What it gives you

You write Kotlin Compose UI in a separate guest module. That code
compiles to JavaScript via Kotlin/JS and gets bundled into a
`.zipline` file. Your host app downloads the bundle at runtime,
executes it in [Zipline](https://github.com/cashapp/zipline) (a
QuickJS wrapper), and Keliver's protocol translates the guest's
widget tree into real Compose widgets on the host platform —
Android (Jetpack Compose), iOS (Compose Multiplatform), or anywhere
CMP runs.

The result: **change a screen, push a new bundle, every user has it
in seconds without an app-store cycle.**

A typical guest screen looks like normal Compose:

```kotlin
class QuotesScreen : Screen {
    @Composable override fun Content(navigator: Navigator) {
        val provider = HostQuotesProviderBridge.instance
        var quotes by remember { mutableStateOf<List<Quote>?>(null) }

        LaunchedEffect(provider) {
            quotes = provider?.getQuotes(filter = null) ?: emptyList()
        }

        Column(modifier = Modifier.fillMaxSize().padding(16, 16, 16, 16)) {
            Text("Select Quote", style = SchemaTextStyle.TitleLarge)
            LazyColumn {
                quotes.orEmpty().forEach { q ->
                    LazyItem { QuoteCard(q) }
                }
            }
        }
    }
}
```

The host wires services that the guest can call via Zipline RPC:

```kotlin
class QuotesAppSpec : TreehouseApp.Spec<SduiAppService>() {
    override val name = "quotes"
    override val manifestUrl = quotesManifestUrlFlow.asStateFlow()
    override val serializersModule = SduiSerializersModule

    private lateinit var quotesProvider: RealHostQuotesProvider
    private lateinit var quoteNavigator: RealHostQuoteNavigator

    override suspend fun bindServices(treehouseApp, zipline) {
        // bindWithTimeout turns the silent bind-hang failure shape into
        // an actionable timeout — see KNOWN_BUGS.md U1.
        bindWithTimeout {
            zipline.bind<HostConsole>("console", retain(StatusCraftHostConsole()))
        }

        requireSerializerOf<Quote>()  // bind-time pre-flight — KNOWN_BUGS U3.

        quotesProvider = RealHostQuotesProvider(
            source = quotesSource,
            scope = appScope,
            ziplineDispatcher = treehouseApp.dispatchers.zipline,
        )
        bindWithTimeout {
            zipline.bind<HostQuotesProvider>("quotes", quotesProvider)
        }

        quoteNavigator = RealHostQuoteNavigator(
            scope = appScope,
            uiDispatcher = treehouseApp.dispatchers.ui,  // KNOWN_BUGS U8.
            callback = onQuoteSelected,
        )
        zipline.bind<HostQuoteNavigator>("quote-nav", quoteNavigator)
    }

    override fun create(zipline: Zipline) = zipline.take<SduiAppService>("app")
}
```

## When to use it

**Good fit:**
- Frequently-updated content surfaces (feeds, banners, festival cards)
- A/B-testable layouts where iteration speed matters
- Settings / info / FAQ pages where pixel-perfect native isn't required
- Experimental screens you don't want to ship via app-store cycles

**Less good fit:**
- Hero screens with brand-critical pixel-perfect UX
- Performance-sensitive surfaces (the Zipline indirection adds startup
  + per-frame overhead — measure for your case)
- Anything that needs platform APIs the schema doesn't expose
  (Bitmap manipulation, MediaStore, Camera, etc.) — these need an
  RPC service designed per capability

A typical app uses Keliver for a slice of its surface area, not the
whole app. The [DevoStatus integration](https://github.com/waliasanchit007/DevoStatus)
uses it for two screens out of ~10.

## Getting started

- **[docs/GETTING_STARTED.md](./docs/GETTING_STARTED.md)** — the short path: run
  the sample, then write your first screen, on Android + iOS.
- **[docs/SCREEN_ARCHITECTURE.md](./docs/SCREEN_ARCHITECTURE.md)** — how to
  structure an app: Repository → Presenter → Screen, API + database via host
  services, and the two presentation styles (with when to use each).
- **[docs/USAGE.md](./docs/USAGE.md)** — the exhaustive reference: vendoring
  Keliver into a Compose Multiplatform host, the dev loop, and every
  silent-failure shape to watch for.

The [`sample/`](./sample) directory is a complete, runnable **starter** — a
Workouts screen (an API call + a host database + live search) built in both
architecture styles, with a green unit test. Copy it and rename, or run it
first to see the loop.

When the public OSS launch lands (see [PUBLIC_LAUNCH_ROADMAP.md](./PUBLIC_LAUNCH_ROADMAP.md))
a proper docs site will replace these.

## Known limitations + open bugs

[`docs/KNOWN_BUGS.md`](./docs/KNOWN_BUGS.md) is the punch list. As of
`1.0.0-caliclan.3`:

- ✅ U1, U2, U3, U5, U6, U7, U10 fixed or mitigated in Keliver (U5
  mitigated in `1.0.0-caliclan.4` via `keliver-image`).
- ✅ U4, U8 part 2, U9, U11 fixed or documented at the integration
  layer (see ServerDrivenUI's KNOWN_BUGS for fix details)
- 🎯 **U8 part 1** (`@MainThread` codegen) — only entry without a
  Keliver-side fix; root cause is in Zipline's compiler plugin, tracked
  at [cashapp/zipline#1825](https://github.com/cashapp/zipline/issues/1825).
  Workaround pattern is well-established.

## Versioning & API stability

Keliver is **pre-1.0** — pin an exact `dev.keliver:*` version and expect
breaking API changes across `0.x`. The public surface of every published module
is tracked by the binary-compatibility-validator on each build, so no API change
ships by accident. See [`docs/API_STABILITY.md`](./docs/API_STABILITY.md).

## What's planned

[`ROADMAP.md`](./ROADMAP.md) — adopter-facing forward-looking plan.
Distinct from [`PUBLIC_LAUNCH_ROADMAP.md`](./PUBLIC_LAUNCH_ROADMAP.md)
which tracks the one-time work to take this repo public.

## License

Apache 2.0 — same as upstream Cash App Redwood. See [`LICENSE.txt`](./LICENSE.txt).

## Credits

Keliver is built on [Cash App Redwood](https://github.com/cashapp/redwood)
and [Zipline](https://github.com/cashapp/zipline). The original Redwood
team did the hard architectural work; this fork picks up maintenance and
adds the production-hardening that surfaces in real-world integrations.
