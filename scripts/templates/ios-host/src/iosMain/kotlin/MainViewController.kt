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
 * created nothing ("No bundle") is retried by the next screen; a first load
 * that fails falls back to the cached last good bundle, or shows "Bundle did
 * not load", and the next update check (a live event, a return to the
 * foreground with on-resume or live updates, or the app's own checkForUpdate)
 * then looks up and loads again in the same process.
 *
 * Updates (W5): checkForUpdate() looks up again and, after a network start,
 * applies a newer bundle in place (Zipline loads it while the old code runs; a
 * failure leaves the old one). With UPDATES = "on-resume" in HostConfig.kt the
 * host does that each time the app returns to the foreground. With "live" (by
 * default in a DEBUG build; a DEBUG build only) it also listens to the bundle
 * server's /bundles/events while a Keliver screen is on show (its view
 * controller's lifecycle STARTED; BundleEvents.kt), so a publish reaches an
 * open app. A
 * DEBUG build also checks when told to from the computer
 * (debugCheckForUpdateName()).
 *
 * Reports (W6): every outcome (loaded, fell back, update applied or failed,
 * not loaded, no bundle, refused) goes to Keliver.onReport, for the app's own
 * analytics or crash keys; with REPORT_URL the host also POSTs it there as
 * JSON, best effort. Nothing leaves the device otherwise.
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.cash.zipline.Call as ZiplineCall
import app.cash.zipline.CallResult
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
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.treehouse.Crashed
import dev.keliver.treehouse.DynamicContentWidgetFactory
import dev.keliver.treehouse.EventListener
import dev.keliver.treehouse.Loading
import dev.keliver.treehouse.MemoryStateStore
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseAppFactory
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.EmptySerializersModule
import okio.ByteString.Companion.decodeHex
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIViewController
import platform.UIKit.navigationController
import platform.darwin.NOTIFY_STATUS_OK
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.notify_register_dispatch

private const val TAG = "KeliverHost"
/** One Zipline cache per key, and one rollback floor and last-good URL per key with it. */
private fun cacheName(trust: ProductionTrust.Verified) = "keliver-production-${trust.publicKeyHex.lowercase().take(16)}"
/** The last manifest URL that loaded from the network, per key: it says that key's cache holds a bundle. */
private fun lastGoodKey(trust: ProductionTrust.Verified) = "keliver.lastGoodManifestUrl-${cacheName(trust)}"
/** The highest signed sequence this host has run, per key (the rollback floor); [publicKeyHex]: any trusted key. */
private fun floorKey(publicKeyHex: String) = "keliver.highestSequence-keliver-production-${publicKeyHex.lowercase().take(16)}"
/** W8: the floor across every trusted key's own floor, so a rotation never lowers it. */
private fun floorOf(trust: ProductionTrust.Verified): Long =
  floorAcross(trust.publicKeysHex.map { NSUserDefaults.standardUserDefaults.integerForKey(floorKey(it)) })
/** Writes [sequence] as the floor under every trusted key that holds a lower one. */
private fun raiseFloor(trust: ProductionTrust.Verified, sequence: Long) {
  val defaults = NSUserDefaults.standardUserDefaults
  trust.publicKeysHex.filter { defaults.integerForKey(floorKey(it)) < sequence }.forEach { defaults.setInteger(sequence, forKey = floorKey(it)) }
}
/**
 * This install's random id, for staged rollouts (W4.5): made once, kept here.
 * It leaves the device only in reports, and only when REPORT_URL is set (W6).
 */
private const val INSTALL_ID = "keliver.installId"
private fun installId(): String {
  val defaults = NSUserDefaults.standardUserDefaults
  return defaults.stringForKey(INSTALL_ID) ?: NSUUID().UUIDString.also { defaults.setObject(it, forKey = INSTALL_ID) }
}
/** This build's CFBundleVersion (CURRENT_PROJECT_VERSION), for host-version gates; null unless an integer. */
private fun hostVersion(): Long? = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toLongOrNull()

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** What a start found: the app to run, or why there is none. */
private sealed interface HostState {
  /** [retry]: nothing was created, so the next start() may look again. */
  data class Message(val title: String, val text: String, val retry: Boolean = false) : HostState
  data class Running(val app: TreehouseApp<PortalPresenter>) : HostState
}

/**
 * What a screen shows (host kit K2, the same shape as Android's). Observe it with
 * [Keliver.onStateChange] (Swift) or [Keliver.state] to hide the app's own chrome while
 * [Running], or to open what a screen replaced when it is [Unavailable] ([Keliver.viewController]'s
 * onUnavailable does that for one screen). Changes happen on the main thread.
 */
public sealed interface KeliverHostState {
  /** Looking up and loading the first bundle. */
  public data object Loading : KeliverHostState

  /**
   * The host cannot show a screen. [outcome] is the report's (W6): `no-bundle` (the lookup failed
   * and nothing is cached; the next screen to open looks again), `refused` (the build's key or
   * bundle server is unusable) or `not-loaded` (the bundle did not load, and no last good one did
   * either; the next update check loads again). [reason] is the report's fixed category. [title] and [message] say what happened for
   * a developer: they can name addresses and paths, so a release binary's screen does not show
   * them. [attempt] numbers these in the process from 1, so the same failure on a retry is a new
   * state; compare [outcome] and [reason], not whole states.
   */
  public data class Unavailable(
    val outcome: String,
    val reason: String,
    val title: String,
    val message: String,
    val attempt: Int,
  ) : KeliverHostState

  /** The bundle is running; screens show it. */
  public class Running internal constructor(internal val app: TreehouseApp<PortalPresenter>) : KeliverHostState
}

