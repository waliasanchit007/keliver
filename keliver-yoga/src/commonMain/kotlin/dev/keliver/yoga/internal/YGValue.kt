/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package dev.keliver.yoga.internal

import dev.keliver.yoga.internal.enums.YGUnit

internal data class YGValue(
  val value: Float,
  val unit: YGUnit,
)
