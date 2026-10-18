package dev.keliver.portal.ingest

import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty

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
 * The declared member types of a class or interface: each property (and
 * `val`/`var` constructor parameter) → its type text, or [UNKNOWN_TYPE]
 * when it declares none. [complete] is false when a supertype is not
 * declared in the same file — a member not listed may then be inherited
 * from it, with a type ingest cannot read.
 */
internal class MemberTypes(val types: Map<String, String>, val complete: Boolean) {
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
  var complete = true
  for (entry in cls.superTypeListEntries) {
    val name = entry.typeReference?.text?.substringBefore('<')?.trim()
    val sup = name?.let(decls::get)
    if (sup == null || sup in seen) {
      if (sup == null) complete = false
      continue
    }
    val inherited = memberTypes(sup, decls, seen)
    inherited.types.forEach { (k, v) -> types.putIfAbsent(k, v) }
    if (!inherited.complete) complete = false
  }
  return MemberTypes(types, complete)
}

/** The element type's simple name of a `List<X>` type text — null for anything else (a nullable or generic element too). */
internal fun listElementName(type: String?): String? {
  val t = type?.replace(" ", "") ?: return null
  val element = Regex("^(?:kotlin\\.collections\\.)?List<([A-Za-z_][A-Za-z0-9_]*)>$").find(t)?.groupValues?.get(1)
  return element
}

/** [type] without a `kotlin.` qualifier and spaces — `kotlin.Int` is `Int`. */
internal fun plainType(type: String): String = type.replace(" ", "").removePrefix("kotlin.")
