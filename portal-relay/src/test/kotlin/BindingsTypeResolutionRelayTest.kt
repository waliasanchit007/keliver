import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.OpBatch
import dev.keliver.portal.document.OpEnvelope
import dev.keliver.portal.document.PropValue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K4 final review: the screen's Bindings interface is the one its
 * parameter's type names as Kotlin reads it — through a same-file typealias
 * (chained too), past an annotation or `?`, and into a nested declaration —
 * not the parameter's text. Otherwise a new binding is written as
 * `b.subtitle` while `val subtitle` is never declared (the guest stops
 * compiling) and the contract loses its fields and actions. Each file also
 * declares `AOtherBindings` first, so the old first-`…Bindings` rule and an
 * ambiguous fallback cannot be what passes.
 */
class BindingsTypeResolutionRelayTest {
  private val decoy = "interface AOtherBindings {\n  val name: Double\n  fun onOther()\n}\n"
  private val bindings = "interface HomeScreenBindings {\n  val name: String\n  fun onTap()\n}\n"

  private fun screen(param: String, tail: String) = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: $param) {
      Column {
        StyledText(text = "x")
      }
    }
  """.trimIndent() + "\n\n" + decoy + "\n" + tail

  private class Fixture(val file: File, val svc: DocumentService)

  private fun fixture(text: String): Fixture {
    val dir = Files.createTempDirectory("k4-bindings-type").toFile().apply { deleteOnExit() }
    val file = File(dir, "home.kt").apply { writeText(text) }
    return Fixture(file, service(file))
  }

  private fun service(file: File) = DocumentService.fromFileOrTree(
    screenKey = "k4-bindings-type-${System.nanoTime()}/home", treeJson = null, onProjected = {},
    kotlinFile = file, functionName = "HomeScreen", packageName = "app.screens",
  )

  private fun DocumentService.batch(vararg ops: DocOp) = OpBatch(doc.version, OpEnvelope("t", 0L), ops.toList())

  private fun awaitStatus(svc: DocumentService, version: Long): DocumentService.WriteBackStatus {
    val deadline = System.currentTimeMillis() + 30_000
    while (System.currentTimeMillis() < deadline) {
      svc.writeBack?.takeIf { it.version == version }?.let { return it }
      Thread.sleep(25)
    }
    return assertNotNull(svc.writeBack?.takeIf { it.version == version }, "write-back of v$version reported")
  }

  /**
   * The contract is [iface]'s (`name: String`, `onTap`), read whole; a new
   * `Bind("subtitle")` is written as `b.subtitle` AND declared in [iface]'s
   * body (between [ifaceHeader] and its closing brace), never in the decoy;
   * and the written file reads back with `subtitle` in its contract.
   */
  private fun assertResolved(param: String, tail: String, ifaceHeader: String = "interface HomeScreenBindings {") {
    val f = fixture(screen(param, tail))
    val contract = f.svc.doc.contract
    assertEquals(mapOf("name" to "String"), contract.fields, param)
    assertEquals(listOf("onTap"), contract.actions, param)
    assertNull(contract.unlistedFieldsUnknown, param)

    val text = (f.svc.doc.root as DocNode.Widget).children[0].handle
    val ack = f.svc.submit(f.svc.batch(DocOp.SetProp(text, "text", PropValue.Bind("subtitle"))))
    assertTrue(ack.ok, "$param: ${ack.error}")
    val status = awaitStatus(f.svc, ack.version)
    assertTrue(status.written, "$param: ${status.json()}")
    val written = f.file.readText()
    assertTrue("text = b.subtitle" in written, written)
    val body = written.substringAfter(ifaceHeader).substringBefore("\n}")
    assertTrue("val subtitle: String" in body, "$param: subtitle not declared in the screen's interface:\n$written")
    assertTrue("subtitle" !in written.substringAfter("interface AOtherBindings").substringBefore("}"), written)
    // Re-read: the file is a screen whose contract declares the new field.
    val reread = service(f.file).doc.contract
    assertEquals("String", reread.fields["subtitle"], "$param: $reread")
    assertEquals(listOf("onTap"), reread.actions, param)
  }

  @Test fun aSameFileTypealias() = assertResolved("HB", "typealias HB = HomeScreenBindings\n\n$bindings")

  @Test fun aChainedTypealias() = assertResolved("HB", "typealias HB = HB2\ntypealias HB2 = HomeScreenBindings?\n\n$bindings")

  @Test fun anAnnotatedParameterType() = assertResolved("@Suppress(\"x\") HomeScreenBindings", bindings)

  @Test fun aNullableAnnotatedParameterType() = assertResolved("@Suppress(\"x\") HomeScreenBindings?", bindings)

  @Test fun aNestedInterface() = assertResolved(
    "Screens.HomeScreenBindings",
    "object Screens {\n  interface HomeScreenBindings {\n    val name: String\n    fun onTap()\n  }\n}\n",
    ifaceHeader = "  interface HomeScreenBindings {",
  )
}
