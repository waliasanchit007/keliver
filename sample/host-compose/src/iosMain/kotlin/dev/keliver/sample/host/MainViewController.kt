/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(dev.keliver.leaks.RedwoodLeakApi::class)

package dev.keliver.sample.host

import androidx.compose.ui.window.ComposeUIViewController
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.ZiplineService
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.ZiplineHttpClient
import app.cash.zipline.loader.withDevelopmentServerPush
import dev.keliver.leaks.LeakDetector
import dev.keliver.sample.schema.protocol.host.SampleSchemaHostProtocol
import dev.keliver.sample.shared.SampleAppService
import dev.keliver.treehouse.EventListener
import dev.keliver.treehouse.MemoryStateStore
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseAppFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
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
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionWebSocketCloseCodeNormalClosure
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.addValue
import platform.Foundation.dataTaskWithRequest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS entry point. The Swift side (in your Xcode project) calls this
 * to obtain a `UIViewController` that hosts the Keliver-rendered
 * guest tree:
 *
 * ```swift
 * import KeliverSampleHost
 *
 * struct ContentView: UIViewControllerRepresentable {
 *   func makeUIViewController(context: Context) -> UIViewController {
 *     return MainKt.MainViewController()
 *   }
 *   func updateUIViewController(_ vc: UIViewController, context: Context) {}
 * }
 * ```
 *
 * The framework name (`KeliverSampleHost`) is what `host-compose`'s
 * build.gradle.kts declares via `binaries.framework { baseName = "..." }`.
 */
public fun MainViewController() = ComposeUIViewController {
  val app = initializeTreehouseApp()
  SampleHostApp(treehouseApp = app)
}

/** iOS dev-mode manifest URL. Set this to your laptop's LAN IP for
 * a physical device. The simulator uses `localhost` directly. */
public object IosDevConfig {
  public val manifestUrl: String = "http://localhost:8080/manifest.zipline.json"

  /**
   * Parity with host-android `DevConfig.HOT_RELOAD`. **Verified end-to-end** on
   * an iPhone 16 Pro simulator (iOS 26.3): a guest edit served by
   * `./gradlew :guest:serveDevelopmentZipline --continuous` hot-reloads the
   * running app via [IosZiplineHttpClient.openDevelopmentServerWebSocket] with
   * no reinstall (a second `codeLoadSuccess` fires). Degrades gracefully to a
   * single manifest emit when no dev server is reachable, so it is safe to
   * leave on by default.
   */
  public const val HOT_RELOAD: Boolean = true
}

// Ed25519 PUBLIC key matching the PRIVATE signing key in
// guest/build.gradle.kts (identical to host-android). Public keys are
// safe to embed in the app.
private const val SAMPLE_SIGNING_PUBLIC_KEY =
  "98ecb60bb1c418e771378d4451ddfb2f9c440e3ec29cf2f89850ded8f5190cea"

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private var cachedApp: TreehouseApp<SampleAppService>? = null

