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
package dev.keliver.treehouse

import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.runTest

/**
 * The order a production host's start policy PARALLEL meets (PR #249's review, M1): the cached
 * release's load finishes at about the moment the lookup names a different manifest.
 *
 * CodeHost installs a finished load from a block it queues on the UI dispatcher, under the code
 * session's own scope, and that block only checks that SOME load is in progress. So stop() and
 * start() on the SAME app, run before that block, do not discard it: the stale session becomes the
 * running one. That is why the hosts load the speculative release in a TreehouseApp of its own and
 * close() it when they drop it: a closed app's queued block finds it stopped and discards the load.
 */
class SpeculativeLoadOrderTest {
  /** The UI dispatcher, holding what is dispatched to it while [closed]; [open] runs that at once, in order. */
  private class Gate(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var closed = true
      private set
    private val held = ArrayDeque<Runnable>()
    val heldCount: Int get() = held.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      if (closed) held.addLast(block) else delegate.dispatch(context, block)
    }

    fun open() {
      closed = false
      while (held.isNotEmpty()) held.removeFirst().run()
    }
  }

  /** Each successful code load's Zipline, in order. */
  private class Loads : EventListener.Factory {
    val loaded = mutableListOf<Zipline>()

    override fun create(app: TreehouseApp<*>, manifestUrl: String?) = object : EventListener() {
      override fun codeLoadSuccess(manifest: ZiplineManifest, zipline: Zipline, startValue: Any?) {
        loaded += zipline
      }
    }

    override fun close() {}
  }

  @Test
  fun stopAndStartOnTheSameAppStillInstallsTheLoadThatFinishedFirst() = runTest {
    val gate = Gate(dispatcher())
    val loads = Loads()
    val tester = TreehouseTester(this, uiDispatcher = gate)
    tester.eventListenerFactory = loads
    val app = tester.loadApp()

    // The cached load runs to its end; installing it waits on the UI dispatcher.
    app.start()
    testScheduler.advanceUntilIdle()
    assertThat(loads.loaded.size).isEqualTo(1)
    assertThat(gate.heldCount).isGreaterThan(0)

    // The lookup names another manifest: the same app is stopped and started for it, before the
    // UI dispatcher runs the cached load's queued block.
    app.stop()
    app.start()
    gate.open()

    // The cached load is the running code now, although the app was stopped after it loaded.
    assertThat(app.zipline.value).isSameInstanceAs(loads.loaded[0])

    testScheduler.advanceUntilIdle()
    app.stop()
    app.close()
  }

  @Test
  fun aClosedAppNeverInstallsItsLoadAndANewAppRunsOnlyItsOwn() = runTest {
    val gate = Gate(dispatcher())
    val loads = Loads()
    val tester = TreehouseTester(this, uiDispatcher = gate)
    tester.eventListenerFactory = loads

    // The cached release, in an app of its own: it loads; installing it waits.
    val cached = tester.loadApp()
    cached.start()
    testScheduler.advanceUntilIdle()
    assertThat(loads.loaded.size).isEqualTo(1)
    assertThat(gate.heldCount).isGreaterThan(0)

    // The lookup names another manifest: the host closes the cached app and starts a new one.
    cached.stop()
    cached.close()
    val network = tester.loadApp()
    network.start()
    gate.open()

    // The cached load's block found its app stopped and discarded it; the new app runs nothing yet.
    assertThat(cached.zipline.value).isNull()
    assertThat(network.zipline.value).isNull()

    // The new app runs its own load, never the cached one.
    testScheduler.advanceUntilIdle()
    assertThat(loads.loaded.size).isEqualTo(2)
    assertThat(network.zipline.value).isSameInstanceAs(loads.loaded[1])
    assertThat(cached.zipline.value).isNull()

    network.stop()
    network.close()
  }
}
