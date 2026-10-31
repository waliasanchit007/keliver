@file:OptIn(ExperimentalForeignApi::class)

package @@PACKAGE@@

import app.cash.zipline.loader.ZiplineHttpClient
import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.time.Duration
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import platform.Foundation.NSHTTPCookieAcceptPolicy
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.IOException
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.addValue
import platform.Foundation.appendData
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One request; returns (status, body bytes, headers). Never follows a redirect when [session] says so. */
internal suspend fun send(
  session: NSURLSession,
  request: NSURLRequest,
): Triple<Int, ByteString, Map<String, String>> = suspendCancellableCoroutine { continuation ->
  val task = session.dataTaskWithRequest(
    request = request,
    completionHandler = { data: NSData?, response: NSURLResponse?, error: NSError? ->
      when {
        error != null -> continuation.resumeWithException(IOException(error.localizedDescription))
        response !is NSHTTPURLResponse -> continuation.resumeWithException(IOException("not an HTTP response: $response"))
        else -> continuation.resume(
          Triple(
            response.statusCode.toInt(),
            data?.toByteString() ?: ByteString.EMPTY,
            response.allHeaderFields.entries.associate { (k, v) -> k.toString() to v.toString() },
          ),
        )
      }
    },
  )
  continuation.invokeOnCancellation { task.cancel() }
  task.resume()
}

internal fun getRequest(url: String, timeoutSeconds: Double): NSURLRequest =
  NSMutableURLRequest(
    uRL = NSURL(string = url) ?: throw IOException("not a URL: $url"),
    cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
    timeoutInterval = timeoutSeconds,
  )

/**
 * A GET for the bundle lookup: refused past [maxBytes] (at its Content-Length,
 * or as soon as more arrives, so a huge answer is never held in memory), and
 * cancelled at [timeout] in all. NSURLRequest's timeoutInterval is only an idle
 * timeout: a server that trickles bytes never trips it.
 *
 * One session per request, invalidated when its task finishes, because the
 * delegate (which counts the bytes) belongs to this one task.
 */
internal suspend fun sendBounded(request: NSURLRequest, maxBytes: Long, timeout: Duration): Pair<Int, ByteString> {
  if (!timeout.isPositive()) throw IOException("the bundle lookup used up its time")
  return withTimeoutOrNull(timeout) {
    suspendCancellableCoroutine { continuation ->
      val session = NSURLSession.sessionWithConfiguration(
        configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration,
        delegate = BoundedFetch(maxBytes) { continuation.resumeWith(it) },
        delegateQueue = null,
      )
      val task = session.dataTaskWithRequest(request)
      continuation.invokeOnCancellation { task.cancel() }
      task.resume()
      session.finishTasksAndInvalidate()
    }
  } ?: throw IOException("timed out: ${request.URL?.absoluteString} did not answer within $timeout")
}

/** Collects one task's body up to [maxBytes]; [done] is called once, when the task completes. */
private class BoundedFetch(
  private val maxBytes: Long,
  private val done: (Result<Pair<Int, ByteString>>) -> Unit,
) : NSObject(), NSURLSessionDataDelegateProtocol {
  private val data = NSMutableData()
  private var status = 0
  private var refused: String? = null

  override fun URLSession(
    session: NSURLSession,
    dataTask: NSURLSessionDataTask,
    didReceiveResponse: NSURLResponse,
    completionHandler: (NSURLSessionResponseDisposition) -> Unit,
  ) {
    status = (didReceiveResponse as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
    val declared = didReceiveResponse.expectedContentLength
    if (declared > maxBytes) {
      refused = "the answer is larger than $maxBytes bytes (Content-Length $declared)"
      completionHandler(NSURLSessionResponseCancel)
    } else {
      completionHandler(NSURLSessionResponseAllow)
    }
  }

  override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
    if (refused != null) return
    if (data.length.toLong() + didReceiveData.length.toLong() > maxBytes) {
      refused = "the answer is larger than $maxBytes bytes"
      dataTask.cancel()
      return
    }
    data.appendData(didReceiveData)
  }

  override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
    val why = refused
    done(
      when {
        why != null -> Result.failure(IOException(why))
        didCompleteWithError != null -> Result.failure(IOException(didCompleteWithError.localizedDescription))
        status == 0 -> Result.failure(IOException("not an HTTP response"))
        else -> Result.success(status to data.toByteString())
      },
    )
  }
}

