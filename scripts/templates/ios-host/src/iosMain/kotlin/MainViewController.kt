/*
 * This app's production Keliver host for iOS. Scaffolded by keliver-new-ios-host.sh;
 * yours to edit from here on. Parity with the Android host that
 * keliver-new-production-host.sh scaffolds:
 *
 * - Production ONLY. Every manifest must verify against the embedded public
 *   key (HostConfig.kt); without a valid key nothing is fetched at all. There
 *   is no development path and no unverified fallback.
 * - The bundle lookup runs FIRST, with a short timeout, and the Treehouse app
 *   is created only once its answer is known (no empty-URL load: U28). It
 *   reads <server>/bundles/index.json (BundleIndex.kt), which keliver-publish
 *   writes for any static server or CDN and the relay serves; only on a 404 for
 *   it (a relay from tools 0.3.7 or earlier) does it ask /bundles/latest.
 *   - lookup answers: load the newest compatible bundle, its manifest held to
 *     the sha256 the index gives; Zipline pins it.
 *   - lookup fails and a bundle loaded before: start from Zipline's cache. In
 *     Zipline 1.22 the cache is read only BEFORE the network and only when the
 *     FreshnessChecker accepts it, so this start, and only this one, accepts
 *     the cached bundle. Zipline verifies its manifest against the key first.
 *   - otherwise: a "No bundle" screen.
 * - One Zipline cache per key, so a key change can't strand the host on a
 *   cache it can't verify.
 * - Manifest URLs are followed only on the bundle server's own origin. (Zipline's
 *   downloads follow HTTP redirects, as on Android.)
 *
 * Rollback protection (W4, BundleIndex.kt): the host keeps the highest signed
 * sequence it has run for this key and refuses a manifest below it, or one
 * without a sequence once it has run a sequenced one, from the network and from
 * the cache. The floor rises only after a load succeeded. Not protected: a
 * reinstall resets it.
 *
 * Lifecycle (W2): `Keliver` is the host, one per process. It looks up and loads
 * once (start() is idempotent); every view controller it makes only observes
 * its state, so a second screen, or a screen shown again, never repeats the
 * lookup or the load. Once a bundle is loading, it is the one this process
 * runs: a newer bundle is picked up on the next process start. A start that
 * created nothing ("No bundle") is retried by the next screen; a load that
 * fails falls back to the cached last good bundle, or shows "Bundle did not
 * load" until the next process start.
 *
 * Updates (W5): checkForUpdate() looks up again and, after a network start,
 * applies a newer bundle in place (Zipline loads it while the old code runs; a
 * failure leaves the old one). With UPDATES = "on-resume" in HostConfig.kt the
 * host does that each time the app returns to the foreground.
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.EmptySerializersModule
import okio.ByteString.Companion.decodeHex
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSURLSession
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIViewController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val TAG = "KeliverHost"
/** One Zipline cache per key, and one rollback floor and last-good URL per key with it. */
private fun cacheName(trust: ProductionTrust.Verified) = "keliver-production-${trust.publicKeyHex.lowercase().take(16)}"
/** The last manifest URL that loaded from the network, per key: it says that key's cache holds a bundle. */
private fun lastGoodKey(trust: ProductionTrust.Verified) = "keliver.lastGoodManifestUrl-${cacheName(trust)}"
/** The highest signed sequence this host has run, per key (the rollback floor). */
private fun floorKey(trust: ProductionTrust.Verified) = "keliver.highestSequence-${cacheName(trust)}"
/** This install's random id, for staged rollouts (W4.5): made once, kept here, never sent anywhere. */
private const val INSTALL_ID = "keliver.installId"
private fun installId(): String {
  val defaults = NSUserDefaults.standardUserDefaults
  return defaults.stringForKey(INSTALL_ID) ?: NSUUID().UUIDString.also { defaults.setObject(it, forKey = INSTALL_ID) }
}
/** This build's CFBundleVersion (CURRENT_PROJECT_VERSION), for host-version gates; null unless an integer. */
private fun hostVersion(): Long? = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toLongOrNull()

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private sealed interface HostState {
  data object Loading : HostState
  /** [retry]: nothing was created, so the next start() may look again. */
  data class Message(val title: String, val text: String, val retry: Boolean = false) : HostState
  data class Running(val app: TreehouseApp<PortalPresenter>) : HostState
}

