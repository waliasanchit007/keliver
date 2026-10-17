import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** K5: which canvas image URLs go through the relay's /preview-asset. */
class PreviewAssetRoutingTest {
  private val hosts = setOf("img.example.com")
  private val relay = "http://localhost:18077"

  @Test fun aListedHostIsRewrittenWithTheWholeUrlEncoded() {
    assertEquals(
      "$relay/preview-asset?url=https%3A%2F%2Fimg.example.com%2Fa%20b%2Fc.png%3Fw%3D2%26h%3D3",
      previewAssetUrl("https://img.example.com/a%20b/c.png?w=2&h=3".replace("%20", " "), hosts, relay),
    )
    // Case-insensitive scheme and host; a port does not change the host.
    assertEquals(
      "$relay/preview-asset?url=HTTP%3A%2F%2FIMG.example.com%3A8443%2Fx.png",
      previewAssetUrl("HTTP://IMG.example.com:8443/x.png", hosts, relay),
    )
  }

  @Test fun everythingElseLoadsDirectly() {
    for (url in listOf(
      "https://cdn.img.example.com/x.png",
      "https://img.example.com.evil.test/x.png",
      "https://user@img.example.com/x.png",
      "data:image/png;base64,AAAA",
      "file:///img.example.com/x.png",
      "https://[::1]/x.png",
    )) {
      assertNull(previewAssetUrl(url, hosts, relay), url)
    }
    assertNull(previewAssetUrl("https://img.example.com/x.png", emptySet(), relay))
  }

  @Test fun theHostListIsReadFromTheRelay() {
    assertEquals(setOf("img.example.com", "cdn.example.org"), parsePreviewAssetHosts("""{"endpoint":"/preview-asset","hosts":["img.example.com","CDN.example.org"]}"""))
    assertEquals(emptySet(), parsePreviewAssetHosts("<html>404</html>"))
    assertEquals(emptySet(), parsePreviewAssetHosts("{}"))
  }

  @Test fun encodingMatchesEncodeUriComponent() {
    assertEquals("%C3%A9%20%2B%26%3D-_.!~*'()", encodeUriComponent("é +&=-_.!~*'()"))
  }
}
