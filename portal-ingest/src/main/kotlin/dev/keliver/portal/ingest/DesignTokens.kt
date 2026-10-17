package dev.keliver.portal.ingest

import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
import dev.keliver.portal.tokenKindOf
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * K2: design-token constants an app declares in its portal-scanned sources —
 * `const val` (or a `val` with a constant initializer) at top level or in a
 * top-level `object`:
 *
 * ```kotlin
 * object C { const val Ink = -14737633 }
 * object Assets { private const val WEB = "https://example.com"; const val Logo = "$WEB/logo.png" }
 * ```
 *
 * Values are computed at ingest: Int/Long/Double/Boolean literals (negative
 * too) and String literals, templates and `+` concatenations made only of
 * other resolvable constants, resolved transitively with cycle detection.
 */
class TokenDecl internal constructor(
  /** The declaring file's package; null = the default package. */
  val packageName: String?,
  /** The top-level object holding it, null for a top-level property. */
  val owner: String?,
  val name: String,
  /** The declaring file's name (private top-level and private objects are file-scoped). */
  val fileName: String,
  /** Private in its object (only that object sees it) or private at file level. */
  val isPrivate: Boolean,
  /** The holding object is private: the constant is visible only inside its file. */
  val ownerPrivate: Boolean,
  val isConst: Boolean,
  /** The declared Kotlin type text, null when inferred. */
  val declaredType: String?,
  internal val initializer: KtExpression,
  internal val scope: FileScope,
) {
  /** How screens reference it: `C.Ink`, or `Ink` for a top-level constant. */
  val ref: String get() = owner?.let { "$it.$name" } ?: name

  /** The import a file outside [packageName] needs: the object, or the constant itself. */
  val importFq: String? get() = packageName?.let { "$it.${owner ?: name}" }

  val fqName: String get() = listOfNotNull(packageName, owner, name).joinToString(".")

  /** Usable from another file: neither the constant nor its object is private. */
  val isExported: Boolean get() = !isPrivate && !ownerPrivate
}

/** A [TokenDecl] with its computed value (null when it does not resolve: unsupported, cyclic, unknown reference). */
data class TokenEntry(val decl: TokenDecl, val value: Any?, val ambiguous: Boolean = false) {
  val kind: String? get() = tokenKindOf(value)
}

/**
 * The name-resolution context of one file: its package and its imports
 * (exact `import a.b.C` and star `import a.b.*`). Aliased imports
 * (`import a.b.C as D`) are deliberately not followed — a reference written
 * through an alias cannot be inserted into another file — so such a
 * reference stays RawCode.
 */
class FileScope internal constructor(
  val packageName: String?,
  val fileName: String,
  internal val exact: Map<String, String>,
  internal val stars: Set<String>,
  /** Set while evaluating an initializer inside an object (its members resolve bare). */
  internal val enclosingOwner: String? = null,
) {
  internal fun inside(owner: String?) = FileScope(packageName, fileName, exact, stars, owner)

  companion object {
    fun of(file: KtFile): FileScope {
      val exact = LinkedHashMap<String, String>()
      val stars = LinkedHashSet<String>()
      for (d in file.importDirectives) {
        val fq = d.importedFqName?.asString() ?: continue
        when {
          d.isAllUnder -> stars += fq
          d.aliasName == null -> exact[fq.substringAfterLast('.')] = fq
        }
      }
      return FileScope(file.packageFqName.asString().ifEmpty { null }, file.name, exact, stars)
    }
  }
}

/** Resolves a reference expression's text in one file to a token value (null = not a token). */
internal fun interface TokenLookup {
  fun lookup(text: String): PropValue.Token?
}

/**
 * The design tokens of one app: every constant declaration in the scanned
 * sources, evaluated once. Immutable; the relay rebuilds it when a source changes.
 */
class TokenTable private constructor(decls: List<TokenDecl>) {
  private val byOwnerName = decls.groupBy { it.owner to it.name }
  private val memo = HashMap<TokenDecl, Any?>()
  private val evaluating = LinkedHashSet<TokenDecl>()
  private val NONE = Any()

  /** Every declaration with its value, in a stable order (package, owner, name). */
  val entries: List<TokenEntry> = run {
    val refs = decls.filter { it.isExported }.groupBy { it.ref }
    decls.map { d -> TokenEntry(d, valueOf(d), ambiguous = d.isExported && (refs[d.ref]?.size ?: 0) > 1) }
      .sortedWith(compareBy({ it.decl.packageName ?: "" }, { it.decl.owner ?: "" }, { it.decl.name }))
  }

  /** What the editor may OFFER: exported, resolvable, and not ambiguous by simple reference. */
  val offered: List<TokenEntry> get() = entries.filter { it.decl.isExported && it.value != null && !it.ambiguous }

  /** The offered token a client names by its [ref] and [importFq] (null when there is none). */
  fun offered(ref: String, importFq: String?): TokenEntry? =
    offered.firstOrNull { it.decl.ref == ref && it.decl.importFq == importFq }

