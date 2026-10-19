package dev.keliver.portal.ingest

import dev.keliver.portal.RESERVED_COMPONENT_NAMES
import dev.keliver.portal.isModifierType
import dev.keliver.portal.kotlinStringLiteral
import dev.keliver.portal.widgetSpec
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType

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
  ): Output {
    val adapters = sortedMapOf<String, Adapter>()
    val refused = sortedMapOf<String, String>()
    for (f in files) {
      val file = PsiEnv.parse(f.path, LineEndings.toLf(f.text))
      // The recognizer's rule: a component file's first @Composable function.
      val fn = file.declarations.filterIsInstance<KtNamedFunction>()
        .firstOrNull { d -> d.annotationEntries.any { it.shortName?.asString() == "Composable" } } ?: continue
      val name = fn.name ?: continue
      val spec = Recognizer.recognizeComponent(f.path, f.text, tokens = tokens)?.spec
      val pkg = file.packageFqName.asString().takeIf { it.isNotEmpty() }
      when (val r = adapterFor(fn, name, pkg, packageName, spec?.defaults.orEmpty(), sameModule)) {
        is Either.Ok -> if (name in adapters) refused[name] = "declared twice" else adapters[name] = r.value
        is Either.No -> refused[name] = r.reason
      }
    }
    refused.keys.forEach { adapters.remove(it) }
    return Output(render(adapters, refused, packageName, valName, sameModule, regenerateHint), adapters.keys.toList(), refused)
  }

  private class Adapter(val name: String, val pkg: String?, val decode: List<String>, val args: List<String>)

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
    tokenDefaults: Map<String, Any?>,
    sameModule: Boolean,
  ): Either {
    if (name in RESERVED_COMPONENT_NAMES || widgetSpec(name) != null) return Either.No("name collides with a reserved/primitive widget")
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
      val typeRef = p.typeReference ?: return Either.No("parameter '$pName' has no type")
      val typeText = typeRef.text.trim()
      val nullable = typeRef.typeElement is KtNullableType
      val fnType = typeRef.typeElement as? KtFunctionType
        ?: (typeRef.typeElement as? KtNullableType)?.innerType as? KtFunctionType
      val default = p.defaultValue?.text?.trim()
      val q = kotlinStringLiteral(pName)
      fun omitOr(reason: String): Either? =
        if (default != null) null else Either.No("parameter `$pName: $typeText` $reason and has no default")
      when {
        pName == "modifier" && isModifierType(typeText) -> args += "modifier = call.modifier"
        fnType != null && typeText.contains("@Composable") -> {
          val plainSlot = !nullable && default == null && fnType.parameters.isEmpty() &&
            fnType.receiverTypeReference == null && fnType.returnTypeReference?.text == "Unit"
          if (plainSlot) {
            slots++
            val l = local(); decode += "val $l = call.content()"; args += "$pName = $l"
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
            args += "$pName = $l"
          } else {
            // `{ v -> f(v) }` takes its parameter type from the component's
            // signature, so no import of a custom event type is needed.
            val l = local()
            decode += "val $l = call.${if (nullable) "event1OrNull" else "event1"}<Any?>($q)"
            args += if (nullable) "$pName = if ($l == null) null else { v -> $l(v) }" else "$pName = { v -> $l(v) }"
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
          args += "$pName = $l"
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

  private fun render(
    adapters: Map<String, Adapter>,
    refused: Map<String, String>,
    packageName: String,
    valName: String,
    sameModule: Boolean,
    hint: String,
  ): String = buildString {
    appendLine("// GENERATED by portal-ingest CompiledAdapterGenerator — do not edit.")
    appendLine("// $hint")
    if (packageName.isNotEmpty()) {
      appendLine("package $packageName")
      appendLine()
    }
    val imports = sortedSetOf("dev.keliver.portal.render.CompiledComponent")
    adapters.values.forEach { a -> if (a.pkg != null && a.pkg != packageName) imports += "${a.pkg}.${a.name}" }
    imports.forEach { appendLine("import $it") }
    appendLine()
    appendLine("/**")
    appendLine(" * Compiled component preview adapters (docs/COMPILED_COMPONENT_PREVIEW.md):")
    appendLine(" * the canvas draws an OPAQUE component listed here with the app's own")
    appendLine(" * compiled composable. Return it from AppPreviewEntry.compiledComponents.")
    if (refused.isNotEmpty()) {
      appendLine(" *")
      appendLine(" * Refused (these keep their placeholder):")
      refused.forEach { (n, r) -> appendLine(" *  - $n: $r") }
    }
    appendLine(" */")
    val visibility = if (sameModule) "internal" else "public"
    if (adapters.isEmpty()) {
      appendLine("$visibility val $valName: Map<String, CompiledComponent> = emptyMap()")
      return@buildString
    }
    appendLine("$visibility val $valName: Map<String, CompiledComponent> = mapOf(")
    for (a in adapters.values) {
      appendLine("  ${kotlinStringLiteral(a.name)} to CompiledComponent { call ->")
      a.decode.forEach { appendLine("    $it") }
      if (a.args.isEmpty()) {
        appendLine("    call.compose { ${a.name}() }")
      } else {
        appendLine("    call.compose {")
        appendLine("      ${a.name}(")
        a.args.forEach { appendLine("        $it,") }
        appendLine("      )")
        appendLine("    }")
      }
      appendLine("  },")
    }
    appendLine(")")
  }
}
