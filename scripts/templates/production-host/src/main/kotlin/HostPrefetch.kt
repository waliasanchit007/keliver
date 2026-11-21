@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package @@PACKAGE@@

import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * NF4 (cold start): a request YOUR first screen makes as soon as it shows (its first page of data),
 * declared by the app (Android: `KeliverConfig.prefetch`; iOS: `Keliver.shared.prefetch`). The host
 * sends it when the process starts toward a screen (Android: the first activity's creation; iOS: a
 * start in the foreground, the first scene entering it, or the first Keliver screen; never in a
 * start for a push, a job or a background launch that shows nothing), while the bundle loads and
 * the screen's first composition runs, and the screen's identical request takes the response
 * instead of waiting for the network. The guest is not changed and cannot tell (no protocol change:
 * any bundle, old or new, benefits or is unaffected).
 *
 * The request is a GET under the API base, sent exactly as a guest's would be (the guests' one
 * HostHttp: the same client, the RequestPolicy, the same checks): a declared request the policy
 * refuses is refused, and sends nothing. The guest's request takes it only when it is the SAME
 * request: GET, the same path and query, no body, and no header of its own (after the headers the
 * HostHttp drops). It is taken once, and only within [PrefetchedRequests]' `maxAgeMillis` of its
 * answer ARRIVING; a prefetch that failed, answered anything but 2xx, or is still in flight
 * `maxWaitMillis` after the guest asked, is not used, and the guest's request goes out as usual.
 *
 * Fresh data, and the right user's: any request other than a GET through the guests' HostHttp drops
 * every response not taken yet (the guest may have changed what it would show), and so does
 * `discardPrefetched()` (Android: `KeliverHost`; iOS: `Keliver.shared`), which the app calls on
 * sign-out or a change of user. The app's credentials are taken as the request goes out (at the
 * start toward a screen, which can be later than the host's start), so the session must be restored
 * by then: the prefetch goes out with whatever they are at that moment.
 *
 * The SAME file is in the Android and the iOS host; keep them identical (common Kotlin: no lock,
 * one atomic state).
 */
class KeliverPrefetch(path: String, query: Map<String, String> = emptyMap()) {
  /** The path's segments: `/items` and `items` are the same request, as HostHttp sends them. */
  val segments: List<String> = path.split('/').filter { it.isNotEmpty() }
  val query: Map<String, String> = query.toMap()

  init {
    require(segments.isNotEmpty()) { "KeliverPrefetch: '$path' names no path under the API base" }
    require(segments.none { it == "." || it == ".." || '\\' in it || '?' in it || '#' in it }) {
      "KeliverPrefetch: '$path' is not a plain relative path (put the query in `query`)"
    }
  }

  fun toRequest(): HttpRequest = HttpRequest(method = "GET", path = segments.joinToString("/"), query = query)

  /** Whether [request] (the guest's), with [keptHeaders] (what the HostHttp would send of its headers), is this one. */
  fun matches(request: HttpRequest, keptHeaders: Map<String, String>): Boolean = mismatch(request, keptHeaders) == null

  /** Whether [request] is a GET of this one's path (a near match, when it is not the same request). */
  internal fun samePath(request: HttpRequest): Boolean =
    request.method.equals("GET", ignoreCase = true) && request.path.split('/').filter { it.isNotEmpty() } == segments

  /** Why [request] is not this one (names only: no header or query values, which can be secrets); null when it is. */
  internal fun mismatch(request: HttpRequest, keptHeaders: Map<String, String>): String? = when {
    !request.method.equals("GET", ignoreCase = true) -> "method ${request.method.uppercase()}"
    request.body != null -> "it has a body"
    request.path.split('/').filter { it.isNotEmpty() } != segments -> "another path"
    request.query != query -> "another query (keys ${request.query.keys.sorted()} against ${query.keys.sorted()}, or other values)"
    keptHeaders.isNotEmpty() -> "headers of its own: ${keptHeaders.keys.map { it.lowercase() }.sorted()}"
    else -> null
  }

  override fun toString(): String = "GET /${segments.joinToString("/")}${if (query.isEmpty()) "" else "?…"}"
}

