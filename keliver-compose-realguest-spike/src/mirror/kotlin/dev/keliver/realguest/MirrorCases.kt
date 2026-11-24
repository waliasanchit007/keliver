/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.realguest

import dev.keliver.compose.animation.core.Animatable
import dev.keliver.compose.animation.core.tween

/*
 * Today's hand-written guest mirror (keliver-compose-values, Gate B-e's guest-clock Animatable) on
 * the same frame driver and spec as anim.longTweenFrames: the per-frame cost to compare with the
 * real animation-core's.
 */
fun mirrorCases(): List<Case> = listOf(
  Case("mirror.longTweenFrames", bench = { n ->
    val f = Frames()
    val a = Animatable(0f)
    f.launch { a.animateTo(1f, tween(1_000_000)) }
    repeat(n) { f.frame() }
    f.job.cancel()
    f.dispatcher.drain()
    a.value
  }) {
    val f = Frames()
    val a = Animatable(0f)
    f.launch { a.animateTo(1f, tween(300)) }
    while (a.isRunning && f.frames < 100) f.frame()
    "frames=${f.frames} last=${a.value}"
  },
)
