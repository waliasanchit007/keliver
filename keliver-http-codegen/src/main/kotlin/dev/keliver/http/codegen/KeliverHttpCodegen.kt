/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.http.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Reads `@KeliverApi`-annotated interfaces and emits a companion
 * `*Impl(KeliverHttp)` class per interface backed by the typed
 * helpers on `dev.keliver.http.KeliverHttp`.
 *
 * Design locked in `docs/HTTP_API_CODEGEN_DESIGN.md`. Phase 2 of
 * issue #18.
 */
public class KeliverHttpCodegen(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
) : SymbolProcessor {

  override fun process(resolver: Resolver): List<KSAnnotated> {
    val symbols = resolver
      .getSymbolsWithAnnotation(FqNames.KELIVER_API)
      .filterIsInstance<KSClassDeclaration>()

    val deferred = mutableListOf<KSAnnotated>()

    symbols.forEach { ksClass ->
      // Defer if any referenced type still has unresolved references
      // — KSP runs in rounds; we'll see it again next round.
      if (!ksClass.validate()) {
        deferred.add(ksClass)
        return@forEach
      }
      try {
        processInterface(ksClass)
      } catch (e: ProcessingException) {
        logger.error(e.message.orEmpty(), e.symbol)
      } catch (e: Exception) {
        logger.error(
          "Unhandled error generating @KeliverApi impl for " +
            "${ksClass.qualifiedName?.asString()}: ${e.message}",
          ksClass,
        )
      }
    }

    return deferred
  }

  // --- per-interface processing ---

  private fun processInterface(ksClass: KSClassDeclaration) {
    if (ksClass.classKind != ClassKind.INTERFACE) {
      throw ProcessingException(
        "@KeliverApi can only be applied to interfaces. " +
          "Found: ${ksClass.classKind.name.lowercase()} ${ksClass.qualifiedName?.asString()}.",
        ksClass,
      )
    }

    val packageName = ksClass.packageName.asString()
    val interfaceName = ksClass.simpleName.asString()
    val implName = "${interfaceName}Impl"

    val interfaceType = ksClass.toClassName()

    val httpProp = PropertySpec.builder("http", FqNames.KELIVER_HTTP)
      .initializer("http")
      .addModifiers(KModifier.PRIVATE)
      .build()

    val ctorSpec = FunSpec.constructorBuilder()
      .addParameter("http", FqNames.KELIVER_HTTP)
      .build()

    val typeSpec = TypeSpec.classBuilder(implName)
      .addModifiers(KModifier.PUBLIC)
      .addSuperinterface(interfaceType)
      .primaryConstructor(ctorSpec)
      .addProperty(httpProp)
      .apply {
        ksClass.getAllFunctions()
          .filter { it.simpleName.asString() !in OBJECT_INHERITED_METHODS }
          .filter { it.isAbstract }
          .forEach { fn ->
            addFunction(emitMethod(ksClass, fn))
          }
      }
      .build()

    val file = FileSpec.builder(packageName, implName)
      .addType(typeSpec)
      .build()

    file.writeTo(
      codeGenerator = codeGenerator,
      dependencies = Dependencies(
        aggregating = false,
        // Containing file is non-null for a top-level interface
        // declaration; KSP-defensive null cast.
        sources = arrayOf(ksClass.containingFile ?: return),
      ),
    )
  }

  // --- per-function emission ---

  private fun emitMethod(
    iface: KSClassDeclaration,
    fn: KSFunctionDeclaration,
  ): FunSpec {
    val methodName = fn.simpleName.asString()

    if (Modifier.SUSPEND !in fn.modifiers) {
      throw ProcessingException(
        "@KeliverApi method `${iface.qualifiedName?.asString()}.$methodName` " +
          "must be `suspend`. The codegen only routes through the suspending " +
          "helpers on KeliverHttp (get / post / put / delete).",
        fn,
      )
    }

    val httpAnnotations = HTTP_METHOD_FQNS.mapNotNull { fqn -> fn.findAnnotation(fqn) }
    if (httpAnnotations.size != 1) {
      val present = httpAnnotations.map { it.shortName.asString() }
      throw ProcessingException(
        "@KeliverApi method `${iface.qualifiedName?.asString()}.$methodName` " +
          "must have exactly one HTTP-method annotation " +
          "(@GET, @POST, @PUT, or @DELETE). Found: $present.",
        fn,
      )
    }
    val httpAnnotation = httpAnnotations.single()
    val httpVerb = httpAnnotation.shortName.asString() // GET / POST / …
    val pathTemplate = httpAnnotation.firstStringArgument()

    val params = fn.parameters.map { ksParam -> parseParameter(iface, fn, ksParam) }

    // Validate @Body only on POST / PUT.
    val bodyParams = params.filter { it.kind is ParamKind.Body }
    if (bodyParams.isNotEmpty() && httpVerb !in BODY_VERBS) {
      throw ProcessingException(
        "@Body is only valid on @POST or @PUT methods; " +
          "`${iface.qualifiedName?.asString()}.$methodName` is @$httpVerb.",
        fn,
      )
    }
    if (bodyParams.size > 1) {
      throw ProcessingException(
        "@KeliverApi method `${iface.qualifiedName?.asString()}.$methodName` " +
          "has multiple @Body parameters; only one is allowed.",
        fn,
      )
    }
    if (httpVerb in BODY_VERBS && bodyParams.isEmpty()) {
      throw ProcessingException(
        "@$httpVerb method `${iface.qualifiedName?.asString()}.$methodName` " +
          "has no @Body parameter. Use the raw KeliverHttp.execute API for " +
          "body-less POST/PUT, or add a @Body parameter.",
        fn,
      )
    }

    // Validate path template ↔ @Path parameters.
    val pathPlaceholders = PATH_PLACEHOLDER_REGEX.findAll(pathTemplate)
      .map { it.groupValues[1] }
      .toList()
    val pathParamNames = params.mapNotNull { (it.kind as? ParamKind.Path)?.name }
    val missingFromParams = pathPlaceholders - pathParamNames.toSet()
    val unusedPathParams = pathParamNames - pathPlaceholders.toSet()
    if (missingFromParams.isNotEmpty()) {
      throw ProcessingException(
        "Path template `$pathTemplate` references {$missingFromParams} but " +
          "`${iface.qualifiedName?.asString()}.$methodName` has no matching @Path " +
          "parameter.",
        fn,
      )
    }
    if (unusedPathParams.isNotEmpty()) {
      throw ProcessingException(
        "Method `${iface.qualifiedName?.asString()}.$methodName` has @Path " +
          "parameters $unusedPathParams that don't appear in the path template " +
          "`$pathTemplate`.",
        fn,
      )
    }

    val returnTypeName = fn.returnType?.resolve()?.toTypeName()
      ?: throw ProcessingException(
        "Could not resolve return type for " +
          "`${iface.qualifiedName?.asString()}.$methodName`.",
        fn,
      )

    val body = buildMethodBody(httpVerb, pathTemplate, params, returnTypeName)

    return FunSpec.builder(methodName)
      .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
      .returns(returnTypeName)
      .apply {
        params.forEach { p ->
          addParameter(
            ParameterSpec.builder(p.name, p.ksType.toTypeName()).build(),
          )
        }
      }
      .addCode(body)
      .build()
  }

  private fun buildMethodBody(
    httpVerb: String,
    pathTemplate: String,
    params: List<Param>,
    returnType: TypeName,
  ): CodeBlock {
    val builder = CodeBlock.builder()

    val pathExpr = buildPathExpression(pathTemplate, params)
    val queryParams = params.filter { it.kind is ParamKind.Query }
    val headerParams = params.filter { it.kind is ParamKind.Header }
    val headerMapParam = params.firstOrNull { it.kind is ParamKind.HeaderMap }
    val bodyParam = params.firstOrNull { it.kind is ParamKind.Body }

    val isUnit = returnType.toString() == "kotlin.Unit"

    val callTarget = when (httpVerb) {
      "GET" -> if (isUnit) "deleteUnit" else "get" // GET returning Unit is unusual; use get<Unit>
      "POST" -> "post"
      "PUT" -> "put"
      "DELETE" -> if (isUnit) "deleteUnit" else "delete"
      else -> error("unreachable")
    }

    // For GET<Unit> there's no KeliverHttp helper; emit a raw get<Unit>.
    val methodCall = if (httpVerb == "GET" && isUnit) "get<kotlin.Unit>" else callTarget

    builder.add("return http.$methodCall(\n")
    builder.indent()
    builder.add("path = $pathExpr,\n")
    if (httpVerb in BODY_VERBS) {
      bodyParam?.let { builder.add("body = %N,\n", it.name) }
    }
    if (queryParams.isNotEmpty()) {
      builder.add("query = ")
      builder.add(buildStringMapLiteral(queryParams) { name, p -> "put(%S, %N.toString())" to listOf(name, p) })
      builder.add(",\n")
    }
    if (headerParams.isNotEmpty() || headerMapParam != null) {
      builder.add("headers = ")
      builder.add(buildHeadersMapLiteral(headerParams, headerMapParam))
      builder.add(",\n")
    }
    builder.unindent()
    builder.add(")\n")

    return builder.build()
  }

  /**
   * Build a `buildMap<String, String> { … }` literal that combines
   * single [singles] (`@Header` parameters) and an optional [spread]
   * (`@HeaderMap` parameter). The spread's nullable-value entries are
   * filtered.
   */
  private fun buildHeadersMapLiteral(
    singles: List<Param>,
    spread: Param?,
  ): CodeBlock {
    val b = CodeBlock.builder()
    b.add("buildMap<%T, %T> {\n", String::class, String::class)
    b.indent()
    singles.forEach { p ->
      val k = p.kind as ParamKind.Header
      if (p.isNullable) b.add("if (%N != null) ", p.name)
      b.add("put(%S, %N.toString())\n", k.name, p.name)
    }
    if (spread != null) {
      if (spread.isNullableValueMap) {
        // Map<String, String?> — putAll a filtered view to avoid
        // ever stashing a null in the wire envelope.
        b.add(
          "%N.forEach·{·(k,·v)·->·if·(v·!=·null)·put(k,·v)·}\n",
          spread.name,
        )
      } else {
        b.add("putAll(%N)\n", spread.name)
      }
    }
    b.unindent()
    b.add("}")
    return b.build()
  }

  // --- helpers ---

  private fun parseParameter(
    iface: KSClassDeclaration,
    fn: KSFunctionDeclaration,
    ksParam: KSValueParameter,
  ): Param {
    val name = ksParam.name?.asString()
      ?: throw ProcessingException("anonymous parameter on $fn", fn)
    val pathAnn = ksParam.findAnnotation(FqNames.PATH)
    val queryAnn = ksParam.findAnnotation(FqNames.QUERY)
    val bodyAnn = ksParam.findAnnotation(FqNames.BODY)
    val headerAnn = ksParam.findAnnotation(FqNames.HEADER)
    val headerMapAnn = ksParam.findAnnotation(FqNames.HEADER_MAP)
    val annCount = listOf(pathAnn, queryAnn, bodyAnn, headerAnn, headerMapAnn).count { it != null }

    if (annCount != 1) {
      throw ProcessingException(
        "Parameter `$name` on `${iface.qualifiedName?.asString()}.${fn.simpleName.asString()}` " +
          "must have exactly one of @Path / @Query / @Body / @Header / @HeaderMap; " +
          "found $annCount.",
        ksParam,
      )
    }

    val kind = when {
      pathAnn != null -> ParamKind.Path(pathAnn.firstStringArgument())
      queryAnn != null -> ParamKind.Query(queryAnn.firstStringArgument())
      bodyAnn != null -> ParamKind.Body
      headerAnn != null -> ParamKind.Header(headerAnn.firstStringArgument())
      headerMapAnn != null -> ParamKind.HeaderMap
      else -> error("unreachable — annCount guard above")
    }

    val resolvedType = ksParam.type.resolve()
    var isNullableValueMap = false

    if (kind is ParamKind.HeaderMap) {
      // Validate the parameter type is Map<String, String> or
      // Map<String, String?>. Anything else fails the build with a
      // pointer at the canonical shape.
      val classFqn = resolvedType.declaration.qualifiedName?.asString()
      val typeArgs = resolvedType.arguments
      val keyType = typeArgs.getOrNull(0)?.type?.resolve()
      val valueType = typeArgs.getOrNull(1)?.type?.resolve()
      val keyFqn = keyType?.declaration?.qualifiedName?.asString()
      val valueFqn = valueType?.declaration?.qualifiedName?.asString()
      val mapClasses = setOf("kotlin.collections.Map", "kotlin.collections.MutableMap")
      val isMap = classFqn in mapClasses
      val isStringKey = keyFqn == "kotlin.String" && keyType?.isMarkedNullable == false
      val isStringValue = valueFqn == "kotlin.String"
      if (!isMap || !isStringKey || !isStringValue) {
        throw ProcessingException(
          "@HeaderMap parameter `$name` on " +
            "`${iface.qualifiedName?.asString()}.${fn.simpleName.asString()}` " +
            "must be of type `Map<String, String>` or `Map<String, String?>`. " +
            "Found: $resolvedType.",
          ksParam,
        )
      }
      isNullableValueMap = valueType.isMarkedNullable
    }

    return Param(
      name = name,
      ksType = resolvedType,
      isNullable = resolvedType.isMarkedNullable,
      isNullableValueMap = isNullableValueMap,
      kind = kind,
    )
  }

  private fun buildPathExpression(template: String, params: List<Param>): CodeBlock {
    if (PATH_PLACEHOLDER_REGEX.containsMatchIn(template)) {
      // Substitute each `{name}` with $name (Kotlin interp). KotlinPoet
      // requires literal $ in raw strings; build a String concatenation
      // CodeBlock instead.
      val sb = StringBuilder()
      var last = 0
      for (m in PATH_PLACEHOLDER_REGEX.findAll(template)) {
        sb.append(template, last, m.range.first)
        val placeholder = m.groupValues[1]
        // Path values get .toString() so non-String types (Int, UUID, etc.)
        // serialize via their natural string form.
        sb.append("\${").append(placeholder).append(".toString()}")
        last = m.range.last + 1
      }
      sb.append(template, last, template.length)
      return CodeBlock.of("%P", sb.toString())
    }
    return CodeBlock.of("%S", template)
  }

  /**
   * Build a `buildMap<String, String> { … }` literal that filters out
   * null parameter values. Each [params] entry becomes a guarded
   * `put(name, value.toString())` statement.
   */
  private fun buildStringMapLiteral(
    params: List<Param>,
    statement: (name: String, p: String) -> Pair<String, List<Any>>,
  ): CodeBlock {
    val b = CodeBlock.builder()
    b.add("buildMap<%T, %T> {\n", String::class, String::class)
    b.indent()
    params.forEach { p ->
      val wireName = when (val k = p.kind) {
        is ParamKind.Query -> k.name
        is ParamKind.Header -> k.name
        else -> error("buildStringMapLiteral got non-string-map param: ${p.kind}")
      }
      val (fmt, args) = statement(wireName, p.name)
      if (p.isNullable) {
        b.add("if (%N != null) ", p.name)
      }
      b.add(fmt, *args.toTypedArray())
      b.add("\n")
    }
    b.unindent()
    b.add("}")
    return b.build()
  }

  // --- private types ---

  private data class Param(
    val name: String,
    val ksType: com.google.devtools.ksp.symbol.KSType,
    val isNullable: Boolean,
    /**
     * `true` if this parameter is a `Map<String, String?>` — the
     * codegen emits a `forEach { (k, v) -> if (v != null) put(k, v) }`
     * spread instead of `putAll(map)`.
     */
    val isNullableValueMap: Boolean,
    val kind: ParamKind,
  )

  private sealed interface ParamKind {
    data class Path(val name: String) : ParamKind
    data class Query(val name: String) : ParamKind
    data object Body : ParamKind
    data class Header(val name: String) : ParamKind
    data object HeaderMap : ParamKind
  }

  /** Diagnostic exception thrown from validation paths. */
  private class ProcessingException(
    override val message: String,
    val symbol: KSAnnotated,
  ) : RuntimeException(message)

  private companion object {
    val HTTP_METHOD_FQNS = listOf(FqNames.GET, FqNames.POST, FqNames.PUT, FqNames.DELETE)
    val BODY_VERBS = setOf("POST", "PUT")
    val PATH_PLACEHOLDER_REGEX = Regex("""\{(\w+)\}""")

    /** Methods inherited from `Any` that we skip when iterating an interface. */
    val OBJECT_INHERITED_METHODS = setOf("equals", "hashCode", "toString")

    /**
     * Find an annotation on a [KSAnnotated] by fully-qualified name.
     * KSP exposes annotations as a sequence; this is a small helper
     * around the common lookup.
     */
    fun KSAnnotated.findAnnotation(fqn: String): KSAnnotation? =
      annotations.firstOrNull {
        val decl = it.annotationType.resolve().declaration
        decl.qualifiedName?.asString() == fqn
      }

    /** Read the first positional argument of an annotation as a `String`. */
    fun KSAnnotation.firstStringArgument(): String =
      arguments.firstOrNull()?.value as? String
        ?: error(
          "expected first string argument on @${shortName.asString()}, " +
            "got ${arguments.firstOrNull()?.value?.javaClass}",
        )
  }
}
