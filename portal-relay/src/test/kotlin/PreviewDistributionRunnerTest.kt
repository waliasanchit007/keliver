import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewDistributionRunnerTest {
  @Test fun missingDistRetriesBuildAndThenSucceeds() {
    val repo = createTempDirectory("portal-preview").toFile()
    val commands = mutableListOf<List<String>>()
    val config = PortalConfig(previewDist = "dist", previewServeDir = "live")
    val runner = PreviewDistributionRunner(repo, config) { command, _ ->
      commands += command
      if (commands.size == 2) writeCompleteDist(File(repo, "dist"))
      PreviewDistributionRunner.CommandResult(0, "")
    }

    assertNull(runner.build { false })
    assertEquals(2, commands.size)
    assertTrue("--rerun-tasks" in commands.last())
  }

  @Test fun promotionStampsAndCopiesOnlyCompleteDistribution() {
    val repo = createTempDirectory("portal-preview").toFile()
    val config = PortalConfig(previewDist = "dist", previewServeDir = "live")
    writeCompleteDist(File(repo, "dist"))
    val runner = PreviewDistributionRunner(repo, config) { _, _ ->
      PreviewDistributionRunner.CommandResult(0, "")
    }

    runner.promote()

    val live = File(repo, "live")
    assertTrue(File(live, "index.html").readText().contains("web-spike.js?v="))
    assertTrue(File(live, "app.wasm").isFile)
  }

  private fun writeCompleteDist(dist: File) {
    dist.mkdirs()
    File(dist, "index.html").writeText("<script src=\"web-spike.js\"></script>")
    File(dist, "web-spike.js").writeText("// loader")
    File(dist, "app.wasm").writeBytes(byteArrayOf(0))
  }
}
