/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.benchmarks

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue

/**
 * Gate B's phone checkpoint (prototype builds only; docs/GATE_B_RESULTS.md): records the device's
 * state next to each measurement, in `gateb-phone-state.txt` of the additional test output (pulled
 * by the connected test task), and holds a run back below a battery level.
 *
 * Instrumentation arguments: `gatebMinBattery` (percent, default 0) skips the test below it, so a
 * benchmark never runs on a nearly empty phone (power saving would skew the numbers).
 */
internal object GatebPhone {
  private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

  fun state(): String {
    val context = instrumentation.context
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: -1
    val plugged = when (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
      BatteryManager.BATTERY_PLUGGED_AC -> "ac"
      BatteryManager.BATTERY_PLUGGED_USB -> "usb"
      BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
      0, null -> "unplugged"
      else -> "other"
    }
    val status = when (battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
      BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
      BatteryManager.BATTERY_STATUS_FULL -> "full"
      BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
      BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not-charging"
      else -> "unknown"
    }
    val temperature = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
    return "battery=$level% status=$status plugged=$plugged temperature=${temperature}C " +
      "powerSave=${power.isPowerSaveMode} thermalStatus=${power.currentThermalStatus} " +
      "refreshRate=${display.refreshRate}Hz device=${Build.MANUFACTURER}/${Build.MODEL}/API${Build.VERSION.SDK_INT}"
  }

  fun batteryLevel(): Int {
    val battery = instrumentation.context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
    return battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
  }

  /** Appends `label: state` to the state file and skips the test below `gatebMinBattery`. */
  fun recordAndCheck(label: String) {
    record("$label: ${state()}")
    val min = InstrumentationRegistry.getArguments().getString("gatebMinBattery")?.toInt() ?: 0
    val level = batteryLevel()
    assumeTrue("battery $level% is below gatebMinBattery=$min%", level >= min)
  }

  fun record(line: String) {
    println("GatebPhone: $line")
    output("gateb-phone-state.txt").appendText("${System.currentTimeMillis()} $line\n")
  }

  fun output(name: String): File {
    val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
      ?.let(::File)
      ?: instrumentation.context.getExternalFilesDir(null)!!
    dir.mkdirs()
    return File(dir, name)
  }
}
