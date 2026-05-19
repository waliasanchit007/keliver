# Change Log

> Konduit's history begins with a fork from CashApp Redwood `0.18.0`.
> Entries below `0.18.0` are from upstream Redwood. Konduit-only entries
> use the `1.0.0-caliclan.N` versioning scheme — see
> `docs/KONDUIT_PLAN.md` (in the Caliclan repo) for the full versioning
> and wire-format compatibility policy.

## [Unreleased]

New:
- `dev.konduit:konduit-treehouse-codegen` — KSP processor that
  emits `Generated<Name>Adapter` open classes for
  `@KonduitAppService`-annotated interfaces. Closes the second
  half of U12; adopter cost drops to **~5 LoC + zero
  `@file:Suppress`** (a companion-object wrapper on the
  interface itself). The 5 lines are unavoidable: Zipline IR
  looks up `<Interface>.Companion.Adapter` by name at code-load
  time, and KSP can't inject members into an existing companion
  object. Generated class is `internal` so the public API stays
  clean.

  4 fixture tests via `dev.zacsweers.kctfork:ksp` cover the
  happy path (correct generated source for an interface + its
  inherited `AppService` members), serial-name propagation, and
  the validation diagnostics (rejects classes, rejects
  interfaces that don't extend `AppService`).

  Sample migrated as a proof of API: `SampleAppService.kt`
  drops from ~30 LoC + `ManualSampleAppServiceAdapter.kt`
  (~70 LoC) to a single `@KonduitAppService`-annotated
  interface with the 5-line companion wrapper. End-to-end
  verified on Pixel 9 emulator (API 37): same Zipline RPC
  sequence, same "Hello, Konduit!" rendered.

- `@KonduitAppService` source-retention annotation in
  `konduit-treehouse` — marker that the codegen processor
  picks up. Zero runtime weight (SOURCE retention).

- `sample/benchmarks/` — Performance Phase 2 scaffolding.
  AndroidX Macrobenchmark `ColdStartBenchmark` (cold + warm
  variants) for measuring the sample's startup latency. Includes
  a new `benchmark` build type on `:host-android` configured to
  satisfy Macrobenchmark's safety checks: `isDebuggable = false`,
  debug-key signed (so adb-install works without a release
  keystore), `<profileable android:shell="true">` in the
  manifest, `androidx.profileinstaller` baked in. Runs via
  `./gradlew :benchmarks:connectedBenchmarkAndroidTest`.

  Known limitation: emulator `dumpsys gfxinfo … framestats` is
  slow to populate post-launch, which trips Macrobenchmark's
  activity-launch detection. Physical-device runs work; emulator
  runs need
  `testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"]
  = "EMULATOR"` (already set) and may still hit the underlying
  upstream issue. See `docs/PERFORMANCE.md` § "Phase 2 known
  limitation" for the full writeup.

- `dev.konduit.treehouse.KonduitAppServiceAdapter<T>` +
  `konduitReturningFunction()` helper + Konduit-blessed
  typealiases for `OutboundCallHandler` / `OutboundService` —
  cuts the [Zipline #765](https://github.com/cashapp/zipline/issues/765)
  manual-adapter boilerplate from ~95 LoC + 7-entry
  `@file:Suppress` to ~70 LoC + 2-entry `@file:Suppress`.
  Adopter imports come from `dev.konduit.treehouse` instead of
  `app.cash.zipline.internal.bridge.*`. Sample's
  `ManualSampleAppServiceAdapter.kt` migrated to the helper as a
  proof of API + ongoing usage example. Closes part of ROADMAP
  item #6; the KSP-based annotation-driven follow-up (full
  elimination of the boilerplate) is queued.

- `docs/KNOWN_BUGS.md` U12 — codifies the [Zipline #765](https://github.com/cashapp/zipline/issues/765)
  manual-adapter requirement that every Konduit `AppService`
  subinterface hits. Documents the symptom (`Constructor 'Adapter.<init>'
  can not be called` at QuickJS load), the root cause (Zipline IR
  plugin can't generate adapters for interfaces that transitively
  extend `ZiplineService` via `AppService`), the ~95-line manual
  workaround, and two possible Konduit-side fix approaches.
  Surfaced during the sample app's first end-to-end test run
  ([`sample/TESTING.md`](sample/TESTING.md)).

- `sample/` — **end-to-end tested on both Android and iOS.** The
  Android pass on a Pixel 9 emulator (API 37) surfaced 5 latent
  bugs/gaps between the README and what shipped: (1) Zipline IR
  plugin missing from every module with `take`/`bind` calls, (2)
  `kotlinSerialization` missing from `:shared`, (3)
  `Adapter.<init>` IrLinkageError per U12, (4) silent failures
  with no `EventListener` wired, (5) README referenced a
  nonexistent `serveDevelopmentZipline` Gradle task.

  The iOS pass on an iPhone 17 Pro simulator (iOS 26.3.1, Xcode
  26.3) surfaced 3 additional bugs/gaps: (iOS-#1) Xcode Run Script
  couldn't find `sample/gradlew`, (iOS-#2) no iOS-side
  `EventListener` (parity with Android #4), (iOS-#3)
  `NSLog("%@", kotlinString)` crashes with `EXC_BAD_ACCESS` on
  Xcode 26.3's pointer-authentication when called from
  Kotlin/Native. All 8 findings fixed in tree.

  Cross-platform parity confirmed: the five Android-side fixes
  apply unchanged on iOS, and only the three iOS-platform-specific
  findings (build-script, NSLog crash) are new. Full debugging log
  in [`sample/TESTING.md`](sample/TESTING.md) with separate
  Android and iOS case studies.

  Ready-made Xcode project at [`sample/iosApp/`](sample/iosApp/)
  links the `KonduitSampleHost.framework` and runs against the
  same `python3 -m http.server`-served bundle as the Android side.

- `docs/PERFORMANCE.md` + `scripts/measure-baselines.sh` — baseline
  performance measurements for adopter planning. Covers four metric
  categories:

  * **Measured today.** APK size (debug 13.4 MB / release 10.8 MB),
    Zipline bundle (Development 2.8 MB / Production 732 KB —
    *of which Konduit own-code is 12 KB*), iOS framework size,
    cold + warm build times. Reproduced from `sample/` against
    `1.0.0-caliclan.4-SNAPSHOT`.

  * **Methodology + target SLAs for runtime metrics.** Cold start
    (proposed P50 ≤ 800 ms / P95 ≤ 1500 ms via AndroidX
    Macrobenchmark), warm mount (P50 ≤ 100 ms via Compose snapshot
    observer), update latency (P50 ≤ 16 ms — single frame at 60 Hz),
    memory footprint (idle overhead ≤ 25 MB beyond a native-Compose
    baseline). Implementation queued for `konduit-benchmarks/` in
    the perf workstream's Phase 2.

  * **Comparison baselines.** Native-Compose-only and upstream
    Cash App Redwood 0.18.0 reference points documented as Phase 3
    (adopter-demand-gated).

  * **Reproducibility.** `scripts/measure-baselines.sh [--cold]`
    rebuilds + emits `key=value` lines that can be diffed across
    releases or piped into a CI artifact. Portable across macOS
    (BSD `stat`) and Linux (GNU `stat`).

  Closes the "performance baselines" line item from the post-caliclan.4
  roadmap. Phase 2 (device-level instrumentation) and Phase 3
  (comparison baselines) are tracked in `docs/PERFORMANCE.md`'s
  roadmap table.

- `sample/` — standalone Gradle build that's the smallest faithful
  Konduit setup adopters can copy. Covers every required moving
  part: a 2-widget custom `@Schema` (`Box`, `Text`), the four
  codegen modules (widget / modifier / protocol-host /
  protocol-guest), a Kotlin/JS `:guest` bundle, a Compose
  Multiplatform `:host-compose` module producing a
  `KonduitSampleHost.framework` for iOS, and a thin
  `:host-android` Android application shell. The whole thing
  renders one `Text` widget reading "Hello, Konduit!" — minimal
  but end-to-end. Adopters point their Swift app at
  `MainKt.MainViewController()` for iOS; the Android side is a
  standard `MainActivity` + `TreehouseAppFactory(...)`. Folds in
  the iOS-host-validation work that was tracked separately in
  the roadmap. See [`sample/README.md`](sample/README.md) for the
  runbook + adoption walkthrough.

- `dev.konduit:konduit-http-codegen` — KSP processor that reads
  `@KonduitApi`-annotated interfaces from `konduit-http-annotations`
  (Phase 1) and emits a companion `*Impl(KonduitHttp)` class per
  interface. Phases 2 + 3 of issue #18; ships the codegen MVP covering
  `@GET`, `@POST`, `@PUT`, `@DELETE`, `@Path`, `@Query`, `@Body`,
  `@Header`, and `@HeaderMap`. Adopter integration:

  ```kotlin
  plugins {
      id("com.google.devtools.ksp")
  }

  dependencies {
      implementation(libs.konduit.guest)   // pulls konduit-http-annotations transitively
      ksp("dev.konduit:konduit-http-codegen:1.0.0-caliclan.4-SNAPSHOT")
  }
  ```

  The processor validates:
  - `@KonduitApi` only on interfaces (rejects classes)
  - All methods must be `suspend`
  - Exactly one HTTP-method annotation per method
  - `@Body` only on `@POST` / `@PUT`; rejected on `@GET` / `@DELETE`
  - Exactly one parameter annotation per parameter
  - Every `{name}` placeholder in the path template has a matching
    `@Path("name")` parameter (and vice versa — orphan `@Path`
    parameters fail the build)
  - `@HeaderMap` parameter must be `Map<String, String>` or
    `Map<String, String?>` (other shapes rejected)

  `@HeaderMap` codegen spreads via `putAll(headers)` for non-nullable
  value maps; for `Map<String, String?>`, emits a `forEach` filter
  that drops null values before insertion — keeps the wire-level
  `Map<String, String>` invariant on `HttpRequest.headers`.

  Generated code uses inline + reified KonduitHttp helpers
  (`http.get<Res>(path, query, headers)`, `http.post(path, body, ...)`
  etc.), so JSON serialization picks up the adopter's
  `KonduitHttp.json` configuration without extra wiring. Auto-service
  registration via `dev.zacsweers.autoservice:auto-service-ksp` — no
  manual META-INF/services entry needed.

  Test coverage: 11 fixture tests via `dev.zacsweers.kctfork:ksp`
  (kotlin-compile-testing fork) — 5 happy-path generated-source
  assertions covering each HTTP verb + `@Path` substitution + both
  `@HeaderMap` shapes, plus 6 validation-diagnostic assertions
  covering each rejected misuse. Tests compile real Kotlin fixture
  sources with the processor installed and assert on either exit
  code or the generated `*Impl.kt` content.

- `dev.konduit.gradle.BundleSizeBudgetTask` — Gradle task that fails
  the build if the sum of `.zipline` bundle module sizes exceeds a
  configurable budget. Adopters register it on their guest module:

  ```kotlin
  tasks.register<dev.konduit.gradle.BundleSizeBudgetTask>("checkBundleSize") {
      bundleFiles.from(fileTree("build/zipline") { include("*.zipline") })
      maxBytes.set(500_000L)
      warnAtBytes.set(400_000L)   // optional soft threshold
  }
  tasks.named("check") { dependsOn("checkBundleSize") }
  ```

  Error output ranks files by size so size-regression diagnosis
  is one log read: the largest file is usually the regression. Soft
  warn threshold is optional. 6/6 unit tests via `ProjectBuilder`.
  Shipped from `konduit-gradle-plugin` — no new plugin id, register
  the task type directly.

- `dev.konduit:konduit-http-annotations` — Retrofit-style HTTP API
  annotations (`@KonduitApi`, `@GET`, `@POST`, `@PUT`, `@DELETE`,
  `@Path`, `@Query`, `@Body`, `@Header`, `@HeaderMap`). Phase 1 of
  the codegen workstream from issue #18; ships the annotation API
  surface so adopters can preview the shape and start drafting
  `@KonduitApi` interfaces today. All annotations are `SOURCE`
  retention — zero runtime weight. The KSP processor that consumes
  them and generates `*Impl(http: KonduitHttp)` classes is queued
  for the next development cycle — full multi-phase design is in
  `docs/HTTP_API_CODEGEN_DESIGN.md`. Re-exported through
  `dev.konduit:konduit-guest`.

Docs:
- USAGE.md § "Reactive data — `Flow<T>` vs Observer callbacks": new
  section explaining when to use Zipline's native `Flow<T>` wire
  type vs the Observer-callback pattern ServerDrivenUI currently
  uses. Includes a side-by-side decision table, the U1 / dispatcher
  caveats, and the recommendation for new providers on caliclan.3+
  to prefer `Flow<T>` returns.
- KNOWN_BUGS U1: clarifies that the suspend-bind hang is specific to
  `suspend fun X(...): List<@Serializable T>` shapes — a non-suspend
  method returning `Flow<T>` is wire-safe because Zipline's
  `FlowSerializer` marshals it as a service proxy rather than an
  inline collection.

New:
- `Spec.requireSerializersOf(vararg types: KType)` — bulk-validate
  that every wire type used across a host's `ZiplineService`
  interfaces has a registered `KSerializer` in the spec's
  `serializersModule`. Equivalent to calling
  `requireSerializerOf<T>()` N times, but the bulk form is more
  discoverable and shorter at each call site:

  ```kotlin
  override suspend fun bindServices(treehouseApp, zipline) {
      requireSerializersOf(
          typeOf<Quote>(),
          typeOf<Wallpaper>(),
          typeOf<SavedCardKey>(),
          typeOf<List<Quote>>(),   // verifies Quote transitively
      )
      bindWithTimeout {
          zipline.bind<HostQuotesProvider>("quotes", impl)
      }
  }
  ```

  Catches U3 (kotlinx-serialization plugin missing on the module
  declaring the type) plus the U4-adjacent silent-failure shape: a
  `SerializationException` thrown inside a `ZiplineService`
  callback's response handler gets swallowed by the protocol path,
  leaving the guest with no response, no error, no log. Validating
  every wire type at bind time forces the failure into the
  actionable surface instead. Multiplatform — works on Android,
  iOS, and JVM hosts. Returns the resolved `KSerializer<*>` list
  in input order. Closes issue #30 (bind-time approach; runtime
  instrumentation is queued as a separate safety-net follow-up).

- `MissingSerializerException` now exposes a public-by-default
  string-named constructor (`@PublishedApi internal`) plus the
  existing `KClass<*>` overload, so the bulk helper can use a
  human-readable type display for generics like `List<Quote>`.

Fixed:
- Schema parser rejects `@Composable` lambdas as `@Property`. Adopters
  who naively reach for `@Property val content: @Composable () -> Unit`
  to model a composition slot got a silent runtime no-op — the codegen
  saw a function-typed property, generated RPC-callback wiring, and
  the @Composable annotation got dropped at the proxy boundary. The
  schema parser FIR check now rejects the shape at build time with a
  clear message pointing at the canonical `@Children` workaround.
  Closes issue #31.

New:
- `dev.konduit.zipline-shapes` Gradle plugin
  (`konduit-gradle-plugin`) — build-time lint that rejects
  `ZiplineService` interface methods with function-typed parameters
  (`(T) -> Unit`, `((A) -> B)?`, etc). The bad signature compiles
  silently today but produces a runtime-broken proxy on `take<T>()`
  in the guest, with every method call no-oping silently —
  KNOWN_BUGS U11. Apply the plugin in any module that declares
  `ZiplineService` interfaces:

  ```kotlin
  plugins {
      id("dev.konduit.zipline-shapes")
  }
  ```

  The plugin auto-registers a `validateZiplineServiceShapes` task,
  scans every `.kt` under `src/`, fails the build with the offending
  interface name + parameter snippet + canonical workaround (a
  callback `ZiplineService` like `SnackbarResultCallback`), and
  hooks into the `check` lifecycle. Replaces the inline Gradle
  task ServerDrivenUI used to ship — adopters get the lint for
  free now. See issue #29, `docs/KNOWN_BUGS.md` U11.

- `dev.konduit:konduit-nav` — guest-side typed navigation. Replaces
  the per-app `HostNavigator` RPC-per-route pattern with a
  guest-owned back stack and a `KonduitNavController<R>` API that
  mirrors Compose Navigation ergonomics.

  Adopters define routes as a sealed interface (args inline,
  type-checked at the call site); `rememberKonduitNavController<R>(start)`
  + `KonduitNavHost(controller) { route -> when … }` is the entire
  setup. Nested screens read the controller via
  `currentKonduitNavController<R>()`.

  Each stack entry is wrapped in its own `SaveableStateHolder` slot,
  so a screen's `rememberSaveable` state survives a navigate-away-
  and-back round-trip; popped entries get their state cleared.

  Controller API: `current`, `backstack`, `canPop`, `navigate`,
  `pop`, `popUntil { … }`, `replaceAll`. v1 is pure guest — host-aware
  back stack, deep linking from the host, and animated transitions
  are queued as additive future work and won't break the existing
  call shape when they land.

  Re-exported through `dev.konduit:konduit-guest`. See issue #25,
  `docs/USAGE.md` "Typed navigation in the guest".

Changed:
- POM metadata for every published artifact now reflects the Konduit fork
  rather than the upstream Cash App Redwood project. Adopters inspecting
  published artifacts (or any consumers using Maven's POM-driven tooling)
  see Konduit-branded `<name>`, `<description>`, `<url>`, `<developers>`,
  and `<scm>` instead of inherited `cashapp/redwood` values. License stays
  Apache-2.0. Required cleanup before Maven Central publishing (Phase 5);
  also removes a real source of confusion on GitHub Packages today.
  Closes #11.

New:
- `dev.konduit:konduit-http` — HTTP shim. `HostHttpProvider :
  ZiplineService` is a generic HTTP proxy the host implements once with
  its existing `HttpClient` (Ktor / Retrofit / OkHttp / etc.).
  `KonduitHttp` is the guest-side typed wrapper exposing `get<Res>`,
  `post<Req, Res>`, `put<Req, Res>`, `delete<Res>`, plus empty-body
  helpers `deleteUnit`, `postEmpty<Res>`, `putEmpty<Res>`, and
  `requestRaw`. Built on inline + reified kotlinx-serialization.
  Non-2xx responses raise `KonduitHttpException(status, body)`.
  Adopters replace N per-endpoint `HostXxxProvider` services with
  one host binding plus a typed API class per backend. Wire types
  (`HttpRequest`, `HttpResponse`) are wire-additive — all fields
  default-valued so future additions (binary bodies, cache policy,
  streaming) can append without breaking older clients. Re-exported
  through both facades. See issues #7 + #28, `docs/USAGE.md`
  "API calls from the guest".

- `dev.konduit:konduit-storage` — key/value persistence shim.
  `HostStorage : ZiplineService` is a minimal `get` / `set` / `keys`
  contract the host implements once with its backend of choice
  (`DataStore<Preferences>` on Android, `NSUserDefaults` on iOS, a
  file-backed JSON blob, an SQLite KV table). `KonduitStorage` is the
  guest-side typed wrapper: `suspend inline fun <reified T> get(key)`
  / `set(key, value)` / `remove(key)` / `keys(prefix)`, with values
  round-tripping through kotlinx-serialization. Closes the common need
  for cross-mount persistence without forcing adopters to write a new
  `HostXxxProvider` per saved-state shape. Re-exported through both
  facades. See issue #10, `docs/USAGE.md` "Key/value persistence from
  the guest".

- `docs/MIGRATION_FROM_REDWOOD.md` — migration guide for teams moving
  from upstream Cash App Redwood (any version up to `0.18.0`) to
  Konduit. Covers what's identical (wire format, runtime semantics,
  type names), what's renamed (Maven coordinates, Gradle plugin IDs,
  package paths), what's removed (Phase 1.5 View / UIView / DOM
  module trim), what's added (production-hardening helpers, facades,
  vm/http/storage shims), a `sed` script handling 90%+ of the rename
  surface, and a step-by-step migration checklist. Closes #13.

