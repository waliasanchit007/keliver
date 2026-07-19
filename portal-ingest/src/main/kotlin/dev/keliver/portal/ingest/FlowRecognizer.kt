package dev.keliver.portal.ingest

import dev.keliver.portal.flow.FlowSpec
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * #13 F1 — PSI-parse a flows/ declaration back into its [FlowSpec]. Recognizes
 * exactly the `flow{}` DSL shape (design §0):
 *
 * ```kotlin
 * val FieldNotesFlow = flow("FieldNotes", start = "feed") {
 *   route("openNote", to = "detail")
 * }
 * ```
 *
 * The SAME source compiles into the app's guest (runtime nav), so — like the
 * screen grammar — the .kt file is the single source of truth; nothing here is
 * a second copy to drift. Non-matching files return null (not an error): a
 * flows/ dir may hold hand-owned helpers too.
 */
object FlowRecognizer {
  fun recognize(fileName: String, source: String): FlowSpec? {
    val file = PsiEnv.parse(fileName, source)
    for (prop in file.declarations.filterIsInstance<KtProperty>()) {
      val call = prop.initializer as? KtCallExpression ?: continue
      if (callee(call) != "flow") continue
      val name = str(arg(call, "name", 0)) ?: continue
      val start = str(arg(call, "start", 1)) ?: continue
      val routes = LinkedHashMap<String, String>()
      val body = call.lambdaArguments.firstOrNull()
        ?.getLambdaExpression()?.bodyExpression
      body?.statements?.forEach { st ->
        val rc = st as? KtCallExpression ?: return@forEach
        if (callee(rc) != "route") return@forEach
        val key = str(arg(rc, "key", 0)) ?: return@forEach
        val to = str(arg(rc, "to", 1)) ?: return@forEach
        routes[key] = to
      }
      return FlowSpec(name, start, routes)
    }
    return null
  }

  private fun callee(call: KtCallExpression): String? =
    (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

  /** The argument named [named], else the [position]-th positional (non-lambda) argument. */
  private fun arg(call: KtCallExpression, named: String, position: Int): KtExpression? {
    val args = call.valueArgumentList?.arguments ?: return null
    args.firstOrNull { it.getArgumentName()?.asName?.asString() == named }
      ?.let { return it.getArgumentExpression() }
    return args.filter { it.getArgumentName() == null }
      .getOrNull(position)?.getArgumentExpression()
  }

  /** A plain (no-interpolation) string literal's value, else null. */
  private fun str(e: KtExpression?): String? {
    val t = e as? KtStringTemplateExpression ?: return null
    if (t.entries.any { it !is KtLiteralStringTemplateEntry }) return null
    return t.entries.joinToString("") { it.text }
  }
}
