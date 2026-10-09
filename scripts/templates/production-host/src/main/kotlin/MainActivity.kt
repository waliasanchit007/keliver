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
 * - The bundle lookup runs FIRST, with a short timeout, and the Treehouse app is
 *   created only once its answer is known, so there is no load attempt on an
 *   empty URL (Keliver's U28). The lookup reads <server>/bundles/index.json
 *   (BundleIndex.kt), which keliver-publish writes for any static server or
 *   CDN and the relay serves; only when that is a 404 (a relay from tools 0.3.7
 *   or earlier) does it ask the relay's /bundles/latest instead.
 *   - lookup answers: load the newest compatible bundle from the network, its
 *     manifest held to the sha256 the index gives. Once it has loaded, Zipline
 *     pins it in its cache.
 *   - lookup fails, and a bundle loaded before: start from Zipline's cache. In
 *     Zipline 1.22 the cache is used ONLY before the network and only when the
 *     FreshnessChecker accepts it; there is no fallback after a network failure.
 *     So this start, and only this one, accepts the cached bundle as fresh. That
 *     bundle is the last one that loaded from the network, and its manifest is
 *     verified again against the key before it runs. It stays in use until the
 *     next launch; the app does not move to a newer bundle while it runs.
 *   - otherwise: a "No bundle" screen.
 * - The manifest must come from the bundle server's own origin.
 *
 * Rollback protection (W4, BundleIndex.kt): the host keeps the highest signed
 * sequence it has run for this key and refuses a manifest below it, or one
 * without a sequence once it has run a sequenced one, from the network and from
 * the cache. The floor rises only after a load succeeded. Not protected: a
 * reinstall or "clear data" resets it.
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
import app.cash.zipline.loader.DefaultFreshnessCheckerNotFresh
import app.cash.zipline.loader.FreshnessChecker
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.ZiplineHttpClient
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
import java.util.concurrent.TimeUnit

private const val TAG = "KeliverHost"
private const val PREFS = "keliver-host"
private const val LAST_GOOD_MANIFEST = "lastGoodManifestUrl"
/** The highest signed sequence this host has run, per key (the rollback floor). */
private fun floorKey(cacheName: String) = "highestSequence-$cacheName"
/** The stored floor; a value of the wrong type (only tampering writes one) reads as unreadable, i.e. refuse all. */
private fun android.content.SharedPreferences.floor(cacheName: String): Long =
  runCatching { getLong(floorKey(cacheName), 0L) }.getOrDefault(Long.MAX_VALUE)

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
    // One Zipline cache per key. Zipline verifies its pinned manifest before it
    // tries the network, and throws if that fails; a cache shared across a key
    // change would hold a manifest the new key cannot verify, and no bundle
    // would load, online or not. The old key's cache stays on disk, unused.
    val cacheName = "keliver-production-${trust.publicKeyHex.lowercase().take(16)}"

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
    // Set once a bundle has loaded from the network, so it says Zipline's cache
    // holds one. Only a saved URL on the CURRENT bundle server's origin counts;
    // after an update that moved the server, the old one is ignored.
    val lastGood = prefs.getString(LAST_GOOD_MANIFEST, null)?.takeIf { sameOrigin(it.toHttpUrlOrNull(), server) }
    // Read when each manifest arrives, not once here: a restart after a crash
    // must see a floor raised since this activity started.
    val floor = { prefs.floor(cacheName) }
    setContent { MessageScreen("Loading", "Looking up the latest bundle…") }
    lifecycleScope.launch {
      // Short timeouts: offline, this decides how long the app waits before it
      // starts from the cache.
      val lookupClient = okhttp.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
      val latest = withContext(Dispatchers.IO) { lookupBundle(lookupClient, server, capabilities) }
      when {
        latest != null -> {
          Log.d(TAG, "loading ${latest.manifestUrl} (${latest.source}); rollback floor ${floor()}")
          val http = ManifestPinningHttpClient(okhttp.asZiplineHttpClient(), latest.manifestUrl, latest.manifestSha256, floor)
          startTreehouse(verifier, cacheName, okhttp, http, apiBase, latest.manifestUrl, DefaultFreshnessCheckerNotFresh)
        }
        lastGood != null -> {
          Log.d(TAG, "lookup failed; starting from the cached bundle (last loaded from $lastGood); rollback floor ${floor()}")
          // Guarded too: when the cache is refused or empty, Zipline goes on to fetch
          // lastGood from the network, and a lookup that failed proves nothing about
          // that server (it may have failed the lookup on purpose).
          val http = ManifestPinningHttpClient(okhttp.asZiplineHttpClient(), lastGood, null, floor)
          startTreehouse(verifier, cacheName, okhttp, http, apiBase, lastGood, AcceptCachedBundle(floor))
        }
        else -> setContent { MessageScreen("No bundle", "No compatible bundle at $server, and none loaded before.") }
      }
    }
  }

  /** The bundle to load: its manifest URL, the sha256 the index holds it to (null from the relay's legacy lookup), and where it came from. */
  private class Lookup(val manifestUrl: String, val manifestSha256: String?, val source: String)

  /**
   * The newest compatible bundle on the bundle server, or null. Reads
   * bundles/index.json; on a 404 for it, asks the relay's bundles/latest.
   * Either way the manifest must be on the bundle server's own origin.
   */
  private fun lookupBundle(okhttp: OkHttpClient, server: HttpUrl, capabilities: List<String>): Lookup? = runCatching {
    val indexUrl = server.newBuilder().addPathSegments("bundles/index.json").build()
    val request = Request.Builder().url(indexUrl).header("Cache-Control", "no-cache").build()
    okhttp.newCall(request).execute().use { response ->
      if (response.code == 404) {
        Log.d(TAG, "no bundles/index.json at $server; asking bundles/latest (a relay from tools 0.3.7 or earlier)")
        return@runCatching legacyLatest(okhttp, server, capabilities)
      }
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        Log.e(TAG, "bundle index: HTTP ${response.code}")
        return@runCatching null
      }
      val pick = pickFromIndex(body, capabilities, channel = BuildConfig.KELIVER_CHANNEL).getOrElse {
        Log.e(TAG, "bundle index: ${it.message}")
        return@runCatching null
      }
      val url = server.newBuilder().addPathSegments("bundles/${pick.manifestPath}").build()
      if (!sameOrigin(url, server)) {
        Log.e(TAG, "refusing a manifest URL off the bundle server's origin: ${pick.manifestPath}")
        return@runCatching null
      }
      Lookup(
        url.toString(),
        pick.manifestSha256,
        "index sequence ${pick.sequence}, channel ${pick.channel} (host: ${BuildConfig.KELIVER_CHANNEL}), " +
          "manifest sha256 ${pick.manifestSha256.take(12)}…",
      )
    }
  }.onFailure { Log.e(TAG, "bundle lookup failed", it) }.getOrNull()

  /** The relay's /bundles/latest, for relays that serve no index. */
  private fun legacyLatest(okhttp: OkHttpClient, server: HttpUrl, capabilities: List<String>): Lookup? {
    val lookup = server.newBuilder().addPathSegments("bundles/latest")
      .addQueryParameter("widgetVersion", HOST_WIDGET_VERSION.toString())
      .addQueryParameter("caps", capabilities.joinToString(","))
      .build()
    okhttp.newCall(Request.Builder().url(lookup).build()).execute().use { response ->
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        Log.e(TAG, "bundle lookup: HTTP ${response.code} ($body)")
        return null
      }
      val path = Json.parseToJsonElement(body).jsonObject["manifestUrl"]?.jsonPrimitive?.content
      if (path == null) {
        Log.e(TAG, "no compatible bundle ($body)")
        return null
      }
      val url = server.resolve(path)
      if (!sameOrigin(url, server)) {
        Log.e(TAG, "refusing a manifest URL off the bundle server's origin: $path")
        return null
      }
      return Lookup(url.toString(), null, "relay bundles/latest")
    }
  }

  private fun sameOrigin(url: HttpUrl?, server: HttpUrl): Boolean =
    url != null && url.scheme == server.scheme && url.host == server.host && url.port == server.port

  private fun startTreehouse(
    verifier: ManifestVerifier,
    cacheName: String,
    okhttp: OkHttpClient,
    ziplineHttp: ZiplineHttpClient,
    apiBase: HttpUrl?,
    manifestUrl: String,
    freshness: FreshnessChecker,
  ) {
    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
    val flow = MutableStateFlow(manifestUrl)
    val factory = TreehouseAppFactory(
      context = applicationContext,
      httpClient = ziplineHttp,
      manifestVerifier = verifier,
      embeddedFileSystem = null,
      embeddedDir = null,
      cacheName = cacheName,
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
      override val freshnessChecker = freshness

      override suspend fun bindServices(treehouseApp: TreehouseApp<PortalPresenter>, zipline: Zipline) {
        zipline.bind<HostSqlDriver>("HostSqlDriver", AndroidSqlHost(applicationContext))
        if (apiBase != null) zipline.bind<HostHttpProvider>("HostHttp", OkHttpHostHttp(okhttp, apiBase))
      }

      override fun create(zipline: Zipline): PortalPresenter = zipline.take("PortalPresenter")
    }
    val app = factory.create(
      appScope = lifecycleScope,
      spec = spec,
      // Remember a manifest URL only once code has loaded for it from the
      // network. A load from the cache reports no URL, so it changes nothing.
      // Raise the rollback floor to the sequence that just ran: Zipline verified
      // this manifest's signature, and so its metadata, before loading it.
      eventListenerFactory = LoggingEventListenerFactory(
        onLoaded = { url -> prefs.edit().putString(LAST_GOOD_MANIFEST, url).apply() },
        onSequence = { sequence ->
          val floor = prefs.floor(cacheName)
          if (sequence > floor) {
            // commit(), not apply(): Zipline has already pinned this bundle, and a
            // floor write lost to a kill would let the previous sequence run again.
            prefs.edit().putLong(floorKey(cacheName), sequence).commit()
            Log.d(TAG, "rollback floor raised: $floor -> $sequence")
          }
        },
      ),
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
  }
}

