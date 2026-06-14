# Known Keliver / Zipline bugs

> This document was originally written in the
> [ServerDrivenUI](https://github.com/waliasanchit007/ServerDrivenUI)
> reference integration. It now lives in the Keliver fork so docs
> travel with the artifact. Cross-references to "the integration" or
> "DevoStatus" point back to that reference repo and the production
> app it drives.

Surfaced by the DevoStatus integration (real Android Compose app
consuming Keliver as a Maven library + git submodule during early
adoption). Each entry lists symptom, reproduction, current workaround,
and what an upstream fix would look like.

Entries are split into two sections:

- **Upstream-only** — the fix has to land in the Keliver fork
  (`waliasanchit007/keliver`, this repo) or in Zipline itself. The
  integration ships a workaround; the doc keeps a record so future
  readers understand *why* the workaround is there.
- **Actionable in the integration** — anything that could be fixed
  in a downstream consumer (schema definitions, host wiring, guest
  composables). When fixed there it moves to ServerDrivenUI's
  `docs/CHANGELOG.md`.

Resolved bugs live in two places:
- **Keliver-side fixes** — this fork's [`CHANGELOG.md`](../CHANGELOG.md)
  (release notes for `1.0.0-caliclan.N`).
- **Integration-side fixes** — ServerDrivenUI's
  [`docs/CHANGELOG.md`](https://github.com/waliasanchit007/ServerDrivenUI/blob/keliver-main/docs/CHANGELOG.md).

> Treat this file as the punch list. When a bug becomes fixed, move it
> to `CHANGELOG.md` with the fixing commit.

---

## Upstream-only

These cannot be fixed without modifying Keliver (the
`waliasanchit007/keliver` fork that this repo consumes as a Maven
dependency) or Zipline. Workarounds are in place; documenting the
shape so a future upstream PR can pick them up.

### U1. `suspend` `ZiplineService` methods returning `List<@Serializable T>` hang `bind<>()`

**Severity:** high (silent failure mode; integrators give up before
finding the workaround).
**Mitigation shipped in Keliver `1.0.0-caliclan.3`:**
`Spec.bindWithTimeout { … }` turns the silent hang into a clear
`ZiplineBindTimeoutException` after 30s (default — configurable). The
exception message names the suspect signature shape and points at this
KNOWN_BUGS entry, so an integrator sees an actionable error instead of
giving up on a frozen build. The root cause still lives upstream in
Zipline's compiler plugin.

**Symptom.** Declaring a `ZiplineService` method as
`suspend fun foo(...): List<MySerializable>` causes the host's
`zipline.bind<MyService>("name", impl)` call to hang indefinitely. No
exception is thrown, no log line is emitted. The hang happens *before*
the guest ever calls the method — it's a bind-time problem.

**Reproduce.**
```kotlin
// shared/Protocol.kt
@Serializable
data class Quote(val id: String, val text: String)

interface HostQuotesProvider : ZiplineService {
    suspend fun getQuotes(filter: String?): List<Quote>  // ← hangs
}

// host
override suspend fun bindServices(treehouseApp: ..., zipline: Zipline) {
    zipline.bind<HostQuotesProvider>("quotes", impl)
    Log.d("…", "bound")  // ← never logged
}
```

Removing `suspend` (`fun getQuotes(...): List<Quote>`) resolves the
hang immediately.

**Empirically reproduced** on Keliver / Zipline 1.26 (commit
`0d18809` in this repo). Not investigated for whether the issue is in
Zipline's compiler plugin codegen or the host-side proxy construction.

**Workaround in place.** Keep `getQuotes` non-suspend; have the host
pre-cache the data before binding. See `HostQuotesProvider`'s kdoc and
Step 4½ in `USAGE.md`. Used throughout shared/Protocol.kt.

**Diagnostic shipped (Keliver `1.0.0-caliclan.3`):** wrap suspect
`bind`/`take` calls in `Spec.bindWithTimeout { … }`. When the hang
triggers, the timeout fires after 30s with `ZiplineBindTimeoutException`
whose message names the suspect signature shape. Strictly an upgrade
over "build hangs forever, no log." DevoStatus's `KeliverDemoScreen.kt`
uses this pattern; `KeliverQuotesScreen.kt` and `KeliverExploreScreen.kt`
can adopt it incrementally.

**Upstream fix.** Investigate the Zipline 1.26 compiler-plugin codegen
for `suspend fun … : List<@Serializable T>` signatures. A workaround
inside Zipline could be: detect the offending shape and either
(a) compile through it correctly, or (b) emit a build-time error so
integrators see a clear "this shape is unsupported" message instead of
a silent runtime hang.

**Workaround code paths to revert** once fixed:
- `shared/Protocol.kt#HostQuotesProvider.getQuotes`
- `presenter/screens/QuotesScreen.kt` (the snapshot-based fetch pattern)
- DevoStatus's `KeliverQuotesScreen.kt` load-gate (the
  `if (nativeQuotes.isEmpty()) { spinner } else { keliver }` wrapper)

**What about `Flow<T>` return types?** Zipline natively serializes
`kotlinx.coroutines.flow.Flow<T>` via its `FlowSerializer`, so a
**non-suspend** method that returns a `Flow<T>` does NOT trip this hang:

```kotlin
// Safe shape on Zipline 1.26 — non-suspend, Flow return.
fun observe(filter: String?): Flow<List<Quote>>
```

The bad shape is specifically `suspend fun` returning a collection —
not the `Flow<T>` envelope itself. The Flow alternative to the
Observer-callback pattern is documented in `USAGE.md` § "Reactive
data — Flow<T> vs Observer callbacks". `suspend fun observe():
Flow<T>` is closer to U1's hang shape and should be wrapped in
`Spec.bindWithTimeout { … }`.

---

### U2. Zipline Gradle plugin is mandatory on every module that calls `bind`/`take`, silently hangs otherwise

**Severity:** high (silent failure; same "give up" outcome).
**Mitigation shipped in Keliver `1.0.0-caliclan.3`:**
`Spec.bindWithTimeout { … }` catches U2's hang shape too (same surface
as U1). The `ZiplineBindTimeoutException` message lists both U1 and U2
as candidates so the integrator knows to check the `plugins {}` block
in addition to the suspect-signature shape. `take` already throws a
clear "is the Zipline plugin configured?" error — only `bind` was
silent before.

**Symptom.** A module that calls `zipline.bind<Foo>(...)` or
`zipline.take<Foo>(...)` but doesn't apply the
`app.cash.zipline` Gradle plugin compiles successfully and links
successfully. At runtime, `bind` hangs forever and `take` throws
`"unexpected call to Zipline.take: is the Zipline plugin configured?"`.

**Reproduce.** New host module that depends on `:shared` but doesn't
add `alias(libs.plugins.zipline)` to its `plugins {}` block. Call
`zipline.bind<HostConsole>(...)` — never returns.

**Workaround in place.** Always apply the plugin in every module that
touches `bind`/`take`:

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.zipline)  // ← mandatory anywhere bind/take is called
}
```

Documented in `USAGE.md` Step 2 "⚠️ MANDATORY".

**Upstream fix.** The Zipline runtime could detect missing-plugin state
(no codegen artifacts on the classpath for the requested service) and
throw on `bind` instead of hanging. A lint/Detekt rule would also catch
this at compile time.

---

### U3. `kotlinx-serialization` plugin required on every module defining `@Serializable` wire types used by a `ZiplineService`

**Severity:** medium (runtime error has good message, but error fires
late in integration).
**Mitigation shipped in Keliver `1.0.0-caliclan.3`:**
`Spec.requireSerializerOf<T>()` is a bind-time pre-flight check that
throws `MissingSerializerException` with a clear diagnostic when a
`@Serializable` wire type's serializer can't be resolved. Move the
failure point from "first guest call" to "bind time" by calling
`requireSerializerOf<Quote>()` etc. at the top of `bindServices`.
DevoStatus's `KeliverQuotesScreen.kt` shows the pattern.

**Symptom.** Defining `@Serializable data class Quote(...)` in a module
that doesn't apply `org.jetbrains.kotlin.plugin.serialization` compiles
OK, but `zipline.take<HostQuotesProvider>("quotes")` (or the
corresponding `bind` on the host) throws at runtime:

```
Serializer for class 'Quote' is not found.
Please ensure that class is marked as '@Serializable' and that the
serialization compiler plugin is applied.
```

**Reproduce.** Add `@Serializable` to a data class in a module that has
only `alias(libs.plugins.kotlinMultiplatform)` — no `kotlinSerialization`.

**Workaround in place.** Apply the plugin in every module with wire
types:

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.zipline)
    alias(libs.plugins.kotlinSerialization)  // ← required for @Serializable wire types
}
```

DevoStatus hit this in `:shared` initially — fixed in upstream commit
`c73b04c` (see also the comment at the top of `shared/build.gradle.kts`).

**Upstream fix.** The Zipline Gradle plugin could check whether
`@Serializable` types appear in the module's classfiles and warn if the
kotlinSerialization plugin isn't also applied.

---

### U5. Coil 3's singleton `ImageLoader` has no network fetcher by default

**Severity:** low (was: already documented), now mitigated.
**Mitigation shipped in Keliver `1.0.0-caliclan.4`:**
`KeliverImage.installSingleton()` from the new `keliver-image` module
(re-exported through `keliver-host`) wires the platform-appropriate
network fetcher in one call — OkHttp on Android / JVM, Ktor 3 + the
Darwin engine on iOS. Adopters call it once near the top of their
root `@Composable` and `AsyncImage` works.

The earlier USAGE.md cheat sheet still applies as the symptom
reference. Adopters who want full control over the ImageLoader
configuration can pass `fetcher = { ... }` and `additional = { ... }`
blocks to override the default fetcher and tune cache policy,
interceptors, telemetry — the helper is opinionated about the
"works out of the box" path but not opinionated about everything
beyond that.

**Symptom.** A schema `AsyncImage` with an `http://…` URL renders blank.
No exception, no log line. Looks like the schema widget is broken.

