/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.realguest

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/*
 * animation-core on the guest's frame clock: Animatable.animateTo in a coroutine whose context has a
 * BroadcastFrameClock (what keliver-compose's guest clock is), stepped 16 ms per frame.
 */

/** A 2-float value with its own converter: what Offset.VectorConverter is, without ui-geometry. */
private data class XY(val x: Float, val y: Float)
private val XYConverter: TwoWayConverter<XY, AnimationVector2D> =
  TwoWayConverter({ AnimationVector2D(it.x, it.y) }, { XY(it.v1, it.v2) })

fun animCases(): List<Case> = listOf(
  Case("anim.tween") {
    val f = Frames()
    val a = Animatable(0f)
    val values = ArrayList<Float>()
    f.launch { a.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) { values += value } }
    while (a.isRunning && f.frames < 100) f.frame()
    "frames=${f.frames} values=${values.size} last=${a.value} mid=${values.getOrNull(values.size / 2)} running=${a.isRunning}"
  },
  Case("anim.spring") {
    val f = Frames()
    val a = Animatable(0f)
    var n = 0
    f.launch { a.animateTo(100f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) { n++ } }
    var peak = 0f
    while (a.isRunning && f.frames < 1000) {
      f.frame()
      if (a.value > peak) peak = a.value
    }
    "frames=${f.frames} values=$n last=${a.value} overshoot=${peak > 100f}"
  },
  Case("anim.keyframesVector") {
    val f = Frames()
    val a = Animatable(XY(0f, 0f), XYConverter)
    f.launch {
      a.animateTo(XY(100f, 50f), keyframes { durationMillis = 400; XY(80f, 10f) at 100 })
    }
    while (a.isRunning && f.frames < 100) f.frame()
    "frames=${f.frames} last=${a.value} velocity=${a.velocity}"
  },
  // Per-frame cost of a running animation: an infinite repeat stepped n frames.
  Case("anim.infiniteFrames", bench = { n ->
    val f = Frames()
    val a = Animatable(0f)
    f.launch { a.animateTo(1f, infiniteRepeatable(tween(500), RepeatMode.Reverse)) }
    repeat(n) { f.frame() }
    f.job.cancel()
    f.dispatcher.drain()
    a.value
  }) {
    val f = Frames()
    val a = Animatable(0f)
    f.launch { a.animateTo(1f, infiniteRepeatable(tween(500), RepeatMode.Reverse)) }
    repeat(60) { f.frame() }
    val v = a.value
    f.job.cancel()
    f.dispatcher.drain()
    "after60=$v cancelledRunning=${a.isRunning}"
  },
  // The same driver and spec as the mirror's bench (MirrorCases.kt): a long tween, n frames.
  Case("anim.longTweenFrames", bench = { n ->
    val f = Frames()
    val a = Animatable(0f)
    f.launch { a.animateTo(1f, tween(1_000_000)) }
    repeat(n) { f.frame() }
    f.job.cancel()
    f.dispatcher.drain()
    a.value
  }) { "ok" },
  // The value math alone, without coroutines: what a host-independent evaluator would call.
  Case("anim.targetBasedMath", bench = { n ->
    val anim = TargetBasedAnimation(spring<Float>(), Float.VectorConverter, 0f, 1f)
    var x = 0f
    repeat(n) { x += anim.getValueFromNanos((it % 60) * 16_000_000L) }
    x
  }) {
    val anim = TargetBasedAnimation(tween<Float>(300), Float.VectorConverter, 0f, 1f)
    "duration=${anim.durationNanos} at150=${anim.getValueFromNanos(150_000_000L)}"
  },
)
