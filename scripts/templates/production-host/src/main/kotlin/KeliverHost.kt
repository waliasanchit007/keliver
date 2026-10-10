/*
 * This app's production Keliver host: everything but the screen. Scaffolded by
 * keliver-new-production-host.sh from Keliver's portal-device-android host,
 * reworked for production. Build ONE per process (the app's Application owns
 * it) and show it with KeliverScreen or KeliverView.
 *
 * - Production ONLY. Every bundle's manifest must verify against the key in
 *   src/main/assets/keliver/portal_ed25519.pub, which the build checks and
 *   compiles into BuildConfig.KELIVER_PUBLIC_KEY_HEX; the host reads that, never
 *   the merged assets (an app's or another library's asset of the same name
 *   would win the merge). Without a valid key nothing is fetched at all.
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
 *
 * Lifecycle (W2): the host owns its coroutine scope, its one Treehouse app and
 * Zipline, its SQL and HTTP hosts and its image loader, for the life of the
 * process. start() is idempotent: the first screen calls it, and the
 * Application may call it earlier to warm up. Screens only observe [state], so
 * a configuration change, or a second screen, never repeats the lookup or the
 * load. Once a bundle is loading, it is the one this process runs: a newer
 * bundle is picked up on the next process start. A start that created nothing
 * ("No bundle": the lookup failed and nothing was cached) is retried by the
 * next screen to call start(). A load that fails shows "Bundle did not load"
 * until the next process start (falling back to the last good bundle is W5).
 */
@file:OptIn(dev.keliver.leaks.RedwoodLeakApi::class)

package @@PACKAGE@@

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.DefaultFreshnessCheckerNotFresh
import app.cash.zipline.loader.FreshnessChecker
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.asZiplineHttpClient
import coil3.ImageLoader
import dev.keliver.http.HostHttpProvider
import dev.keliver.leaks.LeakDetector
import dev.keliver.material.protocol.host.KeliverMaterialHostProtocol
import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.treehouse.EventListener
import dev.keliver.treehouse.MemoryStateStore
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseAppFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

internal const val TAG = "KeliverHost"
private const val PREFS = "keliver-host"
/** The last manifest URL that loaded from the network, per key: it says that key's cache holds a bundle. */
private fun lastGoodKey(cacheName: String) = "lastGoodManifestUrl-$cacheName"
/** One Zipline cache per key, and one rollback floor and last-good URL with it. */
private fun cacheNameFor(publicKeyHex: String) = "keliver-production-${publicKeyHex.lowercase().take(16)}"
/** The highest signed sequence this host has run, per key (the rollback floor). */
private fun floorKey(cacheName: String) = "highestSequence-$cacheName"
/** The stored floor; a value of the wrong type (only tampering writes one) reads as unreadable, i.e. refuse all. */
private fun SharedPreferences.floor(cacheName: String): Long =
  runCatching { getLong(floorKey(cacheName), 0L) }.getOrDefault(Long.MAX_VALUE)
/** This install's random id, for staged rollouts (W4.5): made once, kept here, never sent anywhere. */
private const val INSTALL_ID = "installId"
private fun SharedPreferences.installId(): String =
  runCatching { getString(INSTALL_ID, null) }.getOrNull()
    // One that cannot be stored would re-roll this install's buckets every launch, and it
    // would end up in every partial rollout: use a fixed id instead (one shared bucket).
    ?: java.util.UUID.randomUUID().toString().let { if (edit().putString(INSTALL_ID, it).commit()) it else "unstored" }

/**
 * What a host is built with. [fromBuild] reads this module's build settings and
 * its checked key, both from BuildConfig: use it. A config built by hand skips
 * the build's checks of the key and of https:// in release builds; the key is
 * still decided before anything is fetched.
 * [hostVersion] is what index `minHostVersion`/`maxHostVersion` gates compare
 * with: by default the app's versionCode.
 */