/** The bundle a host runs (W5): its signed sequence (-1: none), and whether it came from Zipline's cache. */
public data class KeliverBundle(val sequence: Long, val fromCache: Boolean)

/** What [Keliver.checkForUpdate] found: "up-to-date", "available" (applying or at the next start) or "failed". */
public data class KeliverUpdateCheck(val outcome: String, val sequence: Long, val applying: Boolean, val reason: String)

/** What happened to the bundle the host runs (W5): "applied", "failed" or "fell-back". */
public data class KeliverUpdateEvent(val kind: String, val sequence: Long, val reason: String)

/** What a running app needs to look up and apply a newer bundle. Main thread only. */
internal class Session(
  val app: TreehouseApp<PortalPresenter>,
  val pinning: ManifestPinningHttpClient,
  val manifestUrls: MutableStateFlow<String>,
  val lookup: LookupContext,
  var fromCache: Boolean,
)
internal class LookupContext(val server: BundleServer, val capabilities: List<String>, val floor: () -> Long)

/**
 * This process's Keliver host (Swift: `Keliver.shared`). It looks up and loads
 * the bundle once; [viewController] makes a screen that shows it.
 */
public object Keliver {
  private val state = MutableStateFlow<HostState>(HostState.Loading)
  private var started = false // read and written on the main thread only
  private var loaded = false
  private var session: Session? = null
  /** The update handed to Zipline and not yet reported back: its index sequence, manifest URL, and when. */
  private class Pending(val sequence: Long, val url: String, val at: TimeSource.Monotonic.ValueTimeMark)
  private var pending: Pending? = null
  /** Manifest URLs that failed to load as updates in this process: not tried again until the next start. */
  private val failedUpdates = mutableSetOf<String>()
  private var lastLookup = TimeSource.Monotonic.markNow()
  private var current: KeliverBundle? = null

  /** The bundle running now, or null before any has loaded (W5). Main thread. */
  public fun currentBundle(): KeliverBundle? = current

  /** Called on the main thread with each update applied or failed, and each fall-back (W5). */
  public var onUpdateEvent: ((KeliverUpdateEvent) -> Unit)? = null

  /** One image loader for every screen. */
  internal val imageLoader: ImageLoader by lazy {
    ImageLoader.Builder(PlatformContext.INSTANCE)
      .components { add(KtorNetworkFetcherFactory()) } // images over the network (Ktor, Darwin engine)
      .build()
  }

