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
package dev.keliver.compose.animation

import dev.keliver.compose.animation.core.AnimatedRef
import dev.keliver.compose.animation.core.AnimatedRefCodec
import dev.keliver.compose.animation.core.AnimationMismatches
import dev.keliver.compose.animation.core.CubicBezierEasing
import dev.keliver.compose.animation.core.DecodedSpec
import dev.keliver.compose.animation.core.FastOutSlowInEasing
import dev.keliver.compose.animation.core.LinearEasing
import dev.keliver.compose.animation.core.Spring
import dev.keliver.compose.animation.core.VisibilityThreshold
import dev.keliver.compose.animation.core.decode
import dev.keliver.compose.animation.core.snap
import dev.keliver.compose.animation.core.spring
import dev.keliver.compose.animation.core.tween
import dev.keliver.compose.animation.core.wire
import dev.keliver.compose.ui.unit.IntSize
import dev.keliver.compose.wire.KeliverGeneratedApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json

class AnimationCodecTest {
  @Test fun specsTravelAsShortJson() {
    assertEquals("""{"tween":[400,0,"linear"]}""", tween<Float>(400, easing = LinearEasing).wire.toString())
    assertEquals("""{"tween":[300,50,[0.4,0,0.2,1]]}""", tween<Float>(delayMillis = 50).wire.toString())
    assertEquals(
      """{"spring":[1,400],"vt":[1,1]}""",
      spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold).wire.toString(),
    )
    assertEquals("""{"snap":10}""", snap<Float>(10).wire.toString())
  }

  @Test fun specsDecode() {
    val t = assertIs<DecodedSpec.Tween>(tween<Float>(250, 5, FastOutSlowInEasing).wire.decode())
    assertEquals(listOf(250, 5), listOf(t.durationMillis, t.delay))
    assertEquals(listOf(0.4f, 0f, 0.2f, 1f), t.easing)
    val s = assertIs<DecodedSpec.Spring>(spring<Float>(0.5f, 200f).wire.decode())
    assertEquals(0.5f to 200f, s.dampingRatio to s.stiffness)
  }

  @OptIn(KeliverGeneratedApi::class)
  @Test fun aCustomEasingIsReportedAndFallsBackToTheDefault() {
    val reported = ArrayList<String>()
    val sink = AnimationMismatches.sink
    AnimationMismatches.reset()
    AnimationMismatches.sink = { reported += it }
    try {
      assertEquals(tween<Float>(300).wire, tween<Float>(300, easing = { it * it }).wire)
      assertEquals(1, reported.size)
      assertTrue("custom Easing" in reported.single())
    } finally {
      AnimationMismatches.sink = sink
      AnimationMismatches.reset()
    }
  }

  @Test fun specsAndTransitionsCompareByValue() {
    assertEquals(spring<Float>(stiffness = 400f), spring(stiffness = 400f))
    assertEquals(fadeIn() + expandIn(), fadeIn() + expandIn())
    assertNotEquals(fadeIn() + expandIn(), fadeIn() + expandVertically())
    assertEquals(CubicBezierEasing(0.4f, 0f, 0.2f, 1f), FastOutSlowInEasing)
  }

  @Test fun transitionsRoundTrip() {
    val enter = fadeIn(tween(200), initialAlpha = 0.5f) + expandVertically()
    val json = Json.encodeToString(EnterTransitionCodec, enter)
    assertEquals(enter, Json.decodeFromString(EnterTransitionCodec, json))
  }

  @Test fun animatedRefRoundTrips() {
    val ref = AnimatedRef(7, tween<Float>(400).wire, 0.02f, "alpha")
    val json = Json.encodeToString(AnimatedRefCodec, ref)
    assertEquals("""{"id":7,"spec":{"tween":[400,0,[0.4,0,0.2,1]]},"vt":0.02,"label":"alpha"}""", json)
    assertEquals(ref, Json.decodeFromString(AnimatedRefCodec, json))
    assertEquals("""{"id":1}""", Json.encodeToString(AnimatedRefCodec, AnimatedRef(1, null)))
  }

  @Test fun cubicBezierEndsAndMidpoint() {
    assertEquals(0f, FastOutSlowInEasing.transform(0f))
    assertEquals(1f, FastOutSlowInEasing.transform(1f))
    // Compose's FastOutSlowInEasing at 0.5 is about 0.774.
    val mid = FastOutSlowInEasing.transform(0.5f)
    kotlin.test.assertTrue(mid in 0.77f..0.78f, "$mid")
  }
}
