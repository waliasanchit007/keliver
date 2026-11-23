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
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.portal.render.CompiledComponentProbe
import dev.keliver.protocol.Change
import dev.keliver.protocol.ChangesSink
import dev.keliver.protocol.guest.DefaultGuestProtocolAdapter
import dev.keliver.protocol.guest.ProtocolRedwoodComposition
import dev.keliver.protocol.guest.guestRedwoodVersion
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher
import dev.keliver.ui.UiConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Compiled component preview's failure probe: composes the compiled call once
 * in a throwaway guest composition — the canvas's own protocol widget system,
 * whose changes go nowhere — and reports what it threw. The initial
 * composition is synchronous, so the answer is known before the canvas
 * composes the call. A throw in the canvas composition itself would stop the
 * whole preview (Compose cannot catch it around a composable call).
 */
internal object DiscardingCompiledProbe : CompiledComponentProbe {
  private val noBack = object : OnBackPressedDispatcher {
    override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable =
      object : Cancellable {
        override fun cancel() {}
      }
  }

  override fun failureOf(content: @Composable () -> Unit): Throwable? {
    val guest = DefaultGuestProtocolAdapter(
      hostVersion = guestRedwoodVersion,
      widgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
    )
    guest.initChangesSink(object : ChangesSink {
      override fun sendChanges(changes: List<Change>) {}
    })
    val job = Job()
    val composition = ProtocolRedwoodComposition(
      scope = CoroutineScope(job + BroadcastFrameClock()),
      guestAdapter = guest,
      widgetVersion = EDITOR_WIDGET_VERSION.toUInt(),
      onBackPressedDispatcher = noBack,
      saveableStateRegistry = null,
      uiConfigurations = MutableStateFlow(UiConfiguration()),
    )
    return try {
      composition.setContent(content)
      null
    } catch (e: Throwable) {
      e
    } finally {
      runCatching { composition.cancel() }
      job.cancel()
    }
  }
}
