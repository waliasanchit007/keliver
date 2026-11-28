/*
 * Copyright (C) 2026 Keliver contributors.
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
@file:OptIn(KeliverFrameFoldApi::class)

package dev.keliver.treehouse

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isTrue
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import okio.FileSystem

/** I10 with real threads: two writers and a reader on one key, and writers racing a session change. */
class FrameStoreConcurrencyTest {
  private val parent = scratchDirectory()
  private val secure = SecureRandom()
  private val clock = FakeFrameClock()

  @AfterTest fun cleanUp() {
    FileSystem.SYSTEM.deleteRecursively(parent, mustExist = false)
  }

  private fun store(): FrameStore = FrameStore(
    FileSystem.SYSTEM,
    parent / "frames",
    "build-1",
    clock,
    { n -> ByteArray(n).also { secure.nextBytes(it) } },
  ).also { it.session("user-1") }

  @Test fun aReaderOnlyEverSeesAWholeFrameFromOneOfTheTwoWriters() {
    val store = store()
    val stop = AtomicBoolean(false)
    val problems = CopyOnWriteArrayList<String>()
    val reads = java.util.concurrent.atomic.AtomicInteger()
    val start = CountDownLatch(1)

    fun writer(text: String) = Thread {
      start.await()
      repeat(300) {
        val result = store.write(CACHE, baseKey, snapshotOf(text), FrameFoldContent.Whole, 60_000)
        if (result !is FrameWrite.Stored) problems += "write: $result"
      }
    }

    val reader = Thread {
      start.await()
      while (!stop.get()) {
        when (val read = store.read(CACHE, baseKey)) {
          is FrameRead.Hit -> {
            val text = textOf(read.frame)
            reads.incrementAndGet()
            if (text != "A" && text != "B") problems += "read text $text"
          }
          is FrameRead.Miss -> if (read.reason != FrameMiss.Absent) problems += "miss ${read.reason}"
        }
      }
    }

    val a = writer("A")
    val b = writer("B")
    reader.start()
    a.start()
    b.start()
    start.countDown()
    a.join()
    b.join()
    stop.set(true)
    reader.join()

    assertThat(problems.toList()).isEmpty()
    assertThat(reads.get() > 0).isTrue()
    val last = (store.read(CACHE, baseKey) as FrameRead.Hit).frame
    assertThat(textOf(last) in listOf("A", "B")).isTrue()
    val leftovers = FileSystem.SYSTEM.listRecursively(parent).filter { it.name.endsWith(".tmp") }.toList()
    assertThat(leftovers).isEmpty()
  }
}
