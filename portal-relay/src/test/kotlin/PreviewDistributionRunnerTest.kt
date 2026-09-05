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

  /**
   * Regression: the stamp used to be a literal `web-spike.js` replace, so it
   * matched only THIS repo's dogfood editor. `keliver-new-editor.sh` emits
   * `<app>-editor.js`, so for every consumer nothing was stamped and the
   * browser kept the cached loader with its stale baked-in wasm hash. The
   * existing test above used the one filename that happened to work.
   */
  @Test fun promotionStampsAConsumerEditorLoaderName() {
    val repo = createTempDirectory("portal-preview").toFile()
    val config = PortalConfig(previewDist = "dist", previewServeDir = "live")
    val dist = File(repo, "dist")
    dist.mkdirs()
    File(dist, "index.html").writeText("<script src=\"acme-editor.js\"></script>")
    File(dist, "acme-editor.js").writeText("// loader")
    File(dist, "app.wasm").writeBytes(byteArrayOf(0))

    PreviewDistributionRunner(repo, config) { _, _ ->
      PreviewDistributionRunner.CommandResult(0, "")
    }.promote()

    val html = File(repo, "live/index.html").readText()
    assertTrue(html.contains("acme-editor.js?v="), "consumer loader was not cache-busted: $html")
  }

  private fun writeCompleteDist(dist: File) {
    dist.mkdirs()
    File(dist, "index.html").writeText("<script src=\"web-spike.js\"></script>")
    File(dist, "web-spike.js").writeText("// loader")
    File(dist, "app.wasm").writeBytes(byteArrayOf(0))
  }
}