- `dev.konduit:konduit-console` — standard host-bound logging service.
  `KonduitConsole : ZiplineService` is the canonical `fun log(level,
  message)` contract every adopter previously rewrote from scratch.
  `DefaultKonduitConsole(tag)` ships as a reference impl that emits
  `"[$tag/$level] $message"` via `println` (lands on Logcat on Android,
  the Xcode console on iOS, stdout on JVM). Subclass and override
  `output(line)` to route through `android.util.Log`, `os_log`, SLF4J,
  or whatever your app uses. Re-exported through `konduit-host`. See
  issue #27, `docs/USAGE.md` Step 2.

- `dev.konduit:konduit-image` — one-line Coil 3 setup. Closes the
  long-standing KNOWN_BUGS U5 silent-failure shape (blank AsyncImage
  with no exception). `KonduitImage.installSingleton()` registers a
  platform-appropriate Coil 3 `ImageLoader` singleton with a default
  network fetcher: OkHttp on Android / JVM (zero new transitive
  weight — OkHttp ships with Zipline anyway), Ktor 3 + Darwin engine
  on iOS. Adopters call it once near the top of their root
  `@Composable`; `AsyncImage` then renders correctly. Configurable
  via `crossfade`, `fetcher`, and `additional` parameters to override
  the default fetcher or tune the `ImageLoader.Builder` (cache size,
  interceptors, custom mappers). Re-exported through the
  `konduit-host` facade. See issue #26, `docs/USAGE.md` "If you use
  AsyncImage", `docs/KNOWN_BUGS.md` U5.

