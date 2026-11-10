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
