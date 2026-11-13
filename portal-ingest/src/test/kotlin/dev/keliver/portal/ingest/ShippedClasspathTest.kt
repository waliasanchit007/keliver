package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ED0 spike (docs/ED0_SPIKE.md), H1 of its review: portal-ingest takes the prototype
 * `keliver-compose-values` `compileOnly`, so a shipped consumer (portal-relay, the tools zip) never
 * carries it. This runs ingest and write-back with the flag off in a class loader that has exactly
 * what such a consumer has — portal-ingest's main output and its runtime classpath, nothing of this
 * test's classpath — and shows that path loads none of the prototype's classes.
 */
class ShippedClasspathTest {
  private fun paths(property: String): List<File> =
    System.getProperty(property)!!.split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)

  private fun shippedLoader(): URLClassLoader {
    val shipped = paths("dev.keliver.portal.ed0.shippedClasspath")
    assertTrue(shipped.none { "keliver-compose-values" in it.path }, "the runtime classpath has no keliver-compose-values: $shipped")
    // The probe's own classes only (this package's test output), never the test runtime classpath.
    val urls = (shipped + paths("dev.keliver.portal.ed0.probeClasses")).map { it.toURI().toURL() }
    return URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader())
  }

  @Test fun theFlagOffPathRunsWithoutTheComposePrototype() {
    shippedLoader().use { loader ->
      assertFailsWith<ClassNotFoundException> { loader.loadClass("dev.keliver.compose.ui.graphics.Color") }
      val probe = loader.loadClass(ShippedProbe::class.java.name)
      assertEquals(ShippedProbe::class.java.name, probe.name)
      val out = probe.getMethod("run", String::class.java).invoke(null, ComposeFixtures.sampleCard) as String
      assertEquals("ok", out)
    }
  }

  @Test fun theFlagOnWithoutThePrototypeFailsWithItsReason() {
    shippedLoader().use { loader ->
      val probe = loader.loadClass(ShippedProbe::class.java.name)
      val why = probe.getMethod("flagOn").invoke(null) as String
      assertTrue("keliver-compose-values is not on the classpath" in why, why)
    }
  }
}

/** Runs in [ShippedClasspathTest]'s class loader: portal-ingest's public API only, the ED0 flag off. */
object ShippedProbe {
  private val legacy = """
    import androidx.compose.runtime.Composable
    import dev.keliver.material.compose.StyledText
    import dev.keliver.layout.compose.Column

    @Composable
    fun PortalScreen(b: PortalScreenBindings) {
      Column {
        StyledText(text = b.title, fontSize = 22)
      }
    }

    interface PortalScreenBindings {
      val title: String
    }
  """.trimIndent()

  /** Ingests a legacy screen and Gate A's Compose-shaped sample, and writes an edit back: "ok", or what failed. */
  @JvmStatic fun run(composeShaped: String): String {
    check(ComposeIngest.catalog() == null) { "the flag is off" }
    val rec = Recognizer.recognize("PortalScreen.kt", legacy)!!
    val doc = UiDocument("main", rec.root, rec.contract, version = 0, nextHandle = 0)
    fun find(n: DocNode): DocNode.Widget? = (n as? DocNode.Widget)?.let { w -> if (w.type == "StyledText") w else w.children.firstNotNullOfOrNull(::find) }
    val text = find(doc.root)!!.handle
    val merged = WriteBack.merge(legacy, doc.apply(DocOp.SetProp(text, "fontSize", lit(30))).doc)
    check(merged == legacy.replace("fontSize = 22", "fontSize = 30")) { "write-back: $merged" }
    // Off, Compose-shaped code is today's grammar (RawCode where it is not grammar), not Compose nodes.
    val sample = Recognizer.recognize("SampleCard.kt", composeShaped, asComponent = true)!!
    check(sample.rawReasons.isNotEmpty()) { "the sample is not today's grammar" }
    return "ok"
  }

  /** Turns the flag on for this thread: the reason it fails without the prototype. */
  @JvmStatic fun flagOn(): String = try {
    ComposeIngest.with(ComposeCatalog(emptyList(), emptyList())) { ComposeIngest.catalog() }
    "no failure"
  } catch (e: IllegalStateException) {
    e.message.orEmpty()
  }
}