  /** Looks up and loads the bundle, once per process; later calls do nothing. From any thread: it runs on the main one. */
  public fun start() {
    if (!NSThread.isMainThread) {
      dispatch_async(dispatch_get_main_queue()) { start() }
      return
    }
    if (started) return
    started = true
    if (UPDATES == "on-resume") {
      NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
        resumed()
      }
    }
    lastLookup = TimeSource.Monotonic.markNow()
    appScope.launch {
      val s = startHost()
      state.value = s
      if (s is HostState.Message && s.retry) started = false
    }
  }

  /** Called on the main thread when a code load succeeded; [url] is null for a load from the cache. */
  internal fun codeLoaded(sequence: Long?, url: String?) {
    loaded = true
    current = KeliverBundle(sequence ?: -1, fromCache = url == null)
    // After a load from the cache Zipline takes no further manifest; after a network load it does.
    session?.fromCache = url == null
    val p = pending
    if (p != null && url == p.url) {
      pending = null
      log("update applied: sequence $sequence")
      onUpdateEvent?.invoke(KeliverUpdateEvent("applied", sequence ?: -1, ""))
    }
  }

  /** Called on the main thread when Zipline found the manifest unchanged: nothing to apply. */
  internal fun codeSkipped() {
    val p = pending ?: return
    pending = null
    log("update skipped: ${p.url} is the running manifest")
  }

  /** Called on the main thread when the running app's session is created. */
  internal fun attach(session: Session) { this.session = session }

  /** Called on the main thread when the host falls back to the cached last good bundle. */
  internal fun fellBack(reason: String) {
    onUpdateEvent?.invoke(KeliverUpdateEvent("fell-back", -1, reason))
  }

  /** Called on the main thread when a load failed after code had run: an update that did not load. */
  internal fun updateFailed(reason: String) {
    val p = pending ?: return
    pending = null
    failedUpdates += p.url // not tried again in this process
    log("update failed: sequence ${p.sequence} did not load ($reason); the running bundle stays")
    onUpdateEvent?.invoke(KeliverUpdateEvent("failed", p.sequence, reason))
  }

  /**
   * Looks up the newest bundle now (W5), with the same index, channel,
   * constraints and floor as the start. With [apply], and when this process
   * started from the network, a newer one loads in place: Zipline loads it while
   * the running code goes on, swaps it in on success and keeps the old one on
   * failure ([onUpdateEvent]). After a start from the cache, a newer bundle
   * applies at the next process start. From any thread: it runs on the main one.
   */
  public suspend fun checkForUpdate(apply: Boolean = true): KeliverUpdateCheck = withContext(Dispatchers.Main) {
    checkOnMain(apply)
  }

  private suspend fun checkOnMain(apply: Boolean): KeliverUpdateCheck {
    val s = session
    val running = current
    if (s == null || running == null) {
      log("update check: no bundle is running yet")
      return KeliverUpdateCheck("failed", -1, false, "no bundle is running yet")
    }
    pending?.let {
      // Zipline reports every load (success, failure, unchanged); one not heard
      // of for two minutes is given up on, so a lost report cannot stop updates.
      if (it.at.elapsedNow() < 120.seconds) return KeliverUpdateCheck("available", it.sequence, true, "")
      log("update check: sequence ${it.sequence} was never reported back; looking again")
      pending = null
    }
    lastLookup = TimeSource.Monotonic.markNow()
    val latest = lookupBundle(s.lookup.server, s.lookup.capabilities, HostFacts(installId(), hostVersion(), s.lookup.floor()))
    pending?.let { return KeliverUpdateCheck("available", it.sequence, true, "") }
    if (latest == null) {
      log("update check: the lookup failed")
      return KeliverUpdateCheck("failed", -1, false, "the lookup failed")
    }
    val sequence = latest.sequence
      ?: return KeliverUpdateCheck("failed", -1, false, "the bundle server serves no bundles/index.json; updates need one")
    // The URL Zipline already has (the running bundle, or the one it is on): it
    // would not see it again, a StateFlow does not repeat a value.
    if ((running.sequence >= 0 && sequence <= running.sequence) || latest.manifestUrl == s.manifestUrls.value) {
      log("update check: up to date (sequence ${running.sequence})")
      return KeliverUpdateCheck("up-to-date", running.sequence, false, "")
    }
    if (latest.manifestUrl in failedUpdates) {
      log("update check: sequence $sequence did not load earlier in this process; it applies at the next start")
      return KeliverUpdateCheck("available", sequence, false, "")
    }
    if (!apply || s.fromCache) {
      log("update check: sequence $sequence is available (running ${running.sequence}); it applies at the next start")
      return KeliverUpdateCheck("available", sequence, false, "")
    }
    log("update check: sequence $sequence is available (running ${running.sequence}); applying ${latest.manifestUrl}")
    pending = Pending(sequence, latest.manifestUrl, TimeSource.Monotonic.markNow())
    s.pinning.pin(latest.manifestUrl, latest.manifestSha256)
    s.manifestUrls.value = latest.manifestUrl
    return KeliverUpdateCheck("available", sequence, true, "")
  }

  /** With UPDATES = "on-resume": check and apply, at most every 30 s (the host observes the foreground itself). */
  private fun resumed() {
    val since = lastLookup.elapsedNow()
    if (since < 30.seconds) {
      log("update check skipped: the last lookup was ${since.inWholeSeconds} s ago (at most one every 30 s)")
      return
    }
    appScope.launch { checkForUpdate() }
  }

  /** Whether any code has loaded in this process (main thread). */
  internal fun hasLoaded(): Boolean = loaded

  /** Called on the main thread when a code load failed: before any code ran, say so instead of a blank screen. */
  internal fun codeLoadFailed(reason: String) {
    if (!loaded) state.value = HostState.Message("Bundle did not load", reason.take(300))
  }

  /**
   * A screen showing the host: its messages while it looks up and loads, then
   * the guest's screen. [safeArea] pads it to the safe area; turn it off when
   * the containing layout already does.
   */
  public fun viewController(safeArea: Boolean = true): UIViewController = ComposeUIViewController {
    LaunchedEffect(Unit) { start() }
    val current by state.collectAsState()
    val modifier = if (safeArea) Modifier.fillMaxSize().safeDrawingPadding() else Modifier.fillMaxSize()
    when (val s = current) {
      HostState.Loading -> MessageScreen("Loading", "Looking up the latest bundle…", safeArea)
      is HostState.Message -> MessageScreen(s.title, s.text, safeArea)
      is HostState.Running -> {
        val widgetSystem = remember { ComposeUiKeliverMaterialWidgetSystem(imageLoader) }
        val contentSource = remember {
          object : TreehouseContentSource<PortalPresenter> {
            override fun get(app: PortalPresenter) = app.launch()
          }
        }
        Box(modifier) {
          TreehouseContent(treehouseApp = s.app, widgetSystem = widgetSystem, contentSource = contentSource)
        }
      }
    }
  }
}

