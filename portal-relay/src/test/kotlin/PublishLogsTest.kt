import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublishLogsTest {
  private val dirs = mutableListOf<File>()
  private fun dir(): File = Files.createTempDirectory("publish-logs").toFile().also { dirs += it }

  @AfterTest fun cleanUp() = dirs.forEach { it.deleteRecursively() }

  @Test
  fun theWholeOutputIsKeptOutsideTheServedBundles() {
    val store = dir()
    val output = (1..500).joinToString("\n") { "line $it" }
    val file = PublishLogs.save(store, sequence = 7, output = output, nowMillis = 1000)
    assertEquals(output, file.readText()) // all 500 lines, not the response's last 30
    assertEquals(File(store, PublishLogs.DIR), file.parentFile)
    assertFalse(file.canonicalPath.startsWith(File(store, "bundles").canonicalPath))
  }

  @Test
  fun logsAreOwnerOnlyFromCreation() {
    if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
    val store = dir()
    File(store, PublishLogs.DIR).mkdirs() // an existing, umask-default directory is narrowed too
    val file = PublishLogs.save(store, sequence = 1, output = "secret", nowMillis = 1000)
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parentFile.toPath())))
  }

  @Test
  fun theLogDirectoryIsNeverAProject() {
    assertTrue(PublishLogs.DIR in RESERVED_ROOT_DIRS)
    assertTrue("reports" in RESERVED_ROOT_DIRS) // HostReports' directory, the same gap
  }

  @Test
  fun onlyTheNewestLogsAreKept() {
    val store = dir()
    repeat(PublishLogs.KEEP + 5) { i -> PublishLogs.save(store, sequence = i + 1, output = "run $i", nowMillis = 1000L + i) }
    val names = File(store, PublishLogs.DIR).list()!!.sorted()
    assertEquals(PublishLogs.KEEP, names.size)
    assertFalse("publish-v1-1000.log" in names) // the oldest went
    assertTrue("publish-v${PublishLogs.KEEP + 5}-${1000 + PublishLogs.KEEP + 4}.log" in names)
  }

  @Test
  fun otherFilesInTheDirectoryAreLeftAlone() {
    val store = dir()
    val other = File(store, "${PublishLogs.DIR}/notes.txt").apply { parentFile.mkdirs(); writeText("mine") }
    repeat(PublishLogs.KEEP + 1) { i -> PublishLogs.save(store, sequence = i + 1, output = "", nowMillis = 1000L + i) }
    assertTrue(other.isFile)
  }

  @Test
  fun onlyACompilerCrashIsBuiltAgain() {
    val crash = "e: java.lang.IllegalStateException: Internal compiler error. See log for more details\nBUILD FAILED in 4s"
    assertTrue(PublishRetry.isCompilerCrash(1, crash))
    assertTrue(PublishRetry.isCompilerCrash(1, "org.jetbrains.kotlin.backend.common.BackendException: Backend Internal error: Exception during IR lowering"))
    assertFalse(PublishRetry.isCompilerCrash(0, crash)) // it passed: nothing to retry
    assertFalse(PublishRetry.isCompilerCrash(1, "e: file:///app/src/jsMain/kotlin/screens/a.kt:3:5 Unresolved reference 'x'.\nBUILD FAILED in 2s"))
    assertFalse(PublishRetry.isCompilerCrash(1, "FAILURE: Build failed with an exception."))
  }

  @Test
  fun aCompilerCrashIsBuiltOnceMoreNeverTwice() {
    val crash = 1 to "e: Internal compiler error"
    fun runWith(vararg results: Pair<Int, String>): Triple<Int, Boolean, Int> {
      var calls = 0
      var retries = 0
      val (code, retried) = PublishRetry.run(onRetry = { retries++ }, build = { results[calls++] })
      assertEquals(if (retried) 1 else 0, retries)
      return Triple(code, retried, calls)
    }
    assertEquals(Triple(0, true, 2), runWith(crash, 0 to "BUILD SUCCESSFUL")) // the retry passed
    assertEquals(Triple(1, true, 2), runWith(crash, crash, crash)) // crashed again: reported, not a third build
    assertEquals(Triple(1, false, 1), runWith(1 to "e: Unresolved reference 'x'.", 0 to "unused")) // a real error: once
    assertEquals(Triple(0, false, 1), runWith(0 to "BUILD SUCCESSFUL"))
  }
}
