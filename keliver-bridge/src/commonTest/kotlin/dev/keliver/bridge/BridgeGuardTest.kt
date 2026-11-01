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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class BridgeGuardTest {
  private val logged = mutableListOf<String>()

  // The app's context is the test's scheduler, so time is virtual and nothing runs until the
  // test lets it: "posted, not yet run" can be checked.
  private fun TestScope.guard() = BridgeGuard("ShopActions", StandardTestDispatcher(testScheduler)) { logged += it }

  @Test fun returnsTheAnswer() = runTest {
    assertEquals(true, guard().call("openRoute", 30) { true })
  }

  @Test fun aNullAnswerIsAnAnswerNotATimeout() = runTest {
    assertNull(guard().call<String?>("find", 30) { null })
  }

  @Test fun aThrowFailsWithItsClassOnlyAndLogsItsMessage() = runTest {
    val e = assertFailsWith<BridgeCallFailed> {
      guard().call<Unit>("share", 30) { throw IllegalStateException("token=secret") }
    }
    assertEquals("KeliverBridge: ShopActions.share: threw IllegalStateException", e.message)
    assertNull(e.cause)
    assertEquals(listOf("KeliverBridge: ShopActions.share threw IllegalStateException: token=secret"), logged)
  }

  @Test fun noAnswerInTimeFailsAndCancelsTheAppsCode() = runTest {
    var cancelled = false
    var heard = false
    val e = assertFailsWith<BridgeCallFailed> {
      guard().call<Unit>("share", 30) { call ->
        call.onCancel { heard = true }
        try {
          awaitCancellation()
        } finally {
          cancelled = true
        }
      }
    }
    assertEquals("KeliverBridge: ShopActions.share: no answer within 30 s", e.message)
    advanceUntilIdle()
    assertTrue(cancelled)
    assertTrue(heard)
  }

  @Test fun aTimeoutFailsTheCallRatherThanCancellingIt() = runTest {
    // The guest must see a failure: a TimeoutCancellationException would reach it as a cancel.
    val e = runCatching { guard().call<Unit>("share", 1) { awaitCancellation() } }.exceptionOrNull()
    assertTrue(e is BridgeCallFailed && e !is CancellationException, "got $e")
  }

  @Test fun noLimitWaitsForTheAnswer() = runTest {
    val answer = CompletableDeferred<String>()
    val result = async { guard().call("pickAddress", 0) { answer.await() } }
    advanceTimeBy(10.hours)
    assertFalse(result.isCompleted)
    answer.complete("home")
    assertEquals("home", result.await())
  }

  @Test fun theGuestsCancelCancelsTheAppsCodeAndRunsOnCancel() = runTest {
    var heard = false
    var started = false
    var cancelled = false
    val result = async {
      guard().call<Unit>("pickAddress", 0) { call ->
        started = true
        call.onCancel { heard = true }
        try {
          awaitCancellation()
        } finally {
          cancelled = true
        }
      }
    }
    runCurrent()
    assertTrue(started)
    result.cancel()
    advanceUntilIdle()
    assertTrue(cancelled)
    assertTrue(heard)
  }

  @Test fun anAnswerJustInTimeIsTheAnswerAndNotCancelled() = runTest {
    var heard = false
    val result = guard().call("openRoute", 30) { call ->
      call.onCancel { heard = true }
      delay(30.seconds - 1.milliseconds)
      true
    }
    advanceUntilIdle()
    assertTrue(result)
    assertFalse(heard)
  }

  @Test fun anAnswerJustTooLateIsATimeoutAndTheCallIsCancelled() = runTest {
    var heard = false
    var answered = false
    val e = assertFailsWith<BridgeCallFailed> {
      guard().call("openRoute", 30) { call ->
        call.onCancel { heard = true }
        delay(30.seconds + 1.milliseconds)
        answered = true
        true
      }
    }
    advanceUntilIdle()
    assertEquals("KeliverBridge: ShopActions.openRoute: no answer within 30 s", e.message)
    assertTrue(heard)
    assertFalse(answered)
  }

  @Test fun aStubsTodoIsAFailure() = runTest {
    val e = assertFailsWith<BridgeCallFailed> { guard().call<Unit>("share", 30) { TODO() } }
    assertEquals("KeliverBridge: ShopActions.share: threw NotImplementedError", e.message)
  }

  @Test fun anErrorFailsTheCallAndIsLoggedOnceNotThrownAtTheApp() = runTest {
    val e = assertFailsWith<BridgeCallFailed> { guard().call<Unit>("share", 30) { throw AssertionError("bad") } }
    assertEquals("KeliverBridge: ShopActions.share: threw AssertionError", e.message)
    advanceUntilIdle()
    assertEquals(listOf("KeliverBridge: ShopActions.share threw AssertionError: bad"), logged)
  }

  @Test fun aTodoInAPostOrOnCancelIsLoggedWithItsFunction() = runTest {
    val g = guard()
    g.post("track") { TODO("later") }
    val result = async {
      g.call<Unit>("pickAddress", 0) { call ->
        call.onCancel { TODO("close it") }
        awaitCancellation()
      }
    }
    runCurrent()
    result.cancel()
    advanceUntilIdle()
    assertEquals(
      listOf(
        "KeliverBridge: ShopActions.track threw NotImplementedError: An operation is not implemented: later",
        "KeliverBridge: ShopActions.pickAddress onCancel threw NotImplementedError: An operation is not implemented: close it",
      ),
      logged,
    )
  }

  @Test fun anAnswerAtTheLimitStillCounts() = runTest {
    // The limit fires first and the app answers before the guest's wait resumes: the answer
    // is already in when the wait ends, and it stands.
    var heard = false
    val result = guard().call("openRoute", 30) { call ->
      call.onCancel { heard = true }
      delay(30.seconds)
      true
    }
    advanceUntilIdle()
    assertTrue(result)
    assertFalse(heard)
  }

  @Test fun theGuestCancellingAfterTheAnswerIsInDoesNotRunOnCancel() = runTest {
    // The app's code runs at once (unconfined); the guest's wait resumes later, on the test's
    // scheduler, and is cancelled before it does.
    val g = BridgeGuard("ShopActions", UnconfinedTestDispatcher(testScheduler))
    val gate = CompletableDeferred<Unit>()
    var heard = false
    var call: BridgeCall? = null
    val result = async {
      g.call("openRoute", 0) { c ->
        call = c
        c.onCancel { heard = true }
        gate.await()
        true
      }
    }
    runCurrent()
    gate.complete(Unit)
    result.cancel()
    advanceUntilIdle()
    assertFalse(heard)
    assertFalse(call!!.isCancelled)
  }

  @Test fun aLogThatThrowsEndsNeitherTheCallNorTheProcess() = runTest {
    // Were the guard's scope handler to throw (it logs), kotlinx would pass that on and runTest
    // would fail: this also shows nothing escapes.
    val g = BridgeGuard("ShopActions", StandardTestDispatcher(testScheduler)) { throw IllegalStateException("console down") }
    val e = assertFailsWith<BridgeCallFailed> { g.call<Unit>("share", 30) { error("boom") } }
    assertEquals("KeliverBridge: ShopActions.share: threw IllegalStateException", e.message)
    g.post("track") { error("boom") }
    advanceUntilIdle()
  }

  @Test fun aJobInTheAppsContextDoesNotEndItsCalls() = runTest {
    // An app passing a lifecycle's context: the guard keeps the dispatcher, not the Job.
    val lifecycle = Job()
    val g = BridgeGuard("ShopActions", StandardTestDispatcher(testScheduler) + lifecycle)
    lifecycle.cancel()
    assertTrue(g.call("openRoute", 30) { true })
    var heard = false
    val result = async {
      g.call<Unit>("pickAddress", 0) { call ->
        call.onCancel { heard = true }
        awaitCancellation()
      }
    }
    runCurrent()
    result.cancel()
    advanceUntilIdle()
    assertTrue(heard)
  }

  @Test fun aNegativeLimitIsRefused() = runTest {
    assertFailsWith<IllegalArgumentException> { guard().call("openRoute", -1) { true } }
  }

  @Test fun aCallCancelledBeforeItWasPostedNeverRuns() = runTest {
    // The app's context is held: a main thread that has not got to the call yet.
    val held = HeldDispatcher()
    var started = false
    val result = async { BridgeGuard("ShopActions", held).call<Unit>("share", 30) { started = true } }
    runCurrent()
    assertEquals(1, held.waiting.size, "the app's code is posted")
    result.cancel()
    runCurrent()
    held.runAll()
    assertFalse(started)
  }

  private class HeldDispatcher : CoroutineDispatcher() {
    val waiting = mutableListOf<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) {
      waiting += block
    }
    fun runAll() {
      while (waiting.isNotEmpty()) waiting.removeAt(0).run()
    }
  }

  @Test fun onCancelStillRunsWhenTheCallersScopeIsCancelled() = runTest {
    // A code reload closes the guest's Zipline, which cancels its calls with their scope.
    var heard = false
    val zipline = CoroutineScope(Job() + coroutineContext[CoroutineDispatcher]!!)
    val g = guard()
    zipline.launch {
      g.call<Unit>("pickAddress", 0) { call ->
        call.onCancel { heard = true }
        awaitCancellation()
      }
    }
    runCurrent()
    zipline.coroutineContext[Job]!!.cancel()
    advanceUntilIdle()
    assertTrue(heard)
  }

  @Test fun onCancelRegisteredAfterTheCancelRunsAtOnce() = runTest {
    var late: BridgeCall? = null
    val result = async {
      guard().call<Unit>("pickAddress", 0) { call ->
        late = call
        awaitCancellation()
      }
    }
    runCurrent()
    result.cancel()
    advanceUntilIdle()
    assertTrue(late!!.isCancelled)
    var heard = false
    late!!.onCancel { heard = true }
    assertFalse(heard, "it is posted to the app's context, not run on the caller's thread")
    advanceUntilIdle()
    assertTrue(heard)
  }

  @Test fun onCancelIsNotRunForAnAnsweredCall() = runTest {
    var call: BridgeCall? = null
    var heard = false
    guard().call("openRoute", 30) { c ->
      call = c
      c.onCancel { heard = true }
      true
    }
    call!!.onCancel { heard = true }
    advanceUntilIdle()
    assertFalse(heard)
    assertFalse(call!!.isCancelled)
  }

  @Test fun aCancellationTheAppsCodeThrowsIsAFailureNotAHang() = runTest {
    val e = assertFailsWith<BridgeCallFailed> {
      guard().call<Unit>("share", 0) { withTimeout(1.seconds) { awaitCancellation() } }
    }
    assertEquals("KeliverBridge: ShopActions.share: threw TimeoutCancellationException", e.message)
  }

  @Test fun theFirstDoneWinsAndLaterAnswersAreIgnored() = runTest {
    val result = guard().callback<Int>("count", 30) { _, done, fail ->
      done(1)
      done(2)
      fail("too late")
    }
    assertEquals(1, result)
  }

  @Test fun theFirstFailWinsWithTheAppsMessageOnOneLine() = runTest {
    val e = assertFailsWith<BridgeCallFailed> {
      guard().callback<Int>("count", 30) { _, done, fail ->
        fail("no address\n  on file")
        done(1)
      }
    }
    assertEquals("KeliverBridge: ShopActions.count: no address on file", e.message)
    assertTrue(logged.isEmpty())
  }

  @Test fun aCallbackAnsweredLaterIsAwaited() = runTest {
    var answer: ((String) -> Unit)? = null
    val result = async { guard().callback<String>("pickAddress", 0) { _, done, _ -> answer = done } }
    runCurrent()
    assertFalse(result.isCompleted)
    answer!!("home")
    assertEquals("home", result.await())
  }

  @Test fun aThrowAfterTheAnswerIsLoggedAndTheAnswerStands() = runTest {
    val result = guard().callback<Int>("count", 30) { _, done, _ ->
      done(1)
      error("after")
    }
    assertEquals(1, result)
    assertEquals(listOf("KeliverBridge: ShopActions.count threw after answering: IllegalStateException: after"), logged)
  }

  @Test fun aCallbackThatThrowsFails() = runTest {
    val e = assertFailsWith<BridgeCallFailed> {
      guard().callback<Int>("count", 30) { _, _, _ -> error("boom") }
    }
    assertEquals("KeliverBridge: ShopActions.count: threw IllegalStateException", e.message)
  }

  @Test fun postReturnsAtOnceAndRunsLaterOnTheAppsContext() = runTest {
    var ran = false
    guard().post("track") { ran = true }
    assertFalse(ran)
    advanceUntilIdle()
    assertTrue(ran)
  }

  @Test fun aThrowFromAPostIsLoggedAndDropped() = runTest {
    guard().post("track") { error("boom") }
    advanceUntilIdle()
    assertEquals(listOf("KeliverBridge: ShopActions.track threw IllegalStateException: boom"), logged)
  }

  @Test fun theAppsCodeRunsOnTheAppsContext() = runTest {
    val app = StandardTestDispatcher(testScheduler, name = "app")
    val g = BridgeGuard("ShopActions", app)
    assertTrue(g.call("openRoute", 30) { currentCoroutineContext()[CoroutineDispatcher] === app })
  }
}