/** A JSON POST: a host report (W6). */
internal fun postJsonRequest(url: String, json: String, timeoutSeconds: Double): NSURLRequest =
  NSMutableURLRequest(
    uRL = NSURL(string = url) ?: throw IOException("not a URL: $url"),
    cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
    timeoutInterval = timeoutSeconds,
  ).apply {
    setHTTPMethod("POST")
    setValue("application/json", forHTTPHeaderField = "Content-Type")
    setHTTPBody((json as NSString).dataUsingEncoding(NSUTF8StringEncoding))
  }

/** Zipline's downloads (manifests and modules), over NSURLSession. */
internal class NSURLSessionZiplineHttpClient(
  private val session: NSURLSession = NSURLSession.sharedSession,
) : ZiplineHttpClient() {
  override suspend fun download(url: String, requestHeaders: List<Pair<String, String>>): ByteString {
    val request = NSMutableURLRequest(
      uRL = NSURL(string = url) ?: throw IOException("not a URL: $url"),
      cachePolicy = NSURLRequestUseProtocolCachePolicy,
      timeoutInterval = 60.0,
    ).apply { requestHeaders.forEach { (k, v) -> addValue(v, forHTTPHeaderField = k) } }
    val (status, body, _) = send(session, request)
    if (status !in 200 until 300) throw IOException("failed to fetch $url: HTTP $status")
    return body
  }
}

/** Refuses every redirect: the completion handler gets null, so the 3xx itself is returned. */
private class NoRedirects : NSObject(), NSURLSessionTaskDelegateProtocol {
  override fun URLSession(
    session: NSURLSession,
    task: NSURLSessionTask,
    willPerformHTTPRedirection: NSHTTPURLResponse,
    newRequest: NSURLRequest,
    completionHandler: (NSURLRequest?) -> Unit,
  ) = completionHandler(null)
}

/** For host reports (W6): ephemeral, and no redirect is followed (a 3xx is the answer). */
internal val reportSession: NSURLSession by lazy {
  NSURLSession.sessionWithConfiguration(
    configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration,
    delegate = NoRedirects(),
    delegateQueue = null,
  )
}

/**
 * B2: your app's credentials for one guest HostHttp request ([Keliver.requestHeaders]). It is
 * called on the main thread with the request's method and its path relative to API_BASE_URL,
 * both already allowed by the [RequestPolicy]. Call `done` once, with the headers to add, or
 * with null to refuse the request (nothing is sent).
 */
public typealias KeliverRequestHeaders = (method: String, path: String, done: (Map<String, String>?) -> Unit) -> Unit

/**
 * The HostHttp capability: guests send a RELATIVE request, and it goes to YOUR
 * API base over real HTTP, and nowhere else. The rules match the Android
 * host's OkHttpHostHttp:
 * - `.` and `..` segments and backslashes are refused;
 * - `Host` and hop-by-hop headers from the guest are dropped;
 * - redirects are not followed;
 * - with a [RequestPolicy], only its origin and its exact `(method, path)`
 *   pairs, checked before anything is sent; a refused request fails in the
 *   guest and is logged (the path only in a DEBUG binary);
 * - the method-override and path-override headers (`X-Original-URL` and kin)
 *   are dropped;
 * - with [appHeaders] (B2, [Keliver.requestHeaders]: your app's credentials),
 *   it needs a [RequestPolicy] (without one every request is refused), and
 *   is asked for each request the policy allowed; the guest's own
 *   `Authorization`, `Proxy-Authorization` and `Cookie` are dropped, a
 *   response's `Set-Cookie` is not handed back, and this session keeps no
 *   cookies, cache or credentials of its own. Other credentials (a query
 *   parameter, an API-key header) are your callback's to set. No code of
 *   yours runs between the check and the wire, so the URL sent is the one
 *   checked.
 *
 * Retries or certificate pinning: add them here — this is your code.
 */
