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
package dev.keliver.treehouse.composeui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import app.cash.zipline.Zipline
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import dev.keliver.compose.ExperimentalKeliverHostFeaturesApi
import dev.keliver.treehouse.TreehouseApp
import hosttemplate.HostFeatures
import hosttemplate.KELIVER_LINE
import hosttemplate.KeliverSpec
import hosttemplate.KeliverTreehouseContent
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf

/**
 * NF5 H0: the host templates' own code for this checkout's line (its files in
 * scripts/templates/production-host/src/line-0.5/kotlin/, KeliverLine.kt and HostFeatures.kt;
 * compiled in by this module's build.gradle), against this checkout's TreehouseContent and
 * TreehouseApp.Spec.
 */
@OptIn(ExperimentalTestApi::class)
class HostTemplateLineTest {
  private val platform = FakePlatformBackDispatcher()

  @Test
  fun theLineFileIsTheLineThisCheckoutBuilds() {
    assertThat(KELIVER_LINE).isEqualTo("0.5")
  }

  @OptIn(ExperimentalKeliverHostFeaturesApi::class)
  @Test
  fun theHostsSpecHandsTreehouseTheFeaturesTheHostDeclares() {
    val spec = object : KeliverSpec<FakeAppService>() {
      override val name = "template"
      override val manifestUrl = flowOf("https://example.invalid/manifest.zipline.json")
      override suspend fun bindServices(treehouseApp: TreehouseApp<FakeAppService>, zipline: Zipline) = Unit
      override fun create(zipline: Zipline): FakeAppService = error("unused")
    }

    assertThat(spec.hostFeatures).isEqualTo(HostFeatures.implemented)
  }

  /** As KeliverScreen.kt and MainViewController.kt draw an update swap: `backEnabled = !hidden`. */
  @Test
  fun backWithTheTemplatesHiddenContentReachesNoGuest() = runComposeUiTest {
    val shown = FakeApp("shown guest", platform.received)
    val hidden = FakeApp("hidden guest", platform.received)
    setContent {
      Box {
        TemplateContent(hidden, hidden = true)
        TemplateContent(shown, hidden = false)
      }
    }
    waitForIdle()
    shown.guestRegistersBackHandler()
    hidden.guestRegistersBackHandler()

    platform.back()

    assertThat(platform.received).containsExactly("shown guest")
  }

  @Composable
  private fun TemplateContent(app: FakeApp, hidden: Boolean) {
    CompositionLocalProvider(LocalDesktopBackDispatcher provides platform) {
      KeliverTreehouseContent(
        treehouseApp = app,
        widgetSystem = NoWidgets,
        contentSource = { error("the fake content never asks for a UI") },
        modifier = Modifier,
        dynamicContentWidgetFactory = EmptyDynamicContentWidgetFactory,
        backEnabled = !hidden,
      )
    }
  }
}
