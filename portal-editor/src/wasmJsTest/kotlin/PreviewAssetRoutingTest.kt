import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
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
    // Case-insensitive scheme and host; the scheme's own default port is fine.
    assertEquals(
      "$relay/preview-asset?url=HTTP%3A%2F%2FIMG.example.com%3A80%2Fx.png",
      previewAssetUrl("HTTP://IMG.example.com:80/x.png", hosts, relay),
    )
    assertEquals(
      "$relay/preview-asset?url=https%3A%2F%2Fimg.example.com%3A443%2Fx.png",
      previewAssetUrl("https://img.example.com:443/x.png", hosts, relay),
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
      // Any other port: the relay would refuse it.
      "HTTP://IMG.example.com:8443/x.png",
      "http://img.example.com:443/x.png",
      "https://img.example.com:80/x.png",
      "http://img.example.com:6379/x",
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

  @Test fun withoutAListImagesWaitOnceNotEveryTime() = runTest {
    val gate = PreviewAssetHostGate(waitMillis = 3_000)
    gate.awaitLoaded()
    assertEquals(3_000L, currentTime)
    gate.awaitLoaded()
    gate.awaitLoaded()
    assertEquals(3_000L, currentTime, "later images do not wait again")
    assertEquals("https://img.example.com/x.png", gate.route("https://img.example.com/x.png", relay))
    // A list that arrives late still routes the images after it.
    gate.loaded(hosts)
    assertEquals("$relay/preview-asset?url=https%3A%2F%2Fimg.example.com%2Fx.png", gate.route("https://img.example.com/x.png", relay))
  }

  @Test fun theListEndsTheWaitWhenItArrives() = runTest {
    val gate = PreviewAssetHostGate(waitMillis = 3_000)
    launch {
      delay(200)
      gate.loaded(hosts)
    }
    gate.awaitLoaded()
    assertEquals(200L, currentTime)
    assertEquals("$relay/preview-asset?url=https%3A%2F%2Fimg.example.com%2Fx.png", gate.route("https://img.example.com/x.png", relay))
  }

  @Test fun atMostTwoRelayFetchesRunAtOnceAndDirectUrlsAreNotHeld() = runTest {
    val gate = PreviewAssetHostGate()
    gate.loaded(hosts)
    var running = 0
    var most = 0
    val targets = mutableListOf<String>()
    suspend fun slowFetch(target: String): String {
      targets += target
      running++
      most = maxOf(most, running)
      delay(100)
      running--
      return target
    }
    val relayed = (1..5).map { launch { gate.load("https://img.example.com/$it.png", relay, ::slowFetch) } }
    relayed.forEach { it.join() }
    assertEquals(RELAY_IMAGE_FETCHES, most)
    assertEquals(300L, currentTime, "five 100 ms fetches, two at a time")
    assertEquals(5, targets.count { it.startsWith("$relay/preview-asset?url=") })
    // Direct URLs are not capped, and do not wait behind relay fetches.
    most = 0
    targets.clear()
    val held = (1..4).map { launch { gate.load("https://img.example.com/held$it.png", relay, ::slowFetch) } }
    val direct = (1..5).map { launch { gate.load("https://elsewhere.example/$it.png", relay, ::slowFetch) } }
    direct.forEach { it.join() }
    assertEquals(400L, currentTime, "the direct fetches ran beside the relay's first two")
    assertEquals(5, targets.count { it.startsWith("https://elsewhere.example/") })
    held.forEach { it.join() }
    assertEquals(500L, currentTime)
  }

  @Test fun encodingMatchesEncodeUriComponent() {
    assertEquals("%C3%A9%20%2B%26%3D-_.!~*'()", encodeUriComponent("é +&=-_.!~*'()"))
  }
}
