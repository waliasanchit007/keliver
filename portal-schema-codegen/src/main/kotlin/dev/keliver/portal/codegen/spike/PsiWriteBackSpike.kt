/*
 * V2 SPIKE S2 — PSI subtree write-back (the platform's #1 engineering risk).
 * Proves: replace a widget call expression via KtPsiFactory on a fresh parse,
 * serialize the file, and everything OUTSIDE the touched node stays
 * byte-identical (comments, formatting, RawCode, imports).
 * Run: ./gradlew :portal-schema-codegen:runWriteBackSpike
 */
package dev.keliver.portal.codegen.spike

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.lang.jvm.facade.JvmElementProvider
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderBase
import org.jetbrains.kotlin.com.intellij.pom.PomModel
import org.jetbrains.kotlin.com.intellij.pom.PomModelAspect
import org.jetbrains.kotlin.com.intellij.pom.PomTransaction
import org.jetbrains.kotlin.com.intellij.pom.impl.PomTransactionBase
import org.jetbrains.kotlin.com.intellij.pom.tree.TreeAspect
import org.jetbrains.kotlin.com.intellij.psi.impl.smartPointers.SmartPointerAnchorProvider
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.TreeCopyHandler
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

private val ORIGINAL = """
package myapp.screens

import androidx.compose.runtime.Composable
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.layout.compose.Column

// Header comment with   weird     spacing that must survive.
@Composable
fun CheckoutScreen(b: CheckoutBindings) {
  StyledBox(cornerRadiusDp = 12, fillWidth = true) {
    Column {
      /* block comment inside children */
      StyledText(text = b.title, fontSize = 22, bold = true)
      if (b.title.length > 40) {
        StyledText(text = "long title!",   fontSize = 10)   // odd spacing kept
      }
      Button(text = "Buy now", onClick = b::buy)
    }
  }
}

interface CheckoutBindings {
  val title: String
  fun buy()
}
""".trimIndent()

/**
 * PSI MUTATION support for a bare KotlinCoreEnvironment — the ktlint/detekt
 * recipe: register the tree-mutation extension points + a pass-through
 * PomModel (mutations run inside PomModel transactions in IntelliJ).
 */
private fun enablePsiMutations(project: MockProject) {
  val area = Extensions.getRootArea()
  fun ep(name: String, clazz: Class<*>) {
    if (!area.hasExtensionPoint(name)) {
      area.registerExtensionPoint(name, clazz.name, ExtensionPoint.Kind.INTERFACE)
    }
  }
  ep("org.jetbrains.kotlin.com.intellij.treeCopyHandler", TreeCopyHandler::class.java)
  ep("org.jetbrains.kotlin.com.intellij.psi.treeChangePreprocessor",
    org.jetbrains.kotlin.com.intellij.psi.impl.PsiTreeChangePreprocessor::class.java)
  ep("org.jetbrains.kotlin.com.intellij.smartPointerAnchorProvider", SmartPointerAnchorProvider::class.java)
  ep("org.jetbrains.kotlin.com.intellij.jvm.elementProvider", JvmElementProvider::class.java)

  val treeAspect = TreeAspect()
  val pomModel = object : UserDataHolderBase(), PomModel {
    override fun runTransaction(transaction: PomTransaction) {
      (transaction as PomTransactionBase).run()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : PomModelAspect> getModelAspect(aspect: Class<T>): T? =
      if (aspect == TreeAspect::class.java) treeAspect as T else null
  }
  project.registerService(PomModel::class.java, pomModel)
}

fun main() {
  val disposable = Disposer.newDisposable()
  val env = KotlinCoreEnvironment.createForProduction(
    disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES,
  )
  enablePsiMutations(env.project as MockProject)
  val factory = KtPsiFactory(env.project)

  // Test 1: SetProp — replace the Button call (text -> "Purchase", add enabled).
  var file = factory.createFile("CheckoutScreen.kt", ORIGINAL)
  val button = file.collectDescendantsOfType<KtCallExpression>()
    .first { it.calleeExpression?.text == "Button" }
  val buttonStart = button.textRange.startOffset
  val buttonEndFromEnd = ORIGINAL.length - button.textRange.endOffset
  val newButton = factory.createExpression("""Button(text = "Purchase", enabled = true, onClick = b::buy)""")
  button.replace(newButton)
  val after = file.text

  // Byte-identity outside the touched node: prefix and suffix must be unchanged.
  val prefixOk = after.substring(0, buttonStart) == ORIGINAL.substring(0, buttonStart)
  val suffixOk = after.substring(after.length - buttonEndFromEnd) == ORIGINAL.substring(ORIGINAL.length - buttonEndFromEnd)
  val replacedOk = after.contains("""Button(text = "Purchase", enabled = true, onClick = b::buy)""")
  println("test1 SetProp: prefix=$prefixOk suffix=$suffixOk replaced=$replacedOk")

  // Test 2: InsertNode — add a Spacer after the RAW if-statement (statement insertion).
  file = factory.createFile("CheckoutScreen.kt", ORIGINAL)
  val fn = file.declarations.filterIsInstance<KtNamedFunction>().first { it.name == "CheckoutScreen" }
  val column = file.collectDescendantsOfType<KtCallExpression>().first { it.calleeExpression?.text == "Column" }
  val columnBody = column.lambdaArguments.first().getLambdaExpression()!!.bodyExpression as KtBlockExpression
  val ifStmt = columnBody.statements.first { it.text.startsWith("if") }
  val spacer = factory.createExpression("""Spacer(height = Dp(12.0))""")
  val newline = factory.createNewLine()
  columnBody.addAfter(spacer, columnBody.addAfter(newline, ifStmt))
  val after2 = file.text
  val rawStillExact = after2.contains("""StyledText(text = "long title!",   fontSize = 10)   // odd spacing kept""")
  val commentKept = after2.contains("/* block comment inside children */") &&
    after2.contains("// Header comment with   weird     spacing that must survive.")
  val inserted = after2.contains("Spacer(height = Dp(12.0))")
  println("test2 Insert: rawExact=$rawStillExact comments=$commentKept inserted=$inserted")

  // Test 3: DeleteNode — remove the first StyledText; rest untouched.
  file = factory.createFile("CheckoutScreen.kt", ORIGINAL)
  val st = file.collectDescendantsOfType<KtCallExpression>().first { it.calleeExpression?.text == "StyledText" }
  st.delete()
  val after3 = file.text
  val deleted = !after3.contains("fontSize = 22")
  val restKept = after3.contains("""Button(text = "Buy now", onClick = b::buy)""") &&
    after3.contains("if (b.title.length > 40)")
  println("test3 Delete: deleted=$deleted restKept=$restKept")

  // Test 4: idempotent round-trip — re-parse test1 output, replace Button with ITSELF, expect byte-identical.
  val file4 = factory.createFile("CheckoutScreen.kt", after)
  val b4 = file4.collectDescendantsOfType<KtCallExpression>().first { it.calleeExpression?.text == "Button" }
  b4.replace(factory.createExpression(b4.text))
  val idempotent = file4.text == after
  println("test4 Idempotent self-replace: $idempotent")

  val pass = prefixOk && suffixOk && replacedOk && rawStillExact && commentKept && inserted &&
    deleted && restKept && idempotent
  println("SPIKE ${if (pass) "PASS" else "FAIL"}")
  Disposer.dispose(disposable)
}