/** The view controller your Xcode app shows (MainViewControllerKt.MainViewController()): Keliver's screen. */
public fun MainViewController(): UIViewController = Keliver.viewController()

private suspend fun startHost(): HostState {
  // Decided BEFORE any fetch: no valid key, no network, no bundle.
  val trust = decideProductionTrust(PORTAL_PUBLIC_KEY_HEX)
  if (trust is ProductionTrust.Refused) {
    log("refusing to load: ${trust.message}")
    return HostState.Message("Refusing to load", trust.message)
  }
  trust as ProductionTrust.Verified
  log("verifying manifests with portal-ed25519 ${trust.publicKeyHex.take(8)}…; updates $UPDATES")
  val server = BundleServer.parse(BUNDLE_SERVER)
    ?: return HostState.Message("Refusing to load", "The bundle server '$BUNDLE_SERVER' is not an http(s) URL.")
  val apiBase = API_BASE_URL.takeIf { it.isNotBlank() }
  // Advertise only what this host really provides: the lookup picks a bundle that needs no more.
  val capabilities = buildList {
    add(dev.keliver.portal.sql.HOST_SQL_CAPABILITY)
    if (apiBase != null) add(dev.keliver.capabilities.HOST_HTTP_CAPABILITY)
  }
  // Set once a bundle has loaded from the network; only on the CURRENT server's origin.
  val lastGood = NSUserDefaults.standardUserDefaults.stringForKey(lastGoodKey(trust))?.takeIf { server.owns(it) }
  // Read when each manifest arrives, not once here: a restart after a crash must
  // see a floor raised since the host started.
  val floor = { NSUserDefaults.standardUserDefaults.integerForKey(floorKey(trust)) }
  // What the index's constraints are checked against (W4.5).
  val latest = lookupBundle(server, capabilities, HostFacts(installId(), hostVersion(), floor()))
  val lookup = LookupContext(server, capabilities, floor)
  return when {
    latest != null -> {
      log("loading ${latest.manifestUrl} (${latest.source}); rollback floor ${floor()}")
      // A failed load falls back to the cached last good bundle, if there is one (W5).
      HostState.Running(createApp(trust, latest.manifestUrl, latest.manifestSha256, lookup, DefaultFreshnessCheckerNotFresh, apiBase, fallback = lastGood))
    }
    lastGood != null -> {
      log("lookup failed; starting from the cached bundle (last loaded from $lastGood); rollback floor ${floor()}")
      HostState.Running(createApp(trust, lastGood, null, lookup, AcceptCachedBundle(floor), apiBase, fallback = null))
    }
    else -> HostState.Message("No bundle", "No compatible bundle at ${server.base}, and none loaded before.", retry = true)
  }
}