/** The bundle a host runs (W5): its signed sequence (-1: none), and whether it came from Zipline's cache. */
public data class KeliverBundle(val sequence: Long, val fromCache: Boolean)

/** What [Keliver.checkForUpdate] found: "up-to-date", "available" (applying or at the next start) or "failed". */
public data class KeliverUpdateCheck(val outcome: String, val sequence: Long, val applying: Boolean, val reason: String)

/**
 * One outcome, as the host reports it (W6). [outcome] is loaded, fell-back,
 * update-applied, update-failed, not-loaded, no-bundle, refused or screen-failed;
 * [source] is "network", "cache" or ""; [sequence] -1 when there is none;
 * [hostVersion] -1 when CFBundleVersion is not an integer. [reason] is a fixed
 * category (sha256-mismatch, below-floor, bridge-missing, bridge-shape,
 * signature, lookup-failed, network, config, exception, other); [detail] is the full message, for the app only: it can hold
 * addresses, paths or URLs, so [toJson], what REPORT_URL receives, leaves it
 * out. [installId] is the random id made on the device for rollouts.
 */
public data class KeliverReport(
  val installId: String,
  val channel: String,
  val hostVersion: Long,
  val sequence: Long,
  val source: String,
  val outcome: String,
  val reason: String,
  val detail: String,
  val platform: String = "ios",
) {
  public fun toJson(): String = buildJsonObject {
    put("installId", installId)
    put("channel", channel)
    put("hostVersion", hostVersion.takeIf { it >= 0 })
    put("sequence", sequence.takeIf { it >= 0 })
    put("source", source.ifEmpty { null })
    put("outcome", outcome)
    put("reason", reason)
    put("platform", platform)
  }.toString()
}

/** What happened to the bundle the host runs (W5): "applied", "failed" or "fell-back". */
public data class KeliverUpdateEvent(val kind: String, val sequence: Long, val reason: String)

/** What a running app needs to look up and apply a newer bundle. Main thread only. */
internal class Session(
  val app: TreehouseApp<PortalPresenter>,
  val pinning: ManifestPinningHttpClient,
  val manifestUrls: MutableStateFlow<String>,
  val lookup: LookupContext,
  var fromCache: Boolean,
  /** Loads a manifest URL (and its index sha256) into [app] again, from the network: `loadAgain`. */
  val reload: (String, String?) -> Unit,
) {
  /** The first load failed, and nothing runs: the next update check loads again. */
  var notLoaded = false
  private var failedUrl: String? = null
  private var failedSha256: String? = null
  private var unloadable = false
  /** The report's reason for the load that failed. */
  var failedReason = ""
    private set

  /**
   * The first load failed for [reason]: remembers what did not load, and whether the bundle
   * itself cannot load whatever the link does. Only the floor's own refusal and Zipline's
   * signature failure count, by their exact messages: a TLS error can say "verified", and a
   * sha256 mismatch can be a stale CDN copy that refreshes.
   */
  fun markNotLoaded(reason: String) {
    notLoaded = true
    failedUrl = manifestUrls.value
    failedSha256 = failedUrl?.let(pinning::pinnedSha256)
    failedReason = reportReason("not-loaded", reason)
    unloadable = "rollback refused:" in reason || "bridge refused:" in reason ||
      ("manifest signature for key" in reason && "did not verify" in reason)
  }

  /**
   * The index still names exactly what failed (the same manifest and sha256), and the bundle
   * itself cannot load (below the floor, or its signature did not verify): fetching it again
   * cannot help, and would report the failure again. Without a sha256 (the relay's legacy
   * lookup) the bytes at that URL may have changed, so it is fetched again.
   */
  fun sameUnloadable(url: String, sha256: String?): Boolean =
    unloadable && failedSha256 != null && url == failedUrl && sha256 == failedSha256
}
internal class LookupContext(val server: BundleServer, val capabilities: List<String>, val floor: () -> Long)

/**
 * This process's Keliver host (Swift: `Keliver.shared`). It looks up and loads
 * the bundle once; [viewController] makes a screen that shows it.
 */
public object Keliver {
  private val mutableState = MutableStateFlow<KeliverHostState>(KeliverHostState.Loading)

  /** What screens show (K2). Main thread. */
  public val state: StateFlow<KeliverHostState> = mutableState.asStateFlow()

  /** [state]'s value now, typed for Swift (K2). Main thread. */
  public val currentState: KeliverHostState get() = mutableState.value

  /**
   * Called on the main thread with each LATER change of [state] (K2): Swift's way to observe it.
   * Read [currentState] when you set it, for the state it starts from.
   */
  public var onStateChange: ((KeliverHostState) -> Unit)? = null

  /**
   * Whether screens show the host's own text (addresses, paths, errors): only in a DEBUG binary.
   * A release binary shows "Loading…" and a generic line instead (K2's release text policy).
   */
  @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
  public val showsDeveloperText: Boolean get() = kotlin.native.Platform.isDebugBinary

  private var unavailableAttempts = 0

  /**
   * What guests' HostHttp requests may reach (K2): set it before the host starts (before the
   * first screen, or [start]). Null allows every path under API_BASE_URL, as before.
   */
  public var requestPolicy: RequestPolicy? = null
    set(value) {
      if (started) log("requestPolicy set after the host started: it applies from the next process start")
      field = value
    }