- `dev.konduit:konduit-vm` — guest-side ViewModel helper module.
  `KonduitViewModel` base class owns a `viewModelScope` (`SupervisorJob` +
  `Dispatchers.Main`, the Zipline dispatcher) and an `onCleared` hook;
  the `konduitViewModel { factory() }` `@Composable` entry point
  constructs the VM on first composition, returns the same instance
  across recompositions, and cancels the scope when the hosting
  `@Composable` leaves the tree. Closes the cosmetic gap with native
  Compose-Android ViewModel ergonomics for the parts that port (scope,
  recomposition survival, lifecycle hook). Re-exported through
  `dev.konduit:konduit-guest`. See issue #6 and `docs/USAGE.md` —
  "ViewModel-like patterns in the guest".

- `dev.konduit:konduit-host` and `dev.konduit:konduit-guest` —
  facade modules that aggregate the public adopter-facing surface
  through `api` dependencies. A host module can drop its 8-line
  Konduit dependency block (`konduit-treehouse-host`,
  `konduit-treehouse-host-composeui`, `konduit-compose`,
  `konduit-widget`, `konduit-runtime`, `konduit-protocol`,
  `konduit-protocol-host`, `konduit-treehouse`, plus `zipline` and
  `zipline-loader`) in favor of `implementation(libs.konduit.host)`;
  a guest module collapses its 7-line equivalent into
  `implementation(libs.konduit.guest)`. The pre-facade per-module
  imports continue to work — the facade is additive. See
  PUBLIC_LAUNCH_ROADMAP.md Phase 2 and `docs/USAGE.md` for the
  adoption snippets.

Changed:
- `KonduitViewModel.onCleared()` visibility narrowed from `public open`
  to `protected open`. The Compose helper invokes it through a
  framework-internal trampoline (`@PublishedApi internal
  clearInternal()`), matching the encapsulation pattern Android's
  `ViewModel` uses (`clear` internal → `onCleared` protected).
  Adopters can still override `onCleared` for cleanup; the only break
  is calling `vm.onCleared()` from outside the VM class, which had no
  legitimate use case. Safe pre-1.0-release polish.
- `konduitViewModel { ... }` accepts an optional `key: Any? = null`
  parameter. When `key` changes between recompositions, the current
  VM's `onCleared` runs and the factory builds a fresh instance.
  Mirrors Android's `viewModel(key = ...)` pattern. Useful for
  screens parameterized by an upstream value (`userId`, list filter,
  navigator entry id) that should produce a clean VM when the value
  changes. Existing call sites (no key) keep their current behavior.

Fixed:
- Nothing yet.


## [1.0.0-caliclan.3] - 2026-05-17

Production-hardening release. Mitigations + outright fixes for the
silent-failure shapes the ServerDrivenUI / DevoStatus integration
surfaced over the prior weeks. See `docs/KNOWN_BUGS.md` in the
ServerDrivenUI reference repo for the full historical context per
entry.

