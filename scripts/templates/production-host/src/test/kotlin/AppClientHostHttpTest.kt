package @@PACKAGE@@

import dev.keliver.http.HttpRequest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.Request
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
    val headers = mapOf(
      "authorization" to "Bearer guest", "COOKIE" to "session=guest", "Proxy-Authorization" to "Basic x",
      "X-Original-URL" to "/api/account", "X-Forwarded-Host" to "elsewhere.example.com", "X-Trace" to "1",
    )
    OkHttpHostHttp(noAuth, base(), policy(RequestPolicy.Rule("GET", "orders")), appOwnsCredentials = true)
      .execute(HttpRequest("GET", "orders", headers = headers))
    val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
    assertNull(recorded.headers["Authorization"])
    assertNull(recorded.headers["Cookie"])
    assertNull(recorded.headers["Proxy-Authorization"])
    assertNull(recorded.headers["X-Original-URL"])
    assertNull(recorded.headers["X-Forwarded-Host"])
    assertEquals("1", recorded.headers["X-Trace"])
    assertEquals(1, seen.get())
  }

  @Test fun theSessionCookieIsNotHandedToTheGuest() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "session=secret").addHeader("ETag", "\"1\"").build())
    val response = http(policy(RequestPolicy.Rule("GET", "orders"))).execute(HttpRequest("GET", "orders"))
    assertTrue(response.headers.keys.none { it.equals("Set-Cookie", ignoreCase = true) })
    assertEquals("\"1\"", response.headers.entries.first { it.key.equals("ETag", ignoreCase = true) }.value)
  }

  @Test fun anInterceptorActingOnAGuestHeaderCannotSendTheRequestElsewhere() {
    // A common app pattern: a marker header switches where a request goes.
    val switching = OkHttpClient.Builder().addInterceptor { chain ->
      val r = chain.request()
      if (r.header("X-Target") == "admin") chain.proceed(r.newBuilder().url(server.url("/api/admin")).build()) else chain.proceed(r)
    }.build()
    val why = refused {
      OkHttpHostHttp(switching, base(), policy(RequestPolicy.Rule("GET", "orders")), appOwnsCredentials = true)
        .execute(HttpRequest("GET", "orders", headers = mapOf("X-Target" to "admin")))
    }
    assertTrue(why, "not the one checked" in why)
    assertEquals(0, server.requestCount)
  }

  @Test fun aRewriteCannotBeAnsweredFromTheAppsCache() {
    // The app's client caches; its interceptor switches the URL on a guest header. A cached
    // response for the switched path must not reach the guest either.
    val cache = Cache(kotlin.io.path.createTempDirectory("hosthttp-cache").toFile(), 1L shl 20)
    val switching = OkHttpClient.Builder().cache(cache).addInterceptor { chain ->
      val r = chain.request()
      if (r.header("X-Target") == "admin") chain.proceed(r.newBuilder().url(server.url("/api/admin")).build()) else chain.proceed(r)
    }.build()
    server.enqueue(MockResponse.Builder().code(200).addHeader("Cache-Control", "max-age=600").body("admin data").build())
    switching.newCall(Request.Builder().url(server.url("/api/admin")).build()).execute().use { assertEquals(200, it.code) }
    val why = refused {
      OkHttpHostHttp(switching, base(), policy(RequestPolicy.Rule("GET", "orders")), appOwnsCredentials = true)
        .execute(HttpRequest("GET", "orders", headers = mapOf("X-Target" to "admin")))
    }
    assertTrue(why, "not the one checked" in why)
    assertEquals(1, server.requestCount)
    cache.close()
  }

  @Test fun anAuthenticatorRetryIsCheckedToo() = runBlocking {
    // Retrying the same URL with a fresh token is fine; a retry to another path is refused.
    var retryPath = "/api/orders"
    val client = appClient.newBuilder().authenticator(Authenticator { _, response ->
      response.request.newBuilder().url(server.url(retryPath)).header("Authorization", "Bearer refreshed").build()
    }).build()
    val http = OkHttpHostHttp(client, base(), policy(RequestPolicy.Rule("GET", "orders")), appOwnsCredentials = true)
    server.enqueue(MockResponse.Builder().code(401).build())
    server.enqueue(MockResponse.Builder().code(200).body("ok").build())
    assertEquals(200, http.execute(HttpRequest("GET", "orders")).status)
    assertEquals(2, server.requestCount)

    retryPath = "/api/login"
    server.enqueue(MockResponse.Builder().code(401).build())
    val why = refused { http.execute(HttpRequest("GET", "orders")) }
    assertTrue(why, "not the one checked" in why)
    assertEquals(3, server.requestCount)
  }

  @Test fun traceIsNeverSentThroughTheAppsClient() {
    val loose = RequestPolicy(
      base(),
      setOf(RequestPolicy.Rule("TRACE", "orders"), RequestPolicy.Rule("TRACK", "orders"), RequestPolicy.Rule("CONNECT", "orders")),
      allowStateChanging = true, allowCleartext = true,
    )
    for (method in listOf("TRACE", "trace", "TRACK", "CONNECT")) {
      val why = refused { http(loose).execute(HttpRequest(method, "orders")) }
      assertTrue(why, "never sent through the app's own client" in why)
    }
    assertEquals(0, seen.get())
    assertEquals(0, server.requestCount)
  }

  @Test fun theHostsPlainClientStillSendsTrace() = runBlocking {
    val loose = RequestPolicy(base(), setOf(RequestPolicy.Rule("TRACE", "orders")), allowStateChanging = true, allowCleartext = true)
    server.enqueue(MockResponse.Builder().code(200).build())
    val plain = OkHttpHostHttp(OkHttpClient(), base(), loose)
    assertEquals(200, plain.execute(HttpRequest("TRACE", "orders")).status)
    assertEquals("TRACE", server.takeRequest().method)
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
