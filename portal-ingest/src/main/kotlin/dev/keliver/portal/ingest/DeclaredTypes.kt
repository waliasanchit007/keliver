package dev.keliver.portal.ingest

import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

/**
 * K4: the type text of a reference whose declared type ingest cannot read —
 * a property without a type (`val x get() = 1.5`), a member inherited from a
 * supertype declared in another file, a screen field when the screen's
 * Bindings interface is not in its file. A template never reads one (it
 * could be a Double or nullable, which the device and the preview print
 * differently); the contract carries it as is, so the document refuses a
 * template reading that field too ([dev.keliver.portal.templateRefTypeFits]
 * does not fit it).
 */
internal const val UNKNOWN_TYPE = "<unknown>"

/**
 * E14: the bindings parameter of a component's document — none: the body
 * reads its parameters by their bare names (`title`, `onClick`), so write-back
 * spells a field or an action without a receiver.
 */
internal const val COMPONENT_PARAMS = ""

/**
 * The declared member types of a class or interface: each property (and
 * `val`/`var` constructor parameter) → its type text, or [UNKNOWN_TYPE]
 * when it declares none. [outside] names a supertype not declared in the
 * same file (the first one met), or is null — a member not listed may then
 * be inherited from it, with a type ingest cannot read ([complete] false).
 */
internal class MemberTypes(val types: Map<String, String>, val outside: String?) {
  val complete: Boolean get() = outside == null

  /** The type of member [name]: its declared type; unknown when it may be inherited from elsewhere; null when none is declared. */
  fun typeOf(name: String): String? = types[name] ?: if (complete) null else UNKNOWN_TYPE
}

/** The file's top-level classes and interfaces by name (the first of a name wins). */
internal fun typeDecls(file: KtFile): Map<String, KtClass> {
  val out = LinkedHashMap<String, KtClass>()
  file.declarations.filterIsInstance<KtClass>().forEach { c -> c.name?.let { out.putIfAbsent(it, c) } }
  return out
}

/**
 * [cls]'s members with their declared types, its own first, then those of
 * each supertype declared in [decls] (the same file), recursively. A member
 * the class declares wins over an inherited one.
 */
internal fun memberTypes(cls: KtClass, decls: Map<String, KtClass>, seen: MutableSet<KtClass> = HashSet()): MemberTypes {
  seen += cls
  val types = LinkedHashMap<String, String>()
  cls.primaryConstructorParameters.filter { it.hasValOrVar() }.forEach { p ->
    p.name?.let { types.putIfAbsent(it, p.typeReference?.text ?: UNKNOWN_TYPE) }
  }
  cls.declarations.filterIsInstance<KtProperty>().forEach { p ->
    p.name?.let { types.putIfAbsent(it, p.typeReference?.text ?: UNKNOWN_TYPE) }
  }
  var outside: String? = null
  for (entry in cls.superTypeListEntries) {
    val name = entry.typeReference?.text?.substringBefore('<')?.trim()
    val sup = sameFileClass(entry.typeReference, decls)
    if (sup == null || sup in seen) {
      if (sup == null && outside == null) outside = name ?: entry.text
      continue
    }
    val inherited = memberTypes(sup, decls, seen)
    inherited.types.forEach { (k, v) -> types.putIfAbsent(k, v) }
    if (outside == null) outside = inherited.outside
  }
  return MemberTypes(types, outside)
}

/**
 * The class or interface declared in this file that the type reference
 * [ref] names — `Base`, one nested in a declaration of the file
 * (`Outer.Base`), either qualified by the file's own package — or null when
 * it names one declared elsewhere (#135 review: `Outer.Base` was looked up
 * as a top-level name, so a nested supertype read as foreign).
 */
internal fun sameFileClass(ref: KtTypeReference?, decls: Map<String, KtClass>): KtClass? {
  val segs = segmentsOf(ref) ?: return null
  val file = ref?.containingKtFile ?: return null
  val pkg = file.packageFqName.pathSegments().map { it.asString() }
  val path = if (pkg.isNotEmpty() && segs.size > pkg.size && segs.take(pkg.size) == pkg) segs.drop(pkg.size) else segs
  if (path.size == 1) return decls[path.single()]
  var cur: KtClassOrObject = file.declarations.filterIsInstance<KtClassOrObject>().firstOrNull { it.name == path.first() } ?: return null
  for (seg in path.drop(1)) {
    cur = cur.declarations.filterIsInstance<KtClassOrObject>().firstOrNull { it.name == seg } ?: return null
  }
  return cur as? KtClass
}

