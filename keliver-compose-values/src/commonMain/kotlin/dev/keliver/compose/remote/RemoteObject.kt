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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 *   guest samples) by parameter name; a restorable argument carries the position the guest last knew;
 * - [observed]: the tags of the fields the guest has read, each from its first read; only these are
 *   reported back, as they change;
 * - [commands]: the commands not yet acknowledged; a command that leaves this list while it runs is
 *   cancelled on the host;
 * - [restore]: the tags of the fields the guest saves for process death. The host reports these once
 *   the state has been still ([RemoteReport.settled]), not on every frame.
 */
@Serializable
public data class RemoteObjectWire(
  val id: String,
  val type: Int,
  val init: JsonObject,
  val observed: List<Int>,
  val commands: List<RemoteCommand>,
  val restore: List<Int> = emptyList(),
)

/**
 * What the host sends back as the widget's event: the new values of [fields] (by the field's tag), the
 * commands it finished with each result (`JsonNull` for `Unit`) in [done], the commands that threw in
 * [failed] (the message), and the commands Compose cancelled (an interrupted scroll) in [cancelled].
 * [settled] carries the restorable fields' values after the state has been still.
 */
@Serializable
public data class RemoteReport(
  val fields: Map<Int, JsonElement> = emptyMap(),
  val done: Map<Int, JsonElement> = emptyMap(),
  val failed: Map<Int, String> = emptyMap(),
  val cancelled: List<Int> = emptyList(),
  val settled: Map<Int, JsonElement> = emptyMap(),
)

/** A command that threw on the host (a failed `require`, an argument the host cannot read), or that could not be sent. */
public class RemoteCommandException(message: String) : RuntimeException(message)

/**
 * The guest's handle on one host-owned object. Generated proxies read fields and issue commands
 * through it; the host's [RemoteReport] updates the fields and completes the commands.
 *
 * - A field is observed from its first read: [wire] lists it once [bind]'s effect has recorded it, and
 *   the host reports it when it changes (from a `snapshotFlow`, so at most once a frame). A first read
 *   writes no Compose state, so it may run inside `snapshotFlow` (a read-only snapshot).
 * - A command stays in [wire] until the host acknowledges it; a suspend member returns then, throws
 *   [RemoteCommandException] if the host's call threw, and [CancellationException] if it was cancelled.
 *   Cancelling the caller removes the command from [wire], which cancels it on the host.
 * - [wire] reads Compose state (the observed set, the pending commands, [init]), so the binding
 *   that reads it recomposes, and re-sends the property, when any of them changes.
 * - Without a widget bound ([bind] composed) a command cannot reach any host state: it fails at once
 *   with a message saying so, where it would otherwise wait for ever.
 */
