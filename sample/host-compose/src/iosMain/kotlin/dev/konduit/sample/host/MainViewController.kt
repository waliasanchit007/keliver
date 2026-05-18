/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(dev.konduit.leaks.RedwoodLeakApi::class)

package dev.konduit.sample.host

import androidx.compose.ui.window.ComposeUIViewController
import app.cash.zipline.Zipline
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.ZiplineHttpClient
import dev.konduit.leaks.LeakDetector
import dev.konduit.sample.schema.protocol.host.SampleSchemaHostProtocol
import dev.konduit.sample.shared.SampleAppService
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

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private var cachedApp: TreehouseApp<SampleAppService>? = null

private fun initializeTreehouseApp(): TreehouseApp<SampleAppService> {
  cachedApp?.let { return it }

  val manifestUrlFlow = MutableStateFlow(IosDevConfig.manifestUrl)

  val factory = TreehouseAppFactory(
    httpClient = IosZiplineHttpClient(),
    manifestVerifier = ManifestVerifier.NO_SIGNATURE_CHECKS,
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

  val app = factory.create(appScope = appScope, spec = spec)
  cachedApp = app
  return app
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
