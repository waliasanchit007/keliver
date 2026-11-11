/*
 * DEBUG builds only (ED3): the debug link that points this host at the relay's device proxy.
 * Scaffolded into src/debug, with the intent filter in src/debug/AndroidManifest.xml: a release
 * build has neither this class nor the filter (keliver-new-production-host-selftest.sh checks
 * both in a built release APK).
 */
package @@PACKAGE@@

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.security.NetworkSecurityPolicy
import android.util.Log
import java.io.File

/** This app's debug link scheme, as src/debug/AndroidManifest.xml declares it: `keliver-` and the app's package. */
internal const val DEBUG_LINK_SCHEME = "@@DEBUG_LINK_SCHEME@@"

/**
 * Opens `<DEBUG_LINK_SCHEME>://keliver-debug/bundles?server=<URL>` (the editor's "On a device"
 * panel: `adb shell "am start -a android.intent.action.VIEW -d '<link>'"`, or its QR code where a
 * camera app offers to open such a link): it checks the link ([DebugBundleLink.server]: a
 * loopback, private or link-local address or localhost, a port from 1024 up, nothing else), asks, then
 * sets the bundle server on the device as the adb run-as switch does (files/keliver/bundle-server,
 * read by KeliverConfig.fromBuild) and restarts the app, whose host then looks up from there.
 *
 * It moves only where bundles come from: every manifest still verifies against the embedded key,
 * the rollback floor still holds, and the cached last good bundle is used only on the origin it
 * came from. It needs this module's debug variant AND a debuggable app, as every debug surface
 * does, and asks before it changes anything, since any app or web page can open a link.
 */
class KeliverDebugLinkActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (!BuildConfig.DEBUG || (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
      finish()
      return
    }
    val serverFile = File(filesDir, DEBUG_BUNDLE_SERVER_FILE)
    val current = runCatching { serverFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    val server = DebugBundleLink.server(intent?.dataString, DEBUG_LINK_SCHEME)
    val dialog = AlertDialog.Builder(this).setOnCancelListener { finish() }
    when {
      server == null -> {
        Log.w(TAG, "debug link refused: not $DEBUG_LINK_SCHEME://${DebugBundleLink.HOST}${DebugBundleLink.PATH}?server=<http(s)://<private address>:<port>>")
        dialog.setTitle("Not a Keliver debug link")
          .setMessage(
            "This debug build takes a bundle server from a link only as " +
              "$DEBUG_LINK_SCHEME://${DebugBundleLink.HOST}${DebugBundleLink.PATH}?server=<URL>, where the URL is " +
              "http(s)://<a loopback, private or link-local IP address, or localhost>:<port 1024 or above> " +
              "(the relay's device proxy). Nothing was changed.",
          )
          .setPositiveButton("Close") { _, _ -> finish() }
      }
      server.startsWith("http:") && !cleartextPermitted(server.substringAfter("://").substringBeforeLast(':').removeSurrounding("[", "]")) -> {
        Log.w(TAG, "debug link refused: this build allows no cleartext http:// to $server (built against an https:// server?)")
        dialog.setTitle("This build cannot reach $server")
          .setMessage(
            "The device proxy is plain http://, and this build allows no cleartext http://. A standalone host's " +
              "debug build allows it only when its keliver.bundleServer (or another server it was built with) is " +
              "http://, so a host built against an https:// server cannot use the proxy; an app that embeds the " +
              "host decides in its own manifest. Nothing was changed.",
          )
          .setPositiveButton("Close") { _, _ -> finish() }
      }
      else -> {
        dialog.setTitle("Load bundles from $server?")
          .setMessage(
            "This debug build restarts and loads this app's bundles from there (the relay's device proxy). " +
              "Each one still has to verify against the app's key." +
              (current?.let { "\n\nNow: $it" } ?: ""),
          )
          .setPositiveButton("Load") { _, _ -> use(serverFile, server) }
          .setNegativeButton("Cancel") { _, _ -> finish() }
      }
    }
    if (current != null) dialog.setNeutralButton("Use the build's server") { _, _ -> use(serverFile, null) }
    dialog.show()
  }

  /** Writes [server] (null: removes the file, back to keliver.bundleServer), then restarts the app. */
  private fun use(serverFile: File, server: String?) {
    val written = runCatching {
      if (server == null) {
        serverFile.delete() || !serverFile.exists()
      } else {
        serverFile.parentFile?.mkdirs()
        val tmp = File(serverFile.parentFile, serverFile.name + ".tmp")
        tmp.writeText(server + "\n")
        tmp.renameTo(serverFile)
      }
    }.getOrDefault(false)
    if (!written) {
      Log.w(TAG, "debug link: could not write files/$DEBUG_BUNDLE_SERVER_FILE")
      finish()
      return
    }
    Log.i(TAG, "bundle server ${server?.let { "set to $it" } ?: "reset to keliver.bundleServer"} by a debug link; restarting")
    val launch = packageManager.getLaunchIntentForPackage(packageName)?.component
    finish()
    if (launch != null) startActivity(Intent.makeRestartActivityTask(launch))
    // The host reads its bundle server once per process (KeliverConfig.fromBuild): a new process.
    Runtime.getRuntime().exit(0)
  }

  private fun cleartextPermitted(host: String): Boolean {
    val policy = NetworkSecurityPolicy.getInstance()
    return if (Build.VERSION.SDK_INT >= 24) policy.isCleartextTrafficPermitted(host) else policy.isCleartextTrafficPermitted
  }
}
