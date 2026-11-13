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
@file:Suppress("ktlint:standard:property-naming")
@file:OptIn(KeliverGeneratedApi::class)

package dev.keliver.compose.animation.core

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.keliver.compose.ui.unit.Dp
import dev.keliver.compose.ui.unit.IntOffset
import dev.keliver.compose.ui.unit.IntSize
import dev.keliver.compose.wire.JsonCodec
import dev.keliver.compose.wire.KeliverGeneratedApi
import dev.keliver.compose.wire.toCompact
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * Gate B-e (docs/COMPOSE_GENERATOR_DESIGN.md §10): the guest mirrors of Compose's finite animation
 * specs and easings, written by hand like the other value codecs (§6). A spec travels as a short
 * JSON value the host turns into the real spec; the guest also runs them itself, for `Animatable`
 * on its own frame clock. Not mirrored: `keyframes`, `repeatable`, `infiniteRepeatable` and custom
 * `Easing` lambdas (a custom easing fails when it is sent, not at compile time).
 */

/** The guest mirror of `androidx.compose.animation.core.Easing`. */
@Stable
public fun interface Easing {
  public fun transform(fraction: Float): Float
}

/**
 * The guest mirror of `androidx.compose.animation.core.CubicBezierEasing`. Travels as `[a,b,c,d]`;
 * the guest's own curve (for `Animatable`) solves for the parameter numerically, so a guest-side
 * frame can differ from Compose's by a rounding step.
 */
@Immutable
public class CubicBezierEasing(
  private val a: Float,
  private val b: Float,
  private val c: Float,
  private val d: Float,
) : Easing {
  internal val points: List<Float> get() = listOf(a, b, c, d)

  private fun bezier(p1: Float, p2: Float, t: Float): Float {
    val u = 1f - t
    return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
  }

  override fun transform(fraction: Float): Float {
    if (fraction <= 0f) return 0f
    if (fraction >= 1f) return 1f
    // Bisection on x(t) = fraction; x is monotonic for control points in [0, 1].
    var lo = 0f
    var hi = 1f
    var t = fraction
    repeat(32) {
      val x = bezier(a, c, t)
      if (abs(x - fraction) < 1e-6f) return bezier(b, d, t)
      if (x < fraction) lo = t else hi = t
      t = (lo + hi) / 2f
    }
    return bezier(b, d, t)
  }

  override fun equals(other: Any?): Boolean = other is CubicBezierEasing && points == other.points
  override fun hashCode(): Int = points.hashCode()
  override fun toString(): String = "CubicBezierEasing($a, $b, $c, $d)"
}

@Stable public val FastOutSlowInEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

@Stable public val LinearOutSlowInEasing: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

@Stable public val FastOutLinearInEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

@Stable public val LinearEasing: Easing = LinearEasingImpl

private object LinearEasingImpl : Easing {
  override fun transform(fraction: Float): Float = fraction
  override fun toString(): String = "LinearEasing"
}

/** As `androidx.compose.animation.core.AnimationConstants`. */
public object AnimationConstants {
  public const val DefaultDurationMillis: Int = 300
  public const val UnspecifiedTime: Long = Long.MIN_VALUE
}

/** As `androidx.compose.animation.core.Spring`: the same constants. */
public object Spring {
  public const val StiffnessHigh: Float = 10_000f
  public const val StiffnessMedium: Float = 1500f
  public const val StiffnessMediumLow: Float = 400f
  public const val StiffnessLow: Float = 200f
  public const val StiffnessVeryLow: Float = 50f
  public const val DampingRatioHighBouncy: Float = 0.2f
  public const val DampingRatioMediumBouncy: Float = 0.5f
  public const val DampingRatioLowBouncy: Float = 0.75f
  public const val DampingRatioNoBouncy: Float = 1f
  public const val DefaultDisplacementThreshold: Float = 0.01f
}

