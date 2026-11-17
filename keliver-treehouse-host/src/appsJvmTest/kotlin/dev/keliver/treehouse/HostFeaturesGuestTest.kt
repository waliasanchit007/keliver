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
package dev.keliver.treehouse

import app.cash.zipline.Zipline
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.example.redwood.testapp.testing.ButtonValue
import com.example.redwood.testapp.testing.TextInputValue
import com.example.redwood.testapp.treehouse.TestAppPresenter
import dev.keliver.compose.ExperimentalKeliverHostFeaturesApi
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * D2 at run time, end to end: the features a host's [TreehouseApp.Spec.hostFeatures] declares
 * reach a real guest (the test app, Kotlin/JS in QuickJS) as `LocalHostFeatures`, through
 * `AppLifecycle.Host.hostFeatures` and the guest's `bind`. If any link drops them, every host
 * silently falls back to keliver-nav's guest-side frame; this test is what notices.
 */
class HostFeaturesGuestTest {
  @Test fun theFeaturesASpecDeclaresReachTheGuest() = runTest {
    val tester = TreehouseTester(this)
    tester.declareFeatures(setOf("other@2", "keliver.nav.retainedContainer@1"))

    assertThat(tester.guestsReport()).isEqualTo("features: keliver.nav.retainedContainer@1,other@2")
  }

  @Test fun aSpecThatDeclaresNoneGivesTheGuestNone() = runTest {
    val tester = TreehouseTester(this)

    assertThat(tester.guestsReport()).isEqualTo("features: ")
  }

  /** Opens the tester's screen, switches it to the guest's report, and returns what it shows. */
  private suspend fun TreehouseTester.guestsReport(): String {
    val treehouseApp = loadApp()
    val content = content(treehouseApp)
    val view = view()
    content.bind(view)
    content.awaitContent(1)
    (view.views.single() as TextInputValue).onChange!!.invoke("HostFeaturesTestReport")
    sendFrame()
    content.awaitContent(2)
    val report = (view.views.single() as ButtonValue).text.toString()
    treehouseApp.stop()
    treehouseApp.close()
    return report
  }

  private fun TreehouseTester.declareFeatures(features: Set<String>) {
    val original = spec
    spec = object : TreehouseApp.Spec<TestAppPresenter>() {
      override val name get() = original.name
      override val manifestUrl get() = original.manifestUrl
      override val loadCodeFromNetworkOnly get() = original.loadCodeFromNetworkOnly

      @ExperimentalKeliverHostFeaturesApi
      override val hostFeatures get() = features

      override suspend fun bindServices(treehouseApp: TreehouseApp<TestAppPresenter>, zipline: Zipline) {
        original.bindServices(treehouseApp, zipline)
      }

      override fun create(zipline: Zipline) = original.create(zipline)
    }
  }
}
