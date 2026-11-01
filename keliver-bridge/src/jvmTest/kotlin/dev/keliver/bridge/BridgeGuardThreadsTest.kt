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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

class BridgeGuardThreadsTest {
  // done and fail from many threads at once (an app answering from its own queues): one answer
  // wins, the guest gets exactly that one, and the answers that lose return quietly on their
  // own threads instead of throwing there.
  @Test fun oneOfManyConcurrentAnswersWinsAndTheRestReturnQuietly() {
    val threads = Executors.newFixedThreadPool(8)
    val thrown = ConcurrentLinkedQueue<Throwable>()
    try {
      val guard = BridgeGuard("ShopActions")
      repeat(200) {
        val all = CountDownLatch(8)
        val result = runCatching {
          runBlocking {
            guard.callback<Int>("count", 30) { _, done, fail ->
              val go = CountDownLatch(1)
              for (i in 0 until 8) {
                threads.execute {
                  try {
                    go.await()
                    if (i % 2 == 0) done(i) else fail("no $i")
                  } catch (t: Throwable) {
                    thrown += t
                  } finally {
                    all.countDown()
                  }
                }
              }
              go.countDown()
            }
          }
        }
        assertTrue(all.await(10, TimeUnit.SECONDS), "every answer returned")
        val answer = result.getOrNull()
        val failure = result.exceptionOrNull()
        assertTrue(
          (answer != null && answer % 2 == 0) ||
            (failure is BridgeCallFailed && failure.message!!.matches(Regex("KeliverBridge: ShopActions.count: no [1357]"))),
          "got $result",
        )
      }
      assertEquals(emptyList(), thrown.toList())
    } finally {
      threads.shutdownNow()
    }
  }

  // A context that will not run anything (an executor shut down with its app, a quit Looper):
  // the call fails at once with a reason, and a post or an onCancel there is logged as not run.
  @Test fun aContextThatRefusesTheCallFailsIt() {
    val closed = Executors.newSingleThreadExecutor().apply { shutdown() }
    val logged = ConcurrentLinkedQueue<String>()
    val guard = BridgeGuard("ShopActions", closed.asCoroutineDispatcher()) { logged += it }
    val e = runCatching { runBlocking { guard.call("openRoute", 30) { true } } }.exceptionOrNull()
    assertEquals("KeliverBridge: ShopActions.openRoute: the app's context did not run it", e?.message)
    guard.post("track") { }
    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (logged.none { it.startsWith("KeliverBridge: ShopActions.track was not run: ") } && System.nanoTime() < until) {
      Thread.sleep(10)
    }
    assertTrue(logged.any { it.startsWith("KeliverBridge: ShopActions.track was not run: ") }, "logged: $logged")
  }
}
