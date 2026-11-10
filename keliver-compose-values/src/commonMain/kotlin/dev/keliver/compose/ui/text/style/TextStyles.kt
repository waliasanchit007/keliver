/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.ui.text.style

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.keliver.compose.wire.JsonCodec
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** The guest mirror of `androidx.compose.ui.text.style.TextAlign`. Wire: Compose's int value. */
@Immutable
@JvmInline
@Serializable(with = TextAlignCodec::class)
public value class TextAlign internal constructor(public val value: Int) {
  public companion object {
    @Stable public val Left: TextAlign = TextAlign(1)
    @Stable public val Right: TextAlign = TextAlign(2)
    @Stable public val Center: TextAlign = TextAlign(3)
    @Stable public val Justify: TextAlign = TextAlign(4)
    @Stable public val Start: TextAlign = TextAlign(5)
    @Stable public val End: TextAlign = TextAlign(6)
    @Stable public val Unspecified: TextAlign = TextAlign(Int.MIN_VALUE)
  }
}

public object TextAlignCodec : JsonCodec<TextAlign>("dev.keliver.compose.TextAlign") {
  override fun toJson(value: TextAlign): JsonElement = JsonPrimitive(value.value)
  override fun fromJson(json: JsonElement): TextAlign = TextAlign(json.jsonPrimitive.int)
}

/** The guest mirror of `androidx.compose.ui.text.style.TextOverflow`. Wire: Compose's int value. */
@Immutable
@JvmInline
@Serializable(with = TextOverflowCodec::class)
public value class TextOverflow internal constructor(public val value: Int) {
  public companion object {
    @Stable public val Clip: TextOverflow = TextOverflow(1)
    @Stable public val Ellipsis: TextOverflow = TextOverflow(2)
    @Stable public val Visible: TextOverflow = TextOverflow(3)
    @Stable public val StartEllipsis: TextOverflow = TextOverflow(4)
    @Stable public val MiddleEllipsis: TextOverflow = TextOverflow(5)
  }
}

public object TextOverflowCodec : JsonCodec<TextOverflow>("dev.keliver.compose.TextOverflow") {
  override fun toJson(value: TextOverflow): JsonElement = JsonPrimitive(value.value)
  override fun fromJson(json: JsonElement): TextOverflow = TextOverflow(json.jsonPrimitive.int)
}

/** The guest mirror of `androidx.compose.ui.text.style.TextDecoration`. Wire: a bit mask, 1 underline, 2 line-through. */
@Immutable
@Serializable(with = TextDecorationCodec::class)
public class TextDecoration internal constructor(public val mask: Int) {
  public operator fun plus(decoration: TextDecoration): TextDecoration = TextDecoration(mask or decoration.mask)
  public operator fun contains(other: TextDecoration): Boolean = (mask or other.mask) == mask
  override fun equals(other: Any?): Boolean = other is TextDecoration && other.mask == mask
  override fun hashCode(): Int = mask
  public companion object {
    @Stable public val None: TextDecoration = TextDecoration(0)
    @Stable public val Underline: TextDecoration = TextDecoration(1)
    @Stable public val LineThrough: TextDecoration = TextDecoration(2)
    public fun combine(decorations: List<TextDecoration>): TextDecoration = TextDecoration(decorations.fold(0) { m, d -> m or d.mask })
  }
}

public object TextDecorationCodec : JsonCodec<TextDecoration>("dev.keliver.compose.TextDecoration") {
  override fun toJson(value: TextDecoration): JsonElement = JsonPrimitive(value.mask)
  override fun fromJson(json: JsonElement): TextDecoration = TextDecoration(json.jsonPrimitive.int)
}