/**
 * The declared requests' responses (see [KeliverPrefetch]). They are sent once both [arm] (the
 * HostHttp, when the host starts) and [uiStarting] (the start toward a screen) have been called, in
 * either order: a process that never shows a screen sends nothing. [responseFor] hands a guest's
 * matching request its response, once; [discard] drops what was not taken. Thread-safe.
 */
class PrefetchedRequests(
  declared: List<KeliverPrefetch>,
  /** How long a response is kept after it ARRIVED (not after it was sent). */
  private val maxAgeMillis: Long = DEFAULT_PREFETCH_MAX_AGE_MILLIS,
  /** How long a guest's request waits for a prefetch still in flight before it goes out itself. */
  private val maxWaitMillis: Long = DEFAULT_PREFETCH_MAX_WAIT_MILLIS,
  /** Monotonic milliseconds (Android passes SystemClock.elapsedRealtime). */
  private val now: () -> Long = monotonicMillis(),
  /** What went unused, and why (the hosts log it in debug builds only); never header or query values. */
  private val log: (String) -> Unit = {},
) {
  private val declared = declared.toList()

  private class Sent(val prefetch: KeliverPrefetch) {
    lateinit var response: Deferred<HttpResponse>

    /** When the answer (or the failure) arrived; -1 while in flight. */
    @Volatile var arrivedAt: Long = -1

    fun olderThan(maxAgeMillis: Long, at: Long): Boolean = arrivedAt >= 0 && at - arrivedAt > maxAgeMillis
  }

  /** Everything this object knows, replaced as a whole (compare-and-set), never changed in place. */
  private class State(
    val sender: Pair<CoroutineScope, suspend (HttpRequest) -> HttpResponse>? = null,
    val uiStarted: Boolean = false,
    val started: Boolean = false,
    /** Sent and not taken, dropped or expired yet. */
    val sent: List<Sent> = emptyList(),
  ) {
    fun with(
      sender: Pair<CoroutineScope, suspend (HttpRequest) -> HttpResponse>? = this.sender,
      uiStarted: Boolean = this.uiStarted,
      started: Boolean = this.started,
      sent: List<Sent> = this.sent,
    ) = State(sender, uiStarted, started, sent)
  }

  private val state = AtomicReference(State())

  /** Replaces the state with [change]'s answer for it (run again if another thread changed it first); returns the state it replaced. */
  private inline fun update(change: (State) -> State): State {
    while (true) {
      val old = state.load()
      if (state.compareAndSet(old, change(old))) return old
    }
  }

  init {
    require(maxAgeMillis > 0) { "PrefetchedRequests: maxAgeMillis $maxAgeMillis is not a positive duration" }
    require(maxWaitMillis > 0) { "PrefetchedRequests: maxWaitMillis $maxWaitMillis is not a positive duration" }
  }

  /** How the requests go out: [send] in [scope]. Nothing is sent before [uiStarting]. */
  fun arm(scope: CoroutineScope, send: suspend (HttpRequest) -> HttpResponse) {
    update { if (it.sender == null) it.with(sender = scope to send) else it }
    sendIfReady()
  }

  /** The process is starting toward a screen. */
  fun uiStarting() {
    update { if (it.uiStarted) it else it.with(uiStarted = true) }
    sendIfReady()
  }

  /** Sends every declared request, once for this object's life, when both [arm] and [uiStarting] came. */
  private fun sendIfReady() {
    while (true) {
      val s = state.load()
      val (scope, send) = s.sender ?: return
      if (!s.uiStarted || s.started) return
      // Made lazily, and started only once this thread is the one that marked the state started.
      val made = declared.map { p ->
        Sent(p).also { sent ->
          sent.response = scope.async(start = CoroutineStart.LAZY) {
            try {
              send(p.toRequest())
            } finally {
              sent.arrivedAt = now()
            }
          }
        }
      }
      if (!state.compareAndSet(s, s.with(started = true, sent = s.sent + made))) {
        made.forEach { it.response.cancel() }
        continue
      }
      for (sent in made) {
        sent.response.start() // a discard() since the state changed has cancelled it: then nothing goes out
        // Dropped (and logged) when it expires, whether or not a request ever asks for it.
        scope.launch {
          sent.response.join()
          delay(maxAgeMillis + 1)
          expire(now())
        }
      }
      return
    }
  }

  /** Drops every response not taken yet (any non-GET through HostHttp; the app's discardPrefetched()). */
  fun discard(why: String) {
    for (s in update { it.with(sent = emptyList()) }.sent) {
      log("${s.prefetch} dropped untaken: $why")
      s.response.cancel()
    }
  }

  /** Drops what arrived more than maxAgeMillis before [at]. */
  private fun expire(at: Long) {
    var old: List<Sent> = emptyList()
    update { s ->
      old = s.sent.filter { it.olderThan(maxAgeMillis, at) }
      if (old.isEmpty()) s else s.with(sent = s.sent - old.toSet())
    }
    for (s in old) log("${s.prefetch} expired untaken (its answer arrived ${at - s.arrivedAt} ms ago, over $maxAgeMillis)")
  }

  /**
   * The response for [request] if it is a declared one whose answer arrived less than
   * maxAgeMillis ago (or is still in flight), not taken yet; it is taken now. Null otherwise (the
   * caller sends the request itself).
   */
  fun take(request: HttpRequest, keptHeaders: Map<String, String>): Deferred<HttpResponse>? {
    // Too old to hand out: dropped, so a later identical request goes to the network.
    expire(now())
    var taken: Sent? = null
    var near: Sent? = null
    update { s ->
      taken = s.sent.firstOrNull { it.prefetch.matches(request, keptHeaders) }
      near = if (taken == null) s.sent.firstOrNull { it.prefetch.samePath(request) } else null
      taken?.let { t -> s.with(sent = s.sent.filter { it !== t }) } ?: s
    }
    near?.let { log("${it.prefetch} not taken by a GET of the same path: ${it.prefetch.mismatch(request, keptHeaders)}") }
    return taken?.response
  }

  /**
   * The prefetched response for [request] when it is usable: taken ([take]), arrived within
   * maxWaitMillis, and 2xx. Null otherwise: the caller sends the request itself. The caller's own
   * cancellation is not swallowed.
   */
  suspend fun responseFor(request: HttpRequest, keptHeaders: Map<String, String>): HttpResponse? {
    val pending = take(request, keptHeaders) ?: return null
    val what = "the prefetch of GET /${request.path.trimStart('/')}"
    val response = try {
      withTimeoutOrNull(maxWaitMillis) { pending.await() }
    } catch (e: Exception) {
      currentCoroutineContext().ensureActive()
      log("$what failed (${e::class.simpleName}): the request goes out itself")
      return null
    }
    if (response == null) {
      pending.cancel()
      log("$what still in flight after $maxWaitMillis ms: the request goes out itself")
      return null
    }
    if (response.status !in 200..299) {
      log("$what answered ${response.status}: the request goes out itself")
      return null
    }
    return response
  }

  /** Tests: waits until every sent request has answered or failed. */
  internal suspend fun settled() {
    state.load().sent.forEach { it.response.join() }
  }

  companion object {
    /**
     * 5 s after the answer arrives. The response must outlive the gap between its arrival and the
     * guest's request: on the A12 (NF0, THREE_WAY_BENCHMARK.md) the guest asks about 1.4 s after the
     * process start with a cached bundle and about 1.8 s on a first launch, and the answer is there
     * a round trip after the first activity's creation (about 0.4 s), so 1–1.5 s; 5 s is three
     * times that. A start that downloads its code first can take longer: the guest's request then
     * goes out itself, as without a prefetch. Short, so the answer is still the start's own: a
     * change made through HostHttp drops it anyway (any non-GET), and the app drops it on sign-out.
     */
    const val DEFAULT_PREFETCH_MAX_AGE_MILLIS = 5_000L

    /**
     * 1 s. A prefetch sent at the start toward a screen has normally answered before the guest asks
     * (on the A12, about 1 s later); one still in flight then a second later is likely stalled (a
     * network switch), and waiting on it to the client's read timeout (10 s or more) would make the
     * first screen slower than no prefetch at all. The worst case is now 1 s over not having one.
     */
    const val DEFAULT_PREFETCH_MAX_WAIT_MILLIS = 1_000L
  }
}

/** Milliseconds on the monotonic clock, from when it was made. */
private fun monotonicMillis(): () -> Long {
  val origin = TimeSource.Monotonic.markNow()
  return { origin.elapsedNow().inWholeMilliseconds }
}
