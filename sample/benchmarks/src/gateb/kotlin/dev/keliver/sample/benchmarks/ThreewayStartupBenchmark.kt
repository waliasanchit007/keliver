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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The three-way benchmark's cold start (prototype builds only; docs/THREE_WAY_BENCHMARK.md):
 * `ThreewayActivity` as native Compose, a Keliver guest and a web page in a WebView, each showing the
 * list of 20 items from the fixture API (the Mac's, through `adb reverse`, with a fixed delay).
 * `timeToFullDisplayMs` ends at the frame the route reports fully drawn in: the first draw after
 * the list's data is in the form's tree.
 */
@RunWith(AndroidJUnit4::class)
class ThreewayStartupBenchmark {
  @get:Rule
  val rule = MacrobenchmarkRule()

  @Test fun native() = start("native")

  @Test fun guest() = start("guest")

  @Test fun web() = start("web")

  /**
   * Instrumentation argument `localApi=true`: the app serves the fixture itself (LocalFixture; no
   * Mac and no `adb reverse`), as the in-app series do since the second session (NF0's cold start).
   */
  private val localApi: Boolean =
    InstrumentationRegistry.getArguments().getString("localApi").toBoolean()

  private fun start(form: String) {
    GatebPhone.recordAndCheck("threeway startup $form localApi=$localApi")
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
      startActivityAndWait(
        Intent().setClassName(TARGET_PACKAGE, "dev.keliver.sample.host.threeway.ThreewayActivity")
          .putExtra("form", form)
          .putExtra("overLockScreen", true)
          .putExtra("localApi", localApi),
      )
      // The list's first row (the WebView's text is visible to UiAutomator through accessibility).
      // Not part of the metric: if it is not found, the trace still has the report.
      device.wait(Until.hasObject(By.textContains("Order #10001")), 20_000)
      // Keep tracing past the report (see GatebStartupBenchmark).
      Thread.sleep(1_000)
    }
  }

  private companion object {
    const val TARGET_PACKAGE = "dev.keliver.sample"
    const val ITERATIONS = 10
  }
}
