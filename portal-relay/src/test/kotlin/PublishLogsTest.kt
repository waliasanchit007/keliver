import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublishLogsTest {
  private fun dir(): File = Files.createTempDirectory("publish-logs").toFile()

  @Test
  fun theWholeOutputIsKeptOutsideTheServedBundles() {
    val store = dir()
    val output = (1..500).joinToString("\n") { "line $it" }
    val file = PublishLogs.save(store, sequence = 7, output = output, nowMillis = 1000)
    assertEquals(output, file.readText()) // all 500 lines, not the response's last 30
    assertEquals(File(store, "publish-logs"), file.parentFile)
    assertFalse(file.canonicalPath.startsWith(File(store, "bundles").canonicalPath))
  }

  @Test
  fun onlyTheNewestLogsAreKept() {
    val store = dir()
    repeat(PublishLogs.KEEP + 5) { i -> PublishLogs.save(store, sequence = i + 1, output = "run $i", nowMillis = 1000L + i) }
    val names = File(store, "publish-logs").list()!!.sorted()
    assertEquals(PublishLogs.KEEP, names.size)
    assertFalse("publish-v1-1000.log" in names) // the oldest went
    assertTrue("publish-v${PublishLogs.KEEP + 5}-${1000 + PublishLogs.KEEP + 4}.log" in names)
  }

  @Test
  fun otherFilesInTheDirectoryAreLeftAlone() {
    val store = dir()
    val other = File(store, "publish-logs/notes.txt").apply { parentFile.mkdirs(); writeText("mine") }
    repeat(PublishLogs.KEEP + 1) { i -> PublishLogs.save(store, sequence = i + 1, output = "", nowMillis = 1000L + i) }
    assertTrue(other.isFile)
  }
}