/** The bundle to load: its manifest URL, the sha256 the index holds it to (null from the relay's legacy lookup), and where it came from. */
private class Lookup(val manifestUrl: String, val manifestSha256: String?, val source: String, val sequence: Long? = null)

/**
 * The newest compatible bundle on the bundle server, or null. Reads
 * bundles/index.json; on a 404 for it, asks the relay's bundles/latest. Either
 * way the manifest must be on the bundle server's own origin.
 */
private suspend fun lookupBundle(server: BundleServer, capabilities: List<String>, facts: HostFacts): Lookup? = runCatching {
  // Short: offline, this decides how long the app waits before it starts from the cache.
  val (status, body, _) = send(NSURLSession.sharedSession, getRequest("${server.base}/bundles/index.json", timeoutSeconds = 10.0))
  if (status == 404) {
    log("no bundles/index.json at ${server.base}; asking bundles/latest (a relay from tools 0.3.7 or earlier)")
    return@runCatching legacyLatest(server, capabilities)
  }
  if (status !in 200 until 300) {
    log("bundle index: HTTP $status")
    return@runCatching null
  }
  val pick = pickFromIndex(body.utf8(), capabilities, channel = CHANNEL, facts = facts).getOrElse {
    log("bundle index: ${it.message}")
    return@runCatching null
  }
  val url = server.resolveSameOrigin("${server.base}/bundles/${pick.manifestPath}") ?: run {
    log("refusing a manifest URL off the bundle server's origin: ${pick.manifestPath}")
    return@runCatching null
  }
  Lookup(
    url,
    pick.manifestSha256,
    "index sequence ${pick.sequence}, channel ${pick.channel} (host: $CHANNEL), host version ${facts.hostVersion}, manifest sha256 ${pick.manifestSha256.take(12)}…",
    pick.sequence,
  )
}.onFailure { log("bundle lookup failed: ${it.message}") }.getOrNull()

/** The relay's /bundles/latest, for relays that serve no index. */
private suspend fun legacyLatest(server: BundleServer, capabilities: List<String>): Lookup? {
  val lookup = "${server.base}/bundles/latest?widgetVersion=$HOST_WIDGET_VERSION&caps=${percentEncode(capabilities.joinToString(","))}"
  val (status, body, _) = send(NSURLSession.sharedSession, getRequest(lookup, timeoutSeconds = 10.0))
  if (status !in 200 until 300) {
    log("bundle lookup: HTTP $status (${body.utf8()})")
    return null
  }
  val path = Json.parseToJsonElement(body.utf8()).jsonObject["manifestUrl"]?.jsonPrimitive?.content
  if (path == null) {
    log("no compatible bundle (${body.utf8()})")
    return null
  }
  val url = server.resolveSameOrigin(path) ?: run {
    log("refusing a manifest URL off the bundle server's origin: $path")
    return null
  }
  return Lookup(url, null, "relay bundles/latest")
}

