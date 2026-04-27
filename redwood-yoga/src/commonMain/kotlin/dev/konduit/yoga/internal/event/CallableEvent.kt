/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
@file:Suppress("unused")

package dev.konduit.yoga.internal.event

import dev.konduit.yoga.internal.YGConfig
import dev.konduit.yoga.internal.enums.YGMeasureMode

internal sealed interface CallableEvent

internal class LayoutData : CallableEvent {
  var layouts = 0
  var measures = 0
  var maxMeasureCache = 0
  var cachedLayouts = 0
  var cachedMeasures = 0
  var measureCallbacks = 0
  val measureCallbackReasonsCount = IntArray(LayoutPassReason.COUNT) { 0 }
}

internal class LayoutPassStartEventData(
  val layoutContext: Any?,
) : CallableEvent

internal class LayoutPassEndEventData(
  val layoutContext: Any?,
  val layoutData: LayoutData,
) : CallableEvent

internal class MeasureCallbackEndEventData(
  val layoutContext: Any?,
  val width: Float,
  val widthMeasureMode: YGMeasureMode,
  val height: Float,
  val heightMeasureMode: YGMeasureMode,
  val measuredWidth: Float,
  val measuredHeight: Float,
  val reason: LayoutPassReason,
) : CallableEvent

internal class NodeAllocationEventData(
  val config: YGConfig?,
) : CallableEvent

internal class NodeDeallocationEventData(
  val config: YGConfig?,
) : CallableEvent

internal class NodeLayoutEventData(
  val layoutType: LayoutType,
  val layoutContext: Any?,
) : CallableEvent

internal object EmptyEventData : CallableEvent
