/*
 * spike/keliver-web portal sub-project B — the EXPORT VERIFIER.
 * Generates Exported.kt from the sample tree via exportKotlin(); compiling this
 * module then proves the generated Kotlin is valid guest source that uses the
 * keliver composables correctly. (Repurposed from the dead gate-3 screen.json tool.)
 */
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.sampleTree
import dev.keliver.portal.serializeTree
import java.io.File

private const val OUT = "/Users/sanchitwalia/AndroidStudioProjects/konduit/web-spike-guest-compiler/src/main/kotlin/Exported.kt"

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
}
