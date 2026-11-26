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
package dev.keliver.compose.size.android

import android.content.res.AssetManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.cash.zipline.Zipline
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.ZiplineHttpClient
import dev.keliver.compose.root.composeui.keliverComposeWidgetSystem
import dev.keliver.compose.root.protocol.host.KeliverComposeHostProtocol
import dev.keliver.compose.size.SizeSamples
import dev.keliver.leaks.LeakDetector
import dev.keliver.treehouse.MemoryStateStore
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseAppFactory
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.modules.EmptySerializersModule
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString

/** The product route: the guest bundle from the APK's assets, on the generated root's renderers. */
internal val guestRoute: GuestRoute? = ProductRoute

private object ProductRoute : GuestRoute {
  @Composable
  override fun Content(activity: ComponentActivity, sample: String) {
    val app = remember { treehouseApp(activity) }
    // No legacy keliver-material system; Coil only in the productCoil flavor (Coil.kt).
    val widgetSystem = remember { keliverComposeWidgetSystem(coil = coilFactory()) }
    val source = remember {
      object : TreehouseContentSource<SizeSamples> {
        override fun get(app: SizeSamples) = app.launch(sample)
      }
    }
    TreehouseContent(treehouseApp = app, widgetSystem = widgetSystem, contentSource = source, modifier = Modifier.fillMaxSize())
  }

  @OptIn(dev.keliver.leaks.RedwoodLeakApi::class)
  private fun treehouseApp(activity: ComponentActivity): TreehouseApp<SizeSamples> {
    val factory = TreehouseAppFactory(
      context = activity.applicationContext,
      httpClient = AssetsHttpClient(activity.assets),
      manifestVerifier = ManifestVerifier.Builder()
        .addEd25519(name = "size-ed25519", trustedKey = SIZE_PUBLIC_KEY_HEX.decodeHex())
        .build(),
      cacheName = "keliver-size",
      stateStore = MemoryStateStore(),
      leakDetector = LeakDetector.none(),
      hostProtocolFactory = KeliverComposeHostProtocol.Factory,
    )
    val spec = object : TreehouseApp.Spec<SizeSamples>() {
      override val name = "keliver-size"
      override val manifestUrl = MutableStateFlow("https://size.invalid/manifest.zipline.json")
      override val serializersModule = EmptySerializersModule()
      override suspend fun bindServices(treehouseApp: TreehouseApp<SizeSamples>, zipline: Zipline) = Unit
      override fun create(zipline: Zipline): SizeSamples = zipline.take("SizeSamples")
    }
    return factory.create(appScope = activity.lifecycleScope, spec = spec)
  }
}

/** Serves the bundle from the APK's `assets/size/` by file name. */
private class AssetsHttpClient(private val assets: AssetManager) : ZiplineHttpClient() {
  override suspend fun download(url: String, requestHeaders: List<Pair<String, String>>): ByteString = withContext(Dispatchers.IO) {
    assets.open("size/${url.substringAfterLast('/')}").use { it.readBytes().toByteString() }
  }
}
