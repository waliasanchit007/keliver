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
package dev.keliver.compose.wire

import dev.keliver.compose.foundation.BorderStroke
import dev.keliver.compose.foundation.lazy.LazyListStateWire
import dev.keliver.compose.foundation.lazy.ScrollCommand
import dev.keliver.compose.foundation.shape.RoundedCornerShape
import dev.keliver.compose.ui.Alignment
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.graphics.ColorFilter
import dev.keliver.compose.ui.graphics.Shape
import dev.keliver.compose.ui.graphics.painter.Painter
import dev.keliver.compose.ui.graphics.painter.VectorPainter
import dev.keliver.compose.ui.graphics.vector.ImageVector
import dev.keliver.compose.ui.unit.Dp
import dev.keliver.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToDynamic

/**
 * The guest's fast protocol path encodes property values with encodeToDynamic; each codec must
 * give there what it gives as JSON text (an array value was once flattened to its first element).
 */
class DynamicCodecTest {
  private fun <T> same(serializer: KSerializer<T>, value: T) =
    assertEquals(Json.encodeToString(serializer, value), JSON.stringify(Json.encodeToDynamic(serializer, value)).replace(".0", ""))

  @Test fun dynamicEqualsText() {
    same(Alignment.serializer(), Alignment.TopStart.let { Alignment.BottomEnd })
    same(Dp.serializer(), 16.dp)
    same(Color.serializer(), Color(0xFF1F1F1F))
    same(Shape.serializer(), RoundedCornerShape(12.dp))
  }

  @Test fun gateBValuesDynamicEqualsText() {
    // Objects holding arrays and nested objects: a scroll command, a recorded `*Defaults` call.
    same(LazyListStateWire.serializer(), LazyListStateWire(0, 0, ScrollCommand(2, 42, 8, true)))
    same(BorderStroke.serializer(), BorderStroke(1.dp, Color(0xFFD0D0D0)))
    same(BorderStroke.serializer(), BorderStroke.ofCall(DefaultsCall(7, mapOf(1 to defaultsArg(true), 2 to defaultsArg(2.dp)))))
    same(Painter.serializer(), VectorPainter.of(ImageVector("Filled.Info")))
    same(ColorFilter.serializer(), ColorFilter.tint(Color.White))
  }
}
