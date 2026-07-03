/*
 * spike/keliver-web portal sub-project B — the EXPORT VERIFIER.
 * Generates Exported.kt from the sample tree via exportKotlin(); compiling this
 * module then proves the generated Kotlin is valid guest source that uses the
 * keliver composables correctly. (Repurposed from the dead gate-3 screen.json tool.)
 */
import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.collectContract
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.sampleTree
import dev.keliver.portal.serializeTree
import dev.keliver.portal.widgetSpecs
import java.io.File

private const val OUT = "/Users/sanchitwalia/AndroidStudioProjects/konduit/web-spike-guest-compiler/src/main/kotlin/Exported.kt"
private const val SINK_OUT = "/Users/sanchitwalia/AndroidStudioProjects/konduit/web-spike-guest-compiler/src/main/kotlin/ExportedKitchenSink.kt"
private const val BOUND_OUT = "/Users/sanchitwalia/AndroidStudioProjects/konduit/web-spike-guest-compiler/src/main/kotlin/ExportedBound.kt"
private const val BOUND_IMPL_OUT = "/Users/sanchitwalia/AndroidStudioProjects/konduit/web-spike-guest-compiler/src/main/kotlin/ExportedBoundImpl.kt"

fun main() {
  val original = sampleTree(3)
  val source = exportKotlin(original)
  File(OUT).writeText(source)
  println("export: wrote ${source.length} chars -> $OUT")

  // M2 layer 1: verify the tree survives a serialize -> deserialize round-trip.
  // exportKotlin is a deterministic function of the tree, so equal exports prove
  // the structure + props + their exact Kotlin types were preserved.
  val json = serializeTree(original)
  val roundTripped = deserializeTree(json)
  val ok = exportKotlin(roundTripped) == source
  println("serialize round-trip: ${if (ok) "OK" else "MISMATCH"}  (${json.length} chars json)")
  check(ok) { "tree serialization round-trip mismatch" }

  // P1 kitchen sink: one instance of EVERY generated-catalog widget with its
  // sampleProps — compiling this proves every generated exporter branch emits
  // valid Kotlin against the real keliver composable signatures.
  // The first child also carries modifier props ("mod.*"), proving the exported
  // Modifier chain compiles against the generated extensions.
  val kitchenSink = WidgetNode(
    type = "Column",
    props = emptyMap(),
    children = widgetSpecs.mapIndexed { i, spec ->
      val mods = if (i == 0) {
        mapOf("mod.Padding.allDp" to 8, "mod.CornerRadius.radiusDp" to 4, "mod.AnimateContentSize" to true)
      } else {
        emptyMap()
      }
      WidgetNode(type = spec.type, props = spec.sampleProps + mods)
    },
  )
  val sinkSource = exportKotlin(kitchenSink, functionName = "ExportedKitchenSink")
  File(SINK_OUT).writeText(sinkSource)
  println("kitchen sink: ${widgetSpecs.size} widgets -> $SINK_OUT")

  // P3: the BINDINGS round-trip proof. A screen with a bound field + a named
  // action exports to Screen(b: Bindings) + the interface; the hand-written
  // mock impl below compiles against it — the contract boundary holds.
  val boundTree = WidgetNode(
    type = "Column",
    props = emptyMap(),
    children = listOf(
      WidgetNode("StyledText", mapOf("text" to Bind("title"), "fontSize" to 22, "bold" to true)),
      WidgetNode("Text", mapOf("text" to Bind("subtitle"))),
      WidgetNode("Button", mapOf("text" to "Buy now", "onClick" to Action("buyTapped"))),
    ),
  )
  // Wire round-trip must preserve Bind/Action exactly.
  val boundBack = deserializeTree(serializeTree(boundTree))
  check(exportKotlin(boundBack, "BoundScreen") == exportKotlin(boundTree, "BoundScreen")) {
    "Bind/Action serialization round-trip mismatch"
  }
  val contract = collectContract(boundTree)
  println("bound contract: fields=${contract.fields} actions=${contract.actions}")
  File(BOUND_OUT).writeText(exportKotlin(boundTree, functionName = "BoundScreen"))
  File(BOUND_IMPL_OUT).writeText(
    """
    |import androidx.compose.runtime.Composable
    |
    |/** Hand-written side of the round-trip boundary — codegen never touches this. */
    |class MockBoundBindings : BoundScreenBindings {
    |  override val title: String = "Mock title"
    |  override val subtitle: String = "Mock subtitle"
    |  override fun buyTapped() { println("buyTapped") }
    |}
    |
    |@Composable
    |fun BoundScreenPreview() {
    |  BoundScreen(MockBoundBindings())
    |}
    |
    """.trimMargin(),
  )
  println("bound screen + mock impl -> $BOUND_OUT / $BOUND_IMPL_OUT")
}
