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
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.keliver.compose.gatea.sample.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.toKString
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSStringFromClass
import platform.QuartzCore.CACurrentMediaTime
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDelegateProtocol
import platform.UIKit.UIApplicationDelegateProtocolMeta
import platform.UIKit.UIApplicationMain
import platform.UIKit.UIResponder
import platform.UIKit.UIResponderMeta
import platform.UIKit.UIScreen
import platform.UIKit.UIWindow
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv

/** When main() started (CACurrentMediaTime, seconds): the zero of the first-frame times. */
internal var startedAt = 0.0

/**
 * The launch arguments (`simctl launch <udid> dev.keliver.gateb <mode> <sample> [manifest URL]`):
 * mode `native` or `guest`, sample `orders`, `card` or `item`.
 */
internal class LaunchArguments(val mode: String, val sample: String, val manifestUrl: String?)

internal fun launchArguments(): LaunchArguments {
  val args = NSProcessInfo.processInfo.arguments.map { it.toString() }.drop(1)
  return LaunchArguments(
    mode = args.getOrNull(0) ?: "native",
    sample = args.getOrNull(1) ?: "orders",
    manifestUrl = args.getOrNull(2),
  )
}

fun main(args: Array<String>) {
  startedAt = CACurrentMediaTime()
  memScoped {
    val argv = (arrayOf("GateB") + args).map { it.cstr.ptr }.toCValues()
    UIApplicationMain(args.size + 1, argv, null, NSStringFromClass(AppDelegate))
  }
}

internal class AppDelegate @OverrideInit constructor() :
  UIResponder(),
  UIApplicationDelegateProtocol {
  companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta

  private var appWindow: UIWindow? = null

  override fun window(): UIWindow? = appWindow

  override fun setWindow(window: UIWindow?) {
    appWindow = window
  }

  override fun application(application: UIApplication, didFinishLaunchingWithOptions: Map<Any?, *>?): Boolean {
    val launch = launchArguments()
    log("launch mode=${launch.mode} sample=${launch.sample} manifest=${launch.manifestUrl}")
    appWindow = UIWindow(frame = UIScreen.mainScreen.bounds).apply {
      rootViewController = sampleViewController(launch)
      makeKeyAndVisible()
    }
    return true
  }
}

/**
 * One line on stdout and, when the launch names one (SIMCTL_CHILD_GATEB_LOG, which simctl passes as
 * GATEB_LOG), appended to that host file: the script reads it while the app runs.
 */
internal fun log(message: String) {
  val line = "GATEB $message"
  println(line)
  val path = getenv("GATEB_LOG")?.toKString() ?: return
  val file = fopen(path, "a") ?: return
  fputs("$line\n", file)
  fclose(file)
}
