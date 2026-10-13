@file:OptIn(ExperimentalForeignApi::class)

package @@PACKAGE@@

import app.cash.zipline.loader.ZiplineHttpClient
import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.time.Duration
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * The HostHttp capability: guests send a RELATIVE request, and it goes to YOUR
 * API base over real HTTP, and nowhere else. The rules match the Android
 * host's OkHttpHostHttp:
 * - `.` and `..` segments and backslashes are refused;
 * - `Host` and hop-by-hop headers from the guest are dropped;
 * - redirects are not followed.
 *
 * Add authentication, retries or certificate pinning here — this is your code.
 */
internal class NSURLSessionHostHttp(private val base: String) : HostHttpProvider {
  private val session = NSURLSession.sessionWithConfiguration(
    configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration,
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
    request.headers.forEach { (k, v) ->
      require(TOKEN.matches(k) && v.none { it == '\r' || it == '\n' || it == '\u0000' }) {
        "HostHttp: header '$k' is not a valid header name and value"
      }
    }
    val query = request.query.entries.joinToString("&") { (k, v) -> "${percentEncode(k)}=${percentEncode(v)}" }
    val url = base.trimEnd('/') + segments.joinToString("") { "/" + percentEncode(it) } +
      (if (query.isEmpty()) "" else "?$query")
    val call = NSMutableURLRequest(
      uRL = NSURL(string = url) ?: throw IOException("HostHttp: not a URL: $url"),
      cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
      timeoutInterval = 30.0,
    ).apply {
      setHTTPMethod(method)
      request.headers.filterKeys { it.lowercase() !in DROPPED_HEADERS }.forEach { (k, v) ->
        setValue(v, forHTTPHeaderField = k)
      }
      request.body?.let { setHTTPBody((it as NSString).dataUsingEncoding(NSUTF8StringEncoding)) }
    }
    val (status, body, headers) = send(session, call)
    return HttpResponse(status = status, body = body.utf8(), headers = headers)
  }

  private companion object {
    val METHOD = Regex("^[A-Z]{1,16}$")
    val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    val DROPPED_HEADERS = setOf(
      "host", "connection", "content-length", "transfer-encoding", "keep-alive",
      "proxy-connection", "te", "trailer", "upgrade",
    )
  }
}
