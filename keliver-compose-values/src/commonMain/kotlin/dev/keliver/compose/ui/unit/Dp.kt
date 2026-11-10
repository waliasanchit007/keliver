/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.ui.unit

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.keliver.compose.wire.JsonCodec
import dev.keliver.compose.wire.floatOrUnspecified
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The guest mirror of `androidx.compose.ui.unit.Dp`. Wire: a number, or `"u"` for [Unspecified]. */
@Immutable
@JvmInline
@Serializable(with = DpCodec::class)
public value class Dp(public val value: Float) : Comparable<Dp> {
  @Stable public operator fun plus(other: Dp): Dp = Dp(value + other.value)
  @Stable public operator fun minus(other: Dp): Dp = Dp(value - other.value)
  @Stable public operator fun unaryMinus(): Dp = Dp(-value)
  @Stable public operator fun div(other: Float): Dp = Dp(value / other)
  @Stable public operator fun div(other: Int): Dp = Dp(value / other)
  @Stable public operator fun div(other: Dp): Float = value / other.value
  @Stable public operator fun times(other: Float): Dp = Dp(value * other)
  @Stable public operator fun times(other: Int): Dp = Dp(value * other)
  override fun compareTo(other: Dp): Int = value.compareTo(other.value)
  override fun toString(): String = if (isUnspecified) "Dp.Unspecified" else "$value.dp"

  public companion object {
    @Stable public val Hairline: Dp = Dp(0f)
    @Stable public val Infinity: Dp = Dp(Float.POSITIVE_INFINITY)
    @Stable public val Unspecified: Dp = Dp(Float.NaN)
  }
}

@Stable public val Dp.isSpecified: Boolean get() = !value.isNaN()
@Stable public val Dp.isUnspecified: Boolean get() = value.isNaN()
@Stable public val Int.dp: Dp get() = Dp(toFloat())
@Stable public val Double.dp: Dp get() = Dp(toFloat())
@Stable public val Float.dp: Dp get() = Dp(this)
@Stable public operator fun Float.times(other: Dp): Dp = Dp(this * other.value)
@Stable public operator fun Int.times(other: Dp): Dp = Dp(this * other.value)
@Stable public fun max(a: Dp, b: Dp): Dp = if (a >= b) a else b
@Stable public fun min(a: Dp, b: Dp): Dp = if (a <= b) a else b

public object DpCodec : JsonCodec<Dp>("dev.keliver.compose.Dp") {
  override fun toJson(value: Dp): JsonElement = floatOrUnspecified(value.value)
  override fun fromJson(json: JsonElement): Dp = Dp(json.floatOrUnspecified())
}
