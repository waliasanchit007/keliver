package @@PACKAGE@@

import dev.keliver.http.HttpRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** K1: RequestPolicy, alone and in OkHttpHostHttp against a local server (nothing refused reaches it). */
class RequestPolicyTest {
  private val server = MockWebServer()

  @Before fun start() = server.start()

  @After fun stop() = server.close()

  private fun base() = server.url("/api/")

  private fun policy(vararg rules: RequestPolicy.Rule, stateChanging: Boolean = false, cleartext: Boolean = true) =
    RequestPolicy(base(), rules.toSet(), allowStateChanging = stateChanging, allowCleartext = cleartext)

  private fun http(policy: RequestPolicy?) = OkHttpHostHttp(OkHttpClient(), base(), policy)

  private fun refused(block: suspend () -> Unit): String = try {
    runBlocking { block() }
    fail("the request was not refused"); ""
  } catch (e: HostHttpRefused) {
    e.message.orEmpty()
  }

  @Test fun anAllowedRequestGoesThrough() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).body("ok").build())
    val response = http(policy(RequestPolicy.Rule("get", "/orders/recent/"))).execute(HttpRequest("GET", "orders/recent"))
    assertEquals(200, response.status)
    assertEquals("/api/orders/recent", server.takeRequest(5, TimeUnit.SECONDS)?.url?.encodedPath)
  }

  @Test fun aPathNotInTheListIsRefusedBeforeAnythingIsSent() {
    val why = refused { http(policy(RequestPolicy.Rule("GET", "orders/recent"))).execute(HttpRequest("GET", "orders/all")) }
    assertTrue(why, "GET /orders/all is not in the allow-list" in why)
    assertEquals(0, server.requestCount)
  }

  @Test fun theMethodIsPartOfTheRule() {
    val why = refused {
      http(policy(RequestPolicy.Rule("GET", "orders"), stateChanging = true)).execute(HttpRequest("DELETE", "orders"))
    }
    assertTrue(why, "DELETE /orders is not in the allow-list" in why)
    assertEquals(0, server.requestCount)
  }

  @Test fun aStateChangingCallNeedsTheBuildSwitchEvenWhenListed() {
    val rule = RequestPolicy.Rule("POST", "orders")
    val why = refused { http(policy(rule)).execute(HttpRequest("POST", "orders", body = "{}")) }
    assertTrue(why, "state-changing" in why)
    assertEquals(0, server.requestCount)
    server.enqueue(MockResponse.Builder().code(201).build())
    val ok = runBlocking { http(policy(rule, stateChanging = true)).execute(HttpRequest("POST", "orders", body = "{}")) }
    assertEquals(201, ok.status)
  }

  @Test fun anotherOriginIsRefused() {
    val elsewhere = RequestPolicy("https://api.example.com/".toHttpUrl(), setOf(RequestPolicy.Rule("GET", "orders")))
    val why = refused { http(elsewhere).execute(HttpRequest("GET", "orders")) }
    assertTrue(why, "is not the allowed origin https://api.example.com:443" in why)
    assertEquals(0, server.requestCount)
  }

  @Test fun cleartextIsRefusedUnlessTheBuildAllowsIt() {
    val why = refused { http(policy(RequestPolicy.Rule("GET", "orders"), cleartext = false)).execute(HttpRequest("GET", "orders")) }
    assertTrue(why, "only https is allowed" in why)
    assertEquals(0, server.requestCount)
  }

  @Test fun aRedirectIsNotFollowed() = runBlocking {
    server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://elsewhere.example.com/").build())
    val response = http(policy(RequestPolicy.Rule("GET", "orders"))).execute(HttpRequest("GET", "orders"))
    assertEquals(302, response.status)
    assertEquals(1, server.requestCount)
  }

  @Test fun theRuleComparesWholeSegments() {
    val p = policy(RequestPolicy.Rule("GET", "orders"))
    assertNull(p.refusal("GET", base().newBuilder().addPathSegment("orders").build(), "/orders/"))
    assertNotNull(p.refusal("GET", base().newBuilder().addPathSegments("orders/1").build(), "orders/1"))
    assertNotNull(p.refusal("GET", base().newBuilder().addPathSegment("ordersx").build(), "ordersx"))
  }

  @Test fun withoutAPolicyEveryPathUnderTheBaseIsAllowedAsBefore() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).build())
    assertEquals(200, http(null).execute(HttpRequest("DELETE", "anything/at/all")).status)
  }
}