New:
- `TreehouseApp.Spec.retain(service)` — strong-ref pass-through helper that
  keeps the given service reachable for the lifetime of the Spec (the
  lifetime of the TreehouseApp). Use this when binding inline anonymous
  service implementations: `zipline.bind<HostFoo>("foo", retain(object :
  HostFoo { … }))`. Zipline holds only weak references internally, so
  inline anon objects were previously GC-eligible the moment `bindServices`
  returned, causing the first guest call to throw "no such service
  (service closed?)". `retain()` removes the requirement to remember the
  `val`-field pattern. Closes integration bug U7 in the ServerDrivenUI
  reference repo.
- `Spec.retainedServices: List<Any>` — read-only view of services
  currently retained, for diagnostics or tests.
- `Spec.bindWithTimeout(timeoutMillis = 30_000L) { block }` — wraps a
  `zipline.bind`/`zipline.take` call with a deadline. Throws
  `ZiplineBindTimeoutException` with a diagnostic message if the bind
  doesn't return in time. Turns two silent-hang failure modes into one
  actionable exception:
  - KNOWN_BUGS U1: `suspend fun X(...): List<@Serializable T>` makes
    bind block forever on Zipline 1.26.
  - KNOWN_BUGS U2: Zipline Gradle plugin not applied to the module
    calling `bind`.

  Healthy binds return in milliseconds, so the timeout never fires in
  normal operation.
- `ZiplineBindTimeoutException` — public exception class thrown by
  `bindWithTimeout`. Carries the underlying
  `TimeoutCancellationException` as `cause`. Message names both U1 and
  U2 as candidates plus the workaround for each.
- `Spec.requireSerializerOf<T>()` — bind-time pre-flight check that
  resolves a `KSerializer<T>` from `serializersModule` and throws
  `MissingSerializerException` with a clear diagnostic if it can't.
  Catches U3 (kotlinx-serialization plugin missing on the module
  declaring a `@Serializable` type) at bind time rather than at first-
  call time. Optional helper — call once per wire type at the top of
  `bindServices` for the strongest diagnostics.
- `MissingSerializerException` — public exception class thrown by
  `requireSerializerOf`. Names the type that couldn't be resolved plus
  the two known causes (missing `@Serializable` annotation, missing
  kotlinx-serialization plugin).

Changed:
- Nothing yet.

Fixed:
- **Schema parser rejects function-typed `@Modifier` properties at build time.**
  Previously, declaring a property like `val onClick: () -> Unit` on a
  `@Modifier` data class compiled on JVM but produced invalid Kotlin in the
  protocol-guest JS codegen output (`ContextualSerializer(Function0<Unit>::class)`
  — class literals aren't allowed on parameterized types). The integrator
  saw a cryptic "expecting class body" error in generated code they didn't
  write. The parser now rejects this shape with a clear message pointing
  at the canonical workaround (put click handlers on widgets as a regular
  `@Property` — see `Button.onClick` / `Box.onClick`). Closes integration
  bug U6 in the ServerDrivenUI reference repo.
- **Modifier serializer codegen no longer white-screens on enum properties.**
  Previously the protocol-guest generator emitted
  `ContextualSerializer(MyEnum::class)` without the fallback constructor
  args for any enum field on a `@Modifier` whose `@Serializable`
  annotation the FIR parser couldn't detect (cross-module enum types
  are the common case). At runtime the encode call threw
  `SerializationException`, which the protocol path swallowed silently,
  resulting in a blank `TreehouseContent` with zero logs — the worst
  documented failure shape in the integration. The codegen now emits the
  `MyEnum.serializer(), emptyArray()` fallback for every non-parameterized
  `ClassName` typed property; `ContextualSerializer` then falls through
  to the auto-generated `.serializer()` companion. Types that aren't
  `@Serializable` produce a clear compile error referencing the missing
  `.serializer()` companion instead of a silent runtime white screen.
  Closes integration bug U10 in the ServerDrivenUI reference repo.


## [1.0.0-caliclan.2] - 2026-04-30

Phase 1.5 cleanup. Konduit now ships only Compose Multiplatform-relevant
modules.

Removed:
- `konduit-layout-{view,uiview,dom}`
- `konduit-lazylayout-{view,uiview,dom}`
- `konduit-ui-basic-{view,uiview,dom}`
- `konduit-widget-{view-test,uiview-test}`
- `konduit-dom-testing`
- `konduit-leak-detector-zipline-test`
- Upstream `test-app/` integration tests
- Upstream `samples/` (Counter, EmojiSearch demo apps)

Module count: 60 → 47. Wire format unchanged from `caliclan.1`;
consumers can upgrade transparently.


## [1.0.0-caliclan.1] - 2026-04-29

Initial Konduit fork from CashApp Redwood `0.18.0`. No functional
changes from upstream — only renames + group/version reset.

Changed:
- Maven group `app.cash.redwood` → `dev.konduit`
- Module names `redwood-*` → `konduit-*`
- Package paths `app.cash.redwood.*` → `dev.konduit.*`
- Version reset to `1.0.0-caliclan.1`

Wire format identical to upstream `0.18.0` — Caliclan migration
verified end-to-end on Android + iOS with no behavior changes.


## [0.18.0] - 2025-08-01
[0.18.0]: https://github.com/cashapp/redwood/releases/tag/0.18.0

New:
- Schema `@Widget`s can now set `internalComposable = true` to have their `@Composable` functions generated as internal. This will require that you define a public version in the main sources of the module which generates the functions. This can be used to hide old widgets that should no longer be used, create more complex widget protocols away from callers, and to conditionally split implementation between two bindings, for example.
- UI changes which come from Treehouse are now converted to their final value on a background thread. Previously JSON deserialization happened on the background thread to an intermediate model, but mapping that model to the final value still occurred on the main thread.

Changed:
- Schema dependencies can now be a graph (i.e., dependencies can have their own dependencies), but the entire transitive set needs to be redeclared on the root schema (for the protocol to work properly).
- Compose UI widget type has been changed from `@Composable () -> Unit` to `@Composable (Modifier) -> Unit` to support unscoped modifiers.
- JVM and Android artifacts now target Java 11 bytecode, as the upstream Compose dependencies now all target Java 11.
- The host protocol type has been renamed from `ProtocolFactory` to `HostProtocol`. An instance of `HostProtocol` is now required when constructing a `TreehouseAppFactory`.
- Enforce that event properties declared in your schema always return `Unit`.
- The root node's children are now identified using the tag 99,999 instead of 1. This attempts to prevent accidentally using the value for non-root nodes. The old value is still supported by the actual root node for compatibility with older guest code.
- In-development snapshots are now published to the Central Portal Snapshots repository at https://central.sonatype.com/repository/maven-snapshots/.

Fixed:
- Don't double insets on insets-aware `UIViews`. Previously we offered the same insets via two mechanisms, which could result in double insets.
- Don't conflate `CrossAxisAlignment.Stretch` with `Contraint.Fill`. We had a bug where `CrossAxisAlignment.Stretch` would cause children to fill their parent container.
- Honor the inbound max width for `Row` and `Column` layouts using `Constraint.Wrap` on iOS. This is necessary for child components that can wrap, like text.
- Using "stretch" cross-axis alignment on a lazy list now works correctly in Compose UI.

Upgraded:
- Kotlin 2.2.0
- Zipline 1.21.0


## [0.17.0] - 2025-01-30
[0.17.0]: https://github.com/cashapp/redwood/releases/tag/0.17.0

Breaking:
- Treehouse hosts running Redwood 0.11.0 or older are not longer actively supported. They will continue to work, but they will experience indefinite memory leaks of native widgets.
- Old, deprecated overloads of `ZiplineTreehouseUi.start` have been removed. The new overloads have been available since Redwood 0.8.0 for over a year.

New:
- `UIConfiguration.viewInsets` tracks the safe area of the specific `RedwoodView` being targeted. This is currently implemented for views on Android and UIViews on iOS.
- `ConsumeInsets {}` composable consumes insets. Most applications should call this in their root composable function.
- Add `TestRedwoodComposition.setContentAndSnapshot` function which is a fused version of `setContent` and `awaitSnapshot`, except that it guarantees the returned snapshot is the result of the initial composition of the content without any additional frames sent.

