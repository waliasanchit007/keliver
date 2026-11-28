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
package dev.keliver.compose.remote

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Remote objects (MS4-43, G2; docs/COMPOSE_GENERIC_MECHANISMS.md section 1.2): a host-owned state
 * class (`PagerState`) has a generated guest proxy and a generated host dispatcher. The runtime they
 * share is here, written once. Members cross by their lock tag (`remotes` in the group's lock), never
 * by name: a field or command is an Int on the wire.
 */

/**
 * One command the guest sent and the host has not acknowledged yet: [seq] is the guest's sequence
 * number (the host runs each once), [member] the command's lock tag, [args] its arguments by
 * parameter name, in each type's wire form.
 */
@Serializable
public data class RemoteCommand(val seq: Int, val member: Int, val args: JsonObject)

/**
 * What the guest sends the host as its widget's property (the state's binding):
 * - [id]: the guest's name for this object;
 * - [type]: the class's lock tag, which tells the host which factory and dispatcher to use;
 * - [init]: the factory's arguments (and the current value of each lambda it takes, which the
 *   guest samples) by parameter name;
 * - [observed]: the tags of the fields the guest has read, each from its first read; only these are
 *   reported back;
 * - [commands]: the commands not yet acknowledged.
 */
@Serializable
public data class RemoteObjectWire(
  val id: String,
  val type: Int,
  val init: JsonObject,
  val observed: List<Int>,
  val commands: List<RemoteCommand>,
)

/**
 * What the host sends back as the widget's event: the new values of [fields] (by the field's tag),
 * and the commands it finished, each with its result (`JsonNull` for `Unit`).
 */
@Serializable
public data class RemoteReport(
  val fields: Map<Int, JsonElement> = emptyMap(),
  val done: Map<Int, JsonElement> = emptyMap(),
)

/**
 * The guest's handle on one host-owned object. Generated proxies read fields and issue commands
 * through it; the host's [RemoteReport] updates the fields and completes the commands.
 *
 * - A field is observed from its first read: [wire] lists it, and the host reports it when it
 *   changes (from a `snapshotFlow`, so at most once a frame).
 * - A command stays in [wire] until the host acknowledges it; a suspend member returns then.
 * - [wire] reads Compose state (the observed set, the pending commands, [init]), so the binding
 *   that reads it recomposes, and re-sends the property, when any of them changes.
 */
public class RemoteObject(
  public val id: String,
  public val type: Int,
  private val init: () -> JsonObject = { JsonObject(emptyMap()) },
) {
  private val fields = mutableStateMapOf<Int, JsonElement>()
  private val seen = HashSet<Int>()
  private val observed = mutableStateListOf<Int>()
  private val pending = mutableStateListOf<RemoteCommand>()
  private val waiting = HashMap<Int, CompletableDeferred<JsonElement>>()
  private var seq = 0

  /** The host's last report of [member], or null before the first one. Observes it from now on. */
  public fun read(member: Int): JsonElement? {
    if (seen.add(member)) observed += member
    return fields[member]
  }

  /** A command the guest awaits: returns the host's result when the host's call returned. */
  public suspend fun call(member: Int, args: JsonObject): JsonElement {
    val d = CompletableDeferred<JsonElement>()
    waiting[enqueue(member, args)] = d
    return d.await()
  }

  /** A command the guest does not await. */
  public fun send(member: Int, args: JsonObject) {
    enqueue(member, args)
  }

  private fun enqueue(member: Int, args: JsonObject): Int {
    val s = ++seq
    pending += RemoteCommand(s, member, args)
    return s
  }

  /** The property the guest sends. */
  public val wire: RemoteObjectWire
    get() = RemoteObjectWire(id, type, init(), observed.toList(), pending.toList())

  /** The host's report, from the widget's event. */
  public fun onHostReport(report: RemoteReport) {
    for ((k, v) in report.fields) fields[k] = v
    for ((s, result) in report.done) {
      pending.removeAll { it.seq == s }
      waiting.remove(s)?.complete(result)
    }
  }

  public companion object {
    private var counter = 0

    /** A name no other object of this guest has. */
    public fun nextId(): String = "r${++counter}"
  }
}
