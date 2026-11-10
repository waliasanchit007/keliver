import dev.keliver.portal.document.DocNode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `keliver check` (WS5): editor ingest, the unsupported-API scan and the bundle budget, on a scratch app. */
class KeliverCheckTest {
  private fun app(config: String = """{"screensDir":"src/screens","publishOutput":"build/bundle"}"""): File {
    val dir = createTempDirectory("keliver-check").toFile()
    File(dir, "keliver.portal.json").writeText(config)
    File(dir, "src/screens").mkdirs()
    return dir
  }

  private val clean = """
    package app.screens
    import androidx.compose.runtime.Composable
    import dev.keliver.material.compose.Text

    interface HomeBindings { val title: String }

    @Composable
    fun HomeScreen(b: HomeBindings) {
      Text(text = b.title)
    }
  """.trimIndent()

  private val withRaw = """
    package app.screens
    import androidx.compose.runtime.Composable
    import dev.keliver.material.compose.Text

    interface HomeBindings { val title: String }

    @Composable
    fun HomeScreen(b: HomeBindings) {
      Text(text = b.title)
      val x = listOf(1, 2).map { it * 2 }.joinToString()
    }
  """.trimIndent()

  @Test fun cleanScreenHasNoRawCode() {
    val dir = app()
    File(dir, "src/screens/Home.kt").writeText(clean)
    val f = KeliverCheck.editor(dir, loadPortalConfig(dir))
    assertTrue(f.none { it.severity == "error" }, f.toString())
    assertEquals("pass", f.last().severity)
    assertTrue(f.last().message.contains("1 screen(s)"), f.last().message)
  }

  @Test fun rawCodeIsReportedWithFileAndLine() {
    val dir = app()
    File(dir, "src/screens/Home.kt").writeText(withRaw)
    val errors = KeliverCheck.editor(dir, loadPortalConfig(dir)).filter { it.severity == "error" }
    assertEquals(1, errors.size, errors.toString())
    assertEquals("src/screens/Home.kt", errors[0].file)
    assertEquals(10, errors[0].line)
    assertTrue(errors[0].toJson().toString().contains("\"line\":10"))
  }

  @Test fun unsupportedApisAreWarnings() {
    val dir = app()
    File(dir, "src/logic").mkdirs()
    File(dir, "src/logic/Repo.kt").writeText(
      """
      package app.logic
      import java.io.File
      import androidx.compose.runtime.remember
      import androidx.compose.ui.Modifier
      // import android.content.Context  (a comment: not reported)
      class Repo { fun now() = System.currentTimeMillis(); val k = Repo::class.java }
      val s = "import okhttp3.OkHttpClient"
      """.trimIndent(),
    )
    val f = KeliverCheck.api(dir, loadPortalConfig(dir))
    val rules = f.filter { it.severity == "warning" }.map { "${it.extra["rule"]}@${it.line}" }
    assertEquals(listOf("java@2", "androidx@4", "jvm-reflection@6", "jvm-system@6"), rules)
    assertEquals("src/logic/Repo.kt", f.first().file)
  }

  @Test fun cleanGuestPassesTheApiScan() {
    val dir = app()
    File(dir, "src/screens/Home.kt").writeText(clean)
    assertEquals(listOf("pass"), KeliverCheck.api(dir, loadPortalConfig(dir)).map { it.severity })
  }

  @Test fun budget() {
    val none = app()
    assertEquals("skip", KeliverCheck.run(none, listOf("budget")).single().severity)

    val dir = app("""{"screensDir":"src/screens","publishOutput":"build/bundle","check":{"bundleBudgetBytes":100}}""")
    assertEquals("error", KeliverCheck.run(dir, listOf("budget")).single().severity) // nothing built
    File(dir, "build/bundle").mkdirs()
    File(dir, "build/bundle/app.zipline").writeBytes(ByteArray(60))
    val pass = KeliverCheck.run(dir, listOf("budget")).single()
    assertEquals("pass", pass.severity)
    assertEquals(60L, pass.extra["bytes"])
    File(dir, "build/bundle/lib.zipline").writeBytes(ByteArray(50))
    assertEquals("error", KeliverCheck.run(dir, listOf("budget")).single().severity)
  }

