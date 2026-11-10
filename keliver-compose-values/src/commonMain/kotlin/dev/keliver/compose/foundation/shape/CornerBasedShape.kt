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
package dev.keliver.compose.foundation.shape

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.keliver.compose.ui.graphics.Shape
import dev.keliver.compose.ui.unit.Dp
import dev.keliver.compose.ui.unit.dp
import dev.keliver.compose.wire.toCompact
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The guest mirror of `androidx.compose.foundation.shape.CornerSize`: a dp size or a percent. */
@Immutable
public class CornerSize internal constructor(internal val dp: Dp?, internal val percent: Int?) {
  internal fun toJson(): JsonElement = if (percent != null) JsonPrimitive("$percent%") else JsonPrimitive(dp!!.value.toCompact())
  public fun dpOrNull(): Dp? = dp
  public fun percentOrNull(): Int? = percent
  override fun equals(other: Any?): Boolean = other is CornerSize && other.dp == dp && other.percent == percent
  override fun hashCode(): Int = 31 * (dp?.hashCode() ?: 0) + (percent ?: 0)
  override fun toString(): String = if (percent != null) "CornerSize($percent%)" else "CornerSize($dp)"
}

@Stable public fun CornerSize(size: Dp): CornerSize = CornerSize(size, null)

@Stable public fun CornerSize(percent: Int): CornerSize = CornerSize(null, percent)

@Stable public val ZeroCornerSize: CornerSize = CornerSize(0.dp)

/** The guest mirror of `androidx.compose.foundation.shape.CornerBasedShape`. */
@Immutable
public abstract class CornerBasedShape internal constructor(
  public val topStart: CornerSize,
  public val topEnd: CornerSize,
  public val bottomEnd: CornerSize,
  public val bottomStart: CornerSize,
) : Shape() {
  override fun equals(other: Any?): Boolean = other != null &&
    other::class == this::class &&
    (other as CornerBasedShape).let { it.topStart == topStart && it.topEnd == topEnd && it.bottomEnd == bottomEnd && it.bottomStart == bottomStart }
  override fun hashCode(): Int = listOf(this::class.simpleName, topStart, topEnd, bottomEnd, bottomStart).hashCode()
}

/** The guest mirror of `androidx.compose.foundation.shape.RoundedCornerShape`. */
@Immutable
public class RoundedCornerShape(
  topStart: CornerSize,
  topEnd: CornerSize,
  bottomEnd: CornerSize,
  bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {
  override fun toString(): String = "RoundedCornerShape($topStart, $topEnd, $bottomEnd, $bottomStart)"
}

@Stable public fun RoundedCornerShape(corner: CornerSize): RoundedCornerShape = RoundedCornerShape(corner, corner, corner, corner)

@Stable public fun RoundedCornerShape(size: Dp): RoundedCornerShape = RoundedCornerShape(CornerSize(size))

@Stable public fun RoundedCornerShape(percent: Int): RoundedCornerShape = RoundedCornerShape(CornerSize(percent))

@Stable
public fun RoundedCornerShape(
  topStart: Dp = 0.dp,
  topEnd: Dp = 0.dp,
  bottomEnd: Dp = 0.dp,
  bottomStart: Dp = 0.dp,
): RoundedCornerShape = RoundedCornerShape(CornerSize(topStart), CornerSize(topEnd), CornerSize(bottomEnd), CornerSize(bottomStart))

@Stable public val CircleShape: RoundedCornerShape = RoundedCornerShape(50)

/** The guest mirror of `androidx.compose.foundation.shape.CutCornerShape`. */
@Immutable
public class CutCornerShape(
  topStart: CornerSize,
  topEnd: CornerSize,
  bottomEnd: CornerSize,
  bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart)

@Stable public fun CutCornerShape(size: Dp): CutCornerShape = CornerSize(size).let { CutCornerShape(it, it, it, it) }

@Stable public fun CutCornerShape(percent: Int): CutCornerShape = CornerSize(percent).let { CutCornerShape(it, it, it, it) }
