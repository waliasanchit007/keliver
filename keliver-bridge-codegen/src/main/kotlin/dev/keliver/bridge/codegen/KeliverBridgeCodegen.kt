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
package dev.keliver.bridge.codegen

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isInternal
import com.google.devtools.ksp.isPrivate
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
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Reads each `@KeliverBridge` interface, refuses the shapes the design rules out (section 3 of
 * `docs/BRIDGE_CODEGEN_DESIGN.md`), and writes `<Name>Bridge.kt` next to it ([BridgeEmitter]).
 * Every refusal names the interface or the function and says what to do instead.
 */
internal class KeliverBridgeCodegen(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
) : SymbolProcessor {
  override fun process(resolver: Resolver): List<KSAnnotated> {
    val deferred = mutableListOf<KSAnnotated>()
    for (symbol in resolver.getSymbolsWithAnnotation(KELIVER_BRIDGE)) {
      if (!symbol.validate()) {
        deferred += symbol
        continue
      }
      try {
        val bridge = read(symbol)
        // SHAPE reads every type the functions reach, often in other files: each is an input,
        // so a change to one of them regenerates the bridge.
        val files = (listOf(bridge.declaration) + bridge.shape.declarations).mapNotNull { it.containingFile }.distinct()
        BridgeEmitter(bridge).file()
          .writeTo(codeGenerator, Dependencies(aggregating = false, *files.toTypedArray()))
      } catch (e: Refused) {
        logger.error("@KeliverBridge: ${e.message}", e.node)
      }
    }
    return deferred
  }

  private fun read(symbol: KSAnnotated): Bridge {
    val declaration = symbol as? KSClassDeclaration
    if (declaration == null || declaration.classKind != ClassKind.INTERFACE) {
      refuse(symbol, "only an interface can be a bridge")
    }
    val simpleName = declaration.simpleName.asString()
    if (declaration.parentDeclaration != null) refuse(declaration, "$simpleName must be a top-level interface")
    if (declaration.typeParameters.isNotEmpty()) refuse(declaration, "$simpleName must not be generic")
    if (declaration.isInternal() || declaration.isPrivate()) {
      refuse(declaration, "$simpleName must be public: the generated Bridge object exposes it")
    }
    if (declaration.superTypes.any { it.resolve().declaration.qualifiedName?.asString() != "kotlin.Any" }) {
      refuse(declaration, "$simpleName must not extend another interface: declare every function on it")
    }
    if (declaration.getDeclaredProperties().any()) {
      refuse(declaration, "$simpleName must not declare properties: a bridge has functions only")
    }

    val annotation = declaration.annotation(KELIVER_BRIDGE)!!
    // KSP does not always see an annotation's default values when it comes from a library klib
    // (a multiplatform contract module's metadata compilation): the defaults are repeated here,
    // as declared on @KeliverBridge.
    val name = (annotation.argument("name") as String? ?: "").ifEmpty { simpleName }
    if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*"))) {
      refuse(declaration, "name '$name' must be letters, digits, '_' and '.' (it is the binding name)")
    }
    val version = annotation.argument("version") as Int? ?: 1
    if (version < 1) refuse(declaration, "version must be 1 or more: $version")
    val timeoutSeconds = annotation.argument("timeoutSeconds") as Int? ?: 30
    if (timeoutSeconds < 0) refuse(declaration, "timeoutSeconds must be 0 (no limit) or more: $timeoutSeconds")

    val functions = declaration.getDeclaredFunctions().toList()
    if (functions.isEmpty()) refuse(declaration, "$simpleName declares no functions")
    functions.groupBy { it.simpleName.asString() }.values.firstOrNull { it.size > 1 }?.let {
      refuse(it[1], "$simpleName.${it[1].simpleName.asString()} is overloaded: give each function its own name (Objective-C export would rename one)")
    }
    return Bridge(
      declaration = declaration,
      className = declaration.toClassName(),
      name = name,
      version = version,
      functions = functions.map { readFunction(simpleName, timeoutSeconds, it) },
      shape = BridgeShape.of(functions),
    )
  }

  private fun readFunction(owner: String, defaultTimeout: Int, function: KSFunctionDeclaration): BridgeFunction {
    val name = function.simpleName.asString()
    val where = "$owner.$name"
    if (!function.isAbstract) {
      refuse(function, "$where must not have a body: every function is served by the host")
    }
    if (name == "close") refuse(function, "$where clashes with ZiplineService.close(): give it another name")
    if (OBJC_FAMILIES.any { name == it || (name.startsWith(it) && name[it.length].isUpperCase()) }) {
      refuse(function, "$where starts with an Objective-C method family (init, new, copy, mutableCopy, alloc), which Kotlin/Native renames for Swift: give it another name")
    }
    if (function.extensionReceiver != null) refuse(function, "$where must not be an extension function")
    if (function.typeParameters.isNotEmpty()) refuse(function, "$where must not be generic")
    val suspending = Modifier.SUSPEND in function.modifiers
    val returnType = function.returnType!!.resolve().expanded()
    val returnsUnit = returnType.qualifiedName() == "kotlin.Unit" && !returnType.isMarkedNullable
    if (!suspending && !returnsUnit) {
      refuse(function, "$where must be suspend, or return Unit (fire and forget): a non-suspend call would hold the guest's thread until the host answered")
    }
    if (suspending && returnType.qualifiedName() in COLLECTIONS) {
      refuse(function, "$where must not return a collection from a suspend function (KNOWN_BUGS U1): wrap it in one @Serializable class")
    }
    if (!returnsUnit) checkValueType(function, "$where's result", returnType)

    val parameters = function.parameters.map { parameter ->
      val parameterName = parameter.name!!.asString()
      if (parameter.isVararg) refuse(parameter, "$where's parameter $parameterName must not be vararg")
      if (parameter.hasDefault) refuse(parameter, "$where's parameter $parameterName must not have a default value")
      if (parameterName in GENERATED_NAMES) {
        refuse(parameter, "$where's parameter $parameterName clashes with a name the generated code uses (${GENERATED_NAMES.sorted().joinToString()}): rename it")
      }
      val type = parameter.type.resolve().expanded()
      when {
        type.isFunctionType || type.isSuspendFunctionType ->
          refuse(parameter, "$where's parameter $parameterName must not be a function (KNOWN_BUGS U11): an action takes values")
        type.qualifiedName() == FLOW ->
          refuse(parameter, "$where's parameter $parameterName must not be a Flow: an action takes values")
        type.isZiplineService() ->
          refuse(parameter, "$where's parameter $parameterName must not be a ZiplineService: an action takes values")
      }
      checkValueType(parameter, "$where's parameter $parameterName", type)
      BridgeParameter(parameterName, parameter.type.toTypeName())
    }

    val timeout = function.annotation(BRIDGE_TIMEOUT)?.argument("seconds") as Int? ?: defaultTimeout
    if (timeout < 0) refuse(function, "$where's @BridgeTimeout must be 0 (no limit) or more: $timeout")
    return BridgeFunction(
      name = name,
      parameters = parameters,
      returnType = if (returnsUnit) null else function.returnType!!.toTypeName(),
      suspending = suspending,
      timeoutSeconds = timeout,
    )
  }

  /** A primitive, String, an enum, a @Serializable class, or a List or Map of those. */
  private fun checkValueType(node: KSNode, what: String, unexpanded: KSType) {
    val type = unexpanded.expanded()
    val qualifiedName = type.qualifiedName()
    when {
      qualifiedName in VALUES -> Unit
      qualifiedName == "kotlin.collections.List" || qualifiedName == "kotlin.collections.Map" ->
        type.arguments.forEach { argument ->
          val argumentType = argument.type?.resolve() ?: refuse(node, "$what must not use a star projection")
          checkValueType(node, what, argumentType)
        }
      else -> {
        val declaration = type.declaration as? KSClassDeclaration
        val ok = declaration != null &&
          (declaration.classKind == ClassKind.ENUM_CLASS || declaration.annotation(SERIALIZABLE) != null)
        if (!ok) {
          refuse(node, "$what is $qualifiedName: use a primitive, String, an enum, a @Serializable class, or a List or Map of those")
        }
      }
    }
  }

  private fun KSType.isZiplineService(): Boolean {
    val declaration = declaration as? KSClassDeclaration ?: return false
    return declaration.qualifiedName?.asString() == ZIPLINE_SERVICE ||
      declaration.getAllSuperTypes().any { it.qualifiedName() == ZIPLINE_SERVICE }
  }

  private class Refused(message: String, val node: KSNode) : Exception(message)

  private fun refuse(node: KSNode, message: String): Nothing = throw Refused(message, node)
}