  /**
   * B2: YOUR app's credentials for guests' HostHttp requests, in Swift:
   * `Keliver.shared.requestHeaders = { method, path, done in done(["Authorization": "Bearer \(token)"]) }`.
   * Called on the main thread for each request the [requestPolicy] allowed, with its method and its
   * path relative to API_BASE_URL. Call `done` once: with the headers to add (they replace the
   * guest's of the same name), or with nil to refuse the request, as when nobody is signed in;
   * nothing is sent then, nor when `done` is not called within 30 s (counted from before the hop
   * to the main thread). Do not block the main thread in it: answer `done` later instead.
   *
   * It needs a [requestPolicy] (your credentials with no allow-list would let a guest call any
   * path under the API base as the user): without one every request is refused. With it, a
   * guest's own `Authorization`, `Proxy-Authorization` and `Cookie` headers are dropped, a
   * response's `Set-Cookie` is not handed back, HostHttp keeps no cookies, cache or credentials
   * of its own, and TRACE, TRACK and CONNECT are refused (TRACE would echo your headers to the
   * guest). Bundles, the update stream and reports never get these headers.
   * Set it before the host starts (before the first screen, or [start]).
   */
  public var requestHeaders: KeliverRequestHeaders? = null
    set(value) {
      if (started) log("requestHeaders set after the host started: it applies from the next process start")
      field = value
    }

  private fun setState(s: KeliverHostState) {
    mutableState.value = s
    // The app's handler must not take the host down with it.
    runCatching { onStateChange?.invoke(s) }.onFailure { log("onStateChange threw: ${it.message}") }
  }

  /** The host cannot show a screen: says so on [state] (W6's [outcome] and its reason) and reports it. Main thread. */
  private fun unavailable(outcome: String, title: String, message: String, detail: String) {
    setState(KeliverHostState.Unavailable(outcome, reportReason(outcome, detail), title, message, ++unavailableAttempts))
    report(outcome, detail = detail)
  }
  /**
   * Set when the running code threw (K2's `screen-failed`, as on Android): Treehouse stops it, so a
   * screen opened afterwards would wait for code that never comes. It shows this instead, and calls
   * its onUnavailable. Cleared when code loads again (an update). Main thread.
   */
  internal val crashed = MutableStateFlow<KeliverHostState.Unavailable?>(null)

  /**
   * K2's `screen-failed`: a screen's code threw while it showed. Reported (W6), and returned as that
   * screen's state for its `onUnavailable`; [state] stays [KeliverHostState.Running], since the next
   * code load (an update) replaces the failed code. Main thread.
   */
  internal fun screenFailed(exception: Throwable): KeliverHostState.Unavailable {
    val detail = exception.message ?: exception::class.simpleName ?: "unknown error"
    val running = current
    report("screen-failed", running?.sequence, running?.let { if (it.fromCache) "cache" else "network" } ?: "", detail)
    return KeliverHostState.Unavailable(
      "screen-failed", reportReason("screen-failed", detail), "This screen stopped", detail.take(300), ++unavailableAttempts,
    ).also { crashed.value = it }
  }

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

  /** Called on the main thread with every outcome (W6), for the app's analytics or crash keys. */
  public var onReport: ((KeliverReport) -> Unit)? = null

  /**
   * Called on the main thread with each timing mark (M0; docs/DEVICE_HOST.md, "Timing marks"):
   * its name and CACurrentMediaTime() in nanoseconds. Only builds with the marks (Debug and
   * Benchmark) call it.
   */
  public var onTrace: ((name: String, nanos: Long) -> Unit)? = null

  /**
   * Hands [outcome] to [onReport] and, with REPORT_URL, POSTs it there. Best
   * effort: a report that cannot be sent is dropped; it never blocks or fails
   * anything else. Main thread.
   */
  internal fun report(outcome: String, sequence: Long? = null, source: String = "", detail: String = "") {
    // A retried start reports its first "no bundle" only.
    if (outcome == "no-bundle") { if (reportedNoBundle) return; reportedNoBundle = true }
    val r = KeliverReport(installId(), CHANNEL, hostVersion() ?: -1, sequence ?: -1, source, outcome, reportReason(outcome, detail), detail.take(300))
    // The app's handler must not take the host down with it.
    runCatching { onReport?.invoke(r) }.onFailure { log("onReport threw: ${it.message}") }
    if (REPORT_URL.isEmpty()) return
    // Not the URL: it is the app's.
    log("report: $outcome sequence=${sequence ?: "none"} reason=${r.reason.ifEmpty { "-" }}")
    appScope.launch {
      runCatching { send(reportSession, postJsonRequest(REPORT_URL, r.toJson(), timeoutSeconds = 10.0)) }
        .onFailure { log("report not sent: ${it.message}") }
    }
  }
  private var reportedNoBundle = false

