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
package dev.keliver.compose.extras

import androidx.compose.runtime.Composable
import dev.keliver.compose.gatea.compose.ExtraShimmer
import dev.keliver.compose.internal.splitModifierEvents
import dev.keliver.compose.ui.Modifier
import dev.keliver.compose.ui.graphics.RectangleShape
import dev.keliver.compose.ui.graphics.Shape

/**
 * A Keliver extra, not Compose API: an animated skeleton placeholder the host draws with an
 * infinite transition. Size it with modifiers, as a Box.
 */
@Composable
public fun Shimmer(modifier: Modifier = Modifier, shape: Shape = RectangleShape) {
  val split = splitModifierEvents(modifier)
  ExtraShimmer(shape = shape, modifierEvent = split.events, modifier = split.modifier)
}
