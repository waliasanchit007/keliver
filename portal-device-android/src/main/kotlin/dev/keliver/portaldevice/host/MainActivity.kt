/*
 * spike/keliver-web portal M2 Option X — Android Treehouse HOST.
 * Loads the keliver-material guest bundle (unsigned, from the local serve task),
 * binds HostApi (OkHttp GET of the relay), and mounts TreehouseContent with the
 * published keliver-material renderers. Template: sample/host-android.
 */
@file:OptIn(dev.keliver.leaks.RedwoodLeakApi::class)

package dev.keliver.portaldevice.host

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.asZiplineHttpClient
import coil3.ImageLoader
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.modules.EmptySerializersModule
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "PortalDevice"
// The Zipline dev server (:guest:serveDevelopmentZipline) serves the bundle on 8080;
// 10.0.2.2 is the emulator's alias for the host machine's localhost.
private const val MANIFEST_URL = "http://10.0.2.2:8080/manifest.zipline.json"

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.d(TAG, "onCreate — manifest $MANIFEST_URL")

    val ziplineHttpClient = OkHttpClient().asZiplineHttpClient()
    val okhttp = OkHttpClient()

    val factory = TreehouseAppFactory(
      context = applicationContext,
      httpClient = ziplineHttpClient,
      manifestVerifier = ManifestVerifier.NO_SIGNATURE_CHECKS,
      embeddedFileSystem = null,
      embeddedDir = null,
      cacheName = "portal-device-zipline",
      cacheMaxSizeInBytes = 50L * 1024L * 1024L,
      concurrentDownloads = 4,
      stateStore = MemoryStateStore(),
      leakDetector = LeakDetector.none(),
      hostProtocolFactory = KeliverMaterialHostProtocol.Factory,
    )

    val spec = object : TreehouseApp.Spec<PortalPresenter>() {
      override val name = "portal-device"
      override val manifestUrl = MutableStateFlow(MANIFEST_URL).asStateFlow()
      override val serializersModule = EmptySerializersModule()

      override suspend fun bindServices(treehouseApp: TreehouseApp<PortalPresenter>, zipline: Zipline) {
        zipline.bind<HostApi>("HostApi", RealHostApi(okhttp))
      }

      override fun create(zipline: Zipline): PortalPresenter = zipline.take("PortalPresenter")
    }

    val app = factory.create(
      appScope = lifecycleScope,
      spec = spec,
      eventListenerFactory = LoggingEventListenerFactory,
    )

    setContent {
      MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          val widgetSystem = remember {
            ComposeUiKeliverMaterialWidgetSystem(
              ImageLoader.Builder(applicationContext).build(),
            )
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
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }
  }
}

/** Host impl of the guest's HostApi: does the actual OkHttp GET on Android. */
private class RealHostApi(private val client: OkHttpClient) : HostApi {
  override suspend fun httpCall(url: String): String = withContext(Dispatchers.IO) {
    client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() ?: "{}" }
  }
}

private object LoggingEventListenerFactory : EventListener.Factory {
  override fun create(app: TreehouseApp<*>, manifestUrl: String?): EventListener = LoggingEventListener
  override fun close() {}
}

private object LoggingEventListener : EventListener() {
  override fun codeLoadSuccess(manifest: ZiplineManifest, zipline: Zipline, startValue: Any?) {
    Log.d(TAG, "codeLoadSuccess modules=${manifest.modules.keys.size}")
  }
  override fun codeLoadFailed(exception: Exception, startValue: Any?) {
    Log.e(TAG, "codeLoadFailed: ${exception.message}", exception)
  }
  override fun uncaughtException(exception: Throwable) {
    Log.e(TAG, "uncaughtException: ${exception.message}", exception)
  }
}