/**
 * The name of the screen function [text] (a file named [fileName]) declares, as parsed: its first
 * top-level `@Composable` function that is not private (a helper), else its first one; null when
 * it declares none. Review 4 #9: read from the file's syntax, so an annotation on the same line,
 * a comment, or a string holding a comment opener cannot hide or invent one.
 */
fun declaredScreenFunctionName(fileName: String, text: String): String? {
  val composables = runCatching { PsiEnv.parse(fileName, text) }.getOrNull()?.declarations.orEmpty()
    .filterIsInstance<KtNamedFunction>()
    .filter { f -> f.annotationEntries.any { it.shortName?.asString() == "Composable" } }
  return (composables.firstOrNull { !it.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) } ?: composables.firstOrNull())?.name
}

/** A screen file's screen function: its first `@Composable` function (null = not a screen). */
internal fun screenFunction(file: KtFile): KtNamedFunction? = file.declarations.filterIsInstance<KtNamedFunction>()
  .firstOrNull { f -> f.annotationEntries.any { it.shortName?.asString() == "Composable" } }

/**
 * K4: what the screen's bindings parameter (its first) names, read from its
 * PSI type — never its text: annotations, modifiers, `?` and type arguments
 * are not part of the name; a same-file typealias (chained too) and an
 * import alias are followed to their target; `Outer.XBindings` is the
 * interface nested in `Outer` declared in this file; a qualifier that is the
 * file's own package is dropped. [iface] is the interface (null when there is
 * none to read), [why] says why when it is null.
 *
 * A plain name this file does not declare may be a typealias declared in
 * another file: the screen then takes the file's ONLY `…Bindings` interface
 * (the 0.3.x rule) — unless the name itself is a `…Bindings` one or is
 * qualified by another package (it names an interface declared elsewhere) or
 * the file declares more than one (ambiguous: none is taken).
 */
internal class ScreenBindings(val iface: KtClass?, val why: String?)

/** What a type reference names in its file. */
private sealed interface Named {
  /** [decl], declared in the file; [shown] is how the parameter writes it. */
  class InFile(val decl: KtClassOrObject, val shown: String) : Named
  /** Not declared in the file: [shown] and [detail] (the reason's tail); [simple] is its last segment. */
  class Elsewhere(val shown: String, val simple: String, val qualified: Boolean, val detail: String) : Named
  /** Not a name ingest reads ([detail] says what it is instead). */
  class Unreadable(val shown: String, val detail: String) : Named
}

internal fun screenBindings(fn: KtNamedFunction, file: KtFile): ScreenBindings {
  val param = fn.valueParameters.firstOrNull() ?: return ScreenBindings(null, "the screen has no Bindings parameter")
  val named = nameOf(param.typeReference, file, HashSet(), via = null)
  return when (named) {
    is Named.InFile -> {
      val cls = named.decl as? KtClass
      if (cls != null && cls.isInterface()) ScreenBindings(cls, null)
      else ScreenBindings(null, "the screen takes ${named.shown}, which this file declares, but not as an interface")
    }
    is Named.Elsewhere -> {
      val all = file.declarations.filterIsInstance<KtClass>().filter { it.isInterface() && it.name?.endsWith("Bindings") == true }
      val namesElsewhere = named.qualified || named.simple.endsWith("Bindings")
      when {
        !namesElsewhere && all.size == 1 -> ScreenBindings(all.single(), null)
        !namesElsewhere && all.size > 1 -> ScreenBindings(
          null,
          "the screen takes ${named.shown}, ${named.detail}, and this file declares more than one …Bindings interface " +
            "(${all.joinToString { "`${it.name}`" }}) — none is taken for it",
        )
        else -> ScreenBindings(null, "the screen takes ${named.shown}, ${named.detail}")
      }
    }
    is Named.Unreadable -> ScreenBindings(null, "the screen takes ${named.shown}, ${named.detail}")
  }
}

/** K4: the Bindings interface the screen [fn] takes ([screenBindings]); null when there is none to read. */
internal fun screenInterface(fn: KtNamedFunction, file: KtFile): KtClass? = screenBindings(fn, file).iface

