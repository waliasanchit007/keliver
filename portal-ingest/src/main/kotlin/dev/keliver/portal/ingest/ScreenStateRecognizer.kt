package dev.keliver.portal.ingest

import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

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
 * The annotation and `NamedState` are matched by what the file's imports bind
 * them to, not by their short names: `dev.keliver.nav.ScreenState` written out,
 * imported (also under an alias, or by `import dev.keliver.nav.*`), or used in
 * package `dev.keliver.nav` itself. A `@ScreenState` another import binds
 * elsewhere is some other annotation and is ignored; one no import binds is
 * reported (the file can't compile without the import).
 *
 * The state's name and fixture set must be plain string literals (the portal
 * lists them without running code); the body is a `NamedState(...)` call
 * (also written `dev.keliver.nav.NamedState(...)`), as an expression body or a
 * single `return`; the function is top-level and takes no parameters or
 * receiver. A declaration outside that shape — a state inside an object or a
 * class included — is not a state and is reported in [ScreenStates.problems],
 * never skipped silently, as is a repeated name (the first declaration wins).
 * These functions are not `@Composable`, so the screen grammar never reads
 * them: they cost the screen no RawCode.
 */
object ScreenStateRecognizer {
  const val ANNOTATION = "ScreenState"
  const val STATE_CLASS = "NamedState"
  const val PACKAGE = "dev.keliver.nav"
  const val ANNOTATION_FQ = "$PACKAGE.$ANNOTATION"
  const val STATE_CLASS_FQ = "$PACKAGE.$STATE_CLASS"