**Cause.** Coil 3 ships with an empty default `ImageLoader`. The
`coil-network-okhttp` (or `coil-network-ktor3` / `coil-network-ktor2`)
artifact adds a network fetcher, but the integrator has to call
`setSingletonImageLoaderFactory { … }` before any `AsyncImage` is
composed.

**Workaround in place.** See `USAGE.md` "⚠️ If you use AsyncImage"
callout. This repo's `:composeApp` `App.kt` calls
`setSingletonImageLoaderFactory` with the right platform fetcher; each
DevoStatus screen (`KeliverDemoScreen`, `KeliverQuotesScreen`,
`KeliverExploreScreen`) does the same.

**Upstream fix.** Keliver could supply a default
`setSingletonImageLoaderFactory` call from within
`TreehouseApp`'s composeui wrapper, with an `okhttp` fetcher when the
integrator's classpath has OkHttp (detect at build time, or fall back
to a no-op + clear warning). Or document this in a way that's
impossible to miss — a startup-time println if the singleton hasn't
been initialized would help.

---

### U6. ~~Keliver codegen emits invalid Kotlin for lambda-typed `@Modifier` properties on Kotlin/JS~~ — FIXED in Keliver `1.0.0-caliclan.3`

**Status:** Fixed. Keliver's schema parser now rejects function-typed
`@Modifier` properties at build time with a clear error message that
points integrators at the canonical workaround (put click handlers on
widgets, not modifiers). The cryptic "expecting class body" error in
generated `:shared-protocol-guest:compileKotlinJs` output can no longer
happen — the bad shape is caught at `:schema:redwoodJsonGenerate` time
with a message like:

```
@Modifier com.example.MyMod#onClick cannot be a function type.
Keliver codegen for lambda-typed modifier properties is broken on
Kotlin/JS — the generated `ContextualSerializer(Function0<Unit>::class)`
is invalid Kotlin syntax and breaks `:shared-protocol-guest:compileKotlinJs`.
Move the handler onto the widget as a regular `@Property` instead
(see Keliver's `Button.onClick` / `Box.onClick` for the canonical shape).
```

Historical entry preserved below for context.

<details>
<summary>Original entry</summary>

**Severity:** high (the build silently produces uncompilable codegen
output if you happen to add a `() -> Unit` field on a `@Modifier`).
**Origin:** HANDOVER.md gotcha #8.

