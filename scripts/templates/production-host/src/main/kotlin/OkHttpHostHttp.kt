package @@PACKAGE@@

import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The HostHttp capability: guests send a relative request, and it goes to YOUR
 * API base (`keliver.apiBaseUrl`) over real HTTP. Add authentication, retries
 * or certificate pinning here — this is your code now.
 */
class OkHttpHostHttp(private val client: OkHttpClient, apiBaseUrl: String) : HostHttpProvider {
  private val base = apiBaseUrl.trimEnd('/').toHttpUrl()

  override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
    val url = base.newBuilder().apply {
      request.path.trim('/').takeIf { it.isNotEmpty() }?.let { addPathSegments(it) }
      request.query.forEach { (k, v) -> addQueryParameter(k, v) }
    }.build()
    val contentType = request.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
    val method = request.method.uppercase()
    // OkHttp refuses POST/PUT/PATCH without a body; send an empty one.
    val body = request.body?.toRequestBody(contentType?.toMediaTypeOrNull())
      ?: if (method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
    val call = Request.Builder().url(url).method(method, body).apply {
      request.headers.forEach { (k, v) -> header(k, v) }
    }.build()
    client.newCall(call).execute().use { response ->
      HttpResponse(
        status = response.code,
        body = response.body?.string().orEmpty(),
        headers = response.headers.toMultimap().mapValues { it.value.joinToString(",") },
      )
    }
  }
}
