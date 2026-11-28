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
package dev.keliver.compose.core.composeui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.keliver.Modifier as KeliverModifier
import dev.keliver.compose.core.widget.ExtraRemoteObject
import dev.keliver.compose.remote.RemoteObjectWire
import dev.keliver.compose.remote.RemoteReport
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Remote objects on the host (MS4-43, G2; docs/COMPOSE_GENERIC_MECHANISMS.md section 1.2): the part
// written once. GeneratedRemote.kt writes, per state class, the dispatcher and the factory call.

/** What the generator writes per state class: field reads and command calls by lock tag. */
internal interface RemoteDispatcher<S> {
  fun read(state: S, member: Int): JsonElement
  suspend fun invoke(state: S, member: Int, args: JsonObject): JsonElement
}

/**
 * Runs the guest's commands on the host's real [state], and reports the fields the guest observes
 * ([RemoteObjectWire.observed]). Each command runs once, by its sequence number, and is acknowledged
 * (with its result) when the real member returns; the observed fields are reported from a
 * `snapshotFlow`, so on change, and at most once a frame.
 */
@Composable
internal fun <S> RemoteHost(wire: RemoteObjectWire, state: S, dispatcher: RemoteDispatcher<S>, report: (RemoteReport) -> Unit) {
  val currentReport by rememberUpdatedState(report)
  val started = remember { mutableSetOf<Int>() }
  for (command in wire.commands) {
    key(command.seq) {
      LaunchedEffect(command.seq) {
        if (!started.add(command.seq)) return@LaunchedEffect
        val result = dispatcher.invoke(state, command.member, command.args)
        currentReport(RemoteReport(done = mapOf(command.seq to result)))
      }
    }
  }
  val observed = wire.observed
  LaunchedEffect(observed) {
    snapshotFlow { observed.associateWith { dispatcher.read(state, it) } }
      .collect { currentReport(RemoteReport(fields = it)) }
  }
}

/**
 * The real states of the remote objects the guest has bound, by the proxy's id: what a renderer of
 * a widget that takes a state (a pager) reads to draw with the same state the guest's commands run on.
 */
public class RemoteObjects {
  private val states = mutableStateMapOf<String, Any>()

  /** The real state bound under [id] (a `PagerState`), or null while there is none. Reading it observes it. */
  public operator fun get(id: String): Any? = states[id]

  internal fun put(id: String, state: Any) {
    states[id] = state
  }

  internal fun remove(id: String, state: Any) {
    if (states[id] === state) states.remove(id)
  }
}

/** The host composition's [RemoteObjects]; `null` where nothing provides them (the states are then only the guest's commands' targets). */
public val LocalRemoteObjects: ProvidableCompositionLocal<RemoteObjects?> = staticCompositionLocalOf { null }

/**
 * The binding of one remote object ([ExtraRemoteObject]): creates the real state with the generated
 * factory, runs the guest's commands on it and publishes it in [LocalRemoteObjects]. Draws nothing.
 */
internal class ComposeUiExtraRemoteObject :
  ExtraRemoteObject<@Composable (Modifier) -> Unit>,
  GeneratedWidget {
  override var modifier: KeliverModifier by mutableStateOf<KeliverModifier>(KeliverModifier)
  override var modifierEventSink: ((Int) -> Unit)? = null

  private var wire: RemoteObjectWire? by mutableStateOf(null)
  private var onReport: ((RemoteReport) -> Unit)? by mutableStateOf(null)

  override fun wire(wire: RemoteObjectWire) {
    this.wire = wire
  }

  override fun onReport(onReport: (RemoteReport) -> Unit) {
    this.onReport = onReport
  }

  override val value: @Composable (Modifier) -> Unit = {
    val wire = wire
    if (wire != null) {
      val state = GeneratedRemoteObjects.host(wire) { report -> onReport?.invoke(report) }
      val registry = LocalRemoteObjects.current
      if (state != null && registry != null) {
        DisposableEffect(registry, wire.id, state) {
          registry.put(wire.id, state)
          onDispose { registry.remove(wire.id, state) }
        }
      }
    }
  }
}
