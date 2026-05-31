/*
 * Copyright (C) 2022 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.treehouse

import app.cash.zipline.Zipline
import app.cash.zipline.loader.DefaultFreshnessCheckerNotFresh
import app.cash.zipline.loader.FreshnessChecker
import kotlin.native.ObjCName
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Thrown by [TreehouseApp.Spec.bindWithTimeout] when a wrapped
 * `Zipline.bind`/`Zipline.take` call doesn't return within the
 * deadline. Two distinct silent-hang shapes manifest as this exception
 * (both documented as upstream Zipline gotchas in
 * `ServerDrivenUI/docs/KNOWN_BUGS.md`):
 *
 *  - **U1** — `ZiplineService` method with signature
 *    `suspend fun X(...): List<@Serializable T>` on Zipline 1.26.
 *  - **U2** — Zipline Gradle plugin not applied to the module calling
 *    `bind`/`take`. (`take` separately throws `"unexpected call to
 *    Zipline.take: is the Zipline plugin configured?"`, but `bind`
 *    hangs.)
 *
 * The exception message lists both so integrators have something
 * actionable to investigate.
 *
 * The exception's `cause` is the underlying
 * [TimeoutCancellationException] from `kotlinx.coroutines.withTimeout`.
 */
/**
 * Thrown by [TreehouseApp.Spec.requireSerializerOf] when a
 * `@Serializable` wire type can't be resolved at bind time. The most
 * common cause is the kotlinx-serialization Gradle plugin not being
 * applied to the module that declares the type — without it the
 * compiler doesn't generate the `.serializer()` companion, so runtime
 * lookups fail.
 *
 * Closes integration bug U3 in `ServerDrivenUI/docs/KNOWN_BUGS.md` —
 * surfaces the failure at bind time with a clear cause instead of
 * waiting until the first call sends the type across the wire.
 */
public class MissingSerializerException @PublishedApi internal constructor(
  typeDisplayName: String,
  cause: SerializationException,
) : RuntimeException(
  buildString {
    append("No serializer found for type `$typeDisplayName`.\n")
    append("Two known causes:\n")
    append("  1. The type isn't annotated `@kotlinx.serialization.Serializable`. ")
    append("Add the annotation to the data class / enum.\n")
    append("  2. The module declaring the type doesn't apply the ")
    append("`org.jetbrains.kotlin.plugin.serialization` Gradle plugin. ")
    append("Without it the compiler never generates the `.serializer()` ")
    append("companion (KNOWN_BUGS.md U3). Add ")
    append("`alias(libs.plugins.kotlinSerialization)` to the module's ")
    append("`plugins {}` block.")
  },
  cause,
) {
  /**
   * Convenience constructor used by [TreehouseApp.Spec.requireSerializerOf] —
   * accepts a `KClass<*>` and renders its short name in the message. Kept
   * separate from the [KType]-friendly primary constructor so the existing
   * single-type call site stays unchanged.
   */
  @PublishedApi
  internal constructor(type: KClass<*>, cause: SerializationException) :
    this(type.simpleName ?: type.toString(), cause)
}

public class ZiplineBindTimeoutException internal constructor(
  timeoutMillis: Long,
  cause: TimeoutCancellationException,
) : RuntimeException(
  buildString {
    append("Zipline.bind/take did not return within ${timeoutMillis}ms.\n")
    append("Two known causes:\n")
    append("  1. ZiplineService method signature ")
    append("`suspend fun X(...): List<@Serializable T>` makes bind hang ")
    append("indefinitely with no log on Zipline 1.26 (KNOWN_BUGS.md U1). ")
    append("Workaround: drop `suspend` — the method runs on the Zipline ")
    append("dispatcher anyway, so blocking work is already on the right ")
    append("thread for synchronous return.\n")
    append("  2. The Zipline Gradle plugin (`app.cash.zipline`) is not ")
    append("applied to the module that called bind/take. Without it, the ")
    append("plugin's codegen never runs and bind hangs forever (take ")
    append("throws its own diagnostic). KNOWN_BUGS.md U2. Workaround: add ")
    append("`alias(libs.plugins.zipline)` to the module's `plugins {}` block.")
  },
  cause,
)

