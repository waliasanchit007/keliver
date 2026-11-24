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
package dev.keliver.spike.wire

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The guest half of the spike's runtime (docs/COMPOSE_GENERIC_MECHANISMS.md), written once for
// every type each mechanism covers.

@RequiresOptIn("Called by generated code only.")
public annotation class InternalSpikeApi

/**
 * A recorded call (mechanism 1): which public constructor, factory or constant made the value,
 * and the arguments the guest passed, each in its type's wire form. An argument left out is
 * Compose's default, evaluated by the host. Wire: `{"call":"<id>","args":{"name":…}}`.
 * (The product would key [id] by a lock tag, as `DefaultsCall` does today.)
 */
@Immutable
public class CallRecord(public val id: String, public val args: JsonObject = JsonObject(emptyMap())) {
  public fun toJson(): JsonElement = buildJsonObject {
    put("call", JsonPrimitive(id))
    if (args.isNotEmpty()) put("args", args)
  }

  override fun equals(other: Any?): Boolean = other is CallRecord && other.id == id && other.args == args
  override fun hashCode(): Int = id.hashCode() * 31 + args.hashCode()
  override fun toString(): String = toJson().toString()
}

/** A guest value that is a recorded call. */
public interface CallRecorded {
  public val call: CallRecord
}

/**
 * The base of every concrete call-recorded mirror. Equality is the call's: the same producer with
 * the same arguments. `TextStyle()` and `TextStyle(color = Color.Unspecified)` are not equal here,
 * though they are in Compose (an explicit default is sent); see the doc's "what breaks".
 */
@Immutable
public abstract class CallValue(override val call: CallRecord) : CallRecorded {
  override fun equals(other: Any?): Boolean = other is CallValue && other::class == this::class && other.call == call
  override fun hashCode(): Int = call.hashCode()
  override fun toString(): String = "${this::class.simpleName}$call"
}

/**
 * The guest's handle on one host-owned object (mechanism 2). Generated proxies read fields and
 * issue commands through it; the host's report updates the fields and completes the commands.
 *
 * - A field is observed from its first read: [wire] lists it, and the host reports it on change
 *   (from a `snapshotFlow`, so at most once a frame).
 * - A command is sent in [wire] until the host acknowledges it; a suspend member returns then.
 */
public class RemoteObject(public val id: String) {
  private val fields = mutableStateMapOf<String, JsonElement>()
  private val observed = linkedSetOf<String>()
  private val pending = mutableStateListOf<JsonArray>()
  private val waiting = mutableMapOf<Int, CompletableDeferred<Unit>>()
  private var seq = 0

  /** The host's last report of [name], or null before the first one. Subscribes to it. */
  public fun read(name: String): JsonElement? {
    observed += name
    return fields[name]
  }

  /** A command the guest awaits: returns when the host's call returned. */
  public suspend fun call(member: String, args: JsonObject) {
    val d = CompletableDeferred<Unit>()
    waiting[enqueue(member, args)] = d
    d.await()
  }

  /** A command the guest does not await. */
  public fun send(member: String, args: JsonObject) {
    enqueue(member, args)
  }

  private fun enqueue(member: String, args: JsonObject): Int {
    val s = ++seq
    pending += JsonArray(listOf(JsonPrimitive(s), JsonPrimitive(member), args))
    return s
  }

  /** What the guest sends as the widget's property: `{"id":…,"obs":[…],"cmd":[[seq,member,args],…]}`. */
  public val wire: JsonObject get() = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("obs", JsonArray(observed.map(::JsonPrimitive)))
    put("cmd", JsonArray(pending.toList()))
  }

  /** The host's report: `{"f":{name:value}}` and/or `{"done":[seq…]}`. */
  public fun onHostReport(report: JsonObject) {
    report["f"]?.jsonObject?.forEach { (k, v) -> fields[k] = v }
    report["done"]?.jsonArray?.forEach { s ->
      val n = s.jsonPrimitive.int
      pending.removeAll { it[0].jsonPrimitive.int == n }
      waiting.remove(n)?.complete(Unit)
    }
  }
}
