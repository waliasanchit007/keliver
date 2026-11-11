import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Owner, 25 Oct: a publish said in one line, and which bundle devices get. */
class EditorPublishTest {
  @Test fun aPublishThatStoredABundleSaysWhichVersion() {
    val log = """
      > Task :compileProductionExecutableKotlinJsZipline
      BUILD SUCCESSFUL in 16s
      publish: the manifest is signed with this app's portal-ed25519 key
      publish OK: bundle v3 (widgetVersion=1, srcHash=b95f) -> /store/bundles/v3
    """.trimIndent()
    assertEquals(PublishSummary(3, null), publishSummary(true, log))
  }

  @Test fun aRefusedPublishSaysWhyNotTheWholeLog() {
    val log = """
      > Task :compileKotlinJs
      BUILD SUCCESSFUL in 16s
      publish REFUSED: the bundle is UNSIGNED: its manifest has no portal-ed25519 signature
      Nothing was stored.
    """.trimIndent()
    val s = publishSummary(true, log)
    assertNull(s.version)
    assertTrue(s.reason!!.startsWith("REFUSED: the bundle is UNSIGNED"), s.reason)
    assertEquals("The relay did not answer", publishSummary(false, "The relay did not answer").reason)
  }

  @Test fun theNewestBundleIsTheOneDevicesGet() {
    val index = """{"format":1,"entries":[{"sequence":1,"version":1,"createdAt":"2026-10-25T12:20:24Z"},""" +
      """{"sequence":3,"version":3,"createdAt":"2026-10-25T13:06:21Z"},{"sequence":2,"version":2,"createdAt":"2026-10-25T12:44:54Z"}]}"""
    assertEquals(3 to "2026-10-25T13:06:21Z", newestBundle(index))
    assertNull(newestBundle("""{"format":1,"entries":[]}"""), "nothing published yet")
    assertNull(newestBundle("not json"))
  }

  @Test fun theDevicesChipSaysHowManyPhonesAreListeningLive() {
    val at = { iso: String -> iso.take(10) }
    val (none, noneTip) = devicesChip(3 to "2026-10-27T10:00:00Z", at, 0)
    assertEquals("On devices: v3 · 2026-10-27", none)
    assertTrue("No phone is listening live" in noneTip, noneTip)
    assertEquals("On devices: v3 · 2026-10-27 · 1 phone live", devicesChip(3 to "2026-10-27T10:00:00Z", at, 1).first)
    assertEquals("Not published yet · 2 phones live", devicesChip(null, at, 2).first)
    val (old, oldTip) = devicesChip(3 to "2026-10-27T10:00:00Z", at, null)
    assertEquals("On devices: v3 · 2026-10-27", old, "a relay that does not say leaves the chip as it was")
    assertTrue("live" !in oldTip, oldTip)
  }

  @Test fun theRelaySaysHowManyDevicesListen() {
    assertEquals(2, devicesListening("""{"project":"p","screen":"s","version":4,"writeBack":null,"devicesListening":2}"""))
    assertNull(devicesListening("""{"project":"p","screen":"s","version":4,"writeBack":null}"""), "an older relay")
    assertNull(devicesListening("not json"))
  }

  // ED3: the device proxy's links.
  private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 29 29" shape-rendering="crispEdges"><rect width="29" height="29" fill="#fff"/><path d="M4,4h1v1h-1zM5,4h1v1h-1z" fill="#000"/></svg>"""

  private fun status(url: String, qr: String) =
    """{"enabled":true,"urls":[{"url":"$url","qrSvg":"${qr.replace("\"", "\\\"")}"}]}"""

  @Test fun theDeviceProxysLinksAndQrCodes() {
    assertEquals(listOf(DeviceLink("http://10.0.2.2:8078", svg)), deviceLinks(status("http://10.0.2.2:8078", svg)))
    assertEquals(emptyList(), deviceLinks("""{"enabled":false,"env":"KELIVER_DEVICE_PROXY"}"""), "off")
    assertNull(deviceLinks("""{"error":"not found"}"""), "a relay without the proxy")
    assertNull(deviceLinks("not json"))
  }

