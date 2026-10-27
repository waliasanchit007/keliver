package @@PACKAGE@@

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** K2: the iOS host's RequestPolicy, on the JVM (it is pure Kotlin). The HTTP side is NSURLSessionHostHttp. */
class RequestPolicyTest {
  private val base = "https://api.example.com/v1"

  private fun policy(vararg rules: RequestPolicy.Rule, stateChanging: Boolean = false, cleartext: Boolean = false, origin: String = "https://api.example.com") =
    RequestPolicy(origin, rules.toSet(), allowStateChanging = stateChanging, allowCleartext = cleartext)

  @Test fun anAllowedRequestGoesThrough() {
    val p = policy(RequestPolicy.Rule("get", "/orders/recent/"))
    assertNull(p.baseRefusal(base))
    assertNull(p.refusal("GET", "orders/recent"))
    assertNull(p.refusal("get", "orders//recent"))
  }

  @Test fun aPathNotInTheListOrAnotherMethodIsRefused() {
    val p = policy(RequestPolicy.Rule("GET", "orders"), stateChanging = true)
    assertTrue("GET /orders/all is not in the allow-list" in p.refusal("GET", "orders/all")!!)
    assertTrue("DELETE /orders is not in the allow-list" in p.refusal("DELETE", "orders")!!)
    assertNotNull(p.refusal("GET", "ordersx"))
    assertNotNull(p.refusal("GET", "orders/1"))
  }

  @Test fun aStateChangingCallNeedsTheBuildSwitchEvenWhenListed() {
    val rule = RequestPolicy.Rule("POST", "orders")
    assertTrue("state-changing" in policy(rule).refusal("POST", "orders")!!)
    assertNull(policy(rule, stateChanging = true).refusal("POST", "orders"))
    assertNull(policy(RequestPolicy.Rule("HEAD", "orders")).refusal("HEAD", "orders"))
  }

  @Test fun thePolicyMustNameTheApiBasesOrigin() {
    assertTrue("is not the policy's origin" in policy(origin = "https://other.example.com").baseRefusal(base)!!)
    assertTrue("is not the policy's origin" in policy(origin = "https://api.example.com:8443").baseRefusal(base)!!)
    assertNull(policy(origin = "https://API.example.com:443/ignored/path").baseRefusal(base))
  }

  @Test fun cleartextIsRefusedUnlessTheBuildAllowsIt() {
    assertTrue("only https is allowed" in policy(origin = "http://api.example.com").baseRefusal("http://api.example.com/v1")!!)
    assertNull(policy(origin = "http://api.example.com", cleartext = true).baseRefusal("http://api.example.com/v1"))
  }

  @Test fun originsAreParsedStrictly() {
    assertEquals(RequestPolicy.Origin("https", "api.example.com", 443), RequestPolicy.originOf("https://api.example.com"))
    assertEquals(RequestPolicy.Origin("http", "[::1]", 8080), RequestPolicy.originOf("http://[::1]:8080/x"))
    for (bad in listOf("ftp://x", "https://", "https://user@api.example.com", "https://api.example.com:99999", "api.example.com", "https://:443")) {
      assertNull(RequestPolicy.originOf(bad), bad)
    }
  }
}
