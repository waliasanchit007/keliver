@file:OptIn(KtNonPublicApi::class)

package dev.keliver.portal.ingest

import java.lang.reflect.Proxy
import org.jetbrains.kotlin.com.intellij.mock.MockComponentManager
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNonPublicApi
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtPsiMutationService
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.createExpressionByPattern

/**
 * Kotlin 2.4 moved Kotlin PSI's own mutation logic (`KtExpression.replace`, `KtElement.delete`,
 * and more) behind [KtPsiMutationService], an application service that only the IDE's Kotlin
 * plugin implements. Without one, every such call throws "Cannot mutate Kotlin PSI because
 * KtPsiMutationService is missing".
 *
 * The write-back mutates PSI headlessly, and reaches three of those operations. They are
 * implemented here as Kotlin 2.2 implemented them inline (`KtExpressionImpl.replaceExpression`,
 * `KtElementImpl.delete`, `KtBlockExpression.delete`; the IDE's `KtPsiMutationServiceImpl` does
 * the same), so the bytes written stay what they were (D4). Any other mutation fails loudly,
 * naming the operation, rather than doing something it never did before.
 */
internal object HeadlessPsiMutations {
  /** Registers the service once per (shared) application; a no-op when one is already there. */
  fun install() {
    val application = ApplicationManager.getApplication()
    if (application.getService(KtPsiMutationService::class.java) != null) return
    (application as MockComponentManager).registerService(KtPsiMutationService::class.java, service)
  }

  private val service: KtPsiMutationService = Proxy.newProxyInstance(
    KtPsiMutationService::class.java.classLoader,
    arrayOf(KtPsiMutationService::class.java),
  ) { proxy, method, args ->
    when (method.name) {
      "replaceExpression" -> {
        @Suppress("UNCHECKED_CAST")
        replaceExpression(
          expression = args[0] as KtExpression,
          newElement = args[1] as PsiElement,
          reformat = args[2] as Boolean,
          rawReplaceHandler = args[3] as (PsiElement) -> PsiElement,
        )
      }
      "deleteElement", "deleteBlockExpression" -> {
        deleteElement(args[0] as KtElement)
        null
      }
      "toString" -> "HeadlessPsiMutations"
      "hashCode" -> System.identityHashCode(proxy)
      "equals" -> proxy === args[0]
      else -> throw UnsupportedOperationException(
        "Kotlin PSI mutation '${method.name}' is not available headlessly (HeadlessPsiMutations)",
      )
    }
  } as KtPsiMutationService

  private fun replaceExpression(
    expression: KtExpression,
    newElement: PsiElement,
    reformat: Boolean,
    rawReplaceHandler: (PsiElement) -> PsiElement,
  ): PsiElement {
    val parent = expression.parent
    if (newElement is KtExpression) {
      when (parent) {
        is KtExpression, is KtValueArgument -> {
          if (KtPsiUtil.areParenthesesNecessary(newElement, expression, parent as KtElement)) {
            val factory = KtPsiFactory(expression.project)
            return rawReplaceHandler(factory.createExpressionByPattern("($0)", newElement, reformat = reformat))
          }
        }
        is KtSimpleNameStringTemplateEntry -> {
          if (newElement !is KtSimpleNameExpression && !newElement.isThisWithoutLabel()) {
            val factory = KtPsiFactory(expression.project)
            val newEntry = parent.replace(factory.createBlockStringTemplateEntry(newElement)) as KtBlockStringTemplateEntry
            return newEntry.expression!!
          }
        }
      }
    }
    return rawReplaceHandler(newElement)
  }

  private fun deleteElement(element: KtElement) {
    deleteTrailingSemicolon(element)
    element.rawDelete()
  }

  private fun deleteTrailingSemicolon(element: KtElement) {
    if (element is KtEnumEntry) return
    val sibling = PsiTreeUtil.skipSiblingsForward(element, PsiWhiteSpace::class.java, PsiComment::class.java)
    if (sibling == null || sibling.node.elementType != KtTokens.SEMICOLON) return
    val lastSiblingToDelete = PsiTreeUtil.skipSiblingsForward(sibling, PsiWhiteSpace::class.java)?.prevSibling ?: sibling
    element.parent.deleteChildRange(element.nextSibling, lastSiblingToDelete)
  }

  private fun PsiElement.isThisWithoutLabel() = this is KtThisExpression && getLabelName() == null
}
