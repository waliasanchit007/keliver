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

package dev.keliver.compose.animation.core

import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.unit.Dp
import dev.keliver.compose.ui.unit.dp
import dev.keliver.compose.wire.KeliverGeneratedApi
import kotlin.math.floor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guest's tagged values (Gate B-e, docs/GATE_B_RESULTS.md decision 2). Runs on the JVM, JS and
 * wasm; JS is where the guest runs (QuickJS). The comparison tests pin what a guest read of an
 * animated value IS today, not what it should be.
 */
class AnimatedReferencesTest {
  private val handles = ArrayList<Handle<*, *>>()

  private fun ref(h: Handle<*, *>) = AnimatedRef(h.id, null)
  private fun float(target: Float, h: FloatHandle = FloatHandle().also { handles += it }) = h.tag(target, ref(h))
  private fun dp(target: Dp, h: DpHandle = DpHandle().also { handles += it }) = h.tag(target, ref(h))
  private fun color(target: Color, h: ColorHandle = ColorHandle().also { handles += it }) = h.tag(target, ref(h))

  @AfterTest fun forgetAll() {
    handles.forEach { it.onForgotten() }
    assertEquals(0, AnimatedReferences.size)
    assertTrue(AnimatedReferences.floatSlots.isEmpty() && AnimatedReferences.dpSlots.isEmpty() && AnimatedReferences.colorSlots.isEmpty())
  }

  @Test fun tagAndUntagRoundTrip() {
    val h = FloatHandle().also { handles += it }
    val tagged = float(0.5f, h)
    assertEquals(h.id, AnimatedReferences.refOf(tagged)?.id)
    assertEquals(0.5f, AnimatedReferences.targetOf(tagged))
    assertNull(AnimatedReferences.refOf(0.5f), "the plain target is not a reference")
    assertEquals(0.25f, AnimatedReferences.targetOf(0.25f))
    assertEquals(tagged, float(0.5f, h), "the same target and spec keep the tag")

    val d = DpHandle().also { handles += it }
    val taggedDp = dp(16.dp, d)
    assertEquals(d.id, AnimatedReferences.refOf(taggedDp)?.id)
    assertEquals(16.dp, AnimatedReferences.targetOf(taggedDp))

    val c = ColorHandle().also { handles += it }
    val taggedColor = color(Color.Red, c)
    assertEquals(c.id, AnimatedReferences.refOf(taggedColor)?.id)
    assertEquals(Color.Red, AnimatedReferences.targetOf(taggedColor))
    val reference = Color.reference("colorScheme.primary", 0.5f)
    val taggedRef = color(reference)
    assertEquals(reference, AnimatedReferences.targetOf(taggedRef))
    assertEquals(reference.referenceName, taggedRef.referenceName)
    assertEquals(reference.alpha, taggedRef.alpha)
    assertEquals(4, AnimatedReferences.size)
  }

  @Test fun aNewTargetReleasesTheOldTag() {
    val h = FloatHandle().also { handles += it }
    val first = float(1f, h)
    val second = float(0f, h)
    assertNull(AnimatedReferences.refOf(first))
    assertEquals(h.id, AnimatedReferences.refOf(second)?.id)
    assertEquals(1, AnimatedReferences.size)
  }

  @Test fun forgettingReleasesTheTagAndItsCount() {
    val a = FloatHandle()
    val b = FloatHandle().also { handles += it }
    val taggedA = float(1f, a)
    val taggedB = float(1f, b)
    a.onForgotten()
    assertNull(AnimatedReferences.refOf(taggedA))
    assertEquals(b.id, AnimatedReferences.refOf(taggedB)?.id)
    assertEquals(1, AnimatedReferences.size)
    assertEquals(taggedA, float(1f), "a released count is reused")
  }