class KeliverConfig(
  val bundleServer: String,
  val publicKeyHex: String?,
  val channel: String = "stable",
  val apiBaseUrl: String? = null,
  val hostVersion: Long? = null,
) {
  companion object {
    fun fromBuild(context: Context): KeliverConfig = KeliverConfig(
      bundleServer = BuildConfig.KELIVER_BUNDLE_SERVER,
      publicKeyHex = BuildConfig.KELIVER_PUBLIC_KEY_HEX.takeIf { it.isNotBlank() },
      channel = BuildConfig.KELIVER_CHANNEL,
      apiBaseUrl = BuildConfig.KELIVER_API_BASE_URL.takeIf { it.isNotBlank() },
    )
  }
}

/** What a screen shows: a message (loading, refused, no bundle), or the running app. */
sealed interface KeliverHostState {
  data class Message(val title: String, val message: String) : KeliverHostState
  class Running internal constructor(internal val app: TreehouseApp<PortalPresenter>) : KeliverHostState
}

class KeliverHost private constructor(context: Context, private val config: KeliverConfig) {
  private val context = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val started = AtomicBoolean(false)
  private val loaded = AtomicBoolean(false)
  private val mutableState = MutableStateFlow<KeliverHostState>(KeliverHostState.Message("Loading", "Looking up the latest bundle…"))

  /** What screens show; they only observe it. */
  val state: StateFlow<KeliverHostState> = mutableState.asStateFlow()

  /** One image loader, one SQL host per host (not per composition, not per code load). */
  internal val imageLoader: ImageLoader by lazy { ImageLoader.Builder(this.context).build() }
  private val sqlHost by lazy { AndroidSqlHost(this.context) }

  /** Looks up and loads the bundle, once per process; later calls do nothing. */
  fun start() {
    if (!started.compareAndSet(false, true)) return
    scope.launch { run() }
  }

  private suspend fun run() {
    // Decided BEFORE any fetch: no valid key, no network, no bundle.
    val trust = decideProductionTrust(config.publicKeyHex)
    if (trust is ProductionTrust.Refused) {
      Log.e(TAG, "refusing to load: ${trust.message}")
      mutableState.value = KeliverHostState.Message("Refusing to load", trust.message)
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
    // (Claimed in create(), so a second host for this key fails there.)
    val cacheName = cacheNameFor(trust.publicKeyHex)

    val okhttp = OkHttpClient()
    val server = config.bundleServer.toHttpUrlOrNull()
    if (server == null) {
      mutableState.value = KeliverHostState.Message("Refusing to load", "The bundle server '${config.bundleServer}' is not a URL.")
      return
    }
    val apiBase = config.apiBaseUrl?.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()
    // Advertise only what this host really provides: the bundle lookup uses it
    // to pick a compatible bundle.
    val capabilities = buildList {
      add(dev.keliver.portal.sql.HOST_SQL_CAPABILITY)
      if (apiBase != null) add(dev.keliver.capabilities.HOST_HTTP_CAPABILITY)
    }

    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // Set once a bundle has loaded from the network, so it says Zipline's cache
    // holds one. Only a saved URL on the CURRENT bundle server's origin counts;
    // after an update that moved the server, the old one is ignored.
    val lastGood = prefs.getString(lastGoodKey(cacheName), null)?.takeIf { sameOrigin(it.toHttpUrlOrNull(), server) }
    // Read when each manifest arrives, not once here: a restart after a crash
    // must see a floor raised since this host started.
    val floor = { prefs.floor(cacheName) }
    // Short timeouts: offline, this decides how long the app waits before it
    // starts from the cache.
    val lookupClient = okhttp.newBuilder()
      .connectTimeout(5, TimeUnit.SECONDS)
      .callTimeout(10, TimeUnit.SECONDS)
      .build()
    // What the index's constraints are checked against (W4.5).
    val facts = HostFacts(prefs.installId(), config.hostVersion ?: appVersionCode(), floor())
    val latest = withContext(Dispatchers.IO) { lookupBundle(lookupClient, server, capabilities, facts) }
    when {
      latest != null -> {
        Log.d(TAG, "loading ${latest.manifestUrl} (${latest.source}); rollback floor ${floor()}")
        val http = ManifestPinningHttpClient(okhttp.asZiplineHttpClient(), latest.manifestUrl, latest.manifestSha256, floor)
        // A failed load falls back to the cached last good bundle, if there is one (W5).
        startTreehouse(prefs, verifier, cacheName, okhttp, http, apiBase, latest.manifestUrl, DefaultFreshnessCheckerNotFresh, floor, fallback = lastGood)
      }
      lastGood != null -> {
        Log.d(TAG, "lookup failed; starting from the cached bundle (last loaded from $lastGood); rollback floor ${floor()}")
        // Guarded too: when the cache is refused or empty, Zipline goes on to fetch
        // lastGood from the network, and a lookup that failed proves nothing about
        // that server (it may have failed the lookup on purpose).
        val http = ManifestPinningHttpClient(okhttp.asZiplineHttpClient(), lastGood, null, floor)
        startTreehouse(prefs, verifier, cacheName, okhttp, http, apiBase, lastGood, AcceptCachedBundle(floor), floor, fallback = null)
      }
      else -> {
        mutableState.value = KeliverHostState.Message("No bundle", "No compatible bundle at $server, and none loaded before.")
        // Nothing was created, so the next screen to start() may look again.
        started.set(false)
      }
    }
  }

  /** This app's versionCode, for host-version gates (a library's BuildConfig has none). */
  private fun appVersionCode(): Long? = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
  }.getOrNull()

