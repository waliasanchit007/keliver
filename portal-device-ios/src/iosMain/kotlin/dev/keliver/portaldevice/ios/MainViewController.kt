/*
 * spike/keliver-web portal P5 — the iOS Treehouse HOST (peer of the Android
 * host). Two modes, chosen by a launch argument:
 *   (default) dev  — loads the interpreter guest from :8080 and binds HostApi
 *                    so it polls the portal-server draft (live editing).
 *   "prod"         — resolves the newest compatible PUBLISHED bundle from the
 *                    portal-server store and verifies its Ed25519 signature.
 * Template: sample/host-compose MainViewController (runtime-proven on iOS).
 */
@file:OptIn(dev.keliver.leaks.RedwoodLeakApi::class)

package dev.keliver.portaldevice.ios

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.ZiplineHttpClient
import coil3.ImageLoader
import coil3.PlatformContext
import dev.keliver.leaks.LeakDetector
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetSystem
import dev.keliver.material.protocol.host.KeliverMaterialHostProtocol
import dev.keliver.portaldevice.HostApi
import dev.keliver.portaldevice.PortalPresenter
import dev.keliver.treehouse.EventListener
import dev.keliver.treehouse.MemoryStateStore
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseAppFactory
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.modules.EmptySerializersModule
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import okio.IOException
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.addValue
import platform.Foundation.dataTaskWithRequest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// The iOS simulator shares the Mac's network — localhost reaches the
// portal-server (:8077) and the zipline dev server (:8080) directly.
private const val DEV_MANIFEST_URL = "http://localhost:8080/manifest.zipline.json"
private const val PORTAL_SERVER = "http://localhost:8077"

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private var cachedApp: TreehouseApp<PortalPresenter>? = null

public fun MainViewController() = ComposeUIViewController {
  val app = initializeTreehouseApp()
  val widgetSystem = remember {
    ComposeUiKeliverMaterialWidgetSystem(ImageLoader.Builder(PlatformContext.INSTANCE).build())
  }
  val contentSource = remember {
    object : TreehouseContentSource<PortalPresenter> {
      override fun get(app: PortalPresenter) = app.launch()
    }
  }
  TreehouseContent(
    treehouseApp = app,
    widgetSystem = widgetSystem,
    contentSource = contentSource,
  )
}

private fun isProdMode(): Boolean =
  NSProcessInfo.processInfo.arguments.any { it.toString() == "prod" }

private fun initializeTreehouseApp(): TreehouseApp<PortalPresenter> {
  cachedApp?.let { return it }
  val prodMode = isProdMode()
  val ziplineHttpClient = IosZiplineHttpClient()

  val verifier = if (prodMode && PORTAL_PUBLIC_KEY_HEX.isNotEmpty()) {
    log("prod mode: verifying manifests with portal-ed25519 ${PORTAL_PUBLIC_KEY_HEX.take(8)}…")
    ManifestVerifier.Builder()
      .addEd25519(name = "portal-ed25519", trustedKey = PORTAL_PUBLIC_KEY_HEX.decodeHex())
      .build()
  } else {
    if (prodMode) log("prod mode WITHOUT embedded public key — NO_SIGNATURE_CHECKS fallback")
    ManifestVerifier.NO_SIGNATURE_CHECKS
  }
  log("mode=${if (prodMode) "prod" else "dev"}")

  val factory = TreehouseAppFactory(
    httpClient = ziplineHttpClient,
    manifestVerifier = verifier,
    embeddedFileSystem = null,
    embeddedDir = null,
    cacheName = if (prodMode) "portal-device-ios-prod" else "portal-device-ios",
    cacheMaxSizeInBytes = 50L * 1024L * 1024L,
    concurrentDownloads = 4,
    stateStore = MemoryStateStore(),
    leakDetector = LeakDetector.none(),
    hostProtocolFactory = KeliverMaterialHostProtocol.Factory,
  )

  val manifestFlow = MutableStateFlow(if (prodMode) "" else DEV_MANIFEST_URL)
  if (prodMode) {
    appScope.launch(Dispatchers.Default) {
      runCatching {
        // M7: declare the host capabilities we bind below (Zipline HostSqlDriver),
        // or the gate serves an older bundle that doesn't need them.
        val caps = dev.keliver.portal.sql.HOST_SQL_CAPABILITY.replace("@", "%40")
        val body = httpGet("$PORTAL_SERVER/bundles/latest?widgetVersion=1&caps=$caps")
        val path = Regex("\"manifestUrl\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        if (path != null) {
          log("prod mode: loading $PORTAL_SERVER$path")
          manifestFlow.value = "$PORTAL_SERVER$path"
        } else {
          log("prod mode: no compatible bundle ($body)")
        }
      }.onFailure { log("prod mode: bundle lookup failed: ${it.message}") }
    }
  }

  val spec = object : TreehouseApp.Spec<PortalPresenter>() {
    override val name = "portal-device"
    override val manifestUrl = manifestFlow.asStateFlow()
    override val serializersModule = EmptySerializersModule()

    override suspend fun bindServices(treehouseApp: TreehouseApp<PortalPresenter>, zipline: Zipline) {
      zipline.bind<HostApi>("HostApi", IosHostApi())
      // M7/M9: the data-layer capability so the compiled screen's presenter has
      // its data layer on iOS too (parity with the Android host).
      zipline.bind<dev.keliver.portal.sql.HostSqlDriver>("HostSqlDriver", IosSqlHost())
    }

    override fun create(zipline: Zipline): PortalPresenter = zipline.take("PortalPresenter")
  }

  val app = factory.create(
    appScope = appScope,
    spec = spec,
    eventListenerFactory = LoggingEventListenerFactory,
  )
  cachedApp = app
  return app
}

