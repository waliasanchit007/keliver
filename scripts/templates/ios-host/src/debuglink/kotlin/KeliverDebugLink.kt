/*
 * DEBUG configuration only (ED3): the debug link that points this host at the relay's device
 * proxy. This file and DebugBundleLink.kt are compiled only when Xcode's build setting
 * KELIVER_DEBUG_LINK is YES, which only the Debug configuration sets (build.gradle); every other
 * build compiles src/nodebuglink/ instead, and its Info.plist has no URL type for the link
 * (the Debug configuration's is iosApp/Info-Debug.plist). keliver-new-ios-host-selftest.sh checks
 * both. Scaffolded by keliver-new-ios-host.sh.
 */
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package @@PACKAGE@@

import kotlin.native.Platform
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertActionStyleDestructive
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

/** This app's debug link scheme, as iosApp/Info-Debug.plist declares it: `keliver-` and the app's package. */
internal const val DEBUG_LINK_SCHEME = "@@DEBUG_LINK_SCHEME@@"

/**
 * The only port a debug link may name: the relay's device proxy (KELIVER_DEVICE_PROXY_PORT, by
 * default the relay's port + 1, from keliver.portal.json when this host was scaffolded). Change it
 * here if your proxy listens elsewhere.
 */
internal const val DEBUG_LINK_PORT = @@DEBUG_LINK_PORT@@

/** Where a debug link's server is kept (this app's own defaults). */
private const val DEBUG_BUNDLE_SERVER_KEY = "keliver.debugBundleServer"

/** The bundle server a debug link set, or null (then BUNDLE_SERVER). A release binary never reads it. */
internal fun debugLinkBundleServer(): String? {
  if (!Platform.isDebugBinary) return null
  val value = NSUserDefaults.standardUserDefaults.stringForKey(DEBUG_BUNDLE_SERVER_KEY)?.trim()
  // Written only from a checked link; checked again, so a value edited by other means is ignored.
  return value?.let { DebugBundleLink.bundleServer(it, DEBUG_LINK_PORT) }
}

/**
 * Opens `<DEBUG_LINK_SCHEME>://keliver-debug/bundles?server=<URL>` (the editor's "On a device" QR
 * code, scanned with the camera, or `xcrun simctl openurl booted '<link>'`). Swift calls it from
 * `.onOpenURL` (iosApp/iOSApp.swift, under `#if KELIVER_DEBUG_LINK`). It checks the link
 * ([DebugBundleLink.server]: an address or localhost, the proxy's port, nothing else), asks, then
 * keeps the server in this app's defaults and closes the app: the host reads its bundle server
 * once per process, so the next launch loads from there. True when the link was this host's to
 * handle (its scheme), whatever came of it.
 *
 * It moves only where bundles come from: every manifest still verifies against the embedded key,
 * the rollback floor still holds, and the cached last good bundle is used only on the origin it
 * came from. A plain http:// server also needs an App Transport Security exception for it, as
 * the scaffolder writes for an http:// --bundle-server (development only). It asks before it
 * changes anything, since any app or web page can open a link.
 */
public fun openKeliverDebugLink(url: String): Boolean {
  if (!Platform.isDebugBinary) return false
  if (!url.startsWith("$DEBUG_LINK_SCHEME:", ignoreCase = true)) return false
  val server = DebugBundleLink.server(url, DEBUG_LINK_SCHEME, DEBUG_LINK_PORT)
  val current = NSUserDefaults.standardUserDefaults.stringForKey(DEBUG_BUNDLE_SERVER_KEY)
  val alert: UIAlertController
  if (server == null) {
    consoleLine("KeliverHost: debug link refused: not $DEBUG_LINK_SCHEME://${DebugBundleLink.HOST}${DebugBundleLink.PATH}?server=<http(s)://<address>:$DEBUG_LINK_PORT>")
    alert = UIAlertController.alertControllerWithTitle(
      "Not a Keliver debug link",
      "This debug build takes a bundle server from a link only as " +
        "$DEBUG_LINK_SCHEME://${DebugBundleLink.HOST}${DebugBundleLink.PATH}?server=<URL>, where the URL is " +
        "http(s)://<an IP address or localhost>:$DEBUG_LINK_PORT (the relay's device proxy). Nothing was changed.",
      UIAlertControllerStyleAlert,
    )
    alert.addAction(UIAlertAction.actionWithTitle("Close", UIAlertActionStyleCancel, null))
  } else {
    alert = UIAlertController.alertControllerWithTitle(
      "Load bundles from $server?",
      "This debug build keeps that server and closes; open it again to load this app's bundles from there " +
        "(the relay's device proxy). Each one still has to verify against the app's key." +
        (current?.let { "\n\nNow: $it" } ?: ""),
      UIAlertControllerStyleAlert,
    )
    alert.addAction(UIAlertAction.actionWithTitle("Load and close", UIAlertActionStyleDefault) { _ -> use(server) })
    alert.addAction(UIAlertAction.actionWithTitle("Cancel", UIAlertActionStyleCancel, null))
  }
  if (current != null) {
    alert.addAction(UIAlertAction.actionWithTitle("Use the build's server", UIAlertActionStyleDestructive) { _ -> use(null) })
  }
  @Suppress("DEPRECATION")
  var top: UIViewController? = UIApplication.sharedApplication.keyWindow?.rootViewController
  while (top?.presentedViewController != null) top = top.presentedViewController
  if (top == null) {
    consoleLine("KeliverHost: debug link: no view controller to ask from; nothing was changed")
    return true
  }
  top.presentViewController(alert, animated = true, completion = null)
  return true
}

/** Keeps [server] (null: removes it, back to BUNDLE_SERVER), then ends the process: the next launch uses it. */
private fun use(server: String?) {
  val defaults = NSUserDefaults.standardUserDefaults
  if (server == null) defaults.removeObjectForKey(DEBUG_BUNDLE_SERVER_KEY) else defaults.setObject(server, forKey = DEBUG_BUNDLE_SERVER_KEY)
  defaults.synchronize()
  consoleLine("KeliverHost: bundle server ${server?.let { "set to $it" } ?: "reset to BUNDLE_SERVER"} by a debug link; closing")
  platform.posix.exit(0)
}
