/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.foundation.layout

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.keliver.compose.ui.unit.Dp
import dev.keliver.compose.ui.unit.dp
import dev.keliver.compose.wire.JsonCodec
import dev.keliver.compose.wire.toCompact
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** The guest mirror of `androidx.compose.foundation.layout.PaddingValues`. Wire: `[s,t,e,b]`. */
@Immutable
@Serializable(with = PaddingValuesCodec::class)
public abstract class PaddingValues internal constructor() {
  public abstract val start: Dp
  public abstract val top: Dp
  public abstract val end: Dp
  public abstract val bottom: Dp
  override fun equals(other: Any?): Boolean =
    other is PaddingValues && other.start == start && other.top == top && other.end == end && other.bottom == bottom
  override fun hashCode(): Int = listOf(start, top, end, bottom).hashCode()
}

private class PaddingValuesImpl(
  override val start: Dp,
  override val top: Dp,
  override val end: Dp,
  override val bottom: Dp,
) : PaddingValues()

@Stable public fun PaddingValues(all: Dp): PaddingValues = PaddingValuesImpl(all, all, all, all)
@Stable public fun PaddingValues(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): PaddingValues = PaddingValuesImpl(horizontal, vertical, horizontal, vertical)
@Stable
public fun PaddingValues(start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp): PaddingValues =
  PaddingValuesImpl(start, top, end, bottom)

public object PaddingValuesCodec : JsonCodec<PaddingValues>("dev.keliver.compose.PaddingValues") {
  override fun toJson(value: PaddingValues): JsonElement =
    JsonArray(listOf(value.start, value.top, value.end, value.bottom).map { JsonPrimitive(it.value.toCompact()) })
  override fun fromJson(json: JsonElement): PaddingValues =
    json.jsonArray.map { Dp(it.jsonPrimitive.float) }.let { PaddingValuesImpl(it[0], it[1], it[2], it[3]) }
}
