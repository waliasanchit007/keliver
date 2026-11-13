/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.benchmarks

import android.content.Intent
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate B's cold start (prototype builds only; docs/GATE_B_RESULTS.md): `GatebActivity` in `host`
 * mode (the guest's screen, loaded from the bundle in the APK's assets) against `native-host` (the
 * same screen compiled into the app). `timeToInitialDisplayMs` is the activity's first frame (an
 * empty surface for the guest); `timeToFullDisplayMs` is the frame after `reportFullyDrawn`, which
 * the route calls on its first composition (native) or once the guest's first change batch has been
 * applied (guest): the Android counterpart of the iOS first-frame comparison.
 */
@RunWith(AndroidJUnit4::class)
class GatebStartupBenchmark {
  @get:Rule
  val rule = MacrobenchmarkRule()

  @Test fun host() = start("host")

  @Test fun nativeHost() = start("native-host")

  private fun start(mode: String) {
    GatebPhone.recordAndCheck("startup $mode")
    rule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = listOf(StartupTimingMetric()),
      iterations = ITERATIONS,
      startupMode = StartupMode.COLD,
      setupBlock = {
        device.wakeUp()
        pressHome()
      },
    ) {
      startActivityAndWait(Intent().setClassName(TARGET_PACKAGE, "dev.keliver.sample.host.gateb.GatebActivity").putExtra("mode", mode))
      val label = "mode: " + mode.removePrefix("native-")
      check(device.wait(Until.hasObject(By.text(label)), 20_000)) { "the $mode route did not draw '$label'" }
    }
  }

  private companion object {
    const val TARGET_PACKAGE = "dev.keliver.sample"
    const val ITERATIONS = 10
  }
}
