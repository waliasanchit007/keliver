/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.benchmarks

import android.content.Intent
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate B-e (prototype builds only; docs/GATE_B_RESULTS.md): frame timing of the same animation
 * screen in four forms, each running for a fixed time.
 *
 * - `host`: the guest's screen, `animate*AsState` as animated references (the host animates);
 * - `guest`: the guest's screen, `Animatable` on the guest's frame clock (one change per frame);
 * - `nativeHost` / `nativeGuest`: the same file compiled as native Compose in the host.
 *
 * The screen animates 12 boxes (alpha, size, colour) back and forth every 700 ms, plus an
 * `AnimatedVisibility` and an `animateContentSize`. The setup block opens the route and waits until
 * the guest has drawn (`mode: …`); the measured block then lets it animate for [RUN_MILLIS].
 * FrameTimingMetric reports frame duration (CPU) percentiles and frame overrun; the jank count is
 * the number of frames with a positive overrun (computed from the JSON report).
 */
@RunWith(AndroidJUnit4::class)
class GatebAnimationBenchmark {
  @get:Rule
  val rule = MacrobenchmarkRule()

  @Test fun host() = run("host")

  @Test fun guest() = run("guest")

  @Test fun nativeHost() = run("native-host")

  @Test fun nativeGuest() = run("native-guest")

  private fun run(mode: String) {
    GatebPhone.recordAndCheck("animation $mode")
    measure(mode)
  }

  @OptIn(ExperimentalMetricApi::class)
  private fun measure(mode: String) = rule.measureRepeated(
    packageName = TARGET_PACKAGE,
    metrics = listOf(FrameTimingMetric()),
    iterations = ITERATIONS,
    startupMode = null,
    setupBlock = {
      killProcess()
      startActivityAndWait(
        Intent().setClassName(TARGET_PACKAGE, "dev.keliver.sample.host.gateb.GatebActivity").putExtra("mode", mode),
      )
      val label = "mode: " + mode.removePrefix("native-")
      check(device.wait(Until.hasObject(By.text(label)), 20_000)) { "the $mode route did not draw '$label'" }
      // Let the first composition and the first animation period settle.
      Thread.sleep(1_000)
    },
  ) {
    Thread.sleep(RUN_MILLIS)
  }

  private companion object {
    const val TARGET_PACKAGE = "dev.keliver.sample"
    const val ITERATIONS = 5
    const val RUN_MILLIS = 5_000L
  }
}