internal fun KSType.qualifiedName(): String? = declaration.qualifiedName?.asString()

/** The type a typealias stands for (with its nullability), or this type. */
internal fun KSType.expanded(): KSType {
  val alias = declaration as? KSTypeAlias ?: return this
  val target = alias.type.resolve().expanded()
  return if (isMarkedNullable) target.makeNullable() else target
}

internal fun KSAnnotated.annotation(qualifiedName: String): KSAnnotation? =
  annotations.firstOrNull { it.annotationType.resolve().qualifiedName() == qualifiedName }

internal fun KSAnnotation.argument(name: String): Any? =
  arguments.firstOrNull { it.name?.asString() == name }?.value
    ?: defaultArguments.firstOrNull { it.name?.asString() == name }?.value

internal const val KELIVER_BRIDGE = "dev.keliver.bridge.api.KeliverBridge"
internal const val BRIDGE_TIMEOUT = "dev.keliver.bridge.api.BridgeTimeout"
internal const val SERIALIZABLE = "kotlinx.serialization.Serializable"
internal const val SERIAL_NAME = "kotlinx.serialization.SerialName"
internal const val TRANSIENT = "kotlinx.serialization.Transient"
private const val ZIPLINE_SERVICE = "app.cash.zipline.ZiplineService"
private const val FLOW = "kotlinx.coroutines.flow.Flow"

// Kotlin/Native renames a function in these Objective-C method families for Swift.
private val OBJC_FAMILIES = listOf("alloc", "copy", "mutableCopy", "new", "init")

// Names the generated code declares around the app's parameters (BridgeEmitter).
private val GENERATED_NAMES = setOf("call", "done", "fail", "impl", "guard", "callbacks", "service", "zipline", "context", "log", "it", "e")

private val VALUES = setOf(
  "kotlin.Boolean", "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long",
  "kotlin.Float", "kotlin.Double", "kotlin.Char", "kotlin.String",
)

private val COLLECTIONS = setOf(
  "kotlin.collections.List", "kotlin.collections.Set", "kotlin.collections.Map",
  "kotlin.collections.Collection", "kotlin.collections.Iterable",
  "kotlin.collections.MutableList", "kotlin.collections.MutableSet", "kotlin.collections.MutableMap",
  "kotlin.Array",
)
