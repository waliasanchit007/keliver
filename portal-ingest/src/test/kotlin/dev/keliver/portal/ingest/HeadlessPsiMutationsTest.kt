@file:OptIn(KtNonPublicApi::class)

package dev.keliver.portal.ingest

import org.jetbrains.kotlin.psi.KtNonPublicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiMutationService
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Kotlin 2.4 moved PSI mutation behind [KtPsiMutationService]; [HeadlessPsiMutations] supplies it
 * headlessly as Kotlin 2.2 did it inline. Each case asserts the exact resulting source text, and
 * every expectation here is Kotlin 2.2's own output: the same cases (minus the service-specific
 * one) were run against main on Kotlin 2.2 (41f95f7f5, 2026-11-10) and gave these results,
 * including the three NullPointerExceptions: the headless environment registers no
 * CodeStyleManager, and KtPsiFactory's parenthesizing and template-promoting patterns reformat
 * through it. Write-back has never reached those paths (it would have crashed on 2.2 too); they
 * are pinned here so a change shows up (docs/KNOWN_BUGS.md, U40).
 */
class HeadlessPsiMutationsTest {
  private fun parse(text: String): KtFile = PsiEnv.parse("Sample.kt", text)

  private inline fun <reified T : KtElement> KtFile.first(): T =
    PsiTreeUtil.findChildrenOfType(this, T::class.java).first()

  @Test fun replaceOperandWithLowerPrecedenceIsParenthesized() {
    val file = parse("val x = a + b\n")
    val plus = file.first<KtBinaryExpression>()
    // As on Kotlin 2.2: no CodeStyleManager headlessly (see the class comment).
    assertFailsWith<NullPointerException> {
      plus.right!!.replace(PsiEnv.factory.createExpression("c ?: d"))
    }
  }

  @Test fun replaceCallArgumentNeedsNoParentheses() {
    val file = parse("val x = f(b)\n")
    val call = file.first<KtCallExpression>()
    call.valueArguments.single().getArgumentExpression()!!.replace(PsiEnv.factory.createExpression("c ?: d"))
    assertEquals("val x = f(c ?: d)\n", file.text)
  }

  @Test fun replaceReceiverInCallArgumentIsParenthesized() {
    val file = parse("val x = f(b.g())\n")
    val receiver = file.first<KtCallExpression>().valueArguments.single().getArgumentExpression()!!
      .let { PsiTreeUtil.findChildOfType(it, org.jetbrains.kotlin.psi.KtNameReferenceExpression::class.java)!! }
    // As on Kotlin 2.2: no CodeStyleManager headlessly (see the class comment).
    assertFailsWith<NullPointerException> {
      receiver.replace(PsiEnv.factory.createExpression("c ?: d"))
    }
  }

  @Test fun deleteStatementTakesItsTrailingSemicolon() {
    val file = parse("fun f() {\n  Foo();\n  Bar()\n}\n")
    file.first<KtCallExpression>().delete()
    assertEquals("fun f() {\n  Bar()\n}\n", file.text)
  }

  @Test fun deleteStatementWithoutSemicolonLeavesTheRest() {
    val file = parse("fun f() {\n  Foo()\n  Bar()\n}\n")
    file.first<KtCallExpression>().delete()
    assertEquals("fun f() {\n  Bar()\n}\n", file.text)
  }

  @Test fun replaceSimpleTemplateEntryPromotesToBlock() {
    val file = parse("val s = \"hi \$x!\"\n")
    val entry = PsiTreeUtil.findChildOfType(file, KtSimpleNameStringTemplateEntry::class.java)!!
    // As on Kotlin 2.2: no CodeStyleManager headlessly (see the class comment).
    assertFailsWith<NullPointerException> {
      entry.expression!!.replace(PsiEnv.factory.createExpression("a.b"))
    }
  }

  @Test fun replaceSimpleTemplateEntryWithANameStaysSimple() {
    val file = parse("val s = \"hi \$x!\"\n")
    val entry = PsiTreeUtil.findChildOfType(file, KtSimpleNameStringTemplateEntry::class.java)!!
    entry.expression!!.replace(PsiEnv.factory.createExpression("y"))
    assertEquals("val s = \"hi \$y!\"\n", file.text)
    assertTrue(PsiTreeUtil.findChildOfType(file, KtStringTemplateExpression::class.java) != null)
  }

  @Test fun deleteBlockExpression() {
    val file = parse("fun f() {\n  Foo()\n}\n")
    file.first<KtNamedFunction>().bodyBlockExpression!!.delete()
    assertEquals("fun f()\n", file.text)
  }

  @Test fun unimplementedMutationThrowsNamingItself() {
    // Deleting an enum entry is one of the mutations the write-back never uses.
    val file = parse("enum class E { A, B }\n")
    val entry = file.first<KtEnumEntry>()
    val service = ApplicationManager.getApplication().getService(KtPsiMutationService::class.java)
    val thrown = assertFailsWith<UnsupportedOperationException> { service.deleteEnumEntry(entry) }
    assertTrue("deleteEnumEntry" in thrown.message.orEmpty(), "the message names the operation: ${thrown.message}")
    assertEquals("enum class E { A, B }\n", file.text, "nothing was changed")
  }
}
