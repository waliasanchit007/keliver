# Generated action bridges (B3) — design

Step B3 of [`ADOPTION_PLAN_2026-10.md`](ADOPTION_PLAN_2026-10.md), item 4 ("Bridge"), under
D13: data goes through `HostHttp` (B1, B2), and only actions (open a route, share, track,
haptic, a result from a web page) need a contract. This document fixes B3's shape before code,
as [`HTTP_API_CODEGEN_DESIGN.md`](HTTP_API_CODEGEN_DESIGN.md) did for issue #18. B4 (the
contract version in the index entry and the signed manifest) and B5 (the reference app) build
on it and are only touched where B3 must leave room for them.

## The problem, as it was measured

In the adoption the retro records (`ADOPTION_RETRO_2026-10.md`), one 11-call `ZiplineService`
contract was copied byte for byte into three repositories. The Android side was a hand-written
Kotlin delegate. The iOS side was a hand-written Swift delegate behind a hand-written Kotlin
adapter. The app-side glue around it (the delegates, a web-result screen, the seam, settings,
debug switches) came to about 900 Kotlin and 990 Swift lines. Two Zipline traps wait for any
hand-written contract: a `suspend` call returning `List<@Serializable T>` hangs `bind`
(KNOWN_BUGS U1), and a `(T) -> Unit` parameter makes every call a silent no-op (U11).

What this repository has today (2026-11-02):
- Hosts bind data and infrastructure services only (`HostHttp`, `HostSqlDriver`, the trace).
  The one action is `KeliverScreenHost.closeScreen()`, which both host templates declare in the
  fixed package `dev.keliver.hostkit`, and which a guest redeclares by shape, because Zipline
  matches a call by its signature, the parameter types' packages included.
- Swift reaches Kotlin through Kotlin function types: `Keliver.requestHeaders`
  (`KeliverRequestHeaders`, B2) is a callback with a `done`, and its suspend side
  (`credentials()` in the iOS template's `IosHttp.kt`) posts the call to the main thread, waits
  30 s at most, and ignores a second `done`. B3 generates that pattern, with a limit per
  function.

## Decisions

### 1. A KSP processor, not a bespoke Gradle task

The plan said "a Gradle task generates". KSP is used instead because:
- it resolves the interface's types (a Gradle task would parse Kotlin itself);
- it is incremental;
- the repository already ships two KSP processors that a consumer wires the same way
  (`keliver-http-codegen`, `keliver-treehouse-codegen`), with `kotlin-compile-testing` tests.

The contract module is multiplatform, so the processor runs on `kspCommonMainMetadata` and its
output is added to `commonMain`, with the ksp#1318 task dependency, as
`sample/shared/build.gradle.kts` does for the treehouse codegen. Generated sources are compiled like
hand-written ones, so the Zipline plugin adapts a generated `ZiplineService` there too; the
treehouse codegen's output in `sample/shared` already relies on that. A `dev.keliver.bridge` Gradle
plugin id that applies all of this is a later convenience, not part of B3.

KSP does not always see the annotations' default values in that metadata compilation (an
annotation from a library klib), so the processor repeats them (found in B3b).

### 2. Modules

| Module | Targets | Contents |
|---|---|---|
| `keliver-bridge-annotations` | common (JVM, JS, iOS, Android) | `@KeliverBridge(name, version, timeoutSeconds)` and `@BridgeTimeout(seconds)`, `SOURCE` retention |
| `keliver-bridge` | common | the runtime the generated code calls: the guard (§5), `BridgeCall`, `BridgeCallFailed` |
| `keliver-bridge-codegen` | tooling (JVM) | the KSP processor |
| `keliver-bridge-fixture` | common, not published | a contract module built with the processor and the Zipline plugin, for its tests (§6) |

The runtime is small and real code, so it is tested once, in `keliver-bridge`, on JVM, JS and
the iOS simulator, and not re-emitted (and re-reviewed) into every app. None of the first three is
published until a libraries release, which needs the owner; until then the reference app (B5)
uses them from this checkout, the way the production host is already built from source.

### 3. The input: one annotated interface in the app's contract module

