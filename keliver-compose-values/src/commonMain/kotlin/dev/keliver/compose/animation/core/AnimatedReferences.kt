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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.unit.Dp
import dev.keliver.compose.wire.JsonCodec
import dev.keliver.compose.wire.KeliverGeneratedApi
import dev.keliver.compose.wire.toCompact
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * Animated references (docs/COMPOSE_GENERATOR_DESIGN.md §10, Gate B-e): `animate*AsState` in the
 * guest does not animate. It returns a State whose value is the target, TAGGED so that the
 * generated stubs can recognise it: the target's float bits plus a count (the count-th float away
 * from zero; call sites sharing a target take counts 1, 2, 3…), or, for a Color, spare bits of its
 * 64-bit packing that no component read uses. So a guest read is NOT the exact target: `== target`
 * is false (Color too), a target of 0f reads as Float.MIN_VALUE (`> 0f` is true), a negative target
 * reads slightly more negative (`>= -90f` is false). The full semantics, and the read-log
 * alternative, are in docs/GATE_B_RESULTS.md (decision 2); AnimatedReferencesTest pins them. A
 * generated stub that receives a tagged value sends the exact target and, before its modifier
 * element (or at the end of a widget's chain), an `ExtraAnimated` element naming the parameter and
 * carrying this reference: an id, the spec and the label. The host's renderer wraps that argument
 * in the real `animate*AsState`, keyed by the id, so the animation runs on the host's frame clock
 * and nothing crosses the bridge while it runs: one change per new target.
 *
 * Arithmetic on a tagged value (`1f - alpha`, `size * 2`) gives an untagged value, a plain literal
 * with no animation (the design's rule for theme references). A host built before Gate B-e does not
 * know `ExtraAnimated` and ignores it: it renders the target with no animation.
 */

/** What travels for one animated value: `{"id":3,"spec":…,"vt":0.01,"label":"…"}`. */
@Immutable
@Serializable(with = AnimatedRefCodec::class)
public class AnimatedRef(
  public val id: Int,
  /** The spec, or null for the `animate*AsState` function's own default (the host then omits it). */
  public val spec: AnimationSpecWire?,
  /** `animateFloatAsState`'s visibility threshold, when it is not the default. */
  public val visibilityThreshold: Float? = null,
  public val label: String? = null,
) {
  override fun equals(other: Any?): Boolean = other is AnimatedRef && other.id == id && other.spec == spec &&
    other.visibilityThreshold == visibilityThreshold && other.label == label
  override fun hashCode(): Int = id * 31 + (spec?.hashCode() ?: 0)
  override fun toString(): String = "AnimatedRef(id=$id, spec=$spec, label=$label)"
}

public object AnimatedRefCodec : JsonCodec<AnimatedRef>("dev.keliver.compose.AnimatedRef") {
  override fun toJson(value: AnimatedRef): JsonElement = buildJsonObject {
    put("id", value.id)
    value.spec?.let { put("spec", it.json) }
    value.visibilityThreshold?.let { put("vt", JsonPrimitive(it.toCompact())) }
    value.label?.let { put("label", it) }
  }

  override fun fromJson(json: JsonElement): AnimatedRef {
    val o = json as JsonObject
    return AnimatedRef(
      id = o["id"]!!.jsonPrimitive.int,
      spec = o["spec"]?.let { AnimationSpecWire(it) },
      visibilityThreshold = o["vt"]?.jsonPrimitive?.float,
      label = o["label"]?.jsonPrimitive?.content,
    )
  }
}

/** The guest's table of tagged values, read by the generated stubs. */
@KeliverGeneratedApi
public object AnimatedReferences {
  internal class Registration<T>(val target: T, val ref: AnimatedRef)

  private var nextId = 1
  internal val floats: HashMap<Int, Registration<Float>> = HashMap()
  internal val dps: HashMap<Int, Registration<Dp>> = HashMap()
  internal val colors: HashMap<ULong, Registration<Color>> = HashMap()
  internal val floatSlots: HashMap<Int, Slots> = HashMap()
  internal val dpSlots: HashMap<Int, Slots> = HashMap()
  internal val colorSlots: HashMap<ULong, Slots> = HashMap()

  internal fun newId(): Int = nextId++

  /** The reference a tagged value carries, or null for a plain value. */
  public fun refOf(value: Float): AnimatedRef? = if (floats.isEmpty()) null else floats[value.toBits()]?.ref
  public fun refOf(value: Dp): AnimatedRef? = if (dps.isEmpty()) null else dps[value.value.toBits()]?.ref
  public fun refOf(value: Color): AnimatedRef? = if (colors.isEmpty()) null else colors[value.value]?.ref

  /** The exact target of a tagged value; a plain value is returned as it is. */
  public fun targetOf(value: Float): Float = if (floats.isEmpty()) value else floats[value.toBits()]?.target ?: value
  public fun targetOf(value: Dp): Dp = if (dps.isEmpty()) value else dps[value.value.toBits()]?.target ?: value
  public fun targetOf(value: Color): Color = if (colors.isEmpty()) value else colors[value.value]?.target ?: value

  /** How many tagged values are live (tests). */
  public val size: Int get() = floats.size + dps.size + colors.size
}

/**
 * The counts in use for one target: the highest handed out and those released since. Call sites
 * that share a target take counts 1, 2, 3…, and tagging is O(1) per call (no probing from 1).
 */
internal class Slots {
  var high: Int = 0
  val free: ArrayList<Int> = ArrayList()
  var live: Int = 0
}

/**
 * One `animate*AsState` call site's registration; removed when the call leaves the composition.
 * Internal (not private) so that AnimatedReferencesTest can drive it without a composition.
 */
internal abstract class Handle<T, K>(
  private val table: HashMap<K, AnimatedReferences.Registration<T>>,
  private val slots: HashMap<K, Slots>,
) : RememberObserver {
  val id: Int = AnimatedReferences.newId()
  private var key: K? = null
  private var base: K? = null
  private var count: Int = 0
  private var tagged: T? = null

  /** The target's own key: what a plain read of the target looks up. */
  abstract fun baseOf(target: T): K

  /** The key of [target] tagged with [count], or null when that count cannot tag it. */
  abstract fun keyOf(target: T, count: Int): K?
  abstract fun valueOf(key: K): T

  fun tag(target: T, ref: AnimatedRef): T {
    val current = key
    if (current != null) {
      val r = table[current]
      if (r != null && r.target == target && r.ref == ref) return tagged!!
    }
    release()
    // A target that count 1 cannot tag (NaN, ±Infinity, ±MAX_VALUE, Color.Unspecified) is never
    // tagged: the guest reads it exactly, and the host gets it with no animation.
    if (keyOf(target, 1) == null) return target
    val b = baseOf(target)
    val s = slots.getOrPut(b) { Slots() }
    while (true) {
      val c = if (s.free.isNotEmpty()) s.free.removeAt(s.free.size - 1) else ++s.high
      val k = keyOf(target, c)
      if (k == null) {
        // The counts ran out for this target (it is close to the float's limit, or this many call
        // sites share it): untagged, as above.
        s.free.add(c)
        if (s.live == 0) slots.remove(b)
        return target
      }
      // Another target's tag (1f + 2 ULPs is both 1f's count 2 and (1f + 1 ULP)'s count 1): this
      // count is skipped for good (it never goes back to the free list), so a clash costs once.
      if (table.containsKey(k)) continue
      table[k] = AnimatedReferences.Registration(target, ref)
      s.live++
      key = k
      base = b
      count = c
      return valueOf(k).also { tagged = it }
    }
  }

  private fun release() {
    val k = key ?: return
    table.remove(k)
    val b = base!!
    slots[b]?.let { s ->
      s.live--
      if (s.live == 0) slots.remove(b) else s.free.add(count)
    }
    key = null
    base = null
    tagged = null
  }

  override fun onRemembered() {}
  override fun onForgotten() = release()
  override fun onAbandoned() = release()
}

/**
 * A float target's bits plus the count: the count-th representable float AWAY from zero. The
 * tagged value must stay finite and keep the target's sign, so NaN, ±Infinity and ±MAX_VALUE (whose
 * next float is ±Infinity) cannot be tagged.
 */
internal fun taggedFloatBits(target: Float, count: Int): Int? {
  if (target.isNaN() || target.isInfinite()) return null
  val bits = target.toBits()
  val k = bits + count
  val v = Float.fromBits(k)
  if (v.isNaN() || v.isInfinite() || (k xor bits) < 0) return null
  return k
}

internal class FloatHandle : Handle<Float, Int>(AnimatedReferences.floats, AnimatedReferences.floatSlots) {
  override fun baseOf(target: Float) = target.toBits()
  override fun keyOf(target: Float, count: Int) = taggedFloatBits(target, count)
  override fun valueOf(key: Int) = Float.fromBits(key)
}

internal class DpHandle : Handle<Dp, Int>(AnimatedReferences.dps, AnimatedReferences.dpSlots) {
  override fun baseOf(target: Dp) = target.value.toBits()
  override fun keyOf(target: Dp, count: Int) = taggedFloatBits(target.value, count)
  override fun valueOf(key: Int) = Dp(Float.fromBits(key))
}

internal class ColorHandle : Handle<Color, ULong>(AnimatedReferences.colors, AnimatedReferences.colorSlots) {
  override fun baseOf(target: Color) = target.value

  // A literal keeps its ARGB (bits 32–63) and colour space (bits 0–5); a reference keeps its name,
  // alpha and marker (bits 0–31). Neither reads the bits the count goes in (8–31 of a literal,
  // 32–63 of a reference); a target that already has any of them set is not tagged.
  override fun keyOf(target: Color, count: Int): ULong? = when {
    target == Color.Unspecified -> null
    target.isReference -> if ((target.value shr 32) != 0UL) null else target.value or (count.toULong() shl 32)
    count >= (1 shl 24) || (target.value and 0xFFFFFF00UL) != 0UL -> null
    else -> target.value or (count.toULong() shl 8)
  }
  override fun valueOf(key: ULong) = Color(key)
}

private val defaultFloatAnimation: SpringSpec<Float> = spring()
private val dpDefaultSpring: SpringSpec<Dp> = spring(visibilityThreshold = Dp.VisibilityThreshold)

@Composable
private fun <T, K> animatedReference(handle: Handle<T, K>, target: T, ref: (Int) -> AnimatedRef): State<T> {
  val tagged = handle.tag(target, ref(handle.id))
  return rememberUpdatedState(tagged)
}

/**
 * As `androidx.compose.animation.core.animateFloatAsState`, as an animated reference: the host runs
 * the animation where the value is used (a generated parameter), so a guest read gets the target.
 */
@Composable
public fun animateFloatAsState(
  targetValue: Float,
  animationSpec: AnimationSpec<Float> = defaultFloatAnimation,
  visibilityThreshold: Float = 0.01f,
  label: String = "FloatAnimation",
): State<Float> {
  val handle = remember { FloatHandle() }
  return animatedReference(handle, targetValue) { id ->
    AnimatedRef(
      id,
      if (animationSpec === defaultFloatAnimation) null else animationSpec.wire,
      visibilityThreshold.takeIf { it != 0.01f },
      label.takeIf { it != "FloatAnimation" },
    )
  }
}

/** As `androidx.compose.animation.core.animateDpAsState`, as an animated reference. */
@Composable
public fun animateDpAsState(
  targetValue: Dp,
  animationSpec: AnimationSpec<Dp> = dpDefaultSpring,
  label: String = "DpAnimation",
): State<Dp> {
  val handle = remember { DpHandle() }
  return animatedReference(handle, targetValue) { id ->
    AnimatedRef(id, if (animationSpec === dpDefaultSpring) null else animationSpec.wire, null, label.takeIf { it != "DpAnimation" })
  }
}

@Deprecated(
  "Keliver: 'finishedListener' runs when the host's animation ends, which is not reported to the guest yet (Gate B-e). " +
    "Leave it out, or animate on the guest's frame clock with Animatable in a LaunchedEffect.",
  level = DeprecationLevel.ERROR,
)
@Composable
public fun animateFloatAsState(
  targetValue: Float,
  animationSpec: AnimationSpec<Float> = defaultFloatAnimation,
  visibilityThreshold: Float = 0.01f,
  label: String = "FloatAnimation",
  finishedListener: ((Float) -> Unit)?,
): State<Float> = throw UnsupportedOperationException()

@Deprecated(
  "Keliver: 'finishedListener' runs when the host's animation ends, which is not reported to the guest yet (Gate B-e). " +
    "Leave it out, or animate on the guest's frame clock with Animatable in a LaunchedEffect.",
  level = DeprecationLevel.ERROR,
)
@Composable
public fun animateDpAsState(
  targetValue: Dp,
  animationSpec: AnimationSpec<Dp> = dpDefaultSpring,
  label: String = "DpAnimation",
  finishedListener: ((Dp) -> Unit)?,
): State<Dp> = throw UnsupportedOperationException()

/** Shared with `dev.keliver.compose.animation.animateColorAsState`. */
@KeliverGeneratedApi
@Composable
public fun animatedColorReference(targetValue: Color, animationSpec: AnimationSpec<Color>, isDefault: Boolean, label: String): State<Color> {
  val handle = remember { ColorHandle() }
  return animatedReference(handle, targetValue) { id ->
    AnimatedRef(id, if (isDefault) null else animationSpec.wire, null, label.takeIf { it != "ColorAnimation" })
  }
}
