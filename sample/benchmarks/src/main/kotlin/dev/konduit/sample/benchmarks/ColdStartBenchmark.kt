/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.konduit.sample.benchmarks

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 of the Konduit performance workstream — cold-start
 * latency measurement on the `:host-android` sample.
 *
 * **What this measures.** Wall time from a `pressHome` / cold
 * launch sequence until `MainActivity.onCreate` returns and the
 * first Compose composition pass completes. Captured via
 * Macrobenchmark's `StartupTimingMetric`, which decodes Android
 * system traces (`TraceSectionMetric` shape under the hood).
 *
 * **What this does NOT measure.** The bundle-fetch + QuickJS-load
 * latency that completes AFTER `MainActivity.onCreate` (Treehouse
 * downloads the manifest + modules asynchronously). For that, see
 * the queued `BundleLoadBenchmark` that watches the
 * `KonduitSample: codeLoadSuccess` log line via UiAutomator.
 *
 * **Reproducibility.** Macrobenchmark iterates 5 times by default
 * and reports median + P90 + P99. The numbers vary substantially
 * between device classes — published baselines should always
 * include the device + Android version they were captured on.
 *
 * **Target SLA (from `docs/PERFORMANCE.md`).** P50 ≤ 800 ms on a
 * Pixel 6 with a fresh Zipline cache, P95 ≤ 1500 ms. Numbers
 * outside that range should fail the build in CI once Phase 3
 * lands (per-release-tag regression gating).
 *
 * **Required device state.**
 * - `:host-android` installed in the `benchmark` build type
 *   (debuggable + profileable, R8-minified). The benchmark
 *   Gradle task handles install.
 * - A Zipline dev server (default
 *   `python3 -m http.server 8080`) reachable from the device at
 *   `http://10.0.2.2:8080/manifest.zipline.json`. Without it the
 *   cold-start metric still completes, but logcat will show
 *   `codeLoadFailed` and the sample will render the host's
 *   blank Surface.
 *
 * To run:
 *
 *   ./gradlew :benchmarks:connectedBenchmarkAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ColdStartBenchmark {
  @get:Rule
  val rule = MacrobenchmarkRule()

  /**
   * Standard cold-start measurement. The `setupBlock` runs once
   * per iteration before the measured block — `pressHome` clears
   * any prior task state. The `measureBlock` is what
   * `StartupTimingMetric` is observing; the `startActivityAndWait`
   * call ends when `MainActivity` has finished its first draw.
   */
  @Test
  fun coldStartup() = rule.measureRepeated(
    packageName = TARGET_PACKAGE,
    metrics = listOf(StartupTimingMetric()),
    iterations = 5,
    startupMode = StartupMode.COLD,
    setupBlock = { pressHome() },
  ) {
    startActivityAndWait()
  }

  /**
   * Warm-start measurement — process is already running, just
   * brought to the foreground. Tests the
   * "tab-switch-back-to-app" path that's separate from full
   * cold start.
   */
  @Test
  fun warmStartup() = rule.measureRepeated(
    packageName = TARGET_PACKAGE,
    metrics = listOf(StartupTimingMetric()),
    iterations = 5,
    startupMode = StartupMode.WARM,
    setupBlock = { pressHome() },
  ) {
    startActivityAndWait()
  }

  private companion object {
    const val TARGET_PACKAGE = "dev.konduit.sample"
  }
}
