import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * U37: compiles a screen file for real — not only parses it — so a test can
 * show that what write-back wrote still compiles (a member redeclared
 * without `override` parses fine and does not compile). kotlinc runs in its
 * own JVM, from this test's runtime classpath (portal-ingest carries the
 * compiler), so it never shares state with the resident PSI environment.
 * The same harness as portal-ingest's tests (test sources are not shared).
 *
 * The screen's imports are stubbed with plain declarations ([STUBS]): no
 * Compose compiler, just enough for the call shapes the fixtures use.
 */
internal object KotlinCompileCheck {
  val STUBS = mapOf(
    "Composable.kt" to "package androidx.compose.runtime\n\nannotation class Composable\n",
    "Column.kt" to "package dev.keliver.layout.compose\n\nfun Column(content: () -> Unit) = content()\n",
    "Material.kt" to """
      package dev.keliver.material.compose

      fun StyledText(text: String, fontSize: Int = 0) {}
      fun ListItem(headline: String, onClick: () -> Unit = {}) {}
    """.trimIndent() + "\n",
  )

  /** Null when [sources] (file name → text, with [STUBS]) compile; else kotlinc's output. */
  fun errors(sources: Map<String, String>): String? {
    val dir = Files.createTempDirectory("u37-compile").toFile()
    try {
      val src = File(dir, "src").apply { mkdirs() }
      val files = (STUBS + sources).map { (name, text) -> File(src, name).apply { writeText(text) } }
      val stdlib = File(KotlinVersion::class.java.protectionDomain.codeSource.location.toURI()).absolutePath
      val java = File(System.getProperty("java.home"), "bin/java").absolutePath
      val cmd = listOf(
        java, "-cp", System.getProperty("java.class.path"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-nowarn", "-classpath", stdlib, "-d", File(dir, "out").absolutePath,
      ) + files.map { it.absolutePath }
      val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
      val out = proc.inputStream.bufferedReader().readText()
      check(proc.waitFor(3, TimeUnit.MINUTES)) { "kotlinc did not finish" }
      return if (proc.exitValue() == 0) null else out.ifBlank { "kotlinc exited ${proc.exitValue()}" }
    } finally {
      dir.deleteRecursively()
    }
  }
}