Fixed:
- Fix inconsistency in margin application between `ComposeUiBox` and `ViewBox`.
- Add support for the Height modifier in `ComposeUiBox`.
- Add support for the Width modifier in `ComposeUiBox`.
- Call `DisposableEffect` when a screen is unbound. We were only calling these when the effect was removed from the composition.
- Support `movableContentOf` in Treehouse (and generally in the Redwood protocol). Note: this requires the host be running version 0.17.0 or newer.
- Fix case where `Column` and `Row` would not update their intrinsic size on iOS if they are not a child of another `Column` or `Row`.


## [0.16.0] - 2024-11-19
[0.16.0]: https://github.com/cashapp/redwood/releases/tag/0.16.0

New:
- Redwood publishes what's happening in bound content through the new `Content.State` type.
- Accept a `ZiplineHttpClient` in `TreehouseAppFactory` on Android.

Changed:
- Drop support for non-incremental layouts in `Row` and `Column`.
- Support for `@Default` annotation has now been removed, as detailed in the 0.15.0 release.

Fixed:
- Fix a layout bug where children of fixed-with `Row` containers were assigned the wrong width.
- Fix inconsistencies between iOS and Android for `Column` and `Row` layouts.
- Fix a layout bug where `Row` and `Column` layouts reported the wrong dimensions if their subviews could wrap.
- Correctly update the layout when a Box's child's modifiers are removed.
- Fix a layout bug where children of `Box` containers were not measured properly.
- Fix a bug where `LazyColumn` didn't honor child widget resizes.

Breaking:
- Replace `CodeListener` with a new `DynamicContentWidgetFactory` API. Now loading and crashed views work like all other child widgets.


## [0.15.0] - 2024-09-30
[0.15.0]: https://github.com/cashapp/redwood/releases/tag/0.15.0

New:
- Default expressions can now be used directly in the schema rather than using the `@Default` annotation. The annotation has been deprecated, and will be removed in the next release.
- `EventListener.Factory.close()` is called by `TreehouseApp.close()` to release any resources held by the factory.
- Lambda parameter names defined in the schema are now propagated to the generated composable and widget interface.
- `ResizableWidget` is an interface that `UIView` widgets must use if their intrinsic sizes may change dynamically. It notifies any enclosing parent views to trigger a new layout.

Changed:
- Removed Wasm JS target. We are not ready to support it yet.

Fixed:
- Breaking the last remaining retain cycle in `UIViewLazyList`.
- Don't leak the `DisplayLink` when a `TreehouseApp` is stopped on iOS.
- Correctly handle dynamic size changes for child widgets of `Box`, `Column`, and `Row`.
- Don't clip elements of `Column` and `Row` layouts whose unbounded size exceeds the container size.
- Correctly implement margins for `Box` on iOS.
- Correctly handle dynamic updates to modifiers on `Column` and `Row`.


## [0.14.0] - 2024-08-29
[0.14.0]: https://github.com/cashapp/redwood/releases/tag/0.14.0

New:
- Source-based schema parser is now the default. The `useFir` Gradle property has been removed.
- `TreehouseAppFactory` accepts a `LeakDetector` which can be used to notify you of reference leaks for native UI nodes, Zipline instances, Redwood's own internal wrappers, and more.
- Introduce a `LoadingStrategy` interface to manage `LazyList` preloading.
- Optimize encoding modifiers in Kotlin/JS.

Changed:
- In Treehouse, events from the UI are now serialized on a background thread. This means that there is both a delay and a thread change between when a UI binding sends an event and when that object is converted to JSON. All arguments to events must not be mutable and support property reads on any thread. Best practice is for all event arguments to be completely immutable.
- `ProtocolFactory` interface is now sealed as arbitrary subtypes were never supported. Only schema-generated subtypes should be used.
- `UIViewLazyList` doesn't crash with a `NullPointerException` if cells are added, removed, and re-added without being reused.
- Change `UiConfiguration.viewportSize` to be nullable. A null `viewportSize` indicates the viewport's size has not been resolved yet.

Fixed:
- Breaking `content: UIView` retain cycle in `UIViewLazyList`'s `LazyListContainerCell`.
- Update `ProtocolNode` widget IDs when recycling widgets. This was causing pooled nodes to be leaked.

Breaking:
- The `TreehouseApp.spec` property is removed. Most callers should be able to use `TreehouseApp.name` instead. This is necessary to avoid a retain cycle.

Upgraded:
- Kotlin 2.0.20
- Zipline 1.17.0


## [0.13.0] - 2024-07-25
[0.13.0]: https://github.com/cashapp/redwood/releases/tag/0.13.0

New:
- Wasm JS added as a target for common Redwood modules. There is no Treehouse support today.
- Add `onScroll` property to `Row` and `Column`. This property is invoked when `overflow = Overflow.Scroll` and the container is scrolled.
- Add `Px` class to represent a raw pixel value in the host's coordinate system.
- New source-based schema parser can be enabled with `redwood { useFir = true }` in your schema module. Please report and failures to the issue tracker. This parser will become the default in 0.14.0.

Changed:
- The `TreehouseApp` type is now an abstract class. This should make it easier to write unit tests for code that integrates Treehouse.
- The `TreehouseApp.Spec.bindServices()` function is now suspending.
- The `TreehouseAppFactory` function now accepts a Zipline `LoaderEventListener` parameter.

Fixed:
- Using a `data object` for a widget of modifier no longer causes schema parsing to crash.
- Ensuring `LazyList`'s `itemsBefore` and `itemsAfter` properties are always within `[0, itemCount]`, to prevent `IndexOutOfBoundsException` crashes.
- Don't crash in `LazyList` when a scroll and content change occur in the same update.
- Updating a flex container's margin now works correctly for Yoga-based layouts.

Breaking:
- The `TreehouseApp.Factory.dispatchers` property is removed, and callers should migrate to `TreehouseApp.dispatchers`. With this update each `TreehouseApp` has its own private thread so a shared `dispatchers` property no longer fits our implementation.
  -`TreehouseApp.Spec.bindServices()` now accepts a `TreehouseApp` parameter.

Upgraded:
- Zipline 1.16.0


## [0.12.0] - 2024-06-18
[0.12.0]: https://github.com/cashapp/redwood/releases/tag/0.12.0

