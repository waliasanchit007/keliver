import java.io.File

/** Runs and promotes the consumer editor build without ever serving a stale/incomplete dist. */
class PreviewDistributionRunner(
  private val repoDir: File,
  private val config: PortalConfig,
  private val execute: (List<String>, () -> Boolean) -> CommandResult = ::executeCommand,
) : PreviewBuilder.Runner {
  data class CommandResult(val exitCode: Int, val output: String)

  override fun build(cancelled: () -> Boolean): String? {
    val command = listOf(File(repoDir, "gradlew").absolutePath, config.previewBuildTask, "-q")
    val first = execute(command, cancelled)
    if (cancelled()) return "cancelled"
    if (first.exitCode != 0) return first.errorSummary()

    val missing = missingDistributionArtifacts()
    if (missing.isEmpty()) return null

    // Gradle can report the webpack distribution task UP-TO-DATE while its
    // copied dist was deleted or is incomplete. Retry once from task outputs.
    val retry = execute(command + "--rerun-tasks", cancelled)
    if (cancelled()) return "cancelled"
    if (retry.exitCode != 0) return retry.errorSummary()
    val stillMissing = missingDistributionArtifacts()
    return if (stillMissing.isEmpty()) null else {
      "preview build produced an incomplete distribution after retry; missing ${stillMissing.joinToString()} at ${File(repoDir, config.previewDist)}"
    }
  }

  override fun promote() {
    val missing = missingDistributionArtifacts()
    check(missing.isEmpty()) {
      "incomplete preview distribution; missing ${missing.joinToString()} at ${File(repoDir, config.previewDist)}"
    }

    val src = File(repoDir, config.previewDist)
    val dst = File(repoDir, config.previewServeDir)
    val tmp = File(dst.parentFile ?: repoDir, dst.name + ".tmp")
    tmp.deleteRecursively()
    check(src.copyRecursively(tmp, overwrite = true)) { "could not copy $src to $tmp" }

    // Cache-bust the stable loader filename before the swap, so the promoted
    // tree is complete at every point visible to the HTTP server.
    val index = File(tmp, "index.html")
    val stamped = index.readText().replace(
      Regex("""web-spike\.js(\?v=\d+)?"""), "web-spike.js?v=${System.currentTimeMillis()}",
    )
    index.writeText(stamped)

    dst.deleteRecursively()
    check(tmp.renameTo(dst)) { "could not promote $tmp to $dst" }
  }

  private fun missingDistributionArtifacts(): List<String> {
    val dist = File(repoDir, config.previewDist)
    if (!dist.isDirectory) return listOf("directory")
    return buildList {
      if (!File(dist, "index.html").isFile) add("index.html")
      if (dist.listFiles()?.none { it.isFile && it.extension == "js" } != false) add("*.js")
      if (dist.listFiles()?.none { it.isFile && it.extension == "wasm" } != false) add("*.wasm")
    }
  }

  private fun CommandResult.errorSummary(): String =
    output.lines().filter { it.isNotBlank() }.takeLast(15).joinToString("\n")

  companion object {
    private fun executeCommand(command: List<String>, cancelled: () -> Boolean): CommandResult {
      val process = ProcessBuilder(command).directory(File(command.first()).parentFile).redirectErrorStream(true).start()
      val output = StringBuilder()
      process.inputStream.bufferedReader().forEachLine { line ->
        output.appendLine(line)
        if (cancelled()) process.destroy()
      }
      return CommandResult(process.waitFor(), output.toString())
    }
  }
}
