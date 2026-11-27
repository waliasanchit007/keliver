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
@file:OptIn(dev.keliver.leaks.RedwoodLeakApi::class)

package dev.keliver.compose.size.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeUIViewController
import app.cash.zipline.Zipline
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.ZiplineHttpClient
import dev.keliver.compose.coil.widget.KeliverComposeCoilWidgetFactory
import dev.keliver.compose.core.composeui.samples.native.ItemDetail
import dev.keliver.compose.core.composeui.samples.native.SampleCard
import dev.keliver.compose.core.composeui.samples.native.SampleOrders
import dev.keliver.compose.root.composeui.keliverComposeWidgetSystem
import dev.keliver.compose.root.protocol.host.KeliverComposeHostProtocol
import dev.keliver.compose.size.SAMPLE_CARD
import dev.keliver.compose.size.SAMPLE_ITEM
import dev.keliver.compose.size.SizeSampleData
import dev.keliver.compose.size.SizeSamples
import dev.keliver.leaks.LeakDetector
import dev.keliver.treehouse.MemoryStateStore
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseAppFactory
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.addValue
import platform.Foundation.dataTaskWithRequest
import platform.UIKit.UIViewController

/** The baseline: a sample drawn by native Compose. */
internal fun nativeViewController(sample: String): UIViewController = ComposeUIViewController {
  Window { NativeSample(sample) }
}

/**
 * The product route (native mode, or the guest on the generated root's renderers). [coil] is the
 * entry point's: only ProductCoil's reaches the coil group's renderers.
 */
internal fun productViewController(launch: LaunchArguments, coil: () -> CoilFactory?): UIViewController = ComposeUIViewController {
  Window {
    if (launch.mode == "guest") GuestSample(launch, coil) else NativeSample(launch.sample)
  }
}

internal typealias CoilFactory = KeliverComposeCoilWidgetFactory<@Composable (Modifier) -> Unit>

@Composable
private fun Window(content: @Composable () -> Unit) {
  MaterialTheme {
    Box(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) { content() }
  }
}

@Composable
private fun NativeSample(sample: String) {
  when (sample) {
    SAMPLE_CARD -> SampleCard(SizeSampleData.CARD_TITLE, SizeSampleData.CARD_QUANTITY, SizeSampleData.tags) {}
    SAMPLE_ITEM -> ItemDetail(onBack = {}, onAdjust = {}, onReorder = {})
    else -> SampleOrders(SizeSampleData.orders, onBack = {}, onOpen = {})
  }
}

@Composable
private fun GuestSample(launch: LaunchArguments, coil: () -> CoilFactory?) {
  val url = launch.manifestUrl ?: error("guest mode needs a manifest URL (the third launch argument)")
  val app = remember { treehouseApp(url) }
  // No legacy keliver-material system; Coil only in the ProductCoil executable.
  val widgetSystem = remember { keliverComposeWidgetSystem(coil = coil()) }
  val source = remember {
    object : TreehouseContentSource<SizeSamples> {
      override fun get(app: SizeSamples) = app.launch(launch.sample)
    }
  }
  TreehouseContent(treehouseApp = app, widgetSystem = widgetSystem, contentSource = source)
}

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private fun treehouseApp(manifestUrl: String): TreehouseApp<SizeSamples> {
  val factory = TreehouseAppFactory(
    httpClient = IosZiplineHttpClient(),
    manifestVerifier = ManifestVerifier.Builder()
      .addEd25519(name = "size-ed25519", trustedKey = SIZE_PUBLIC_KEY_HEX.decodeHex())
      .build(),
    embeddedFileSystem = null,
    embeddedDir = null,
    cacheName = "keliver-size",
    cacheMaxSizeInBytes = 20L * 1024L * 1024L,
    concurrentDownloads = 4,
    stateStore = MemoryStateStore(),
    leakDetector = LeakDetector.none(),
    hostProtocolFactory = KeliverComposeHostProtocol.Factory,
  )
  val spec = object : TreehouseApp.Spec<SizeSamples>() {
    override val name = "keliver-size"
    override val manifestUrl = flowOf(manifestUrl)
    override val serializersModule = EmptySerializersModule()
    override suspend fun bindServices(treehouseApp: TreehouseApp<SizeSamples>, zipline: Zipline) = Unit
    override fun create(zipline: Zipline): SizeSamples = zipline.take("SizeSamples")
  }
  return factory.create(appScope = appScope, spec = spec)
}

/** NSURLSession-backed Zipline HTTP client (as keliver-compose-gatea-sample-ios). */
private class IosZiplineHttpClient : ZiplineHttpClient() {
  override suspend fun download(url: String, requestHeaders: List<Pair<String, String>>): ByteString =
    suspendCancellableCoroutine { continuation: CancellableContinuation<ByteString> ->
      val request = NSMutableURLRequest(uRL = NSURL(string = url), cachePolicy = NSURLRequestReloadIgnoringLocalCacheData, timeoutInterval = 60.0)
      for ((name, value) in requestHeaders) request.addValue(value, forHTTPHeaderField = name)
      val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data: NSData?, response: NSURLResponse?, error: NSError? ->
        when {
          error != null -> continuation.resumeWithException(IOException(error.description))
          response !is NSHTTPURLResponse || data == null -> continuation.resumeWithException(IOException("unexpected response: $response"))
          response.statusCode !in 200 until 300 -> continuation.resumeWithException(IOException("failed to fetch $url: ${response.statusCode}"))
          else -> continuation.resume(data.toByteString())
        }
      }
      continuation.invokeOnCancellation { task.cancel() }
      task.resume()
    }
}