/** The segments of the user type [ref] names (`a.B.C` is [a, B, C]) — past annotations, modifiers, `?` and type arguments; null when it names none. */
private fun segmentsOf(ref: KtTypeReference?): List<String>? {
  var el: KtTypeElement? = ref?.typeElement
  while (el is KtNullableType) el = el.innerType
  var user: KtUserType? = el as? KtUserType ?: return null
  val out = ArrayList<String>()
  while (user != null) {
    out += user.referencedName ?: return null
    user = user.qualifier
  }
  return out.asReversed()
}

private fun nameOf(ref: KtTypeReference?, file: KtFile, aliases: MutableSet<String>, via: String?): Named {
  val segs = segmentsOf(ref)
  val text = ref?.typeElement?.text ?: ref?.text ?: "?"
  if (segs == null) {
    return if (via == null) Named.Unreadable("`$text`", "which is not an interface")
    else Named.Unreadable(via, "a typealias of `$text`, which is not an interface")
  }
  val dotted = segs.joinToString(".")
  val shown = via ?: "`$dotted`"
  val aliasNote = if (via == null) "which" else "a typealias of `$dotted`, which"
  val top = file.declarations.filterIsInstance<KtClassOrObject>().asReversed().associateBy { it.name } // the first of a name wins
  val pkg = file.packageFqName.asString()
  val path = when {
    segs.size > 1 && segs.dropLast(1).joinToString(".") == pkg -> listOf(segs.last())
    else -> segs
  }
  if (path.size == 1) {
    val name = path.single()
    top[name]?.let { return Named.InFile(it, shown) }
    file.declarations.filterIsInstance<KtTypeAlias>().firstOrNull { it.name == name }?.let { alias ->
      if (!aliases.add(name)) return Named.Unreadable(via ?: "`$name`", "a typealias cycle through `$name`")
      return nameOf(alias.getTypeReference(), file, aliases, via ?: "`$name`")
    }
    file.importDirectives.firstOrNull { it.aliasName == name }?.importedFqName?.let { fq ->
      val target = fq.asString()
      return if (fq.parent().asString() == pkg) {
        top[fq.shortName().asString()]?.let { Named.InFile(it, shown) }
          ?: Named.Elsewhere(shown, fq.shortName().asString(), qualified = false, detail = "an import alias of `$target`, which is not declared in this file")
      } else {
        Named.Elsewhere(shown, fq.shortName().asString(), qualified = true, detail = "an import alias of `$target`, declared in another package")
      }
    }
    return Named.Elsewhere(shown, name, qualified = false, detail = "$aliasNote is not declared in this file")
  }
  // A qualified name: a declaration nested in one this file declares, else one declared elsewhere.
  val root = top[path.first()]
    ?: return Named.Elsewhere(shown, path.last(), qualified = true, detail = "$aliasNote is not declared in this file")
  var cur: KtClassOrObject = root
  for (i in 1 until path.size) {
    cur = cur.declarations.filterIsInstance<KtClassOrObject>().firstOrNull { it.name == path[i] }
      ?: return Named.Elsewhere(
        shown, path.last(), qualified = true,
        detail = "$aliasNote is not found: `${path.take(i).joinToString(".")}` in this file declares no `${path[i]}`",
      )
  }
  return Named.InFile(cur, shown)
}

/**
 * K4: why a screen field the screen's interface does not list has a type
 * ingest cannot read (the contract's
 * [dev.keliver.portal.document.Contract.unlistedFieldsUnknown]) — null when
 * the interface the screen takes ([target]) is read whole in its file (an
 * unlisted field is new: contract write-back declares it).
 */
internal fun unlistedFieldsWhy(target: ScreenBindings, types: MemberTypes?): String? {
  val iface = target.iface ?: return target.why
  val outside = types?.outside ?: return null
  return "`${iface.name}` extends `$outside`, declared in another file — a field it does not declare here may be inherited, with any type"
}

/** The element type's simple name of a `List<X>` type text — null for anything else (a nullable or generic element too). */
internal fun listElementName(type: String?): String? {
  val t = type?.replace(" ", "") ?: return null
  val element = Regex("^(?:kotlin\\.collections\\.)?List<([A-Za-z_][A-Za-z0-9_]*)>$").find(t)?.groupValues?.get(1)
  return element
}

/** [type] without a `kotlin.` qualifier and spaces — `kotlin.Int` is `Int`. */
internal fun plainType(type: String): String = type.replace(" ", "").removePrefix("kotlin.")
