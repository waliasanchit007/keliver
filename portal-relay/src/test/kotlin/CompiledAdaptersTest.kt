import dev.keliver.portal.ingest.TokenTable
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Compiled component preview: the relay writes the per-app editor's adapters before a preview build. */
class CompiledAdaptersTest {
  private fun repoWith(vararg components: Pair<String, String>): File {
    val repo = createTempDirectory("compiled-adapters").toFile()
    val dir = File(repo, "app/components").apply { mkdirs() }
    components.forEach { (name, text) -> File(dir, name).writeText(text) }
    return repo
  }

  private val badge = """
    package app.ui
    import androidx.compose.runtime.Composable
    import dev.keliver.Modifier
    @Composable
    fun PriceTag(label: String, modifier: Modifier = Modifier) { val x = label.uppercase() }
  """.trimIndent()

  private val config = PortalConfig(
    componentsDir = "app/components",
    compiledComponents = CompiledComponentsConfig(out = "editor/src/wasmJsMain/kotlin/AppCompiled.kt", valName = "AppCompiled"),
  )

  @Test fun offWithoutConfig() {
    val repo = repoWith("PriceTag.kt" to badge)
    assertNull(CompiledAdapters.emit(repo, PortalConfig(), File(repo, "app/components"), TokenTable.EMPTY))
    assertFalse(File(repo, "editor").exists())
  }

  @Test fun writesTheAdaptersAndRewritesOnlyOnChange() {
    val repo = repoWith("PriceTag.kt" to badge)
    val out = File(repo, "editor/src/wasmJsMain/kotlin/AppCompiled.kt")
    assertNull(CompiledAdapters.emit(repo, config, File(repo, "app/components"), TokenTable.EMPTY))
    val text = out.readText()
    assertTrue("\"PriceTag\" to CompiledComponent" in text, text)
    assertTrue("import app.ui.PriceTag" in text, text)
    out.setLastModified(1_000)
    assertNull(CompiledAdapters.emit(repo, config, File(repo, "app/components"), TokenTable.EMPTY))
    assertEquals(1_000, out.lastModified(), "unchanged adapters are not rewritten")
  }

  @Test fun anOutPathOutsideTheRepoFailsTheBuildStep() {
    val repo = repoWith("PriceTag.kt" to badge)
    val escaping = config.copy(compiledComponents = CompiledComponentsConfig(out = "../escape/AppCompiled.kt"))
    val err = CompiledAdapters.emit(repo, escaping, File(repo, "app/components"), TokenTable.EMPTY)
    assertNotNull(err)
    assertTrue("inside the app repository" in err, err)
  }

  @Test fun aPrepareFailureFailsThePreviewBuildBeforeGradleRuns() {
    val repo = repoWith()
    var ran = false
    val runner = PreviewDistributionRunner(repo, PortalConfig(previewDist = "dist"), prepare = { "compiled component adapters: boom" }) { _, _ ->
      ran = true
      PreviewDistributionRunner.CommandResult(0, "")
    }
    assertEquals("compiled component adapters: boom", runner.build { false })
    assertFalse(ran)
  }

  /** This repo's own per-app editor (web-spike) carries the adapters the relay would write for portal-app-lib. */
  @Test fun theDogfoodEditorsAdaptersAreCurrent() {
    var root: File? = File("").absoluteFile
    while (root != null && !File(root, "keliver.portal.json").isFile) root = root.parentFile
    val repo = requireNotNull(root) { "repo root not found" }
    val cfg = loadPortalConfig(repo)
    val compiled = assertNotNull(cfg.compiledComponents, "keliver.portal.json declares compiledComponents")
    val out = File(repo, compiled.out)
    val scratch = createTempDirectory("compiled-adapters-dogfood").toFile()
    val copy = cfg.copy(compiledComponents = compiled.copy(out = "AppLib.kt"))
    // portal-app-lib's components declare no design tokens, so the relay's table adds nothing here.
    assertNull(CompiledAdapters.emit(scratch, copy, File(repo, cfg.resolvedComponentsDir()), TokenTable.EMPTY))
    val fresh = File(scratch, "AppLib.kt").readText()
    if (System.getenv("KELIVER_UPDATE_COMPILED_ADAPTERS") == "1") out.writeText(fresh)
    assertEquals(fresh, out.takeIf { it.isFile }?.readText(), "stale ${compiled.out}: run the relay's preview build (or KELIVER_UPDATE_COMPILED_ADAPTERS=1 on this test) and commit it")
    // The dogfood opaque component the editor draws compiled.
    val pill = File(repo, cfg.resolvedComponentsDir() + "/NoteCountPill.kt")
    val spec = dev.keliver.portal.ingest.Recognizer.recognizeComponent(pill.name, pill.readText())!!.spec
    assertFalse(spec.transparent, "NoteCountPill must stay opaque: ${spec.diagnostic}")
    assertTrue("\"NoteCountPill\" to CompiledComponent" in fresh, fresh)
  }
}