**Symptom.** Declaring a function-typed property on a `@Modifier` data
class compiles fine on JVM but produces invalid Kotlin in the JS code-
gen output — `ContextualSerializer(Function0<Unit>::class)`, which is
not legal Kotlin syntax (class literal not allowed on a generic
parameterized type). The `:shared-protocol-guest:compileKotlinJs` task
fails with a cryptic "expecting class body" error pointing at generated
code the integrator didn't write.

**Reproduce.** Add this to the schema:
```kotlin
@Modifier(N)
data class Clickable(
    val onClick: () -> Unit,   // ← codegen breaks on JS
)
```

`compileKotlinJvm` succeeds. `:shared-protocol-guest:compileKotlinJs`
fails with an error on a generated file.

**Workaround in place.** Click handlers / lambdas live on the *widget*
that needs them, not on a modifier. Every Tier 1 / Tier 2 widget that
needs a click handler declares `onClick: (() -> Unit)?` as a regular
`@Property` (Button, IconButton, FAB, Box, Card all follow this). The
schema's `Box` and `Card` widgets carry `onClick` directly rather than
relying on a `Modifier.clickable {}` chain.

**Upstream fix.** Keliver's `dev.keliver.generator.modifiers` plugin
should special-case function-typed properties on `@Modifier` — either
generate a `ZiplineService`-backed proxy (matching the U11 fix pattern)
or emit a compile-time error so integrators see the rejection upfront
rather than discover it through a broken JS codegen output.

</details>

---

### U10. ~~Keliver codegen emits `ContextualSerializer(MyEnum::class)` for enum fields on `@Modifier` classes — silent white screen~~ — FIXED in Keliver `1.0.0-caliclan.3`

**Status:** Fixed in Keliver commit `79a314004`
(`keliver-tooling-codegen` protocol-guest generator). Modifier
serializer codegen now emits the `.serializer(), emptyArray()` fallback
for every non-parameterized `ClassName` typed property; the
`ContextualSerializer` falls through to the auto-generated `.serializer()`
companion so the white screen can no longer happen for `@Serializable
enum` modifier fields.

The `SduiSerializersModule` workaround in `:schema-types` is now
redundant but kept (the contextual registration is harmless when the
fallback already works; removing it has no observable effect).

Types that aren't `@Serializable` now produce a compile-time error
pointing at the missing `.serializer()` companion instead of a silent
runtime white screen — strictly better failure mode.

Historical entry preserved below for context.

<details>
<summary>Original entry</summary>

**Severity:** critical (worst documented failure mode — completely
silent, looks like the schema widget didn't render at all).
**Origin:** HANDOVER.md gotcha #10.

**Symptom.** With `@Modifier(N) data class Background(val color:
SchemaColor)`, the generated `BackgroundTagAndSerializer` includes
`ContextualSerializer(SchemaColor::class)`. At runtime the encode call
throws `SerializationException("Class 'SchemaColor' is not registered
for polymorphic serialization in the scope of 'Modifier'")`. Keliver's
protocol path swallows the exception silently; the batch of widget
updates never reaches the host; the host renders an empty
`TreehouseContent`; **the screen stays blank with zero logs**.

The signature of this failure: guest's compose composition runs to
completion (you can `println` from inside lambdas and see them) but
ZERO `factory.X()` calls happen on the host side.

**Reproduce.** Add an enum-typed property to any `@Modifier` data
class. Do NOT register a contextual serializer for that enum. Run the
guest. Host TreehouseContent stays blank, no exception in any log.

**Workaround in place.** Define a shared
`SerializersModule` (`SduiSerializersModule` in `:schema-types`) that
registers each enum used in a `@Modifier` as a contextual serializer:

```kotlin
public val SduiSerializersModule: SerializersModule = SerializersModule {
    contextual(SchemaColor::class, SchemaColor.serializer())
    // Add more contextuals as new enums get used in @Modifier fields.
}
```

Then wire it into both sides:
- Host: every `TreehouseApp.Spec` overrides
  `val serializersModule = SduiSerializersModule`.
- Guest: `StandardAppLifecycle(json = Json { serializersModule = SduiSerializersModule })`.

Enums used only as widget `@Property` (not modifier fields) work fine —
codegen calls `MyEnum.serializer()` directly there. Only modifier
fields trigger the contextual codegen.

> **Cost:** new enums added to a `@Modifier` need to be remembered to
> register in `SduiSerializersModule`. Forgetting reproduces the white
> screen for that one new modifier — fix-by-omission is silent. This is
> the highest-risk knowledge-debt on the integration.

**Upstream fix.** Two options:
1. **Change codegen** — Keliver's modifier generator could detect
   `@Serializable enum` types and emit `MyEnum.serializer()` directly,
   matching the @Property codegen. Removes the need for the
   contextual-registration dance entirely.
2. **Ship a baseline serializers module** — Keliver publishes a
   `KeliverDefaultSerializers` module covering all schema-types enums
   it ships, integrators add it to their own module. Reduces friction
   but still requires integrators to register their own additions.

Option (1) is the right fix.

</details>

---

### U11. `ZiplineService` methods with `(T) -> Unit` lambda parameters silently fail at runtime

**Severity:** high (build succeeds, `zipline.take<T>` returns
non-null-but-broken proxy, every method call is a silent no-op).
**Origin:** HANDOVER.md gotcha #11.
**Mitigation shipped in Keliver `1.0.0-caliclan.4`:** the
`dev.keliver.zipline-shapes` Gradle plugin (in
`keliver-gradle-plugin`). Adopters apply it to any module that
declares `ZiplineService` interfaces:

```kotlin
plugins {
    id("dev.keliver.zipline-shapes")
}
```

The plugin auto-registers a `validateZiplineServiceShapes` task that
scans every `.kt` file under `src/`, fails the build with a clear
message pointing at this entry, and hooks into the `check`
lifecycle so it runs on every CI pass. Replaces the per-app inline
Gradle task that ServerDrivenUI used to ship — adopters now get the
check for free, no copy/paste.

The root cause is still upstream in Zipline's compiler plugin (which
accepts the bad signature shape but produces a runtime-broken proxy);
this lint just prevents the bad shape from ever reaching that code
path.

**Symptom.** Defining a `ZiplineService` interface method with a
function-typed parameter compiles fine. The host's `bind<T>` succeeds.
The guest's `take<T>(name)` doesn't throw — it returns a non-null proxy.
But the proxy's first method call silently no-ops; the host method body
never runs. Subsequent calls fail the same way. There's no exception
visible to the guest (the actual proxy-construction failure happens
before the host-console polyfill is installed, so the error println
goes to a dropped Zipline stdout).

