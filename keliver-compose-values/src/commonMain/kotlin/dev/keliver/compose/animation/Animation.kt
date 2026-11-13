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

package dev.keliver.compose.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import dev.keliver.compose.animation.core.Animatable
import dev.keliver.compose.animation.core.AnimationSpec
import dev.keliver.compose.animation.core.FiniteAnimationSpec
import dev.keliver.compose.animation.core.Spring
import dev.keliver.compose.animation.core.SpringSpec
import dev.keliver.compose.animation.core.TwoWayConverter
import dev.keliver.compose.animation.core.VisibilityThreshold
import dev.keliver.compose.animation.core.animatedColorReference
import dev.keliver.compose.animation.core.spring
import dev.keliver.compose.animation.core.wire
import dev.keliver.compose.ui.Alignment
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.unit.IntOffset
import dev.keliver.compose.ui.unit.IntSize
import dev.keliver.compose.wire.JsonCodec
import dev.keliver.compose.wire.KeliverGeneratedApi
import dev.keliver.compose.wire.toCompact
import kotlin.math.cbrt
import kotlin.math.pow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

private val colorDefaultSpring: SpringSpec<Color> = spring()

/** As `androidx.compose.animation.animateColorAsState`, as an animated reference (see AnimatedReferences.kt). */
@Composable
public fun animateColorAsState(
  targetValue: Color,
  animationSpec: AnimationSpec<Color> = colorDefaultSpring,
  label: String = "ColorAnimation",
): State<Color> = animatedColorReference(targetValue, animationSpec, animationSpec === colorDefaultSpring, label)

@Deprecated(
  "Keliver: 'finishedListener' runs when the host's animation ends, which is not reported to the guest yet (Gate B-e). " +
    "Leave it out, or animate on the guest's frame clock with Animatable in a LaunchedEffect.",
  level = DeprecationLevel.ERROR,
)
@Composable
public fun animateColorAsState(
  targetValue: Color,
  animationSpec: AnimationSpec<Color> = colorDefaultSpring,
  label: String = "ColorAnimation",
  finishedListener: ((Color) -> Unit)?,
): State<Color> = throw UnsupportedOperationException()

/** As `androidx.compose.animation.Animatable(Color)`: animates in Oklab, as Compose's does. */
public fun Animatable(initialValue: Color): Animatable<Color, dev.keliver.compose.animation.core.AnimationVector4D> =
  Animatable(initialValue, Color.VectorConverter)

/** As `androidx.compose.animation.VectorConverter` for [Color]: Oklab with alpha, as Compose converts. */
public val Color.Companion.VectorConverter: TwoWayConverter<Color, dev.keliver.compose.animation.core.AnimationVector4D>
  get() = OklabConverter

private object OklabConverter : TwoWayConverter<Color, dev.keliver.compose.animation.core.AnimationVector4D> {
  override val convertToVector: (Color) -> dev.keliver.compose.animation.core.AnimationVector4D = { c ->
    require(!c.isReference) { "Keliver: a theme colour is resolved on the host; animate it with animateColorAsState (an animated reference)." }
    val (l, a, b) = srgbToOklab(c.red, c.green, c.blue)
    dev.keliver.compose.animation.core.AnimationVector4D(c.alpha, l, a, b)
  }
  override val convertFromVector: (dev.keliver.compose.animation.core.AnimationVector4D) -> Color = { v ->
    val (r, g, b) = oklabToSrgb(v.v2, v.v3, v.v4)
    Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), v.v1.coerceIn(0f, 1f))
  }
}

