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
 * generated stubs can recognise it: the target's float bits plus a small count (a few ULPs away
 * from the target, so a guest read gets the target to within ~1e-6 relative), or, for a Color,
 * spare bits of its 64-bit packing that no read uses (a guest read gets the exact target). A
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

/** One `animate*AsState` call site's registration; removed when the call leaves the composition. */
private abstract class Handle<T, K>(private val table: HashMap<K, AnimatedReferences.Registration<T>>) : RememberObserver {
  val id: Int = AnimatedReferences.newId()
  private var key: K? = null
  private var tagged: T? = null

  abstract fun untaggable(target: T): Boolean
  abstract fun keyOf(target: T, count: Int): K
  abstract fun valueOf(key: K, target: T): T

  fun tag(target: T, ref: AnimatedRef): T {
    if (untaggable(target)) {
      release()
      return target
    }
    val current = key
    if (current != null) {
      val r = table[current]
      if (r != null && r.target == target && r.ref == ref) return tagged!!
      table.remove(current)
    }
    var count = 1
    var k = keyOf(target, count)
    while (table.containsKey(k)) k = keyOf(target, ++count)
    table[k] = AnimatedReferences.Registration(target, ref)
    key = k
    return valueOf(k, target).also { tagged = it }
  }

  private fun release() {
    key?.let { table.remove(it) }
    key = null
  }

  override fun onRemembered() {}
  override fun onForgotten() = release()
  override fun onAbandoned() = release()
}

private class FloatHandle : Handle<Float, Int>(AnimatedReferences.floats) {
  override fun untaggable(target: Float) = target.isNaN() || target.isInfinite()
  override fun keyOf(target: Float, count: Int) = target.toBits() + count
  override fun valueOf(key: Int, target: Float) = Float.fromBits(key)
}

private class DpHandle : Handle<Dp, Int>(AnimatedReferences.dps) {
  override fun untaggable(target: Dp) = target.value.isNaN() || target.value.isInfinite()
  override fun keyOf(target: Dp, count: Int) = target.value.toBits() + count
  override fun valueOf(key: Int, target: Dp) = Dp(Float.fromBits(key))
}

private class ColorHandle : Handle<Color, ULong>(AnimatedReferences.colors) {
  override fun untaggable(target: Color) = target == Color.Unspecified
  override fun keyOf(target: Color, count: Int): ULong =
    // A literal keeps its ARGB (bits 32–63) and colour space (bits 0–5); a reference keeps its
    // name, alpha and marker (bits 0–31). Neither reads the bits the count goes in.
    if (target.isReference) target.value or (count.toULong() shl 32) else target.value or (count.toULong() shl 8)
  override fun valueOf(key: ULong, target: Color) = Color(key)
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
