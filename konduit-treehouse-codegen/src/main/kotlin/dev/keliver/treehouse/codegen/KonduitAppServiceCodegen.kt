/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.treehouse.codegen

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Reads `@KonduitAppService`-annotated interfaces and emits a
 * `Generated<Name>Adapter` open class per interface — the full
 * Zipline #765 manual-adapter workaround, lifted out of every
 * adopter's tree.
 *
 * Closes the second half of `docs/KNOWN_BUGS.md` U12. The
 * first-half KonduitAppServiceAdapter helper (caliclan.5) cut
 * adopter cost from ~95 LoC to ~70; this processor cuts it to
 * ~5 (a companion-object stub on the interface).
 */
public class KonduitAppServiceCodegen(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
) : SymbolProcessor {

  override fun process(resolver: Resolver): List<KSAnnotated> {
    val annotationFqn = FqNames.KonduitAppService.canonicalName
    val rawSymbols = resolver.getSymbolsWithAnnotation(annotationFqn).toList()
    val symbols = rawSymbols.filterIsInstance<KSClassDeclaration>()

    // Debug visibility — adopters set `ksp.verbose=true` in
    // gradle.properties to see this. Helpful when the processor
    // appears to do nothing (typically: annotation FQN typo or the
    // commonMain ksp configuration is missing the dep).
    logger.info(
      "KonduitAppServiceCodegen scanning '$annotationFqn' — " +
        "found ${rawSymbols.size} raw symbol(s), " +
        "${symbols.size} class declaration(s)",
    )

    symbols.forEach { ksClass ->
      // NOTE: we deliberately do NOT call `ksClass.validate()` here.
      //
      // The adopter pattern for @KonduitAppService inserts a
      // companion-object Adapter that forward-references the
      // `GeneratedXxxAdapter` class we're about to emit:
      //
      //   @KonduitAppService
      //   interface MyAppService : AppService {
      //     companion object {
      //       internal class Adapter(...) : GeneratedMyAppServiceAdapter(...)
      //     }
      //   }
      //
      // `validate()` walks the symbol's full tree including the
      // companion, sees the unresolved `GeneratedMyAppServiceAdapter`,
      // and returns false. If we defer on that, we never generate —
      // because the symbol's validation can't pass without us
      // generating, and we won't generate until validation passes.
      //
      // The processor only reads the user interface's OWN structure
      // (declared methods, immediate supertypes, return types — all
      // resolvable types Kotlin already understands). It doesn't
      // touch the companion's body. So skipping validate() is safe;
      // there's no circular dependency the deferred-symbol mechanism
      // would protect us from.
      try {
        processInterface(ksClass)
      } catch (e: ProcessingException) {
        logger.error(e.message.orEmpty(), e.symbol)
      } catch (e: Exception) {
        logger.error(
          "Unhandled error generating @KonduitAppService adapter for " +
            "${ksClass.qualifiedName?.asString()}: ${e.message}",
          ksClass,
        )
      }
    }

    return emptyList()
  }

  // --- per-interface processing ---

  private fun processInterface(ksClass: KSClassDeclaration) {
    if (ksClass.classKind != ClassKind.INTERFACE) {
      throw ProcessingException(
        "@KonduitAppService can only be applied to interfaces. " +
          "Found: ${ksClass.classKind.name.lowercase()} ${ksClass.qualifiedName?.asString()}.",
        ksClass,
      )
    }

    val appServiceFqn = FqNames.AppService.canonicalName
    val extendsAppService = ksClass.getAllSuperTypes().any { type ->
      type.declaration.qualifiedName?.asString() == appServiceFqn
    }
    if (!extendsAppService) {
      throw ProcessingException(
        "@KonduitAppService requires the annotated interface to " +
          "extend `$appServiceFqn` (directly or transitively). " +
          "Found ${ksClass.qualifiedName?.asString()} with supertypes: " +
          ksClass.superTypes.joinToString { it.resolve().declaration.qualifiedName?.asString() ?: "?" },
        ksClass,
      )
    }

    val packageName = ksClass.packageName.asString()
    val interfaceName = ksClass.simpleName.asString()
    val generatedName = "Generated${interfaceName}Adapter"
    val interfaceTypeName = ksClass.toClassName()
    val serialNameFqn = ksClass.qualifiedName?.asString().orEmpty()

    val members = collectMembers(ksClass)
    if (members.isEmpty()) {
      // Vanishingly unlikely (every AppService inherits appLifecycle
      // + close from the supertype hierarchy), but handle it for
      // diagnostic clarity.
      throw ProcessingException(
        "@KonduitAppService interface ${ksClass.qualifiedName?.asString()} " +
          "has no instance methods or properties — nothing to generate.",
        ksClass,
      )
    }

    val file = FileSpec.builder(packageName, generatedName)
      .addFileComment(
        "Generated by konduit-treehouse-codegen from @KonduitAppService " +
          "on $serialNameFqn. Do not edit by hand — re-run the build to " +
          "regenerate after schema changes.",
      )
      // The generated body references `KonduitOutboundCallHandler` +
      // `KonduitOutboundService` (typealiases for Zipline-internal
      // types). Kotlin checks `internal` visibility at every use
      // site, regardless of the alias — so the generated file needs
      // these two suppressions even though the konduit-treehouse
      // base file already has them. Pinned to exactly the two
      // entries used; the wider 7-entry block on the manual
      // workaround template isn't required because we control the
      // shape of the generated declarations.
      .addAnnotation(
        com.squareup.kotlinpoet.AnnotationSpec.builder(Suppress::class)
          .addMember("%S", "INVISIBLE_MEMBER")
          .addMember("%S", "INVISIBLE_REFERENCE")
          .useSiteTarget(com.squareup.kotlinpoet.AnnotationSpec.UseSiteTarget.FILE)
          .build(),
      )
      .addType(buildAdapterClass(interfaceTypeName, generatedName, serialNameFqn, members, ksClass))
      .build()

    file.writeTo(
      codeGenerator,
      Dependencies(aggregating = false, ksClass.containingFile!!),
    )
  }

  /**
   * Collect the methods + properties the generated adapter must
   * forward. Order matters: positional call IDs in the generated
   * `outboundService(...)` must match the order entries are
   * appended in `ziplineFunctions(...)`.
   *
   * Ordering policy:
   * 1. User-declared functions (declaration order).
   * 2. User-declared properties (declaration order).
   * 3. Inherited members (appLifecycle from AppService, close
   *    from ZiplineService, etc.) — declaration order on the
   *    supertype chain.
   *
   * The user-first ordering keeps positional IDs stable across
   * additions to inherited supertypes (Zipline can't change
   * `close()`'s position relative to user methods, even if a new
   * version of AppService adds a member above it).
   */
  private fun collectMembers(ksClass: KSClassDeclaration): List<Member> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<Member>()

    fun addFunctions(decls: Sequence<KSFunctionDeclaration>) {
      decls.forEach { fn ->
        // Skip kotlin.Any inherited methods.
        if (fn.simpleName.asString() in DEFAULT_ANY_METHODS) return@forEach
        if (!fn.isInstanceMethod()) return@forEach
        val key = fn.simpleName.asString() + ":" + fn.parameters.size
        if (!seen.add(key)) return@forEach
        result.add(Member.Function(fn))
      }
    }

    fun addProperties(decls: Sequence<KSPropertyDeclaration>) {
      decls.forEach { prop ->
        if (prop.isAbstract().not() && prop.containingFile != null) return@forEach
        val key = "prop:${prop.simpleName.asString()}"
        if (!seen.add(key)) return@forEach
        result.add(Member.Property(prop))
      }
    }

    // User-declared first.
    addFunctions(ksClass.getDeclaredFunctions())
    addProperties(ksClass.getDeclaredProperties())

    // Then inherited from supertypes in source-order.
    ksClass.superTypes.forEach { superTypeRef ->
      val superDecl = superTypeRef.resolve().declaration as? KSClassDeclaration ?: return@forEach
      addFunctions(superDecl.getAllFunctions())
      addProperties(superDecl.getAllProperties())
    }

    return result
  }

  // --- KotlinPoet building ---

  private fun buildAdapterClass(
    interfaceType: ClassName,
    generatedName: String,
    serialNameFqn: String,
    members: List<Member>,
    ksClass: KSClassDeclaration,
  ): TypeSpec {
    val adapterSuper = FqNames.KonduitAppServiceAdapter
      .parameterizedBy(interfaceType)
    val callHandlerType = FqNames.KonduitOutboundCallHandler
    val outboundMarker = FqNames.KonduitOutboundService
    val ziplineFunctionListType = LIST.parameterizedBy(
      FqNames.ZiplineFunction.parameterizedBy(interfaceType),
    )
    val kSerializerListType = LIST.parameterizedBy(
      FqNames.KSerializer.parameterizedBy(STAR),
    )

    val ctor = FunSpec.constructorBuilder()
      .addParameter("serializers", kSerializerListType)
      .addParameter(
        ParameterSpec.builder("serialName", String::class)
          .defaultValue("%S", serialNameFqn)
          .build(),
      )
      .build()

    val simpleNameProp = PropertySpec.builder("simpleName", String::class)
      .addModifiers(KModifier.OVERRIDE)
      .initializer("%S", interfaceType.simpleName)
      .build()

    val ziplineFnsBody = CodeBlock.builder()
      .addStatement(
        "val out = mutableListOf<%T>()",
        FqNames.ZiplineFunction.parameterizedBy(interfaceType),
      )
    members.forEachIndexed { _, member ->
      val signature = member.signatureString()
      val returnType = member.returnTypeName(ksClass)
      val resultSerializer = member.resultSerializerExpr(ksClass)
      ziplineFnsBody.add(
        "out.add(\n  %M<%T>(\n    id = %S,\n    signature = %S,\n    resultSerializer = %L,\n    call = { %L },\n  ),\n)\n",
        com.squareup.kotlinpoet.MemberName(FqNames.konduitReturningFunction.packageName, FqNames.konduitReturningFunction.simpleName),
        interfaceType,
        member.idString(),
        signature,
        resultSerializer,
        member.callLambdaBody(),
      )
      // Suppress unused-var warning for returnType — referenced
      // only via resultSerializer's `%T`-formatted block, but kept
      // here so future maintainers can extend the codegen with
      // additional per-return-type logic without re-walking KSP.
      @Suppress("UNUSED_EXPRESSION") returnType
    }
    ziplineFnsBody.addStatement("return out")

    val ziplineFnsFn = FunSpec.builder("ziplineFunctions")
      .addModifiers(KModifier.OVERRIDE)
      .addParameter("serializersModule", FqNames.SerializersModule)
      .returns(ziplineFunctionListType)
      .addCode(ziplineFnsBody.build())
      .build()

    val outboundBody = CodeBlock.builder()
      .add("return object : %T, %T {\n", interfaceType, outboundMarker)
      .indent()
      .addStatement("override val callHandler: %T = callHandler", callHandlerType)
    members.forEachIndexed { index, member ->
      outboundBody.add(member.outboundOverride(index))
    }
    outboundBody.unindent().add("}\n")

    val outboundFn = FunSpec.builder("outboundService")
      .addModifiers(KModifier.OVERRIDE)
      .addParameter("callHandler", callHandlerType)
      .returns(interfaceType)
      .addCode(outboundBody.build())
      .build()

    return TypeSpec.classBuilder(generatedName)
      // INTERNAL, not PUBLIC. The user-written
      // `companion object { internal class Adapter(...) : GeneratedXxxAdapter(...) }`
      // wrapper that Zipline IR looks up by name is itself internal,
      // so the generated base class can stay internal too. Making
      // it public would expose Zipline's internal `OutboundCallHandler`
      // type through this module's API surface, which Kotlin
      // rejects with `EXPOSED_PARAMETER_TYPE` unless we widen the
      // file-level @Suppress. Internal classes don't trigger that
      // check.
      .addModifiers(KModifier.INTERNAL, KModifier.OPEN)
      .superclass(adapterSuper)
      .addSuperclassConstructorParameter("serializers")
      .addSuperclassConstructorParameter("serialName")
      .primaryConstructor(ctor)
      .addProperty(simpleNameProp)
      .addFunction(ziplineFnsFn)
      .addFunction(outboundFn)
      .build()
  }

  // --- Member abstraction ---

  /**
   * Wraps a function or property declaration uniformly. The
   * generator only needs `(id, signature, returnType,
   * resultSerializerExpr, callLambdaBody, outboundOverride)` per
   * member.
   */
  private sealed class Member {
    abstract fun idString(): String
    abstract fun signatureString(): String
    abstract fun returnTypeName(host: KSClassDeclaration): TypeName
    abstract fun resultSerializerExpr(host: KSClassDeclaration): CodeBlock
    abstract fun callLambdaBody(): CodeBlock
    abstract fun outboundOverride(positionalId: Int): CodeBlock

    class Function(val fn: KSFunctionDeclaration) : Member() {
      override fun idString(): String = fn.simpleName.asString()

      override fun signatureString(): String {
        val params = fn.parameters.joinToString {
          val t = it.type.resolve()
          (it.name?.asString() ?: "_") + ": " + (t.declaration.qualifiedName?.asString() ?: "?")
        }
        val returnFqn = fn.returnType?.resolve()?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"
        return "fun ${fn.simpleName.asString()}($params): $returnFqn"
      }

      override fun returnTypeName(host: KSClassDeclaration): TypeName =
        (fn.returnType?.resolve()?.toTypeName()) ?: UNIT

      override fun resultSerializerExpr(host: KSClassDeclaration): CodeBlock {
        val returnType = fn.returnType?.resolve()
        return resolveSerializerExpr(returnType, returnTypeName(host))
      }

      override fun callLambdaBody(): CodeBlock {
        val returnType = fn.returnType?.resolve()
        // For Unit returns, append `; Unit` so the lambda satisfies
        // the (T) -> Any? contract in konduitReturningFunction.
        val isUnit = returnType?.declaration?.qualifiedName?.asString() == "kotlin.Unit"
        return if (isUnit) {
          CodeBlock.of("it.%N(); Unit", fn.simpleName.asString())
        } else {
          CodeBlock.of("it.%N()", fn.simpleName.asString())
        }
      }

      override fun outboundOverride(positionalId: Int): CodeBlock {
        val name = fn.simpleName.asString()
        val returnType = fn.returnType?.resolve()
        val returnTypeName = returnType?.toTypeName() ?: UNIT
        val isUnit = returnType?.declaration?.qualifiedName?.asString() == "kotlin.Unit"
        return if (isUnit) {
          CodeBlock.of(
            "override fun %N() { callHandler.call(this, %L) }\n",
            name, positionalId,
          )
        } else {
          CodeBlock.of(
            "override fun %N(): %T = callHandler.call(this, %L) as %T\n",
            name, returnTypeName, positionalId, returnTypeName,
          )
        }
      }
    }

    class Property(val prop: KSPropertyDeclaration) : Member() {
      override fun idString(): String = prop.simpleName.asString()

      override fun signatureString(): String {
        val typeFqn = prop.type.resolve().declaration.qualifiedName?.asString() ?: "?"
        return "fun ${prop.simpleName.asString()}(): $typeFqn"
      }

      override fun returnTypeName(host: KSClassDeclaration): TypeName =
        prop.type.resolve().toTypeName()

      override fun resultSerializerExpr(host: KSClassDeclaration): CodeBlock =
        resolveSerializerExpr(prop.type.resolve(), returnTypeName(host))

      override fun callLambdaBody(): CodeBlock =
        CodeBlock.of("it.%N", prop.simpleName.asString())

      override fun outboundOverride(positionalId: Int): CodeBlock {
        val typeName = returnTypeName(host = prop.parentDeclaration as KSClassDeclaration)
        return CodeBlock.of(
          "override val %N: %T\n  get() = callHandler.call(this, %L) as %T\n",
          prop.simpleName.asString(), typeName, positionalId, typeName,
        )
      }
    }
  }

  // --- Serializer-shape resolution ---

  private companion object {
    val DEFAULT_ANY_METHODS = setOf("equals", "hashCode", "toString")

    /**
     * Choose the right serializer-building expression for the
     * given return type. Returning a `ZiplineService` subtype
     * (including `ZiplineTreehouseUi`, `AppLifecycle`, …) routes
     * to `ziplineServiceSerializer<T>()`. Everything else routes
     * through `serializersModule.serializer<T>()` so the wire
     * format follows the adopter's configured Json.
     */
    fun resolveSerializerExpr(returnType: KSType?, typeName: TypeName): CodeBlock {
      if (returnType == null) {
        return CodeBlock.of("serializersModule.%M<%T>()",
          com.squareup.kotlinpoet.MemberName(FqNames.serializer.packageName, FqNames.serializer.simpleName),
          UNIT)
      }
      val classDecl = returnType.declaration as? KSClassDeclaration
      val isZiplineService = classDecl?.getAllSuperTypes()?.any { st ->
        st.declaration.qualifiedName?.asString() == FqNames.ZiplineService.canonicalName
      } == true
      return if (isZiplineService) {
        CodeBlock.of(
          "%M<%T>()",
          com.squareup.kotlinpoet.MemberName(
            FqNames.ziplineServiceSerializer.packageName,
            FqNames.ziplineServiceSerializer.simpleName,
          ),
          typeName,
        )
      } else {
        CodeBlock.of(
          "serializersModule.%M<%T>()",
          com.squareup.kotlinpoet.MemberName(
            FqNames.serializer.packageName,
            FqNames.serializer.simpleName,
          ),
          typeName,
        )
      }
    }

    fun KSFunctionDeclaration.isInstanceMethod(): Boolean = !this.isConstructor() &&
      // Filter out static-style functions if any sneak through; for
      // interfaces these should never appear, but defensively skip.
      this.functionKind.name == "MEMBER"
  }
}

/**
 * Carries a [com.google.devtools.ksp.symbol.KSNode] alongside the
 * error message so KSP can emit it at the right source location.
 */
internal class ProcessingException(
  message: String,
  val symbol: KSAnnotated,
) : RuntimeException(message)