/**
 * Manages the [Zipline] runtimes that run the code to power on-screen views.
 *
 * This takes care to launch a [Zipline] when it is requested and available.
 *
 *  * **Requested:** The runtime is started either explicitly via a call to [start], or implicitly
 *    by calling binding creating content to a UI.
 *
 *  * **Available:** The runtime is available when its code is ready, either via a download,
 *    embedded in the host application, or through the cache from an earlier execution.
 *
 * If new code is available during execution (typically during development), this will perform a
 * hot reload: gracefully stopping the current Zipline and starting its successor. It also
 * implements restarting the Zipline after an uncaught exception (in both development and
 * production).
 *
 * It is rarely necessary to call the [start], [stop], and [restart] methods directly. Calling
 * [createContent] will trigger a start automatically. Use [close] to permanently stop the Zipline.
 */
@ObjCName("TreehouseApp", exact = true)
public abstract class TreehouseApp<A : AppService> : AutoCloseable {
  public abstract val name: String
  public abstract val dispatchers: TreehouseDispatchers

  /**
   * Returns the current zipline attached to this host, or null if Zipline hasn't loaded yet. The
   * returned value will be invalid when new code is loaded.
   *
   * It is unwise to use this instance for anything beyond measurement and monitoring, because the
   * instance may be replaced if new code is loaded.
   */
  public abstract val zipline: StateFlow<Zipline?>

  /**
   * Create content for [source].
   *
   * Calls to this function will [start] this app if it isn't already started.
   */
  public abstract fun createContent(
    source: TreehouseContentSource<A>,
  ): Content

  /**
   * Initiate the initial code download and load, and start driving the views that are rendered by
   * this app.
   *
   * This function returns immediately if this app is already started.
   *
   * This function may only be invoked on [TreehouseDispatchers.ui].
   */
  public abstract fun start()

  /**
   * Stop any currently-running code and stop receiving new code.
   *
   * This function may only be invoked on [TreehouseDispatchers.ui].
   */
  public abstract fun stop()

  /**
   * Stop the currently-running application (if any) and start it again.
   *
   * This function may only be invoked on [TreehouseDispatchers.ui].
   */
  public abstract fun restart()

  /** Permanently stop the app and release any resources necessary to start it again. */
  public abstract override fun close()

  /**
   * Creates new instances of [TreehouseApp].
   *
   * This manages a cache that should be shared by all launched applications. This object holds a
   * stateful disk cache. At most one instance with each cache name should be open at any time. Most
   * applications should share a single [Factory] across all applications for best caching.
   */
  @ObjCName("TreehouseAppFactory", exact = true)
  public interface Factory : AutoCloseable {
    public fun <A : AppService> create(
      appScope: CoroutineScope,
      spec: Spec<A>,
      eventListenerFactory: EventListener.Factory = EventListener.NONE,
    ): TreehouseApp<A>
  }

  /**
   * Configuration and code to launch a Treehouse application.
   */
  public abstract class Spec<A : AppService> {
    /**
     * Strong references to services bound via [bindRetained]. Zipline
     * itself only keeps weak references to bound services, so inline
     * anonymous service implementations would otherwise be GC-eligible
     * the moment [bindServices] returns — the first guest call would
     * then error with `"no such service (service closed?)"`. Holding
     * them in this list keeps them alive for the lifetime of this Spec
     * (which is the lifetime of the [TreehouseApp]).
     *
     * Exposed via [retainedServices] for tests / diagnostics; integrators
     * should not need to touch it directly.
     */
    @PublishedApi
    internal val _retainedServices: MutableList<Any> = mutableListOf()

    /**
     * Read-only view of services currently retained by this Spec. Useful
     * for diagnostics or tests; not part of the integrator-facing API.
     */
    public val retainedServices: List<Any> get() = _retainedServices

    public abstract val name: String

    /**
     * The URL of the Zipline manifest file to load this app's code from.
     *
     * This flow should emit each time that a code load should be attempted. No code will be loaded
     * until this flow's first emit.
     *
     * The flow may make subsequent emits to trigger a hot reload attempt. Hot reloads will be
     * attempted even if the URL is unchanged. This is typically most useful during development.
     * Consider using [app.cash.zipline.loader.withDevelopmentServerPush] to turn the Zipline
     * development server URL flow into one that emits each time that server's code is updated.
     */
    public abstract val manifestUrl: Flow<String>

