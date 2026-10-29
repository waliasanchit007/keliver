package @@PACKAGE@@

import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The HostHttp capability: guests send a RELATIVE request, and it goes to YOUR
 * API base (`keliver.apiBaseUrl`) over real HTTP — and nowhere else:
 *
 * - `.` and `..` segments and backslashes are refused, so a path cannot climb
 *   out of the base path (OkHttp would otherwise pop `..`);
 * - `Host` and hop-by-hop headers from the guest are dropped;
 * - redirects are not followed, so a response cannot send the call elsewhere;
 * - with a [RequestPolicy], only its origin and its exact `(method, path)`
 *   pairs, checked before anything is sent; a refused request fails in the
 *   guest and is logged here (the path only in a debug build);
 * - the method-override headers are dropped, so an allowed POST cannot be
 *   turned into another method on servers that honour them;
 * - with [appOwnsCredentials] (your own client, `KeliverConfig.httpClient`),
 *   the guest's `Authorization`, `Proxy-Authorization` and `Cookie` headers are
 *   dropped too: your client's interceptor adds the user's credentials, and a
 *   guest cannot replace or add its own.
 *
 * Authentication, retries or certificate pinning: pass your app's OkHttpClient
 * as `KeliverConfig.httpClient` (it then needs a [RequestPolicy]), or edit this
 * code — it is yours.
 * Header values are joined with ", "; a multi-valued `Set-Cookie` does not
 * survive that, because HttpResponse carries one string per header.
 */
class OkHttpHostHttp(
  client: OkHttpClient,
  private val base: HttpUrl,
  private val policy: RequestPolicy? = null,
  private val appOwnsCredentials: Boolean = false,
) : HostHttpProvider {
  private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

  /** Set when the policy cannot allow anything on [base] (a wrong origin): every request is refused. */
  private val baseRefusal: String? = policy?.baseRefusal(base)?.also { Log.e(TAG, "HostHttp: $it; every request is refused") }

  override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
    val segments = request.path.split('/').filter { it.isNotEmpty() }
    require(segments.none { it == "." || it == ".." || '\\' in it }) {
      "HostHttp: '${request.path}' is not a plain relative path under the API base"
    }
    val url = base.newBuilder().apply {
      segments.forEach { addPathSegment(it) }
      request.query.forEach { (k, v) -> addQueryParameter(k, v) }
    }.build()
    val method = request.method.uppercase()
    if (policy != null) {
      (baseRefusal ?: policy.refusal(method, request.path))?.let { why ->
        // A path can carry ids: logged only in a debug build.
        Log.w(TAG, "HostHttp refused: ${if (BuildConfig.DEBUG) why else "a request outside the app's RequestPolicy"}")
        throw HostHttpRefused("HostHttp refused: $why")
      }
    }
    val headers = request.headers.filterKeys {
      val name = it.lowercase()
      name !in DROPPED_HEADERS && !(appOwnsCredentials && name in CREDENTIAL_HEADERS)
    }
    val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
    // OkHttp refuses POST/PUT/PATCH without a body; send an empty one.
    val body = request.body?.toRequestBody(contentType?.toMediaTypeOrNull())
      ?: if (method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
    val call = Request.Builder().url(url).method(method, body).apply {
      headers.forEach { (k, v) -> header(k, v) }
    }.build()
    client.newCall(call).execute().use { response ->
      HttpResponse(
        status = response.code,
        body = response.body?.string().orEmpty(),
        headers = response.headers.toMultimap().mapValues { it.value.joinToString(", ") },
      )
    }
  }

  private companion object {
    val DROPPED_HEADERS = setOf(
      "host", "connection", "content-length", "transfer-encoding", "keep-alive",
      "proxy-connection", "te", "trailer", "upgrade",
      "x-http-method-override", "x-http-method", "x-method-override",
    )
    val CREDENTIAL_HEADERS = setOf("authorization", "proxy-authorization", "cookie")
  }
}

/** A request the app's [RequestPolicy] does not allow; nothing was sent. */
class HostHttpRefused(message: String) : IllegalArgumentException(message)