private fun createApp(
  trust: ProductionTrust.Verified,
  manifestUrl: String,
  manifestSha256: String?,
  lookup: LookupContext,
  freshness: FreshnessChecker,
  apiBase: String?,
  fallback: String?,
): TreehouseApp<PortalPresenter> {
  val floor = lookup.floor
  val verifier = ManifestVerifier.Builder()
    .addEd25519("portal-ed25519", trust.publicKeyHex.decodeHex())
    .build()
  // The manifest held to the index's sha256 (when there is one) and to the rollback floor.
  val pinning = ManifestPinningHttpClient(NSURLSessionZiplineHttpClient(), manifestUrl, manifestSha256, floor)
  val flow = MutableStateFlow(manifestUrl)
  // One checker for the app's life, switched for the fall-back (W5): Treehouse
  // reads the spec's checker each time it (re)starts its loader.
  val freshnessSwitch = SwitchableFreshness(freshness)
  var fellBack = false
  lateinit var app: TreehouseApp<PortalPresenter>
  val factory = TreehouseAppFactory(
    httpClient = pinning,
    manifestVerifier = verifier,
    embeddedFileSystem = null,
    embeddedDir = null,
    // One cache per key: Zipline verifies the pinned manifest before the
    // network and throws if that fails, so a shared cache would strand a host
    // whose key changed.
    cacheName = cacheName(trust),
    cacheMaxSizeInBytes = 50L * 1024L * 1024L,
    concurrentDownloads = 4,
    stateStore = MemoryStateStore(),
    leakDetector = LeakDetector.none(),
    hostProtocolFactory = KeliverMaterialHostProtocol.Factory,
  )
  // One of each per host, bound into every code load (not one per load: each
  // holds a connection or a session).
  val sql = IosSqlHost()
  val http = apiBase?.let { NSURLSessionHostHttp(it) }
  val spec = object : TreehouseApp.Spec<PortalPresenter>() {
    override val name = "keliver-production"
    override val manifestUrl = flow
    override val serializersModule = EmptySerializersModule()
    override val freshnessChecker: FreshnessChecker = freshnessSwitch

    override suspend fun bindServices(treehouseApp: TreehouseApp<PortalPresenter>, zipline: Zipline) {
      zipline.bind<HostSqlDriver>("HostSqlDriver", sql)
      if (http != null) zipline.bind<HostHttpProvider>("HostHttp", http)
    }

    override fun create(zipline: Zipline): PortalPresenter = zipline.take("PortalPresenter")
  }
  app = factory.create(
    appScope = appScope,
    spec = spec,
    // Remember a manifest URL only once code has loaded for it from the
    // network. A load from the cache reports no URL, so it changes nothing.
    // Raise the rollback floor to the sequence that just ran: Zipline verified this
    // manifest's signature, and so its metadata, before loading it.
    eventListenerFactory = LoggingEventListenerFactory(
      onSuccess = { sequence, url -> appScope.launch { Keliver.codeLoaded(sequence, url) } },
      onSkipped = { appScope.launch { Keliver.codeSkipped() } },
      // Before any code ran: fall back to the cached last good bundle once (W5),
      // as a start with a failed lookup does (the floor holds; Zipline verifies
      // the cached manifest again). Otherwise the host says the load failed.
      onFailed = { reason ->
        appScope.launch {
          if (Keliver.hasLoaded()) {
            Keliver.updateFailed(reason) // the running bundle stays: Zipline only swaps on success
          } else if (fallback != null && !fellBack) {
            fellBack = true
            Keliver.fellBack(reason)
            log("the bundle did not load; falling back to the cached last good bundle (last loaded from $fallback)")
            freshnessSwitch.current = AcceptCachedBundle(floor)
            pinning.pin(fallback, null)
            // stop() then start(): after a failed first load the app is still
            // "starting", which restart() would leave alone.
            app.stop()
            flow.value = fallback
            app.start()
          } else {
            Keliver.codeLoadFailed(reason)
          }
        }
      },
      onLoaded = { url -> NSUserDefaults.standardUserDefaults.setObject(url, forKey = lastGoodKey(trust)) },
      onSequence = { sequence ->
        val defaults = NSUserDefaults.standardUserDefaults
        val current = defaults.integerForKey(floorKey(trust))
        if (sequence > current) {
          defaults.setInteger(sequence, forKey = floorKey(trust))
          log("rollback floor raised: $current -> $sequence")
        }
      },
    ),
  )
  Keliver.attach(Session(app, pinning, flow, lookup, fromCache = freshness is AcceptCachedBundle))
  return app
}

