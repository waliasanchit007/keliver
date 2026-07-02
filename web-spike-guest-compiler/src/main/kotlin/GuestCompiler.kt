/*
 * spike/keliver-web portal sub-project B — the EXPORT VERIFIER.
 * Generates Exported.kt from the sample tree via exportKotlin(); compiling this
 * module then proves the generated Kotlin is valid guest source that uses the
 * keliver composables correctly. (Repurposed from the dead gate-3 screen.json tool.)
 */
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.sampleTree
import dev.keliver.portal.serializeTree
import dev.keliver.portal.widgetSpecs
import java.io.File

private const val OUT = "/Users/sanchitwalia/AndroidStudioProjects/konduit/web-spike-guest-compiler/src/main/kotlin/Exported.kt"
private const val SINK_OUT = "/Users/sanchitwalia/AndroidStudioProjects/konduit/web-spike-guest-compiler/src/main/kotlin/ExportedKitchenSink.kt"

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
  val kitchenSink = WidgetNode(
    type = "Column",
    props = emptyMap(),
    children = widgetSpecs.map { spec -> WidgetNode(type = spec.type, props = spec.sampleProps) },
  )
  val sinkSource = exportKotlin(kitchenSink, functionName = "ExportedKitchenSink")
  File(SINK_OUT).writeText(sinkSource)
  println("kitchen sink: ${widgetSpecs.size} widgets -> $SINK_OUT")
}
