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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class KeliverHttpTest {

  @Serializable
  data class Quote(val id: Int, val text: String)

  @Serializable
  data class NewQuote(val text: String)

  private class StubProvider(
    var response: HttpResponse = HttpResponse(200, "[]"),
  ) : HostHttpProvider {
    var lastRequest: HttpRequest? = null
    override suspend fun execute(request: HttpRequest): HttpResponse {
      lastRequest = request
      return response
    }
  }

  @Test
  fun get_round_trips_typed_response() = runTest {
    val json = Json
    val quotes = listOf(Quote(1, "First"), Quote(2, "Second"))
    val stub = StubProvider(HttpResponse(200, json.encodeToString(quotes)))
    val http = KeliverHttp(stub, json)

    val result: List<Quote> = http.get("/quotes", mapOf("limit" to "20"))

    assertEquals(quotes, result)
    assertEquals("GET", stub.lastRequest?.method)
    assertEquals("/quotes", stub.lastRequest?.path)
    assertEquals(mapOf("limit" to "20"), stub.lastRequest?.query)
  }

  @Test
  fun post_serializes_body_and_parses_response() = runTest {
    val json = Json
    val newQuote = NewQuote("Hello")
    val created = Quote(99, "Hello")
    val stub = StubProvider(HttpResponse(201, json.encodeToString(created)))
    val http = KeliverHttp(stub, json)

    val result: Quote = http.post("/quotes", newQuote)

    assertEquals(created, result)
    assertEquals("POST", stub.lastRequest?.method)
    assertEquals(json.encodeToString(newQuote), stub.lastRequest?.body)
  }

  @Test
  fun post_sets_application_json_content_type_by_default() = runTest {
    val stub = StubProvider(HttpResponse(201, """{"id":1,"text":"x"}"""))
    val http = KeliverHttp(stub)

    http.post<NewQuote, Quote>("/quotes", NewQuote("x"))

    assertEquals("application/json", stub.lastRequest?.headers?.get("Content-Type"))
  }

  @Test
  fun post_allows_adopter_to_override_content_type() = runTest {
    val stub = StubProvider(HttpResponse(201, """{"id":1,"text":"x"}"""))
    val http = KeliverHttp(stub)

    http.post<NewQuote, Quote>(
      "/quotes",
      NewQuote("x"),
      headers = mapOf("Content-Type" to "application/vnd.example+json"),
    )

    assertEquals(
      "application/vnd.example+json",
      stub.lastRequest?.headers?.get("Content-Type"),
    )
  }

  @Test
  fun post_passes_query_parameters_through() = runTest {
    val stub = StubProvider(HttpResponse(201, """{"id":1,"text":"x"}"""))
    val http = KeliverHttp(stub)

    http.post<NewQuote, Quote>(
      "/quotes",
      NewQuote("x"),
      query = mapOf("notify" to "true"),
    )

    assertEquals(mapOf("notify" to "true"), stub.lastRequest?.query)
  }

  @Test
  fun put_sets_application_json_content_type_by_default() = runTest {
    val stub = StubProvider(HttpResponse(200, """{"id":1,"text":"x"}"""))
    val http = KeliverHttp(stub)

    http.put<NewQuote, Quote>("/quotes/1", NewQuote("x"))

    assertEquals("PUT", stub.lastRequest?.method)
    assertEquals("application/json", stub.lastRequest?.headers?.get("Content-Type"))
  }

  @Test
  fun deleteUnit_succeeds_on_2xx_with_empty_body() = runTest {
    val stub = StubProvider(HttpResponse(204, ""))
    val http = KeliverHttp(stub)

    http.deleteUnit("/quotes/1")

    assertEquals("DELETE", stub.lastRequest?.method)
    assertEquals("/quotes/1", stub.lastRequest?.path)
  }

  @Test
  fun deleteUnit_throws_on_4xx() = runTest {
    val stub = StubProvider(HttpResponse(403, "Forbidden"))
    val http = KeliverHttp(stub)

    val ex = assertFailsWith<KeliverHttpException> {
      http.deleteUnit("/quotes/1")
    }
    assertEquals(403, ex.status)
    assertEquals("Forbidden", ex.body)
  }

  @Test
  fun postEmpty_sends_no_body_and_parses_typed_response() = runTest {
    val stub = StubProvider(HttpResponse(201, """{"id":1,"text":"x"}"""))
    val http = KeliverHttp(stub)

    val result: Quote = http.postEmpty("/quotes/regenerate")

    assertEquals(Quote(1, "x"), result)
    assertEquals("POST", stub.lastRequest?.method)
    assertNull(stub.lastRequest?.body)
  }

  @Test
  fun putEmpty_sends_no_body_and_parses_typed_response() = runTest {
    val stub = StubProvider(HttpResponse(200, """{"id":1,"text":"x"}"""))
    val http = KeliverHttp(stub)

    val result: Quote = http.putEmpty("/quotes/1/touch")

    assertEquals(Quote(1, "x"), result)
    assertEquals("PUT", stub.lastRequest?.method)
    assertNull(stub.lastRequest?.body)
  }

  @Test
  fun non_2xx_throws_KeliverHttpException_with_status_and_body() = runTest {
    val stub = StubProvider(HttpResponse(404, "Not found"))
    val http = KeliverHttp(stub)

    val ex = assertFailsWith<KeliverHttpException> {
      http.get<Quote>("/quotes/999")
    }

    assertEquals(404, ex.status)
    assertEquals("Not found", ex.body)
  }

  @Test
  fun server_error_5xx_also_raises_KeliverHttpException() = runTest {
    val stub = StubProvider(HttpResponse(503, "Service Unavailable"))
    val http = KeliverHttp(stub)
    val ex = assertFailsWith<KeliverHttpException> {
      http.get<Quote>("/quotes/1")
    }
    assertEquals(503, ex.status)
  }

  @Test
  fun requestRaw_returns_response_for_non_2xx_without_throwing() = runTest {
    val stub = StubProvider(HttpResponse(404, "Not found"))
    val http = KeliverHttp(stub)

    val response = http.requestRaw(HttpRequest("GET", "/quotes/999"))

    assertEquals(404, response.status)
    assertEquals("Not found", response.body)
  }

  @Test
  fun custom_json_config_is_used() = runTest {
    val strictJson = Json { ignoreUnknownKeys = false }
    // Response includes an extra field not in Quote — strict Json should fail.
    val stub = StubProvider(HttpResponse(200, """{"id":1,"text":"x","extra":"surprise"}"""))
    val http = KeliverHttp(stub, strictJson)

    assertFailsWith<Exception> {
      http.get<Quote>("/quotes/1")
    }
  }

  @Test
  fun cancellation_propagates_through_KeliverHttp_to_provider() = runTest {
    val started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val blockingProvider = object : HostHttpProvider {
      override suspend fun execute(request: HttpRequest): HttpResponse {
        started.complete(Unit)
        try {
          awaitCancellation()
        } finally {
          cancelled.complete(Unit)
        }
      }
    }
    val http = KeliverHttp(blockingProvider)

    val job = launch(Dispatchers.Unconfined) {
      http.get<Quote>("/quotes/1")
    }

    withContext(Dispatchers.Unconfined) {
      started.await()
    }
    assertTrue(job.isActive)

    job.cancel()

    withContext(Dispatchers.Unconfined) {
      cancelled.await()
    }
    assertNotNull(cancelled)
  }
}
