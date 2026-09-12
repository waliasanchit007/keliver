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
    // This step used to be asserted for the SCRIPT only, while the function it
    // calls itself authoritative did not implement it at all (U23).
    val kotlin = withUserHome(home) { PortalConfig().storeDir(app) }
    assertEquals(pointed.canonicalPath, kotlin.canonicalPath)
  }

  @Test
  fun aSymlinkAndTheRealPathAgree() {
    // Every other case here uses a Files.createTempDirectory path, whose final
    // component is never a symlink — which is precisely why this suite could
    // not see U25.1.
    val home = tmp("home")
    val work = tmp("work")
    val real = File(work, "app-v2").also { it.mkdirs() }
    val link = File(work, "current")
    java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath())

    val kotlinReal = withUserHome(home) { PortalConfig().storeDir(real) }
    val kotlinLink = withUserHome(home) { PortalConfig().storeDir(link) }
    assertEquals(kotlinReal.canonicalPath, kotlinLink.canonicalPath)
    assertEquals(kotlinLink.canonicalPath, File(viaScript(link, home)).canonicalPath)
    assertEquals(kotlinReal.canonicalPath, File(viaScript(real, home)).canonicalPath)
  }

  @Test
  fun aUnicodeDirectoryNameAgrees() {
    val home = tmp("home")
    val work = tmp("work")
    // Kotlin's slug filter was ASCII and character-wise; Python's isalnum() is
    // Unicode-aware and code-point-wise. They produced different names.
    for (name in listOf("café-☕", "Ünïcödé", "a b", "a\uD83D\uDE00b")) {
      val app = File(work, name).also { it.mkdirs() }
      val kotlin = withUserHome(home) { PortalConfig().storeDir(app) }
      assertEquals(kotlin.canonicalPath, File(viaScript(app, home)).canonicalPath, "for name '$name'")
    }
  }

  @Test
  fun aPathContainingDotDotAgrees() {
    // `abspath` collapses ".." lexically; the JVM resolves the symlink first.
    // For work/x/link/.. that is two different app directories — one store each.
    val home = tmp("home")
    val work = tmp("work")
    val target = File(work, "target").also { it.mkdirs() }
    File(work, "x").mkdirs()
    java.nio.file.Files.createSymbolicLink(File(work, "x/link").toPath(), target.toPath())
    val viaDotDot = File(work, "x/link/..")
    assertEquals(
      withUserHome(home) { PortalConfig().storeDir(viaDotDot) }.canonicalPath,
      File(viaScript(viaDotDot, home)).canonicalPath,
    )
  }

  @Test
  fun theDefaultOnlyModeAgrees() {
    val home = tmp("home")
    val app = tmp("app")
    // A pointer and an explicit store are both present; --default must ignore
    // both, the way the recovery command needs it to.
    File(app, ".gradle").mkdirs()
    File(app, ".gradle/keliver-store-path").writeText(tmp("pointed").absolutePath + "\n")
    File(app, "keliver.portal.json").writeText("""{"store":"${tmp("explicit").absolutePath}"}""")
    val kotlin = withUserHome(home) { defaultStoreDir(app) }
    val viaDefault = run {
      val pb = ProcessBuilder(script.absolutePath, app.absolutePath, "--home", home.absolutePath, "--default")
      pb.redirectErrorStream(true)
      val p = pb.start()
      val out = p.inputStream.bufferedReader().readText().trim()
      p.waitFor()
      assertEquals(0, p.exitValue(), out)
      out
    }
    assertEquals(kotlin.canonicalPath, File(viaDefault).canonicalPath)
  }

  @Test
  fun anExistingStoreUnderAnOlderSlugIsAdoptedByBoth() {
    val home = tmp("home")
    val app = tmp("app")
    val hash = withUserHome(home) { appStoreHash(app) }
    val older = File(home, ".keliver-portal/apps/whatever-it-was-called-$hash").also { it.mkdirs() }
    val kotlin = withUserHome(home) { PortalConfig().storeDir(app) }
    assertEquals(older.canonicalPath, kotlin.canonicalPath)
    assertEquals(older.canonicalPath, File(viaScript(app, home)).canonicalPath)
  }

  @Test
  fun aSplitStoreIsRefusedByBoth() {
    val home = tmp("home")
    val app = tmp("app")
    val hash = withUserHome(home) { appStoreHash(app) }
    File(home, ".keliver-portal/apps/current-$hash").mkdirs()
    File(home, ".keliver-portal/apps/app-v2-$hash").mkdirs()

    kotlin.test.assertFailsWith<StoreOwnershipException> {
      withUserHome(home) { PortalConfig().storeDir(app) }
    }
    val pb = ProcessBuilder(script.absolutePath, app.absolutePath, "--home", home.absolutePath)
    pb.redirectErrorStream(true)
    val p = pb.start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    assertEquals(3, p.exitValue(), "the mirror must refuse a split store too, got: $out")
    assertTrue("store split" in out, out)
  }

  @Test
  fun explainNamesTheRuleThatDecided() {
    // keliver-store-recover.sh branches on WHICH rule selected the store — a
    // committed "store" outranks it, a pointer is its own to rewrite — so the
    // labels are part of the contract, not a debugging aid. Re-deriving the
    // precedence in the recovery script is how the rules drifted the first time.
    val home = tmp("home")
    val app = tmp("app")
    fun explain(): Pair<String, String> {
      val pb = ProcessBuilder(script.absolutePath, app.absolutePath, "--home", home.absolutePath, "--explain")
      val p = pb.start()
      val out = p.inputStream.bufferedReader().readText().trim()
      val err = p.errorStream.bufferedReader().readText()
      p.waitFor()
      assertEquals(0, p.exitValue(), err)
      val parts = out.split('\t')
      assertEquals(2, parts.size, "expected '<step>\\t<path>', got '$out'")
      return parts[0] to parts[1]
    }

    val (step1, path1) = explain()
    assertEquals("default", step1)
    assertEquals(withUserHome(home) { PortalConfig().storeDir(app) }.canonicalPath, File(path1).canonicalPath)

    val pointed = tmp("pointed")
    File(app, ".gradle").mkdirs()
    File(app, ".gradle/keliver-store-path").writeText(pointed.absolutePath + "\n")
    val (step2, path2) = explain()
    assertEquals("pointer", step2)
    assertEquals(pointed.canonicalPath, File(path2).canonicalPath)
    assertEquals(File(path2).canonicalPath, withUserHome(home) { PortalConfig().storeDir(app) }.canonicalPath)

    val explicit = tmp("explicit")
    File(app, "keliver.portal.json").writeText("""{"store":"${explicit.absolutePath}"}""")
    val (step3, path3) = explain()
    assertEquals("config", step3)
    assertEquals(explicit.canonicalPath, File(path3).canonicalPath)

    // An existing store recorded under an older slug is reported as adopted,
    // so the recovery can tell it from a first boot.
    val app2 = tmp("app2")
    val older = File(home, ".keliver-portal/apps/older-name-${withUserHome(home) { appStoreHash(app2) }}")
    older.mkdirs()
    val pb = ProcessBuilder(script.absolutePath, app2.absolutePath, "--home", home.absolutePath, "--explain")
    val p = pb.start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor()
    assertEquals(0, p.exitValue(), out)
    assertEquals("adopted", out.substringBefore('\t'))
    assertEquals(older.canonicalPath, File(out.substringAfter('\t')).canonicalPath)
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