**Reproduce.**
```kotlin
interface HostSnackbar : ZiplineService {
    fun showWithResult(message: String, onResult: (Boolean) -> Unit)
    //                                  ^^^^^^^^^^^^^^^^^^^^^^^^^^
    //                                  function-typed param → silent proxy
}
```

Host implements + binds normally. Guest calls
`HostSnackbarBridge.instance?.showWithResult("Saved", { ok -> … })`.
Host body doesn't fire. No log line.

**Cause.** Zipline marshals values across the QuickJS boundary in
exactly two flavors:
1. `@Serializable` values, or
2. `ZiplineService` proxies.

A raw function type is neither. Build succeeds because the Kotlin
compiler accepts the signature; the Zipline runtime proxy construction
fails before the guest's first call but the failure is unobservable.

**Workaround in place.** Replace the lambda parameter with a dedicated
`ZiplineService` callback type:

```kotlin
interface SnackbarResultCallback : ZiplineService {
    fun onResult(actionPerformed: Boolean)
}

interface HostSnackbar : ZiplineService {
    fun showWithResult(
        message: String,
        callback: SnackbarResultCallback,  // ← ZiplineService, not lambda
    )
}
```

The guest wraps the user's lambda in an anonymous `SnackbarResultCallback`
impl. The host calls `callback.onResult(...)` then `callback.close()`
exactly once (or the proxy leaks — `serviceLeaked` warning surfaces).

See `RealHostSnackbar` in `composeApp/Protocol.kt` for the canonical
shape.

**Upstream fix.** Zipline's compiler plugin should detect function-typed
parameters / returns on a `@interface ZiplineService` and either
(a) auto-generate the callback service wrapping at the boundary, or
(b) emit a build-time error so integrators see the rejection upfront
rather than discover it through a silent runtime no-op.

---

### U7. ~~`TreehouseApp.Spec` services held as anonymous inline references get GC'd~~ — FIXED in Keliver `1.0.0-caliclan.3`

**Status:** Fixed in commit `<TBD-keliver-hash>` via
`TreehouseApp.Spec.retain()`. The `val`/`lateinit var` workaround
documented previously still works; `retain()` is just the cleaner
shape when the service is an inline anonymous object.

**Use:**
```kotlin
val spec = object : TreehouseApp.Spec<…>() {
    override suspend fun bindServices(treehouseApp, zipline) {
        zipline.bind<HostConsole>("console", retain(object : HostConsole {
            override fun log(message: String) = println(message)
        }))
    }
}
```

`retain(service)` returns the service unchanged but adds it to an
internal strong-ref list on the Spec. Equivalent semantics to holding
the service as a `val` field of the Spec, but easier to remember when
you're tempted to inline an anon object.

Historical entry preserved below for context.

<details>
<summary>Original entry</summary>

**Severity:** medium (documented in code as "gotcha #6", but trips
every new integrator on the first try).

**Symptom.** Inline `bind` such as
`zipline.bind<HostConsole>("console", object : HostConsole { … })`
binds successfully, then the first guest call to the service errors
with `no such service (service closed?)`.

**Cause.** Keliver's leak detector logs `serviceLeaked` events
("invoked when a service is garbage collected without being closed").
The anonymous instance becomes GC-eligible the moment `bindServices`
returns; the host's underlying weak reference gets cleared before the
guest's first call.

