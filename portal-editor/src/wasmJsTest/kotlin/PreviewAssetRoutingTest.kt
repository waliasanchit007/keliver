import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
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

  @Test fun aHangingHostQueuesOnlyItsOwnImages() = runTest {
    val gate = PreviewAssetHostGate()
    gate.loaded(setOf("slow-a.example.com", "slow-b.example.com", "img.example.com"))
    val hang = CompletableDeferred<String>()
    val started = mutableListOf<String>()
    suspend fun hangingFetch(target: String): String {
      started += target
      return hang.await()
    }
    // Two tarpit hosts, five images each: each host runs one, the other four
    // wait on that host's permit, not on a shared one.
    val stuck = listOf("slow-a", "slow-b").flatMap { h ->
      (1..5).map { launch { gate.load("https://$h.example.com/$it.png", relay, ::hangingFetch) } }
    }
    runCurrent()
    assertEquals(2, started.size, "one fetch per hanging host")
    assertEquals(2, gate.hostsInUse())
    // Another host's image still loads at once, on the third shared permit.
    val before = currentTime
    val got = gate.load("https://img.example.com/x.png", relay) { delay(100); it }
    assertEquals("$relay/preview-asset?url=https%3A%2F%2Fimg.example.com%2Fx.png", got)
    assertEquals(100L, currentTime - before, "not held up by the ten stuck images")
    hang.complete("done")
    stuck.joinAll()
    assertEquals(10, started.size, "every stuck image ran once its host was free")
    assertEquals(0, gate.hostsInUse(), "idle hosts leave no entry behind")
  }

  @Test fun atMostThreeRelayFetchesRunAtOnceOnePerHost() = runTest {
    val gate = PreviewAssetHostGate()
    val hostNames = (1..5).map { "h$it.example.com" }
    gate.loaded(hostNames.toSet())
    var running = 0
    var most = 0
    val perHost = mutableMapOf<String, Int>()
    var mostPerHost = 0
    suspend fun slowFetch(target: String): String {
      val host = target.substringAfter("%2F%2F").substringBefore("%2F")
      running++
      perHost[host] = (perHost[host] ?: 0) + 1
      most = maxOf(most, running)
      mostPerHost = maxOf(mostPerHost, perHost.getValue(host))
      delay(100)
      perHost[host] = perHost.getValue(host) - 1
      running--
      return target
    }
    val loads = hostNames.flatMap { h -> (1..3).map { launch { gate.load("https://$h/$it.png", relay, ::slowFetch) } } }
    loads.joinAll()
    assertEquals(RELAY_IMAGE_FETCHES, most, "the shared cap")
    assertEquals(RELAY_FETCHES_PER_HOST, mostPerHost, "the per-host cap")
    assertEquals(500L, currentTime, "fifteen 100 ms fetches, three at a time")
    assertEquals(0, gate.hostsInUse())
  }

  @Test fun directUrlsAreNotHeldBehindRelayFetches() = runTest {
    val gate = PreviewAssetHostGate()
    gate.loaded(hosts)
    val targets = mutableListOf<String>()
    suspend fun slowFetch(target: String): String {
      targets += target
      delay(100)
      return target
    }
    val held = (1..4).map { launch { gate.load("https://img.example.com/held$it.png", relay, ::slowFetch) } }
    val direct = (1..5).map { launch { gate.load("https://elsewhere.example/$it.png", relay, ::slowFetch) } }
    direct.joinAll()
    assertEquals(100L, currentTime, "the direct fetches ran beside the relay's first")
    assertEquals(5, targets.count { it.startsWith("https://elsewhere.example/") })
    held.joinAll()
    assertEquals(400L, currentTime, "one host's four relay fetches, one at a time")
  }

  @Test fun aCancelledWaitLeavesNoHostEntry() = runTest {
    val gate = PreviewAssetHostGate()
    gate.loaded(hosts)
    val hang = CompletableDeferred<String>()
    val loads = (1..3).map { launch { gate.load("https://img.example.com/$it.png", relay) { hang.await() } } }
    runCurrent()
    assertEquals(1, gate.hostsInUse())
    loads.forEach { it.cancel() }
    loads.joinAll()
    assertEquals(0, gate.hostsInUse())
    // The permits came back: the host loads again at once.
    assertEquals("ok", gate.load("https://img.example.com/next.png", relay) { "ok" })
  }

  @Test fun theHostKeyIsSchemeAndHost() {
    assertEquals("https://img.example.com", previewAssetHostKey("HTTPS://IMG.example.com:443/a.png"))
    assertEquals("http://img.example.com", previewAssetHostKey("http://img.example.com/a.png"))
    assertNull(previewAssetHostKey("https://img.example.com:8443/a.png"))
  }

  @Test fun encodingMatchesEncodeUriComponent() {
    assertEquals("%C3%A9%20%2B%26%3D-_.!~*'()", encodeUriComponent("é +&=-_.!~*'()"))
  }
}
