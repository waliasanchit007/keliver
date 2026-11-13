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
package dev.keliver.compose.animation.core

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.keliver.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope

/*
 * The guest-side alternative to animated references (docs/COMPOSE_GENERATOR_DESIGN.md §10, Gate
 * B-e): `Animatable.animateTo` in a `LaunchedEffect` runs in the guest, on the frame clock the host
 * drives (`withFrameNanos`), and each frame's value is a new property or modifier value: one
 * change crosses the bridge per frame while it runs. Written by hand: tween (with the mirrored
 * easings), spring (the same closed form as Compose's SpringSimulation) and snap. Not mirrored:
 * decay, bounds, `animateDecay`, `MotionDurationScale`.
 */

/** The guest mirror of `androidx.compose.animation.core.AnimationVector`. */
public sealed class AnimationVector {
  internal abstract val size: Int
  internal abstract operator fun get(index: Int): Float
  internal abstract fun newVector(values: FloatArray): AnimationVector
}

public class AnimationVector1D(public val value: Float) : AnimationVector() {
  override val size: Int get() = 1
  override fun get(index: Int): Float = value
  override fun newVector(values: FloatArray): AnimationVector = AnimationVector1D(values[0])
}

public class AnimationVector2D(public val v1: Float, public val v2: Float) : AnimationVector() {
  override val size: Int get() = 2
  override fun get(index: Int): Float = if (index == 0) v1 else v2
  override fun newVector(values: FloatArray): AnimationVector = AnimationVector2D(values[0], values[1])
}

public class AnimationVector4D(public val v1: Float, public val v2: Float, public val v3: Float, public val v4: Float) : AnimationVector() {
  override val size: Int get() = 4
  override fun get(index: Int): Float = when (index) {
    0 -> v1
    1 -> v2
    2 -> v3
    else -> v4
  }
  override fun newVector(values: FloatArray): AnimationVector = AnimationVector4D(values[0], values[1], values[2], values[3])
}

/** The guest mirror of `androidx.compose.animation.core.TwoWayConverter`. */
public interface TwoWayConverter<T, V : AnimationVector> {
  public val convertToVector: (T) -> V
  public val convertFromVector: (V) -> T
}

public fun <T, V : AnimationVector> TwoWayConverter(convertToVector: (T) -> V, convertFromVector: (V) -> T): TwoWayConverter<T, V> =
  TwoWayConverterImpl(convertToVector, convertFromVector)

private class TwoWayConverterImpl<T, V : AnimationVector>(
  override val convertToVector: (T) -> V,
  override val convertFromVector: (V) -> T,
) : TwoWayConverter<T, V>

public val Float.Companion.VectorConverter: TwoWayConverter<Float, AnimationVector1D>
  get() = FloatConverter

public val Dp.Companion.VectorConverter: TwoWayConverter<Dp, AnimationVector1D>
  get() = DpConverter

private val FloatConverter = TwoWayConverter<Float, AnimationVector1D>({ AnimationVector1D(it) }, { it.value })
private val DpConverter = TwoWayConverter<Dp, AnimationVector1D>({ AnimationVector1D(it.value) }, { Dp(it.value) })

/** As Compose's: why an animation ended. */
public enum class AnimationEndReason { BoundReached, Finished }

/** As Compose's `AnimationResult`, without the end state's vectors. */
public class AnimationResult<T, V : AnimationVector>(public val endValue: T, public val endReason: AnimationEndReason) {
  override fun toString(): String = "AnimationResult(endReason=$endReason, endValue=$endValue)"
}

/** As `androidx.compose.animation.core.Animatable(Float)`. */
public fun Animatable(initialValue: Float, visibilityThreshold: Float = Spring.DefaultDisplacementThreshold): Animatable<Float, AnimationVector1D> =
  Animatable(initialValue, Float.VectorConverter, visibilityThreshold)

/**
 * The guest mirror of `androidx.compose.animation.core.Animatable`. [value] is snapshot state, so a
 * composable that reads it recomposes every frame the animation runs, and sends one change.
 */