  /** Hands [event] to [onUpdateEvent]; the app's handler must not take the host down with it. */
  private fun emit(event: KeliverUpdateEvent) {
    runCatching { onUpdateEvent?.invoke(event) }.onFailure { log("onUpdateEvent threw: ${it.message}") }
  }

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
    KeliverTiming.start() // Debug and Benchmark builds only (M0); nothing elsewhere
    // Once: a start that created nothing runs again (started is reset), and a
    // second observer would make every foreground check twice.
    if (updatesMode() != "next-launch" && !resumeObserved) {
      resumeObserved = true
      NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
        resumed()
      }
    }
    registerDebugCheckForUpdate()
    lastLookup = TimeSource.Monotonic.markNow()
    // A start after "no bundle" looks again: Loading meanwhile, as on the first.
    if (mutableState.value is KeliverHostState.Unavailable) setState(KeliverHostState.Loading)
    appScope.launch {
      when (val s = startHost()) {
        is HostState.Running -> setState(KeliverHostState.Running(s.app))
        is HostState.Message -> {
          if (s.retry) started = false
          unavailable(if (s.retry) "no-bundle" else "refused", s.title, s.text, detail = s.text)
        }
      }
    }
  }

  /** Called on the main thread when a code load succeeded; [url] is null for a load from the cache. */
  internal fun codeLoaded(sequence: Long?, url: String?) {
    val first = !loaded
    loaded = true
    crashed.value = null // new code: no longer the code that threw
    current = KeliverBundle(sequence ?: -1, fromCache = url == null)
    // After a load from the cache Zipline takes no further manifest; after a network load it does.
    session?.fromCache = url == null
    val p = pending
    if (p != null && url == p.url) {
      pending = null
      log("update applied: sequence $sequence")
      emit(KeliverUpdateEvent("applied", sequence ?: -1, ""))
      report("update-applied", sequence, "network")
    } else if (first) {
      report("loaded", sequence, if (url == null) "cache" else "network")
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
    emit(KeliverUpdateEvent("fell-back", -1, reason))
    report("fell-back", detail = reason)
  }

  /** Called on the main thread when a load failed after code had run: an update that did not load. */
  internal fun updateFailed(reason: String) {
    val p = pending ?: return
    pending = null
    failedUpdates += p.url // not tried again in this process
    log("update failed: sequence ${p.sequence} did not load ($reason); the running bundle stays")
    emit(KeliverUpdateEvent("failed", p.sequence, reason))
    report("update-failed", p.sequence, "network", reason)
  }

  /**
   * Looks up the newest bundle now (W5), with the same index, channel,
   * constraints and floor as the start. With [apply], and when this process
   * started from the network, a newer one loads in place: Zipline loads it while
   * the running code goes on, swaps it in on success and keeps the old one on
   * failure ([onUpdateEvent]). After a start from the cache, a newer bundle
   * applies at the next process start. When the first load failed ("Bundle did
   * not load"), it looks up and loads again instead, whatever [apply] says; then
   * [onUpdateEvent] gets nothing, and the state and the reports say how it went.
   * From any thread: it runs on the main one.
   */
  public suspend fun checkForUpdate(apply: Boolean = true): KeliverUpdateCheck = withContext(Dispatchers.Main) {
    checkOnMain(apply)
  }

  private suspend fun checkOnMain(apply: Boolean): KeliverUpdateCheck {
    val s = session
    val running = current
    if (s != null && running == null && s.notLoaded) return loadAgain(s)
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

  private var debugCheckRegistered = false // main thread
  private var resumeObserved = false // main thread

  /**
   * A DEBUG binary only: a check now, applying a newer bundle, not held to the 30 s between
   * on-resume checks, when the Darwin notification [debugCheckForUpdateName] is posted
   * (`xcrun devicectl device notification post --device <id> --name <name>` on a phone,
   * `xcrun simctl spawn <udid> notifyutil -p <name>` on a simulator). A release binary never
   * registers it. Every rule of an update still holds (the index, the channel, the constraints,
   * the floor, the signature). Main thread.
   */
  @OptIn(kotlin.experimental.ExperimentalNativeApi::class, ExperimentalForeignApi::class)
  private fun registerDebugCheckForUpdate() {
    if (!kotlin.native.Platform.isDebugBinary || debugCheckRegistered) return
    debugCheckRegistered = true
    val name = debugCheckForUpdateName()
    val status = memScoped {
      val token = alloc<IntVar>()
      notify_register_dispatch(name, token.ptr, dispatch_get_main_queue()) { _ ->
        log("update check requested by $name (a debug build)")
        appScope.launch { checkForUpdate() }
      }
    }
    if (status != NOTIFY_STATUS_OK.toUInt()) log("could not register $name (notify status $status)")
  }

  /** With UPDATES = "on-resume" or "live": check and apply, at most every 30 s (the host observes the foreground itself). */
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

  /**
   * Keliver screens on show ([viewController] counts them by its lifecycle: started from
   * viewWillAppear until viewDidDisappear or the app going to the background); the live
   * listener runs while there is one. Main thread.
   */
  private var screensShown = 0
  private var liveListener: Job? = null
  /** Set when the bundle server has no event stream (a static server): not asked again in this process. */
  private var liveUnsupported = false

  private fun screenShown() {
    screensShown++
    syncLiveListener()
  }

  private fun screenHidden() {
    screensShown = (screensShown - 1).coerceAtLeast(0)
    syncLiveListener()
  }

  /** UPDATES = "live" in a DEBUG binary ([updatesMode] makes it "on-resume" in a release one). */
  @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
  private fun liveUpdatesAllowed(): Boolean = updatesMode() == "live" && !liveUnsupported && kotlin.native.Platform.isDebugBinary

  private fun syncLiveListener() {
    if (screensShown > 0 && liveUpdatesAllowed()) {
      if (liveListener?.isActive != true) liveListener = appScope.launch { listenForBundles() }
    } else {
      if (liveListener?.isActive == true) log("live updates: stopped listening (no screen on show)")
      liveListener?.cancel()
      liveListener = null
    }
  }

  /**
   * Holds `/bundles/events` open and runs [checkForUpdate] on each event, until
   * cancelled (no screen on show; in the background none is). An event is
   * only a nudge: what is applied is what the index names and the manifest's
   * signature allows, as on any update. Checks run one at a time; events that
   * arrive during one ask for one more. Before the first bundle has loaded
   * ("No bundle"), an event starts the host again instead, so the first publish
   * reaches the open app too; after a first load that failed, the check loads
   * again ([loadAgain]). A dropped stream is retried after 2 s, doubling
   * to 30 s; the delay goes back to 2 s only once an event has arrived. Main thread.
   */
  private suspend fun listenForBundles() = coroutineScope {
    val checks = Channel<Unit>(Channel.CONFLATED)
    launch {
      for (ignored in checks) {
        if (session == null) start() else runCatching { checkOnMain(apply = true) }
      }
    }
    var retry = 2.seconds
    while (true) {
      // The server the session runs on; before one exists, the one a start would use.
      val base = session?.lookup?.server?.base
        ?: bundleServerSetting().first.takeIf { '?' !in it && '#' !in it }?.let { BundleServer.parse(it) }?.base
      if (base == null) {
        checks.close()
        return@coroutineScope
      }
      val url = "$base/bundles/events"
      val end = runCatching {
        readBundleEvents(url, onListening = { log("live updates: listening to $url") }) { data ->
          log("live updates: the bundle server says $data")
          checks.trySend(Unit)
        }
      }
      ensureActive()
      val stream = end.getOrNull()
      if (stream?.kind == LiveStream.NOT_SERVED) {
        log("live updates: $url is not served (a static bundle server); updates apply on resume")
        liveUnsupported = true
        checks.close()
        return@coroutineScope
      }
      if (stream?.kind == LiveStream.HEARD) retry = 2.seconds
      log("live updates: the stream ended (${end.exceptionOrNull()?.message ?: stream?.error ?: "closed"}); again in ${retry.inWholeSeconds} s")
      delay(retry)
      retry = (retry * 2).coerceAtMost(30.seconds)
    }
  }

  /**
   * The first load failed and nothing runs (W5's open question from a phone on a weak link: the
   * host stayed on "Bundle did not load" until the process was restarted). Looks up again and
   * loads what the index names now, from the network, into the same app. Every rule of a start
   * holds (the index, the channel, the constraints, the floor, the signature). The state is
   * [KeliverHostState.Running] again while it loads; a failure says "Bundle did not load" again.
   * It is not an update: [onUpdateEvent] gets no "applied" or "failed" for it; the state and the
   * reports (`loaded`, or `not-loaded` again) say how it went. When the index still names the
   * bundle that failed, and it cannot load (below the floor, or its signature did not verify),
   * it is not fetched again. Main thread.
   */
  private suspend fun loadAgain(s: Session): KeliverUpdateCheck {
    lastLookup = TimeSource.Monotonic.markNow()
    val latest = lookupBundle(s.lookup.server, s.lookup.capabilities, HostFacts(installId(), hostVersion(), s.lookup.floor()))
    // Another check may have started it while this one looked up.
    if (session !== s || !s.notLoaded) return KeliverUpdateCheck("failed", -1, false, "the bundle is already loading again")
    if (latest == null) {
      log("update check: the first bundle did not load, and the lookup failed")
      return KeliverUpdateCheck("failed", -1, false, "the lookup failed")
    }
    if (s.sameUnloadable(latest.manifestUrl, latest.manifestSha256)) {
      log("update check: the first bundle did not load (${s.failedReason}), and the index still names it: not fetched again")
      return KeliverUpdateCheck("failed", -1, false, "the bundle that did not load (${s.failedReason}) is still the newest")
    }
    s.notLoaded = false
    log("update check: the first bundle did not load; loading ${latest.manifestUrl} (${latest.source})")
    setState(KeliverHostState.Running(s.app))
    s.reload(latest.manifestUrl, latest.manifestSha256)
    return KeliverUpdateCheck("available", latest.sequence ?: -1, true, "")
  }

  /**
   * Called on the main thread when a code load failed: before any code ran, say so instead of a
   * blank screen, and let the next update check load again ([loadAgain]).
   */
  internal fun codeLoadFailed(reason: String) {
    if (loaded) return
    session?.markNotLoaded(reason)
    unavailable("not-loaded", "Bundle did not load", reason.take(300), detail = reason)
  }

  /**
   * A screen showing the host: its messages while it looks up and loads, then
   * the guest's screen. [safeArea] pads it to the safe area; turn it off when
   * the containing layout already does.
   */
  public fun viewController(safeArea: Boolean = true): UIViewController = viewController(safeArea, onUnavailable = null)

  /**
   * [viewController], and [onUnavailable] (K2): called on the main thread each time this screen
   * shows an [KeliverHostState.Unavailable] state. Replace the Keliver screen there with what it
   * replaced (your web page or native screen, with the route you were showing): dismiss or pop it
   * rather than stacking the fallback over it. After "no bundle", a screen created later looks up
   * again (Loading meanwhile).
   */
  public fun viewController(safeArea: Boolean, onUnavailable: ((KeliverHostState.Unavailable) -> Unit)?): UIViewController =
    viewController(safeArea, onUnavailable, route = null, onClose = null)

  /**
   * [viewController] for [route] (K2): the guest's screen for it (its `launchRoute`; a guest from
   * before the host kit shows its default screen, logged). The screen closes itself through
   * [onClose], on the main thread; null pops this view controller from its navigation controller,
   * or dismisses it, whichever way it was shown. A new route is a new view controller (in SwiftUI,
   * give KeliverScreen a new `.id`).
   */
  @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
  public fun viewController(
    safeArea: Boolean,
    onUnavailable: ((KeliverHostState.Unavailable) -> Unit)?,
    route: KeliverRoute?,
    onClose: (() -> Unit)?,
  ): UIViewController {
    KeliverTiming.memory("open") // Debug and Benchmark builds only (M5)
    // Weak: the screen holds this closure, and a view controller that went closes nothing.
    var shownBy: kotlin.native.ref.WeakReference<UIViewController>? = null
    val close: () -> Unit = onClose ?: { shownBy?.get()?.let(::closeShown) }
    return screen(safeArea, onUnavailable, route, close).also { shownBy = kotlin.native.ref.WeakReference(it) }
  }

  /**
   * Closes [vc] the way it was shown: pops the navigation stack back to the screen below the one
   * that holds [vc] (not whatever is on top), or dismisses what presents it. A SwiftUI app that
   * shows it in a sheet or a NavigationStack passes onClose instead (dismiss(), or drop the path
   * element), so its own state agrees.
   */
  private fun closeShown(vc: UIViewController) {
    val nav = vc.navigationController
    if (nav != null) {
      // The navigation stack's entry that is, or contains, vc.
      var entry: UIViewController? = vc
      while (entry != null && entry.parentViewController != nav) entry = entry.parentViewController
      val stack = nav.viewControllers
      val i = stack.indexOf(entry)
      if (i > 0) {
        nav.popToViewController(stack[i - 1] as UIViewController, animated = true)
        return
      }
    }
    // What presents the screen (or its container); not a modal the screen presents itself, which
    // UIKit would dismiss instead: close that one first.
    var shown: UIViewController? = vc
    while (shown != null && shown.presentingViewController == null) shown = shown.parentViewController
    if (shown != null) shown.dismissViewControllerAnimated(true, completion = null)
    else log("closeScreen: this screen is neither pushed nor presented; pass onClose to close it your way")
  }

  /** The guest's UI for [route] (launchRoute), or launch() without one or for a guest from before K2. */
  private fun launch(
    app: PortalPresenter,
    treehouseApp: TreehouseApp<PortalPresenter>,
    route: KeliverRoute?,
    close: () -> Unit,
  ): dev.keliver.treehouse.ZiplineTreehouseUi {
    val code = treehouseApp.zipline.value
    if (route == null || (code != null && code === noRouteCode)) return app.launch()
    return try {
      app.launchRoute(route.encode(), ScreenHost(close))
    } catch (e: app.cash.zipline.ZiplineApiMismatchException) {
      // This loaded code is not asked again (each try leaves its ScreenHost bound). Keyed on its
      // Zipline: each screen gets its own scoped proxy of the presenter, and new code is a new
      // Zipline, so nothing needs clearing when it loads.
      noRouteCode = code
      log("this bundle's guest takes no route (scaffolded before the host kit): showing its default screen")
      app.launch()
    }
  }

  /** The loaded code (its Zipline) whose guest has no launchRoute, if any. */
  private var noRouteCode: app.cash.zipline.Zipline? = null

  private class ScreenHost(private val close: () -> Unit) : dev.keliver.hostkit.KeliverScreenHost {
    // Zipline calls in on its own dispatcher; the app's onClose runs on the main thread, and a
    // throw there must not end the process.
    override fun closeScreen() {
      appScope.launch { runCatching(close).onFailure { log("onClose threw: ${it.message}") } }
    }
  }

  private fun screen(
    safeArea: Boolean,
    onUnavailable: ((KeliverHostState.Unavailable) -> Unit)?,
    route: KeliverRoute?,
    close: () -> Unit,
  ): UIViewController = ComposeUIViewController {
    remember {
      // Debug and Benchmark builds only (M0, M5)
      KeliverTiming.mark("open")
      KeliverTiming.memoryAfterFrame("ui")
    }
    LaunchedEffect(Unit) { start() }
    // Live updates (UPDATES = "live", debug builds): the host listens for publishes while a
    // screen is on show. The composition outlives that (a tab not selected, a screen pushed over
    // it), so this follows the view controller's lifecycle, as Android's KeliverScreen does.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
      var shown = false
      val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START && !shown) {
          shown = true
          screenShown()
        } else if (event == Lifecycle.Event.ON_STOP && shown) {
          shown = false
          screenHidden()
        }
      }
      lifecycle.addObserver(observer)
      onDispose {
        lifecycle.removeObserver(observer)
        if (shown) screenHidden()
      }
    }
    val current by mutableState.collectAsState()
    val modifier = if (safeArea) Modifier.fillMaxSize().safeDrawingPadding() else Modifier.fillMaxSize()
    when (val s = current) {
      KeliverHostState.Loading ->
        if (showsDeveloperText) MessageScreen("Loading", "Looking up the latest bundle…", safeArea) else MessageScreen("Loading…", null, safeArea)
      is KeliverHostState.Unavailable -> {
        // Still the state? A screen created on an old "no bundle" restarts the host just before
        // this runs (the start() effect is earlier), and must not act on that failure.
        LaunchedEffect(s) {
          if (mutableState.value == s) runCatching { onUnavailable?.invoke(s) }.onFailure { log("onUnavailable threw: ${it.message}") }
        }
        UnavailableScreen(s, safeArea)
      }
      is KeliverHostState.Running -> {
        val widgetSystem = remember { ComposeUiKeliverMaterialWidgetSystem(imageLoader) }
        val contentSource = remember {
          object : TreehouseContentSource<PortalPresenter> {
            // first-frame, in Debug and Benchmark builds only (M0); the UI itself elsewhere.
            override fun get(app: PortalPresenter) = KeliverTiming.firstFrame(launch(app, s.app, route, close))
          }
        }
        // K2's screen-failed: the code threw while this screen showed it. (onUnavailable and
        // safeArea are this view controller's, fixed for its life.)
        val failureWidgets = remember { ScreenFailedWidgets({ onUnavailable }, safeArea) }
        Box(modifier) {
          TreehouseContent(
            treehouseApp = s.app,
            widgetSystem = widgetSystem,
            contentSource = contentSource,
            dynamicContentWidgetFactory = failureWidgets,
          )
        }
      }
    }
  }
}

