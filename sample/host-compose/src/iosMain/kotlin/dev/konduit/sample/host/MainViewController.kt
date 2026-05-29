/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(dev.konduit.leaks.RedwoodLeakApi::class)

package dev.konduit.sample.host

import androidx.compose.ui.window.ComposeUIViewController
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.ZiplineService
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.ZiplineHttpClient
import dev.konduit.leaks.LeakDetector
import dev.konduit.sample.schema.protocol.host.SampleSchemaHostProtocol
import dev.konduit.sample.shared.SampleAppService
import dev.konduit.treehouse.EventListener
import dev.konduit.treehouse.MemoryStateStore
import dev.konduit.treehouse.TreehouseApp
import dev.konduit.treehouse.TreehouseAppFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import platform.Foundation.addValue
import platform.Foundation.dataTaskWithRequest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS entry point. The Swift side (in your Xcode project) calls this
 * to obtain a `UIViewController` that hosts the Konduit-rendered
 * guest tree:
 *
 * ```swift
 * import KonduitSampleHost
 *
 * struct ContentView: UIViewControllerRepresentable {
 *   func makeUIViewController(context: Context) -> UIViewController {
 *     return MainKt.MainViewController()
 *   }
 *   func updateUIViewController(_ vc: UIViewController, context: Context) {}
 * }
 * ```
 *
 * The framework name (`KonduitSampleHost`) is what `host-compose`'s
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

  val manifestUrlFlow = MutableStateFlow(IosDevConfig.manifestUrl)

  val factory = TreehouseAppFactory(
    httpClient = IosZiplineHttpClient(),
    // Mirror host-android: verify the guest manifest's Ed25519 signature
    // (signed in guest/build.gradle.kts) before running guest code. Same
    // key name + public key as the Android host.
    manifestVerifier = ManifestVerifier.Builder()
      .addEd25519(
        name = "konduit-sample-ed25519",
        trustedKey = SAMPLE_SIGNING_PUBLIC_KEY.decodeHex(),
      )
      .build(),
    embeddedFileSystem = null,
    embeddedDir = null,
    cacheName = "konduit-sample-zipline",
    cacheMaxSizeInBytes = 50L * 1024L * 1024L,
    concurrentDownloads = 4,
    stateStore = MemoryStateStore(),
    leakDetector = LeakDetector.none(),
    hostProtocolFactory = SampleSchemaHostProtocol.Factory,
  )

  val spec = object : TreehouseApp.Spec<SampleAppService>() {
    override val name = "konduit-sample"
    override val manifestUrl = manifestUrlFlow.asStateFlow()
    override val serializersModule = EmptySerializersModule()

    override suspend fun bindServices(
      treehouseApp: TreehouseApp<SampleAppService>,
      zipline: Zipline,
    ) {
      // No host services bound in the minimal sample.
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
 * log show --predicate 'eventMessage CONTAINS "KonduitSample"'`.
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
  // properly-typed C wrapper (see Konduit's docs/KNOWN_BUGS.md).
  private fun log(message: String) = println(message)

  override fun ziplineCreated(zipline: Zipline) =
    log("KonduitSample: ziplineCreated")
  override fun bindService(name: String, service: ZiplineService) =
    log("KonduitSample: bindService name=$name")
  override fun takeService(name: String, service: ZiplineService) =
    log("KonduitSample: takeService name=$name")
  override fun codeLoadSuccess(manifest: ZiplineManifest, zipline: Zipline, startValue: Any?) =
    log("KonduitSample: codeLoadSuccess modules=${manifest.modules.keys.size}")
  override fun codeLoadFailed(exception: Exception, startValue: Any?) =
    log("KonduitSample: codeLoadFailed: ${exception.message ?: "<no message>"}")
  override fun manifestReady(manifest: ZiplineManifest) =
    log("KonduitSample: manifestReady modules=${manifest.modules.keys.size}")
  override fun manifestParseFailed(exception: Exception) =
    log("KonduitSample: manifestParseFailed: ${exception.message ?: "<no message>"}")
  override fun mainFunctionStart(applicationName: String): Any? {
    log("KonduitSample: mainFunctionStart app=$applicationName")
    return null
  }
  override fun mainFunctionEnd(applicationName: String, startValue: Any?) =
    log("KonduitSample: mainFunctionEnd app=$applicationName")
  override fun uncaughtException(exception: Throwable) =
    log("KonduitSample: uncaughtException: ${exception.message ?: "<no message>"}")
  override fun serviceLeaked(name: String) =
    log("KonduitSample: serviceLeaked name=$name")
}

/**
 * `NSURLSession`-backed Zipline HTTP client. Konduit ships an
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
