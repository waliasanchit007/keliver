/*
 * This app's production Keliver host for iOS. Scaffolded by keliver-new-ios-host.sh;
 * yours to edit from here on. Parity with the Android host that
 * keliver-new-production-host.sh scaffolds:
 *
 * - Production ONLY. Every manifest must verify against the embedded public
 *   key (HostConfig.kt); without a valid key nothing is fetched at all. There
 *   is no development path and no unverified fallback.
 * - The bundle lookup runs FIRST, with a short timeout, and the Treehouse app
 *   is created only once its answer is known (no empty-URL load: U28).
 *   - lookup answers: load the newest compatible bundle; Zipline pins it.
 *   - lookup fails and a bundle loaded before: start from Zipline's cache. In
 *     Zipline 1.22 the cache is read only BEFORE the network and only when the
 *     FreshnessChecker accepts it, so this start, and only this one, accepts
 *     the cached bundle. Zipline verifies its manifest against the key first.
 *   - otherwise: a "No bundle" screen.
 * - One Zipline cache per key, so a key change can't strand the host on a
 *   cache it can't verify.
 * - Manifests are followed only on the bundle server's own origin.
 *
 * Not protected: rollback. Any bundle signed by this key is accepted.
 */
@file:OptIn(dev.keliver.leaks.RedwoodLeakApi::class)

package @@PACKAGE@@

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.DefaultFreshnessCheckerNotFresh
import app.cash.zipline.loader.FreshnessChecker
import app.cash.zipline.loader.ManifestVerifier
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.ktor3.KtorNetworkFetcherFactory
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.EmptySerializersModule
import okio.ByteString.Companion.decodeHex
import platform.Foundation.NSURLSession
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController

private const val TAG = "KeliverHost"
private const val LAST_GOOD_MANIFEST = "keliver.lastGoodManifestUrl"

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private sealed interface HostState {
  data object Loading : HostState
  data class Message(val title: String, val text: String) : HostState
  data class Running(val app: TreehouseApp<PortalPresenter>) : HostState
}

/** The view controller your Xcode app shows (MainViewControllerKt.MainViewController()). */
public fun MainViewController(): UIViewController = ComposeUIViewController {
  var state by remember { mutableStateOf<HostState>(HostState.Loading) }
  LaunchedEffect(Unit) { state = startHost() }
  when (val s = state) {
    HostState.Loading -> MessageScreen("Loading", "Looking up the latest bundle…")
    is HostState.Message -> MessageScreen(s.title, s.text)
    is HostState.Running -> {
      val widgetSystem = remember {
        ComposeUiKeliverMaterialWidgetSystem(
          ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { add(KtorNetworkFetcherFactory()) } // images over the network (Ktor, Darwin engine)
            .build(),
        )
      }
      val contentSource = remember {
        object : TreehouseContentSource<PortalPresenter> {
          override fun get(app: PortalPresenter) = app.launch()
        }
      }
      Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        TreehouseContent(treehouseApp = s.app, widgetSystem = widgetSystem, contentSource = contentSource)
      }
    }
  }
}

private suspend fun startHost(): HostState {
  // Decided BEFORE any fetch: no valid key, no network, no bundle.
  val trust = decideProductionTrust(PORTAL_PUBLIC_KEY_HEX)
  if (trust is ProductionTrust.Refused) {
    log("refusing to load: ${trust.message}")
    return HostState.Message("Refusing to load", trust.message)
  }
  trust as ProductionTrust.Verified
  log("verifying manifests with portal-ed25519 ${trust.publicKeyHex.take(8)}…")
  val server = BundleServer.parse(BUNDLE_SERVER)
    ?: return HostState.Message("Refusing to load", "The bundle server '$BUNDLE_SERVER' is not an http(s) URL.")
  val apiBase = API_BASE_URL.takeIf { it.isNotBlank() }
  // Advertise only what this host really provides: the lookup picks a bundle that needs no more.
  val capabilities = buildList {
    add(dev.keliver.portal.sql.HOST_SQL_CAPABILITY)
    if (apiBase != null) add(dev.keliver.capabilities.HOST_HTTP_CAPABILITY)
  }
  // Set once a bundle has loaded from the network; only on the CURRENT server's origin.
  val lastGood = NSUserDefaults.standardUserDefaults.stringForKey(LAST_GOOD_MANIFEST)?.takeIf { server.owns(it) }
  val latest = latestManifestUrl(server, capabilities)
  return when {
    latest != null -> {
      log("loading $latest")
      HostState.Running(createApp(trust, latest, DefaultFreshnessCheckerNotFresh, apiBase))
    }
    lastGood != null -> {
      log("lookup failed; starting from the cached bundle (last loaded from $lastGood)")
      HostState.Running(createApp(trust, lastGood, AcceptCachedBundle, apiBase))
    }
    else -> HostState.Message("No bundle", "No compatible bundle at ${server.base}, and none loaded before.")
  }
}

