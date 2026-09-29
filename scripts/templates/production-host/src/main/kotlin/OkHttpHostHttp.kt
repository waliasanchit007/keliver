package @@PACKAGE@@

import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
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
 * - redirects are not followed, so a response cannot send the call elsewhere.
 *
 * Add authentication, retries or certificate pinning here — this is your code.
 * Header values are joined with ", "; a multi-valued `Set-Cookie` does not
 * survive that, because HttpResponse carries one string per header.
 */
class OkHttpHostHttp(client: OkHttpClient, private val base: HttpUrl) : HostHttpProvider {
  private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

  override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
    val segments = request.path.split('/').filter { it.isNotEmpty() }
    require(segments.none { it == "." || it == ".." || '\\' in it }) {
      "HostHttp: '${request.path}' is not a plain relative path under the API base"
    }
    val url = base.newBuilder().apply {
      segments.forEach { addPathSegment(it) }
      request.query.forEach { (k, v) -> addQueryParameter(k, v) }
    }.build()
    val headers = request.headers.filterKeys { it.lowercase() !in DROPPED_HEADERS }
    val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
    val method = request.method.uppercase()
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
    )
  }
}
