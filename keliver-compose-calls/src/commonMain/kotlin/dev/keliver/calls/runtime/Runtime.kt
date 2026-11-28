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
@file:OptIn(InternalCallApi::class)

package dev.keliver.calls.runtime

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Called by generated code only. A guest cannot make, or implement, a Compose value type by hand: it
 * is the call that built it (use the type's constructor, factory or constant), and a guest subclass
 * of an open type is an error stub (docs/COMPOSE_GENERATOR_DESIGN.md §5).
 */
@RequiresOptIn("Keliver: a guest cannot implement or construct this Compose type; call its constructor, factory or constant instead.", level = RequiresOptIn.Level.ERROR)
public annotation class InternalCallApi

/**
 * A recorded call (docs/COMPOSE_GENERIC_MECHANISMS.md §1.1): which public constructor, factory,
 * constant or member made the value ([tag], from the shared `values` lock), the arguments the guest
 * passed, each in its type's wire form, and the call it was made on ([on], for a member call on a
 * recorded value). An argument left out is Compose's default, evaluated by the host.
 *
 * Wire: `{"call":12,"args":{"size":8}}`, and `{"call":40,"on":{"call":7},"args":{…}}` with a receiver.
 */
@Immutable
public class CallRecord(
  public val tag: Int,
  public val args: JsonObject = JsonObject(emptyMap()),
  public val on: CallRecord? = null,
) {
  public fun toJson(): JsonElement = buildJsonObject {
    put("call", JsonPrimitive(tag))
    on?.let { put("on", it.toJson()) }
    if (args.isNotEmpty()) put("args", args)
  }

  override fun equals(other: Any?): Boolean = other is CallRecord && other.tag == tag && other.args == args && other.on == on
  override fun hashCode(): Int = (tag * 31 + args.hashCode()) * 31 + (on?.hashCode() ?: 0)
  override fun toString(): String = toJson().toString()
}

/** A guest value that is a recorded call. */
public interface CallRecorded {
  @InternalCallApi public val call: CallRecord

  /**
   * The arguments the guest built this value from, by parameter name, when a constructor call (or a
   * `copy` of one) built it; null for a value that came from a factory or a constant, where the guest
   * holds the call and not the fields. Reads of constructor-backed properties come from here.
   */
  @InternalCallApi public val fieldValues: Map<String, Any?>?
}

/** The fields of [this], or an error that says why a read is not possible (the value was not built by a constructor). */
@InternalCallApi
public fun CallRecorded.readValues(what: String): Map<String, Any?> = fieldValues
  ?: error("Keliver: $what is not readable: this value came from a factory, a constant or a merge, so the guest holds the call and not the fields.")

/**
 * The base of every concrete call-recorded mirror. Equality is the call's: the same producer with the
 * same arguments. `TextStyle()` and `TextStyle(color = Color.Unspecified)` are not equal here, though
 * they are in Compose (an explicit default is sent); see docs/COMPOSE_GENERIC_MECHANISMS.md §1.1.
 */
@Immutable
public abstract class CallValue @InternalCallApi constructor(
  call: CallRecord,
  fieldValues: Map<String, Any?>? = null,
) : CallRecorded {
  @InternalCallApi override val call: CallRecord = call

  @InternalCallApi override val fieldValues: Map<String, Any?>? = fieldValues

  override fun equals(other: Any?): Boolean = other is CallValue && other::class == this::class && other.call == call
  override fun hashCode(): Int = call.hashCode()
  override fun toString(): String = "${this::class.simpleName}$call"
}
