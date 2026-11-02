/*
 * B5 (docs/BRIDGE_CODEGEN_DESIGN.md, section "B5"): YOUR app's generated action bridges, served
 * to guests. The app declares them when it configures the host (Android: KeliverConfig.bridges;
 * iOS: Keliver.shared.bridges), so the bundle gate (B4, BundleIndex.kt) knows before the first
 * lookup which bundles this host can run, and the host binds them each time code loads.
 * The same file in both hosts; the relay's BridgeGateTest compiles it with BundleIndex.kt.
 * Scaffolded by keliver-new-production-host.sh / keliver-new-ios-host.sh.
 */
package @@PACKAGE@@

import app.cash.zipline.Zipline
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * One of YOUR app's generated bridges. Build it from one generated `<Name>Bridge`, so that the
 * name the gate checks is the name [bind] binds. Android:
 *
 *   KeliverBridgeBinding(ShopActionsBridge.NAME, ShopActionsBridge.VERSION, ShopActionsBridge.SHAPE) {
 *     ShopActionsBridge.bind(it.zipline, shopActions, it.main, it.log)
 *   }
 *
 * [bind] runs once per code load, on Zipline's thread (not the main thread), before the host's
 * own services. It must bind exactly [name], once: the host records what it bound (Zipline's
 * EventListener.bindService), and a binding that binds anything else, or throws, fails that
 * load as "bridge bind failed" (reported as `bridge-bind`). Since every load runs it, the last
 * good bundle then fails the same way: it is the app's programming error, fixed by a new build.
 */
public class KeliverBridgeBinding(
  public val name: String,
  public val version: Int,
  public val shape: String?,
  public val bind: (KeliverBridgeScope) -> Unit,
) {
  internal val declared: DeclaredBridge get() = DeclaredBridge(name, version, shape)
}

/**
 * What a [KeliverBridgeBinding] binds with: this code load's Zipline, the main thread's context
 * (pass it as the generated `bind`'s `context` when your implementation touches UI; on iOS,
 * Swift's callbacks expect it) and the host's log, which writes in debug builds only (a failed
 * call's message may hold your app's data).
 */
public class KeliverBridgeScope(
  public val zipline: Zipline,
  public val main: CoroutineContext,
  public val log: (String) -> Unit,
)

/**
 * The services bound while one app binding runs, as the code load's EventListener reports them
 * ([bound], from `bindService`, which Zipline calls inside `bind`, on the thread that binds).
 * One per host.
 */
internal class BridgeBindRecorder {
  @Volatile private var names: MutableList<String>? = null

  fun bound(name: String) {
    names?.add(name)
  }

  /** Runs [block], and returns the names bound while it ran. */
  fun record(block: () -> Unit): List<String> {
    val recorded = mutableListOf<String>()
    names = recorded
    try {
      block()
    } finally {
      names = null
    }
    return recorded.toList()
  }
}

/**
 * Binds the app's [bridges], each through [bindOne] and required to bind exactly its declared
 * name, as [recorder] saw it. Called before the host binds its own services, which therefore
 * replace anything of theirs a binding bound (Zipline's `bind` replaces without a word). A
 * binding that throws (an Error too) or binds anything else throws "bridge bind failed: …",
 * which fails the load; the throwable's own text goes to [debugLog] only. A cancellation passes
 * through only when [loadCancelled] says the load itself was cancelled: Zipline's loader reports
 * nothing for a cancellation, so one the binding made up would end the load in silence.
 */
internal fun bindAppBridges(
  bridges: List<KeliverBridgeBinding>,
  recorder: BridgeBindRecorder,
  debugLog: (String) -> Unit,
  loadCancelled: () -> Boolean,
  bindOne: (KeliverBridgeBinding) -> Unit,
) {
  for (bridge in bridges) {
    var thrown: Throwable? = null
    val bound = recorder.record {
      try {
        bindOne(bridge)
      } catch (e: CancellationException) {
        if (loadCancelled()) throw e
        thrown = e
      } catch (t: Throwable) {
        thrown = t
      }
    }
    thrown?.let {
      debugLog("${bridge.name}: bind threw: $it")
      throw IllegalStateException("bridge bind failed: ${bridge.name}: ${it::class.simpleName ?: "a throwable"}")
    }
    if (bound != listOf(bridge.name)) {
      throw IllegalStateException("bridge bind failed: ${bridge.name} bound $bound (it must bind ${bridge.name}, once)")
    }
  }
}