/** A screen the host cannot show: the host's own text only when [Keliver.showsDeveloperText]. */
@Composable
private fun UnavailableScreen(s: KeliverHostState.Unavailable, safeArea: Boolean) {
  if (Keliver.showsDeveloperText) MessageScreen(s.title, s.message, safeArea)
  else MessageScreen("Not available", "This screen can't be shown right now. Please try again later.", safeArea)
}

/**
 * What a screen shows in place of the guest's content while code loads, and once the code it showed
 * has thrown (K2's `screen-failed`, as Android's KeliverScreen): the host reports it (W6), the screen
 * calls its `onUnavailable` once per failure and shows [UnavailableScreen]. Treehouse replaces the
 * failed content when code loads again.
 */
private class ScreenFailedWidgets(
  private val onUnavailable: () -> ((KeliverHostState.Unavailable) -> Unit)?,
  private val safeArea: Boolean,
) : DynamicContentWidgetFactory<@Composable (Modifier) -> Unit> {
  // Nothing while code loads, as before; but once the running code has thrown, it never will:
  // a screen opened then says so and calls its onUnavailable.
  override fun Loading(): Loading<@Composable (Modifier) -> Unit> = object : Loading<@Composable (Modifier) -> Unit> {
    override var modifier: RedwoodModifier = RedwoodModifier
    override val value: @Composable (Modifier) -> Unit = {
      val failed by Keliver.crashed.collectAsState()
      failed?.let { f -> Failed(f) }
    }
  }

  override fun Crashed(): Crashed<@Composable (Modifier) -> Unit> = object : Crashed<@Composable (Modifier) -> Unit> {
    private var failed by mutableStateOf<KeliverHostState.Unavailable?>(null)
    override var modifier: RedwoodModifier = RedwoodModifier
    override val value: @Composable (Modifier) -> Unit = {
      failed?.let { f -> Failed(f) }
    }

    override fun uncaughtException(uncaughtException: Throwable) {
      if (failed == null) failed = Keliver.screenFailed(uncaughtException)
    }

    override fun restart(restart: () -> Unit) {}
  }

  @Composable
  private fun Failed(f: KeliverHostState.Unavailable) {
    LaunchedEffect(f) { runCatching { onUnavailable()?.invoke(f) }.onFailure { log("onUnavailable threw: ${it.message}") } }
    UnavailableScreen(f, safeArea)
  }
}

