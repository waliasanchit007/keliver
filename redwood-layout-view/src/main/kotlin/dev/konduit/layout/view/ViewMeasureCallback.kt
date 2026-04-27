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
package dev.konduit.layout.view

import android.view.View
import dev.konduit.yoga.MeasureCallback
import dev.konduit.yoga.MeasureMode
import dev.konduit.yoga.Node
import dev.konduit.yoga.Size
import kotlin.math.roundToInt

internal object ViewMeasureCallback : MeasureCallback {
  override fun measure(
    node: Node,
    width: Float,
    widthMode: MeasureMode,
    height: Float,
    heightMode: MeasureMode,
  ): Size {
    val view = node.context as View
    val safeWidth = if (width.isFinite()) width.roundToInt() else 0
    val safeHeight = if (height.isFinite()) height.roundToInt() else 0
    val widthSpec = View.MeasureSpec.makeMeasureSpec(safeWidth, widthMode.toAndroid())
    val heightSpec = View.MeasureSpec.makeMeasureSpec(safeHeight, heightMode.toAndroid())
    view.measure(widthSpec, heightSpec)
    return Size(view.measuredWidth.toFloat(), view.measuredHeight.toFloat())
  }
}