/** The newest compatible bundle's manifest URL, on the bundle server's own origin, or null. */
private suspend fun latestManifestUrl(server: BundleServer, capabilities: List<String>): String? = runCatching {
  val lookup = "${server.base}/bundles/latest?widgetVersion=1&caps=${percentEncode(capabilities.joinToString(","))}"
  // Short: offline, this decides how long the app waits before it starts from the cache.
  val (status, body, _) = send(NSURLSession.sharedSession, getRequest(lookup, timeoutSeconds = 10.0))
  if (status !in 200 until 300) {
    log("bundle lookup: HTTP $status (${body.utf8()})")
    return@runCatching null
  }
  val path = Json.parseToJsonElement(body.utf8()).jsonObject["manifestUrl"]?.jsonPrimitive?.content
  if (path == null) {
    log("no compatible bundle (${body.utf8()})")
    return@runCatching null
  }
  server.resolveSameOrigin(path) ?: run {
    log("refusing a manifest URL off the bundle server's origin: $path")
    null
  }
}.onFailure { log("bundle lookup failed: ${it.message}") }.getOrNull()

private fun createApp(
  trust: ProductionTrust.Verified,
  manifestUrl: String,
  freshness: FreshnessChecker,
  apiBase: String?,
): TreehouseApp<PortalPresenter> {
  val verifier = ManifestVerifier.Builder()
    .addEd25519("portal-ed25519", trust.publicKeyHex.decodeHex())
    .build()
  val factory = TreehouseAppFactory(
    httpClient = NSURLSessionZiplineHttpClient(),
    manifestVerifier = verifier,
    embeddedFileSystem = null,
    embeddedDir = null,
    // One cache per key: Zipline verifies the pinned manifest before the
    // network and throws if that fails, so a shared cache would strand a host
    // whose key changed.
    cacheName = "keliver-production-${trust.publicKeyHex.lowercase().take(16)}",
    cacheMaxSizeInBytes = 50L * 1024L * 1024L,
    concurrentDownloads = 4,
    stateStore = MemoryStateStore(),
    leakDetector = LeakDetector.none(),
    hostProtocolFactory = KeliverMaterialHostProtocol.Factory,
  )
  val spec = object : TreehouseApp.Spec<PortalPresenter>() {
    override val name = "keliver-production"
    override val manifestUrl = MutableStateFlow(manifestUrl)
    override val serializersModule = EmptySerializersModule()
    override val freshnessChecker = freshness

    override suspend fun bindServices(treehouseApp: TreehouseApp<PortalPresenter>, zipline: Zipline) {
      zipline.bind<HostSqlDriver>("HostSqlDriver", IosSqlHost())
      if (apiBase != null) zipline.bind<HostHttpProvider>("HostHttp", NSURLSessionHostHttp(apiBase))
    }

    override fun create(zipline: Zipline): PortalPresenter = zipline.take("PortalPresenter")
  }
  return factory.create(
    appScope = appScope,
    spec = spec,
    // Remember a manifest URL only once code has loaded for it from the
    // network. A load from the cache reports no URL, so it changes nothing.
    eventListenerFactory = LoggingEventListenerFactory { url ->
      NSUserDefaults.standardUserDefaults.setObject(url, forKey = LAST_GOOD_MANIFEST)
    },
  )
}

/** Used only when the lookup failed: accept the bundle Zipline pinned, whatever its age. */
private object AcceptCachedBundle : FreshnessChecker {
  override fun isFresh(manifest: ZiplineManifest, freshAtEpochMs: Long) = true
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
    log("codeLoadSuccess modules=${manifest.modules.keys.size}")
    manifestUrl?.let(onLoaded)
  }
  override fun codeLoadFailed(exception: Exception, startValue: Any?) =
    log("codeLoadFailed: ${exception.message}")
  override fun uncaughtException(exception: Throwable) =
    log("uncaughtException: ${exception.message}")
}

/** stdout, which `xcrun simctl launch --console-pty` captures. */
private fun log(message: String) = println("$TAG: $message")

@Composable
private fun MessageScreen(title: String, message: String) {
  Column(
    modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    BasicText(title)
    BasicText(message, modifier = Modifier.padding(top = 12.dp))
  }
}