/**
 * Host impl of the guest's HostApi. The shared guest polls the Android-form
 * relay URL (10.0.2.2); on iOS the Mac is just localhost — rewrite it.
 */
private class IosHostApi : HostApi {
  override suspend fun httpCall(url: String): String =
    httpGet(url.replace("10.0.2.2", "localhost"))
}

private fun log(message: String) = println("PortalDeviceIos: $message")

private object LoggingEventListenerFactory : EventListener.Factory {
  override fun create(app: TreehouseApp<*>, manifestUrl: String?): EventListener = LoggingEventListener
  override fun close() {}
}

private object LoggingEventListener : EventListener() {
  override fun codeLoadSuccess(manifest: ZiplineManifest, zipline: Zipline, startValue: Any?) =
    log("codeLoadSuccess modules=${manifest.modules.keys.size}")
  override fun codeLoadFailed(exception: Exception, startValue: Any?) =
    log("codeLoadFailed: ${exception.message ?: "<no message>"}")
  override fun manifestParseFailed(exception: Exception) =
    log("manifestParseFailed: ${exception.message ?: "<no message>"}")
  override fun uncaughtException(exception: Throwable) =
    log("uncaughtException: ${exception.message ?: "<no message>"}")
}

/** One-shot NSURLSession GET returning the body as UTF-8 text. */
@OptIn(ExperimentalForeignApi::class)
private suspend fun httpGet(url: String): String =
  suspendCancellableCoroutine { continuation ->
    val request = NSMutableURLRequest(
      uRL = NSURL(string = url)!!,
      cachePolicy = NSURLRequestUseProtocolCachePolicy,
      timeoutInterval = 30.0,
    )
    val task = NSURLSession.sharedSession.dataTaskWithRequest(
      request = request,
      completionHandler = { data: NSData?, response: NSURLResponse?, error: NSError? ->
        when {
          error != null -> continuation.resumeWithException(IOException(error.description))
          response !is NSHTTPURLResponse || data == null ->
            continuation.resumeWithException(IOException("unexpected response: $response"))
          response.statusCode !in 200 until 300 ->
            continuation.resumeWithException(IOException("failed to fetch $url: ${response.statusCode}"))
          else -> continuation.resume(data.toByteString().utf8())
        }
      },
    )
    continuation.invokeOnCancellation { task.cancel() }
    task.resume()
  }

/** NSURLSession-backed Zipline HTTP client (template: sample host-compose). */
private class IosZiplineHttpClient(
  private val urlSession: NSURLSession = NSURLSession.sharedSession,
) : ZiplineHttpClient() {
  @OptIn(ExperimentalForeignApi::class)
  override suspend fun download(
    url: String,
    requestHeaders: List<Pair<String, String>>,
  ): ByteString {
    val nsUrl = NSURL(string = url)!!
    return suspendCancellableCoroutine { continuation: CancellableContinuation<ByteString> ->
      val request = NSMutableURLRequest(
        uRL = nsUrl,
        cachePolicy = NSURLRequestUseProtocolCachePolicy,
        timeoutInterval = 60.0,
      ).apply {
        for ((name, value) in requestHeaders) {
          addValue(value = value, forHTTPHeaderField = name)
        }
      }
      val task = urlSession.dataTaskWithRequest(
        request = request,
        completionHandler = { data: NSData?, response: NSURLResponse?, error: NSError? ->
          when {
            error != null -> continuation.resumeWithException(IOException(error.description))
            response !is NSHTTPURLResponse || data == null ->
              continuation.resumeWithException(IOException("unexpected response: $response"))
            response.statusCode !in 200 until 300 ->
              continuation.resumeWithException(IOException("failed to fetch $url: ${response.statusCode}"))
            else -> continuation.resume(data.toByteString())
          }
        },
      )
      continuation.invokeOnCancellation { task.cancel() }
      task.resume()
    }
  }
}
