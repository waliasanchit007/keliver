package @@PACKAGE@@

import dev.keliver.http.HttpRequest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * B1: guests' HostHttp through the app's own authenticated OkHttpClient, behind
 * the RequestPolicy, against a local server: what is allowed carries the app's
 * credentials, and what is refused never reaches the app's client.
 */
class AppClientHostHttpTest {
  private val server = MockWebServer()

  /** Requests that reached the app's client (its interceptor saw them). */
  private val seen = AtomicInteger()

  /** The app's client: it signs every request with the user's token, and follows redirects (OkHttp's default). */
  private val appClient = OkHttpClient.Builder()
    .addInterceptor { chain ->
      seen.incrementAndGet()
      chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer user-token").build())
    }
    .build()

  @Before fun start() = server.start()

  @After fun stop() = server.close()

  private fun base() = server.url("/api/")

  private fun policy(vararg rules: RequestPolicy.Rule) =
    RequestPolicy(base(), rules.toSet(), allowCleartext = true)

  private fun http(policy: RequestPolicy, base: okhttp3.HttpUrl = base()) =
    OkHttpHostHttp(appClient, base, policy, appOwnsCredentials = true)

  private fun refused(block: suspend () -> Unit): String = try {
    runBlocking { block() }
    fail("the request was not refused"); ""
  } catch (e: HostHttpRefused) {
    e.message.orEmpty()
  }

  @Test fun anAllowedRequestCarriesTheAppsCredentials() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).body("[]").build())
    val response = http(policy(RequestPolicy.Rule("GET", "orders/recent"))).execute(HttpRequest("GET", "orders/recent"))
    assertEquals(200, response.status)
    val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
    assertEquals("/api/orders/recent", recorded.url.encodedPath)
    assertEquals("Bearer user-token", recorded.headers["Authorization"])
    assertEquals(1, seen.get())
  }

  @Test fun aRefusedRequestNeverReachesTheAppsClient() {
    val why = refused { http(policy(RequestPolicy.Rule("GET", "orders/recent"))).execute(HttpRequest("GET", "account/delete")) }
    assertTrue(why, "GET /account/delete is not in the allow-list" in why)
    assertEquals(0, seen.get())
    assertEquals(0, server.requestCount)
  }

  @Test fun anotherOriginIsRefusedBeforeTheAppsClient() {
    // The policy names this server; the API base is another origin.
    val elsewhere = "https://api.elsewhere.example.com/api/".toHttpUrl()
    val why = refused { http(policy(RequestPolicy.Rule("GET", "orders")), base = elsewhere).execute(HttpRequest("GET", "orders")) }
    assertTrue(why, "is not the policy's origin" in why)
    assertEquals(0, seen.get())
    assertEquals(0, server.requestCount)
  }

  @Test fun aRedirectIsNotFollowedEvenThoughTheAppsClientFollowsThem() = runBlocking {
    server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/api/admin").toString()).build())
    server.enqueue(MockResponse.Builder().code(200).body("should not be fetched").build())
    val response = http(policy(RequestPolicy.Rule("GET", "orders"))).execute(HttpRequest("GET", "orders"))
    assertEquals(302, response.status)
    assertEquals(1, server.requestCount)
  }

  @Test fun aGuestCannotSetItsOwnCredentials() = runBlocking {
    // The app's interceptor sets Authorization; a guest's Cookie and Proxy-Authorization go nowhere either.
    val noAuth = OkHttpClient.Builder().addInterceptor { chain -> seen.incrementAndGet(); chain.proceed(chain.request()) }.build()
    server.enqueue(MockResponse.Builder().code(200).build())
    val headers = mapOf("Authorization" to "Bearer guest", "Cookie" to "session=guest", "Proxy-Authorization" to "Basic x", "X-Trace" to "1")
    OkHttpHostHttp(noAuth, base(), policy(RequestPolicy.Rule("GET", "orders")), appOwnsCredentials = true)
      .execute(HttpRequest("GET", "orders", headers = headers))
    val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
    assertNull(recorded.headers["Authorization"])
    assertNull(recorded.headers["Cookie"])
    assertNull(recorded.headers["Proxy-Authorization"])
    assertEquals("1", recorded.headers["X-Trace"])
  }

  @Test fun anAppsClientNeedsAPolicy() {
    try {
      KeliverConfig(bundleServer = "https://bundles.example.com", publicKeyHex = null, apiBaseUrl = base().toString(), httpClient = appClient)
      fail("a config with the app's client and no policy was accepted")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message, "needs a requestPolicy" in e.message.orEmpty())
    }
  }
}
