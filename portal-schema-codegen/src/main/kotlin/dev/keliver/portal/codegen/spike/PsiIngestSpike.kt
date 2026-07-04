/*
 * V2 SPIKE S1 — headless PSI ingest.
 * Proves: KtFile PSI from source in a plain JVM (no IDE), recognizer-style
 * walk WITHOUT name resolution, RawCode spans byte-exact, <500ms warm.
 * Run: ./gradlew :portal-schema-codegen:runPsiSpike
 */
package dev.keliver.portal.codegen.spike

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory

private val SAMPLE_SCREEN = """
package myapp.screens

import androidx.compose.runtime.Composable
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.layout.compose.Column
import dev.keliver.Modifier
import dev.keliver.material.compose.padding

// A human comment that must survive round trips.
@Composable
fun CheckoutScreen(b: CheckoutBindings) {
  StyledBox(cornerRadiusDp = 12, fillWidth = true) {
    Column {
      StyledText(text = b.title, fontSize = 22, bold = true)
      // inline logic the recognizer must NOT understand:
      if (b.title.length > 40) {
        StyledText(text = "long title!", fontSize = 10)
      }
      Button(
        modifier = Modifier.padding(8),
        text = "Buy now",
        onClick = b::buy,
      )
    }
  }
}

interface CheckoutBindings {
  val title: String
  fun buy()
}
""".trimIndent()

// The catalog-known names for the walk (real impl uses the generated catalog).
private val KNOWN = setOf("StyledBox", "Column", "StyledText", "Button")

private sealed interface SpikeNode
private data class Widget(
  val type: String,
  val props: List<Pair<String, String>>, // name -> expression text
  val children: List<SpikeNode>,
) : SpikeNode
private data class Raw(val text: String) : SpikeNode

private fun walkBody(expressions: List<KtExpression>): List<SpikeNode> = expressions.map { expr ->
  val call = expr as? KtCallExpression
  val name = call?.calleeExpression?.text
  if (call != null && name in KNOWN) {
    val props = call.valueArguments
      .filter { it !is KtLambdaArgument }
      .map { (it.getArgumentName()?.asName?.asString() ?: "<positional>") to (it.getArgumentExpression()?.text ?: "") }
    val childExprs = call.lambdaArguments.firstOrNull()
      ?.getLambdaExpression()?.bodyExpression?.statements ?: emptyList()
    Widget(name!!, props, walkBody(childExprs))
  } else {
    Raw(expr.text) // byte-exact span of the unrecognized statement
  }
}

private fun render(n: SpikeNode, indent: String = ""): String = when (n) {
  is Widget -> "$indent${n.type}(${n.props.joinToString { "${it.first}=${it.second}" }})" +
    if (n.children.isEmpty()) "" else "\n" + n.children.joinToString("\n") { render(it, "$indent  ") }
  is Raw -> "$indent[RAW ${n.text.lines().size} lines, ${n.text.length} chars]"
}

fun main() {
  val disposable = Disposer.newDisposable()
  val t0 = System.nanoTime()
  val env = KotlinCoreEnvironment.createForProduction(
    disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES,
  )
  val factory = KtPsiFactory(env.project)
  val tEnv = System.nanoTime()

  // Cold parse + walk.
  var file = factory.createFile("CheckoutScreen.kt", SAMPLE_SCREEN)
  val screenFn = file.declarations.filterIsInstance<KtNamedFunction>().first { it.name == "CheckoutScreen" }
  val tree = walkBody((screenFn.bodyExpression as KtBlockExpression).statements)
  val tCold = System.nanoTime()

  println("=== recognized tree ===")
  tree.forEach { println(render(it)) }

  // RawCode byte-exactness: the if-statement must round-trip verbatim.
  val raw = (((tree[0] as Widget).children[0] as Widget).children).filterIsInstance<Raw>().first()
  val expected = SAMPLE_SCREEN.substringAfter("      ").let { _ ->
    SAMPLE_SCREEN.lines().dropWhile { !it.contains("if (b.title") }.takeWhile { !it.startsWith("      Button") }
      .joinToString("\n").trimEnd()
  }
  val rawOk = raw.text.trim() == expected.trim()
  println("raw-span byte-exact: $rawOk")

  // Warm parse loop.
  val warmRuns = 100
  val tW0 = System.nanoTime()
  repeat(warmRuns) {
    file = factory.createFile("CheckoutScreen.kt", SAMPLE_SCREEN)
    val fn = file.declarations.filterIsInstance<KtNamedFunction>().first { it.name == "CheckoutScreen" }
    walkBody((fn.bodyExpression as KtBlockExpression).statements)
  }
  val tW1 = System.nanoTime()

  fun ms(a: Long, b: Long) = (b - a) / 1_000_000
  println("env boot: ${ms(t0, tEnv)}ms | cold parse+walk: ${ms(tEnv, tCold)}ms | warm avg: ${ms(tW0, tW1) / warmRuns}ms")
  println("SPIKE ${if (rawOk && ms(tW0, tW1) / warmRuns < 500) "PASS" else "FAIL"}")
  Disposer.dispose(disposable)
}
