/*
 * Copyright (C) 2022 Square, Inc.
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
package dev.keliver.layout.composeui

import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import dev.keliver.ui.Dp as RedwoodDp
import dev.keliver.ui.toPlatformDp
import dev.keliver.yoga.MeasureCallback
import dev.keliver.yoga.MeasureMode
import dev.keliver.yoga.Node
import dev.keliver.yoga.Size

internal class ComposeMeasureCallback(
  private val measurable: Measurable,
) : MeasureCallback {
  private var placeable: Placeable? = null

  fun getPlaceable(node: Node): Placeable {
    var placeable = placeable

    // This occurs when 'measure' was skipped - usually due to the node already having a fixed size.
    if (placeable == null) {
      placeable = createPlaceable(node.width, MeasureMode.Exactly, node.height, MeasureMode.Exactly)
    }

    return placeable
  }

  override fun measure(
    node: Node,
    width: Float,
    widthMode: MeasureMode,
    height: Float,
    heightMode: MeasureMode,
  ): Size {
    val placeable = createPlaceable(width, widthMode, height, heightMode)
    return Size(placeable.width.toFloat(), placeable.height.toFloat())
  }

  private fun createPlaceable(
    width: Float,
    widthMode: MeasureMode,
    height: Float,
    heightMode: MeasureMode,
  ): Placeable {
    val constraints = measureSpecsToConstraints(width, widthMode, height, heightMode)
    return measurable.measure(constraints).also { placeable = it }
  }
}

internal fun measureSpecsToConstraints(
  width: Float,
  widthMode: MeasureMode,
  height: Float,
  heightMode: MeasureMode,
): Constraints {
  val minWidth: Int
  val maxWidth: Int
  when (widthMode) {
    MeasureMode.Exactly -> {
      minWidth = width.toInt()
      maxWidth = width.toInt()
    }

    MeasureMode.AtMost -> {
      minWidth = 0
      maxWidth = width.toInt()
    }

    MeasureMode.Undefined -> {
      minWidth = 0
      maxWidth = Constraints.Infinity
    }

    else -> throw AssertionError()
  }
  val minHeight: Int
  val maxHeight: Int
  when (heightMode) {
    MeasureMode.Exactly -> {
      minHeight = height.toInt()
      maxHeight = height.toInt()
    }

    MeasureMode.AtMost -> {
      minHeight = 0
      maxHeight = height.toInt()
    }

    MeasureMode.Undefined -> {
      minHeight = 0
      maxHeight = Constraints.Infinity
    }

    else -> throw AssertionError()
  }
  return Constraints(minWidth, maxWidth, minHeight, maxHeight)
}

internal fun RedwoodDp.toDp(): Dp {
  return Dp(toPlatformDp().toFloat())
}
