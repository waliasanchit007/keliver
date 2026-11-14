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
@file:OptIn(ExperimentalForeignApi::class, dev.keliver.leaks.RedwoodLeakApi::class)

package dev.keliver.compose.gatea.sample.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.window.ComposeUIViewController
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.ZiplineHttpClient
import coil3.ImageLoader
import coil3.PlatformContext
import dev.keliver.compose.gatea.composeui.ComposeUiKeliverComposeGateAWidgetFactory
import dev.keliver.compose.gatea.composeui.GeneratedWidget
import dev.keliver.compose.gatea.composeui.samples.native.ItemDetail
import dev.keliver.compose.gatea.composeui.samples.native.SampleCard
import dev.keliver.compose.gatea.composeui.samples.native.SampleOrders
import dev.keliver.compose.gatea.protocol.host.KeliverComposeGateAHostProtocol
import dev.keliver.compose.gatea.sample.GateBSampleData
import dev.keliver.compose.gatea.sample.GateBSamples
import dev.keliver.compose.gatea.sample.SAMPLE_CARD
import dev.keliver.compose.gatea.sample.SAMPLE_ITEM
import dev.keliver.compose.gatea.widget.GenColumn1
import dev.keliver.compose.gatea.widget.KeliverComposeGateAWidgetFactory
import dev.keliver.compose.gatea.widget.KeliverComposeGateAWidgetSystem
import dev.keliver.layout.composeui.ComposeUiRedwoodLayoutWidgetFactory
import dev.keliver.lazylayout.composeui.ComposeUiRedwoodLazyLayoutWidgetFactory
import dev.keliver.leaks.LeakDetector
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetFactory
import dev.keliver.treehouse.EventListener
import dev.keliver.treehouse.MemoryStateStore
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseAppFactory
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.modules.EmptySerializersModule
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSUserDomainMask
import platform.Foundation.addValue
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.lowPowerModeEnabled
import platform.Foundation.thermalState
import platform.QuartzCore.CACurrentMediaTime
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import platform.UIKit.UIViewController
import platform.posix.getenv

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** Set once the sample's root (a Column, in both samples) exists: composed natively, or created by the guest. */
private var contentArrived by mutableStateOf(false)

internal fun sampleViewController(launch: LaunchArguments): UIViewController = ComposeUIViewController {
  // GATEB_CAPTURE=<file name>: record the window's content into a layer and, once it has settled,
  // write it as a PNG into the app's Documents (a phone has no `simctl io screenshot`). Only the
  // launches that capture pay for the recording.
  val capture = remember { getenv("GATEB_CAPTURE")?.toKString() }
  val layer = if (capture != null) rememberGraphicsLayer() else null
  val recording = if (layer != null) {
    Modifier.drawWithContent {
      layer.record { this@drawWithContent.drawContent() }
      drawLayer(layer)
    }
  } else {
    Modifier
  }
  // The same wrapping in both modes: the theme SameSourceTest uses, a white window, the safe area.
  MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF6750A4), secondaryContainer = Color(0xFFE8DEF8))) {
    Box(Modifier.fillMaxSize().then(recording).background(Color.White).safeDrawingPadding()) {
      if (launch.mode == "guest") GuestSample(launch) else NativeSample(launch.sample)
    }
  }
  FirstFrameProbe(launch.mode)
  if (capture != null && layer != null) CaptureAfterSettling(capture, layer)
}

/** Two seconds after the first frame (as the simulator script waits before its screenshot). */
@Composable
private fun CaptureAfterSettling(name: String, layer: GraphicsLayer) {
  LaunchedEffect(Unit) {
    snapshotFlow { contentArrived }.first { it }
    delay(2_000)
    withFrameNanos {}
    val bitmap = layer.toImageBitmap()
    val png = Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
    if (png == null) {
      log("capture failed: PNG encoding")
      return@LaunchedEffect
    }
    val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    FileSystem.SYSTEM.write("$documents/$name".toPath()) { write(png) }
    log("capture saved $name ${bitmap.width}x${bitmap.height}")
  }
}

@Composable
private fun NativeSample(sample: String) {
  SideEffect { contentArrived = true }
  when (sample) {
    SAMPLE_CARD -> Box(Modifier.fillMaxWidth()) {
      SampleCard(GateBSampleData.CARD_TITLE, GateBSampleData.CARD_QUANTITY, GateBSampleData.tags) {}
    }
    SAMPLE_ITEM -> ItemDetail(onBack = {}, onAdjust = {}, onReorder = {})
    else -> SampleOrders(GateBSampleData.orders, onBack = {}, onOpen = {})
  }
}

@Composable
private fun GuestSample(launch: LaunchArguments) {
  val url = launch.manifestUrl ?: error("guest mode needs a manifest URL (the third launch argument)")
  val app = remember { treehouseApp(url) }
  val widgetSystem = remember {
    KeliverComposeGateAWidgetSystem(
      KeliverComposeGateA = FirstWidgetProbe(ComposeUiKeliverComposeGateAWidgetFactory()),
      KeliverMaterial = ComposeUiKeliverMaterialWidgetFactory(ImageLoader.Builder(PlatformContext.INSTANCE).build()),
      RedwoodLayout = ComposeUiRedwoodLayoutWidgetFactory(),
      RedwoodLazyLayout = ComposeUiRedwoodLazyLayoutWidgetFactory(),
    )
  }
  val source = remember {
    object : TreehouseContentSource<GateBSamples> {
      override fun get(app: GateBSamples) = app.launch(launch.sample)
    }
  }
  TreehouseContent(treehouseApp = app, widgetSystem = widgetSystem, contentSource = source)
}