  /** Structural equality of what resolution can observe — the relay re-ingests when this changes. */
  fun signature(): List<String> = entries.map {
    "${it.decl.fqName}|${it.decl.isPrivate}|${it.decl.ownerPrivate}|${it.decl.fileName}|${it.kind}|${it.value}"
  }

  /** A [TokenLookup] for references written in [file]. */
  internal fun lookupFor(file: KtFile): TokenLookup {
    if (entries.isEmpty()) return TokenLookup { null }
    val scope = FileScope.of(file)
    return TokenLookup { text -> resolveRef(text.trim(), scope) }
  }

  /** The value a reference in [scope] resolves to, as a PropValue.Token — the text kept as written. */
  internal fun resolveRef(text: String, scope: FileScope): PropValue.Token? {
    val m = REF.matchEntire(text) ?: return null
    val decl = if (m.groupValues[2].isEmpty()) bare(m.groupValues[1], scope) else qualified(m.groupValues[1], m.groupValues[2], scope)
    decl ?: return null
    val value = valueOf(decl) ?: return null
    if (tokenKindOf(value) !in LITERAL_KINDS) return null
    return PropValue.Token(text, lit(value), decl.importFq)
  }

  /** `Owner.Name`: the owner object as Kotlin finds it (explicit import, same package, star), then its member. */
  private fun qualified(ownerName: String, name: String, scope: FileScope): TokenDecl? {
    val candidates = byOwnerName[ownerName to name].orEmpty()
    if (candidates.isEmpty()) return null
    fun visible(d: TokenDecl) =
      (!d.ownerPrivate || d.fileName == scope.fileName) &&
        (!d.isPrivate || (scope.enclosingOwner == d.owner && d.fileName == scope.fileName))
    scope.exact[ownerName]?.let { fq ->
      return candidates.singleOrNull { "${it.packageName?.let { p -> "$p." } ?: ""}${it.owner}" == fq }?.takeIf(::visible)
    }
    candidates.filter { it.packageName == scope.packageName }.let { same ->
      if (same.isNotEmpty()) return same.singleOrNull()?.takeIf(::visible)
    }
    return candidates.filter { it.packageName in scope.stars }.singleOrNull()?.takeIf(::visible)
  }

  /** A bare `Name`: a member of the enclosing object, else a top-level constant (import, same package, star). */
  private fun bare(name: String, scope: FileScope): TokenDecl? {
    if (scope.enclosingOwner != null) {
      byOwnerName[scope.enclosingOwner to name].orEmpty()
        .singleOrNull { it.packageName == scope.packageName && it.fileName == scope.fileName }
        ?.let { return it }
    }
    val candidates = byOwnerName[null to name].orEmpty()
    if (candidates.isEmpty()) return null
    fun visible(d: TokenDecl) = !d.isPrivate || d.fileName == scope.fileName
    scope.exact[name]?.let { fq ->
      return candidates.singleOrNull { it.fqName == fq }?.takeIf(::visible)
    }
    candidates.filter { it.packageName == scope.packageName }.let { same ->
      if (same.isNotEmpty()) return same.filter(::visible).singleOrNull()
    }
    return candidates.filter { it.packageName in scope.stars }.singleOrNull()?.takeIf(::visible)
  }

  // ── evaluation (all of it runs while [entries] is built; lookups only read [memo]) ──

  private fun valueOf(d: TokenDecl): Any? {
    memo[d]?.let { return if (it === NONE) null else it }
    if (!evaluating.add(d)) return null // a cycle: every member is unresolvable
    val v = try {
      typed(d, eval(d.initializer, d.scope.inside(d.owner)))
    } finally {
      evaluating.remove(d)
    }
    // Inside a cycle, a member's value depends on where evaluation entered it:
    // memoize only results computed with nothing else in flight.
    if (evaluating.isEmpty() || v != null) memo[d] = v ?: NONE
    return v
  }

  /** The value as the declared type reads it (an integer literal declared `Long` is a Long). */
  private fun typed(d: TokenDecl, v: Any?): Any? {
    val type = d.declaredType?.removePrefix("kotlin.")?.trim() ?: return v
    return when {
      v == null -> null
      type == "Long" && v is Int && isIntegerLiteral(d.initializer) -> v.toLong()
      type == tokenKindOf(v) -> v
      else -> null // Int to Double etc. does not compile; anything else is not a token
    }
  }

  private fun isIntegerLiteral(e: KtExpression): Boolean = when (e) {
    is KtConstantExpression -> true
    is KtPrefixExpression -> e.baseExpression?.let(::isIntegerLiteral) == true
    is KtParenthesizedExpression -> e.expression?.let(::isIntegerLiteral) == true
    else -> false
  }