New:
- Upgrade to Kotlin 2.0!
- Added a basic DOM-based `LazyList` implementation.
-`TreehouseApp.close()` stops the app and prevents it from being started again later.
- Added `UiConfiguration.layoutDirection` to support reading the host's layout direction.
- New `konduit-bom` artifact can be used to ensure all Redwood artifacts use the same version. See [Gradle's documentation](https://docs.gradle.org/current/userguide/platforms.html#sub:bom_import) on how to use the BOM in your build.

Changed:
- The `dev.konduit` Gradle plugin has been removed. This plugin did two things: apply the Compose compiler and add a dependency on the `konduit-compose` artifact. The Compose compiler can now be added by applying the `org.jetbrains.kotlin.plugin.compose` Gradle plugin. Dependencies on Redwood artifacts can be added manually.
- Removed deprecated `typealias`es for generated `-WidgetFactories` type which was renamed to `-WidgetSystem` in 0.10.0.
- Removed deprecated `Modifier.flex` extension function which is now supported natively by `Row` and `Column` since 0.8.0.
- Removed deprecated `TreehouseWidgetView` and `TreehouseUIKitView` type aliases for `TreehouseLayout` and `TreehouseUIView` which were renamed in 0.7.0.
- Removed deprecated `TreehouseAppFactory` functions with the old `FileSystem` and `Path` order which were changed in 0.11.0.
- Rename the two types named `ProtocolBridge` to `ProtocolHost` and `ProtocolGuest`.

Fixed:
- Fix memory leaks caused by reference cycles on iOS. We got into trouble mixing garbage-collected Kotlin objects with reference-counted Swift objects.

Breaking:
-`TreehouseApp.zipline` is now a `StateFlow<Zipline?>` instead of a `Zipline?`.
-`CodeListener.onCodeDetached()` replaces `onUncaughtException()`. The new function is called
 whenever code stops driving a view for any reason. The new function accepts a `Throwable?` that is
 non-null if it's detached due to exception.
-`Content.awaitContent()` now accepts an optional `Int` parameter for the number of updates to
 observe before the function returns.
- MacOS targets have been removed from all modules.

Upgraded:
- Kotlin 2.0.0
- Zipline 1.13.0
- kotlinx.serialization 1.7.0


### Gradle plugin removed

This version of Redwood removes the custom Gradle plugin in favor of [the official JetBrains Compose compiler plugin](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compiler.html) which ships as part of Kotlin itself.
Each module in which you had previously applied the `dev.konduit` plugin should be changed to apply `org.jetbrains.kotlin.plugin.compose` instead.
The Redwood dependencies will no longer be added as a result of the plugin change, and so any module which references Redwoods APIs should add those dependencies explicitly.

For posterity, the Kotlin version compatibility table and compiler version customization for our old Redwood Gradle plugin will be archived here:

<details>
<summary>Redwood 0.12.0 Gradle plugin Kotlin compatibility table</summary>
<p>

Since Kotlin compiler plugins are an unstable API, certain versions of Redwood only work with
certain versions of Kotlin.

| Kotlin | Redwood       |
|--------|---------------|
| 1.9.24 | 0.11.0        |
| 1.9.23 | 0.10.0        |
| 1.9.22 | 0.8.0 - 0.9.0 |
| 1.9.10 | 0.7.0         |
| 1.9.0  | 0.6.0         |
| 1.8.22 | 0.5.0         |
| 1.8.20 | 0.3.0 - 0.4.0 |
| 1.7.20 | 0.1.0 - 0.2.1 |

</p>
</details>

<details>
<summary>Redwood 0.12.0 Gradle plugin Compose compiler customization instructions</summary>
<p>

Each version of Redwood ships with a specific JetBrains Compose compiler version which works with
a single version of Kotlin (see [version table](#version-compatibility) above). Newer versions of
the Compose compiler or alternate Compose compilers can be specified using the Gradle extension.

To use a new version of the JetBrains Compose compiler version:
```kotlin
redwood {
  kotlinCompilerPlugin.set("1.4.8")
}
```

To use an alternate Compose compiler dependency:
```kotlin
redwood {
  kotlinCompilerPlugin.set("com.example:custom-compose-compiler:1.0.0")
}
```

</p>
</details>


## [0.11.0] - 2024-05-15
[0.11.0]: https://github.com/cashapp/redwood/releases/tag/0.11.0

New:
- Added `toDebugString` method for `WidgetValue` and `List<WidgetValue>` which returns a formatted string of a widget's children and properties, useful for test debugging.

Changed:
- Removed generated `typealias`es for package names which changed in 0.10.0.
- In `UIViewLazyList`'s `UITableView`, adding special-case handling for programmatic scroll-to-top calls.
- APIs accepting a `FileSystem` and `Path` now have the `FileSystem` coming before the `Path` in the parameter list. Compatibility functions are retained for this version, but will be removed in the next version.
- Change `LazyListState` to be scroll-aware, reducing the size of the preload window while actively scrolling, and optimizing the preload window once the scroll has completed.

Fixed:
- Work around a problem with our memory-leak fix where our old LazyList code would crash when its placeholders were unexpectedly removed.
- Avoid calling into the internal Zipline instance from the UI thread on startup. This would manifest as weird native crashes due to multiple threads mutating shared memory.
- In `UIViewLazyList`, fix `UInt` to `UIColor` conversion math used for  `pullRefreshContentColor`.
- In `YogaUIView`'s `setScrollEnabled` method, only call `setNeedsLayout` if the `scrollEnabled` value is actually changing.
- In `YogaUIView`'s `layoutNodes` method, return early for nested `YogaUIView`s to prevent redundant frame calculations.

Upgraded:
- Zipline 1.10.1.

This version works with Kotlin 1.9.24 by default.


## [0.10.0] - 2024-04-05
[0.10.0]: https://github.com/cashapp/redwood/releases/tag/0.10.0

New:
- Compose UI implementation for `Box`.
- Layout modifier support for HTML DOM layouts.
- Unscoped modifiers provide a global hook for side-effecting behavior on native views. For example, create a background color modifier which changes the platform-native UI node through a factory function.
- `Widget.Children` interface now exposes `widgets: List<Widget<W>>` property. Most subtypes were already exposing this individually.

Changed:
- Disable klib signature clash checks for JS compilations. These occasionally occur as a result of Compose compiler behavior, and are safe to disable (the first-party JetBrains Compose Gradle plugin also disables them).
- `onModifierChanged` callback in `Widget.Children` now receives the index and the `Widget` instance affected by the change.
- The package of 'konduit-protocol-host' changed to `dev.konduit.protocol.host`. This should not affect end-users as its types are mostly for internal use.
- The entire `konduit-yoga` artifact's public API has been annotated with an opt-in annotation indicating that it's only for Redwood internal use and is not stable.
- Revert: Don't block touch events to non-subviews below a `Row`, `Column`, or `Box` in the iOS `UIView` implementation. This matches the behavior of the Android View and Compose UI implementations.
- The generated "widget factories" type (e.g., `MySchemaWidgetFactories`) is now called a "widget system" (e.g., `MySchemaWidgetSystem`). Sometimes it was also referred to as a "provider" in parameter names. A `@Deprecated typealias` is generated for now, but will be removed in the future.
- The package names of some generated code has changed. Deprecated `typealias`es are generated in the old locations for public types and functions, but those will be removed in the next release.
  - Testing code is now under `your.package.testing`.
  - Protocol guest code is now under `your.package.protocol.guest`.
  - Protocol host code is now under `your.package.protocol.host`.
- The 'dev.konduit.generator.compose.protocol' and 'dev.konduit.generator.widget.protocol' Gradle plugins are now deprecated and will be removed in the next release. Use 'dev.konduit.generator.protocol.guest' and 'dev.konduit.generator.protocol.host', respectively.
- The 'konduit-tooling-codegen' CLI flags for protocol codegen have changed from `--compose-protocol` and `--widget-protocol` to `--protocol-guest` and `--protocol-host`, respectively.
- Entrypoints to the protocol on the host-side and guest-side now require supplying the version of Redwood in use on the other side in order to ensure compatibility and work around any bugs in older versions. This uses a new `RedwoodVersion` type, and will be automatically wired if using our Treehouse artifacts.

Fixed:
- Fix failure to release JS resources when calling `CoroutineScope` is being cancelled
- JVM targets now correctly link against Java 8 APIs. Previously they produced Java 8 bytecode, but linked against the compile JDK's APIs (21). This allowed linking against newer APIs that might not exist on older runtimes, which is no longer possible. Android targets which also produce Java 8 bytecode were not affected.
- Fix the `View` implementation of `Box` to wrap its width and height by default. This matches the behavior of the `UIView` implementation and all other layout widgets.
- Fix the `UIView` implementation of `Box` not updating when some of its parameters are changed.
- Fix `Modifier.size` not being applied to children inside a `Box`.
- Fix `Margin` not being applied to the `UIView` implementation of `Box`.
- The `View` implementation of `Box` now applies start/end margins correctly in RTL, and does not crash if set before the native view was attached.
- Fix the backgroundColor for `UIViewLazyList` to be transparent. This matches the behavior of the other `LazyList` platform implementations.
- Fix `TreehouseUIView` to size itself according to the size of its subview.
- In `UIViewLazyList`, adding `beginUpdates`/`endUpdates` calls to `insertRows`/`deleteRows`, and wrapping changes in `UIView.performWithoutAnimation` blocks.
- Fix memory leak in 'protocol-guest' and 'protocol-host' where child nodes beneath a removed node were incorrectly retained in an internal map indefinitely. The guest protocol code has been updated to work around this memory leak when deployed to old hosts by sending individual remove operations for each node in the subtree.
- Ensure that Zipline services are not closed prematurely when disposing a Treehouse UI.
- In `UIViewLazyList`, don't remove subviews from hierarchy during `prepareForReuse` call

This version works with Kotlin 1.9.23 by default.


## [0.9.0] - 2024-02-28
[0.9.0]: https://github.com/cashapp/redwood/releases/tag/0.9.0

Changed:
- Added `Modifier` parameter to `RedwoodContent` which is applied to the root `Box` into which content is rendered (https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#elements-accept-and-respect-a-modifier-parameter).
- The parameter order of `LazyRow` and `LazyColumn` have changed to reflect Compose best practices (https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#elements-accept-and-respect-a-modifier-parameter).
- The parameter order of `TreehouseContent` has changed to reflect Compose best practices (https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#elements-accept-and-respect-a-modifier-parameter).
- The render function of `ComposeWidgetChildren` has been renamed to `Render` to reflect Compose best practices (https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#naming-unit-composable-functions-as-entities).
- Disable decoy generation for JS target to make compatible with JetBrains Compose 1.6. This is an ABI-breaking change, so all Compose-based libraries targeting JS will also need to have been recompiled.

Fixed:
- Don't block touch events to non-subviews below a `Row`, `Column`, or `Box` in the iOS `UIView` implementation. This matches the behavior of the Android View and Compose UI implementations.

This version works with Kotlin 1.9.22 by default.


## [0.8.0] - 2024-02-22
[0.8.0]: https://github.com/cashapp/redwood/releases/tag/0.8.0

New:
- `flex(double)` modifier for layouts which acts as a weight along the main axis.
- Allow reserving widget, modifier, property, and children tags in the schema. This can be used to document old items which no longer exist and prevent their values from accidentally being reused.
- Add `dangerZone { }` DSL to the `redwood { }` Gradle extension which allows enabling Compose reports and metrics. Currently these features break build caching as Compose forces the use of absolute paths in the Kotlin compiler arguments when in use (hence why they're marked as dangerous).
- `BackHandler` composable provides a callback for handling hardware back affordances (currently only on Android).
- Expose `frameClock` on `StandardAppLifecycle` to allow monitoring host frames.
- `CodeListener.onUncaughtException` notifies of any uncaught exceptions which occur in Treehouse guest code.
- Preview: Add `Box` widget which stacks children on top of each other. This is currently only implemented for Android views and iOS UIKit.
- Support `rememberSaveable` in plain Redwood compositions.
- Programmatic scrolls on `LazyListState` can now set `animated=true` for an animated scroll.
- Add `ziplineCreated`, `manifestReady`, and `codeLoadSkippedNotFresh` event callbacks to Treehouse `EventListener`.

Changed:
- The Treehouse Zipline disk cache directory is no longer within the cache directory on Android. This ensures it can't be cleared while the app is running. Zipline automatically constrains the directory to a maximum size so old entires will still be purged automatically.
- Set the Zipline thread's stack size to 8MiB on Android to match iOS.
- Use `margin-inline-start` and `margin-inline-end` for the start and end margin, respectively, for the HTML DOM layout bindings.
- `TestRedwoodComposition` now accepts only the initial `UiConfiuration` and exposes a `MutableStateFlow` for changing its value over time.
- `TreehouseLayout` now defines a default ID to allow state saving and restoration to work. Note that this will only work when a single instance is present in the hierarchy. If you have multiple, supply your own unique IDs.
- Emoji Search sample applications now bundle the latest guest code at compile-time and do not require the server running to work.
- The built-in `RedwoodView` for `HTMLElement` now reports density changes to the `UiConfiguration`.
- Redwood protocol modules have been renamed to 'guest' and 'host' to match Treehouse conventions.
- Suppress deprecation warnings in generated code. This code often refers to user types which may be deprecated, and should not cause additional warnings.
- `TreehouseAppContent.preload` is now idempotent.
- `LazyList` on iOS has changed from `UICollectionView` to `UITableView`, and changes to the backing data are now reported granularly rather than reloading everything.
- Allow arbitrary serializable content within `rememberSaveable` inside Treehouse.
- Add a `TreehouseApp` argument to `CodeListener`. Combined with the new uncaught exception callback, this provides an easy way to restart a Treehouse application on a crash.
- `EventListener.Factory` instances are now supplied as part of a `TreehouseApp` instead of a `TreehouseAppFactory`. This more closely scopes them with the lifetime of the `Zipline` instance.

Fixed:
- Ensure changes to modifiers notify their parent widget when using Treehouse.
- Explicitly mark the generated scope objects as `@Stable` to prevent needless recomposition.
- Dispose the old composition when the `RedwoodContent` composable recomposes or is removed from the composition.
- Ensure `UIViewChildren` indexes children using `typedArrangedSubviews` when removing views from a `UIStackView`.
- Correctly parse `data object` modifiers in the schema.
- Remember the default `CodeListener` for `TreehouseContent` to avoid unneccessary recomposition on creation.
- When calling `TreehouseUi.start`, fall back to older API signature when newer one does not match. This is needed because an addiitonal parameter was added in newer versions, but older guest code may have the old signature.
- Persist saved values from Treehouse without jumping back to the UI thread which allows proper restoration after a config change.
- Reset the requested widths and heights of a layout in the underlying Yoga engine when the size is invalidated. This ensures that the engine will properly measure changed content the grows and shrinks in either dimension.

This version works with Kotlin 1.9.22 by default.


## [0.7.0] - 2023-09-13
[0.7.0]: https://github.com/cashapp/redwood/releases/tag/0.7.0

New:
- Expose viewport size and density in `UiConfiguration`.
- `RedwoodView` and platform-specific subtypes provide a turnkey view into which a
  `RedwoodComposition` can be rendered. `TreehouseView` now extends `RedwoodView`.

Changed:
- Remove support for the Kotlin/JS plugin (`org.jetbrains.kotlin.js`). This plugin is deprecated
  and projects should be migrated to Kotlin multiplatform plugin (`org.jetbrains.kotlin.multiplatform`).
- Some `TreehouseView` subtypes were renamed to better match platform conventions:
  - `TreehouseWidgetView` is now `TreehouseLayout` for Android.
  - `TreehouseUIKitView` is now `TreehouseUIView` for iOS.
- `UIViewChildren` now supports `UIStackView` automatically.
- Package name of types in 'lazylayout-dom' artifact is now `lazylayout` instead of just `layout`.

This version works with Kotlin 1.9.10 by default.


## [0.6.0] - 2023-08-10
[0.6.0]: https://github.com/cashapp/redwood/releases/tag/0.6.0

New:
- Support for specifying custom Compose compiler versions. This will allow you to use the latest
  version of Redwood with newer versions of Kotlin than it explicitly supports.

  See [the README](https://github.com/cashapp/redwood/#custom-compose-compiler) for more information.
- `LazyList` can now be programmatically scrolled through its `ScrollItemIndex` parameter.
- Pull-to-refresh indicator color on `LazyList` is now customizable through
  `pullRefreshContentColor` parameter.

Changes:
- Many public types have been migrated away from `data class` to regular classes with
  `equals`/`hashCode`/`toString()`. If you were relying on destructuring or `copy()` for these
  types you will need to migrate to doing this manually.

Fix:
- The emoji search browser sample no longer crashes on first load.
- Lots of rendering and performance fixes for UIKit version of `LazyList`
  - Only measure items which are visible in the active viewport.
  - Remove some default item spacing imposed by the backing `UICollectionViewFlowLayout`.
  - Share most of the internal bookkeeping logic with the Android implementations for consistency
    and correctness.
  - Placeholders are now correctly sized along the main axis.

This version works with Kotlin 1.9.0 by default.


## [0.5.0] - 2023-07-05
[0.5.0]: https://github.com/cashapp/redwood/releases/tag/0.5.0

This release marks Redwood's "beta" period which provides slightly more stability guarantees than
before. All future releases up to (but NOT including) 1.0 will have protocol and service
compatibility with older versions. In practice, what this means is that you can use Redwood 0.6
(and beyond) to compile and deploy Treehouse guest code which will run inside a Treehouse host
from Redwood 0.5.

Redwood still reserves the right to make binary- and source-incompatible changes within the host
code or within the guest code.

New:
- The relevant tags and names from your schema will now automatically be tracked in an API file and
  changes will be validated to be backwards-compatible. The `redwoodApiGenerate` Gradle task will
  generate or update the file, and the `redwoodApiCheck` task will validate the current schema as
  part of the `check` lifecycle task.
- `width`, `height`, and `size` modifiers allow precise control over widget size within
  Redwood layout.
- Preliminary support for `rememberSaveable` within Treehouse guest code with persistence only
  available on Android hosts.

Changes:
- The flexbox implementation has changed from being a Kotlin port of the Google's Java flexbox
  layout to using Facebook's Yoga library.
- `LazyList` now has arguments for `margin` and cross-axis alignment
  (`verticalAlignment` for `LazyRow`, `horizontalAlignment` for `LazyColumn`)
- Remove the ability to use custom implementations of `LazyList`. Any missing functionality from
  the built-in versions should be filed as a feature request.
- The command-line tools (codegen, lint, schema) are now uploaded to Maven Central as standalone
  zip files in addition to each regular jar artifact for use with non-Gradle build systems.

Fixed:
- RTL layout direction is now supported by the Compose UI and View-based implementations of
  Redwood layout.

This version only works with Kotlin 1.8.22.


## [0.4.0] - 2023-06-09
[0.4.0]: https://github.com/cashapp/redwood/releases/tag/0.4.0

New:
- Experimental support for refresh indicators on `LazyRow` and `LazyColumn` via `refreshing` boolean
  and `onRefresh` lambda. These are experimental because we expect refresh support to migrate to
  some kind of future support for widget decorators so that it can be applied to any widget.
- `DisplayLinkClock` is available for iOS and MacOS users of Redwood.
  (Treehouse already had a frame clock for iOS).
- A `WidgetValue` (or `List<WidgetValue>`) produced from the generated testing function's
  `awaitSnapshot()` can now be converted to a `SnapshotChangeList` which can be serialized to JSON.
  That JSON can then later be deserialized and applied to a `TreehouseView` to recreate a full view
  hierarchy from any state. This is useful for unit testing widget implementations, screenshot
  testing, and more.
- Widget implementations can implement the `ChangeListener` interface to receive an `onEndChanges()`
  callback which occurs after all property or event lambda changes in that batch. This can help
  reduce thrashing in response to changes to multiple properties or event lambdas at once.
- `LazyRow` and `LazyColumn` now support a `placeholder` composable slot which will be used with
  Treehouse when a new item is displayed but before its content has loaded. Additionally, the size
  of these widgets can now be controlled through `width` and `height` constraints.

Changes:
- `LayoutModifier` has been renamed to `Modifier`.
- UI primitives like `Dp`, `Density`, and `Margin` have moved from Treehouse into the Redwood
  runtime (in the `dev.konduit.ui` package).
- `HostConfiguration` has moved from Treehouse into the Redwood runtime (in the
  `dev.konduit.ui` package) and is now called `UiConfiguration`.
- Composables running in Treehouse now run on a background thread on iOS. Previously they were
  running on the main thread. Interactions with UIKit still occur on the main thread.
- `RedwoodContent` function for hosting a Redwood composable within Compose UI has moved into a new
  `konduit-composeui` artifact as it will soon require a Compose UI dependency.
- The generated testing function now returns the value which was returned from the testing lambda.

  Before:
  ```kotlin
  suspend fun ExampleTester(body: suspend TestRedwoodComposition.() -> Unit)
  ```

  Now:
  ```kotlin
  suspend fun <R> ExampleTester(body: suspend TestRedwoodComposition.() -> R): R
  ```
- The Redwood and Treehouse frame clocks now send actual values for the frame time instead of 0.

Fixed:
- Widgets which accept nullable lambdas for events now receive an initial `null` value when no
  lambda is set. Previously a `null` would only be seen after a non-`null` lambda.
- Reduce binary impact of each widget's composable function by eliminating a large error string
  generated by the Kotlin compiler for an error case whose occurrence was impossible.
- The iOS implementation of `Row`, `Column`, `Spacer`, and `UIViewChildren` now react to size and
  child view changes more accurately according to UIKit norms.

This version only works with Kotlin 1.8.20.


## [0.3.0] - 2023-05-15
[0.3.0]: https://github.com/cashapp/redwood/releases/tag/0.3.0

New:

- Support for testing Composables with new test-specific code generation. Use the
  'dev.konduit.generator.testing' plugin to generate a lambda-accepting entrypoint function
  (such as `ExampleTester()`). Inside the lambda you can await snapshots of the values which
  would be bound to the UI widgets at that time.
- Redwood Layout now contains a `Spacer` which can be used to create negative space separately
  from padding (which otherwise disappears when the item disappears).
- The host's safe area insets are now included in `HostConfiguration`. Note that these are global
  values which should only be applied when a view is known to be occupying the full window size.
- Use the host's native frame rate to trigger recomposition inside of Treehouse. Pending snapshot
  changes are also required for recomposition to occur.

Changes:

- Widgets are now created, populated, and attached to the native view hierarchy in a different order
  than before. Previously widget was created, attached to its parent, and then its properties were
  all set followed by any language modifiers. Now, the widget is created, all of its properties and
  layout modifiers are set, and then it is added to its parent. Additionally, widgets are added to
  their parents in a bottom-up manner. Code like `Row { Column { Text } }` will see `Text` be added
  to `Column` before `Column` is added to `Row.
- 'konduit-treehouse' module has been split into '-shared', '-guest', and '-host' modules to
  more cleanly delineate where each is used. "Host" is the native application and "guess" is code
  running inside the Zipline JS VM.
- Schema dependencies are not longer parsed when loading a schema. Instead, a JSON representation
  is loaded from the classpath which contains the parsed structure of the dependency. As a result,
  the module which contains the schema files must apply the 'dev.konduit.schema' plugin in
  order to create this JSON.
- Redwood Layout's `Padding` type is now called `Margin`.
- Both Redwood's own API as well as code generated from your schema is now annotated with
  `@ObjCName` to create better-looking APIs in Objective-C (and Swift).
- The `@Deprecated` annotation on a widget or its properties will now propagate into the generated
  Composable and widget interface.
- Event types are no longer always nullable. They will now respect the nullability in the schema.
- Layout modifiers are now generated into a 'modifier' subpackage.

Fixed:

- Redwood Layout `Constraint`s are now correctly propagated into HTML.

This version only works with Kotlin 1.8.20.


## [0.2.1] - 2023-01-31
[0.2.1]: https://github.com/cashapp/redwood/releases/tag/0.2.1

Changed:
- Do not use a `ScrollView`/`HorizontalScrollView` as the parent container for View-based `Row` and
  `Column` display when the container is not scrollable (the default). Use a `FrameLayout` instead.

Fixed:
- Actually publish the `konduit-treehouse-composeui` artifact.

This version only works with Kotlin 1.7.20.


## [0.2.0] - 2023-01-30
[0.2.0]: https://github.com/cashapp/redwood/releases/tag/0.2.0

New:
- `konduit-layout-dom` module provides HTML implementations of `Row` and `Column`.
- Lazy layout's schema artifacts are now published and can be used by other projects.
- Expose `concurrentDownloads` parameter for `TreehouseApp.Factory`. The default is 8.
- Add `moduleLoadStart` and `moduleLoadEnd` events to Treehouse's `EventListener`.

Changed:
- Compile with Android API 33.
- Counter sample now uses shared `Row` and `Column` layouts rather than its own unspecified one.
- JSON serialization on the Compose-side of Treehouse is now faster and emits dramatically
  less code than before.
- Create a dedicated `CoroutineScope` for each `TreehouseView`. When a view leaves, its coroutines
  can now be immediately canceled without waiting for anything on the application-side.
- `TreehouseLauncher` is now called `TreehouseApp.Factory`. Additionally, when you `create()` a
  `TreehouseApp` from a factory you must also call `start()` for it to actually start.
- Use platform-specific collections types in JS for the Compose-side of Treehouse. This is faster,
  more memory-efficient, and produces less code.
- Update to Zipline 0.9.15.

Fixed:
- Do not expose Gradle `Configuration`s created by our plugin. This ensures they are not candidates
  for downstream modules to match against when declaring a dependency on a project using the plugin.
- Change when the Treehouse `FrameClock` is closed to avoid crashing on updates.

This version only works with Kotlin 1.7.20.


## [0.1.0] - 2022-12-23
[0.1.0]: https://github.com/cashapp/redwood/releases/tag/0.1.0

Initial release.

This version only works with Kotlin 1.7.20.
