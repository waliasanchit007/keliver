/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.http

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Guest-side typed wrapper over a [HostHttpProvider]. Threads a configurable
 * [json] instance so adopters' kotlinx-serialization settings (date format,
 * polymorphic registrations, lenient parsing, etc.) apply consistently.
 *
 * Usage:
 *
 * ```
 * val http = KeliverHttp(HostHttpProviderBridge.instance!!)
 * val quotes: List<Quote> = http.get("/quotes", mapOf("limit" to "20"))
 * val created: Quote = http.post("/quotes", NewQuote(text = "hello"))
 * ```
 *
 * Non-2xx responses raise [KeliverHttpException]; the raw 4xx / 5xx body is
 * still accessible via [HostHttpProvider.execute] directly if the adopter
 * wants to handle status codes without exceptions.
 *
 * Adopter ergonomics — wrap your endpoints in plain classes that take a
 * `KeliverHttp` in their constructor, the same way Retrofit interfaces are
 * grouped per backend:
 *
 * ```
 * class QuotesApi(private val http: KeliverHttp) {
 *     suspend fun list(filter: String?): List<Quote> =
 *         http.get("/quotes", mapOf("filter" to (filter ?: "")))
 *     suspend fun create(quote: NewQuote): Quote =
 *         http.post("/quotes", quote)
 * }
 * ```
 */
public class KeliverHttp(
  public val provider: HostHttpProvider,
  public val json: Json = Json { ignoreUnknownKeys = true },
) {
  /**
   * Translate a raw [HttpResponse] into either the response body (for
   * downstream typed deserialization) or a thrown [KeliverHttpException].
   * Exposed `@PublishedApi internal` so the inline helpers below can call it.
   */
  @PublishedApi
  internal fun unwrap(response: HttpResponse): HttpResponse {
    if (response.status !in 200..299) {
      throw KeliverHttpException(response.status, response.body)
    }
    return response
  }

  /** Execute a GET, parse the response body as JSON of type [Res]. */
  public suspend inline fun <reified Res> get(
    path: String,
    query: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
  ): Res = json.decodeFromString(
    unwrap(
      provider.execute(
        HttpRequest(method = "GET", path = path, query = query, headers = headers),
      ),
    ).body,
  )

  /**
   * Execute a POST with [body] JSON-encoded; parse the response as [Res].
   * Sets `Content-Type: application/json` by default; pass a `Content-Type`
   * entry in [headers] to override.
   */
  public suspend inline fun <reified Req, reified Res> post(
    path: String,
    body: Req,
    query: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
  ): Res = json.decodeFromString(
    unwrap(
      provider.execute(
        HttpRequest(
          method = "POST",
          path = path,
          query = query,
          headers = JSON_CONTENT_TYPE + headers,
          body = json.encodeToString(body),
        ),
      ),
    ).body,
  )

  /**
   * Execute a PUT with [body] JSON-encoded; parse the response as [Res].
   * Sets `Content-Type: application/json` by default; pass a `Content-Type`
   * entry in [headers] to override.
   */
  public suspend inline fun <reified Req, reified Res> put(
    path: String,
    body: Req,
    query: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
  ): Res = json.decodeFromString(
    unwrap(
      provider.execute(
        HttpRequest(
          method = "PUT",
          path = path,
          query = query,
          headers = JSON_CONTENT_TYPE + headers,
          body = json.encodeToString(body),
        ),
      ),
    ).body,
  )

  /** Execute a DELETE, parse the response body as JSON of type [Res]. */
  public suspend inline fun <reified Res> delete(
    path: String,
    query: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
  ): Res = json.decodeFromString(
    unwrap(
      provider.execute(
        HttpRequest(method = "DELETE", path = path, query = query, headers = headers),
      ),
    ).body,
  )

  /**
   * Execute a DELETE; ignore the response body (treat any 2xx as success,
   * throw [KeliverHttpException] on non-2xx). Use for endpoints that return
   * `204 No Content` or other empty success responses — the typed
   * [delete] would fail trying to deserialize an empty body.
   */
  public suspend fun deleteUnit(
    path: String,
    query: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
  ) {
    unwrap(
      provider.execute(
        HttpRequest(method = "DELETE", path = path, query = query, headers = headers),
      ),
    )
  }

  /**
   * Execute a POST with no request body; parse the response as [Res]. For
   * endpoints that don't expect a body (e.g. "create from a query parameter"
   * or fire-and-forget actions). No `Content-Type` header is set since
   * there's no body to describe.
   */
  public suspend inline fun <reified Res> postEmpty(
    path: String,
    query: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
  ): Res = json.decodeFromString(
    unwrap(
      provider.execute(
        HttpRequest(method = "POST", path = path, query = query, headers = headers, body = null),
      ),
    ).body,
  )

  /** Execute a PUT with no request body; parse the response as [Res]. */
  public suspend inline fun <reified Res> putEmpty(
    path: String,
    query: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
  ): Res = json.decodeFromString(
    unwrap(
      provider.execute(
        HttpRequest(method = "PUT", path = path, query = query, headers = headers, body = null),
      ),
    ).body,
  )

  /**
   * Raw execute — returns the full [HttpResponse] without status-code mapping
   * or body deserialization. Use this when you need to inspect non-2xx
   * responses without try/catch, or when the body isn't JSON.
   */
  public suspend fun requestRaw(request: HttpRequest): HttpResponse =
    provider.execute(request)

  @PublishedApi
  internal companion object {
    /**
     * Default header map prepended (with adopter override priority) to POST
     * and PUT requests that send a JSON-encoded body.
     */
    @PublishedApi
    internal val JSON_CONTENT_TYPE: Map<String, String> =
      mapOf("Content-Type" to "application/json")
  }
}
