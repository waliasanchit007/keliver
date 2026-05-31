/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package dev.keliver.yoga.internal.interfaces

import dev.keliver.yoga.internal.YGNode
import dev.keliver.yoga.internal.YGSize
import dev.keliver.yoga.internal.enums.YGMeasureMode

internal fun interface YGMeasureFunc {
  operator fun invoke(
    node: YGNode,
    width: Float,
    widthMode: YGMeasureMode,
    height: Float,
    heightMode: YGMeasureMode,
  ): YGSize
}
