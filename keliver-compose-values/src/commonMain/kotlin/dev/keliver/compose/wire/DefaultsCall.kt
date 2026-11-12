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
package dev.keliver.compose.wire

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A call to a generated `*Defaults` factory (`ButtonDefaults.buttonColors(containerColor = …)`),
 * recorded in the guest and made by the host, which calls the real factory with the same arguments
 * (docs/COMPOSE_GENERATOR_DESIGN.md §6). [factory] is the factory's tag in the group's lock;
 * [args] are the arguments by parameter tag, each in its type's wire form.
 *
 * Wire: `{"f":3,"a":{"1":4279983648}}`. Factory 0 is reserved: an explicit `null` for a nullable
 * parameter whose default is not null (`Button(elevation = null)`, design §5).
 */
@Immutable
public class DefaultsCall(public val factory: Int, public val args: Map<Int, JsonElement> = emptyMap()) {
  public val isExplicitNull: Boolean get() = factory == 0

  override fun equals(other: Any?): Boolean = other is DefaultsCall && other.factory == factory && other.args == args
  override fun hashCode(): Int = factory * 31 + args.hashCode()
  override fun toString(): String = "DefaultsCall($factory, $args)"

  public fun toJson(): JsonElement = buildJsonObject {
    put("f", factory)
    if (args.isNotEmpty()) put("a", JsonObject(args.entries.associate { (k, v) -> k.toString() to v }))
  }

  public companion object {
    public val ExplicitNull: DefaultsCall = DefaultsCall(0)

    public fun fromJson(json: JsonElement): DefaultsCall {
      val o = json.jsonObject
      val args = o["a"]?.jsonObject?.entries?.associate { (k, v) -> k.toInt() to v }.orEmpty()
      return DefaultsCall(o.getValue("f").jsonPrimitive.int, args)
    }

    /** Whether [json] is a call record rather than another form of the same type. */
    public fun isCall(json: JsonElement): Boolean = json is JsonObject && "f" in json
  }
}

/**
 * The guest's value of a Compose type that only a `*Defaults` factory makes (`ButtonColors`,
 * `CardElevation`): it is the call itself. The generator writes one subclass per such type.
 */
@Immutable
public abstract class DefaultsValue(public val call: DefaultsCall) {
  override fun equals(other: Any?): Boolean = other != null && other::class == this::class && (other as DefaultsValue).call == call
  override fun hashCode(): Int = call.hashCode()
  override fun toString(): String = "${this::class.simpleName}($call)"
}

/** The codec every [DefaultsValue] subclass uses. */
public open class DefaultsValueCodec<T : DefaultsValue>(name: String, private val create: (DefaultsCall) -> T) : JsonCodec<T>(name) {
  override fun toJson(value: T): JsonElement = value.call.toJson()
  override fun fromJson(json: JsonElement): T = create(DefaultsCall.fromJson(json))
}

/** A [DefaultsCall] argument in its type's wire form (each mirror type is `@Serializable`). */
public inline fun <reified T> defaultsArg(value: T): JsonElement = WireJson.encodeToJsonElement(kotlinx.serialization.serializer<T>(), value)

/** A [DefaultsCall] argument read back on the host, or null when the guest did not send it. */
public inline fun <reified T> DefaultsCall.arg(tag: Int): T? = args[tag]?.let { WireJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), it) }

@PublishedApi
internal val WireJson: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json
