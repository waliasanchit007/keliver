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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

// done and fail from many threads at once (an app answering from its own queues): one answer
// wins, and the guest gets exactly that one.
class BridgeGuardThreadsTest {
  @Test fun oneOfManyConcurrentAnswersWins() {
    val threads = Executors.newFixedThreadPool(8)
    try {
      val guard = BridgeGuard("ShopActions")
      repeat(200) {
        val result = runCatching {
          runBlocking {
            guard.callback<Int>("count", 30) { _, done, fail ->
              val go = CountDownLatch(1)
              for (i in 0 until 8) {
                threads.execute {
                  go.await()
                  if (i % 2 == 0) done(i) else fail("no $i")
                }
              }
              go.countDown()
            }
          }
        }
        val answer = result.getOrNull()
        val failure = result.exceptionOrNull()
        assertTrue(
          (answer != null && answer % 2 == 0) ||
            (failure is BridgeCallFailed && failure.message!!.matches(Regex("KeliverBridge: ShopActions.count: no [1357]"))),
          "got $result",
        )
      }
    } finally {
      threads.shutdownNow()
    }
  }
}
