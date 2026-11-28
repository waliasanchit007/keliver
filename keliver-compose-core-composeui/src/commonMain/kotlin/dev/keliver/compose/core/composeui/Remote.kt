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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import dev.keliver.compose.remote.RemoteObjectWire
import dev.keliver.compose.remote.RemoteReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Remote objects on the host (MS4-43, G2; docs/COMPOSE_GENERIC_MECHANISMS.md section 1.2): the part
// written once. GeneratedRemote.kt writes, per state class, the dispatcher and the factory call; the
// renderer of a widget that takes the state (Generated.kt) calls `GeneratedRemoteObjects.host`, so the
// widget draws with the very state the guest's commands run on.

/**
 * What the generator writes per state class: field reads and command calls by lock tag. A tag this
 * host does not know (a newer guest's) is `null`: the field is ignored, the command fails.
 */
internal interface RemoteDispatcher<S> {
  fun read(state: S, member: Int): JsonElement?
  suspend fun invoke(state: S, member: Int, args: JsonObject): JsonElement?
}

/** How long a restorable field stays still before the host reports it ([RemoteObjectWire.restore]). */
internal const val SETTLE_MILLIS = 300L

/**
 * Runs the guest's commands on the host's real [state], and reports the fields the guest observes
 * ([RemoteObjectWire.observed]). Each command runs once, by its sequence number, and is acknowledged
 * when the real member returns: with its result, with the message of what it threw (a failed `require`
 * is the guest's bug, not a host crash), or as cancelled (Compose cancelled it, an interrupted scroll).
 * A command the guest takes off the wire (the caller was cancelled) is cancelled here with its slot, and
 * a finished one is forgotten once the guest has taken it off. The observed fields are reported from a
 * `snapshotFlow`, so on change, and at most once a frame; the restorable ones only once still.
 */
@Composable
internal fun <S> RemoteHost(wire: RemoteObjectWire, state: S, dispatcher: RemoteDispatcher<S>, report: (RemoteReport) -> Unit) {
  val currentReport by rememberUpdatedState(report)
  val started = remember { mutableSetOf<Int>() }
  started.retainAll(wire.commands.mapTo(HashSet()) { it.seq })
  for (command in wire.commands) {
    key(command.seq) {
      LaunchedEffect(Unit) {
        if (!started.add(command.seq)) return@LaunchedEffect
        val outcome = try {
          val result = dispatcher.invoke(state, command.member, command.args)
          if (result == null) RemoteReport(failed = mapOf(command.seq to "unknown command ${command.member}: this host does not have it (a newer guest?)")) else RemoteReport(done = mapOf(command.seq to result))
        } catch (e: CancellationException) {
          // The guest took the command off the wire (this coroutine is cancelled): nobody waits. Otherwise
          // Compose cancelled the call itself (an interrupted scroll): the guest's caller is cancelled too.
          if (!isActive) throw e
          RemoteReport(cancelled = listOf(command.seq))
        } catch (e: Exception) {
          RemoteReport(failed = mapOf(command.seq to (e.message ?: e.toString())))
        }
        currentReport(outcome)
      }
    }
  }
  val observed = wire.observed
  LaunchedEffect(observed) {
    snapshotFlow { readFields(state, dispatcher, observed) }
      .collect { if (it.isNotEmpty()) currentReport(RemoteReport(fields = it)) }
  }
  val restore = wire.restore
  if (restore.isNotEmpty()) {
    LaunchedEffect(restore) {
      // The first value is where the state starts: the guest knows it (its factory sent it). After that,
      // a trailing debounce: a drag or a fling reports once it has stopped, not on every frame.
      snapshotFlow { readFields(state, dispatcher, restore) }.drop(1).collectLatest {
        delay(SETTLE_MILLIS)
        currentReport(RemoteReport(settled = it))
      }
    }
  }
}

private fun <S> readFields(state: S, dispatcher: RemoteDispatcher<S>, tags: List<Int>): Map<Int, JsonElement> {
  val out = LinkedHashMap<Int, JsonElement>()
  for (tag in tags) {
    val value = try {
      dispatcher.read(state, tag)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
    if (value != null) out[tag] = value
  }
  return out
}