internal class NSURLSessionHostHttp(
  private val base: String,
  private val policy: RequestPolicy? = null,
  private val appHeaders: KeliverRequestHeaders? = null,
  /** Where [appHeaders] is called: the main thread (a test passes another context). */
  private val appHeadersContext: CoroutineContext = Dispatchers.Main,
  private val appHeadersTimeout: Duration = 30.seconds,
) : HostHttpProvider {
  private val appOwnsCredentials = appHeaders != null

  /** Set when nothing can be allowed: a wrong origin, or your credentials with no policy. */
  private val baseRefusal: String? = (
    if (appHeaders != null && policy == null) {
      "Keliver.requestHeaders needs a requestPolicy (the paths guests may call with your credentials)"
    } else {
      policy?.baseRefusal(base)
    }
  )?.also { println("KeliverHost: HostHttp: $it; every request is refused") }

  private val session = NSURLSession.sessionWithConfiguration(
    configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
      if (appOwnsCredentials) {
        // Your credentials come from requestHeaders alone: nothing a response set is sent again.
        HTTPShouldSetCookies = false
        HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever
        HTTPCookieStorage = null
        URLCache = null
        URLCredentialStorage = null
      }
    },
    delegate = NoRedirects(),
    delegateQueue = null,
  )

  override suspend fun execute(request: HttpRequest): HttpResponse {
    val segments = request.path.split('/').filter { it.isNotEmpty() }
    require(segments.none { it == "." || it == ".." || '\\' in it }) {
      "HostHttp: '${request.path}' is not a plain relative path under the API base"
    }
    val method = request.method.uppercase()
    require(METHOD.matches(method)) { "HostHttp: '${request.method}' is not an HTTP method" }
    // A path can carry ids: logged only in a DEBUG binary (baseRefusal names no path).
    baseRefusal?.let { refused(it) }
    policy?.refusal(method, request.path)?.let { why ->
      refused(why, if (isDebugBinary) why else "a request outside the app's RequestPolicy")
    }
    // TRACE echoes the request, your credentials included, back to the guest; CONNECT is for proxies.
    if (appOwnsCredentials && method in ECHOING_METHODS) refused("$method is never sent with the app's credentials")
    request.headers.forEach { (k, v) ->
      require(validHeader(k, v)) { "HostHttp: header '$k' is not a valid header name and value" }
    }
    val added = appHeaders?.let { credentials(it, method, segments.joinToString("/")) }.orEmpty()
    val replaced = added.keys.mapTo(HashSet()) { it.lowercase() }
    val query = request.query.entries.joinToString("&") { (k, v) -> "${percentEncode(k)}=${percentEncode(v)}" }
    val url = base.trimEnd('/') + segments.joinToString("") { "/" + percentEncode(it) } +
      (if (query.isEmpty()) "" else "?$query")
    val call = NSMutableURLRequest(
      uRL = NSURL(string = url) ?: throw IOException("HostHttp: not a URL: $url"),
      cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
      timeoutInterval = 30.0,
    ).apply {
      setHTTPMethod(method)
      request.headers.filterKeys {
        val name = it.lowercase()
        name !in DROPPED_HEADERS && name !in replaced && !(appOwnsCredentials && name in CREDENTIAL_HEADERS)
      }.forEach { (k, v) -> setValue(v, forHTTPHeaderField = k) }
      added.filterKeys { it.lowercase() !in DROPPED_HEADERS }.forEach { (k, v) -> setValue(v, forHTTPHeaderField = k) }
      request.body?.let { setHTTPBody((it as NSString).dataUsingEncoding(NSUTF8StringEncoding)) }
    }
    val (status, body, headers) = send(session, call)
    return HttpResponse(
      status = status,
      body = body.utf8(),
      headers = headers.filterKeys { !(appOwnsCredentials && it.lowercase() in RESPONSE_CREDENTIAL_HEADERS) },
    )
  }

  /**
   * Asks your [KeliverRequestHeaders] for this request's credentials: null, none in time, or a bad
   * header refuses it. The call is posted to [appHeadersContext] and only the answer is awaited,
   * so a busy main thread, or a callback that blocks it, refuses the request in time rather than
   * holding the guest's call (a `withContext` there would wait for the main thread even when
   * cancelled). A call that reaches the main thread after the time is up is not made.
   */
  private suspend fun credentials(appHeaders: KeliverRequestHeaders, method: String, path: String): Map<String, String> {
    val answer = CompletableDeferred<Map<String, String>?>()
    val asked = CoroutineScope(appHeadersContext).launch {
      if (answer.isCompleted) return@launch
      // Your callback must not take the guest's call down with it; a second `done` is ignored.
      runCatching { appHeaders(method, path) { answer.complete(it) } }.onFailure {
        // Its message could hold a token: logged only in a DEBUG binary.
        println("KeliverHost: HostHttp: requestHeaders threw${if (isDebugBinary) ": ${it.message}" else ""}")
        answer.complete(null)
      }
    }
    var timedOut = false
    val added = try {
      // Wrapped, so that `done(null)` (refused) and no answer in time (timed out) differ.
      val got = withTimeoutOrNull(appHeadersTimeout) { Answer(answer.await()) }
      if (got == null) timedOut = true
      got?.headers
    } finally {
      // Timed out, or the guest's call was cancelled: no answer is taken from now on, and a call
      // still waiting for the main thread is not made.
      answer.complete(null)
      asked.cancel()
    }
    if (added == null) {
      refused(if (timedOut) "the app's requestHeaders did not answer within $appHeadersTimeout" else "the app's requestHeaders gave no headers")
    }
    added.forEach { (k, v) ->
      if (!validHeader(k, v)) refused("the app's requestHeaders gave header '$k', which is not a valid header name and value")
    }
    return added
  }

  private fun refused(why: String, logged: String = why): Nothing {
    println("KeliverHost: HostHttp refused: $logged")
    throw HostHttpRefused("HostHttp refused: $why")
  }

  private fun validHeader(name: String, value: String) =
    TOKEN.matches(name) && value.none { it == '\r' || it == '\n' || it == '\u0000' }

  private companion object {
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    val isDebugBinary = kotlin.native.Platform.isDebugBinary
    val METHOD = Regex("^[A-Z]{1,16}$")
    val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    val DROPPED_HEADERS = setOf(
      "host", "connection", "content-length", "transfer-encoding", "keep-alive",
      "proxy-connection", "te", "trailer", "upgrade",
      "x-http-method-override", "x-http-method", "x-method-override",
      "x-original-url", "x-rewrite-url", "x-forwarded-host", "x-forwarded-prefix", "x-forwarded-proto",
    )
    val CREDENTIAL_HEADERS = setOf("authorization", "proxy-authorization", "cookie")
    val RESPONSE_CREDENTIAL_HEADERS = setOf("set-cookie", "set-cookie2")
    val ECHOING_METHODS = setOf("TRACE", "TRACK", "CONNECT")
  }

  private class Answer(val headers: Map<String, String>?)
}

/** A request the app's [RequestPolicy] does not allow; nothing was sent. */
public class HostHttpRefused(message: String) : IllegalArgumentException(message)
