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
  30 s at most, and ignores a second `done`. That is the pattern B3 generates.

## Decisions

### 1. A KSP processor, not a bespoke Gradle task

The plan said "a Gradle task generates". KSP is used instead because:
- it resolves the interface's types (a Gradle task would parse Kotlin itself);
- it is incremental;
- the repository already ships two KSP processors that a consumer wires the same way
  (`keliver-http-codegen`, `keliver-treehouse-codegen`), with `kotlin-compile-testing` tests.

The contract module is multiplatform, so the processor runs on `kspCommonMainMetadata` and its
output is added to `commonMain`, with the ksp#1318 task dependency, as
`sample/shared/build.gradle.kts` does for the treehouse codegen. A `dev.keliver.bridge` Gradle
plugin id that applies all of this is a later convenience, not part of B3.

### 2. Modules

| Module | Targets | Contents |
|---|---|---|
| `keliver-bridge-annotations` | common (JVM, JS, iOS, Android) | `@KeliverBridge(name, version)`, `SOURCE` retention |
| `keliver-bridge` | common | the runtime the generated code calls: `BridgeCallFailed`, and `awaitCallback`, the suspend-to-callback step (§5) |
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
  suspend fun pickAddress(request: AddressRequest): AddressResult   // @Serializable types
  fun track(event: String, properties: Map<String, String>)          // fire and forget
}
```

The contract module is the app's own (multiplatform, the Zipline plugin applied, U2). The
guest and both hosts depend on it, so the contract is written once, and the shape-matching
redeclaration goes away for app actions. `name` defaults to the interface's simple name and is
the binding name; `version` defaults to 1 and is what B4 will carry.

The processor **fails the build**, naming the function, for:
- a `suspend` function returning `List`, `Set` or `Map` of anything but strings and
  primitives (U1; the message says to wrap it in one `@Serializable` type);
- a parameter of function type, `Flow`, or a `ZiplineService` (U11 and lifetime: an action
  takes values);
- a non-`suspend` function that returns anything but `Unit` (it would block the guest's thread
  on the host's main thread);
- generics, default arguments, overloads (Objective-C export renames them, which makes the
  Swift side unpredictable), `vararg`, and extension functions;
- a parameter or return type that is not a primitive, `String`, an enum, a `@Serializable`
  class, or a `List`/`Map` of those (checked by the declaration, not by trying to serialize).

### 4. What is generated

For `ShopActions`, in its package, in one file per interface:

1. **`ShopActionsService : ZiplineService`**, the same functions. It exists only so that the
   app's interface never carries `ZiplineService`, and neither delegate has to implement
   `close()`.
2. **`ShopActionsBridge`**, an object:
   - `NAME = "ShopActions"`, `VERSION = 2` (B4 reads these; nothing checks them in B3);
   - `bind(zipline, impl: ShopActions)` (host side) binds a forwarding `ShopActionsService`
     under `NAME`;
   - `take(zipline): ShopActions` (guest side) returns a `ShopActions` that forwards to the
     taken service, so guest code, too, sees only the app's interface.
3. **Android delegate:** none is generated, because the app's interface is already one. An
   Android app implements `ShopActions` with `suspend` functions and passes it to `bind`.
4. **`ShopActionsCallbacks`**, the iOS delegate, non-suspend:
   ```kotlin
   interface ShopActionsCallbacks {
     fun openRoute(route: String, done: (Boolean) -> Unit, fail: (String) -> Unit)
     fun share(text: String, url: String?, done: () -> Unit, fail: (String) -> Unit)
     fun pickAddress(request: AddressRequest, done: (AddressResult) -> Unit, fail: (String) -> Unit)
     fun track(event: String, properties: Map<String, String>)
   }
   ```
   Kotlin/Native exports it as an Objective-C protocol, and Swift implements it directly.
   `done` and `fail` are separate, so a nullable result needs no wrapper. They are Kotlin
   function types in the host framework, not in a `ZiplineService`, so U11 does not apply.
5. **`ShopActionsCallbackAdapter(callbacks, context, timeout = 30.seconds) : ShopActions`**,
   which the iOS host passes to `bind`. Each call goes through `awaitCallback` (§5).

### 5. The suspend-to-callback step (`awaitCallback`, in `keliver-bridge`)

The same rules as B2's `credentials()`, because they were reviewed there:
- the call is posted to `context` (the host passes the main dispatcher), and only the answer is
  awaited, so a busy main thread fails the call in time instead of holding the guest's call;
- the first `done` or `fail` wins, and later ones are ignored;
- no answer within `timeout`, a `fail(message)`, or a throw from the callback ends the guest's
  call with `BridgeCallFailed(bridge, function, reason)`, which Zipline passes to the guest as
  an exception. A throw's message is not passed on (it could hold app data): the reason names
  the exception's class only;
- when the guest's call is cancelled, or times out, a call still waiting for the main thread
  is not made;
- a fire-and-forget function is posted and returns at once; a throw is caught and dropped.

### 6. Tests

- **Golden files** (`keliver-bridge-codegen`): each case is a source fixture and its expected
  output, checked in under `src/test/resources/golden/<case>/`. The test runs the processor
  with kotlin-compile-testing, compares the output byte for byte, and then compiles it together
  with the Zipline runtime. A system property rewrites the expected files, and the diff is
  reviewed like any other change. Every rejection in §3 has its own case and message check.
- **The runtime** (`keliver-bridge`, `commonTest`, so JVM and the iOS simulator in CI's full
  build): first answer wins, a second is ignored, the timeout, `fail`, a throw, cancellation
  before the post runs, and fire-and-forget.
- **"Both platforms"** is met in two steps: B3 proves the generated Kotlin compiles and behaves
  on JVM (the Android delegate path) and that the adapter's runtime behaves on iOS; the Swift
  side (a Swift class implementing `ShopActionsCallbacks`, compiled and called) is checked
  where a Swift app exists, in B5's reference-app run on the simulator.

### 7. What B3 does not change

- `closeScreen()` stays in the host kit (`dev.keliver.hostkit`). It is the host's action, not
  the app's, and moving it would break every guest built today.
- `HostHttp` and its binding name are untouched (the guest-side kdoc's `"http"` and the hosts'
  `"HostHttp"` disagree; that is a separate fix).
- Nothing reaches the host templates. Letting an app bind its own services in the scaffolded
  hosts (a hook in Android's `bindServices` and in the iOS `Keliver` object) is part of B5, and
  ships in a tools release.

## Steps

1. B3a: `keliver-bridge-annotations` and `keliver-bridge` (the runtime and its tests).
2. B3b: `keliver-bridge-codegen` with the golden-file and rejection tests.
3. B4 and B5 as the adoption plan says, B5 adding the host hook.