private fun toLinear(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
private fun fromLinear(c: Double): Float = (if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055).toFloat()

private fun srgbToOklab(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
  val lr = toLinear(r)
  val lg = toLinear(g)
  val lb = toLinear(b)
  val l = cbrt(0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb)
  val m = cbrt(0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb)
  val s = cbrt(0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb)
  return Triple(
    (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s).toFloat(),
    (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s).toFloat(),
    (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s).toFloat(),
  )
}

private fun oklabToSrgb(lab: Float, a: Float, b: Float): Triple<Float, Float, Float> {
  val l0 = lab + 0.3963377774 * a + 0.2158037573 * b
  val m0 = lab - 0.1055613458 * a - 0.0638541728 * b
  val s0 = lab - 0.0894841775 * a - 1.2914855480 * b
  val l = l0 * l0 * l0
  val m = m0 * m0 * m0
  val s = s0 * s0 * s0
  return Triple(
    fromLinear(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
    fromLinear(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
    fromLinear(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
  )
}

// -------------------------------------------------------------------------------------------------
// Enter and exit transitions (AnimatedVisibility's `enter` and `exit`): host-side, sent once.

/**
 * The guest mirror of `androidx.compose.animation.EnterTransition`: the parts it was built from, in
 * order (`fadeIn() + expandVertically()`), each as it travels. The host combines the real ones
 * with `+`. Wire: `[{"fadeIn":{…}}, …]`.
 */
@Immutable
@Serializable(with = EnterTransitionCodec::class)
public class EnterTransition internal constructor(@property:KeliverGeneratedApi public val parts: List<JsonElement>) {
  public operator fun plus(enter: EnterTransition): EnterTransition = EnterTransition(parts + enter.parts)
  override fun equals(other: Any?): Boolean = other is EnterTransition && other.parts == parts
  override fun hashCode(): Int = parts.hashCode()
  override fun toString(): String = "EnterTransition($parts)"

  public companion object {
    public val None: EnterTransition = EnterTransition(emptyList())
  }
}

/** The guest mirror of `androidx.compose.animation.ExitTransition` (see [EnterTransition]). */
@Immutable
@Serializable(with = ExitTransitionCodec::class)
public class ExitTransition internal constructor(@property:KeliverGeneratedApi public val parts: List<JsonElement>) {
  public operator fun plus(exit: ExitTransition): ExitTransition = ExitTransition(parts + exit.parts)
  override fun equals(other: Any?): Boolean = other is ExitTransition && other.parts == parts
  override fun hashCode(): Int = parts.hashCode()
  override fun toString(): String = "ExitTransition($parts)"

  public companion object {
    public val None: ExitTransition = ExitTransition(emptyList())
  }
}

public object EnterTransitionCodec : JsonCodec<EnterTransition>("dev.keliver.compose.EnterTransition") {
  override fun toJson(value: EnterTransition): JsonElement = JsonArray(value.parts)
  override fun fromJson(json: JsonElement): EnterTransition = EnterTransition(json.jsonArray.toList())
}

public object ExitTransitionCodec : JsonCodec<ExitTransition>("dev.keliver.compose.ExitTransition") {
  override fun toJson(value: ExitTransition): JsonElement = JsonArray(value.parts)
  override fun fromJson(json: JsonElement): ExitTransition = ExitTransition(json.jsonArray.toList())
}

private fun part(name: String, spec: FiniteAnimationSpec<*>, extra: Map<String, JsonElement> = emptyMap()): JsonElement =
  buildJsonObject {
    put(
      name,
      buildJsonObject {
        put("spec", spec.wire.json)
        extra.forEach { (k, v) -> put(k, v) }
      },
    )
  }

private fun bias(a: Alignment): JsonElement = JsonArray(listOf(JsonPrimitive(a.horizontalBias.toCompact()), JsonPrimitive(a.verticalBias.toCompact())))

private val sizeSpring: FiniteAnimationSpec<IntSize> =
  spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold)
private val offsetSpring: FiniteAnimationSpec<IntOffset> =
  spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)

/** As `androidx.compose.animation.fadeIn`. */
@Stable
public fun fadeIn(animationSpec: FiniteAnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow), initialAlpha: Float = 0f): EnterTransition =
  EnterTransition(listOf(part("fadeIn", animationSpec, mapOf("alpha" to JsonPrimitive(initialAlpha.toCompact())))))

/** As `androidx.compose.animation.fadeOut`. */
@Stable
public fun fadeOut(animationSpec: FiniteAnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow), targetAlpha: Float = 0f): ExitTransition =
  ExitTransition(listOf(part("fadeOut", animationSpec, mapOf("alpha" to JsonPrimitive(targetAlpha.toCompact())))))

/** As `androidx.compose.animation.scaleIn` (its `transformOrigin` is the default, the centre). */
@Stable
public fun scaleIn(animationSpec: FiniteAnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow), initialScale: Float = 0f): EnterTransition =
  EnterTransition(listOf(part("scaleIn", animationSpec, mapOf("scale" to JsonPrimitive(initialScale.toCompact())))))