  fun recognize(fileName: String, source: String): ScreenStates {
    val file = PsiEnv.parse(fileName, LineEndings.toLf(source))
    val scope = ImportScope.of(file)
    val states = ArrayList<ScreenStateSpec>()
    val problems = ArrayList<String>()
    // Every function in the file, in source order: a state nested in an object,
    // a class or another function is reported, not passed over.
    for (fn in file.collectDescendantsOfType<KtNamedFunction>()) {
      val fnName = fn.name ?: continue
      val ann = fn.annotationEntries.firstNotNullOfOrNull { entry ->
        when (scope.annotation(entry)) {
          Binding.Ours -> entry to null
          Binding.Unbound -> entry to "@$ANNOTATION is not imported from $PACKAGE (add `import $ANNOTATION_FQ`)"
          Binding.Elsewhere -> null
        }
      } ?: continue
      val label = if (fn.isTopLevel) fnName else "${container(fn).first}.$fnName"
      val unbound = ann.second
      if (unbound != null) {
        problems += "$label: $unbound"
        continue
      }
      if (!fn.isTopLevel) {
        problems += "$label: a state must be a top-level function; this one is inside ${container(fn).second}"
        continue
      }
      when (val r = read(fnName, ann.first, fn, scope)) {
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

  private fun read(fnName: String, ann: KtAnnotationEntry, fn: KtNamedFunction, scope: ImportScope): Read {
    val annArgs = ann.valueArgumentList?.arguments.orEmpty()
    val name = str(arg(annArgs, "name", 0))
      ?: return Read.Bad("@$ANNOTATION needs its name as a plain string literal")
    if (name.isBlank()) return Read.Bad("@$ANNOTATION name is empty")
    if (fn.valueParameters.isNotEmpty()) return Read.Bad("a state function takes no parameters")
    if (fn.receiverTypeReference != null) return Read.Bad("a state function takes no receiver")
    val body = when (val b = fn.bodyExpression) {
      is KtBlockExpression -> (b.statements.singleOrNull() as? KtReturnExpression)?.returnedExpression
      else -> b
    }
    val call = when (val c = stateCall(body, scope)) {
      is Call.Ok -> c.call
      is Call.Bad -> return Read.Bad(c.why)
    }
    val args = call.valueArguments
    val route = arg(args, "route", 0) ?: return Read.Bad("$STATE_CLASS has no route")
    val fixtures = str(arg(args, "fixtures", 1))
      ?: return Read.Bad("$STATE_CLASS fixtures must be a plain string literal")
    if (fixtures.isBlank()) return Read.Bad("$STATE_CLASS fixtures is empty")
    return Read.Ok(ScreenStateSpec(name, fnName, route.text, routeType(route), fixtures))
  }

  private sealed interface Call {
    data class Ok(val call: KtCallExpression) : Call
    data class Bad(val why: String) : Call
  }

  /** The `NamedState(...)` call [body] is, short or fully qualified, or why it isn't one. */
  private fun stateCall(body: KtExpression?, scope: ImportScope): Call {
    val shape = "the body must be a single $STATE_CLASS(route = …, fixtures = \"…\") call"
    return when (body) {
      is KtCallExpression -> {
        val callee = (body.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
          ?: return Call.Bad(shape)
        when (scope.bindingOf(callee, STATE_CLASS_FQ)) {
          Binding.Ours -> Call.Ok(body)
          Binding.Unbound ->
            if (callee == STATE_CLASS) Call.Bad("$STATE_CLASS is not imported from $PACKAGE (add `import $STATE_CLASS_FQ`)")
            else Call.Bad(shape)
          Binding.Elsewhere ->
            if (callee == STATE_CLASS) {
              Call.Bad("$STATE_CLASS here is ${scope.explicit[callee]?.let { "`$it`" } ?: "this file's own $STATE_CLASS"}, not $STATE_CLASS_FQ")
            } else {
              Call.Bad(shape)
            }
        }
      }
      is KtDotQualifiedExpression -> {
        val sel = body.selectorExpression as? KtCallExpression
        val callee = (sel?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        when {
          sel == null || callee != STATE_CLASS -> Call.Bad(shape)
          "${compact(body.receiverExpression.text)}.$callee" == STATE_CLASS_FQ -> Call.Ok(sel)
          else -> Call.Bad(
            "$STATE_CLASS is qualified as `${compact(body.receiverExpression.text)}.$callee`; " +
              "write `$STATE_CLASS_FQ(…)` or import it",
          )
        }
      }
      else -> Call.Bad(shape)
    }
  }

  /** A nested function's enclosing declaration: its name for the problem's label, and how to say it. */
  private fun container(fn: KtNamedFunction): Pair<String, String> {
    val parent = fn.getStrictParentOfType<KtDeclaration>()
    val name = (parent as? KtNamedDeclaration)?.name
    return when {
      parent is KtObjectDeclaration && parent.isCompanion() -> {
        val owner = parent.getStrictParentOfType<KtClassOrObject>()?.name
        (owner?.let { "$it.${name ?: "Companion"}" } ?: (name ?: "Companion")) to
          "the companion object of ${owner ?: "a class"}"
      }
      parent is KtObjectDeclaration -> (name ?: "object") to (if (name != null) "object $name" else "an object expression")
      parent is KtClass && parent.isInterface() -> (name ?: "interface") to "interface $name"
      parent is KtClass -> (name ?: "class") to "class $name"
      parent is KtNamedFunction -> (name ?: "function") to "function $name()"
      else -> (name ?: "declaration") to "another declaration"
    }
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

  private fun compact(text: String): String = text.filterNot(Char::isWhitespace)

  /** What a name in the file refers to, as far as its imports and package say. */
  private enum class Binding { Ours, Elsewhere, Unbound }

  /**
   * The file's imports, read the way Kotlin resolves a top-level name: an
   * explicit import (or alias) first, then the file's own package, then star
   * imports. The package's other files are not visible here, so a star import
   * of `dev.keliver.nav` counts as ours unless this file declares the name.
   */
  private class ImportScope(
    private val pkg: String,
    /** Visible name (the alias, or the last segment) -> imported FQ name. */
    val explicit: Map<String, String>,
    private val stars: Set<String>,
    /** Top-level names this file declares, which a star import doesn't override. */
    private val own: Set<String>,
  ) {
    /** Does short name [name] (a visible name, possibly an alias) bind [fq]? */
    fun bindingOf(name: String, fq: String): Binding {
      explicit[name]?.let { return if (it == fq) Binding.Ours else Binding.Elsewhere }
      val fqPackage = fq.substringBeforeLast('.')
      val simple = fq.substringAfterLast('.')
      if (name != simple) return Binding.Unbound
      if (pkg == fqPackage) return Binding.Ours
      if (name in own) return Binding.Elsewhere
      if (fqPackage in stars) return Binding.Ours
      // Another name is imported under our class's name only by aliasing it, handled above;
      // a star import of another package might bind it, but then it isn't ours either.
      return Binding.Unbound
    }

    /** How annotation [entry] binds `@ScreenState`; [Binding.Elsewhere] for any other annotation. */
    fun annotation(entry: KtAnnotationEntry): Binding {
      val written = compact(entry.typeReference?.text ?: return Binding.Elsewhere)
      if ('.' in written) return if (written == ANNOTATION_FQ) Binding.Ours else Binding.Elsewhere
      return when (val b = bindingOf(written, ANNOTATION_FQ)) {
        // Unbound is only worth reporting when it is spelled like ours.
        Binding.Unbound -> if (written == ANNOTATION) b else Binding.Elsewhere
        else -> b
      }
    }

    companion object {
      fun of(file: KtFile): ImportScope {
        val explicit = LinkedHashMap<String, String>()
        val stars = LinkedHashSet<String>()
        for (d in file.importDirectives) {
          val fq = d.importedFqName?.asString() ?: continue
          when {
            d.isAllUnder -> stars += fq
            else -> explicit[d.aliasName ?: fq.substringAfterLast('.')] = fq
          }
        }
        val own = file.declarations.mapNotNull { (it as? KtNamedDeclaration)?.name }.toSet()
        return ImportScope(file.packageFqName.asString(), explicit, stars, own)
      }
    }
  }
}
