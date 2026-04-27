/*
 * Copyright (C) 2023 Square, Inc.
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
@file:Suppress("ktlint:standard:property-naming")

package dev.konduit.layout

import dev.konduit.layout.api.CrossAxisAlignment
import dev.konduit.layout.modifier.Flex
import dev.konduit.layout.modifier.Grow
import dev.konduit.layout.modifier.Height
import dev.konduit.layout.modifier.HorizontalAlignment
import dev.konduit.layout.modifier.Shrink
import dev.konduit.layout.modifier.Size
import dev.konduit.layout.modifier.VerticalAlignment
import dev.konduit.layout.modifier.Width
import dev.konduit.ui.Dp
import dev.konduit.ui.Margin
import dev.konduit.ui.dp

internal data class CrossAxisAlignmentImpl(
  override val alignment: CrossAxisAlignment,
) : HorizontalAlignment,
  VerticalAlignment

internal data class VerticalAlignmentImpl(
  override val alignment: CrossAxisAlignment,
) : VerticalAlignment

internal data class HorizontalAlignmentImpl(
  override val alignment: CrossAxisAlignment,
) : HorizontalAlignment

internal data class WidthImpl(
  override val width: Dp,
) : Width

internal data class HeightImpl(
  override val height: Dp,
) : Height

internal data class SizeImpl(
  override val width: Dp,
  override val height: Dp,
) : Size

internal data class MarginImpl(
  override val margin: Margin,
) : dev.konduit.layout.modifier.Margin {
  constructor(all: Dp = 0.dp) : this(Margin(all))
}

internal data class GrowImpl(
  override val value: Double,
) : Grow

internal data class ShrinkImpl(
  override val value: Double,
) : Shrink

internal data class FlexImpl(
  override val value: Double,
) : Flex

internal fun shortText() = "Short\n".repeat(2).trim()

internal fun mediumText() = "MediumMedium\n".repeat(7).trim()

internal fun longText() = "LongLongLongLongLongLongLong\n".repeat(12).trim()
