/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package dev.konduit.yoga.internal.interfaces

import dev.konduit.yoga.internal.YGNode

internal fun interface YGBaselineFunc {
  operator fun invoke(
    node: YGNode,
    width: Float,
    height: Float,
  ): Float
}