  @Test fun onlyTheRelaysOwnShapesAreShown() {
    // A QR code with anything but the relay's plain shapes is not inserted into the page.
    val script = svg.replace("<path", "<script>alert(1)</script><path")
    assertEquals(listOf(DeviceLink("http://10.0.2.2:8078", null)), deviceLinks(status("http://10.0.2.2:8078", script)))
    val onload = svg.replace("<rect ", "<rect onload=\"x()\" ")
    assertEquals(listOf(DeviceLink("http://10.0.2.2:8078", null)), deviceLinks(status("http://10.0.2.2:8078", onload)))
    for (bad in listOf("javascript:alert(1)", "http://evil.example:8078", "https://10.0.2.2:8078", "http://10.0.2.2:8078/x")) {
      assertEquals(emptyList(), deviceLinks(status(bad, svg)), bad)
    }
  }

  @Test fun theDebugHostCommandNamesTheUrl() {
    assertTrue("echo http://10.0.2.2:8078 > files/keliver/bundle-server" in debugHostCommand("http://10.0.2.2:8078"))
  }

  // ED3 follow-up: the QR code is the debug link; the bare URL and the adb command stay beside it.
  private fun statusWithLink(url: String, link: String?, qr: String) =
    """{"enabled":true,"urls":[{"url":"$url","link":${link?.let { "\"$it\"" } ?: "null"},"qrSvg":"${qr.replace("\"", "\\\"")}"}]}"""

  @Test fun theRelaysDebugLinkIsKeptWithItsUrl() {
    val link = "keliver-com.example.shop://keliver-debug/bundles?server=http%3A%2F%2F192.168.1.20%3A8078"
    assertEquals(
      listOf(DeviceLink("http://192.168.1.20:8078", svg, link)),
      deviceLinks(statusWithLink("http://192.168.1.20:8078", link, svg)),
    )
    val v6 = "keliver-com.example.shop://keliver-debug/bundles?server=http%3A%2F%2F%5Bfd00%3A0%3A0%3A0%3A0%3A0%3A0%3A12%5D%3A8078"
    assertEquals(v6, deviceLinks(statusWithLink("http://[fd00:0:0:0:0:0:0:12]:8078", v6, svg))!!.single().link)
    // A relay that makes no link (or an app without one package): the bare URL, as before.
    assertEquals(listOf(DeviceLink("http://10.0.2.2:8078", svg, null)), deviceLinks(statusWithLink("http://10.0.2.2:8078", null, svg)))
  }

  @Test fun aLinkThatIsNotTheRelaysOwnDropsTheLinkAndItsQrCode() {
    val url = "http://192.168.1.20:8078"
    for (bad in listOf(
      "keliver-com.example.shop://keliver-debug/bundles?server=http%3A%2F%2F10.0.0.9%3A8078", // another server than the URL shown
      "keliver-com.example.shop://keliver-debug/bundles?server=http%3A%2F%2F192.168.1.20%3A8078&x=1",
      "javascript:alert(1)",
      "https://evil.example/?server=http%3A%2F%2F192.168.1.20%3A8078",
      "keliver-com.example.shop://keliver-debug/other?server=http%3A%2F%2F192.168.1.20%3A8078",
      "keliver-COM.x://keliver-debug/bundles?server=http%3A%2F%2F192.168.1.20%3A8078",
    )) {
      assertEquals(listOf(DeviceLink(url, null, null)), deviceLinks(statusWithLink(url, bad, svg)), bad)
    }
  }

  @Test fun theDebugLinkCanBeOpenedWithoutACamera() {
    val link = "keliver-com.example.shop://keliver-debug/bundles?server=http%3A%2F%2F10.0.2.2%3A8078"
    val (adb, simctl) = debugLinkCommands(link)
    assertEquals("adb shell am start -a android.intent.action.VIEW -d '$link'", adb)
    assertEquals("xcrun simctl openurl booted '$link'", simctl)
  }
}
