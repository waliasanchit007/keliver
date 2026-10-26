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
}
