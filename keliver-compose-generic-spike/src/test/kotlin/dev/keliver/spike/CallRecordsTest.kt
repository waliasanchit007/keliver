/*
 * Copyright (C) 2026 Keliver contributors.
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
package dev.keliver.spike

import androidx.compose.ui.geometry.Offset as HostOffset
import androidx.compose.ui.graphics.Brush as HostBrush
import androidx.compose.ui.graphics.Color as HostColor
import androidx.compose.ui.graphics.TileMode as HostTileMode
import androidx.compose.ui.text.TextStyle as HostTextStyle
import androidx.compose.ui.text.font.FontWeight as HostFontWeight
import androidx.compose.ui.text.style.TextAlign as HostTextAlign
import androidx.compose.ui.unit.dp as hostDp
import androidx.compose.ui.unit.sp as hostSp
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.unit.dp
import dev.keliver.compose.ui.unit.sp
import dev.keliver.spike.compose.foundation.shape.RoundedCornerShape
import dev.keliver.spike.compose.ui.geometry.Offset
import dev.keliver.spike.compose.ui.graphics.Brush
import dev.keliver.spike.compose.ui.graphics.TileMode
import dev.keliver.spike.compose.ui.text.TextStyle
import dev.keliver.spike.compose.ui.text.font.FontWeight
import dev.keliver.spike.compose.ui.text.style.TextAlign
import dev.keliver.spike.host.GeneratedReplay
import dev.keliver.spike.wire.CallRecorded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json

/**
 * Mechanism 1 (docs/COMPOSE_GENERIC_MECHANISMS.md): a value built in the guest with the generated
 * mirrors crosses as a recorded call, and the host's generated replay makes the same call. The
 * result equals the value Compose builds from the same source text.
 */
class CallRecordsTest {
  /** Through JSON text, as the protocol carries it. */
  private fun replay(v: CallRecorded): Any = GeneratedReplay.replay(Json.parseToJsonElement(v.call.toJson().toString()))

  @Test fun textStyle() {
    val guest = TextStyle(color = Color(0xFF112233), fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, letterSpacing = 0.5.sp)
    val native = HostTextStyle(color = HostColor(0xFF112233), fontSize = 18.hostSp, fontWeight = HostFontWeight.Bold, textAlign = HostTextAlign.Center, letterSpacing = 0.5.hostSp)
    assertEquals(native, replay(guest))
    // Nested call records: a constructor-made FontWeight inside a TextStyle.
    assertEquals(HostTextStyle(fontWeight = HostFontWeight(600)), replay(TextStyle(fontWeight = FontWeight(600))))
  }

  @Test fun leftOutArgumentsTakeComposesDefaultOnTheHost() {
    assertEquals("""{"call":"androidx.compose.ui.text.TextStyle#<init>(color,fontSize,fontWeight,fontStyle,fontSynthesis,fontFamily,fontFeatureSettings,letterSpacing,baselineShift,textGeometricTransform,localeList,background,textDecoration,shadow,drawStyle,textAlign,textDirection,lineHeight,textIndent,platformStyle,lineHeightStyle,lineBreak,hyphens,textMotion)"}""", TextStyle().call.toJson().toString())
    assertEquals(HostTextStyle.Default, replay(TextStyle()))
    // Brush.linearGradient's start, end and tileMode defaults (Offset.Zero, Offset.Infinite, TileMode.Clamp) are the host's.
    assertEquals(HostBrush.linearGradient(listOf(HostColor.Red, HostColor.Blue)), replay(Brush.linearGradient(listOf(Color.Red, Color.Blue))))
  }

  @Test fun brushLinearGradient() {
    val guest = Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), start = Offset(0f, 0f), end = Offset(100f, 0f), tileMode = TileMode.Mirror)
    val native = HostBrush.linearGradient(colors = listOf(HostColor.Red, HostColor.Blue), start = HostOffset(0f, 0f), end = HostOffset(100f, 0f), tileMode = HostTileMode.Mirror)
    assertEquals(native, replay(guest))
  }

  @Test fun roundedCornerShape() {
    val guest = RoundedCornerShape(8.dp)
    assertEquals("""{"call":"androidx.compose.foundation.shape.RoundedCornerShape(size:Dp)","args":{"size":8}}""", guest.call.toJson().toString())
    assertEquals(androidx.compose.foundation.shape.RoundedCornerShape(8.hostDp), replay(guest))
    // A RoundedCornerShape is a Shape in the guest too (its mirror implements Shape's mirror).
    val shape: dev.keliver.spike.compose.ui.graphics.Shape = guest
    assertEquals(guest, shape)
  }

  @Test fun equalityIsTheCallsNotTheValues() {
    assertEquals(TextStyle(fontSize = 18.sp), TextStyle(fontSize = 18.sp))
    assertEquals(TextStyle(fontSize = 18.sp).hashCode(), TextStyle(fontSize = 18.sp).hashCode())
    // What breaks (the doc's list): equal Compose values from different calls are different records.
    assertNotEquals(TextStyle(), TextStyle(color = Color.Unspecified))
    assertNotEquals(FontWeight.Bold, FontWeight(700))
    assertEquals(HostFontWeight.Bold, HostFontWeight(700))
  }
}