  @Test fun budgetSkipsAStaleBundleAfterAFailedCompile() {
    val dir = app("""{"screensDir":"src/screens","publishOutput":"build/bundle","check":{"bundleBudgetBytes":100}}""")
    File(dir, "build/bundle").mkdirs()
    File(dir, "build/bundle/app.zipline").writeBytes(ByteArray(60))
    assertEquals("skip", KeliverCheck.run(dir, listOf("budget"), KeliverCheck.CompileRun("fail", 1L)).single().severity)
    // Compiled by this run but not rewritten: measured, and said so.
    File(dir, "build/bundle/app.zipline").setLastModified(1_000L)
    val stale = KeliverCheck.run(dir, listOf("budget"), KeliverCheck.CompileRun("pass", 2_000_000_000L)).single()
    assertEquals(false, stale.extra["rebuilt"])
    assertTrue(stale.message.contains("not rewritten"), stale.message)
    val fresh = KeliverCheck.run(dir, listOf("budget"), KeliverCheck.CompileRun("pass", 1L)).single()
    assertEquals(true, fresh.extra["rebuilt"])
  }

  @Test fun componentsAreReadAsTheRelayOpensThem() {
    val dir = app("""{"screensDir":"src/screens","componentsDir":"src/components","publishOutput":"build/bundle"}""")
    File(dir, "src/components").mkdirs()
    File(dir, "src/components/PriceTag.kt").writeText(
      """
      package app.components
      import androidx.compose.runtime.Composable
      import dev.keliver.material.compose.Text

      @Composable
      fun PriceTag(label: String) {
        Text(text = label)
        val x = listOf(1).size
      }
      """.trimIndent(),
    )
    File(dir, "src/components/Empty.kt").writeText("package app.components\nval nothing = 1\n")
    val f = KeliverCheck.editor(dir, loadPortalConfig(dir))
    val errors = f.filter { it.severity == "error" }
    assertTrue(errors.any { it.file == "src/components/PriceTag.kt" && it.line == 8 && it.message.startsWith("RawCode") }, errors.toString())
    assertTrue(errors.any { it.file == "src/components/Empty.kt" && it.message.contains("no @Composable") }, errors.toString())
    assertTrue(f.last().message.contains("2 component(s)"), f.last().message)
  }

  @Test fun duplicatedRawCodeGetsBothLines() {
    val src = "a\nX\nb\nX\n"
    val root = DocNode.Widget(
      dev.keliver.portal.document.Handle(1), "Column", children = listOf(
        DocNode.RawCode(dev.keliver.portal.document.Handle(2), "X"),
        DocNode.RawCode(dev.keliver.portal.document.Handle(3), "X"),
      ),
    )
    assertEquals(listOf(2, 4), KeliverCheck.rawCodes(root, src).map { it.second })
  }

  @Test fun compileTaskDefaultsToThePublishTask() {
    assertEquals(null, KeliverCheck.checkConfig(app()).compileTask)
    val dir = app("""{"screensDir":"s","check":{"compileTask":":g:compileDevelopmentZipline"}}""")
    assertEquals(":g:compileDevelopmentZipline", KeliverCheck.checkConfig(dir).compileTask)
  }

  @Test fun stripComments() {
    assertEquals("a " to false, KeliverCheck.stripComments("a // b", false))
    assertEquals("a  c" to false, KeliverCheck.stripComments("a /* b */ c", false))
    assertEquals("" to true, KeliverCheck.stripComments("/* open", false))
    assertEquals(" x" to false, KeliverCheck.stripComments("end */ x", true))
    assertEquals("\"//\"" to false, KeliverCheck.stripComments("\"//\"", false))
  }
}
