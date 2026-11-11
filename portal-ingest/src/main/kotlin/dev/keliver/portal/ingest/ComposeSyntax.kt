package dev.keliver.portal.ingest

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * ED0 spike (docs/ED0_SPIKE.md): reading Compose-shaped calls against the generated catalog.
 *
 *  - **Resolution by import.** A callee is the catalog's widget only when the file's imports make
 *    its simple name that guest declaration (an explicit import, an alias, or a star import of
 *    its package), so a legacy `Text` and `dev.keliver.compose.material3.Text` live in one project.
 *    Where that is not certain (two stars, a same-file declaration, Compose's own API), the call
 *    is RawCode with the reason ([ComposeContext.callee]).
 *  - **Overloads** are chosen as Kotlin chooses them, closely enough for screen code: arguments map
 *    to parameters by position, name and trailing lambda; a candidate needs every required one;
 *    each argument must read as its parameter's kind; of several that fit, the one candidate that
 *    uses no default value wins, and any other tie is ambiguous (RawCode, as the compiler refuses it).
 *  - **Modifier chains** are ordered lists of elements, repeats allowed: `Modifier.a(…).b(…)`.
 *
 * The document keeps a Compose node in today's types: its type is `<guest fq>#<tag>`; its props are
 * its arguments by parameter name; its modifier map holds, per element, `"<i>:<name>#<tag>"` (the
 * element, in chain order) and `"<i>:<name>#<tag>.<param>"` (each argument it passes); a widget
 * with one required slot keeps the slot's statements as its children, any other has one
 * `slot:<name>` node per slot it passes.
 */
internal class ComposeContext(val catalog: ComposeCatalog, file: KtFile) {
  /** Simple (or alias) name → fully qualified name, from the file's explicit imports. */
  private val explicit = HashMap<String, String>()
  private val stars = ArrayList<String>()

  /** The file's top-level declarations by name: they shadow a star import (and clash with an explicit one). */
  private val declared: Set<String> = file.declarations.mapNotNullTo(HashSet()) { (it as? KtNamedDeclaration)?.name }

  init {
    for (d in file.importDirectives) {
      val fq = d.importedFqName?.asString() ?: continue
      if (d.isAllUnder) stars += fq else explicit[d.aliasName ?: fq.substringAfterLast('.')] = fq
    }
  }

  /** Whether the file imports any guest name (`dev.keliver.compose.*`): only such a file is read the Compose way. */
  private val importsGuest: Boolean = explicit.values.any(::isGuest) || stars.any { it == GUEST_ROOT || isGuest(it) }

  /** Whether the file declares a top-level [name]. */
  fun declares(name: String): Boolean = name in declared

  /** How a call to [simple] reads in this file. */
  sealed interface Callee {
    /** A generated widget: [fq] has overloads in the catalog. */
    class Widget(val fq: String) : Callee

    /** Not read: [why] is the RawCode reason. */
    class Raw(val why: String) : Callee
  }

  /**
   * The callee [simple] names, as Kotlin resolves it closely enough for screen code, or null when
   * today's grammar reads it (by its simple name, as it always has). In a file that imports a guest
   * name, a call is RawCode, never a guess, when:
   *  - it is shadowed by (or clashes with) a top-level declaration of the same file;
   *  - two star imports both give a generated widget of that name (the compiler refuses it);
   *  - an explicit import makes it a guest name the catalog does not generate, or Compose's own
   *    API (`androidx.compose.material3.Card`), which is neither a generated nor a legacy widget;
   *  - no explicit import names it and a guest star import may (a guest declaration the catalog
   *    does not generate), unless it is the file's own or a project component ([components]).
   * A file that imports no guest name, an explicit import of a legacy or app name, and anything
   * else keep today's reading.
   */
  fun callee(simple: String, components: Set<String>): Callee? {
    if (!importsGuest) return null
    val shadowed = "`$simple(…)` is declared in this file too, so it may not be the generated widget"
    explicit[simple]?.let { fq ->
      return when {
        catalog.widgetOverloads(fq).isNotEmpty() -> if (declares(simple)) Callee.Raw(shadowed) else Callee.Widget(fq)
        isGuest(fq) -> Callee.Raw("`$simple(…)` is `$fq`, which the generated catalog does not include")
        isComposeOwn(fq) -> Callee.Raw("`$simple(…)` is `$fq`, Compose's own API: neither a generated nor a legacy widget")
        else -> null
      }
    }
    val generated = stars.map { "$it.$simple" }.filter { catalog.widgetOverloads(it).isNotEmpty() }
    if (generated.size > 1) {
      return Callee.Raw("`$simple(…)` is ambiguous between ${generated.joinToString { "`$it`" }} (two star imports)")
    }
    generated.singleOrNull()?.let { return if (declares(simple)) Callee.Raw(shadowed) else Callee.Widget(it) }
    if (declares(simple) || simple in components) return null
    val guestStars = stars.filter { it == GUEST_ROOT || isGuest(it) }
    if (guestStars.isNotEmpty()) {
      return Callee.Raw(
        "`$simple(…)` is not imported by name, and ${guestStars.joinToString { "`$it.*`" }} may give a guest declaration the generated catalog does not include",
      )
    }
    return null
  }

  private fun isGuest(fq: String): Boolean = fq.startsWith("$GUEST_ROOT.")

  private fun isComposeOwn(fq: String): Boolean =
    (fq.startsWith("androidx.compose.") && !fq.startsWith("androidx.compose.runtime.")) || fq.startsWith("org.jetbrains.compose.")

  private companion object {
    const val GUEST_ROOT = "dev.keliver.compose"
  }

  /** What [simple] names in this file among [known] fully qualified names (explicit imports first). */
  fun resolve(simple: String, known: (String) -> Boolean): String? {
    explicit[simple]?.let { return it.takeIf(known) }
    return stars.map { "$it.$simple" }.filter(known).singleOrNull()
  }

  /** Whether the file imports the top-level modifier [entry] under its name (or a star of its package). */
  fun imports(entry: ComposeCatalog.Entry): Boolean = resolve(entry.name) { it == entry.guest } != null

  /** The receiver scope of the content a node of [parentType] holds (`…RowScope`), or null. */
  fun scopeOf(parentType: String?): String? {
    parentType ?: return null
    val slot = parentType.substringAfter('/', "")
    val entry = catalog.widget(parentType.substringBefore('/')) ?: return null
    return if (slot.isEmpty()) entry.singleContentSlot?.scope else entry.param(slot)?.scope
  }
}

internal object ComposeSyntax {
  /** A call's arguments bound to one overload's parameters. */
  class Binding(val entry: ComposeCatalog.Entry, val args: Map<String, KtValueArgument>) {
    /** Whether the call leaves any parameter at its default (Kotlin prefers the one overload that does not). */
    val usesDefaults: Boolean get() = entry.params.any { it.name !in args && !it.required }
  }

  /** A binding, or why the call does not bind. */
  sealed interface Bound {
    class Ok(val binding: Binding) : Bound
    class No(val why: String) : Bound
  }

  /**
   * Binds [call] to one of [overloads]. [fits] says whether an argument reads as its parameter
   * (null = it does; else why not), so an overload whose kinds do not fit drops out.
   */
  fun bind(
    call: KtCallExpression,
    overloads: List<ComposeCatalog.Entry>,
    fits: (ComposeCatalog.Param, KtValueArgument) -> String?,
  ): Bound {
    val shown = call.calleeExpression?.text ?: "?"
    val reasons = mutableListOf<String>()
    val ok = overloads.mapNotNull { e ->
      when (val m = map(call, e, fits)) {
        is Bound.Ok -> m.binding
        is Bound.No -> null.also { reasons += m.why }
      }
    }
    if (ok.isEmpty()) {
      return Bound.No(
        if (overloads.size == 1) "`$shown(…)`: ${reasons.single()}"
        else "`$shown(…)` matches no generated overload (${reasons.distinct().joinToString("; ")})",
      )
    }
    if (ok.size == 1) return Bound.Ok(ok.single())
    // Kotlin's last tie-break is yes/no, not a count: a candidate that uses no default value beats
    // ones that use some. Any other tie (two without defaults, or every candidate using defaults —
    // `Modifier.padding()`) is an ambiguity the compiler refuses.
    val withoutDefaults = ok.filter { !it.usesDefaults }
    if (withoutDefaults.size == 1) return Bound.Ok(withoutDefaults.single())
    val tied = withoutDefaults.ifEmpty { ok }
    return Bound.No("`$shown(…)` is ambiguous between overloads ${tied.joinToString { "#${it.entry.tag}" }}")
  }

  /**
   * Whether [call] (one with no value arguments, the only case decided without reading kinds) binds
   * to exactly [entry] among [overloads] — what write-back checks before it leaves a call empty.
   */
  fun emptyCallBindsTo(call: KtCallExpression, overloads: List<ComposeCatalog.Entry>, entry: ComposeCatalog.Entry): String? {
    when (val b = bind(call, overloads) { _, _ -> null }) {
      is Bound.Ok -> if (b.binding.entry.tag == entry.tag) return null
      is Bound.No -> return b.why
    }
    return "`${call.text}` would bind to another overload than #${entry.tag}"
  }

  private fun map(
    call: KtCallExpression,
    e: ComposeCatalog.Entry,
    fits: (ComposeCatalog.Param, KtValueArgument) -> String?,
  ): Bound {
    val params = e.params
    val out = LinkedHashMap<String, KtValueArgument>()
    var position = 0
    for (arg in call.valueArguments) {
      val p = when {
        arg is KtLambdaArgument -> params.lastOrNull()
          ?.takeIf { it.kind == "slot" || it.kind == "event" }
          ?: return Bound.No("overload #${e.tag} takes no trailing lambda")
        arg.getArgumentName() != null -> {
          val n = arg.getArgumentName()!!.asName.asString()
          params.firstOrNull { it.name == n } ?: return Bound.No("overload #${e.tag} has no parameter `$n`")
        }
        else -> params.getOrNull(position++) ?: return Bound.No("overload #${e.tag} takes ${params.size} arguments")
      }
      if (p.name in out) return Bound.No("`${p.name}` is passed twice for overload #${e.tag}")
      if (arg.isSpread) return Bound.No("a spread argument is not grammar")
      if (p.kind == "unsupported") return Bound.No("`${p.name}` cannot cross to the host in the generated API")
      fits(p, arg)?.let { return Bound.No("`${p.name}`: $it") }
      out[p.name] = arg
    }
    params.firstOrNull { it.required && it.name !in out }?.let { return Bound.No("overload #${e.tag} needs `${it.name}`") }
    return Bound.Ok(Binding(e, out))
  }

  /** The lambda a slot or event argument passes (trailing or named), or null. */
  fun lambdaOf(arg: KtValueArgument): KtLambdaExpression? =
    (arg as? KtLambdaArgument)?.getLambdaExpression() ?: unparen(arg.getArgumentExpression()) as? KtLambdaExpression

  /** Whether an event argument has an event's shape (a lambda or a function reference). */
  fun isEventShape(arg: KtValueArgument): Boolean =
    lambdaOf(arg) != null || unparen(arg.getArgumentExpression()) is KtCallableReferenceExpression

  private fun unparen(e: KtExpression?): KtExpression? = if (e is KtParenthesizedExpression) unparen(e.expression) else e

  /**
   * `Modifier.a(…).b(…)` as its element calls in chain order (`Modifier` alone: none); null when
   * [e] is not a chain that starts at `Modifier` (a variable, `then`, a conditional, …).
   */
  fun chain(e: KtExpression?): List<KtCallExpression>? {
    val x = unparen(e) ?: return null
    return when (x) {
      is KtNameReferenceExpression -> if (x.getReferencedName() == "Modifier") emptyList() else null
      is KtDotQualifiedExpression -> {
        val sel = x.selectorExpression as? KtCallExpression ?: return null
        if (sel.calleeExpression !is KtNameReferenceExpression || sel.typeArgumentList != null) return null
        chain(x.receiverExpression)?.plus(sel)
      }
      else -> null
    }
  }

  /** Key of element [i] in a node's modifier map, and of its argument [param]. */
  fun elementKey(i: Int, entry: ComposeCatalog.Entry): String = "$i:${entry.key}"
  fun argKey(i: Int, entry: ComposeCatalog.Entry, param: String): String = "${elementKey(i, entry)}.$param"

  /** Whether a modifier-map key is a Compose element's (`3:padding#2`, `3:padding#2.all`). */
  fun isElementKey(key: String): Boolean = Regex("""^\d+:[A-Za-z_]\w*#\d+(\..+)?$""").matches(key)

  /** The elements of a Compose node's modifier map, in chain order: (element key, its arguments by name). */
  fun elements(mods: Map<String, dev.keliver.portal.document.PropValue>): List<Pair<String, Map<String, dev.keliver.portal.document.PropValue>>> {
    val heads = mods.keys.filter { '.' !in it }.sortedBy { it.substringBefore(':').toInt() }
    return heads.map { h ->
      h to mods.filterKeys { it.startsWith("$h.") }.mapKeys { it.key.substringAfter("$h.") }
    }
  }
}
