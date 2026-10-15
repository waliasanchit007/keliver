import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublishConfigTest {
  private fun repo(json: String): File =
    Files.createTempDirectory("publish-config").toFile().also { File(it, "keliver.portal.json").writeText(json) }

  @Test
  fun aPublishTaskChangedAfterStartIsUsed() {
    val dir = repo("""{"publishTask":":guest:compileDevelopmentZipline","publishOutput":"guest/build/zipline/Development"}""")
    val started = loadPortalConfig(dir)
    File(dir, "keliver.portal.json")
      .writeText("""{"publishTask":":guest:compileProductionZipline","publishOutput":"guest/build/zipline/Production"}""")

    val now = PublishConfig.current(dir).getOrThrow()
    assertEquals(":guest:compileProductionZipline", now.publishTask)
    assertEquals("guest/build/zipline/Production", now.publishOutput)
    assertEquals(emptyList(), PublishConfig.restartNeeded(started, now))
  }

  @Test
  fun otherChangedSettingsAreNamed() {
    val dir = repo("""{"port":8077,"screensDir":"a/screens"}""")
    val started = loadPortalConfig(dir)
    File(dir, "keliver.portal.json").writeText("""{"port":9000,"screensDir":"b/screens","publishTask":":x"}""")
    assertEquals(listOf("port", "screensDir"), PublishConfig.restartNeeded(started, PublishConfig.current(dir).getOrThrow()))
  }

  @Test
  fun aBrokenFileIsAFailureNotTheOldValues() {
    val dir = repo("""{"publishTask":":ok"}""")
    File(dir, "keliver.portal.json").writeText("""{"publishTask": """)
    assertTrue(PublishConfig.current(dir).isFailure)
  }
}