  /** The bundle to load: its manifest URL, the sha256 the index holds it to (null from the relay's legacy lookup), and where it came from. */
  private class Lookup(val manifestUrl: String, val manifestSha256: String?, val source: String)

  /**
   * The newest compatible bundle on the bundle server, or null. Reads
   * bundles/index.json; on a 404 for it, asks the relay's bundles/latest.
   * Either way the manifest must be on the bundle server's own origin.
   */
  private fun lookupBundle(okhttp: OkHttpClient, server: HttpUrl, capabilities: List<String>, facts: HostFacts): Lookup? = runCatching {
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
      val pick = pickFromIndex(body, capabilities, channel = config.channel, facts = facts).getOrElse {
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
        "index sequence ${pick.sequence}, channel ${pick.channel} (host: ${config.channel}), host version ${facts.hostVersion}, " +
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
    prefs: SharedPreferences,
    verifier: ManifestVerifier,
    cacheName: String,
    okhttp: OkHttpClient,
    ziplineHttp: ManifestPinningHttpClient,
    apiBase: HttpUrl?,
    manifestUrl: String,
    freshness: FreshnessChecker,
    floor: () -> Long,
    fallback: String?,
  ) {
    val flow = MutableStateFlow(manifestUrl)
    // One checker for the app's life, switched for the fall-back (W5): Treehouse
    // reads the spec's checker each time it (re)starts its loader.
    val freshnessSwitch = SwitchableFreshness(freshness)
    var fellBack = false
    lateinit var app: TreehouseApp<PortalPresenter>
    val factory = TreehouseAppFactory(
      context = context,
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
      override val freshnessChecker: FreshnessChecker = freshnessSwitch

      override suspend fun bindServices(treehouseApp: TreehouseApp<PortalPresenter>, zipline: Zipline) {
        zipline.bind<HostSqlDriver>("HostSqlDriver", sqlHost)
        if (apiBase != null) zipline.bind<HostHttpProvider>("HostHttp", OkHttpHostHttp(okhttp, apiBase))
      }

      override fun create(zipline: Zipline): PortalPresenter = zipline.take("PortalPresenter")
    }
    app = factory.create(
      appScope = scope,
      spec = spec,
      // Remember a manifest URL only once code has loaded for it from the
      // network. A load from the cache reports no URL, so it changes nothing.
      // Raise the rollback floor to the sequence that just ran: Zipline verified
      // this manifest's signature, and so its metadata, before loading it.
      eventListenerFactory = LoggingEventListenerFactory(
        onSuccess = { loaded.set(true) },
        onLoaded = { url -> prefs.edit().putString(lastGoodKey(cacheName), url).apply() },
        onSequence = { sequence ->
          val floor = prefs.floor(cacheName)
          if (sequence > floor) {
            // commit(), not apply(): Zipline has already pinned this bundle, and a
            // floor write lost to a kill would let the previous sequence run again.
            prefs.edit().putLong(floorKey(cacheName), sequence).commit()
            Log.d(TAG, "rollback floor raised: $floor -> $sequence")
          }
        },
        // Before any code ran: fall back to the cached last good bundle once (W5),
        // the same way a start with a failed lookup uses it (the floor holds, and
        // Zipline verifies the cached manifest against the key again). Otherwise a
        // failed load would leave the screen blank; say so instead.
        onFailed = { reason ->
          scope.launch {
            if (loaded.get()) return@launch
            if (fallback != null && !fellBack) {
              fellBack = true
              Log.d(TAG, "the bundle did not load; falling back to the cached last good bundle (last loaded from $fallback)")
              freshnessSwitch.current = AcceptCachedBundle(floor)
              ziplineHttp.pin(fallback, null)
              // stop() then start(): after a failed first load the app is still
              // "starting", which restart() would leave alone.
              app.stop()
              flow.value = fallback
              app.start()
            } else {
              mutableState.value = KeliverHostState.Message("Bundle did not load", reason.take(300))
            }
          }
        },
      ),
    )
    mutableState.value = KeliverHostState.Running(app)
  }

  companion object {
    private val claimed = mutableSetOf<String>()

    /**
     * One host per process per key: two would open two loaders on one Zipline
     * cache. A second host for the same key fails here, loudly.
     */
    private fun claim(cacheName: String) = synchronized(claimed) {
      check(claimed.add(cacheName)) { "a KeliverHost for $cacheName already exists in this process: create one, in your Application" }
    }

    /**
     * Builds this process's host. Call once, from the app's Application; then
     * [start] it, or let the first screen. A second host for the same key
     * throws here.
     */
    fun create(context: Context, config: KeliverConfig = KeliverConfig.fromBuild(context)): KeliverHost {
      (decideProductionTrust(config.publicKeyHex) as? ProductionTrust.Verified)?.let { claim(cacheNameFor(it.publicKeyHex)) }
      return KeliverHost(context, config)
    }
  }
}

/** The app's one freshness checker, delegating to [current] (W5: switched for the fall-back). */
private class SwitchableFreshness(@Volatile var current: FreshnessChecker) : FreshnessChecker {
  override fun isFresh(manifest: ZiplineManifest, freshAtEpochMs: Long): Boolean = current.isFresh(manifest, freshAtEpochMs)
}

/**
 * Used only when the bundle lookup failed, or a load failed and the host falls
 * back to the last good bundle (W5): accept the bundle Zipline pinned in
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
  private val onSuccess: () -> Unit,
  private val onLoaded: (String) -> Unit,
  private val onSequence: (Long) -> Unit,
  private val onFailed: (String) -> Unit,
) : EventListener.Factory {
  override fun create(app: TreehouseApp<*>, manifestUrl: String?): EventListener =
    LoggingEventListener(manifestUrl, onSuccess, onLoaded, onSequence, onFailed)
  override fun close() {}
}

private class LoggingEventListener(
  private val manifestUrl: String?,
  private val onSuccess: () -> Unit,
  private val onLoaded: (String) -> Unit,
  private val onSequence: (Long) -> Unit,
  private val onFailed: (String) -> Unit,
) : EventListener() {
  override fun codeLoadSuccess(manifest: ZiplineManifest, zipline: Zipline, startValue: Any?) {
    val sequence = manifestSequence(manifest.metadata)
    Log.d(TAG, "codeLoadSuccess modules=${manifest.modules.keys.size} sequence=${sequence ?: "none"}")
    onSuccess()
    manifestUrl?.let(onLoaded)
    sequence?.let(onSequence)
  }
  override fun codeLoadFailed(exception: Exception, startValue: Any?) {
    Log.e(TAG, "codeLoadFailed: ${exception.message}", exception)
    onFailed(exception.message ?: exception::class.simpleName ?: "unknown error")
  }
  override fun uncaughtException(exception: Throwable) {
    Log.e(TAG, "uncaughtException: ${exception.message}", exception)
  }
}
