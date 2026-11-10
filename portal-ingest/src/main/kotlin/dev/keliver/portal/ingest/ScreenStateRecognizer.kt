package dev.keliver.portal.ingest

import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * One `@ScreenState` declaration of a screen file (ADOPTION_PLAN item 5, S1).
 *
 * [route] is the route expression's source text (D8: routes are data and are
 * edited in Kotlin, so the portal shows it and never rewrites it);
 * [routeType] is the route's class as written (`ItemDetail` for
 * `ItemDetail(id = "x")`, `Route.Home` for `Route.Home`), null when the
 * expression is not a constructor call or a name.
 */
data class ScreenStateSpec(
  val name: String,
  val function: String,
  val route: String,
  val routeType: String?,
  val fixtures: String,
)

/** The states a screen file declares, and why any `@ScreenState` function was not read. */
data class ScreenStates(
  val states: List<ScreenStateSpec>,
  val problems: List<String> = emptyList(),
)

/**
 * Reads the `@ScreenState` functions of a screen file:
 *
 * ```kotlin
 * @ScreenState("review")
 * fun review() = NamedState(route = ItemDetail(id = "sample"), fixtures = "review")
 * ```
 *
 * The state's name and fixture set must be plain string literals (the portal
 * lists them without running code); the body is a `NamedState(...)` call, as an
 * expression body or a single `return`. A declaration outside that shape is not
 * a state and is reported in [ScreenStates.problems], as is a repeated name
 * (the first declaration wins). These functions are not `@Composable`, so the
 * screen grammar never reads them: they cost the screen no RawCode.
 */
object ScreenStateRecognizer {
  const val ANNOTATION = "ScreenState"
  const val STATE_CLASS = "NamedState"

  fun recognize(fileName: String, source: String): ScreenStates {
    val file = PsiEnv.parse(fileName, LineEndings.toLf(source))
    val states = ArrayList<ScreenStateSpec>()
    val problems = ArrayList<String>()
    for (fn in file.declarations.filterIsInstance<KtNamedFunction>()) {
      val ann = fn.annotationEntries.firstOrNull { it.shortName?.asString() == ANNOTATION } ?: continue
      val fnName = fn.name ?: continue
      when (val r = read(fnName, ann, fn)) {
        is Read.Ok -> if (states.any { it.name == r.spec.name }) {
          problems += "$fnName: state '${r.spec.name}' is already declared by ${states.first { it.name == r.spec.name }.function}()"
        } else {
          states += r.spec
        }
        is Read.Bad -> problems += "$fnName: ${r.why}"
      }
    }
    return ScreenStates(states, problems)
  }

  private sealed interface Read {
    data class Ok(val spec: ScreenStateSpec) : Read
    data class Bad(val why: String) : Read
  }

  private fun read(fnName: String, ann: KtAnnotationEntry, fn: KtNamedFunction): Read {
    val annArgs = ann.valueArgumentList?.arguments.orEmpty()
    val name = str(arg(annArgs, "name", 0))
      ?: return Read.Bad("@$ANNOTATION needs its name as a plain string literal")
    if (name.isBlank()) return Read.Bad("@$ANNOTATION name is empty")
    if (fn.valueParameters.isNotEmpty()) return Read.Bad("a state function takes no parameters")
    val body = when (val b = fn.bodyExpression) {
      is KtBlockExpression -> (b.statements.singleOrNull() as? KtReturnExpression)?.returnedExpression
      else -> b
    }
    val call = body as? KtCallExpression
    if (call == null || (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() != STATE_CLASS) {
      return Read.Bad("the body must be a single $STATE_CLASS(route = …, fixtures = \"…\") call")
    }
    val args = call.valueArguments
    val route = arg(args, "route", 0) ?: return Read.Bad("$STATE_CLASS has no route")
    val fixtures = str(arg(args, "fixtures", 1))
      ?: return Read.Bad("$STATE_CLASS fixtures must be a plain string literal")
    if (fixtures.isBlank()) return Read.Bad("$STATE_CLASS fixtures is empty")
    return Read.Ok(ScreenStateSpec(name, fnName, route.text, routeType(route), fixtures))
  }

  private fun routeType(e: KtExpression): String? = when (e) {
    is KtCallExpression -> e.calleeExpression?.text
    is KtNameReferenceExpression -> e.getReferencedName()
    is KtDotQualifiedExpression -> when (val sel = e.selectorExpression) {
      is KtCallExpression -> "${e.receiverExpression.text}.${sel.calleeExpression?.text}"
      is KtNameReferenceExpression -> e.text
      else -> null
    }
    else -> null
  }

  /** The argument named [named], else the [position]-th positional argument. */
  private fun arg(args: List<KtValueArgument>, named: String, position: Int): KtExpression? {
    args.firstOrNull { it.getArgumentName()?.asName?.asString() == named }
      ?.let { return it.getArgumentExpression() }
    return args.filter { it.getArgumentName() == null }.getOrNull(position)?.getArgumentExpression()
  }

  /** A plain (no-interpolation, no-escape) string literal's value, else null. */
  private fun str(e: KtExpression?): String? {
    val t = e as? KtStringTemplateExpression ?: return null
    if (t.entries.any { it !is KtLiteralStringTemplateEntry }) return null
    return t.entries.joinToString("") { it.text }
  }
}