private fun initializeTreehouseApp(): TreehouseApp<SampleAppService> {
  cachedApp?.let { return it }

  // Hoisted so the same Zipline HTTP client backs both the bundle fetch and
  // the hot-reload WebSocket below (mirrors host-android).
  val ziplineHttpClient = IosZiplineHttpClient()

  val factory = TreehouseAppFactory(
    httpClient = ziplineHttpClient,
    // Mirror host-android: verify the guest manifest's Ed25519 signature
    // (signed in guest/build.gradle.kts) before running guest code. Same
    // key name + public key as the Android host.
    manifestVerifier = ManifestVerifier.Builder()
      .addEd25519(
        name = "keliver-sample-ed25519",
        trustedKey = SAMPLE_SIGNING_PUBLIC_KEY.decodeHex(),
      )
      .build(),
    embeddedFileSystem = null,
    embeddedDir = null,
    cacheName = "keliver-sample-zipline",
    cacheMaxSizeInBytes = 50L * 1024L * 1024L,
    concurrentDownloads = 4,
    stateStore = MemoryStateStore(),
    leakDetector = LeakDetector.none(),
    hostProtocolFactory = SampleSchemaHostProtocol.Factory,
  )

  val spec = object : TreehouseApp.Spec<SampleAppService>() {
    override val name = "keliver-sample"

    // Hot reload (parity with host-android): when enabled, subscribe to the
    // Zipline dev server's WebSocket and re-emit the manifest URL on each
    // rebuild via `withDevelopmentServerPush` — which drives our
    // `IosZiplineHttpClient.openDevelopmentServerWebSocket` below. Gated on
    // IosDevConfig.HOT_RELOAD (default OFF): the NSURLSessionWebSocketTask push
    // path is implemented but not yet verified on a device/simulator.
    override val manifestUrl: Flow<String> =
      if (IosDevConfig.HOT_RELOAD) {
        flowOf(IosDevConfig.manifestUrl).withDevelopmentServerPush(ziplineHttpClient)
      } else {
        MutableStateFlow(IosDevConfig.manifestUrl).asStateFlow()
      }
    override val serializersModule = EmptySerializersModule()

    override suspend fun bindServices(
      treehouseApp: TreehouseApp<SampleAppService>,
      zipline: Zipline,
    ) {
      // Same host services as Android — see SampleHostServices (commonMain).
      SampleHostServices.bind(zipline)
    }

    override fun create(zipline: Zipline): SampleAppService =
      zipline.take("app")
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
 * iOS-side `EventListener` for parity with the Android sample's
 * `MainActivity`. Routes Zipline lifecycle events through `NSLog`
 * so they surface in Xcode's console and `xcrun simctl spawn …
 * log show --predicate 'eventMessage CONTAINS "KeliverSample"'`.
 *
 * Without this listener, every iOS-side guest failure
 * (codeLoadFailed, manifestParseFailed, uncaughtException) is
 * SILENT — Kotlin's `println(...)` does not surface in the
 * simulator's unified log subsystem on its own.
 */
private object LoggingEventListenerFactory : EventListener.Factory {
  override fun create(app: TreehouseApp<*>, manifestUrl: String?): EventListener =
    LoggingEventListener
  override fun close() {}
}

private object LoggingEventListener : EventListener() {
  // Use Kotlin's `println` rather than `NSLog`. Kotlin/Native's
  // varargs ↔ ObjC bridge for `NSLog(format, ...)` crashes the app
  // with EXC_BAD_ACCESS at launch when a Kotlin `String` is passed
  // as a `%@` argument — observed during sample testing on Xcode
  // 26.3 + iOS sim 26.3. `println` is the K/N-idiomatic choice and
  // surfaces to Xcode's debug console; if you need entries to show
  // in `xcrun simctl spawn … log show`, wrap with `os_log` via a
  // properly-typed C wrapper (see Keliver's docs/KNOWN_BUGS.md).
  private fun log(message: String) = println(message)

  override fun ziplineCreated(zipline: Zipline) =
    log("KeliverSample: ziplineCreated")
  override fun bindService(name: String, service: ZiplineService) =
    log("KeliverSample: bindService name=$name")
  override fun takeService(name: String, service: ZiplineService) =
    log("KeliverSample: takeService name=$name")
  override fun codeLoadSuccess(manifest: ZiplineManifest, zipline: Zipline, startValue: Any?) =
    log("KeliverSample: codeLoadSuccess modules=${manifest.modules.keys.size}")
  override fun codeLoadFailed(exception: Exception, startValue: Any?) =
    log("KeliverSample: codeLoadFailed: ${exception.message ?: "<no message>"}")
  override fun manifestReady(manifest: ZiplineManifest) =
    log("KeliverSample: manifestReady modules=${manifest.modules.keys.size}")
  override fun manifestParseFailed(exception: Exception) =
    log("KeliverSample: manifestParseFailed: ${exception.message ?: "<no message>"}")
  override fun mainFunctionStart(applicationName: String): Any? {
    log("KeliverSample: mainFunctionStart app=$applicationName")
    return null
  }
  override fun mainFunctionEnd(applicationName: String, startValue: Any?) =
    log("KeliverSample: mainFunctionEnd app=$applicationName")
  override fun uncaughtException(exception: Throwable) =
    log("KeliverSample: uncaughtException: ${exception.message ?: "<no message>"}")
  override fun serviceLeaked(name: String) =
    log("KeliverSample: serviceLeaked name=$name")
}

/**
 * `NSURLSession`-backed Zipline HTTP client. Keliver ships an
 * OkHttp adapter for Android via `okhttp.asZiplineHttpClient()`,
 * but iOS doesn't have a comparable single-line bridge — the
 * Foundation API is what we use.
 */
private class IosZiplineHttpClient(
  private val urlSession: NSURLSession = NSURLSession.sharedSession,
) : ZiplineHttpClient() {
  @OptIn(ExperimentalForeignApi::class)
  override suspend fun download(
    url: String,
    requestHeaders: List<Pair<String, String>>,
  ): ByteString {
    val nsUrl = NSURL(string = url)
    return suspendCancellableCoroutine { continuation: CancellableContinuation<ByteString> ->
      val handler = CompletionHandler(url, continuation)
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
        completionHandler = handler::invoke,
      )
      continuation.invokeOnCancellation { task.cancel() }
      task.resume()
    }
  }

  /**
   * Hot-reload SPI (host-android parity). Opens a receive-only WebSocket to
   * [url] with `NSURLSessionWebSocketTask` and emits each text message the
   * Zipline dev server pushes; the flow completes when the socket closes —
   * matching the [ZiplineHttpClient.openDevelopmentServerWebSocket] contract.
   * NOTE: implemented but not yet verified on a device/simulator; reachable
   * only when IosDevConfig.HOT_RELOAD is true.
   */
  @OptIn(ExperimentalForeignApi::class)
  override suspend fun openDevelopmentServerWebSocket(
    url: String,
    requestHeaders: List<Pair<String, String>>,
  ): Flow<String> = callbackFlow {
    val request = NSMutableURLRequest(
      uRL = NSURL(string = url),
      cachePolicy = NSURLRequestUseProtocolCachePolicy,
      timeoutInterval = 60.0,
    ).apply {
      for ((name, value) in requestHeaders) {
        addValue(value = value, forHTTPHeaderField = name)
      }
    }
    val task = urlSession.webSocketTaskWithRequest(request)

    // Re-arm the receiver after each message (receive-only socket).
    fun receiveNext() {
      task.receiveMessageWithCompletionHandler { message: NSURLSessionWebSocketMessage?, error ->
        if (error != null) {
          close()
        } else {
          message?.string?.let { trySend(it) }
          receiveNext()
        }
      }
    }

    task.resume()
    receiveNext()

    awaitClose {
      task.cancelWithCloseCode(NSURLSessionWebSocketCloseCodeNormalClosure, null)
    }
  }
}

private class CompletionHandler(
  private val url: String,
  private val continuation: CancellableContinuation<ByteString>,
) {
  fun invoke(data: NSData?, response: NSURLResponse?, error: NSError?) {
    if (error != null) {
      continuation.resumeWithException(IOException(error.description))
      return
    }
    if (response !is NSHTTPURLResponse || data == null) {
      continuation.resumeWithException(IOException("unexpected response: $response"))
      return
    }
    if (response.statusCode !in 200 until 300) {
      continuation.resumeWithException(
        IOException("failed to fetch $url: ${response.statusCode}"),
      )
      return
    }
    continuation.resume(data.toByteString())
  }
}
