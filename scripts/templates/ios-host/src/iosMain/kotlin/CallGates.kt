/*
 * A held app's calls out, held (StartPolicy.kt's CallGate; docs/DEVICE_HOST.md, "Start policy"):
 * a PARALLEL start's cached load during its lookup, and an update loading in a new app, run their
 * guest code before they show. What they send out waits until they show, and fails, unsent, when
 * they are dropped. The SAME file is in the Android and the iOS host; keep them identical.
 */
package @@PACKAGE@@

import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Suspends until the gate is decided: true when the call may go. Cancellable: a guest that stops
 * waiting stops waiting here too. For the host's app it is one read.
 */
internal suspend fun CallGate.await(): Boolean =
  isOpen || suspendCancellableCoroutine { c -> whenDecided { open -> if (c.isActive) c.resume(open) } }

/** Why a dropped app's call failed. */
internal const val DROPPED_CALL = "the release that made this call was not shown (the host loaded a newer one): not sent"

/**
 * HostHttp for one app: [delegate]'s, once [gate] is open. A held app's request waits for it (the
 * guest's call is suspend anyway); a dropped app's fails before anything is sent.
 */
internal class GatedHostHttp(private val delegate: HostHttpProvider, private val gate: CallGate) : HostHttpProvider {
  override suspend fun execute(request: HttpRequest): HttpResponse {
    if (!gate.await()) throw IllegalStateException("HostHttp: $DROPPED_CALL")
    return delegate.execute(request)
  }
}

/**
 * The context an app's bridges run the app's code on ([KeliverBridgeScope.main]): [target]'s
 * threads, once [gate] is open. Until then what a held app's bridge call would run waits; when the
 * app is dropped, its coroutine is cancelled first, so the app's code never runs (the bridge's
 * guard answers the guest with a failure; a fire-and-forget call is not run).
 */
internal class GatedDispatcher(private val target: CoroutineDispatcher, private val gate: CallGate) : CoroutineDispatcher() {
  override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    if (gate.isOpen) return target.dispatch(context, block)
    gate.whenDecided { open ->
      if (!open) context[Job]?.cancel()
      target.dispatch(context, block)
    }
  }

  override fun toString(): String = "GatedDispatcher($target)"
}
