/*
 * Copyright (C) 2026 Keliver contributors.
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

package dev.keliver.compose.size.ios

import dev.keliver.compose.coil.composeui.ComposeUiKeliverComposeCoilWidgetFactory
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCValues
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSStringFromClass
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDelegateProtocol
import platform.UIKit.UIApplicationDelegateProtocolMeta
import platform.UIKit.UIApplicationMain
import platform.UIKit.UIResponder
import platform.UIKit.UIResponderMeta
import platform.UIKit.UIScreen
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow

/**
 * The launch arguments (`<mode> <sample> [manifest URL]`): mode `native` or `guest` (the product
 * executables only), sample `orders`, `card` or `item`.
 */
internal class LaunchArguments(val mode: String, val sample: String, val manifestUrl: String?)

/** The window's content: set by the executable's entry point, so each links only its own route. */
private var makeController: (LaunchArguments) -> UIViewController = { UIViewController() }

/** The baseline executable: native Compose only. */
fun mainNative(args: Array<String>) = run(args) { nativeViewController(it.sample) }

/** The product route without Coil (the coil group absent). */
fun mainProduct(args: Array<String>) = run(args) { productViewController(it) { null } }

/** The product route with the optional coil group (only this entry point reaches Coil). */
fun mainProductCoil(args: Array<String>) = run(args) { productViewController(it) { ComposeUiKeliverComposeCoilWidgetFactory() } }

private fun run(args: Array<String>, controller: (LaunchArguments) -> UIViewController) {
  makeController = controller
  memScoped {
    val argv = (arrayOf("KeliverSize") + args).map { it.cstr.ptr }.toCValues()
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
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }.drop(1)
    val launch = LaunchArguments(args.getOrNull(0) ?: "native", args.getOrNull(1) ?: "orders", args.getOrNull(2))
    appWindow = UIWindow(frame = UIScreen.mainScreen.bounds).apply {
      rootViewController = makeController(launch)
      makeKeyAndVisible()
    }
    return true
  }
}