```kotlin
@KeliverBridge(name = "ShopActions", version = 2)
interface ShopActions {
  suspend fun openRoute(route: String): Boolean
  suspend fun share(text: String, url: String?)
  @BridgeTimeout(seconds = 0)                                        // the user picks: no limit
  suspend fun pickAddress(request: AddressRequest): AddressResult   // @Serializable types
  fun track(event: String, properties: Map<String, String>)          // fire and forget
}
```

The contract module is the app's own (multiplatform, the Zipline plugin applied, U2). The
guest and both hosts depend on it, so the contract is written once, and the shape-matching
redeclaration goes away for app actions. How it reaches them is the app's choice, as for any
library it owns: a project in the same build, an included build, or, when the guest and the
hosts live in separate repositories (as in the retro), a version the app publishes to its own
repository. Keliver does not host it.

`name` defaults to the interface's simple name and is the binding name. `version` defaults to 1
and is what B4 will carry. `@KeliverBridge(timeoutSeconds = 30)` is the default limit for a
call; `@BridgeTimeout(seconds)` overrides it for one function, and 0 means none, for calls that
wait on the user (a picker, a web page's result).

The processor **fails the build**, naming the function, for:
- a `suspend` function returning any collection (U1 was measured for `List<@Serializable T>`;
  `Set`, `Map` and lists of primitives are refused as a precaution, so one rule covers them,
  and the message says to wrap the value in one `@Serializable` type);
- a parameter of function type, `Flow`, or a `ZiplineService` (U11 and lifetime: an action
  takes values);
- a non-`suspend` function that returns anything but `Unit` (it would block the guest's thread
  on the host's main thread);
- generics, default arguments, `vararg`, extension functions, and overloads (Objective-C
  export renames a function whose selector clashes, which makes the Swift side depend on the
  other functions; refusing all overloads keeps the rule simple);
- a parameter or return type that is not a primitive, `String`, an enum, a `@Serializable`
  class, or a `List`/`Map` of those (checked by the declaration, not by trying to serialize).

Found while building the processor (B3b), it also refuses:
- an interface that is not public, nested, generic, extends another, or declares properties;
- a function with a body, one named `close` (`ZiplineService.close()`), and one starting with an
  Objective-C method family (after any leading `_`: `init`, `new`, `copy`, `mutableCopy` or
  `alloc`, then the end of the name or anything but a lowercase letter), which Kotlin/Native
  renames for Swift;
- a parameter named like the generated code's own names (`call`, `done`, `fail`, `impl`,
  `guard`, `callbacks`, `service`, `zipline`, `context`, `log`, `it`, `e`, `Unit`,
  `BridgeCallFailed`).

A typealias is checked as the type it stands for; a generic one is refused (write the type).
A generic `@Serializable` class's type arguments are checked as values too.

### 4. What is generated

For `ShopActions`, in its package, in one file per interface, all in `commonMain` (so the guest
bundle carries the iOS types too, unused; Kotlin/JS's dead-code elimination drops them):

1. **`ShopActionsService : ZiplineService`**, the same functions. It exists only so that the
   app's interface never carries `ZiplineService`, and neither delegate has to implement
   `close()`.
2. **`ShopActionsBridge`**, an object:
   - `NAME = "ShopActions"`, `VERSION = 2`, and `SHAPE`, a hash of the functions' names,
     parameter and result types in a fixed form, and of every `@Serializable` class and enum
     they reach: the class's serial name, its fields' serial names and types, and the enum's
     constants by serial name (so a `@SerialName` rename counts), and a sealed class's
     subclasses, since such a change keeps Zipline's call id and fails only when decoded.
     Fields include inherited ones; fields and constants are sorted, since JSON matches them
     by name. A field is a property with a backing field and no `@Transient`; for a property
     of another module's class, whose backing field KSP may not know, any non-`@Transient`
     property. Every file the hash reads is an input of the generated file, and reaching a
     sealed class makes it depend on every source (a new subclass can be in a new file). B4
     can carry `SHAPE` with the version, so a change without a version bump is caught;
     nothing checks either in B3;
   - `bind(zipline, impl: ShopActions, context = Dispatchers.Default, log = {})` (host
     side) binds, under `NAME`, a `ShopActionsService` that runs each call through the guard in
     §5. It is called from Kotlin (its `Zipline` and `CoroutineContext` are no use to Swift):
     on iOS, B5's hook in the Kotlin `Keliver` object calls it, and Swift supplies only the
     callbacks;
   - `bind(zipline, callbacks: ShopActionsCallbacks, context, log)` is the same for the iOS
     callbacks, through `ShopActionsCallbackAdapter` (item 5);
   - `take(zipline): ShopActions` (guest side) returns a `ShopActions` that forwards to the
     taken service, so guest code, too, sees only the app's interface (§5 on failures).
3. **Android delegate:** none is generated, because the app's interface is already one. An
   Android app implements `ShopActions` with `suspend` functions and passes it to `bind`, with
   `Dispatchers.Main` as the context if it touches UI. It gets the same guard as iOS.
4. **`ShopActionsCallbacks`**, the iOS delegate, non-suspend:
   ```kotlin
   interface ShopActionsCallbacks {
     fun openRoute(route: String, call: BridgeCall, done: (Boolean) -> Unit, fail: (String) -> Unit)
     fun share(text: String, url: String?, call: BridgeCall, done: () -> Unit, fail: (String) -> Unit)
     fun pickAddress(request: AddressRequest, call: BridgeCall, done: (AddressResult) -> Unit, fail: (String) -> Unit)
     fun track(event: String, properties: Map<String, String>)
   }
   ```
   - Kotlin/Native exports it as an Objective-C protocol, and Swift implements it directly.
     Primitives are boxed there: `done(KotlinBoolean(bool: true))`, and a `Map<String, Int>` is
     `[String: KotlinInt]`.
   - `done` and `fail` are separate, so a nullable result needs no wrapper. They are Kotlin
     function types in the host framework, not in a `ZiplineService`, so U11 does not apply.
   - `call` (`BridgeCall`, in `keliver-bridge`) tells the app the guest stopped waiting:
     `isCancelled`, and `onCancel { }` to close a picker the guest no longer wants.
   - For Swift to see these types, the iOS host's framework must `export` the contract module
     and `keliver-bridge` (for `BridgeCall`; an export does not carry over to dependencies),
     and depend on both with `api`; the template's framework exports nothing today. That is
     host work, in B5.
5. **`ShopActionsCallbackAdapter(callbacks)`**, internal, generated next to `ShopActionsBridge`.
   It only connects the callbacks to the guard: for each `suspend` function the guard gives it
   a `BridgeCall` and its own `done` and `fail`, and it calls the app's function with them (so
   it does not implement `ShopActions`); for a fire-and-forget function it hands the app's call
   to the guard's post. Posting to `context`, the limit and the first answer winning are the guard's (§5),
   in `keliver-bridge`, not generated code.

### 5. The guard (`keliver-bridge`), the same on both platforms

Every call a host serves through `bind` runs through one guard, with B2's `credentials()` rules
where they apply, because they were reviewed there:
- the call is posted to `context`, and only the answer is awaited, so a busy main thread fails
  the call in time instead of holding the guest's call;
- the function's limit (§3) applies; 0 waits until the guest cancels (Zipline has no call
  limit of its own; a guest's cancel reaches the host, and closing the Zipline cancels its
  calls, so a call waiting forever still ends when the code reloads). The limit is taken with
  `withTimeoutOrNull`, not `withTimeout`: a `TimeoutCancellationException` is a cancellation,
  which Zipline sends as one, and the guest would see its call quietly cancelled instead of
  failed. The answer is boxed inside the guard, so a `null` result is not taken for a timeout.
  The call always runs on `context` (by default `Dispatchers.Default` for an implementation;
  required for Swift's callbacks, which expect the main thread), never on Zipline's own
  thread: it is launched in the guard's scope (below) and only a deferred answer is awaited, as
  `credentials()` does, not run as a child `withContext`, which the limit would have to wait
  for; on a timeout or cancel the launch is cancelled explicitly. So a delegate that blocks
  cannot stop the limit firing;
- for a callback, the first `done` or `fail` wins, and later ones are ignored;
- no answer in time, a `fail(message)`, or a throw ends the guest's call with
  `BridgeCallFailed`, whose message is `KeliverBridge: ShopActions.pickAddress: <reason>` and
  which has no cause. `fail`'s message is the reason as the app wrote it, its line breaks
  replaced by spaces, so it survives as one line. A throw's message is not passed on (it could
  hold app data): the reason names the exception's class, and the message goes only to `log`,
  which a host wires to its debug console;