/** As `androidx.compose.animation.scaleOut` (its `transformOrigin` is the default, the centre). */
@Stable
public fun scaleOut(animationSpec: FiniteAnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow), targetScale: Float = 0f): ExitTransition =
  ExitTransition(listOf(part("scaleOut", animationSpec, mapOf("scale" to JsonPrimitive(targetScale.toCompact())))))

/** As `androidx.compose.animation.expandIn` (its `initialSize` lambda is the default, zero). */
@Stable
public fun expandIn(animationSpec: FiniteAnimationSpec<IntSize> = sizeSpring, expandFrom: Alignment = Alignment.BottomEnd, clip: Boolean = true): EnterTransition =
  EnterTransition(listOf(part("expandIn", animationSpec, mapOf("from" to bias(expandFrom), "clip" to JsonPrimitive(clip)))))

/** As `androidx.compose.animation.shrinkOut` (its `targetSize` lambda is the default, zero). */
@Stable
public fun shrinkOut(animationSpec: FiniteAnimationSpec<IntSize> = sizeSpring, shrinkTowards: Alignment = Alignment.BottomEnd, clip: Boolean = true): ExitTransition =
  ExitTransition(listOf(part("shrinkOut", animationSpec, mapOf("to" to bias(shrinkTowards), "clip" to JsonPrimitive(clip)))))

/** As `androidx.compose.animation.expandVertically` (its `initialHeight` lambda is the default, zero). */
@Stable
public fun expandVertically(animationSpec: FiniteAnimationSpec<IntSize> = sizeSpring, expandFrom: Alignment.Vertical = Alignment.Bottom, clip: Boolean = true): EnterTransition =
  EnterTransition(listOf(part("expandVertically", animationSpec, mapOf("from" to JsonPrimitive(expandFrom.bias.toCompact()), "clip" to JsonPrimitive(clip)))))

/** As `androidx.compose.animation.shrinkVertically` (its `targetHeight` lambda is the default, zero). */
@Stable
public fun shrinkVertically(animationSpec: FiniteAnimationSpec<IntSize> = sizeSpring, shrinkTowards: Alignment.Vertical = Alignment.Bottom, clip: Boolean = true): ExitTransition =
  ExitTransition(listOf(part("shrinkVertically", animationSpec, mapOf("to" to JsonPrimitive(shrinkTowards.bias.toCompact()), "clip" to JsonPrimitive(clip)))))

/** As `androidx.compose.animation.expandHorizontally` (its `initialWidth` lambda is the default, zero). */
@Stable
public fun expandHorizontally(animationSpec: FiniteAnimationSpec<IntSize> = sizeSpring, expandFrom: Alignment.Horizontal = Alignment.End, clip: Boolean = true): EnterTransition =
  EnterTransition(listOf(part("expandHorizontally", animationSpec, mapOf("from" to JsonPrimitive(expandFrom.bias.toCompact()), "clip" to JsonPrimitive(clip)))))

/** As `androidx.compose.animation.shrinkHorizontally` (its `targetWidth` lambda is the default, zero). */
@Stable
public fun shrinkHorizontally(animationSpec: FiniteAnimationSpec<IntSize> = sizeSpring, shrinkTowards: Alignment.Horizontal = Alignment.End, clip: Boolean = true): ExitTransition =
  ExitTransition(listOf(part("shrinkHorizontally", animationSpec, mapOf("to" to JsonPrimitive(shrinkTowards.bias.toCompact()), "clip" to JsonPrimitive(clip)))))

/** As `androidx.compose.animation.slideInVertically` (its `initialOffsetY` lambda is the default, `-height / 2`). */
@Stable
public fun slideInVertically(animationSpec: FiniteAnimationSpec<IntOffset> = offsetSpring): EnterTransition =
  EnterTransition(listOf(part("slideInVertically", animationSpec)))

/** As `androidx.compose.animation.slideOutVertically` (its `targetOffsetY` lambda is the default, `-height / 2`). */
@Stable
public fun slideOutVertically(animationSpec: FiniteAnimationSpec<IntOffset> = offsetSpring): ExitTransition =
  ExitTransition(listOf(part("slideOutVertically", animationSpec)))