    public open val serializersModule: SerializersModule
      get() = EmptySerializersModule()

    public open val freshnessChecker: FreshnessChecker
      get() = DefaultFreshnessCheckerNotFresh

    /**
     * Returns true to only load code from the network. Otherwise, this will recover from
     * unreachable network code by loading code from the cache or the embedded file system.
     *
     * This is false by default. Override it to return true in development, where loading code from
     * a source other than the network may be surprising.
     */
    public open val loadCodeFromNetworkOnly: Boolean
      get() = false

    /**
     * Make services available to guest application on [zipline], typically by making one or more
     * calls to [Zipline.bind].
     *
     * For inline anonymous service implementations (e.g. `object : HostConsole { … }`) wrap the
     * instance in [retain] before passing it to [Zipline.bind]:
     *
     * ```kotlin
     * zipline.bind<HostConsole>("console", retain(object : HostConsole { … }))
     * ```
     *
     * Zipline holds only weak references to bound services; anonymous instances become
     * GC-eligible the moment this method returns, and the first guest call will then error
     * with "no such service (service closed?)". [retain] keeps a strong reference inside this
     * Spec for its lifetime, eliminating that pitfall.
     */
    public abstract suspend fun bindServices(
      treehouseApp: TreehouseApp<A>,
      zipline: Zipline,
    )

    /**
     * Run [block] (typically a `zipline.bind<...>(...)` or `zipline.take<...>(...)` call) with a
     * deadline. Throws [ZiplineBindTimeoutException] if [block] doesn't return within
     * [timeoutMillis], rather than hanging forever.
     *
     * Why: certain `ZiplineService` method signatures — most notably
     * `suspend fun X(...): List<@Serializable T>` (see
     * `ServerDrivenUI/docs/KNOWN_BUGS.md` U1) — make `Zipline.bind` hang indefinitely with no
     * log line on Zipline 1.26. The hang is opaque to integrators: no exception, no
     * diagnostic, the host's `bindServices` simply never returns and downstream code never
     * runs. Wrapping the bind in this helper turns the silent hang into an actionable
     * exception that names the most common cause.
     *
     * Usage:
     * ```kotlin
     * override suspend fun bindServices(treehouseApp, zipline) {
     *     bindWithTimeout {
     *         zipline.bind<HostConsole>("console", hostConsole)
     *     }
     *     bindWithTimeout {
     *         zipline.bind<HostQuotesProvider>("quotes", quotesProvider)
     *     }
     * }
     * ```
     *
     * Why a block + lambda instead of an inline `bind` wrapper: Zipline's compiler plugin
     * generates code at the `bind<ConcreteInterface>` call site and can't see through an
     * `inline reified` wrapper, so the bind has to stay a direct call. Wrapping a non-inline
     * lambda preserves the codegen behavior while still letting us add the timeout.
     *
     * Default 30s is generous — a healthy `bind` returns in milliseconds.
     */
    public suspend fun bindWithTimeout(
      timeoutMillis: Long = 30_000L,
      block: suspend () -> Unit,
    ) {
      try {
        withTimeout(timeoutMillis) { block() }
      } catch (e: TimeoutCancellationException) {
        throw ZiplineBindTimeoutException(timeoutMillis, e)
      }
    }

    /**
     * Resolve a [KSerializer] for [T] right now and return it, or throw
     * [MissingSerializerException] with a clear diagnostic. Useful as a pre-flight check
     * inside [bindServices] to catch missing `@Serializable` annotations / missing
     * kotlinx-serialization plugins at bind time rather than at first-call time.
     *
     * Usage:
     * ```kotlin
     * override suspend fun bindServices(treehouseApp, zipline) {
     *     requireSerializerOf<Quote>()      // throws fast if Quote isn't @Serializable
     *     requireSerializerOf<Wallpaper>()  // …or kotlinx-serialization plugin missing
     *     bindWithTimeout {
     *         zipline.bind<HostQuotesProvider>("quotes", impl)
     *     }
     * }
     * ```
     *
     * Why a manual call rather than auto-checking on `bind`: walking a `ZiplineService`'s
     * methods to extract wire types needs `kotlin-reflect` (JVM-only, ~3MB) and would
     * couple keliver-treehouse-host to a JVM-only dependency. The explicit per-type call
     * stays multiplatform and gives the integrator control over which types to validate.
     *
     * Closes integration bug U3 (KNOWN_BUGS.md) — the runtime error
     * `Serializer for class 'X' is not found` was already actionable, but fired at
     * first-call time rather than bind time; this helper moves the failure point closer
     * to the cause.
     */
    public inline fun <reified T : Any> requireSerializerOf(): KSerializer<T> {
      return try {
        serializersModule.serializer<T>()
      } catch (e: SerializationException) {
        throw MissingSerializerException(T::class, e)
      }
    }