/**
 * Used only when the bundle lookup failed: accept the bundle Zipline pinned in
 * its cache, whatever its age. Zipline still verifies its manifest against the
 * key before loading it. If the cache holds nothing (cleared, or a new key's
 * cache), Zipline goes on to the network, which fails and reports codeLoadFailed.
 */
private class AcceptCachedBundle(private val floor: () -> Long) : FreshnessChecker {
  // The rollback floor holds here too: Zipline hands over the pinned manifest
  // after verifying it. A refused cache is not used; Zipline then fetches the
  // manifest from the network, through the same floor guard.
  override fun isFresh(manifest: ZiplineManifest, freshAtEpochMs: Long): Boolean {
    val problem = rollbackProblem(manifestSequence(manifest.metadata), floor()) ?: return true
    Log.e(TAG, "cached bundle refused: $problem")
    return false
  }
}

private class LoggingEventListenerFactory(
  private val onLoaded: (String) -> Unit,
  private val onSequence: (Long) -> Unit,
) : EventListener.Factory {
  override fun create(app: TreehouseApp<*>, manifestUrl: String?): EventListener =
    LoggingEventListener(manifestUrl, onLoaded, onSequence)
  override fun close() {}
}

private class LoggingEventListener(
  private val manifestUrl: String?,
  private val onLoaded: (String) -> Unit,
  private val onSequence: (Long) -> Unit,
) : EventListener() {
  override fun codeLoadSuccess(manifest: ZiplineManifest, zipline: Zipline, startValue: Any?) {
    val sequence = manifestSequence(manifest.metadata)
    Log.d(TAG, "codeLoadSuccess modules=${manifest.modules.keys.size} sequence=${sequence ?: "none"}")
    manifestUrl?.let(onLoaded)
    sequence?.let(onSequence)
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