/** The guest mirror of `androidx.compose.animation.core.AnimationSpec`. */
public interface AnimationSpec<T>

/** The guest mirror of `androidx.compose.animation.core.FiniteAnimationSpec`. */
public interface FiniteAnimationSpec<T> : AnimationSpec<T>

/** The guest mirror of `androidx.compose.animation.core.DurationBasedAnimationSpec`. */
public interface DurationBasedAnimationSpec<T> : FiniteAnimationSpec<T>

@Immutable
public class TweenSpec<T>(
  public val durationMillis: Int = AnimationConstants.DefaultDurationMillis,
  public val delay: Int = 0,
  public val easing: Easing = FastOutSlowInEasing,
) : DurationBasedAnimationSpec<T> {
  override fun equals(other: Any?): Boolean =
    other is TweenSpec<*> && other.durationMillis == durationMillis && other.delay == delay && other.easing == easing
  override fun hashCode(): Int = (durationMillis * 31 + easing.hashCode()) * 31 + delay
}

@Immutable
public class SpringSpec<T>(
  public val dampingRatio: Float = Spring.DampingRatioNoBouncy,
  public val stiffness: Float = Spring.StiffnessMedium,
  public val visibilityThreshold: T? = null,
) : FiniteAnimationSpec<T> {
  override fun equals(other: Any?): Boolean =
    other is SpringSpec<*> && other.dampingRatio == dampingRatio && other.stiffness == stiffness &&
      other.visibilityThreshold == visibilityThreshold
  override fun hashCode(): Int = ((visibilityThreshold?.hashCode() ?: 0) * 31 + dampingRatio.hashCode()) * 31 + stiffness.hashCode()
}

@Immutable
public class SnapSpec<T>(public val delay: Int = 0) : DurationBasedAnimationSpec<T> {
  override fun equals(other: Any?): Boolean = other is SnapSpec<*> && other.delay == delay
  override fun hashCode(): Int = delay
}

@Stable
public fun <T> tween(
  durationMillis: Int = AnimationConstants.DefaultDurationMillis,
  delayMillis: Int = 0,
  easing: Easing = FastOutSlowInEasing,
): TweenSpec<T> = TweenSpec(durationMillis, delayMillis, easing)

@Stable
public fun <T> spring(
  dampingRatio: Float = Spring.DampingRatioNoBouncy,
  stiffness: Float = Spring.StiffnessMedium,
  visibilityThreshold: T? = null,
): SpringSpec<T> = SpringSpec(dampingRatio, stiffness, visibilityThreshold)

@Stable
public fun <T> snap(delayMillis: Int = 0): SnapSpec<T> = SnapSpec(delayMillis)

/** As `androidx.compose.animation.core.VisibilityThreshold` for [IntSize]. */
public val IntSize.Companion.VisibilityThreshold: IntSize get() = IntSize(1, 1)

/** As `androidx.compose.animation.core.VisibilityThreshold` for [IntOffset]. */
public val IntOffset.Companion.VisibilityThreshold: IntOffset get() = IntOffset(1, 1)

/** As `androidx.compose.animation.core.VisibilityThreshold` for [Dp]. */
public val Dp.Companion.VisibilityThreshold: Dp get() = Dp(0.1f)

/**
 * What a spec sends: `{"tween":[duration,delay,easing]}`, `{"spring":[damping,stiffness],"vt":…}`
 * or `{"snap":delay}`. An easing is `"linear"` or `[a,b,c,d]`; a visibility threshold is a number
 * (Float, Dp) or `[x,y]` (IntSize, IntOffset).
 */
@Immutable
@Serializable(with = AnimationSpecWireCodec::class)
public class AnimationSpecWire(public val json: JsonElement) {
  override fun equals(other: Any?): Boolean = other is AnimationSpecWire && other.json == json
  override fun hashCode(): Int = json.hashCode()
  override fun toString(): String = json.toString()
}

