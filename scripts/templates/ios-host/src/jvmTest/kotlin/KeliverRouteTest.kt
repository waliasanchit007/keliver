package @@PACKAGE@@

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The host kit's routes: a deep link's route, only the allowed query keys, and the text the guest
 * receives. The same file in the Android and iOS hosts, so both are held to the same vectors.
 */
class KeliverRouteTest {
  private fun route(uri: String, vararg keys: String) = KeliverRoute.from(uri, keys.toSet())?.encode()

  @Test fun theSameLinkGivesTheSameTextOnEveryHost() {
    val vectors = listOf(
      "example://orders/42?tab=items&token=secret&utm_source=x" to "orders/42?tab=items",
      "https://example.com/orders/42/" to "orders/42",
      "HTTPS://example.com/orders/42" to "orders/42",
      "example://order_list/42" to "order_list/42",
      "example://user@orders:8080/x" to "orders/x",
      "example:orders/42?tab=x" to "orders/42?tab=x",
      "example://x?tab=a&tab=b" to "x?tab=a",
      "example://search?tab=a%26b%3Dc" to "search?tab=a%26b%3Dc",
      "example://a%2Fb/c" to "a/b/c",
      "example://x/100%zz" to "x/100%25zz",
      "example://x/a+b" to "x/a%2Bb",
      "example://x/caf%C3%A9" to "x/caf%C3%A9",
      "example://x/😀" to "x/%F0%9F%98%80",
    )
    for ((link, text) in vectors) assertEquals(text, route(link, "tab"), link)
  }

  @Test fun notALinkIsNoRoute() {
    for (bad in listOf("no scheme here", "orders/42", "/orders/42", "example://bad path", "example://x\n", "1x://a")) {
      assertNull(KeliverRoute.from(bad, emptySet()), bad)
    }
    assertNull(KeliverRoute.from(null, emptySet()))
  }

  @Test fun encodeAndDecodeRoundTrip() {
    val r = KeliverRoute("orders/a b/ü?&=", mapOf("q" to "x&y=z", "tab" to "items"))
    assertEquals("orders/a+b/%C3%BC%3F%26%3D?q=x%26y%3Dz&tab=items", r.encode())
    assertEquals(r, KeliverRoute.decode(r.encode()))
    val s = KeliverRoute.from("example://search?q=a%26b%3Dc", setOf("q"))!!
    assertEquals("a&b=c", s.query["q"])
    assertEquals(s, KeliverRoute.decode(s.encode()))
  }

  @Test fun aMalformedEscapeStaysAsItIs() {
    assertEquals("100%", KeliverRoute.percentDecode("100%", plusIsSpace = true))
    assertEquals("%zz", KeliverRoute.percentDecode("%zz", plusIsSpace = true))
    assertEquals("😀x", KeliverRoute.percentDecode("😀x", plusIsSpace = false))
  }
}
