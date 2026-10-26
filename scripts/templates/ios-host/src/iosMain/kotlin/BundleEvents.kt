/*
 * Live updates (a DEBUG build with UPDATES = "live", the default there): the
 * bundle server's `/bundles/events`, server-sent events the portal relay
 * serves. Each event names the newest bundle; the host only takes it as a
 * nudge to check, so what loads is still what the index names and the
 * signature allows. A static server does not serve the stream, and the host
 * then updates on resume.
 */
@file:OptIn(ExperimentalForeignApi::class)

package @@PACKAGE@@

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.Buffer
import okio.IOException
import okio.ByteString.Companion.toByteString
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setValue
import platform.darwin.NSObject

/** What one connection to `/bundles/events` came to. */
internal enum class LiveStream { NOT_SERVED, HEARD, SILENT }

/** How a stream ended: [kind], and the error that ended it, if one did. */
internal class LiveStreamEnd(val kind: LiveStream, val error: String?)

/** The longest line this protocol sends is far shorter; a longer one is not one of its events. */
private const val MAX_LINE_BYTES = 4_096L

/**
 * Holds one GET of [url] open until the server closes it, it is silent for
 * 45 s (the server sends a comment every 15 s, so that is a dead link), or the
 * caller is cancelled. [onListening] is called once the stream is accepted and
 * [onEvent] with each `data:` line, both on the main thread.
 *
 * NOT_SERVED: the server has no stream. A 3xx (never followed), a 4xx other
 * than 408 or 429 (S3 and CloudFront answer 403 for a missing key), or a 2xx
 * that is not `text/event-stream` (a catch-all rewrite). Any other status ends
 * the stream with an error, to be tried again.
 */
internal suspend fun readBundleEvents(url: String, onListening: () -> Unit, onEvent: (String) -> Unit): LiveStreamEnd =
  suspendCancellableCoroutine { continuation ->
    val request = NSMutableURLRequest(
      uRL = NSURL(string = url) ?: throw IOException("not a URL: $url"),
      cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
      timeoutInterval = 45.0,
    ).apply { setValue("text/event-stream", forHTTPHeaderField = "Accept") }
    val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
      timeoutIntervalForRequest = 45.0 // between bytes: the server's comments keep a live stream under it
      timeoutIntervalForResource = 7.0 * 24 * 3600 // no limit on the stream as a whole
    }
    // The delegate runs on the main queue: it touches the host's state only there.
    val session = NSURLSession.sessionWithConfiguration(
      configuration = configuration,
      delegate = EventStream(onListening, onEvent) { if (continuation.isActive) continuation.resumeWith(it) },
      delegateQueue = NSOperationQueue.mainQueue,
    )
    val task = session.dataTaskWithRequest(request)
    continuation.invokeOnCancellation { task.cancel() }
    task.resume()
    session.finishTasksAndInvalidate()
  }

/** One stream's delegate: splits it into lines and reports its end once. */
private class EventStream(
  private val onListening: () -> Unit,
  private val onEvent: (String) -> Unit,
  private val done: (Result<LiveStreamEnd>) -> Unit,
) : NSObject(), NSURLSessionDataDelegateProtocol {
  private val pending = Buffer()
  private var notServed = false
  private var failure: String? = null
  private var heard = false

  override fun URLSession(
    session: NSURLSession,
    dataTask: NSURLSessionDataTask,
    didReceiveResponse: NSURLResponse,
    completionHandler: (NSURLSessionResponseDisposition) -> Unit,
  ) {
    val status = (didReceiveResponse as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
    val type = didReceiveResponse.MIMEType.orEmpty().lowercase()
    when {
      status in 300..399 || (status in 400..499 && status != 408 && status != 429) ||
        (status in 200..299 && type != "text/event-stream") -> notServed = true
      status !in 200..299 -> failure = "HTTP $status"
    }
    if (notServed || failure != null) {
      completionHandler(NSURLSessionResponseCancel)
    } else {
      onListening()
      completionHandler(NSURLSessionResponseAllow)
    }
  }

  override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
    if (failure != null) return
    pending.write(didReceiveData.toByteString())
    while (true) {
      val end = pending.indexOf('\n'.code.toByte())
      if (end == -1L) {
        if (pending.size > MAX_LINE_BYTES) {
          failure = "a line longer than $MAX_LINE_BYTES bytes"
          dataTask.cancel()
        }
        return
      }
      val line = pending.readUtf8(end).removeSuffix("\r")
      pending.skip(1)
      if (line.startsWith("data:")) {
        heard = true
        onEvent(line.removePrefix("data:").trim())
      }
    }
  }

  override fun URLSession(
    session: NSURLSession,
    task: NSURLSessionTask,
    willPerformHTTPRedirection: NSHTTPURLResponse,
    newRequest: NSURLRequest,
    completionHandler: (NSURLRequest?) -> Unit,
  ) = completionHandler(null)

  override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
    val error = failure ?: didCompleteWithError?.localizedDescription
    done(
      when {
        notServed -> Result.success(LiveStreamEnd(LiveStream.NOT_SERVED, null))
        heard -> Result.success(LiveStreamEnd(LiveStream.HEARD, error))
        error != null -> Result.failure(IOException(error))
        else -> Result.success(LiveStreamEnd(LiveStream.SILENT, null))
      },
    )
  }
}