  private fun eval(e: KtExpression?, scope: FileScope): Any? = when (e) {
    null -> null
    is KtParenthesizedExpression -> eval(e.expression, scope)
    is KtConstantExpression -> constant(e.text)
    is KtPrefixExpression -> {
      val v = eval(e.baseExpression, scope)
      when (e.operationToken) {
        KtTokens.MINUS -> when (v) { is Int -> -v; is Long -> -v; is Double -> -v; else -> null }
        KtTokens.PLUS -> v.takeIf { it is Int || it is Long || it is Double }
        KtTokens.EXCL -> (v as? Boolean)?.not()
        else -> null
      }
    }
    is KtStringTemplateExpression -> template(e, scope)
    is KtBinaryExpression -> {
      if (e.operationToken != KtTokens.PLUS) {
        null
      } else {
        val l = eval(e.left, scope)
        val r = eval(e.right, scope)
        if (l is String && r != null) l + stringOf(r) else null
      }
    }
    is KtNameReferenceExpression -> bare(e.getReferencedName(), scope)?.let(::valueOf)
    is KtDotQualifiedExpression -> {
      val recv = e.receiverExpression as? KtNameReferenceExpression
      val sel = e.selectorExpression as? KtNameReferenceExpression
      if (recv == null || sel == null) null
      else qualified(recv.getReferencedName(), sel.getReferencedName(), scope)?.let(::valueOf)
    }
    else -> null
  }

  private fun template(e: KtStringTemplateExpression, scope: FileScope): String? {
    if (e.text.startsWith("\"\"\"")) return null // raw strings are not grammar
    val sb = StringBuilder()
    for (entry in e.entries) {
      when (entry) {
        is KtLiteralStringTemplateEntry -> sb.append(entry.text)
        is KtEscapeStringTemplateEntry -> sb.append(entry.unescapedValue)
        is KtSimpleNameStringTemplateEntry, is KtBlockStringTemplateEntry ->
          sb.append(stringOf(eval(entry.expression, scope) ?: return null))
        else -> return null
      }
    }
    return sb.toString()
  }

  /** How Kotlin writes a value into a string (JVM rendering for Double). */
  private fun stringOf(v: Any): String = v.toString()

  companion object {
    val EMPTY: TokenTable = TokenTable(emptyList())

    private val REF = Regex("^([A-Za-z_][A-Za-z0-9_]*)(?:\\.([A-Za-z_][A-Za-z0-9_]*))?$")
    private val LITERAL_KINDS = setOf("String", "Int", "Double", "Boolean")

    /** Scan sources ([fileName] to text) for token declarations. A file that does not parse contributes nothing. */
    fun scan(sources: List<Pair<String, String>>): TokenTable {
      val decls = mutableListOf<TokenDecl>()
      for ((name, text) in sources) {
        val file = runCatching { PsiEnv.parse(name, text) }.getOrNull() ?: continue
        val scope = FileScope.of(file)
        for (top in file.declarations) {
          when (top) {
            is KtProperty -> declOf(top, null, false, scope)?.let(decls::add)
            is KtObjectDeclaration -> {
              if (top.isCompanion() || top.isObjectLiteral()) continue
              val owner = top.name ?: continue
              val ownerPrivate = top.isPrivateLike()
              top.body?.properties?.forEach { p -> declOf(p, owner, ownerPrivate, scope)?.let(decls::add) }
            }
          }
        }
      }
      return TokenTable(decls)
    }

    private fun declOf(p: KtProperty, owner: String?, ownerPrivate: Boolean, scope: FileScope): TokenDecl? {
      if (p.isVar || p.isLocal || p.receiverTypeReference != null) return null
      if (p.getter != null || p.hasDelegate()) return null
      val init = p.initializer ?: return null
      val name = p.name ?: return null
      return TokenDecl(
        packageName = scope.packageName,
        owner = owner,
        name = name,
        fileName = scope.fileName,
        isPrivate = p.isPrivateLike(),
        ownerPrivate = ownerPrivate,
        isConst = p.hasModifier(KtTokens.CONST_KEYWORD),
        declaredType = p.typeReference?.text,
        initializer = init,
        scope = scope,
      )
    }

    private fun KtModifierListOwner.isPrivateLike(): Boolean =
      hasModifier(KtTokens.PRIVATE_KEYWORD) || hasModifier(KtTokens.PROTECTED_KEYWORD)

    /** Kotlin literal text -> Int/Long/Double/Boolean (null for Float, Char, unsigned, null). */
    internal fun constant(text: String): Any? {
      val t = text.replace("_", "")
      return when {
        t == "true" -> true
        t == "false" -> false
        Regex("^0[xX][0-9a-fA-F]+[lL]?$").matches(t) -> integer(t.substring(2), 16)
        Regex("^0[bB][01]+[lL]?$").matches(t) -> integer(t.substring(2), 2)
        Regex("^\\d+[lL]?$").matches(t) -> integer(t, 10)
        Regex("^(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][+-]?\\d+)?$").matches(t) && ('.' in t || 'e' in t || 'E' in t) -> t.toDoubleOrNull()
        else -> null // Float (`1f`), Char, unsigned (`1u`), null
      }
    }

    private fun integer(digits: String, radix: Int): Any? {
      val long = digits.endsWith('l') || digits.endsWith('L')
      val v = digits.trimEnd('l', 'L').toLongOrNull(radix) ?: return null
      return if (!long && v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v
    }
  }
}