/** The app's one freshness checker, delegating to [current] (W5: switched for the fall-back). */
private class SwitchableFreshness(@Volatile var current: FreshnessChecker) : FreshnessChecker {
  override fun isFresh(manifest: ZiplineManifest, freshAtEpochMs: Long): Boolean = current.isFresh(manifest, freshAtEpochMs)
}

/**
 * Used only when the lookup failed, or a load failed and the host falls back to
 * the last good bundle (W5): accept the bundle Zipline pinned, whatever
 * its age, unless it is below the rollback floor. Zipline hands it over after
 * verifying it. A refused cache is not used; Zipline then fetches the manifest
 * from the network, through the same floor guard (createApp wraps every client).
 */
private class AcceptCachedBundle(private val floor: () -> Long) : FreshnessChecker {
  override fun isFresh(manifest: ZiplineManifest, freshAtEpochMs: Long): Boolean {
    val problem = rollbackProblem(manifestSequence(manifest.metadata), floor()) ?: return true
    log("cached bundle refused: $problem")
    return false
  }
}

private class LoggingEventListenerFactory(
  private val onSuccess: (sequence: Long?, manifestUrl: String?) -> Unit,
  private val onSkipped: () -> Unit,
  private val onFailed: (String) -> Unit,
  private val onLoaded: (String) -> Unit,
  private val onSequence: (Long) -> Unit,
) : EventListener.Factory {
  override fun create(app: TreehouseApp<*>, manifestUrl: String?): EventListener =
    LoggingEventListener(manifestUrl, onSuccess, onSkipped, onFailed, onLoaded, onSequence)
  override fun close() {}
}

private class LoggingEventListener(
  private val manifestUrl: String?,
  private val onSuccess: (sequence: Long?, manifestUrl: String?) -> Unit,
  private val onSkipped: () -> Unit,
  private val onFailed: (String) -> Unit,
  private val onLoaded: (String) -> Unit,
  private val onSequence: (Long) -> Unit,
) : EventListener() {
  override fun codeLoadSuccess(manifest: ZiplineManifest, zipline: Zipline, startValue: Any?) {
    val sequence = manifestSequence(manifest.metadata)
    // A load from the cache reports no manifest URL.
    log("codeLoadSuccess modules=${manifest.modules.keys.size} sequence=${sequence ?: "none"} source=${if (manifestUrl == null) "cache" else "network"}")
    onSuccess(sequence, manifestUrl)
    manifestUrl?.let(onLoaded)
    sequence?.let(onSequence)
  }
  override fun codeLoadSkipped(startValue: Any?) {
    log("codeLoadSkipped: the manifest is unchanged")
    onSkipped()
  }
  override fun codeLoadFailed(exception: Exception, startValue: Any?) {
    log("codeLoadFailed: ${exception.message}")
    onFailed(exception.message ?: exception::class.simpleName ?: "unknown error")
  }
  override fun uncaughtException(exception: Throwable) =
    log("uncaughtException: ${exception.message}")
}

/** stdout, which `xcrun simctl launch --console-pty` captures. */
private fun log(message: String) = println("$TAG: $message")

@Composable
private fun MessageScreen(title: String, message: String, safeArea: Boolean = true) {
  Column(
    modifier = (if (safeArea) Modifier.fillMaxSize().safeDrawingPadding() else Modifier.fillMaxSize()).padding(24.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    BasicText(title)
    BasicText(message, modifier = Modifier.padding(top = 12.dp))
  }
}
