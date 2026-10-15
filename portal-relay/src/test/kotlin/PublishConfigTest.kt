import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublishConfigTest {
  private val dirs = mutableListOf<File>()
  private fun repo(json: String): File =
    Files.createTempDirectory("publish-config").toFile().also {
      dirs += it
      File(it, "keliver.portal.json").writeText(json)
    }
  private fun File.rewrite(json: String) = File(this, "keliver.portal.json").writeText(json)

  @AfterTest fun cleanUp() = dirs.forEach { it.deleteRecursively() }

  @Test
  fun aPublishTaskChangedAfterStartIsUsed() {
    val dir = repo("""{"publishTask":":guest:compileDevelopmentZipline","publishOutput":"guest/build/zipline/Development"}""")
    val started = loadPortalConfig(dir)
    dir.rewrite("""{"publishTask":":guest:compileProductionZipline","publishOutput":"guest/build/zipline/Production"}""")

    val now = PublishConfig.current(dir, existedAtStart = true).getOrThrow()
    assertEquals(":guest:compileProductionZipline", now.publishTask)
    assertEquals("guest/build/zipline/Production", now.publishOutput)
    assertEquals(emptyList(), PublishConfig.restartNeeded(started, now))
  }

  @Test
  fun otherChangedSettingsAreNamed() {
    val dir = repo("""{"port":8077,"screensDir":"a/screens"}""")
    val started = loadPortalConfig(dir)
    dir.rewrite("""{"port":9000,"screensDir":"b/screens","publishTask":":x"}""")
    assertEquals(listOf("port", "screensDir"), PublishConfig.restartNeeded(started, PublishConfig.current(dir, true).getOrThrow()))
  }

  @Test
  fun nestedAndNullableChangesAreNamed() {
    val dir = repo("""{}""")
    val started = loadPortalConfig(dir)
    dir.rewrite("""{"appRuntime":{"keliverVersion":"9.9.9","widgetVersion":1},"httpRecording":{"fixtureTtlDays":7}}""")
    assertEquals(listOf("appRuntime", "httpRecording"), PublishConfig.restartNeeded(started, PublishConfig.current(dir, true).getOrThrow()))
  }

  @Test
  fun aValueEqualToTheDefaultIsNoChange() {
    val dir = repo("""{}""")
    val started = loadPortalConfig(dir)
    dir.rewrite("""{"port":8077}""")
    assertEquals(emptyList(), PublishConfig.restartNeeded(started, PublishConfig.current(dir, true).getOrThrow()))
  }

  @Test
  fun aBrokenFileIsAFailureNotTheOldValues() {
    val dir = repo("""{"publishTask":":ok"}""")
    dir.rewrite("""{"publishTask": """)
    assertTrue(PublishConfig.current(dir, true).isFailure)
  }

  @Test
  fun aFileThatFailsValidationIsAFailure() {
    val dir = repo("""{}""")
    dir.rewrite("""{"httpRecording":{"fixtureTtlDays":0}}""")
    assertTrue(PublishConfig.current(dir, true).isFailure)
  }

  @Test
  fun aFileThatWasThereAtStartAndIsGoneIsAFailure() {
    val dir = repo("""{"publishTask":":ok"}""")
    File(dir, "keliver.portal.json").delete()
    assertTrue(PublishConfig.current(dir, existedAtStart = true).isFailure)
    // An app that never had one keeps the defaults, as at start.
    assertTrue(PublishConfig.current(dir, existedAtStart = false).isSuccess)
  }
}
