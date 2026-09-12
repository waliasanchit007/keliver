import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The store contract has one authoritative implementation
 * (`PortalConfig.storeDir`) and one shell mirror
 * (`scripts/keliver-store-path.sh`) that the Gradle builds and the recording
 * client call. This asserts they agree, so the mirror cannot drift.
 *
 * They disagreed once: the relay moved to a per-app store while
 * portal-published-guest, both device hosts and keliver-record-http.sh still
 * resolved ~/.keliver-portal — a bundle signed with one identity and verified
 * against another, and a recording token looked for in the wrong directory.
 */
class StoreContractTest {
  private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
    .first { File(it, "scripts/keliver-store-path.sh").exists() }
  private val script = File(repoRoot, "scripts/keliver-store-path.sh")

  private fun tmp(name: String) =
    java.nio.file.Files.createTempDirectory(name).toFile().also { it.deleteOnExit() }

  private fun viaScript(app: File, home: File, env: Map<String, String> = emptyMap()): String {
    val pb = ProcessBuilder(script.absolutePath, app.absolutePath, "--home", home.absolutePath)
    pb.environment().putAll(env)
    pb.redirectErrorStream(true)
    val p = pb.start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor()
    assertEquals(0, p.exitValue(), "resolver failed: $out")
    return out
  }

  @Test
  fun theDefaultAgrees() {
    val home = tmp("home")
    repeat(3) {
      val app = tmp("app$it")
      val kotlin = withUserHome(home) { PortalConfig().storeDir(app) }
      assertEquals(kotlin.canonicalPath, File(viaScript(app, home)).canonicalPath)
    }
  }

  @Test
  fun anExplicitAbsoluteStoreAgrees() {
    val home = tmp("home")
    val app = tmp("app")
    val explicit = tmp("explicit")
    File(app, "keliver.portal.json").writeText("""{"store":"${explicit.absolutePath}"}""")
    val kotlin = withUserHome(home) { PortalConfig(store = explicit.absolutePath).storeDir(app) }
    assertEquals(kotlin.canonicalPath, File(viaScript(app, home)).canonicalPath)
  }

  @Test
  fun aTildeStoreAgrees() {
    val home = tmp("home")
    val app = tmp("app")
    File(app, "keliver.portal.json").writeText("""{"store":"~/.keliver-portal"}""")
    val kotlin = withUserHome(home) { PortalConfig(store = "~/.keliver-portal").storeDir(app) }
    assertEquals(kotlin.canonicalPath, File(viaScript(app, home)).canonicalPath)
  }

  @Test
  fun portalStoreEnvWins() {
    val home = tmp("home")
    val app = tmp("app")
    val forced = tmp("forced")
    assertEquals(
      forced.canonicalPath,
      File(viaScript(app, home, mapOf("PORTAL_STORE" to forced.absolutePath))).canonicalPath,
    )
  }

  @Test
  fun thePointerIsUsedWhenThereIsNoExplicitStore() {
    val home = tmp("home")
    val app = tmp("app")
    val pointed = tmp("pointed")
    File(app, ".gradle").mkdirs()
    File(app, ".gradle/keliver-store-path").writeText(pointed.absolutePath + "\n")
    assertEquals(pointed.canonicalPath, File(viaScript(app, home)).canonicalPath)
  }

  @Test
  fun theDefaultIsNeverInsideTheApp() {
    val home = tmp("home")
    val app = tmp("app")
    assertTrue(!File(viaScript(app, home)).canonicalPath.startsWith(app.canonicalPath))
  }

  private fun <T> withUserHome(home: File, block: () -> T): T {
    val previous = System.getProperty("user.home")
    System.setProperty("user.home", home.absolutePath)
    try { return block() } finally { System.setProperty("user.home", previous) }
  }
}
