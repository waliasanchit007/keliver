package dev.keliver.portal.ingest

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
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
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiTreeChangePreprocessor
import org.jetbrains.kotlin.com.intellij.psi.impl.smartPointers.SmartPointerAnchorProvider
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.TreeCopyHandler
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * The RESIDENT headless PSI environment (spike S1: boot 357ms once, warm
 * parse ~1ms; spike S2: mutation extension points for M4 write-back).
 */
object PsiEnv {
  private val disposable = Disposer.newDisposable()

  private val env: KotlinCoreEnvironment by lazy {
    val e = KotlinCoreEnvironment.createForProduction(
      disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES,
    )
    enablePsiMutations(e.project as MockProject)
    e
  }

  val factory: KtPsiFactory by lazy { KtPsiFactory(env.project) }

  fun parse(name: String, source: String): KtFile = factory.createFile(name, source)

  /** ktlint/detekt recipe — required for PSI mutation (M4), harmless for parse. */
  private fun enablePsiMutations(project: MockProject) {
    val area = Extensions.getRootArea()
    fun ep(name: String, clazz: Class<*>) {
      if (!area.hasExtensionPoint(name)) {
        area.registerExtensionPoint(name, clazz.name, ExtensionPoint.Kind.INTERFACE)
      }
    }
    ep("org.jetbrains.kotlin.com.intellij.treeCopyHandler", TreeCopyHandler::class.java)
    ep("org.jetbrains.kotlin.com.intellij.psi.treeChangePreprocessor", PsiTreeChangePreprocessor::class.java)
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
}