**Workaround (still valid).** Hold each service as a `val` or
`lateinit var` property of the `Spec` (its lifetime survives the GC
pressure that anon instances don't):

```kotlin
val spec = object : TreehouseApp.Spec<…>() {
    private val hostConsole = MyHostConsole()   // ← strong ref
    private lateinit var hostSnackbar: RealHostSnackbar

    override suspend fun bindServices(...) {
        zipline.bind<HostConsole>("console", hostConsole)
        hostSnackbar = RealHostSnackbar(...)
        zipline.bind<HostSnackbar>("snackbar", hostSnackbar)
    }
}
```
</details>

---

### U12. `AppService` subinterfaces need a hand-rolled `Adapter` — Zipline IR plugin can't generate one

**Symptom.** First-launch of any Keliver app crashes at QuickJS
load time with:

```
codeLoadFailed: Constructor 'Adapter.<init>' can not be called:
  No constructor found for symbol 'your.pkg/YourAppService.Companion.Adapter
    .<init>|<init>(kotlin.collections.List<kotlinx.serialization.KSerializer<*>>;
    kotlin.String){}[0]'
```

The error fires on `Spec.create { zipline.take("app") }` even though
the Zipline + kotlinSerialization plugins are correctly applied to
the module that defines the service interface. Bundle download,
parse, and `mainFunctionStart`/`End` all succeed; the failure is
at the `take` step where the host expects to find a generated
adapter class on `YourAppService.Companion`.

**Root cause.** [Zipline issue #765](https://github.com/cashapp/zipline/issues/765).
The Zipline IR plugin **cannot** auto-generate `ZiplineServiceAdapter`
classes for interfaces that transitively extend `ZiplineService`
through Keliver's `AppService`. The class doesn't get emitted with
the constructor shape that Zipline's loader expects at link time;
the IR linker rejects it.

The `AppService.kt` source ([keliver-treehouse](https://github.com/waliasanchit007/keliver/blob/main/keliver-treehouse/src/commonMain/kotlin/dev/keliver/treehouse/AppService.kt))
even calls this out:

> Note that due to a Zipline limitation it's necessary for
> implementing classes to declare a direct dependency on
> [ZiplineService]. https://github.com/cashapp/zipline/issues/765

But that comment understates the impact — both the *interface
declaration* and the *consuming host's take site* hit this. The
interface needs a manual `Adapter`, not just the impl.

**Workaround.** Hand-roll the adapter. The pattern, from
`sample/shared/.../ManualSampleAppServiceAdapter.kt`:

```kotlin
@file:Suppress(
  "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE",
  "CANNOT_OVERRIDE_INVISIBLE_MEMBER", "EXPOSED_SUPER_CLASS",
  "EXPOSED_PARAMETER_TYPE", "EXPOSED_FUNCTION_RETURN_TYPE",
)
package your.pkg

import app.cash.zipline.internal.bridge.OutboundCallHandler
import app.cash.zipline.internal.bridge.OutboundService
import app.cash.zipline.internal.bridge.ReturningZiplineFunction
import app.cash.zipline.internal.bridge.ZiplineServiceAdapter
import app.cash.zipline.ziplineServiceSerializer
// ...

internal open class ManualYourAppServiceAdapter(
  override val serializers: List<KSerializer<*>>,
  override val serialName: String = "your.pkg.YourAppService",
) : ZiplineServiceAdapter<YourAppService>() {
  override val simpleName: String = "YourAppService"

  override fun ziplineFunctions(
    serializersModule: SerializersModule,
  ): List<ZiplineFunction<YourAppService>> {
    // One ReturningZiplineFunction per method on the interface.
    // Function ids are positional — match the order in outboundService below.
    // ...
  }

  override fun outboundService(callHandler: OutboundCallHandler): YourAppService {
    return object : YourAppService, OutboundService {
      override val callHandler: OutboundCallHandler = callHandler
      // override every method with `callHandler.call(this, N)` where N matches
      // the position in ziplineFunctions() above.
    }
  }
}
```

Then on the interface itself:

```kotlin
interface YourAppService : AppService {
  fun launch(): ZiplineTreehouseUi
  // ... your other methods

  companion object {
    internal class Adapter(
      serializers: List<KSerializer<*>>,
      serialName: String,
    ) : ManualYourAppServiceAdapter(serializers, serialName)
  }
}
```

When you add a new method to `YourAppService`, add a matching
`ReturningZiplineFunction` block in `ziplineFunctions(...)` AND a
delegating override in `outboundService(...)`. The call-id-to-position
mapping must match between the two.

**Real-world examples.**

- [`keliver/sample/shared/.../ManualSampleAppServiceAdapter.kt`](../sample/shared/src/commonMain/kotlin/dev/keliver/sample/shared/ManualSampleAppServiceAdapter.kt)
  — minimum-viable: three methods (`launch`, `appLifecycle`,
  `close`), ~95 LoC.
- ServerDrivenUI's `ManualSduiAppServiceAdapter` — same pattern,
  same surface (because `SduiAppService` doesn't add methods
  beyond what AppService requires).

**Cost to adopters.** Down from ~95 LoC + 7-entry `@file:Suppress`
to **~5 LoC + zero `@file:Suppress`** as of Keliver caliclan.5 via
the [`keliver-treehouse-codegen`](../keliver-treehouse-codegen/)
KSP processor + [`@KeliverAppService`](../keliver-treehouse/src/commonMain/kotlin/dev/keliver/treehouse/KeliverAppService.kt)
annotation. Adopter writes a single companion-object wrapper:

```kotlin
@KeliverAppService
interface MyAppService : AppService {
  fun launch(): ZiplineTreehouseUi

  companion object {
    internal class Adapter(
      serializers: List<KSerializer<*>>,
      serialName: String,
    ) : GeneratedMyAppServiceAdapter(serializers, serialName)
  }
}
```

The 5-line companion wrapper is the only piece KSP can't generate
— Zipline IR looks up `<Interface>.Companion.Adapter` by name at
code-load time, and KSP can't inject members into an existing
companion. The full ~70-line adapter body (function table +
outbound proxy + serializer routing) lives in
`GeneratedMyAppServiceAdapter`, generated automatically.

**Keliver-side helper (shipped).** Two complementary pieces:

1. **`KeliverAppServiceAdapter<T>` runtime helper** (caliclan.5,
   in `keliver-treehouse`) — base class + helper functions that
   cut the manual workaround from ~95 to ~70 LoC for adopters
   who want to hand-roll the adapter. Documented below.

2. **`@KeliverAppService` + `keliver-treehouse-codegen` KSP
   processor** (caliclan.5) — emits `Generated<Name>Adapter`
   automatically. Adopter writes ~5 lines instead of ~70.

   **Required adopter config — KSP must be on the `-2.0.x` API
   line.** The codegen module is built against KSP 2.0. Using a
   `-1.0.x` KSP version triggers the misleading config-time
   error `ksp-<v>-1.0.x is too old for kotlin-2.3.10`. Known
   working: `2.1.20-2.0.1` (against Kotlin 2.1.x),
   `2.2.0-2.0.2` (against Kotlin 2.2.x). DevoStatus migration
   to this processor surfaced this requirement; without
   matching the API line the adopter sees the misleading
   error message rather than the actual incompatibility.

**Reference snippet (manual variant, for context).** Adopters
typically don't need this anymore — use the KSP processor above.
The manual shape stays documented because the KSP processor's
generated output is itself a `KeliverAppServiceAdapter<T>` subclass,
so adopters reading the generated source see exactly this pattern:

```kotlin
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package your.pkg

import dev.keliver.treehouse.KeliverAppServiceAdapter
import dev.keliver.treehouse.KeliverOutboundCallHandler
import dev.keliver.treehouse.KeliverOutboundService
import dev.keliver.treehouse.keliverReturningFunction

internal open class MyAppServiceAdapter(
  serializers: List<KSerializer<*>>,
  serialName: String = "your.pkg.MyAppService",
) : KeliverAppServiceAdapter<MyAppService>(serializers, serialName) {
  override val simpleName = "MyAppService"

  override fun ziplineFunctions(
    serializersModule: SerializersModule,
  ): List<ZiplineFunction<MyAppService>> = listOf(
    keliverReturningFunction<MyAppService>(
      id = "launch",
      signature = "fun launch(): dev.keliver.treehouse.ZiplineTreehouseUi",
      resultSerializer = ziplineServiceSerializer<ZiplineTreehouseUi>(),
      call = { it.launch() },
    ),
    // ...one keliverReturningFunction(...) per method on the interface
  )

  override fun outboundService(
    callHandler: KeliverOutboundCallHandler,
  ) = object : MyAppService, KeliverOutboundService {
    override val callHandler: KeliverOutboundCallHandler = callHandler
    override fun launch() = callHandler.call(this, 0) as ZiplineTreehouseUi
    // ...one override per method on the interface; call IDs are positional
  }
}
```

The remaining two `@file:Suppress` entries cover Kotlin's
visibility check at the use sites for `KeliverOutboundCallHandler`
and `KeliverOutboundService` — those typealias to Zipline's
`internal` types, which Kotlin checks at every reference (the
file-level Suppress can't be hoisted into the alias itself). This
is the smallest possible adopter footprint until Zipline #765
ships upstream.

**Future work.** Upstream resolution of [Zipline #765](https://github.com/cashapp/zipline/issues/765) —
once Zipline's IR plugin generates adapters for `AppService`
subinterfaces directly, the `@KeliverAppService` annotation +
companion wrapper become redundant. Keliver can then deprecate
the codegen module without breaking adopter code (the annotation
becomes a no-op).

**Owner.** Keliver. Helper + KSP processor shipped in caliclan.5;
adopter pain effectively eliminated.

---

### U8. Host `ZiplineService` method bodies execute on the Zipline dispatcher, not Main — UI touches silently no-op

**Severity:** high (silent failure on Android, possible crash on iOS K/N).
**Counterpart to gotcha #12** in `docs/HANDOVER.md`, which documents the
*outbound* direction. This entry covers the *inbound* direction.

**Symptom.** A guest call to a host `ZiplineService` method routes correctly
(host log line fires with the expected arguments) but the host-side side
effect — `NavController.navigate(...)`, `viewModel.someState = ...`, etc. —
never takes effect. The UI just doesn't react. There's no exception, no
warning. The handler runs to completion and exits silently.

**Reproduce.** Define any guest→host service whose method body touches the
UI or Compose state:

```kotlin
interface HostNavigator : ZiplineService {
    fun onItemSelected(id: String)
}

private class RealHostNavigator(
    private val navController: NavController,
) : HostNavigator {
    override fun onItemSelected(id: String) {
        Log.d("MyApp", "onItemSelected($id)")  // ← fires
        navController.navigate("detail/$id")    // ← silent no-op
    }
}
```

The log line appears. The navigation doesn't happen. The user sees nothing.

**Cause.** Zipline runs the host method body on its own thread-confined
dispatcher (the QuickJS thread, `treehouseApp.dispatchers.zipline`).
`NavController.navigate` requires the Main thread, as does any Compose
`MutableState` mutation. Calling them off-Main is a known Android
silent-failure pattern.

JVM-backed Zipline (Android) tolerates the wrong-thread state mutation
quietly. iOS Kotlin/Native may crash (we haven't reproduced this one on
iOS yet, but the symmetric outbound case in gotcha #12 does).

**Workaround in place.** Take a `CoroutineScope` AND the UI dispatcher
in the service's constructor, launch the side effect onto UI:

```kotlin
private class RealHostNavigator(
    private val scope: CoroutineScope,
    private val uiDispatcher: CoroutineDispatcher,  // ← TreehouseApp.dispatchers.ui
    private val navController: NavController,
) : HostNavigator {
    override fun onItemSelected(id: String) {
        Log.d("MyApp", "onItemSelected($id)")
        scope.launch(uiDispatcher) {
            navController.navigate("detail/$id")
        }
    }
}
```

Pass `activity.lifecycleScope` (Android) or a Main-dispatcher scope
(iOS) for `scope`, plus `treehouseApp.dispatchers.ui` for the
`uiDispatcher`. This is the *symmetric pair* to gotcha #12: the inbound
host method needs UI, the outbound guest-proxy call needs the Zipline
dispatcher.

> **Tip.** Prefer `treehouseApp.dispatchers.ui` over reaching for
> `Dispatchers.Main` directly. On Android JVM the two are equivalent,
> but on iOS K/N `dispatchers.ui` is the platform's UI dispatcher
> wrapped in a way that plays nicely with Keliver's threading model.
> See `TreehouseDispatchers` in keliver-treehouse-host — `ui` is
> already public API.

Because the dispatcher only exists after `bindServices` is called with
a `treehouseApp` reference, services that need it have to be
constructed inside `bindServices` (or capture the dispatcher later via
`lateinit var`). DevoStatus's `RealHostQuoteNavigator` +
`RealHostExploreNavigator` show the lateinit-var-in-Spec pattern.

**Upstream fix options.** Two ranked by impact:

1. **`@MainThread` annotation honored by Zipline codegen** — let the
   integrator mark a service method as needing UI, and the generated
   host stub does the dispatch hop. Lowest-friction; integrator never
   has to remember the threading rule.
2. **Doc + lint** — at minimum, surface this in `USAGE.md` next to
   gotcha #12 so the two directions appear as a pair. (`dispatchers.ui`
   exposure was option 2 from a previous version of this entry — it's
   already shipped in Keliver `1.0.0-caliclan.2`; the original entry
   was written before `TreehouseDispatchers.ui` was discovered.)

**Real-world incidence.** Bit DevoStatus's "Tap to create status"
button on the Quotes tab — `HostQuoteNavigator.onQuoteSelected` fired
on every tap, the host's callback received the right ID, but
`navController.navigate(...)` did nothing. Took a logcat audit to
confirm the call was reaching the host before realizing it was a
threading bug rather than a wiring bug.

---

## Actionable here

### U13. Inherited Redwood tests are quarantined — the shared `test-app` fixture was stripped

**What.** A May-2026 test-completeness audit found that `./gradlew
test` failed on a clean checkout, and that **no CI job ran tests at
all** (`ci.yml`, `compat-matrix.yml`, `publish.yml` all pass
`-x test`). Root cause: the Phase 1.5 fork strip removed upstream
Redwood's shared `test-app` fixture (which generates the
`com.example.redwood.testapp.*` schema), but ~22 inherited test
files across 7 modules still import it. They've been
non-compiling — and therefore providing zero coverage — since the
strip, hidden because CI never ran them.

**Affected modules + files (quarantined under `apps*Test` source
sets, opt-in behind `-PkeliverWithTestApp`):**
- `keliver-treehouse-host` — `appsJvmTest` (GuestLifecycleTest,
  LeaksTest, TreehouseTesterTest, leak/heap utils) — needs a live
  guest bundle via `TreehouseTester` (hardcoded `../test-app/...`
  path).
- `keliver-tooling-codegen` — `appsTest` (ModifierGenerationTest,
  WidgetProtocolGenerationTest).
- `keliver-testing`, `keliver-protocol-host`,
  `keliver-protocol-guest`, `keliver-compose` — `appsCommonTest`.
- `keliver-treehouse-guest` — `appsJsTest`.

**What was fixed in the audit (caliclan.5):**
1. Quarantined the 22 fixture-dependent files into gated
   `apps*Test` source dirs. This **recovered** the sibling tests
   that share those source sets (they couldn't compile while the
   broken files sat alongside them) — e.g. keliver-protocol-host's
   6 inline-schema tests, keliver-compose's 6, etc. now run.
2. Removed the dangling `:test-app:presenter-treehouse` task
   dependency that made `./gradlew test` fail outright.
3. Generated the 9 missing API baselines so `apiCheck` passes.
4. Wired `test` + `apiCheck` into `ci.yml` so this can't silently
   rot again.

**Restoration progress (caliclan.5, second pass).** The
`test-app:schema` fixture + its 6 codegen modules
(compose/widget/modifiers/protocol-host/protocol-guest/testing)
were recovered from git history (`8aaeb8898^`) and now build
against caliclan.5, gated behind `-PkeliverWithTestApp` in
`settings.gradle`. Recovering them surfaced + fixed a real latent
codegen bug — the modifier-serializer generator emitted a
non-resolving `.serializer()` for stdlib custom-types
(`kotlin.time.Duration`, `kotlin.UInt`); now routed through
`kotlinx.serialization.builtins`. See the `tooling-codegen` fix
(PR #64).

**RESOLVED (caliclan.5).** All 22 tests are revived and CI runs
them under `-PkeliverWithTestApp`:

- **Category-1 (14 tests)** — schema/codegen tests in
  `keliver-protocol-host` / `-guest` / `keliver-testing` /
  `keliver-compose` / `keliver-tooling-codegen`.
- **Category-2 (8 tests)** — `keliver-treehouse-host` `appsJvmTest`
  integration tests (GuestLifecycleTest, LeaksTest,
  TreehouseTesterTest, FindCycleTest, JvmHeapTest) that load a
  live Zipline guest bundle via `TreehouseTester`, plus
  `keliver-treehouse-guest`'s `jsTest`.

Phase 2 recovered `test-app:presenter` + `presenter-treehouse`
(the guest bundle) and surfaced two more fork-era misses, both
fixed:
1. `FakeTreehouseView.kt` (a testapp-coupled commonTest fixture)
   was stripped; recovered into `appsJvmTest`.
2. `leaks/JvmHeap.kt`'s heap-walker reflection allowlist still
   listed `app.cash` but not `dev.keliver` — a package-rename
   migration miss that made the leak tests error on
   `dev.keliver.treehouse.TreehouseTester$spec$1`. Added
   `dev.keliver`.

The presenter modules compiled against caliclan.5 with **no**
AppService/treehouse drift (the feared U12-adapter interaction
didn't materialize — the guest bundle uses the standard
`AppService` surface). The Kotlin/JS `yarn.lock` concern was a
non-issue for targeted `:module:jvmTest`/`:jsTest` tasks (only
the `build`/`check` aggregate triggers `kotlinStoreYarnLock`), so
CI runs targeted tasks.

Run the full inherited suite locally with:
```
./gradlew -PkeliverWithTestApp \
  :keliver-protocol-host:jvmTest :keliver-protocol-guest:jvmTest \
  :keliver-testing:jvmTest :keliver-compose:jvmTest \
  :keliver-tooling-codegen:test :keliver-treehouse-host:jvmTest \
  :keliver-treehouse-guest:jsTest
```

**Also:** the gradle-plugin lint fixture tests
(`FixtureTest > lintMpp*`) require `ANDROID_HOME` (or the fixture's
`local.properties`) to be set — they spin up a sub-build with an
Android module. CI macOS runners provide it; local runs need it
exported. Not a code defect.

**Owner.** Keliver. Fully resolved in caliclan.5 — fixture
recovered, all 22 inherited tests revived + CI-enforced, and the
underlying stdlib-serializer codegen bug fixed (PR #64).

---

### U14. `movableContentOf` reuse across different parents recreates nodes (2 quarantined ProtocolTest)

**Symptom.** When `movableContentOf` content moves between *different* parent
appliers (e.g. `Row` → `Column`), the moved widget is **recreated**
(`Create` + `Remove(detach=false)`) instead of **moved**
(`Remove(detach=true)` + re-`Add` of the same widget id). Two inherited Redwood
tests in `dev.keliver.protocol.guest.ProtocolTest` assert the reuse path and
fail: `movableContentSameRecomposition`, `multipleMovableContentButOnlyOneReused`.
They are `@Ignore`-quarantined so CI stays green; see task #45.

**First surfaced 2026-05-31**, when CI first actually executed the gated
`-PkeliverWithTestApp` suite — the cloud runner never ran it (Actions billing),
so the self-hosted-runner shift is what exposed it. Reproduces deterministically.

**Verified 2026-06-07** by un-quarantining both tests and running
`./gradlew :keliver-protocol-guest:jvmTest -PkeliverWithTestApp --tests '*ProtocolTest'`
(8 tests, 2 fail, deterministic). The captured wire for
`multipleMovableContentButOnlyOneReused` is three `Remove(detach=false)` and
*nothing else*, where the contract expects
`Remove(false), Remove(detach=true), Remove(false), Add(sameId)` — i.e. every
removal is a destroy and the surviving node is never re-attached. The reuse path
never fires. The 6 other `ProtocolTest` cases pass, including
`movableContentMultipleRecompositions` (which correctly expects `detach=false`).

**Root cause: toolchain-version coupling, NOT a keliver code defect.** Three
independent facts pin this down:

1. The *identical* inherited tests are **enabled and passing on upstream
   cashapp/redwood trunk** (added by [PR #2510](https://github.com/cashapp/redwood/pull/2510),
   Jake Wharton's `detach` design, closing #1902). Same test, same protocol code,
   different toolchain → different result. So it is not the test or the protocol
   that is wrong.
2. keliver's `NodeApplier` and `DefaultGuestProtocolAdapter` are upstream-
   unchanged. An `Applier` only *executes* the ops the compose runtime emits — it
   cannot turn a runtime `Create` back into a reuse. The divergence is therefore
   *upstream of the Applier* (runtime/compiler), and **no Applier-side edit can
   fix it** (an Applier audit against the contract found nothing to change).
3. CMP 1.8.2 == androidx Compose 1.8.2 (JetBrains' fork carries platform patches,
   not movable-content semantics). The genuine movable-content reuse fixes landed
   in compose-runtime **1.9.x / 1.10.x** — *after* the pinned 1.8.2.

Mechanically: `DefaultGuestProtocolAdapter.appendAdd` upgrades a prior `Remove`
to `detach=true` only when the re-added child is the *same* `ProtocolWidget`
instance (`removeIndex` set); on this toolchain the runtime hands it a freshly
created node, so that signal never fires.

**Severity: low-to-moderate.** The UI still renders; the only loss is node
*identity* (and any host-side widget state bound to it — scroll offset, animation)
on `movableContentOf` moves between *different* parents in a *single*
recomposition. Multi-recomposition moves (content fully leaves the tree and
returns) already recreate by design and pass.

**Decision: keep quarantined; defer the fix to the next compose-toolchain bump.**
The fix is a compose toolchain carrying the post-1.8.2 reuse fixes, which cascades
into a Kotlin bump (Compose 1.9.x wants Kotlin 2.3.x) that the published `0.1.x`
line — pinned around Kotlin 2.2.0 / KSP 2.2.0-2.0.2 / Zipline 1.26.0 for stability
— deliberately avoids. Spending that churn to recover node identity on one rare
UI pattern is not worth destabilizing the released line.

**Tripwire.** Whenever keliver next bumps Kotlin/compose (for any reason), re-run
the recipe above and drop the two `@Ignore`s if they pass — the toolchain cost is
then already amortized. Tracked as task #45.

**Owner.** Keliver.

---

### U15. iOS Zipline cache dir is created under the (read-only) app sandbox root → crashes on a real device

**Symptom.** Any Treehouse iOS host crashes at startup **on a physical device**
(works fine on the simulator) with:
```
Uncaught Kotlin exception: okio.IOException: Operation not permitted
  okio.PosixFileSystem#createDirectory
  app.cash.zipline.loader#ZiplineCache
  dev.keliver.treehouse.IosTreehousePlatform#newCache
```

**Root cause.** `keliver-treehouse-host/src/iosMain/kotlin/dev/keliver/treehouse/IosTreehousePlatform.kt`:
```kotlin
override fun newCache(name, maxSizeInBytes, loaderEventListener) = ZiplineCache(
  fileSystem = FileSystem.SYSTEM,
  directory = NSHomeDirectory().toPath() / name,   // <-- app sandbox ROOT
  ...
)
```
`NSHomeDirectory()` is the app container root. On the **simulator** that path is
writable (so this was never caught); on a **real device** only `Documents/`,
`Library/`, `Library/Caches/`, and `tmp/` under it are writable — creating a dir
directly at the root returns `EPERM`.

**Surfaced 2026-06-14** running the keliver-material SDUI host inside the real
Stashfin iOS app on a physical device (the simulator path was already render-proven,
which is exactly why it hid this).

**Fix.** Resolve a device-writable cache dir, e.g.
`directory = NSHomeDirectory().toPath() / "Library" / "Caches" / name`
(or `NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)`).

**Consumer workaround (until fixed upstream):** pass
`cacheName = "Library/Caches/<your-name>"` to `TreehouseAppFactory` so the dir lands
under the writable Library/Caches.

**Owner.** Keliver.

---

## Recently resolved

These were resolved in this repo or in the Keliver fork. Full entries
with commit references live in this fork's [`CHANGELOG.md`](../CHANGELOG.md)
and (for integration-side fixes) in ServerDrivenUI's
[`docs/CHANGELOG.md`](https://github.com/waliasanchit007/ServerDrivenUI/blob/keliver-main/docs/CHANGELOG.md):

- **#4** Schema lacks alpha/offset/border/custom-font modifiers —
  Alpha (modifier 11), Border (modifier 12), Offset (modifier 18),
  `SchemaFontFamily` enum all shipped. See CHANGELOG.
- **#6** `coil-network-ktor2` conflicts with Ktor-3-using consumer
  apps — composeApp now uses `coil-network-okhttp` on Android. See
  CHANGELOG.
- **#9** `lateinit var` services inside `bindServices` skip 2nd
  TreehouseApp mount — not actually a Keliver bug; root cause was
  integrator's unstable `remember` keys. Resolution + canonical
  `rememberKeliverApp` helper documented. See CHANGELOG.
- **U7** Anonymous service GC'd — `TreehouseApp.Spec.retain()` helper
  shipped in Keliver `1.0.0-caliclan.3`. See CHANGELOG.
- **U8 part 2** (dispatcher exposure) — `TreehouseDispatchers.ui`
  has been public API since `1.0.0-caliclan.2`; KNOWN_BUGS doc
  previously listed it as an unshipped fix. Corrected, with all
  DevoStatus host services migrated to `treehouseApp.dispatchers.ui`
  from `Dispatchers.Main`.
- **U10** Modifier serializer codegen white-screen — Keliver
  `1.0.0-caliclan.3` protocol-guest generator now emits `.serializer()`
  fallback for every non-parameterized `ClassName` modifier property.
  `ContextualSerializer` falls through to the auto-generated companion
  so the white screen can no longer happen. The `SduiSerializersModule`
  workaround is now redundant but kept (harmless when fallback works).
  See CHANGELOG.
- **U6** Lambda-on-modifier JS codegen broken — Keliver
  `1.0.0-caliclan.3` schema parser now rejects function-typed
  `@Modifier` properties at parse time with a clear error message
  instead of producing invalid Kotlin/JS codegen output that breaks
  `:shared-protocol-guest:compileKotlinJs` with a cryptic error in
  generated code the integrator never wrote.
- **U11** ZiplineService lambda-param silent failure — `:shared` ships
  a `validateZiplineServiceShapes` Gradle task that rejects the bad
  shape at build time (wired into `:shared:check`). Doesn't fix the
  Zipline-upstream root cause, but prevents the silent runtime no-op
  from ever shipping. Adopters can copy the same task into their
  protocol modules.

---

## Process

When you fix one of these:

1. Write a regression test in `:shared` (or wherever the bug surfaces)
   that fails on master and passes with the fix.
2. Move the section to `docs/CHANGELOG.md` under the next-released
   version with the fixing commit hash.
3. Open a corresponding GitHub issue + link to the commit so external
   integrators can find it via search.
