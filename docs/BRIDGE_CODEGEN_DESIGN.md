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

### 2. Modules

| Module | Targets | Contents |
|---|---|---|
| `keliver-bridge-annotations` | common (JVM, JS, iOS, Android) | `@KeliverBridge(name, version, timeoutSeconds)` and `@BridgeTimeout(seconds)`, `SOURCE` retention |
| `keliver-bridge` | common | the runtime the generated code calls: the guard (§5), `BridgeCall`, `BridgeCallFailed` |
| `keliver-bridge-codegen` | tooling (JVM) | the KSP processor |

The runtime is small and real code, so it is tested once, in `keliver-bridge`, on JVM and on
the iOS simulator, and not re-emitted (and re-reviewed) into every app. None of the three is
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

### 4. What is generated

For `ShopActions`, in its package, in one file per interface, all in `commonMain` (so the guest
bundle carries the iOS types too, unused; Kotlin/JS's dead-code elimination drops them):

1. **`ShopActionsService : ZiplineService`**, the same functions. It exists only so that the
   app's interface never carries `ZiplineService`, and neither delegate has to implement
   `close()`.
2. **`ShopActionsBridge`**, an object:
   - `NAME = "ShopActions"`, `VERSION = 2`, and `SHAPE`, a hash of the functions' names,
     parameter and result types in a fixed form, and of the fields (names and types) of every
     `@Serializable` class they reach, since a changed field keeps Zipline's call id and fails
     only when decoded. B4 can carry `SHAPE` with the version, so a change without a version
     bump is caught; nothing checks either in B3;
   - `bind(zipline, impl: ShopActions, context = EmptyCoroutineContext, log = {})` (host
     side) binds, under `NAME`, a `ShopActionsService` that runs each call through the guard in
     §5. It is called from Kotlin (its `Zipline` and `CoroutineContext` are no use to Swift):
     on iOS, B5's hook in the Kotlin `Keliver` object calls it, and Swift supplies only the
     callbacks;
   - `bind(zipline, callbacks: ShopActionsCallbacks, context, log)` is the same through
     `ShopActionsCallbackAdapter`;
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
5. **`ShopActionsCallbackAdapter(callbacks) : ShopActions`**, which turns each callback into a
   `suspend` call (§5).

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
  failed;
- for a callback, the first `done` or `fail` wins, and later ones are ignored;
- no answer in time, a `fail(message)`, or a throw ends the guest's call with
  `BridgeCallFailed`, whose message is `KeliverBridge: ShopActions.pickAddress: <reason>` and
  which has no cause. `fail`'s message is the reason as the app wrote it. A throw's message is
  not passed on (it could hold app data): the reason names the exception's class, and the
  message goes only to `log`, which a host wires to its debug console;
- when the guest's call is cancelled, or times out, a call still waiting for `context` is not
  made, and `call.onCancel`'s block runs once, on `context`, from a separate launch (the
  cancelled call's own coroutine can no longer switch to it); a block registered after that
  runs at once, also on `context`. A `suspend` delegate is cancelled as a coroutine;
- a fire-and-forget function is posted and returns at once; a throw goes to `log` and is
  dropped.

Across Zipline, a throwable travels as its known type names and its stack-trace text, and is
rebuilt on the other side as a `ZiplineException` (or as a cancellation): Zipline 1.22's
`ThrowableSerializer` writes a `ThrowableSurrogate(types, stacktraceString)`. On the guest the
`ZiplineException`'s message is that text: Zipline strips a leading `<name>: ` only for the
types it knows, so it begins `dev.keliver.bridge.BridgeCallFailed: KeliverBridge: …` and goes on
with the host's stack frames. So the guest cannot catch `BridgeCallFailed` directly; the
generated `take` wrapper looks for `KeliverBridge: ` in the message's first line, rebuilds a
`BridgeCallFailed` from that line alone (no host frames), and rethrows anything else.

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
- **The guard** (`keliver-bridge`, `commonTest`, so JVM and the iOS simulator): first answer
  wins, a second is ignored, each limit including none, `fail`, a throw (its message only in
  `log`), cancellation before the post runs and while waiting (`onCancel` on `context`, and
  registered late), fire-and-forget, a timeout that fails rather than cancels, and the
  guest-side mapping, fed with real stack-trace text from JVM and Kotlin/Native (Zipline's
  `stacktraceString` is `stackTraceToString().trim()`), not a made-up message.
- **"Both platforms"** is met in two steps: B3 proves the generated Kotlin compiles and that
  the guard behaves on JVM and iOS; the Swift side (a Swift class implementing
  `ShopActionsCallbacks`, compiled and called) is checked where a Swift app exists, in B5's
  reference-app run on the simulator.

### 7. What B3 does not change

- `closeScreen()` stays in the host kit (`dev.keliver.hostkit`). It is the host's action, not
  the app's, and moving it would break every guest built today.
- `HostHttp` and its binding name are untouched (the guest-side kdoc's `"http"` and the hosts'
  `"HostHttp"` disagree; that is a separate fix).
- Nothing reaches the host templates. B5's host work, shipped in a tools release: a hook in
  Android's `bindServices` and in the iOS `Keliver` object for the app's own bridges, and the
  iOS framework exporting the contract module.

## Steps

1. B3a: `keliver-bridge-annotations` and `keliver-bridge` (the guard and its tests).
2. B3b: `keliver-bridge-codegen` with the golden-file and rejection tests.
3. B4 and B5 as the adoption plan says, B5 adding the host hook.
