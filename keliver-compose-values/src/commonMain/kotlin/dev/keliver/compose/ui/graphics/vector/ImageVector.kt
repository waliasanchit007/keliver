/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.ui.graphics.vector

import androidx.compose.runtime.Immutable
import dev.keliver.compose.wire.JsonCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The guest mirror of `androidx.compose.ui.graphics.vector.ImageVector`: an icon from an icon
 * group the host includes, by name (`"Filled.ChevronRight"`). Path-built vectors are Gate B.
 * Wire: the name.
 */
@Immutable
@Serializable(with = ImageVectorCodec::class)
public class ImageVector(public val name: String) {
  override fun equals(other: Any?): Boolean = other is ImageVector && other.name == name
  override fun hashCode(): Int = name.hashCode()
  override fun toString(): String = "ImageVector($name)"
}

public object ImageVectorCodec : JsonCodec<ImageVector>("dev.keliver.compose.ImageVector") {
  override fun toJson(value: ImageVector): JsonElement = JsonPrimitive(value.name)
  override fun fromJson(json: JsonElement): ImageVector = ImageVector(json.jsonPrimitive.content)
}
