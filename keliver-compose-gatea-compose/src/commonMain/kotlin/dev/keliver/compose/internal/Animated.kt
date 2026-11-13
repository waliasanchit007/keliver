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
@file:OptIn(KeliverGeneratedApi::class)

package dev.keliver.compose.internal

import dev.keliver.Modifier
import dev.keliver.compose.animation.core.AnimatedRef
import dev.keliver.compose.animation.core.AnimatedReferences
import dev.keliver.compose.gatea.compose.ExtraAnimatedImpl
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.unit.Dp
import dev.keliver.compose.wire.KeliverGeneratedApi

/*
 * The generated stubs' side of animated references (Gate B-e; AnimatedReferences.kt in
 * keliver-compose-values): for a parameter configured as `animated`, the stub sends the exact
 * target, and an `ExtraAnimated` element when the value came from `animate*AsState`.
 */

private fun element(param: String, ref: AnimatedRef?): Modifier = if (ref == null) Modifier else ExtraAnimatedImpl(param, ref)

public fun animated(param: String, value: Float): Modifier = element(param, AnimatedReferences.refOf(value))
public fun animated(param: String, value: Dp): Modifier = element(param, AnimatedReferences.refOf(value))
public fun animated(param: String, value: Color): Modifier = element(param, AnimatedReferences.refOf(value))

public fun untag(value: Float): Float = AnimatedReferences.targetOf(value)
public fun untag(value: Dp): Dp = AnimatedReferences.targetOf(value)
public fun untag(value: Color): Color = AnimatedReferences.targetOf(value)