    /**
     * Bulk-validate that every [type] has a registered [KSerializer] in
     * [serializersModule]. Adopters call this once at the top of
     * [bindServices] with every wire type used across their
     * `ZiplineService` interfaces; missing serializers throw
     * [MissingSerializerException] at bind time rather than at first-call
     * time.
     *
     * Equivalent to calling [requireSerializerOf] N times, but the bulk
     * form is more discoverable and shorter at each integration site:
     *
     * ```kotlin
     * import kotlin.reflect.typeOf
     *
     * override suspend fun bindServices(treehouseApp, zipline) {
     *     requireSerializersOf(
     *         typeOf<Quote>(),
     *         typeOf<Wallpaper>(),
     *         typeOf<SavedCardKey>(),
     *         typeOf<List<Quote>>(),   // verifies Quote transitively
     *     )
     *     bindWithTimeout {
     *         zipline.bind<HostQuotesProvider>("quotes", impl)
     *     }
     * }
     * ```
     *
     * Catches U3 (kotlinx-serialization plugin missing on the module
     * declaring the type) plus the U4-adjacent silent-failure shape: a
     * `SerializationException` thrown inside a `ZiplineService`
     * callback's response handler gets swallowed by the protocol path,
     * leaving the guest with no response, no error, no log. Validating
     * every wire type at bind time forces the failure into the
     * actionable surface instead.
     *
     * Multiplatform: works in any Konduit host (Android, iOS, JVM).
     *
     * Trade-off vs. an auto-walking helper: adopters list each wire type
     * explicitly. A reflection-based auto-walker would need
     * `kotlin-reflect` (3MB, JVM-only) and couple this module to a
     * JVM-only dependency. The explicit list keeps the API
     * multiplatform and gives integrators control over exactly which
     * types are validated — useful when some wire types are routed
     * through services the integrator doesn't directly bind here.
     *
     * Returns the resolved serializers in the same order as [types] so
     * callers who want to memoize them can.
     *
     * Closes issue #30 (U4-adjacent serialization-failure surface).
     */
    public fun requireSerializersOf(vararg types: KType): List<KSerializer<*>> {
      return types.map { type ->
        try {
          serializersModule.serializer(type)
        } catch (e: SerializationException) {
          throw MissingSerializerException(type.toString(), e)
        }
      }
    }

    /**
     * Retain a strong reference to [service] for the lifetime of this [Spec], then return
     * [service] unchanged. Wrap inline anonymous service implementations with this before
     * passing them to [Zipline.bind] — Zipline holds only weak references internally, so an
     * inline `object : MyService { … }` is GC-eligible the moment [bindServices] returns and
     * the first guest call then errors with "no such service (service closed?)". Retaining the
     * instance here keeps it reachable as long as the [TreehouseApp] is alive.
     *
     * Usage:
     * ```kotlin
     * override suspend fun bindServices(treehouseApp, zipline) {
     *     zipline.bind<HostConsole>("console", retain(object : HostConsole {
     *         override fun log(message: String) = println(message)
     *     }))
     * }
     * ```
     *
     * Note: [retain] is a non-inline pass-through (returns [service] as-is). It does NOT call
     * [Zipline.bind] itself — that has to stay a direct call because Zipline's compiler plugin
     * generates code at the `bind<ConcreteInterface>` site and can't see through an inline
     * wrapper.
     *
     * Holding the service as a `val`/`lateinit var` field of the Spec produces the same
     * outcome — both keep the instance reachable for the [TreehouseApp]'s lifetime.
     * [retain] just removes the requirement to remember the pattern when you'd otherwise be
     * tempted to inline an anonymous service object.
     */
    public fun <T : Any> retain(service: T): T {
      _retainedServices += service
      return service
    }

    public abstract fun create(zipline: Zipline): A
  }
}
