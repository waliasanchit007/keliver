import dev.keliver.portal.ingest.CompiledAdapterGenerator
import dev.keliver.portal.ingest.TokenTable
import java.nio.file.Files
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
    assertTrue("\"PriceTag\" to dev.keliver.portal.render.CompiledComponent" in text, text)
    assertTrue("import app.ui.PriceTag as PriceTag__c0" in text, text)
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
    // Review m5: the SAME entry point and token table as the relay / CLI (`:portal-relay:compiledComponentAdapters -Pcheck`).
    val components = File(repo, cfg.resolvedComponentsDir())
    val tokens = CompiledAdapters.appTokens(repo, cfg, "compiled-adapters-dogfood-test")
    if (System.getenv("KELIVER_UPDATE_COMPILED_ADAPTERS") == "1") assertNull(CompiledAdapters.emit(repo, cfg, components, tokens))
    assertNull(CompiledAdapters.check(repo, cfg, components, tokens), "run ./gradlew :portal-relay:compiledComponentAdapters and commit ${compiled.out}")
    // The dogfood opaque component the editor draws compiled.
    val pill = File(components, "NoteCountPill.kt")
    val spec = dev.keliver.portal.ingest.Recognizer.recognizeComponent(pill.name, pill.readText())!!.spec
    assertFalse(spec.transparent, "NoteCountPill must stay opaque: ${spec.diagnostic}")
    assertTrue("\"NoteCountPill\" to dev.keliver.portal.render.CompiledComponent" in File(repo, compiled.out).readText())
  }

  @Test fun checkReportsAStaleFile() {
    val repo = repoWith("PriceTag.kt" to badge)
    val components = File(repo, "app/components")
    assertNull(CompiledAdapters.emit(repo, config, components, TokenTable.EMPTY))
    assertNull(CompiledAdapters.check(repo, config, components, TokenTable.EMPTY))
    File(components, "PriceTag.kt").writeText(badge.replace("label: String", "label: String, count: Int"))
    assertTrue("stale" in CompiledAdapters.check(repo, config, components, TokenTable.EMPTY)!!)
  }

  // ── review m3: never clobber a hand-written file, never write through a link, write atomically ──

  @Test fun aFileTheGeneratorDidNotWriteIsNeverOverwritten() {
    // Before the fix: the relay overwrote whatever sat at `out`.
    val repo = repoWith("PriceTag.kt" to badge)
    val out = File(repo, "editor/src/wasmJsMain/kotlin/AppCompiled.kt").apply { parentFile.mkdirs(); writeText("// my own code\nval x = 1\n") }
    val err = CompiledAdapters.emit(repo, config, File(repo, "app/components"), TokenTable.EMPTY)
    assertNotNull(err)
    assertTrue("refusing to overwrite" in err, err)
    assertEquals("// my own code\nval x = 1\n", out.readText())
  }

  @Test fun anOutPathThatIsASymbolicLinkIsRefused() {
    // Before the fix: written through the link (the target inside the repo was replaced).
    val repo = repoWith("PriceTag.kt" to badge)
    val target = File(repo, "elsewhere/Target.kt").apply { parentFile.mkdirs(); writeText(CompiledAdapterGenerator.GENERATED_HEADER + "\n") }
    val out = File(repo, "editor/src/wasmJsMain/kotlin/AppCompiled.kt").apply { parentFile.mkdirs() }
    Files.createSymbolicLink(out.toPath(), target.toPath())
    val err = CompiledAdapters.emit(repo, config, File(repo, "app/components"), TokenTable.EMPTY)
    assertNotNull(err)
    assertTrue("symbolic link" in err, err)
    assertEquals(CompiledAdapterGenerator.GENERATED_HEADER + "\n", target.readText())
  }

  @Test fun anOutPathThroughALinkedDirectoryIsRefused() {
    val repo = repoWith("PriceTag.kt" to badge)
    val realDir = File(repo, "real").apply { mkdirs() }
    File(repo, "editor/src/wasmJsMain").mkdirs()
    Files.createSymbolicLink(File(repo, "editor/src/wasmJsMain/kotlin").toPath(), realDir.toPath())
    val err = CompiledAdapters.emit(repo, config, File(repo, "app/components"), TokenTable.EMPTY)
    assertNotNull(err)
    assertTrue("symbolic link" in err, err)
    assertTrue(realDir.listFiles()!!.isEmpty())
  }

  @Test fun aCrlfFileStaysCrlfAndLeavesNoTempFile() {
    val repo = repoWith("PriceTag.kt" to badge)
    val components = File(repo, "app/components")
    val out = File(repo, "editor/src/wasmJsMain/kotlin/AppCompiled.kt")
    assertNull(CompiledAdapters.emit(repo, config, components, TokenTable.EMPTY))
    out.writeText(out.readText().replace("\n", "\r\n"))
    out.setLastModified(1_000)
    assertNull(CompiledAdapters.emit(repo, config, components, TokenTable.EMPTY))
    assertEquals(1_000, out.lastModified(), "the CRLF copy of the same text is not rewritten")
    File(components, "PriceTag.kt").writeText(badge.replace("label: String", "label: String, count: Int"))
    assertNull(CompiledAdapters.emit(repo, config, components, TokenTable.EMPTY))
    val text = out.readText()
    assertTrue("count" in text && text.count { it == '\n' } == Regex("\r\n").findAll(text).count(), "still CRLF")
    assertEquals(listOf("AppCompiled.kt"), out.parentFile.list()!!.toList())
  }

  // ── review m4: file-private design tokens resolve under the relay's file identity ──

  @Test fun aFilePrivateTokenDefaultIsInlined() {
    // Before the fix the generator saw the file as "PriceTag.kt" while tokens are
    // registered under its repo-relative path, so the default fell to requireSet.
    val repo = repoWith(
      "PriceTag.kt" to """
        package app.ui
        import androidx.compose.runtime.Composable
        import dev.keliver.Modifier
        private const val Accent = 42
        @Composable
        fun PriceTag(label: String, accent: Int = Accent, modifier: Modifier = Modifier) { val x = label.uppercase() }
      """.trimIndent(),
    )
    val cfg = config.copy(screensDir = "app/screens")
    val tokens = CompiledAdapters.appTokens(repo, cfg, "compiled-adapters-private-token-test")
    assertNull(CompiledAdapters.emit(repo, cfg, File(repo, "app/components"), tokens))
    val text = File(repo, "editor/src/wasmJsMain/kotlin/AppCompiled.kt").readText()
    assertTrue("call.int(\"accent\", 42)" in text, text)
    assertFalse("requireSet" in text, text)
  }

  @Test fun aBlockWithoutAPreviewBuildIsWarnedAbout() {
    assertNotNull(CompiledAdapters.configWarning(config))
    assertNull(CompiledAdapters.configWarning(config.copy(previewBuildTask = ":editor:wasmJsBrowserDistribution")))
    assertNull(CompiledAdapters.configWarning(PortalConfig()))
  }

  /** Round-2 minor 3: a link planted at the old fixed temp name must not redirect the write. */
  @Test fun aLinkPlantedAtTheOldTempNameIsNotWrittenThrough() {
    // Before the fix the relay wrote `.AppCompiled.kt.tmp` (a fixed name):
    // through a planted link the text went outside the repo and the move
    // turned the out file into a link.
    val repo = repoWith("PriceTag.kt" to badge)
    val outside = createTempDirectory("compiled-adapters-outside").toFile()
    val victim = File(outside, "victim.kt").apply { writeText("untouched\n") }
    val dir = File(repo, "editor/src/wasmJsMain/kotlin").apply { mkdirs() }
    Files.createSymbolicLink(File(dir, ".AppCompiled.kt.tmp").toPath(), victim.toPath())
    assertNull(CompiledAdapters.emit(repo, config, File(repo, "app/components"), TokenTable.EMPTY))
    assertEquals("untouched\n", victim.readText())
    val out = File(dir, "AppCompiled.kt")
    assertFalse(Files.isSymbolicLink(out.toPath()))
    assertTrue(out.readText().startsWith(CompiledAdapterGenerator.GENERATED_HEADER))
  }
}
