/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.ui.graphics

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.keliver.compose.wire.JsonCodec
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** As `androidx.compose.ui.graphics.DefaultAlpha`. */
public const val DefaultAlpha: Float = 1.0f

/** The guest mirror of `androidx.compose.ui.graphics.FilterQuality`. Wire: Compose's int value. */
@Immutable
@JvmInline
@Serializable(with = FilterQualityCodec::class)
public value class FilterQuality internal constructor(public val value: Int) {
  public companion object {
    @Stable public val None: FilterQuality = FilterQuality(0)
    @Stable public val Low: FilterQuality = FilterQuality(1)
    @Stable public val Medium: FilterQuality = FilterQuality(2)
    @Stable public val High: FilterQuality = FilterQuality(3)
  }
}

public object FilterQualityCodec : JsonCodec<FilterQuality>("dev.keliver.compose.FilterQuality") {
  override fun toJson(value: FilterQuality): JsonElement = JsonPrimitive(value.value)
  override fun fromJson(json: JsonElement): FilterQuality = FilterQuality(json.jsonPrimitive.int)
}