/**
 * The bundle server this start uses, and whether it came from the launch: BUNDLE_SERVER, or in a
 * DEBUG binary the KELIVER_BUNDLE_SERVER launch environment variable when it is set (`xcrun simctl
 * launch` with SIMCTL_CHILD_KELIVER_BUNDLE_SERVER, `xcrun devicectl device process launch
 * --environment-variables`, or the Xcode scheme's environment): a tunnel's new URL without a
 * rebuild. It moves only where bundles come from. Every manifest still verifies against the
 * embedded key, the rollback floor still holds, and the cached last good bundle is used only on
 * the origin it came from. A release binary ignores the environment.
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal fun bundleServerSetting(): Pair<String, Boolean> {
  if (!kotlin.native.Platform.isDebugBinary) return BUNDLE_SERVER to false
  val fromLaunch = (NSProcessInfo.processInfo.environment["KELIVER_BUNDLE_SERVER"] as? String)?.trim()
  return if (fromLaunch.isNullOrEmpty()) BUNDLE_SERVER to false else fromLaunch to true
}

/**
 * UPDATES, or when it is empty: "live" in a DEBUG binary and "next-launch" in a release one.
 * "live" is a DEBUG binary's only: in a release one it is "on-resume".
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal fun updatesMode(): String {
  val mode = UPDATES.ifEmpty { if (kotlin.native.Platform.isDebugBinary) "live" else "next-launch" }
  return if (mode == "live" && !kotlin.native.Platform.isDebugBinary) "on-resume" else mode
}

/** The Darwin notification a DEBUG binary checks for an update on: `<bundle id>.keliver.check-for-update`. */
internal fun debugCheckForUpdateName(): String = "${NSBundle.mainBundle.bundleIdentifier ?: "keliver"}.keliver.check-for-update"