public class RemoteObject(
  public val id: String,
  public val type: Int,
  /** The class's name, for messages. */
  public val name: String = "state",
  /** The tags of the restorable fields ([RemoteObjectWire.restore]). */
  private val restore: List<Int> = emptyList(),
  private val init: RemoteObject.() -> JsonObject = { JsonObject(emptyMap()) },
) {
  private val fields = mutableStateMapOf<Int, JsonElement>()

  // What the field's first read recorded (plain, so a read can run in a read-only snapshot), and what has
  // been moved to [observed] by the binding's effect. A field is in [seen] only once it is in [observed].
  private val seen = HashSet<Int>()
  private val queued = LinkedHashSet<Int>()
  private val signal = Channel<Unit>(Channel.CONFLATED)
  private val observed = mutableStateListOf<Int>()

  private val pending = mutableStateListOf<RemoteCommand>()
  private val waiting = HashMap<Int, CompletableDeferred<JsonElement>>()
  private val seeds = HashMap<Int, JsonElement>()
  private val settled = HashMap<Int, JsonElement>()
  private var seq = 0
  private var bound = 0

  /** The value a field has before the host's first report of it (its factory argument, or the restored one). */
  public fun seed(member: Int, value: JsonElement) {
    seeds[member] = value
  }

  /**
   * The host's last report of [member] (before the first: its [seed], or null). Observes it from now
   * on, once the binding's effect has recorded it: this writes no Compose state.
   */
  public fun read(member: Int): JsonElement? {
    if (member !in seen && queued.add(member)) signal.trySend(Unit)
    return fields[member] ?: seeds[member]
  }

  /** The newest value known of [member], without observing it: the last settled or reported one, else its seed. */
  public fun peek(member: Int): JsonElement? = settled[member] ?: fields[member] ?: seeds[member]

  /** A setter: the new value is read back at once, and the host's field is set by [member]'s command (not awaited). */
  public fun assign(field: Int, value: JsonElement, setter: Int, args: JsonObject) {
    send(setter, args)
    fields[field] = value
  }

  /** A command the guest awaits: returns the host's result when the host's call returned. */
  public suspend fun call(member: Int, args: JsonObject): JsonElement {
    val d = CompletableDeferred<JsonElement>()
    val s = enqueue(member, args)
    waiting[s] = d
    try {
      return d.await()
    } catch (e: CancellationException) {
      // The caller was cancelled, or the host cancelled the command: either way it leaves the wire.
      waiting.remove(s)
      pending.removeAll { it.seq == s }
      throw e
    }
  }

  /** A command the guest does not await. */
  public fun send(member: Int, args: JsonObject) {
    enqueue(member, args)
  }

  private fun enqueue(member: Int, args: JsonObject): Int {
    if (bound == 0) {
      throw RemoteCommandException(
        "$name is not bound to a widget in this host: a state works through the widget it is passed to " +
          "(pass it to the composable that takes it, and call it from there or after it). " +
          "This release has no widget for it yet, or the screen has not composed it.",
      )
    }
    val s = ++seq
    pending += RemoteCommand(s, member, args)
    return s
  }

  /**
   * The binding: composes with the widget that takes this object, and returns the property the widget
   * sends. Records the fields the guest has read (outside the read), and counts the object as bound.
   */
  @Composable
  public fun bind(): RemoteObjectWire {
    remember(this) { Binding() }
    LaunchedEffect(this) {
      flush()
      for (u in signal) flush()
    }
    return wire
  }

  private inner class Binding : RememberObserver {
    init {
      bound++
    }

    private var released = false

    private fun release() {
      if (!released) {
        released = true
        bound--
      }
    }

    override fun onRemembered() {}
    override fun onForgotten() = release()
    override fun onAbandoned() = release()
  }

  /** Moves the fields read since the last flush into the observed set, which [wire] reads. */
  private fun flush() {
    if (queued.isEmpty()) return
    for (m in queued.toList()) {
      if (seen.add(m)) observed += m
    }
    queued.clear()
  }

  /** The property the guest sends. */
  public val wire: RemoteObjectWire
    get() = RemoteObjectWire(id, type, init(), observed.toList(), pending.toList(), restore)

  /** The host's report, from the widget's event. */
  public fun onHostReport(report: RemoteReport) {
    for ((k, v) in report.fields) {
      fields[k] = v
      if (k in restore) settled[k] = v
    }
    for ((k, v) in report.settled) settled[k] = v
    for ((s, result) in report.done) {
      pending.removeAll { it.seq == s }
      waiting.remove(s)?.complete(result)
    }
    for ((s, message) in report.failed) {
      pending.removeAll { it.seq == s }
      waiting.remove(s)?.completeExceptionally(RemoteCommandException(message))
    }
    for (s in report.cancelled) {
      pending.removeAll { it.seq == s }
      waiting.remove(s)?.cancel(CancellationException("$name: the host cancelled the command"))
    }
  }

  public companion object {
    private var counter = 0

    /** A name no other object of this guest has. */
    public fun nextId(): String = "r${++counter}"
  }
}

/**
 * What a remote object's guest factory saves for process death. The host's state dies with the
 * process, but the guest's saved state (its `SaveableStateRegistry`, which the host app persists) does
 * not, so the factory saves the values of the state's restorable fields ([live]: the newest value the
 * host reported of each, `currentPage` for a pager) and, after a restore, passes them back as the host
 * factory's arguments (`initialPage`): the host creates its state already where it was. [saved] holds
 * what the last save kept; the factory takes it over (and clears it) as soon as it has seeded the object.
 *
 * Process death needs the guest's saved state to outlive the process: the host app must persist it
 * (Treehouse's default `MemoryStateStore` does not; an app that wants this restore supplies a
 * persistent `StateStore`).
 */
public class RemoteRestore {
  public var saved: JsonObject? = null
  public var live: (() -> JsonObject)? = null

  public companion object {
    /** The factory's `rememberSaveable` saver: a JSON text of the restorable fields. */
    public val Saver: Saver<RemoteRestore, String> = Saver(
      save = { r -> JsonObject(r.saved.orEmpty() + r.live?.invoke().orEmpty()).toString() },
      restore = { text -> RemoteRestore().also { it.saved = Json.parseToJsonElement(text) as JsonObject } },
    )
  }
}
