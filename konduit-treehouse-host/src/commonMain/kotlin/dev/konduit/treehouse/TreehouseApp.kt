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
package dev.konduit.treehouse

import app.cash.zipline.Zipline
import app.cash.zipline.loader.DefaultFreshnessCheckerNotFresh
import app.cash.zipline.loader.FreshnessChecker
import kotlin.native.ObjCName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

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
