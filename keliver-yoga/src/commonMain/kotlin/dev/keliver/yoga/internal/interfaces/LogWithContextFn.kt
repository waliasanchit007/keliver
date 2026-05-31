/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package dev.keliver.yoga.internal.interfaces

import dev.keliver.yoga.internal.YGConfig
import dev.keliver.yoga.internal.YGNode
import dev.keliver.yoga.internal.enums.YGLogLevel

internal fun interface LogWithContextFn {
  operator fun invoke(
    config: YGConfig?,
    node: YGNode?,
    level: YGLogLevel,
    context: Any?,
    format: String,
    vararg args: Any?
  ): Int
}