- when the guest's call is cancelled, or times out, a call still waiting for `context` is not
  made, and `call.onCancel`'s block runs once, on `context`, from a separate launch, so it does
  not hold up the guest's cancellation; a block registered after that runs at once, also on
  `context`. A `suspend` delegate is cancelled as a coroutine;
- those launches, and fire-and-forget posts, run in a scope the guard owns for each binding (a
  `SupervisorJob` of its own, not Zipline's), so closing the Zipline on a code reload still
  runs a picker's `onCancel` instead of cancelling it with the calls. The scope is never
  cancelled: it holds only launches in flight and is dropped with the binding;
- a fire-and-forget function is posted and returns at once; a throw goes to `log` and is
  dropped.

Across Zipline, a throwable travels as its known type names and its stack-trace text, and is
rebuilt on the other side as a `ZiplineException` (or as a cancellation): Zipline 1.22's
`ThrowableSerializer` writes a `ThrowableSurrogate(types, stacktraceString)`. On the guest the
`ZiplineException`'s message is that text: Zipline strips a leading `<name>: ` only for the
types it knows, so it begins `dev.keliver.bridge.BridgeCallFailed: KeliverBridge: …` and goes on
with the host's stack frames. So the guest cannot catch `BridgeCallFailed` directly; the
generated `take` wrapper matches a first line that starts with
`dev.keliver.bridge.BridgeCallFailed: KeliverBridge: `, rebuilds a `BridgeCallFailed` whose
message is that line without the class name (so it begins `KeliverBridge: `, with no host
frames), and rethrows anything else.

### 6. Tests

- **Golden files** (`keliver-bridge-codegen`): each case is a source fixture and its expected
  output, checked in under `src/test/resources/golden/<case>/`. The test runs the processor
  with kotlin-compile-testing, compares the output byte for byte, and then compiles it, without
  the Zipline compiler plugin (as the treehouse codegen's tests do): that proves the output is
  valid Kotlin, not that Zipline adapts it. A system property rewrites the expected files, and
  the diff is reviewed like any other change. Every rejection in §3 has its own case and
  message check.
- **Zipline's adapter:** a fixture contract module in this repository, built by Gradle with the
  Zipline plugin for JVM, JS and the iOS simulator in CI's full build, as `sample/shared` is.
  If Zipline's API lets a JVM test bind and take through a pair of endpoints in one process,
  that test runs there too (B3b finds out); otherwise the first round trip is B5's.
  **B3b found:** Zipline 1.22 has no public in-process endpoint pair, so the first round trip
  is B5's. The fixture (`keliver-bridge-fixture`) is built with the plugin for JVM, JS and the
  iOS simulator (Zipline writes `ShopActionsService.Companion.Adapter`), and its JVM tests drive
  the generated classes: both host services through a real guard, the guest's wrapper over a
  service failing with a host's real stack-trace text, and both `bind`s against a real JVM
  Zipline (QuickJS), which shows the plugin rewrote `bind<T>`.
- **The guard** (`keliver-bridge`, `commonTest`, so JVM, JS, where the guest-side mapping runs,
  and the iOS simulator): first answer
  wins, a second is ignored, each limit including none, `fail`, a throw (its message only in
  `log`), cancellation before the post runs and while waiting (`onCancel` on `context`, and
  registered late, and after the binding's Zipline closed), fire-and-forget, a `null` result, a
  delegate that blocks, a timeout that fails rather than cancels, a multi-line `fail`, and the
  guest-side mapping, fed with real stack-trace text from JVM and Kotlin/Native (Zipline's
  `stacktraceString` is `stackTraceToString().trim()`), not a made-up message.
- **"Both platforms"** is met in two steps: B3 proves the generated Kotlin compiles and that
  the guard behaves on JVM, JS and iOS; the Swift side (a Swift class implementing
  `ShopActionsCallbacks`, compiled and called) is checked where a Swift app exists, in B5's
  reference-app run on the simulator.

### 7. What B3 does not change

- `closeScreen()` stays in the host kit (`dev.keliver.hostkit`). It is the host's action, not
  the app's, and moving it would break every guest built today.
- `HostHttp` and its binding name are untouched (the guest-side kdoc's `"http"` and the hosts'
  `"HostHttp"` disagree; that is a separate fix).
- Nothing reaches the host templates. B5's host work, shipped in a tools release: a hook in
  Android's `bindServices` and in the iOS `Keliver` object for the app's own bridges, and the
  iOS framework exporting the contract module and `keliver-bridge`.

## Steps

1. B3a: `keliver-bridge-annotations` and `keliver-bridge` (the guard and its tests).
2. B3b: `keliver-bridge-codegen` with the golden-file and rejection tests.
3. B4 and B5 as the adoption plan says, B5 adding the host hook.

## B4: the contract version in the compatibility gate

Adoption plan item 4, point 3, and D2: a bundle reaches only a host that serves the bridges it
calls, checked twice, at lookup from the index and again at load from the signed manifest, the
way the sequence floor is (W4). B4 follows the existing mechanisms rather than adding new ones.

### What a bundle declares, and where

- **One source: the signed manifest.** The guest's build declares what it calls in Zipline's
  manifest metadata, under `keliver.bridges`: `name@min` pairs, sorted by name and joined by
  `,` (`ShopActions@2`), optionally with the contract's shape, `ShopActions@2#cd5829702ffe49be`.
  `min` is the `VERSION`, and the shape the `SHAPE`, of the `<Name>Bridge` the guest was built
  against.
  - It is written by the app's build, in `zipline { metadata.put('keliver.bridges', …) }`. The
    publish signing block (`templates/publish/signing.gradle`) keeps existing metadata and
    signs over it, as it does for `keliver.sequence`, so the value is covered by the signature.
  - The value is written by hand for now: Gradle cannot read the generated Kotlin constants.
    A `dev.keliver.bridge` Gradle plugin could write it later (§1). A wrong `min` fails safe
    upwards (a host with an older bridge refuses) and fails at the first call downwards (as a
    missing bridge does today), so B5 checks it in the reference app.
- **The index entry is derived from the manifest, never declared separately.** Every path that
  writes an entry reads `keliver.bridges` from the signed manifest it points at and writes it
  into the entry's `constraints` as `"bridges": {"ShopActions": {"min": 2, "shape": "cd58…"}}`
  (`shape` only when the manifest has one):
  - `keliver-publish` (`publishStatic`, which already reads the signed manifest for its
    sequence check);
  - republish and promotion, which today copy an entry's constraints (W4.5): they derive
    `bridges` from the manifest too, so an entry written before B4 gets it as well;
  - the relay, which keeps no entries but rebuilds them from each version's `meta.json` on
    every request (`relayIndexJson`): it derives `bridges` from that version's manifest.
  A malformed value refuses a publish, republish or promotion, and the relay's own publish. The
  relay's index, rebuilt on each request, leaves out a version whose manifest's value is
  malformed anyway (one stored before B4, fail closed). `noStricterThan` already treats an entry with constraints as stricter.

### Why `constraints`, not a new top-level field

Hosts skip an entry whose `constraints` holds a key they do not know (format 1's fail-closed
rule, W4.5). So a host from before B4 never picks a bundle that needs a bridge: it starts the
newest entry without one, or its cache. A new top-level field would be ignored by those hosts,
which would then load a bundle whose first bridge call fails.

### What a host checks

A host knows the bridges it serves from its configuration: the app declares each one (its
generated `<Name>Bridge`'s `NAME`, `VERSION` and `SHAPE`) when it configures the host (B5's
hook, Android `KeliverConfig`, iOS `Keliver`), before the first lookup. Not at `bind`: binding
runs after the lookup, the cache check and the manifest check, so a cold start would refuse a
bundle the host can serve (found in B4a's review). The host then binds what it declared.

1. **Lookup** (`BundleIndex.kt`, the same file in both hosts): an entry whose
   `constraints.bridges` names a bridge the host does not serve, at a `min` above the host's
   `VERSION`, or at the host's `VERSION` with another `shape` (3, below), is skipped, like an
   entry needing a capability or widget version the host lacks.
2. **Load**, on the manifest itself, in the two places the floor is checked: the manifest bytes
   in `ManifestPinningHttpClient` (before Zipline verifies them; it only refuses) and the
   verified metadata in `AcceptCachedBundle` (a cache start). A manifest whose
   `keliver.bridges` the host cannot serve is refused with `bridge refused: …`. What follows
   is what a refused floor gets today, extended to this reason:
   - a first load refused on the network path falls back to the last good bundle when there is
     one; with none, or on a cache start, the screen is not loaded, and `markNotLoaded`
     (`KeliverHost.kt`, iOS `MainViewController.kt`) adds `bridge refused:` to the reasons that
     make a manifest unloadable, so it is not fetched again (when its sha256 is known: a cache
     start's pin has none);
   - a refused update to a running bundle takes the existing update-failed path: the running
     bundle stays, and the manifest is remembered as a failed update;
   - it is reported under new reasons in `reportReason` (`BundleIndex.kt`), matched before the
     signature reasons: `bridge-missing`, and `bridge-shape` for a shape mismatch (3).
   A manifest without `keliver.bridges` needs no bridge; one whose value is malformed is
   refused (fail closed), as `bridge refused: malformed keliver.bridges`, reported as
   `bridge-missing`.
3. **Shape**, when the manifest carries one: at the same version, the guest's shape must equal
   the host's; a different shape at the same version is refused (`bridge refused: the bundle was built against another ShopActions@2 contract …`), since it is the change a version bump was missed for.
   A host at a later version than `min` is not compared, so a version bump must stay
   compatible with older guests, on two levels:
   - **functions:** keep every older function's signature (Zipline call id), adding new ones
     only;
   - **types those older functions use:** change them only in ways an older guest still
     decodes (Zipline's JSON ignores unknown keys and encodes defaults):
     - allowed: a new field with a default;
     - not allowed: a new required field, a renamed or retyped field, and a new enum constant
       or sealed subclass sent to an older guest.
   Anything else needs a new bridge name. The shape is in the index entry too, so a host at
   the same version skips a mismatched entry at lookup instead of refusing it at every start.

### What B4 does not do, and what an adopter must know

- It does not check that the guest calls only what it declared; Zipline already fails such a
  call (`ZiplineApiMismatchException`).
- It does not change `widgetVersion` or capabilities.
- It cannot gate a guest that calls a bridge but declares nothing in `keliver.bridges`: such a
  bundle reaches every host, and a host without the bridge fails at the first call, as today.
  Writing the value from the generated constants (a resource the processor emits, read by a
  Gradle plugin) would close that and the copied-by-hand `shape`; it is a follow-up.
- Declaring bridges on a bundle hides it from every installed host from before B4, which
  keeps its last bundle without bridges: fail closed, but an adopter moving a hand-written
  contract to a declared bridge must ship the B4 host first.
- The relay's legacy `/bundles/latest`, which hosts from tools 0.3.7 or earlier use, never
  returns a bundle whose manifest declares bridges: those hosts serve none, and that endpoint
  has no constraints to skip by (B4a filters it).

### Tests

- `BundleIndexTest`: lookup skips (missing, older, another shape, malformed), the manifest
  checks (network bytes and verified metadata, with and without a shape), `reportReason`'s
  `bridge-missing` and `bridge-shape` (matched before the signature reasons), and the existing
  rule that an unknown constraint is skipped by a host that predates it.
- `StaticPublishTest` and `keliver-publish-selftest.sh`: the entry's constraints come from the
  manifest on publish, republish and promotion (an entry written before B4 included); a
  malformed value refuses. A relay test: `relayIndexJson` derives `bridges` from each
  version's manifest and leaves out a malformed one; the legacy `/bundles/latest` skips a
  bundle that declares bridges. The relay's own publish refusal is not covered by a test: it is
  inside the relay's private publish function, which runs Gradle.
- Scaffolder self-tests: both hosts wire the bridge check where they wire the floor, and
  `markNotLoaded` treats `bridge refused:` as unloadable (checked in the source, as the floor's
  wiring is; it runs on a device in B5's scenarios).
- On a device (the reference app, after B5): an index entry needing a bridge the host does not
  serve is skipped (as S16 does for a capability), and a manifest whose signed requirement the
  host cannot serve is refused at load even when the index entry lies about it.

### Steps

1. B4a: the publisher (index constraints from the manifest on every path, refusal;
   `relayIndexJson` and the legacy endpoint's filter), `BundleIndex.kt` (lookup, manifest
   checks, `reportReason`) and the hosts' `markNotLoaded`, with their tests. Host templates
   pass no bridges yet, so nothing changes for an app without one.
2. B5: the hosts' hooks for an app's bridges (declared in the configuration, before the first
   lookup, and bound with the services), the reference app migrated to a generated bridge, and
   the device scenarios above.
