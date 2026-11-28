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
@file:OptIn(InternalCallApi::class)

package dev.keliver.calls.composeui

import androidx.compose.foundation.shape.RoundedCornerShape as HostRoundedCornerShape
import androidx.compose.ui.geometry.Offset as HostOffset
import androidx.compose.ui.graphics.Brush as HostBrush
import androidx.compose.ui.graphics.Color as HostColor
import androidx.compose.ui.graphics.TileMode as HostTileMode
import androidx.compose.ui.graphics.drawscope.Stroke as HostStroke
import androidx.compose.ui.text.TextStyle as HostTextStyle
import androidx.compose.ui.text.font.FontWeight as HostFontWeight
import androidx.compose.ui.text.style.TextAlign as HostTextAlign
import androidx.compose.ui.unit.dp as hostDp
import androidx.compose.ui.unit.sp as hostSp
import dev.keliver.calls.compose.foundation.shape.RoundedCornerShape
import dev.keliver.calls.compose.ui.geometry.Offset
import dev.keliver.calls.compose.ui.graphics.Brush
import dev.keliver.calls.compose.ui.graphics.Shape
import dev.keliver.calls.compose.ui.graphics.TileMode
import dev.keliver.calls.compose.ui.graphics.drawscope.Stroke
import dev.keliver.calls.compose.ui.text.TextStyle
import dev.keliver.calls.compose.ui.text.font.FontWeight
import dev.keliver.calls.compose.ui.text.style.TextAlign
import dev.keliver.calls.runtime.CallRecorded
import dev.keliver.calls.runtime.InternalCallApi
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.unit.dp
import dev.keliver.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json

/**
 * MS4-42 (G1): a value built in the guest with the generated mirrors crosses as the call that built
 * it, and the host's generated replay makes the same call. The result equals the value Compose builds
 * from the same source text. Moved from #312's spike (docs/COMPOSE_GENERIC_MECHANISMS.md §1.1) and
 * extended with the product's lock tags, receiver call records and readable properties. It runs on the
 * JVM, JS, wasm and the iOS simulator: the guest half is common code, and so is Compose's.
 *
 * The call crosses as JSON text, which is what a protocol property of a call record carries; the
 * lock tags below are pinned on purpose: a tag never changes (values.lock.json is append-only).
 */
class CallRecordsTest {
  /** Through JSON text, as the protocol carries it. */
  private fun replay(v: CallRecorded): Any = GeneratedReplay.replay(Json.parseToJsonElement(v.call.toJson().toString()))

  @Test fun roundedCornerShape() {
    val guest = RoundedCornerShape(8.dp)
    // The wire: the declaration's tag in the shared values lock, not a string id; arguments by parameter name.
    assertEquals("""{"call":${Tags.RoundedCornerShapeSize},"args":{"size":8}}""", guest.call.toJson().toString())
    assertEquals(HostRoundedCornerShape(8.hostDp), replay(guest))
    // A RoundedCornerShape is a Shape in the guest too (its mirror implements Shape's mirror).
    val shape: Shape = guest
    assertEquals(guest, shape)
  }

  @Test fun textStyle() {
    val guest = TextStyle(color = Color(0xFF112233), fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, letterSpacing = 0.5.sp)
    val native = HostTextStyle(color = HostColor(0xFF112233), fontSize = 18.hostSp, fontWeight = HostFontWeight.Bold, textAlign = HostTextAlign.Center, letterSpacing = 0.5.hostSp)
    assertEquals(native, replay(guest))
    // Nested call records: a constructor-made FontWeight inside a TextStyle.
    assertEquals(HostTextStyle(fontWeight = HostFontWeight(600)), replay(TextStyle(fontWeight = FontWeight(600))))
  }

  @Test fun leftOutArgumentsTakeComposesDefaultOnTheHost() {
    // TextStyle() crosses with no arguments.
    assertEquals("""{"call":${Tags.TextStyleCtor}}""", TextStyle().call.toJson().toString())
    assertEquals(HostTextStyle.Default, replay(TextStyle()))
    // Brush.linearGradient's start, end and tileMode defaults (Offset.Zero, Offset.Infinite, TileMode.Clamp) are the host's.
    assertEquals(HostBrush.linearGradient(listOf(HostColor.Red, HostColor.Blue)), replay(Brush.linearGradient(listOf(Color.Red, Color.Blue))))
  }

  @Test fun brushLinearGradient() {
    val guest = Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), start = Offset(0f, 0f), end = Offset(100f, 0f), tileMode = TileMode.Mirror)
    val native = HostBrush.linearGradient(colors = listOf(HostColor.Red, HostColor.Blue), start = HostOffset(0f, 0f), end = HostOffset(100f, 0f), tileMode = HostTileMode.Mirror)
    assertEquals(native, replay(guest))
  }

  @Test fun stroke() {
    assertEquals(HostStroke(width = 3f), replay(Stroke(width = 3f)))
  }

  /** `typography.titleMedium.copy(…)`: a call on a recorded value, carrying the receiver. */
  @Test fun receiverCallRecord() {
    val base = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val guest = base.copy(color = Color(0xFF445566), fontSize = 20.sp)
    val native = HostTextStyle(fontSize = 16.hostSp, fontWeight = HostFontWeight.Medium).copy(color = HostColor(0xFF445566), fontSize = 20.hostSp)
    assertEquals(native, replay(guest))
    // The receiver is part of the wire.
    assertEquals(true, guest.call.toJson().toString().contains(""""on":{"call":"""))
  }

  /** Design §5 case 1: a constructor-backed property is readable, because the guest holds the arguments. */
  @Test fun readableConstructorBackedProperties() {
    val style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
    assertEquals(18.sp, style.fontSize)
    assertEquals(FontWeight.Bold, style.fontWeight)
    // Not passed: Compose's default, copied into the guest.
    assertEquals(dev.keliver.compose.ui.unit.TextUnit.Unspecified, style.letterSpacing)
    assertEquals(Color.Unspecified, style.color)
    // A copy keeps the fields it did not change.
    assertEquals(24.sp, style.copy(fontSize = 24.sp).fontSize)
    assertEquals(FontWeight.Bold, style.copy(fontSize = 24.sp).fontWeight)
    // A value a factory or constant made has no fields in the guest: the read says why, at run time.
    val error = assertFailsWith<IllegalStateException> { FontWeight.Bold.weight }
    assertEquals(true, "not readable" in error.message.orEmpty())
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

/** Lock tags the test pins (values.lock.json never changes a tag). */
private object Tags {
  const val RoundedCornerShapeSize = 216
  const val TextStyleCtor = 653
}
