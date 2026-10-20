package dev.keliver.portal.ingest

import dev.keliver.portal.RESERVED_COMPONENT_NAMES
import dev.keliver.portal.isModifierType
import dev.keliver.portal.kotlinStringLiteral
import dev.keliver.portal.widgetSpec
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Compiled component preview (docs/COMPILED_COMPONENT_PREVIEW.md): emits the
 * per-app editor's adapters — one `CompiledComponent` per project component —
 * from the components' Kotlin SIGNATURES, the same source the recognizer
 * reads. An adapter decodes an instance's arguments and calls the app's own
 * compiled component, so the canvas can draw an OPAQUE component exactly as a
 * device does.
 *
 * Per parameter (the rule the doc states):
 *  - `modifier: Modifier` gets the instance's modifiers + selection tag.
 *  - `String`/`Int`/`Boolean`/`Double` (nullable too) — the types an instance
 *    can set — are decoded. An omitted one takes its default: a literal or a
 *    design token's value is inlined; any other default cannot be reproduced,
 *    so an instance that omits it falls back to the placeholder.
 *  - `() -> Unit` / `(T) -> Unit` (nullable too) fire the instance's action.
 *  - one required `@Composable () -> Unit` slot renders the instance's children.
 *  - any other parameter with a default is left out (the compiled default
 *    applies: the portal cannot set it); without a default the component is
 *    REFUSED and keeps its placeholder, with the reason in the file.
 * A component that is private, has a receiver or type parameters is refused;
 * an `internal` one only when the adapters compile in the same module.
 */
object CompiledAdapterGenerator {
  class Output(
    /** The generated Kotlin source. */
    val source: String,
    /** Components with an adapter. */
    val adapted: List<String>,
    /** Refused components -> why. */
    val refused: Map<String, String>,
  )

  /** One component source file: its path (for messages) and its text. */
  class SourceFile(val path: String, val text: String)

  fun generate(
    files: List<SourceFile>,
    packageName: String,
    valName: String,
    tokens: TokenTable = TokenTable.EMPTY,
    sameModule: Boolean = false,
    regenerateHint: String = "regenerate with CompiledAdapterGenerator",
  ): Output = generateWith(files, packageName, valName, tokens, sameModule, regenerateHint, ::syntaxErrors)

  /** [generate] with the per-adapter validity check replaceable (tests exercise the refusal path). */
  internal fun generateWith(
    files: List<SourceFile>,
    packageName: String,
    valName: String,
    tokens: TokenTable,
    sameModule: Boolean,
    regenerateHint: String,
    adapterCheck: (String) -> String?,
  ): Output {
    require(isPlainIdentifier(valName)) { "valName must be a Kotlin identifier that is not a keyword: $valName" }
    require(packageName.isEmpty() || packageName.split('.').all { it.isNotEmpty() && IDENT.matches(it) }) {
      "packageName must be a Kotlin package name: $packageName"
    }
    val adapters = sortedMapOf<String, Adapter>()
    val refused = sortedMapOf<String, String>()
    val parsed = files.map { it to PsiEnv.parse(it.path, LineEndings.toLf(it.text)) }
    // Types declared in the scanned files, by package: a component that shares
    // its name with one (`class Foo` + `fun Foo()`) makes the aliased call
    // ambiguous, so it is refused. Types outside these files are not seen.
    val typeNames = parsed.flatMap { (_, file) ->
      val pkg = file.packageFqName.asString()
      file.declarations.filter { it is KtClassOrObject || it is KtTypeAlias }.mapNotNull { d -> d.name?.let { pkg to it } }
    }.toSet()
    for ((f, file) in parsed) {
      // The recognizer's rule: a component file's first @Composable function.
      val fn = file.declarations.filterIsInstance<KtNamedFunction>()
        .firstOrNull { d -> d.annotationEntries.any { it.shortName?.asString() == "Composable" } } ?: continue
      val name = fn.name ?: continue
      val spec = Recognizer.recognizeComponent(f.path, f.text, tokens = tokens)?.spec
      val pkg = file.packageFqName.asString().takeIf { it.isNotEmpty() }
      // A component name is the instance's node type: two components sharing
      // one cannot both be previewed (the portal's registry keeps one per name).
      if (name in adapters || name in refused) {
        adapters.remove(name)
        refused[name] = "two components are named $name (portal component names must be unique)"
        continue
      }
      if ((pkg.orEmpty() to name) in typeNames) {
        refused[name] = "a class, object or typealias in its package is also named $name"
        continue
      }
      when (val r = adapterFor(fn, name, pkg, packageName, valName, spec?.defaults.orEmpty(), sameModule)) {
        is Either.Ok -> adapters[name] = r.value
        is Either.No -> refused[name] = r.reason
      }
    }
    // Aliases are assigned in name order, so the output is deterministic.
    adapters.values.forEachIndexed { i, a -> a.alias = if (a.pkg == null) quote(a.name) else "${a.name}__c$i" }
    // Round-trip guard: an adapter whose text does not PARSE as Kotlin is
    // refused, never emitted (the relay rewrites this file before every
    // preview build). Parsing is not compiling: the refusals above exist for
    // the signatures known to parse but not compile.
    for (a in adapters.values.toList()) {
      val errors = adapterCheck("val x = kotlin.collections.mapOf(\n${renderAdapter(a)})\n")
      if (errors != null) {
        adapters.remove(a.name)
        refused[a.name] = "its adapter would not be valid Kotlin ($errors)"
      }
    }
    val source = render(adapters, refused, packageName, valName, sameModule, regenerateHint)
    check(syntaxErrors(source) == null) { "generated adapters do not parse: ${syntaxErrors(source)}" }
    return Output(source, adapters.keys.toList(), refused)
  }

