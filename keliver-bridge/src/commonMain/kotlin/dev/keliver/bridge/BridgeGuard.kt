/*
 * Copyright (C) 2026 Square, Inc.
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
package dev.keliver.bridge

import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Every call a host serves through a generated bridge's `bind` goes through this, the same on
 * Android and iOS (`docs/BRIDGE_CODEGEN_DESIGN.md`, section 5). The app's code runs on
 * [context], launched in the guard's own scope, and only its answer is awaited, so a busy or
 * blocked [context] fails the call in time instead of holding the guest's call. Failures reach
 * the guest as [BridgeCallFailed], on one line; a throw's message goes only to [log].
 *
 * @param bridge the bridge's name, for messages.
 * @param context where the app's code runs: `Dispatchers.Main` for code that touches UI. It
 *   must dispatch: with `Dispatchers.Unconfined`, or `Dispatchers.Main.immediate` called on the
 *   main thread, the app's code runs inline, before the limit starts, and a call that blocks
 *   holds the guest's call.
 * @param log the host's debug console: a throw's message, which is never sent to the guest. A
 *   `log` that throws is ignored.
 */
public class BridgeGuard(
  private val bridge: String,
  context: CoroutineContext = Dispatchers.Default,
  private val log: (String) -> Unit = {},
) {
  // A Job in the app's context (a lifecycle's, say) would replace the guard's own parent, and
  // cancelling it would end the app's code quietly and onCancel with it; the app's exception
  // handler would replace the guard's. Both are dropped: its dispatcher and the rest are kept.
  private val context = context.minusKey(Job).minusKey(CoroutineExceptionHandler)

  // Never cancelled: it holds only launches in flight, so closing the guest's Zipline (which
  // cancels its calls) still runs onCancel, and it is dropped with the binding. The launches
  // catch everything themselves, and logging cannot throw; the handler only keeps anything
  // still escaping from the thread's uncaught handler, which would end the app's process.
  private val scope = CoroutineScope(
    SupervisorJob() + CoroutineExceptionHandler { _, e -> safeLog("${BridgeCallFailed.PREFIX}$bridge: ${e.describe()}") },
  )

  /**
   * Runs [body] on [context] and returns its answer. It fails with [BridgeCallFailed] when
   * [body] throws, or when there is no answer within [timeoutSeconds] (0: no limit; the guest's
   * cancel still ends the call). When the guest stops waiting, [body] is cancelled (or never
   * started) and the [BridgeCall]'s `onCancel` blocks run. An answer and the end of the wait
   * are settled once, by [CompletableDeferred.complete]: an answer that comes as the time runs
   * out is either the answer or a timeout, never both.
   */
  public suspend fun <T> call(
    function: String,
    timeoutSeconds: Int,
    body: suspend (BridgeCall) -> T,
  ): T {
    require(timeoutSeconds >= 0) { "timeoutSeconds must be 0 (no limit) or more: $timeoutSeconds" }
    val call = BridgeCall { block -> runOnContext(function, "onCancel", block) }
    val outcome = CompletableDeferred<Answer<T>>()
    val job = scope.launch(context) {
      val answer: Answer<T> = try {
        Answer.Value(body(call))
      } catch (e: Throwable) {
        // Our own cancel ends it quietly; a cancellation the app's code threw is a failure, and
        // so is an Error (a stub's TODO(), or out of memory): it is logged here, once, and kept
        // from the thread's uncaught handler, which would end the app's process.
        if (e is CancellationException) ensureActive()
        answerFor(function, e)
      }
      outcome.complete(answer)
    }
    // A context that refused the call, or a resumption of it (a closed executor: kotlinx
    // cancels the job), still answers, unless the guard had already ended the wait.
    job.invokeOnCompletion {
      if (it != null && outcome.complete(Answer.Failed("the app's context refused it"))) {
        safeLog("${BridgeCallFailed.PREFIX}$bridge.$function was refused by its context: ${it.describe()}")
      }
    }

    // Ends the wait unless an answer is already in, which then stands.
    fun settle(end: Answer<T>): Answer<T> {
      if (!outcome.complete(end)) return outcome.getCompletedAnswer()
      job.cancel()
      call.cancel()
      return end
    }

    val waited: Answer<T>? = try {
      // withTimeoutOrNull, not withTimeout: a timeout must fail the guest's call, and a
      // TimeoutCancellationException would reach it as a quiet cancel. The answer is boxed, so a
      // null result is not taken for a timeout.
      if (timeoutSeconds > 0) withTimeoutOrNull(timeoutSeconds.seconds) { outcome.await() } else outcome.await()
    } catch (e: CancellationException) {
      // The guest stopped waiting: the app's code stops (or never starts).
      if (settle(Answer.TimedOut) !is Answer.TimedOut) call.answered()
      throw e
    }
    val got = waited ?: settle(Answer.TimedOut)
    if (got !is Answer.TimedOut) call.answered()
    return when (got) {
      is Answer.TimedOut -> throw BridgeCallFailed(bridge, function, "no answer within $timeoutSeconds s")
      is Answer.Value -> got.value
      is Answer.Failed -> throw BridgeCallFailed(bridge, function, got.reason)
    }
  }

  /**
   * [call] for an app's callback: [start] runs on [context] with the call and its `done` and
   * `fail`. The first of them wins and later ones are ignored, as is a throw after it; `fail`'s
   * message is the reason the guest gets.
   */
  public suspend fun <T> callback(
    function: String,
    timeoutSeconds: Int,
    start: (call: BridgeCall, done: (T) -> Unit, fail: (String) -> Unit) -> Unit,
  ): T = call(function, timeoutSeconds) { call ->
    val first = CompletableDeferred<Answer<T>>()
    try {
      start(call, { first.complete(Answer.Value(it)) }, { first.complete(Answer.Failed(it)) })
    } catch (e: Throwable) {
      if (!first.isCompleted) throw e
      safeLog("${BridgeCallFailed.PREFIX}$bridge.$function threw after answering: ${e.describe()}")
    }
    when (val answer = first.await()) {
      is Answer.Value -> answer.value
      is Answer.Failed -> throw AppFailed(answer.reason)
      is Answer.TimedOut -> error("not an app's answer")
    }
  }

  /** A fire-and-forget call: [body] runs later, on [context]; a throw goes to [log]. */
  public fun post(function: String, body: () -> Unit) {
    runOnContext(function, "", body)
  }

  private fun runOnContext(function: String, what: String, block: () -> Unit) {
    val name = "${BridgeCallFailed.PREFIX}$bridge.$function${if (what.isEmpty()) "" else " $what"}"
    // Nothing cancels these launches but a context that refused them.
    scope.launch(context) {
      try {
        block()
      } catch (e: Throwable) {
        if (e is CancellationException) ensureActive()
        safeLog("$name threw ${e.describe()}")
      }
    }.invokeOnCompletion { if (it != null) safeLog("$name was not run: ${it.describe()}") }
  }

  private fun safeLog(message: String) {
    try {
      log(message)
    } catch (_: Throwable) {
      // The host's console failing must not end its process, or the guest's call.
    }
  }

  private fun <T> answerFor(function: String, e: Throwable): Answer<T> {
    if (e is AppFailed) return Answer.Failed(e.reason)
    // Its message could hold the app's data: the guest gets the class only.
    safeLog("${BridgeCallFailed.PREFIX}$bridge.$function threw ${e.describe()}")
    return Answer.Failed("threw ${e::class.simpleName ?: "an exception"}")
  }

  private fun Throwable.describe() = "${this::class.simpleName}: $message"

  private sealed interface Answer<out T> {
    class Value<T>(val value: T) : Answer<T>
    class Failed(val reason: String) : Answer<Nothing>
    object TimedOut : Answer<Nothing>
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun <T> CompletableDeferred<Answer<T>>.getCompletedAnswer(): Answer<T> = getCompleted()

  // The app's `fail(message)`, carried out of [callback]'s body.
  private class AppFailed(val reason: String) : Exception(reason)
}
