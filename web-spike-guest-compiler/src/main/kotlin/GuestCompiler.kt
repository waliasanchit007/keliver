/*
 * spike/keliver-web portal sub-project B — the EXPORT VERIFIER.
 * Generates Exported.kt from the sample tree via exportKotlin(); compiling this
 * module then proves the generated Kotlin is valid guest source that uses the
 * keliver composables correctly. (Repurposed from the dead gate-3 screen.json tool.)
 */
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.sampleTree
import java.io.File

private const val OUT = "/Users/sanchitwalia/AndroidStudioProjects/konduit/web-spike-guest-compiler/src/main/kotlin/Exported.kt"

fun main() {
  val source = exportKotlin(sampleTree(3))
  File(OUT).writeText(source)
  println("export: wrote ${source.length} chars -> $OUT")
}
