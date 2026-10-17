package dev.keliver.portal.ingest

import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
import dev.keliver.portal.tokenKindOf
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
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
 * object C { const val Ink = -14737633; const val Line = 0xFFE0E0E0.toInt() }
 * object Assets { private const val WEB = "https://example.com"; const val Logo = "$WEB/logo.png" }
 * ```
 *
 * Values are computed at ingest: Int/Long/Double/Boolean literals (negative,
 * hex, binary, `_` separators; finite only), `<Int or Long constant>.toInt()`
 * (two's complement, as Kotlin computes it), and String literals, templates
 * and `+` concatenations made only of other String/Int/Long/Boolean constants
 * — resolved transitively with cycle detection. A Double is never rendered
 * into a String (Kotlin/JVM and Kotlin/JS print it differently).
 */
class TokenDecl internal constructor(
  /** The declaring file's package; null = the default package. */
  val packageName: String?,
  /** The top-level object holding it, null for a top-level property. */
  val owner: String?,
  val name: String,
  /** The declaring file's identity — its repo-relative path in the relay (private visibility is per file). */
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

  /** A value a prop can hold: String, Int, Double or Boolean (a Long fits no portal prop). */
  val isLiteralKind: Boolean get() = kind in TokenTable.LITERAL_KINDS
}

/**
 * The name-resolution context of one file: its package, its imports (exact
 * `import a.b.C` and star `import a.b.*`), the names it aliases
 * (`import a.b.C as D` — a reference through an alias, and any name an alias
 * shadows, is never a token) and its own top-level declarations.
 */
class FileScope internal constructor(
  val packageName: String?,
  /** The file's identity (see [TokenDecl.fileName]). */
  val fileName: String,
  internal val exact: Map<String, String>,
  internal val stars: Set<String>,
  internal val aliases: Set<String>,
  internal val ownNames: Set<String>,
  /** Set while evaluating an initializer inside an object (its members resolve bare). */
  internal val enclosingOwner: String? = null,
) {
  internal fun inside(owner: String?) = FileScope(packageName, fileName, exact, stars, aliases, ownNames, owner)

  /** This scope with `import [fq]` added. */
  internal fun withImport(fq: String) =
    FileScope(packageName, fileName, exact + (fq.substringAfterLast('.') to fq), stars, aliases, ownNames, enclosingOwner)

  companion object {
    fun of(file: KtFile, fileName: String = file.name): FileScope {
      val exact = LinkedHashMap<String, String>()
      val stars = LinkedHashSet<String>()
      val aliases = LinkedHashSet<String>()
      for (d in file.importDirectives) {
        val fq = d.importedFqName?.asString() ?: continue
        when {
          d.isAllUnder -> stars += fq
          d.aliasName == null -> exact[fq.substringAfterLast('.')] = fq
          else -> aliases += d.aliasName!!
        }
      }
      val own = file.declarations.mapNotNull { (it as? KtNamedDeclaration)?.name }.toSet()
      return FileScope(file.packageFqName.asString().ifEmpty { null }, fileName, exact, stars, aliases, own)
    }
  }
}

/** Resolves a reference expression's text in one file to a token value (null = not a token). */
fun interface TokenLookup {
  fun lookup(text: String): PropValue.Token?
}

/**
 * One scanned source file: its token declarations and the names of ALL its
 * top-level declarations (classes, objects, typealiases, functions,
 * properties) — a same-package declaration of a name beats a star import, so
 * resolution must know them. Immutable; the relay caches it per file.
 */
class TokenFileScan internal constructor(
  val fileName: String,
  internal val packageName: String?,
  internal val decls: List<TokenDecl>,
  internal val topLevelNames: Set<String>,
)

/**
 * The design tokens of one app: every constant declaration in the scanned
 * sources, evaluated once. Immutable; the relay rebuilds it when a source changes.
 */
class TokenTable private constructor(scans: List<TokenFileScan>) {
  private val decls = scans.flatMap { it.decls }
  private val byOwnerName = decls.groupBy { it.owner to it.name }

  /** package -> every top-level declaration name in the scanned files of that package. */
  private val packageNames: Map<String?, Set<String>> =
    scans.groupBy { it.packageName }.mapValues { (_, s) -> s.flatMapTo(HashSet()) { it.topLevelNames } }

  private val memo = HashMap<TokenDecl, Any?>()
  private val evaluating = LinkedHashSet<TokenDecl>()
  private val NONE = Any()

  /** Every declaration with its value, in a stable order (package, owner, name). */
  val entries: List<TokenEntry> = run {
    val refs = decls.filter { it.isExported }.groupBy { it.ref }
    decls.map { d -> TokenEntry(d, valueOf(d), ambiguous = d.isExported && (refs[d.ref]?.size ?: 0) > 1) }
      .sortedWith(compareBy({ it.decl.packageName ?: "" }, { it.decl.owner ?: "" }, { it.decl.name }))
  }

  /**
   * What the editor may OFFER: exported, of a kind a prop can hold
   * (String/Int/Double/Boolean — never a Long), and not ambiguous by its
   * simple reference.
   */
  val offered: List<TokenEntry> get() = entries.filter { it.decl.isExported && it.isLiteralKind && !it.ambiguous }

  /**
   * The token a client names by [ref] and [importFq] that a file [fileName]
   * may use: of a literal kind, not private in its object, and either
   * exported or declared in a private object of that same file. Ambiguity is
   * not refused here — [bindingProblem] checks what the reference means in
   * the target file.
   */
  fun usable(ref: String, importFq: String?, fileName: String): TokenEntry? = entries.firstOrNull { e ->
    e.decl.ref == ref && e.decl.importFq == importFq && e.isLiteralKind && !e.decl.isPrivate &&
      (!e.decl.ownerPrivate || e.decl.fileName == fileName)
  }

  /** Structural equality of what resolution can observe — the relay re-ingests when this changes. */
  fun signature(): List<String> = entries.map {
    "${it.decl.fqName}|${it.decl.isPrivate}|${it.decl.ownerPrivate}|${it.decl.fileName}|${it.kind}|${it.value}"
  } + packageNames.entries.sortedBy { it.key ?: "" }.map { "${it.key}:${it.value.sorted()}" }

  /**
   * A [TokenLookup] for references written in [file] (identity [fileName]).
   * Names in [shadowed] — the function's parameters, lambda parameters,
   * locals — are what Kotlin binds first, so a reference starting with one
   * is never a token.
   */
  internal fun lookupFor(file: KtFile, fileName: String = file.name, shadowed: Set<String> = emptySet()): TokenLookup {
    if (entries.isEmpty()) return TokenLookup { null }
    val scope = FileScope.of(file, fileName)
    return TokenLookup { text ->
      val t = text.trim()
      if (t.substringBefore('.') in shadowed) null else resolveRef(t, scope)
    }
  }

  /** The value a reference in [scope] resolves to, as a PropValue.Token — the text kept as written. */
  internal fun resolveRef(text: String, scope: FileScope): PropValue.Token? {
    val decl = declFor(text, scope) ?: return null
    val value = valueOf(decl) ?: return null
    if (tokenKindOf(value) !in LITERAL_KINDS) return null
    return PropValue.Token(text, lit(value), decl.importFq)
  }

  private fun declFor(text: String, scope: FileScope): TokenDecl? {
    val m = REF.matchEntire(text) ?: return null
    return if (m.groupValues[2].isEmpty()) bare(m.groupValues[1], scope) else qualified(m.groupValues[1], m.groupValues[2], scope)
  }

  /**
   * K2 (S3): why writing [token] into the file [text] (identity [fileName])
   * would not mean the declaration the client chose — or null when it would.
   * The token's import is what write-back adds; adding it must not change
   * what an existing reference in the file means either.
   */
  fun bindingProblem(text: String, fileName: String, token: PropValue.Token): String? {
    val chosen = usable(token.ref, token.import, fileName)?.decl ?: return "unknown design token ${token.ref}"
    val file = runCatching { PsiEnv.parse(fileName, text) }.getOrNull() ?: return null
    var scope = FileScope.of(file, fileName)
    val simple = token.ref.substringBefore('.')
    val fq = token.import
    if (fq != null) {
      val covered = scope.exact[simple] == fq || fq.substringBeforeLast('.') in scope.stars ||
        fq.substringBeforeLast('.') == scope.packageName
      when {
        covered -> {}
        scope.exact.containsKey(simple) ->
          return "this file imports a different $simple (${scope.exact[simple]}); ${token.ref} would not mean $fq"
        simple in scope.aliases || simple in scope.ownNames || simple in samePackage(scope) ||
          scope.stars.any { simple in packageNames[it].orEmpty() } ->
          return "importing $fq would change what $simple means in this file"
        else -> scope = scope.withImport(fq)
      }
    }
    val bound = declFor(token.ref, scope)
    return if (bound?.fqName == chosen.fqName) null
    else "${token.ref} does not mean ${chosen.fqName} in this file" + (bound?.let { " (it means ${it.fqName})" } ?: "")
  }

  private fun samePackage(scope: FileScope): Set<String> = packageNames[scope.packageName].orEmpty() + scope.ownNames

  /**
   * The token declaration a simple name [n] with member [name] (owner
   * object `n`, or a top-level constant when [name] is null) means in
   * [scope], following Kotlin: an aliased name is never ours; an explicit
   * import wins; then the file's own package (any declaration of the name
   * there — a class, a typealias — beats a star import); then exactly one
   * star-imported package that declares the name.
   */
  private fun lookupName(n: String, scope: FileScope, candidates: List<TokenDecl>, matchesFq: (TokenDecl, String) -> Boolean): TokenDecl? {
    if (candidates.isEmpty() || n in scope.aliases) return null
    scope.exact[n]?.let { fq -> return candidates.singleOrNull { matchesFq(it, fq) } }
    if (n in samePackage(scope)) return candidates.filter { it.packageName == scope.packageName }.singleOrNull()
    val starPkgs = scope.stars.filter { n in packageNames[it].orEmpty() }
    val pkg = starPkgs.singleOrNull() ?: return null
    return candidates.filter { it.packageName == pkg }.singleOrNull()
  }

  /** `Owner.Name`: the owner object as Kotlin finds it, then its member (with Kotlin visibility). */
  private fun qualified(ownerName: String, name: String, scope: FileScope): TokenDecl? {
    val decl = lookupName(ownerName, scope, byOwnerName[ownerName to name].orEmpty()) { d, fq ->
      "${d.packageName?.let { p -> "$p." } ?: ""}${d.owner}" == fq
    } ?: return null
    val visible = (!decl.ownerPrivate || decl.fileName == scope.fileName) &&
      (!decl.isPrivate || (scope.enclosingOwner == decl.owner && decl.fileName == scope.fileName))
    return decl.takeIf { visible }
  }

  /** A bare `Name`: a member of the enclosing object, else a top-level constant. */
  private fun bare(name: String, scope: FileScope): TokenDecl? {
    if (scope.enclosingOwner != null) {
      byOwnerName[scope.enclosingOwner to name].orEmpty()
        .singleOrNull { it.packageName == scope.packageName && it.fileName == scope.fileName }
        ?.let { return it }
    }
    val decl = lookupName(name, scope, byOwnerName[null to name].orEmpty()) { d, fq -> d.fqName == fq } ?: return null
    return decl.takeIf { !it.isPrivate || it.fileName == scope.fileName }
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
        val r = eval(e.right, scope)?.let(::stringOf)
        if (l is String && r != null) l + r else null
      }
    }
    is KtNameReferenceExpression -> bare(e.getReferencedName(), scope)?.let(::valueOf)
    is KtDotQualifiedExpression -> {
      val recv = e.receiverExpression
      when (val sel = e.selectorExpression) {
        // `0xFF1F1F1F.toInt()` — the ARGB idiom: Long.toInt() keeps the low 32 bits (two's complement).
        is KtCallExpression -> {
          val plain = sel.valueArguments.isEmpty() && sel.lambdaArguments.isEmpty() && sel.typeArgumentList == null
          if (!plain || sel.calleeExpression?.text != "toInt") null
          else when (val v = eval(recv, scope)) { is Long -> v.toInt(); is Int -> v; else -> null }
        }
        is KtNameReferenceExpression -> (recv as? KtNameReferenceExpression)
          ?.let { qualified(it.getReferencedName(), sel.getReferencedName(), scope) }?.let(::valueOf)
        else -> null
      }
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
          sb.append(eval(entry.expression, scope)?.let(::stringOf) ?: return null)
        else -> return null
      }
    }
    return sb.toString()
  }

  /** How Kotlin writes a value into a string — refused for a Double (JVM `1.0`, JS `1`). */
  private fun stringOf(v: Any): String? = if (v is Double) null else v.toString()

  companion object {
    val EMPTY: TokenTable = TokenTable(emptyList())

    private val REF = Regex("^([A-Za-z_][A-Za-z0-9_]*)(?:\\.([A-Za-z_][A-Za-z0-9_]*))?$")
    internal val LITERAL_KINDS = setOf("String", "Int", "Double", "Boolean")

    /** Scan sources ([fileName] to text) for token declarations. A file that does not parse contributes nothing. */
    fun scan(sources: List<Pair<String, String>>): TokenTable = of(sources.mapNotNull { (name, text) -> scanFile(name, text) })

    /** A table from already-scanned files (the relay re-scans only files that changed). */
    fun of(scans: List<TokenFileScan>): TokenTable = TokenTable(scans)

    /** Scan one file; [fileName] is its identity (the relay passes the repo-relative path). Null when it does not parse. */
    fun scanFile(fileName: String, text: String): TokenFileScan? {
      val file = runCatching { PsiEnv.parse(fileName, text) }.getOrNull() ?: return null
      val scope = FileScope.of(file, fileName)
      val decls = mutableListOf<TokenDecl>()
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
      return TokenFileScan(fileName, scope.packageName, decls, scope.ownNames)
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

    /**
     * The names a composable function body binds before any top-level
     * declaration: its parameters, every lambda parameter (`forEach { label
     * -> }`, and the implicit `it`), every local `val`/`var` and destructured
     * name. A reference starting with one of these is never a token.
     */
    internal fun shadowedNames(fn: KtNamedFunction): Set<String> {
      val out = linkedSetOf("it")
      PsiTreeUtil.collectElementsOfType(fn, KtParameter::class.java).forEach { p -> p.name?.let(out::add) }
      PsiTreeUtil.collectElementsOfType(fn, KtProperty::class.java).forEach { p -> p.name?.let(out::add) }
      PsiTreeUtil.collectElementsOfType(fn, KtDestructuringDeclarationEntry::class.java).forEach { p -> p.name?.let(out::add) }
      return out
    }

    /** Kotlin literal text -> Int/Long/Double/Boolean (null for Float, Char, unsigned, null, non-finite). */
    internal fun constant(text: String): Any? {
      val t = text.replace("_", "")
      return when {
        t == "true" -> true
        t == "false" -> false
        Regex("^0[xX][0-9a-fA-F]+[lL]?$").matches(t) -> integer(t.substring(2), 16)
        Regex("^0[bB][01]+[lL]?$").matches(t) -> integer(t.substring(2), 2)
        Regex("^\\d+[lL]?$").matches(t) -> integer(t, 10)
        Regex("^(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][+-]?\\d+)?$").matches(t) && ('.' in t || 'e' in t || 'E' in t) ->
          t.toDoubleOrNull()?.takeIf { it.isFinite() }
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