/** The view controller your Xcode app shows (MainViewControllerKt.MainViewController()): Keliver's screen. */
public fun MainViewController(): UIViewController = Keliver.viewController()

private suspend fun startHost(): HostState {
  // Decided BEFORE any fetch: no valid key, no network, no bundle.
  val trust = decideProductionTrust(PORTAL_PUBLIC_KEY_HEX, PORTAL_NEXT_PUBLIC_KEY_HEX)
  if (trust is ProductionTrust.Refused) {
    log("refusing to load: ${trust.message}")
    return HostState.Message("Refusing to load", trust.message)
  }
  trust as ProductionTrust.Verified
  log("verifying manifests with portal-ed25519 ${trust.publicKeyHex.take(8)}…${trust.publicKeysHex.getOrNull(1)?.let { " and the next key ${it.take(8)}…" } ?: ""}; updates ${updatesMode()}")
  val (serverUrl, fromLaunch) = bundleServerSetting()
  if (fromLaunch) log("bundle server from the launch environment (a debug build): $serverUrl")
  // parse() reads only the origin and path; a query or fragment must not reach the base URL the
  // host appends its paths to (the build checks BUNDLE_SERVER for both; the launch value is checked here).
  val server = BundleServer.parse(serverUrl)?.takeIf { '?' !in serverUrl && '#' !in serverUrl } ?: run {
    log("refusing to load: the bundle server '$serverUrl' is not an http(s) URL without a query")
    return HostState.Message("Refusing to load", "The bundle server '$serverUrl' is not an http(s) URL.")
  }
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
  // Kept under every trusted key and read as the highest (W8): a host that trusts
  // the next key carries the floor over to it.
  val floor = { floorOf(trust) }
  raiseFloor(trust, floor())
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
 * way the manifest must be on the bundle server's own origin. Both requests
 * share one deadline, LOOKUP_BUDGET_MILLIS from now (a total, unlike
 * timeoutInterval), and neither answer is read past MAX_INDEX_BYTES.
 */
private suspend fun lookupBundle(server: BundleServer, capabilities: List<String>, facts: HostFacts): Lookup? = runCatching {
  // Short: offline, this decides how long the app waits before it starts from the cache.
  val deadline = TimeSource.Monotonic.markNow() + LOOKUP_BUDGET_MILLIS.milliseconds
  val (status, body) = sendBounded(
    getRequest("${server.base}/bundles/index.json", timeoutSeconds = 10.0),
    MAX_INDEX_BYTES,
    timeout = -deadline.elapsedNow(),
  )
  if (status == 404) {
    log("no bundles/index.json at ${server.base}; asking bundles/latest (a relay from tools 0.3.7 or earlier)")
    return@runCatching legacyLatest(server, capabilities, deadline)
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
private suspend fun legacyLatest(server: BundleServer, capabilities: List<String>, deadline: TimeMark): Lookup? {
  val lookup = "${server.base}/bundles/latest?widgetVersion=$HOST_WIDGET_VERSION&caps=${percentEncode(capabilities.joinToString(","))}"
  val (status, body) = sendBounded(getRequest(lookup, timeoutSeconds = 10.0), MAX_INDEX_BYTES, timeout = -deadline.elapsedNow())
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
  // Each key under its own name, and the first under portal-ed25519 too (W8,
  // BundleIndex.kt: trustedSignatures).
  val verifier = ManifestVerifier.Builder().apply {
    trustedSignatures(trust.publicKeysHex).forEach { (name, key) -> addEd25519(name, key.decodeHex()) }
  }.build()
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
  val http = apiBase?.let { NSURLSessionHostHttp(it, Keliver.requestPolicy, Keliver.requestHeaders) }
  val spec = object : TreehouseApp.Spec<PortalPresenter>() {
    override val name = "keliver-production"
    override val manifestUrl = flow
    override val serializersModule = EmptySerializersModule()
    override val freshnessChecker: FreshnessChecker = freshnessSwitch

    override suspend fun bindServices(treehouseApp: TreehouseApp<PortalPresenter>, zipline: Zipline) {
      zipline.bind<HostSqlDriver>("HostSqlDriver", sql)
      KeliverTiming.bindServices(zipline) // KeliverTrace, in Debug and Benchmark builds only (M0)
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
      onSuccess = { sequence, url ->
        KeliverTiming.mark("code")
        appScope.launch { Keliver.codeLoaded(sequence, url) }
      },
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
        val current = floorOf(trust)
        if (sequence > current) {
          raiseFloor(trust, sequence)
          log("rollback floor raised: $current -> $sequence")
        }
      },
    ),
  )
  KeliverTiming.watch(app) // the memory profile reads its QuickJS heap (M5); Debug and Benchmark builds only
  // After a first load that failed (Keliver.loadAgain): from the network this time, into the same
  // app, stop() then start() as the fall-back does.
  val reload = { url: String, sha256: String? ->
    freshnessSwitch.current = DefaultFreshnessCheckerNotFresh
    pinning.pin(url, sha256)
    app.stop()
    flow.value = url
    app.start()
  }
  Keliver.attach(Session(app, pinning, flow, lookup, fromCache = freshness is AcceptCachedBundle, reload = reload))
  // M3: the code load starts now, not when the first screen's createContent would start it.
  app.start()
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
    // So does B4's bridge check, on the verified metadata.
    val problem = rollbackProblem(manifestSequence(manifest.metadata), floor())
      ?: manifestBridgeProblem(manifest.metadata[BRIDGES_METADATA_KEY], ServedBridges.all)
      ?: return true
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
    KeliverTiming.memory("code", zipline) // the memory profile (M5), Debug and Benchmark builds only
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
  // The memory profile (M5): QuickJS before any code loads. Debug and Benchmark builds only.
  override fun ziplineCreated(zipline: Zipline) = KeliverTiming.memory("runtime", zipline)
  // The guest frame profile (M4), in Debug and Benchmark builds only; nothing elsewhere.
  override fun callStart(call: ZiplineCall): Any? = KeliverTiming.callStart(call)
  override fun callEnd(call: ZiplineCall, result: CallResult, startValue: Any?) = KeliverTiming.callEnd(call, startValue)
}

/** stdout, which `xcrun simctl launch --console-pty` captures: one write a line (consoleLine). */
private fun log(message: String) = consoleLine("$TAG: $message")

@Composable
private fun MessageScreen(title: String, message: String?, safeArea: Boolean = true) {
  Column(
    modifier = (if (safeArea) Modifier.fillMaxSize().safeDrawingPadding() else Modifier.fillMaxSize()).padding(24.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    BasicText(title)
    if (message != null) BasicText(message, modifier = Modifier.padding(top = 12.dp))
  }
}
