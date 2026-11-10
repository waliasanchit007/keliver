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

import dev.keliver.compose.foundation.ScrollState
import dev.keliver.compose.foundation.layout.Arrangement
import dev.keliver.compose.foundation.layout.PaddingValues
import dev.keliver.compose.foundation.shape.CircleShape
import dev.keliver.compose.foundation.shape.RoundedCornerShape
import dev.keliver.compose.material3.ColorScheme
import dev.keliver.compose.material3.Typography
import dev.keliver.compose.ui.Alignment
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.graphics.RectangleShape
import dev.keliver.compose.ui.graphics.Shape
import dev.keliver.compose.ui.text.TextStyle
import dev.keliver.compose.ui.text.font.FontWeight
import dev.keliver.compose.ui.unit.Dp
import dev.keliver.compose.ui.unit.TextUnit
import dev.keliver.compose.ui.unit.dp
import dev.keliver.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class CodecTest {
  private fun <T> roundTrip(serializer: KSerializer<T>, value: T, wire: String) {
    val text = Json.encodeToString(serializer, value)

    // Kotlin/JS writes 0.0f as 0; compare without trailing ".0".
    fun norm(s: String) = s.replace(Regex("(\\d)\\.0(?=[,\\]}]|$)"), "$1")
    assertEquals(norm(wire), norm(text))
    assertEquals(value, Json.decodeFromString(serializer, text))
  }

  @Test fun units() {
    roundTrip(Dp.serializer(), 16.dp, "16")
    roundTrip(Dp.serializer(), 1.5.dp, "1.5")
    roundTrip(TextUnit.serializer(), 14.sp, "\"14sp\"")
    roundTrip(TextUnit.serializer(), TextUnit.Unspecified, "\"u\"")
  }

  @Test fun colours() {
    roundTrip(Color.serializer(), Color(0xFF1F1F1F), "4280229663")
    roundTrip(Color.serializer(), Color.Unspecified, "\"u\"")
    roundTrip(Color.serializer(), ColorScheme.primary, "{\"ref\":\"colorScheme.primary\"}")
    roundTrip(Color.serializer(), ColorScheme.primary.copy(alpha = 0f), "{\"ref\":\"colorScheme.primary\",\"a\":0}")
  }

  @Test fun layout() {
    roundTrip(Alignment.serializer(), Alignment.Center, "[0.0,0.0]")
    roundTrip(Alignment.Vertical.serializer(), Alignment.CenterVertically, "0")
    roundTrip(Arrangement.Horizontal.serializer(), Arrangement.SpaceBetween, "\"sb\"")
    roundTrip(Arrangement.Vertical.serializer(), Arrangement.spacedBy(8.dp), "{\"sp\":8}")
    roundTrip(PaddingValues.serializer(), PaddingValues(start = 1.dp, top = 2.dp, end = 3.dp, bottom = 4.dp), "[1,2,3,4]")
    roundTrip(ScrollState.serializer(), ScrollState(0), "0")
  }

  @Test fun shapesAndText() {
    roundTrip(Shape.serializer(), RoundedCornerShape(12.dp), "{\"rr\":[12,12,12,12]}")
    roundTrip(Shape.serializer(), CircleShape, "{\"rr\":[\"50%\",\"50%\",\"50%\",\"50%\"]}")
    roundTrip(Shape.serializer(), RectangleShape, "\"rect\"")
    roundTrip(TextStyle.serializer(), Typography.titleMedium.copy(fontWeight = FontWeight.Bold), "{\"ref\":\"typography.titleMedium\",\"set\":{\"fontWeight\":700}}")
  }
}