public object AnimationSpecWireCodec : JsonCodec<AnimationSpecWire>("dev.keliver.compose.AnimationSpec") {
  override fun toJson(value: AnimationSpecWire): JsonElement = value.json
  override fun fromJson(json: JsonElement): AnimationSpecWire = AnimationSpecWire(json)
}

/** The spec as it travels. */
public val AnimationSpec<*>.wire: AnimationSpecWire get() = AnimationSpecWire(specJson(this))

/**
 * What the guest's animation mirrors could not send as written (a custom Easing): reported once
 * per message, and the screen keeps running with a fallback, never a crash in composition.
 */
@KeliverGeneratedApi
public object AnimationMismatches {
  public var sink: (String) -> Unit = { println("keliver-compose: $it") }
  private val seen = mutableSetOf<String>()

  public fun report(message: String) {
    if (seen.add(message)) sink(message)
  }

  /** For tests: forget what was reported. */
  public fun reset(): Unit = seen.clear()
}

private fun easingJson(easing: Easing): JsonElement = when (easing) {
  LinearEasing -> JsonPrimitive("linear")
  is CubicBezierEasing -> JsonArray(easing.points.map { JsonPrimitive(it.toCompact()) })
  else -> {
    // Easing is a fun interface, so a lambda compiles; it runs per frame and cannot travel.
    AnimationMismatches.report(
      "a custom Easing runs per frame and cannot be sent to the host; FastOutSlowInEasing (tween's default) instead. " +
        "Use CubicBezierEasing or a named easing.",
    )
    easingJson(FastOutSlowInEasing)
  }
}

private fun thresholdJson(value: Any?): JsonElement? = when (value) {
  null -> null
  is Float -> JsonPrimitive(value.toCompact())
  is Dp -> JsonPrimitive(value.value.toCompact())
  is IntSize -> JsonArray(listOf(JsonPrimitive(value.width), JsonPrimitive(value.height)))
  is IntOffset -> JsonArray(listOf(JsonPrimitive(value.x), JsonPrimitive(value.y)))
  else -> null
}

private fun specJson(spec: AnimationSpec<*>): JsonElement = when (spec) {
  is TweenSpec<*> -> buildJsonObject {
    put("tween", JsonArray(listOf(JsonPrimitive(spec.durationMillis), JsonPrimitive(spec.delay), easingJson(spec.easing))))
  }
  is SpringSpec<*> -> buildJsonObject {
    put("spring", JsonArray(listOf(JsonPrimitive(spec.dampingRatio.toCompact()), JsonPrimitive(spec.stiffness.toCompact()))))
    thresholdJson(spec.visibilityThreshold)?.let { put("vt", it) }
  }
  is SnapSpec<*> -> buildJsonObject { put("snap", spec.delay) }
  else -> throw IllegalArgumentException("Keliver: ${spec::class.simpleName} is not in the generated API (Gate B-e mirrors tween, spring and snap).")
}

/** The parts of a spec's wire form, decoded (the host builds the real spec from them). */
public sealed interface DecodedSpec {
  public class Tween(public val durationMillis: Int, public val delay: Int, public val easing: List<Float>?) : DecodedSpec

  public class Spring(public val dampingRatio: Float, public val stiffness: Float, public val threshold: JsonElement?) : DecodedSpec

  public class Snap(public val delay: Int) : DecodedSpec
}

public fun AnimationSpecWire.decode(): DecodedSpec {
  val o = json as JsonObject
  o["tween"]?.jsonArray?.let { a ->
    val e = a[2]
    return DecodedSpec.Tween(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int, if (e is JsonArray) e.map { it.jsonPrimitive.float } else null)
  }
  o["spring"]?.jsonArray?.let { a -> return DecodedSpec.Spring(a[0].jsonPrimitive.float, a[1].jsonPrimitive.float, o["vt"]) }
  o["snap"]?.let { return DecodedSpec.Snap(it.jsonPrimitive.int) }
  error("unknown animation spec $json")
}
