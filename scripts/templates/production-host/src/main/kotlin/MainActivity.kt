/*
 * This app's production Keliver host. Scaffolded by keliver-new-production-host.sh
 * from Keliver's portal-device-android host, reworked for production:
 *
 * - Production ONLY. Every bundle's manifest must verify against the key in
 *   assets/portal_ed25519.pub; without a valid key nothing is fetched at all.
 *   There is no development path: use the tools bundle's generic development
 *   host for that (it refuses production).
 * - The bundle server and the API base are build settings (gradle.properties),
 *   not emulator addresses.
 * - HostHttp is real HTTP to YOUR API base, bound only when one is configured.
 * - The Treehouse app is created only once a manifest URL is known, so there is
 *   no load attempt on an empty URL (Keliver's U28). It starts at once from the
 *   last manifest that loaded — verified again from Zipline's cache, so the app
 *   works offline — and moves to the newest one when the lookup answers.
 * - The manifest must come from the bundle server's own origin.
 *
 * Not protected: rollback. Any bundle signed by this key is accepted, including
 * an older one, so whoever controls the bundle server (or, over http, the path
 * to it) can serve a previous signed version.
 */
@file:OptIn(dev.keliver.leaks.RedwoodLeakApi::class)

package @@PACKAGE@@

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.asZiplineHttpClient
import coil3.ImageLoader
import dev.keliver.http.HostHttpProvider
import dev.keliver.leaks.LeakDetector
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetSystem
import dev.keliver.material.protocol.host.KeliverMaterialHostProtocol
import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.treehouse.EventListener
import dev.keliver.treehouse.MemoryStateStore
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseAppFactory
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.EmptySerializersModule
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeHex

private const val TAG = "KeliverHost"
private const val PREFS = "keliver-host"
private const val LAST_GOOD_MANIFEST = "lastGoodManifestUrl"

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Decided BEFORE any fetch: no valid key, no network, no bundle.
    val publicKeyHex = runCatching {
      assets.open("portal_ed25519.pub").bufferedReader().use { it.readText() }
    }.getOrNull()
    val trust = decideProductionTrust(publicKeyHex)
    if (trust is ProductionTrust.Refused) {
      Log.e(TAG, "refusing to load: ${trust.message}")
      setContent { MessageScreen("Refusing to load", trust.message) }
      return
    }
    trust as ProductionTrust.Verified
    Log.d(TAG, "verifying manifests with portal-ed25519 ${trust.publicKeyHex.take(8)}…")
    val verifier = ManifestVerifier.Builder()
      .addEd25519("portal-ed25519", trust.publicKeyHex.decodeHex())
      .build()

    val okhttp = OkHttpClient()
    val server = BuildConfig.KELIVER_BUNDLE_SERVER.toHttpUrlOrNull()
    if (server == null) {
      setContent { MessageScreen("Refusing to load", "The bundle server '${BuildConfig.KELIVER_BUNDLE_SERVER}' is not a URL.") }
      return
    }
    val apiBase = BuildConfig.KELIVER_API_BASE_URL.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()
    // Advertise only what this host really provides: the bundle lookup uses it
    // to pick a compatible bundle.
    val capabilities = buildList {
      add(dev.keliver.portal.sql.HOST_SQL_CAPABILITY)
      if (apiBase != null) add(dev.keliver.capabilities.HOST_HTTP_CAPABILITY)
    }

    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
    val lastGood = prefs.getString(LAST_GOOD_MANIFEST, null)
    var manifestFlow: MutableStateFlow<String>? = null
    if (lastGood != null) {
      Log.d(TAG, "starting from the last bundle that loaded: $lastGood")
      manifestFlow = startTreehouse(verifier, okhttp, apiBase, lastGood)
    } else {
      setContent { MessageScreen("Loading", "Looking up the latest bundle…") }
    }
    lifecycleScope.launch {
      val latest = withContext(Dispatchers.IO) { latestManifestUrl(okhttp, server, capabilities) }
      when {
        latest == null && manifestFlow == null ->
          setContent { MessageScreen("No bundle", "No compatible bundle at $server, and none loaded before.") }
        latest == null -> Log.d(TAG, "lookup failed; staying on the last bundle that loaded")
        manifestFlow == null -> {
          Log.d(TAG, "loading $latest")
          manifestFlow = startTreehouse(verifier, okhttp, apiBase, latest)
        }
        manifestFlow!!.value != latest -> {
          Log.d(TAG, "loading $latest")
          manifestFlow!!.value = latest
        }
      }
    }
  }

  /** The newest compatible bundle's manifest URL, on the bundle server's own origin, or null. */
  private fun latestManifestUrl(okhttp: OkHttpClient, server: HttpUrl, capabilities: List<String>): String? = runCatching {
    val lookup = server.newBuilder().addPathSegments("bundles/latest")
      .addQueryParameter("widgetVersion", "1")
      .addQueryParameter("caps", capabilities.joinToString(","))
      .build()
    okhttp.newCall(Request.Builder().url(lookup).build()).execute().use { response ->
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        Log.e(TAG, "bundle lookup: HTTP ${response.code} ($body)")
        return@runCatching null
      }
      val path = Json.parseToJsonElement(body).jsonObject["manifestUrl"]?.jsonPrimitive?.content
      if (path == null) {
        Log.e(TAG, "no compatible bundle ($body)")
        return@runCatching null
      }
      val url = server.resolve(path)
      if (url == null || url.scheme != server.scheme || url.host != server.host || url.port != server.port) {
        Log.e(TAG, "refusing a manifest URL off the bundle server's origin: $path")
        return@runCatching null
      }
      url.toString()
    }
  }.onFailure { Log.e(TAG, "bundle lookup failed", it) }.getOrNull()

  private fun startTreehouse(
    verifier: ManifestVerifier,
    okhttp: OkHttpClient,
    apiBase: HttpUrl?,
    manifestUrl: String,
  ): MutableStateFlow<String> {
    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
    val flow = MutableStateFlow(manifestUrl)
    val factory = TreehouseAppFactory(
      context = applicationContext,
      httpClient = okhttp.asZiplineHttpClient(),
      manifestVerifier = verifier,
      embeddedFileSystem = null,
      embeddedDir = null,
      cacheName = "keliver-production",
      cacheMaxSizeInBytes = 50L * 1024L * 1024L,
      concurrentDownloads = 4,
      stateStore = MemoryStateStore(),
      leakDetector = LeakDetector.none(),
      hostProtocolFactory = KeliverMaterialHostProtocol.Factory,
    )
    val spec = object : TreehouseApp.Spec<PortalPresenter>() {
      override val name = "keliver-production"
      override val manifestUrl = flow
      override val serializersModule = EmptySerializersModule()

      override suspend fun bindServices(treehouseApp: TreehouseApp<PortalPresenter>, zipline: Zipline) {
        zipline.bind<HostSqlDriver>("HostSqlDriver", AndroidSqlHost(applicationContext))
        if (apiBase != null) zipline.bind<HostHttpProvider>("HostHttp", OkHttpHostHttp(okhttp, apiBase))
      }

      override fun create(zipline: Zipline): PortalPresenter = zipline.take("PortalPresenter")
    }
    val app = factory.create(
      appScope = lifecycleScope,
      spec = spec,
      // Remember a manifest only once it has loaded — that is, verified.
      eventListenerFactory = LoggingEventListenerFactory { url ->
        prefs.edit().putString(LAST_GOOD_MANIFEST, url).apply()
      },
    )
    setContent {
      MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          val widgetSystem = remember {
            ComposeUiKeliverMaterialWidgetSystem(ImageLoader.Builder(applicationContext).build())
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
    return flow
  }
}

private class LoggingEventListenerFactory(private val onLoaded: (String) -> Unit) : EventListener.Factory {
  override fun create(app: TreehouseApp<*>, manifestUrl: String?): EventListener = LoggingEventListener(manifestUrl, onLoaded)
  override fun close() {}
}

private class LoggingEventListener(
  private val manifestUrl: String?,
  private val onLoaded: (String) -> Unit,
) : EventListener() {
  override fun codeLoadSuccess(manifest: ZiplineManifest, zipline: Zipline, startValue: Any?) {
    Log.d(TAG, "codeLoadSuccess modules=${manifest.modules.keys.size}")
    manifestUrl?.let(onLoaded)
  }
  override fun codeLoadFailed(exception: Exception, startValue: Any?) {
    Log.e(TAG, "codeLoadFailed: ${exception.message}", exception)
  }
  override fun uncaughtException(exception: Throwable) {
    Log.e(TAG, "uncaughtException: ${exception.message}", exception)
  }
}

@Composable
private fun MessageScreen(title: String, message: String) {
  MaterialTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
      ) {
        Text(text = title, style = MaterialTheme.typography.h6)
        Spacer(Modifier.height(12.dp))
        Text(text = message)
      }
    }
  }
}
