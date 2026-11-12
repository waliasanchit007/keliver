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
package dev.keliver.compose.gatea.sample

import androidx.compose.runtime.Composable
import app.cash.zipline.Zipline
import dev.keliver.compose.gatea.composeui.samples.guest.SampleCard
import dev.keliver.compose.gatea.composeui.samples.guest.SampleOrders
import dev.keliver.compose.gatea.protocol.guest.KeliverComposeGateAProtocolWidgetSystemFactory
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
import kotlinx.serialization.json.Json

/** Zipline's entry point: the host has bound its services; bind the samples for it to take. */
fun main() {
  val zipline = Zipline.get()
  zipline.bind<GateBSamples>("GateBSamples", RealGateBSamples(zipline.json))
}

private class RealGateBSamples(json: Json) : GateBSamples {
  override val appLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = KeliverComposeGateAProtocolWidgetSystemFactory,
    json = json,
    widgetVersion = 1U,
  )

  override fun launch(name: String): ZiplineTreehouseUi = SampleUi(name).asZiplineTreehouseUi(appLifecycle)
}

private class SampleUi(private val name: String) : TreehouseUi {
  @Composable
  override fun Show() {
    when (name) {
      SAMPLE_CARD -> SampleCard(GateBSampleData.CARD_TITLE, GateBSampleData.CARD_QUANTITY, GateBSampleData.tags) {}
      else -> SampleOrders(GateBSampleData.orders, onBack = {}, onOpen = {})
    }
  }
}