@Stable
public class Animatable<T, V : AnimationVector>(
  initialValue: T,
  public val typeConverter: TwoWayConverter<T, V>,
  private val visibilityThreshold: T? = null,
  public val label: String = "Animatable",
) {
  public var value: T by mutableStateOf(initialValue)
    private set

  public var targetValue: T by mutableStateOf(initialValue)
    private set

  public var isRunning: Boolean by mutableStateOf(false)
    private set

  private var velocityVector: FloatArray = FloatArray(typeConverter.convertToVector(initialValue).size)

  public val velocity: T
    @Suppress("UNCHECKED_CAST")
    get() = typeConverter.convertFromVector(typeConverter.convertToVector(value).newVector(velocityVector) as V)

  private var running: Job? = null

  private val defaultSpringSpec: SpringSpec<T> = SpringSpec(visibilityThreshold = visibilityThreshold)

  public suspend fun snapTo(targetValue: T) {
    running?.cancel()
    this.targetValue = targetValue
    value = targetValue
    velocityVector = FloatArray(velocityVector.size)
  }

  public suspend fun stop() {
    running?.cancel()
  }

  public suspend fun animateTo(
    targetValue: T,
    animationSpec: AnimationSpec<T> = defaultSpringSpec,
    initialVelocity: T = velocity,
    block: (Animatable<T, V>.() -> Unit)? = null,
  ): AnimationResult<T, V> = coroutineScope {
    running?.cancel()
    val job = coroutineContext[Job]
    running = job
    this@Animatable.targetValue = targetValue
    val from = typeConverter.convertToVector(value)
    val to = typeConverter.convertToVector(targetValue)
    val v0 = typeConverter.convertToVector(initialVelocity)
    val threshold = visibilityThreshold?.let { typeConverter.convertToVector(it)[0] } ?: Spring.DefaultDisplacementThreshold
    val curve = curveOf(animationSpec, from, to, v0, threshold)
    isRunning = true
    try {
      var start = Long.MIN_VALUE
      while (true) {
        val done = withFrameNanos { now ->
          if (start == Long.MIN_VALUE) start = now
          val play = now - start
          val (values, velocities, finished) = curve(play)
          @Suppress("UNCHECKED_CAST")
          value = typeConverter.convertFromVector(from.newVector(values) as V)
          velocityVector = velocities
          block?.invoke(this@Animatable)
          finished
        }
        if (done) break
      }
      AnimationResult(value, AnimationEndReason.Finished)
    } finally {
      isRunning = false
      if (running === job) running = null
    }
  }
}

/** The position, velocity and whether it has finished, at a play time in nanoseconds. */
private typealias Curve = (Long) -> Triple<FloatArray, FloatArray, Boolean>

private fun curveOf(spec: AnimationSpec<*>, from: AnimationVector, to: AnimationVector, v0: AnimationVector, threshold: Float): Curve {
  val n = from.size
  return when (spec) {
    is TweenSpec<*> -> { play ->
      val t = (play / 1_000_000L - spec.delay).coerceAtLeast(0L)
      val fraction = if (spec.durationMillis == 0) 1f else (t.toFloat() / spec.durationMillis).coerceIn(0f, 1f)
      val eased = spec.easing.transform(fraction)
      val values = FloatArray(n) { from[it] + (to[it] - from[it]) * eased }
      Triple(values, FloatArray(n), play / 1_000_000L >= spec.delay + spec.durationMillis)
    }
    is SnapSpec<*> -> { play ->
      val done = play / 1_000_000L >= spec.delay
      Triple(FloatArray(n) { if (done) to[it] else from[it] }, FloatArray(n), done)
    }
    is SpringSpec<*> -> { play ->
      val seconds = play / 1_000_000_000.0
      val values = FloatArray(n)
      val velocities = FloatArray(n)
      var settled = true
      for (i in 0 until n) {
        val (x, v) = springAt(spec.dampingRatio.toDouble(), spec.stiffness.toDouble(), (from[i] - to[i]).toDouble(), v0[i].toDouble(), seconds)
        if (kotlin.math.abs(x) >= threshold || kotlin.math.abs(v) >= threshold) settled = false
        values[i] = (to[i] + x).toFloat()
        velocities[i] = v.toFloat()
      }
      if (settled) Triple(FloatArray(n) { to[it] }, FloatArray(n), true) else Triple(values, velocities, false)
    }
    else -> throw IllegalArgumentException("Keliver: ${spec::class.simpleName} is not mirrored for Animatable (tween, spring and snap are).")
  }
}

/** Compose's SpringSimulation closed form: displacement and velocity at [t] seconds. */
private fun springAt(damping: Double, stiffness: Double, x0: Double, v0: Double, t: Double): Pair<Double, Double> {
  val w = sqrt(stiffness)
  return when {
    damping > 1.0 -> {
      val gPlus = -damping * w + w * sqrt(damping * damping - 1)
      val gMinus = -damping * w - w * sqrt(damping * damping - 1)
      val b = (gMinus * x0 - v0) / (gMinus - gPlus)
      val a = x0 - b
      Pair(a * exp(gMinus * t) + b * exp(gPlus * t), a * gMinus * exp(gMinus * t) + b * gPlus * exp(gPlus * t))
    }
    damping == 1.0 -> {
      val a = x0
      val b = v0 + w * x0
      val e = exp(-w * t)
      Pair((a + b * t) * e, (a + b * t) * e * -w + b * e)
    }
    else -> {
      val wd = w * sqrt(1 - damping * damping)
      val c = x0
      val s = (1 / wd) * (damping * w * x0 + v0)
      val e = exp(-damping * w * t)
      val x = e * (c * cos(wd * t) + s * sin(wd * t))
      Pair(x, x * -w * damping + e * (-wd * c * sin(wd * t) + wd * s * cos(wd * t)))
    }
  }
}