  private class Adapter(val name: String, val pkg: String?, val decode: List<String>, val args: List<String>) {
    /** How the generated file refers to the component: an import alias, or its (quoted) name. */
    var alias: String = name
  }

  private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")

  /** Function annotations a call does not have to satisfy. */
  private val ALLOWED_ANNOTATIONS = setOf("Composable", "Suppress")

  /** Kotlin's hard keywords: never usable as a bare identifier. */
  private val HARD_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
    "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
    "val", "var", "when", "while",
  )

  private fun isPlainIdentifier(s: String) = IDENT.matches(s) && s !in HARD_KEYWORDS

  /** [s] as a Kotlin identifier: backticked when it is a keyword or not a plain identifier. */
  internal fun quote(s: String): String = if (isPlainIdentifier(s)) s else "`$s`"

  private fun quotePackage(pkg: String) = pkg.split('.').joinToString(".") { quote(it) }

  /** Null when [source] parses cleanly; otherwise the first syntax error. */
  internal fun syntaxErrors(source: String): String? {
    val file = PsiEnv.parse("CompiledAdapterCheck.kt", source)
    val err = file.collectDescendantsOfType<PsiErrorElement>().firstOrNull() ?: return null
    return "${err.errorDescription} at `${err.parent?.text?.take(60)}`"
  }

  /**
   * True when [typeRef] (a function type, maybe nullable) carries `@Composable`
   * by its annotation's SHORT name — `@Composable` and
   * `@androidx.compose.runtime.Composable` alike — and not an annotation
   * inside the function type's own parameters or return type.
   */
  private fun isComposableType(typeRef: KtTypeReference, fnType: KtFunctionType): Boolean {
    fun inside(e: PsiElement): Boolean {
      var p: PsiElement? = e
      while (p != null && p != typeRef) {
        if (p == fnType.parameterList || p == fnType.returnTypeReference || p == fnType.receiverTypeReference) return true
        p = p.parent
      }
      return false
    }
    return typeRef.collectDescendantsOfType<KtAnnotationEntry>()
      .any { it.shortName?.asString() == "Composable" && !inside(it) }
  }

  private sealed interface Either {
    class Ok(val value: Adapter) : Either
    class No(val reason: String) : Either
  }

  private val SCALARS = mapOf("String" to "string", "Int" to "int", "Boolean" to "bool", "Double" to "double")
  private val LIT_TAG = mapOf("String" to "s", "Int" to "i", "Boolean" to "b", "Double" to "d")

  private fun adapterFor(
    fn: KtNamedFunction,
    name: String,
    pkg: String?,
    outPkg: String,
    valName: String,
    tokenDefaults: Map<String, Any?>,
    sameModule: Boolean,
  ): Either {
    if (name in RESERVED_COMPONENT_NAMES || widgetSpec(name) != null) return Either.No("name collides with a reserved/primitive widget")
    if (!isPlainIdentifier(name)) return Either.No("its name is not a plain Kotlin identifier")
    // The import-alias scheme (`Name__cN`): a component named like an alias
    // could be called in place of the one the alias stands for.
    if (Regex(".*__c\\d+").matches(name)) return Either.No("its name looks like a generated import alias (`…__c<N>`)")
    // Annotations other than @Composable can make the CALL not compile
    // (@RequiresOptIn markers, @Deprecated(level = ERROR), …): refused rather
    // than risk a file that freezes every preview build.
    fn.annotationEntries.map { it.shortName?.asString() }.filter { it !in ALLOWED_ANNOTATIONS }.takeIf { it.isNotEmpty() }?.let {
      return Either.No("it carries annotation(s) ${it.joinToString { a -> "@$a" }} the adapter cannot satisfy")
    }
    if (fn.contextReceiverList != null || fn.modifierList?.contextReceiverLists?.isNotEmpty() == true) {
      return Either.No("it has context parameters")
    }
    // A root-package component is called by its bare name; it must not shadow
    // the names the generated file itself uses.
    if (pkg == null && (name in setOf("dev", "kotlin", "call", valName) || Regex("p\\d+").matches(name))) {
      return Either.No("its name would shadow a name the generated adapters use")
    }
    if (fn.hasModifier(KtTokens.PRIVATE_KEYWORD)) return Either.No("private: the editor cannot call it")
    if (fn.hasModifier(KtTokens.INTERNAL_KEYWORD) && !sameModule) return Either.No("internal: the editor's module cannot call it")
    if (fn.receiverTypeReference != null) return Either.No("has a receiver (`${fn.receiverTypeReference!!.text}.`)")
    if (fn.typeParameters.isNotEmpty()) return Either.No("has type parameters")
    if (pkg == null && outPkg.isNotEmpty()) return Either.No("is in the default package")
    val decode = mutableListOf<String>()
    val args = mutableListOf<String>()
    var slots = 0
    fun local() = "p${decode.size}"
    for (p in fn.valueParameters) {
      val pName = p.name ?: return Either.No("has an unnamed parameter")
      if (p.isVarArg) return Either.No("parameter '$pName' is a vararg")
      if (p.annotationEntries.isNotEmpty()) return Either.No("parameter '$pName' carries annotations")
      val typeRef = p.typeReference ?: return Either.No("parameter '$pName' has no type")
      val typeText = typeRef.text.trim()
      val nullable = typeRef.typeElement is KtNullableType
      val fnType = typeRef.typeElement as? KtFunctionType
        ?: (typeRef.typeElement as? KtNullableType)?.innerType as? KtFunctionType
      val default = p.defaultValue?.text?.trim()
      val q = kotlinStringLiteral(pName)
      val arg = quote(pName)
      val shownType = typeText.replace(Regex("\\s+"), " ")
      fun omitOr(reason: String): Either? =
        if (default != null) null else Either.No("parameter `$pName: $shownType` $reason and has no default")
      when {
        pName == "modifier" && isModifierType(typeText) -> {
          // The canvas passes the instance's modifiers (+ selection tag). A
          // default other than the empty Modifier would be lost when the
          // instance passes none, so the preview would not match the device.
          if (default != null && default != "Modifier" && default != "dev.keliver.Modifier" && default != "null") {
            return Either.No("its `modifier` default `$default` cannot be reproduced (only `= Modifier` is supported)")
          }
          args += "modifier = call.modifier"
        }
        fnType != null && isComposableType(typeRef, fnType) -> {
          val plainSlot = !nullable && default == null && fnType.parameters.isEmpty() &&
            fnType.receiverTypeReference == null && fnType.returnTypeReference?.text == "Unit"
          if (plainSlot) {
            slots++
            val l = local(); decode += "val $l = call.content()"; args += "$arg = $l"
          } else {
            omitOr("is a content slot the portal does not support")?.let { return it }
          }
        }
        fnType != null -> {
          val ret = fnType.returnTypeReference?.text
          val arity = fnType.parameters.size
          if ((ret != null && ret != "Unit") || arity > 1 || fnType.receiverTypeReference != null) {
            omitOr("is a function type the portal cannot wire")?.let { return it }
          } else if (arity == 0) {
            val l = local()
            decode += "val $l = call.${if (nullable) "eventOrNull" else "event"}($q)"
            args += "$arg = $l"
          } else {
            // `{ v -> f(v) }` takes its parameter type from the component's
            // signature, so no import of a custom event type is needed.
            val l = local()
            decode += "val $l = call.${if (nullable) "event1OrNull" else "event1"}<Any?>($q)"
            args += if (nullable) "$arg = if ($l == null) null else { v -> $l(v) }" else "$arg = { v -> $l(v) }"
          }
        }
        typeText.removeSuffix("?").trim() in SCALARS -> {
          val base = typeText.removeSuffix("?").trim()
          val getter = SCALARS.getValue(base)
          val l = local()
          val expr = when {
            default == null -> if (nullable) "call.${getter}OrNull($q, null)" else "call.$getter($q)"
            default == "null" && nullable -> "call.${getter}OrNull($q, null)"
            literalOf(default, base) != null -> if (nullable) "call.${getter}OrNull($q, $default)" else "call.$getter($q, $default)"
            tokenDefaults[pName]?.let { valueText(it, base) } != null -> {
              val v = valueText(tokenDefaults.getValue(pName)!!, base)!!
              // The design token's value at generation time (`$default`).
              if (nullable) "call.${getter}OrNull($q, $v)" else "call.$getter($q, $v)"
            }
            else -> {
              decode += "call.requireSet($q, ${kotlinStringLiteral(default)})"
              if (nullable) "call.${getter}OrNull($q, null)" else "call.$getter($q)"
            }
          }
          decode += "val $l = $expr"
          args += "$arg = $l"
        }
        else -> omitOr("has a type the portal cannot set")?.let { return it }
      }
    }
    if (slots > 1) return Either.No("has more than one content slot")
    if (slots == 0) decode.add(0, "call.noContent()")
    return Either.Ok(Adapter(name, pkg, decode, args))
  }

  /** [text] when it is a literal of Kotlin type [base]; null otherwise. */
  private fun literalOf(text: String, base: String): String? {
    // Digit separators (`-16_745_729`) and hex Ints (`0xFF1A73E8.toInt()` is
    // not one; `0x7F000000` is) are literals the compiler reads as written.
    if (base == "Int" && Regex("^-?(\\d[\\d_]*|0[xX][0-9a-fA-F_]+)$").matches(text)) {
      val digits = text.replace("_", "")
      val v = if (digits.removePrefix("-").startsWith("0x", ignoreCase = true)) {
        digits.removePrefix("-").drop(2).toLongOrNull(16)?.let { if (digits.startsWith("-")) -it else it }
      } else {
        digits.toLongOrNull()
      }
      return text.takeIf { v != null && v in Int.MIN_VALUE..Int.MAX_VALUE }
    }
    val lit = Recognizer.parseLiteral(text) ?: return null
    return if (lit.tag == LIT_TAG[base]) text else null
  }

  private fun valueText(v: Any, base: String): String? = when {
    base == "String" && v is String -> kotlinStringLiteral(v)
    base == "Int" && v is Int -> v.toString()
    base == "Boolean" && v is Boolean -> v.toString()
    base == "Double" && v is Double -> v.toString().let { if ('.' in it || 'E' in it) it else "$it.0" }
    base == "Double" && v is Int -> "${v}.0"
    else -> null
  }

  private fun renderAdapter(a: Adapter): String = buildString {
    appendLine("  ${kotlinStringLiteral(a.name)} to dev.keliver.portal.render.CompiledComponent { call ->")
    a.decode.forEach { appendLine("    $it") }
    if (a.args.isEmpty()) {
      appendLine("    call.compose { ${a.alias}() }")
    } else {
      appendLine("    call.compose {")
      appendLine("      ${a.alias}(")
      a.args.forEach { appendLine("        $it,") }
      appendLine("      )")
      appendLine("    }")
    }
    appendLine("  },")
  }

  private fun render(
    adapters: Map<String, Adapter>,
    refused: Map<String, String>,
    packageName: String,
    valName: String,
    sameModule: Boolean,
    hint: String,
  ): String = buildString {
    appendLine(GENERATED_HEADER)
    appendLine("// ${hint.replace(Regex("\\s+"), " ")}")
    if (packageName.isNotEmpty()) {
      appendLine("package ${quotePackage(packageName)}")
      appendLine()
    }
    // Components are imported under an alias, so none can clash with another
    // import or with a name this file uses; the render types are written
    // fully qualified for the same reason.
    val imports = adapters.values.filter { it.pkg != null }
      .map { "${quotePackage(it.pkg!!)}.${quote(it.name)} as ${it.alias}" }
    imports.forEach { appendLine("import $it") }
    if (imports.isNotEmpty()) appendLine()
    appendLine("/**")
    appendLine(" * Compiled component preview adapters (docs/COMPILED_COMPONENT_PREVIEW.md):")
    appendLine(" * the canvas draws an OPAQUE component listed here with the app's own")
    appendLine(" * compiled composable. Return it from AppPreviewEntry.compiledComponents.")
    if (refused.isNotEmpty()) {
      appendLine(" *")
      appendLine(" * Refused (these keep their placeholder):")
      refused.forEach { (n, r) -> appendLine(" *  - $n: ${r.replace("*/", "* /").replace(Regex("\\s+"), " ")}") }
    }
    appendLine(" */")
    val visibility = if (sameModule) "internal" else "public"
    val type = "kotlin.collections.Map<kotlin.String, dev.keliver.portal.render.CompiledComponent>"
    if (adapters.isEmpty()) {
      appendLine("$visibility val $valName: $type = kotlin.collections.emptyMap()")
      return@buildString
    }
    appendLine("$visibility val $valName: $type = kotlin.collections.mapOf(")
    adapters.values.forEach { append(renderAdapter(it)) }
    appendLine(")")
  }

  /** The first line of every generated adapters file (the relay refuses to overwrite any other file). */
  const val GENERATED_HEADER: String = "// GENERATED by portal-ingest CompiledAdapterGenerator — do not edit."
}