/**
 * Logs when the host creates the guest's first Column, and marks the content as arrived from inside
 * that Column's first composition: the same point as [NativeSample]'s SideEffect, so both routes'
 * first-frame probes start from a composed root.
 */
private class FirstWidgetProbe(
  private val delegate: KeliverComposeGateAWidgetFactory<@Composable (Modifier) -> Unit>,
) : KeliverComposeGateAWidgetFactory<@Composable (Modifier) -> Unit> by delegate {
  private var created = false

  override fun GenColumn1(): GenColumn1<@Composable (Modifier) -> Unit> {
    val widget = delegate.GenColumn1()
    if (created) return widget
    created = true
    log("first guest widget at ${elapsedMs()} ms")
    // Both interfaces delegated, so the host treats it exactly as the generated widget.
    return object :
      GenColumn1<@Composable (Modifier) -> Unit> by widget,
      GeneratedWidget by (widget as GeneratedWidget) {
      override val value: @Composable (Modifier) -> Unit = { modifier ->
        SideEffect { contentArrived = true }
        widget.value(modifier)
      }
    }
  }
}

/**
 * First-frame time: from main() to the start of the second frame after the one in which the
 * sample's root was first composed (so that frame has been drawn). Both routes mark arrival with
 * a SideEffect in the root's first composition.
 */
@Composable
private fun FirstFrameProbe(mode: String) {
  LaunchedEffect(Unit) {
    snapshotFlow { contentArrived }.first { it }
    withFrameNanos {}
    withFrameNanos {}
    log("first-frame mode=$mode ms=${elapsedMs()}")
    // The device's state for the record, after the measurement (a simulator reports level -1).
    val device = UIDevice.currentDevice
    device.batteryMonitoringEnabled = true
    val state = when (device.batteryState) {
      UIDeviceBatteryState.UIDeviceBatteryStateCharging -> "charging"
      UIDeviceBatteryState.UIDeviceBatteryStateFull -> "full"
      UIDeviceBatteryState.UIDeviceBatteryStateUnplugged -> "unplugged"
      else -> "unknown"
    }
    val thermal = when (NSProcessInfo.processInfo.thermalState) {
      NSProcessInfoThermalState.NSProcessInfoThermalStateNominal -> "nominal"
      NSProcessInfoThermalState.NSProcessInfoThermalStateFair -> "fair"
      NSProcessInfoThermalState.NSProcessInfoThermalStateSerious -> "serious"
      NSProcessInfoThermalState.NSProcessInfoThermalStateCritical -> "critical"
      else -> "unknown"
    }
    log("device battery=${(device.batteryLevel * 100).toInt()} state=$state thermal=$thermal lowPower=${NSProcessInfo.processInfo.lowPowerModeEnabled}")
  }
}

private fun elapsedMs(): Long = ((CACurrentMediaTime() - startedAt) * 1000).toLong()

private fun treehouseApp(manifestUrl: String): TreehouseApp<GateBSamples> {
  val factory = TreehouseAppFactory(
    httpClient = IosZiplineHttpClient(),
    manifestVerifier = ManifestVerifier.Builder()
      .addEd25519(name = "gateb-ed25519", trustedKey = GATEB_PUBLIC_KEY_HEX.decodeHex())
      .build(),
    embeddedFileSystem = null,
    embeddedDir = null,
    cacheName = "gateb",
    cacheMaxSizeInBytes = 20L * 1024L * 1024L,
    concurrentDownloads = 4,
    stateStore = MemoryStateStore(),
    leakDetector = LeakDetector.none(),
    hostProtocolFactory = KeliverComposeGateAHostProtocol.Factory,
  )
  val spec = object : TreehouseApp.Spec<GateBSamples>() {
    override val name = "gateb"
    override val manifestUrl = flowOf(manifestUrl)
    override val serializersModule = EmptySerializersModule()
    override suspend fun bindServices(treehouseApp: TreehouseApp<GateBSamples>, zipline: Zipline) = Unit
    override fun create(zipline: Zipline): GateBSamples = zipline.take("GateBSamples")
  }
  return factory.create(appScope = appScope, spec = spec, eventListenerFactory = Events)
}

private object Events : EventListener.Factory {
  override fun create(app: TreehouseApp<*>, manifestUrl: String?): EventListener = object : EventListener() {
    override fun manifestVerified(manifest: ZiplineManifest, verifiedKey: String) =
      log("manifestVerified key=$verifiedKey at ${elapsedMs()} ms")
    override fun codeLoadSuccess(manifest: ZiplineManifest, zipline: Zipline, startValue: Any?) =
      log("codeLoadSuccess at ${elapsedMs()} ms")
    override fun codeLoadFailed(exception: Exception, startValue: Any?) =
      log("codeLoadFailed: $exception")
    override fun manifestParseFailed(exception: Exception) = log("manifestParseFailed: $exception")
    override fun uncaughtException(exception: Throwable) = log("uncaughtException: $exception")
    override fun unknownWidget(tag: dev.keliver.protocol.WidgetTag) = log("unknownWidget $tag")
  }

  override fun close() = Unit
}

/** NSURLSession-backed Zipline HTTP client (as portal-device-ios), bypassing the URL cache. */
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
