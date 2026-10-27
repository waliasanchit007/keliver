package @@PACKAGE@@

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** K1: a deep link's route, and only the query keys the app allows. */
class KeliverRouteTest {
  @Test fun onlyAllowedQueryKeysReachTheScreen() {
    val r = KeliverRoute.from("example://orders/42?tab=items&token=secret&utm_source=x", setOf("tab"))!!
    assertEquals("orders/42", r.path)
    assertEquals(mapOf("tab" to "items"), r.query)
    assertEquals("orders/42?tab=items", r.encode())
  }

  @Test fun aWebLinksHostIsNotPartOfTheRoute() {
    assertEquals("orders/42", KeliverRoute.from("https://example.com/orders/42/", emptySet())!!.path)
    assertEquals("orders/42", KeliverRoute.from("example://orders/42", emptySet())!!.path)
  }

  @Test fun aRepeatedKeyKeepsItsFirstValue() {
    assertEquals(mapOf("tab" to "a"), KeliverRoute.from("example://x?tab=a&tab=b", setOf("tab"))!!.query)
  }

  @Test fun notAUriIsNoRoute() {
    assertNull(KeliverRoute.from(null, emptySet()))
    assertNull(KeliverRoute.from("example://bad path with spaces", emptySet()))
  }

  @Test fun encodeAndDecodeRoundTrip() {
    val r = KeliverRoute("orders/a b/ü?&=", mapOf("q" to "x&y=z", "tab" to "items"))
    assertEquals("orders/a+b/%C3%BC%3F%26%3D?q=x%26y%3Dz&tab=items", r.encode())
    assertEquals(r, KeliverRoute.decode(r.encode()))
  }

  @Test fun anEncodedValueInALinkIsDecodedOnce() {
    val r = KeliverRoute.from("example://search?q=a%26b%3Dc", setOf("q"))!!
    assertEquals("a&b=c", r.query["q"])
    assertEquals(r, KeliverRoute.decode(r.encode()))
  }
}
