/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.benchmarks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate B's bridge counter (prototype builds only; docs/GATE_B_RESULTS.md), per form: opens the
 * route, lets it settle, then for [WINDOW_MILLIS] reads the route's once-a-second bridge log (tag
 * `KeliverGateB`: change batches and changes the guest sent) and the host's frames over the same
 * window (`dumpsys gfxinfo`, reset at its start). Writes `gateb-bridge-<mode>.txt` to the
 * additional test output. Not a pass/fail test: it only fails when the route does not draw.
 *
 * The guest's new values per second is its batch rate (each batch is one guest frame carrying all
 * the boxes' changes), to compare with the host's frames per second.
 */
@RunWith(AndroidJUnit4::class)
class GatebBridgeCounter {
  private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

  @Test fun host() = count("host")

  @Test fun guest() = count("guest")

  @Test fun nativeHost() = count("native-host")

  @Test fun nativeGuest() = count("native-guest")

  /** The phone's state and which `dev.keliver` packages it has, with their first install times. */
  @Test fun deviceState() {
    GatebPhone.record("deviceState: ${GatebPhone.state()}")
    val packages = device.executeShellCommand("pm list packages dev.keliver").lines().filter { it.isNotBlank() }
    for (line in packages) {
      val name = line.removePrefix("package:").trim()
      val info = runCatching {
        InstrumentationRegistry.getInstrumentation().context.packageManager.getPackageInfo(name, 0)
      }.getOrNull()
      GatebPhone.record("package $name firstInstallTime=${info?.firstInstallTime} lastUpdateTime=${info?.lastUpdateTime}")
    }
  }

  private fun count(mode: String) {
    GatebPhone.recordAndCheck("bridge $mode")
    device.executeShellCommand("am force-stop $TARGET_PACKAGE")
    device.executeShellCommand("am start -W -n $TARGET_PACKAGE/$ACTIVITY --es mode $mode")
    val label = "mode: " + mode.removePrefix("native-")
    check(device.wait(Until.hasObject(By.text(label)), 20_000)) { "the $mode route did not draw '$label'" }
    Thread.sleep(2_000)

    device.executeShellCommand("dumpsys gfxinfo $TARGET_PACKAGE reset")
    val start = System.currentTimeMillis()
    Thread.sleep(WINDOW_MILLIS)
    val end = System.currentTimeMillis()
    val gfx = device.executeShellCommand("dumpsys gfxinfo $TARGET_PACKAGE")
    val since = "%d.%03d".format(start / 1000, start % 1000)
    val log = device.executeShellCommand("logcat -d -v epoch -T $since -s KeliverGateB:D")
    device.executeShellCommand("am force-stop $TARGET_PACKAGE")

    val frames = Regex("Total frames rendered: (\\d+)").find(gfx)?.groupValues?.get(1)?.toInt()
    val janky = Regex("Janky frames: (\\d+)").find(gfx)?.groupValues?.get(1)
    val readings = Regex("bridge: ([\\d.]+) batches/s, ([\\d.]+) changes/s").findAll(log)
      .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
    val seconds = (end - start) / 1000.0
    val summary = buildString {
      append("mode=$mode window=${seconds}s hostFrames=$frames hostFps=${frames?.let { "%.1f".format(it / seconds) }} janky=$janky ")
      append("readings=${readings.size} ")
      if (readings.isNotEmpty()) {
        val batches = readings.map { it.first }.sorted()
        val changes = readings.map { it.second }.sorted()
        append("batches/s median=${batches[batches.size / 2]} min=${batches.first()} max=${batches.last()} ")
        append("changes/s median=${changes[changes.size / 2]} min=${changes.first()} max=${changes.last()}")
      }
    }
    GatebPhone.record("bridge $mode result: $summary")
    GatebPhone.output("gateb-bridge-$mode.txt").writeText("$summary\n\n--- logcat\n$log\n--- gfxinfo\n$gfx")
  }

  private companion object {
    const val TARGET_PACKAGE = "dev.keliver.sample"
    const val ACTIVITY = "dev.keliver.sample.host.gateb.GatebActivity"
    const val WINDOW_MILLIS = 15_000L
  }
}
