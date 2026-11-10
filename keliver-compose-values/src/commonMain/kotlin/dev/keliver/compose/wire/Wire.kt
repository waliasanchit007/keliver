/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive

/**
 * A codec that writes one JSON element (docs/COMPOSE_GENERATOR_DESIGN.md §6). The protocol only
 * ever encodes with kotlinx.serialization's Json, so every mirror codec is JSON-level: a number,
 * a short string or a small object, chosen to read well in a protocol trace.
 */
public abstract class JsonCodec<T>(name: String) : KSerializer<T> {
  override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor.let { SerialDescriptorProxy(name, it) }

  public abstract fun toJson(value: T): JsonElement
  public abstract fun fromJson(json: JsonElement): T

  override fun serialize(encoder: Encoder, value: T) {
    (encoder as JsonEncoder).encodeJsonElement(toJson(value))
  }

  override fun deserialize(decoder: Decoder): T = fromJson((decoder as JsonDecoder).decodeJsonElement())
}

private class SerialDescriptorProxy(
  override val serialName: String,
  private val delegate: SerialDescriptor,
) : SerialDescriptor by delegate

/** A float from a JSON number, or NaN for the unspecified marker `"u"`. */
internal fun JsonElement.floatOrUnspecified(): Float {
  val p = jsonPrimitive
  return if (p.isString && p.content == "u") Float.NaN else p.float
}

internal fun floatOrUnspecified(value: Float): JsonElement =
  if (value.isNaN()) JsonPrimitive("u") else JsonPrimitive(value.toCompact())

/** 16.0f writes as 16, 1.5f as 1.5: shorter traces, the same value. */
internal fun Float.toCompact(): Number {
  val i = toInt()
  return if (i.toFloat() == this) i else this
}
