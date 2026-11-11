package @@PACKAGE@@

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The debug link (ED3): what a debug host takes from a scanned link, and everything it refuses.
 * The same file in the Android and iOS hosts, so both are held to the same vectors.
 */
class DebugBundleLinkTest {
  private val scheme = "keliver-com.example.shop"
  private fun server(link: String?) = DebugBundleLink.server(link, scheme)
  private fun link(encodedServer: String) = "$scheme://keliver-debug/bundles?server=$encodedServer"
  private fun enc(s: String) = s.replace("%", "%25").replace(":", "%3A").replace("/", "%2F").replace("[", "%5B").replace("]", "%5D")

  @Test fun theRelaysLinksAreAccepted() {
    val vectors = listOf(
      "http://192.168.1.20:8078" to "http://192.168.1.20:8078",
      "http://10.0.2.2:8078" to "http://10.0.2.2:8078",
      "http://127.0.0.1:8078" to "http://127.0.0.1:8078",
      "http://localhost:8078" to "http://localhost:8078",
      "https://192.168.1.20:8078" to "https://192.168.1.20:8078",
      "http://192.168.1.20:8078/" to "http://192.168.1.20:8078",
      "http://[fe80::1]:8078" to "http://[fe80::1]:8078",
      "http://[FD00:AB::12]:8078" to "http://[fd00:ab::12]:8078",
      "http://[::1]:8078" to "http://[::1]:8078",
      "http://[fd00:0:0:0:0:0:0:1]:8078" to "http://[fd00:0:0:0:0:0:0:1]:8078",
    )
    for ((given, want) in vectors) {
      assertEquals(want, server(link(enc(given))), given)
    }
    // Not encoded at all (a link typed by hand): the same answer.
    assertEquals("http://192.168.1.20:8078", server(link("http://192.168.1.20:8078")))
    // The scheme and host in any case (iOS matches schemes without case); the path exactly.
    assertEquals("http://10.0.2.2:8078", server("KELIVER-com.Example.SHOP://Keliver-Debug/bundles?server=${enc("http://10.0.2.2:8078")}"))
  }

  @Test fun onlyLoopbackPrivateLinkLocalAndSharedAddresses() {
    val accepted = listOf(
      "127.0.0.1", "127.255.255.254", "10.0.0.1", "10.255.255.255",
      "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.168.255.255",
      "169.254.0.1", "169.254.255.254", "100.64.0.1", "100.127.255.254",
      "[::1]", "[0:0:0:0:0:0:0:1]", "[fc00::1]", "[fd12:3456::1]", "[fdff:ffff::1]",
      "[fe80::1]", "[fe80::a:b:c:d]", "[febf::1]",
    )
    for (a in accepted) assertEquals("http://$a:8078", server(link(enc("http://$a:8078"))), a)
    val refused = listOf(
      // The public internet (and documentation addresses).
      "8.8.8.8", "1.1.1.1", "[2606:4700::1111]", "[2001:db8::1]",
      // Just outside each range.
      "172.15.255.255", "172.32.0.1", "192.167.1.1", "192.169.1.1", "169.253.1.1", "169.255.1.1",
      "100.63.255.255", "100.128.0.1", "11.0.0.1", "126.255.255.255", "128.0.0.1",
      "[fbff::1]", "[fe00::1]", "[fec0::1]", "[ff02::1]", "[::2]", "[1::1]", "[::1:1]",
      // Unspecified, multicast and broadcast.
      "0.0.0.0", "224.0.0.1", "255.255.255.255", "[::]",
    )
    for (a in refused) assertNull(server(link(enc("http://$a:8078"))), a)
  }

  @Test fun anyPortFrom1024Up() {
    for (p in listOf(1024, 8077, 8078, 9000, 65535)) {
      assertEquals("http://192.168.1.20:$p", server(link(enc("http://192.168.1.20:$p"))), "$p")
    }
    for (p in listOf("1", "80", "443", "1023", "65536", "99999", "0", "01024")) {
      assertNull(server(link(enc("http://192.168.1.20:$p"))), p)
    }
  }

  @Test fun anythingElseIsRefused() {
    val ok = enc("http://192.168.1.20:8078")
    val refused = listOf(
      null, "",
      // Not this app's link.
      "keliver-com.example.other://keliver-debug/bundles?server=$ok",
      "https://keliver-debug/bundles?server=$ok",
      "$scheme:keliver-debug/bundles?server=$ok",
      "$scheme://keliver-debug.evil/bundles?server=$ok",
      "$scheme://evil@keliver-debug/bundles?server=$ok",
      "$scheme://keliver-debug:80/bundles?server=$ok",
      "$scheme://keliver-debug/Bundles?server=$ok",
      "$scheme://keliver-debug/bundles/?server=$ok",
      "$scheme://keliver-debug/bundles/x?server=$ok",
      "$scheme://keliver-debug/?server=$ok",
      "$scheme://keliver-debug/bundles",
      "$scheme://keliver-debug/bundles?",
      "$scheme://keliver-debug/bundles?server=",
      // One parameter, once, and no fragment.
      "$scheme://keliver-debug/bundles?server=$ok&server=$ok",
      "$scheme://keliver-debug/bundles?server=$ok&x=1",
      "$scheme://keliver-debug/bundles?x=1&server=$ok",
      "$scheme://keliver-debug/bundles?server=$ok;x=1",
      "$scheme://keliver-debug/bundles?server=$ok#x",
      "$scheme://keliver-debug/bundles?Server=$ok",
      // Spaces, controls, non-ASCII, and too long.
      "$scheme://keliver-debug/bundles?server=$ok ",
      "$scheme://keliver-debug/bundles?server=http://192.168.1.20:8078\n",
      "$scheme://keliver-debug/bundles?server=http://192.168.1.2ü:8078",
      link(ok) + "/".repeat(DebugBundleLink.MAX_LENGTH),
    )
    for (l in refused) assertNull(server(l), l.toString())
  }

  @Test fun theServerMustBeAPortOnAnAddress() {
    val refused = listOf(
      // http(s) only.
      "ftp://192.168.1.20:8078", "file:///etc/passwd", "javascript:alert(1)", "HTTP://192.168.1.20:8078",
      "//192.168.1.20:8078", "192.168.1.20:8078",
      // An address or localhost, never a name.
      "http://example.com:8078", "http://evil.localhost:8078", "http://LOCALHOST:8078", "http://localhost.:8078",
      "http://192.168.1.20.nip.io:8078", "http://0x7f000001:8078", "http://2130706433:8078", "http://127.1:8078",
      "http://192.168.001.020:8078", "http://192.168.1.256:8078", "http://192.168.1:8078", "http://192.168.1.20.1:8078",
      // No zone or embedded IPv4 part, and well formed.
      "http://[::ffff:192.168.1.20]:8078", "http://[fe80::1%25en0]:8078", "http://[fe80::1%en0]:8078",
      "http://[1:2:3:4:5:6:7:8:9]:8078", "http://[1::2::3]:8078", "http://[12345::1]:8078", "http://[fe80::1:8078",
      "http://[fe80:0:0:0:0:0:0:0:1]:8078",
      // A port, given explicitly.
      "http://192.168.1.20", "http://192.168.1.20:", "http://192.168.1.20:08078", "http://192.168.1.20:8078:8078",
      "http://[::1]8078", "http://192.168.1.20:+8078", "http://192.168.1.20:8078a",
      // No user, query, fragment or path.
      "http://user@192.168.1.20:8078", "http://user:pw@192.168.1.20:8078", "http://192.168.1.20:8078@10.0.0.1:8078",
      "http://192.168.1.20:8078?x=1", "http://192.168.1.20:8078/#x", "http://192.168.1.20:8078/bundles",
      "http://192.168.1.20:8078//", "http://192.168.1.20:8078/..", "http://192.168.1.20:8078\\x",
    )
    for (s in refused) {
      assertNull(server(link(enc(s))), s)
      assertNull(DebugBundleLink.bundleServer(s), s)
    }
  }

  @Test fun percentEncodingIsDecodedOnceAndStrictly() {
    // Encoded twice: the decoded value still holds '%', which is refused.
    assertNull(server(link(enc(enc("http://192.168.1.20:8078")))))
    // Malformed escapes.
    for (e in listOf("http%3", "http%3A%2F%2F192.168.1.20%3A807%", "http%zz//192.168.1.20:8078", "http%00://192.168.1.20:8078")) {
      assertNull(server(link(e)), e)
    }
    // An encoded user, query, fragment, space or backslash is refused once decoded.
    for (c in listOf("%40", "%3F", "%23", "%20", "%5C", "%2E%2E")) {
      assertNull(server(link("http%3A%2F%2F192.168.1.20%3A8078%2F$c")), c)
    }
    // Lower-case escapes are escapes too.
    assertEquals("http://192.168.1.20:8078", server(link("http%3a%2f%2f192.168.1.20%3a8078")))
  }
}