  @Test fun untaggableTargetsAreReturnedAsTheyAre() {
    for (t in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE, -Float.MAX_VALUE)) {
      val v = float(t)
      assertEquals(t.toBits(), v.toBits(), "$t")
      assertNull(AnimatedReferences.refOf(v))
    }
    for (t in listOf(Dp.Unspecified, Dp.Infinity, Dp(Float.MAX_VALUE))) {
      assertEquals(t.value.toBits(), dp(t).value.toBits(), "$t")
    }
    assertEquals(Color.Unspecified, color(Color.Unspecified))
    assertEquals(0, AnimatedReferences.size)

    // One below MAX_VALUE: the first call site tags to MAX_VALUE, the second would cross to
    // Infinity, so it gets the plain (finite) target.
    val nearMax = Float.fromBits(Float.MAX_VALUE.toBits() - 1)
    // (Bits, not values: on Kotlin/JS the Float.MAX_VALUE constant is a decimal double that only
    // rounds to the float.)
    assertEquals(Float.MAX_VALUE.toBits(), float(nearMax).toBits())
    assertEquals(nearMax.toBits(), float(nearMax).toBits())
    assertEquals(1, AnimatedReferences.size)
  }

  @Test fun callSitesSharingATargetTakeConsecutiveCounts() {
    val tagged = (1..1000).map { float(1f) }
    assertEquals(1000, tagged.toSet().size)
    assertEquals(1000, tagged.map { AnimatedReferences.refOf(it)!!.id }.toSet().size)
    assertTrue(tagged.all { AnimatedReferences.targetOf(it) == 1f })
    // The error grows with the number of call sites: the 1000th reads 1f + 1000 ULPs ≈ 1.00012.
    assertEquals(Float.fromBits(1f.toBits() + 1000), tagged.last())
    assertTrue(tagged.last() - 1f > 1.1e-4f && tagged.last() - 1f < 1.3e-4f, "${tagged.last()}")
  }

  @Test fun anotherTargetsTagIsSkipped() {
    val next = Float.fromBits(1f.toBits() + 1)
    val a = float(next) // next + 1 ULP = 1f + 2 ULPs
    assertEquals(Float.fromBits(1f.toBits() + 1), float(1f))
    val b = float(1f) // count 2 clashes with a's tag: skipped
    assertNotEquals(a, b)
    assertEquals(Float.fromBits(1f.toBits() + 3), b)
    assertEquals(next, AnimatedReferences.targetOf(a))
    assertEquals(1f, AnimatedReferences.targetOf(b))
  }

  // What a guest read of a tagged value is, today (docs/GATE_B_RESULTS.md, decision 2).

  @Test fun zeroReadsAsTheSmallestFloat() {
    val v = float(0f)
    assertEquals(1, v.toBits())
    assertTrue(v > 0f)
    assertFalse(v == 0f)
    val n = float(-0f)
    assertEquals((-0f).toBits() + 1, n.toBits())
    assertTrue(n < 0f)
  }

  @Test fun aNegativeTargetReadsFurtherFromZero() {
    val r = float(-90f)
    assertTrue(r < -90f)
    assertFalse(r >= -90f)
    assertEquals(-4.0, floor(float(-3f).toDouble()))
    assertEquals(3.0, floor(float(3f).toDouble()), "a positive target floors to itself")
    assertTrue(dp((-8).dp) < (-8).dp)
  }

  @Test fun aTaggedValueIsNeverEqualToItsTarget() {
    assertFalse(float(1f) == 1f)
    assertFalse(dp(16.dp) == 16.dp)
    val c = color(Color.Red)
    assertFalse(c == Color.Red)
    // Component reads are exact.
    assertEquals(listOf(1f, 1f, 0f, 0f), listOf(c.alpha, c.red, c.green, c.blue))
    assertEquals(Color.Red.argb, c.argb)
    val matched = when (c) {
      Color.Red -> "red"
      else -> "other"
    }
    assertEquals("other", matched)
  }

  @Test fun aTaggedColorTargetIsNotTaggedAgain() {
    val c = color(Color.Red)
    assertEquals(c, color(c), "an already-tagged literal has count bits set")
  }
}
