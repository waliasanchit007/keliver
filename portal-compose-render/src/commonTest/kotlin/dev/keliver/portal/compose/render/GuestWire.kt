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
package dev.keliver.portal.compose.render

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import dev.keliver.compose.gatea.protocol.guest.KeliverComposeGateAProtocolWidgetSystemFactory
import dev.keliver.protocol.Change
import dev.keliver.protocol.guest.DefaultGuestProtocolAdapter
import dev.keliver.protocol.guest.ProtocolRedwoodComposition
import dev.keliver.protocol.host.hostRedwoodVersion
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher
import dev.keliver.ui.UiConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A guest composition of the generated group whose changes are read as the protocol's JSON — what a
 * device's host would receive. Both the compiled screen and the canvas are measured with it.
 */
internal class GuestWire(content: @Composable () -> Unit) {
  val adapter = DefaultGuestProtocolAdapter(
    hostVersion = hostRedwoodVersion,
    widgetSystemFactory = KeliverComposeGateAProtocolWidgetSystemFactory,
  )
  private val clock = BroadcastFrameClock()
  private val composition = ProtocolRedwoodComposition(
    scope = CoroutineScope(Dispatchers.Unconfined + clock),
    guestAdapter = adapter,
    widgetVersion = UInt.MAX_VALUE,
    onBackPressedDispatcher = object : OnBackPressedDispatcher {
      override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable = object : Cancellable {
        override fun cancel() = Unit
      }
    },
    saveableStateRegistry = null,
    uiConfigurations = MutableStateFlow(UiConfiguration()),
  )

  init {
    composition.setContent(content)
  }

  /** The changes since the last call, as the protocol's JSON. */
  fun take(): String {
    clock.sendFrame(0L)
    return Json.encodeToString(ListSerializer(Change.serializer()), adapter.takeChanges())
  }

  fun cancel() = composition.cancel()
}
