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
package dev.keliver.image

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.decodeBase64

/**
 * Coil's ktor3 fetcher is compiled against an older Ktor than the one keliver-image resolves on iOS
 * (coil-network-ktor3 3.6.3 asks for ktor-client-core 3.1.0; the catalog's Darwin engine lifts it).
 * Kotlin/Native links such a mismatch leniently and reports a missing symbol only when it is reached,
 * so these tests reach it: a load through the fetcher, and a request through the real Darwin engine.
 */
class KtorFetcherTest {
  private fun imageLoader(fetcher: ComponentRegistry.Builder.() -> Unit) =
    ImageLoader.Builder(PlatformContext.INSTANCE)
      .components { fetcher() }
      .memoryCachePolicy(CachePolicy.DISABLED)
      .diskCachePolicy(CachePolicy.DISABLED)
      .build()

  private fun request(url: String) = ImageRequest.Builder(PlatformContext.INSTANCE).data(url).build()

  @Test
  fun loadsAnImageThroughTheKtorFetcher() = runTest {
    val requested = mutableListOf<String>()
    val engine = MockEngine { request ->
      requested += request.url.toString()
      respond(onePixelPng, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
    }
    val loader = imageLoader { add(KtorNetworkFetcherFactory(httpClient = { HttpClient(engine) })) }

    val result = loader.execute(request("https://images.example/pixel.png"))

    assertIs<SuccessResult>(result, (result as? ErrorResult)?.throwable?.toString())
    assertEquals(1, result.image.width)
    assertEquals(1, result.image.height)
    assertEquals(listOf("https://images.example/pixel.png"), requested)
  }

  @Test
  fun theDefaultFetcherRequestsThroughTheDarwinEngine() = runTest {
    // Nothing listens on port 1, so NSURLSession fails the request at once: what matters is that
    // the request went through Coil, Ktor and the Darwin engine and came back as Darwin's error.
    val loader = imageLoader { installCoilNetworkFetcher() }

    val result = loader.execute(request("http://127.0.0.1:1/pixel.png"))

    assertIs<ErrorResult>(result)
    assertIs<DarwinHttpRequestException>(result.throwable, result.throwable.toString())
  }

  private companion object {
    val onePixelPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
      .decodeBase64()!!
      .toByteArray()
  }
}
