/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(dev.keliver.leaks.RedwoodLeakApi::class)

package dev.keliver.sample.host.gateb

import android.content.res.AssetManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.cash.zipline.Call
import app.cash.zipline.CallResult
import app.cash.zipline.Zipline
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.ZiplineHttpClient
import coil3.ImageLoader
import dev.keliver.compose.gatea.composeui.keliverComposeGateAWidgetSystem
import dev.keliver.compose.gatea.protocol.host.KeliverComposeGateAHostProtocol
import dev.keliver.leaks.LeakDetector
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetSystem
import dev.keliver.sample.gateb.GatebAppService
import dev.keliver.sample.gateb.nativecopy.AnimationBench as NativeAnimationBench
import dev.keliver.treehouse.EventListener
import dev.keliver.treehouse.MemoryStateStore
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseAppFactory
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.modules.EmptySerializersModule
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString

/**
 * Gate B-e's route (prototype builds only; docs/GATE_B_RESULTS.md): the animation benchmark screen,
 * plain Compose written once, in one of three forms chosen by the `mode` extra:
 *
 * - `host`: the guest's screen against the generated API, `animate*AsState` as animated references
 *   (the host animates);
 * - `guest`: the same guest screen with `Animatable` on the guest's frame clock (one change per frame);
 * - `native-host` / `native-guest`: the same file compiled as native Compose in this app.
 *
 * The guest bundle ships in the APK's assets (no server), signed with the sample's demo key. Every
 * second the route logs how many change batches and changes the guest sent (tag `KeliverGateB`).
 */
class GatebActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val mode = intent.getStringExtra("mode") ?: "host"
    Log.d(TAG, "mode=$mode")
    if (mode.startsWith("native-")) {
      setContent {
        MaterialTheme {
          Surface(Modifier.fillMaxSize()) { NativeAnimationBench(mode.removePrefix("native-")) }
        }
      }
      return
    }

    val factory = TreehouseAppFactory(
      context = applicationContext,
      httpClient = AssetsHttpClient(assets),
      manifestVerifier = ManifestVerifier.Builder()
        .addEd25519(name = "keliver-sample-ed25519", trustedKey = SAMPLE_SIGNING_PUBLIC_KEY.decodeHex())
        .build(),
      cacheName = "keliver-gateb-zipline",
      stateStore = MemoryStateStore(),
      leakDetector = LeakDetector.none(),
      hostProtocolFactory = KeliverComposeGateAHostProtocol.Factory,
    )
    val spec = object : TreehouseApp.Spec<GatebAppService>() {
      override val name = "keliver-gateb"
      override val manifestUrl = MutableStateFlow(MANIFEST_URL)
      override val serializersModule = EmptySerializersModule()
      override suspend fun bindServices(treehouseApp: TreehouseApp<GatebAppService>, zipline: Zipline) {}
      override fun create(zipline: Zipline): GatebAppService = zipline.take("app")
    }
    val app = factory.create(appScope = lifecycleScope, spec = spec, eventListenerFactory = CountingListener)

    setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          val widgetSystem = remember {
            keliverComposeGateAWidgetSystem(ComposeUiKeliverMaterialWidgetSystem(ImageLoader.Builder(applicationContext).build()))
          }
          val contentSource = remember {
            object : TreehouseContentSource<GatebAppService> {
              override fun get(app: GatebAppService) = app.launch(mode)
            }
          }
          TreehouseContent(treehouseApp = app, widgetSystem = widgetSystem, contentSource = contentSource, modifier = Modifier.fillMaxSize())
        }
      }
    }
  }

  private companion object {
    const val TAG = "KeliverGateB"
    const val MANIFEST_URL = "https://gateb.invalid/manifest.zipline.json"
    const val SAMPLE_SIGNING_PUBLIC_KEY = "98ecb60bb1c418e771378d4451ddfb2f9c440e3ec29cf2f89850ded8f5190cea"
  }
}

/** Serves the bundle from the APK's `assets/gateb/` by file name. */
private class AssetsHttpClient(private val assets: AssetManager) : ZiplineHttpClient() {
  override suspend fun download(url: String, requestHeaders: List<Pair<String, String>>): ByteString = withContext(Dispatchers.IO) {
    assets.open("gateb/" + url.substringAfterLast('/')).use { it.readBytes().toByteString() }
  }
}

/**
 * Counts the guest's change batches (`sendChanges` calls into the host) and the changes in them,
 * and logs both once a second: the "protocol messages per second" of Gate B-e.
 */
private object CountingListener : EventListener.Factory {
  private val batches = AtomicLong()
  private val changes = AtomicLong()
  private var windowStart = System.nanoTime()

  override fun create(app: TreehouseApp<*>, manifestUrl: String?): EventListener = object : EventListener() {
    override fun codeLoadFailed(exception: Exception, startValue: Any?) {
      Log.e("KeliverGateB", "codeLoadFailed", exception)
    }

    override fun uncaughtException(exception: Throwable) {
      Log.e("KeliverGateB", "uncaughtException", exception)
    }

    override fun callStart(call: Call): Any? {
      if (call.function.name.startsWith("sendChanges")) {
        batches.incrementAndGet()
        changes.addAndGet(((call.args.firstOrNull() as? List<*>)?.size ?: 0).toLong())
        val now = System.nanoTime()
        if (now - windowStart >= 1_000_000_000L) {
          val seconds = (now - windowStart) / 1e9
          Log.d("KeliverGateB", "bridge: %.1f batches/s, %.1f changes/s".format(batches.getAndSet(0) / seconds, changes.getAndSet(0) / seconds))
          windowStart = now
        }
      }
      return null
    }

    override fun callEnd(call: Call, result: CallResult, startValue: Any?) {}
  }

  override fun close() {}
}
