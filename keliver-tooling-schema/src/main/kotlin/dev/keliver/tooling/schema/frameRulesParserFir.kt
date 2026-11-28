/*
 * Copyright (C) 2026 Keliver contributors.
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
@file:OptIn(SymbolInternals::class)

package dev.keliver.tooling.schema

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.declaredProperties
import org.jetbrains.kotlin.fir.declarations.getSealedClassInheritors
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.resolve.fqName
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

// NF5's frame-rule annotations (dev.keliver.schema's frameRules.kt; docs/SHOW_BEFORE_COMPUTE.md 2.4).

private object FrameFqNames {
  val Replayable = FqName("dev.keliver.schema.Replayable")
  val Scrolls = FqName("dev.keliver.schema.Scrolls")
  val NeverCached = FqName("dev.keliver.schema.NeverCached")
  val BlocksFrameWhenTrue = FqName("dev.keliver.schema.BlocksFrameWhenTrue")
  val StyleToken = FqName("dev.keliver.schema.StyleToken")
  val NotData = FqName("dev.keliver.schema.NotData")
}

internal fun List<FirAnnotation>.hasReplayable(session: FirSession): Boolean =
  any { it.fqName(session) == FrameFqNames.Replayable }

internal fun List<FirAnnotation>.hasScrolls(session: FirSession): Boolean =
  any { it.fqName(session) == FrameFqNames.Scrolls }

/** The one frame-rule annotation in [this], or null; more than one is an error naming [where]. */
internal fun List<FirAnnotation>.findFrameRule(session: FirSession, where: String): FramePropertyRule? {
  val names = map { it.fqName(session) }
  val rules = listOfNotNull(
    FramePropertyRule.NeverCached.takeIf { FrameFqNames.NeverCached in names },
    FramePropertyRule.BlocksFrameWhenTrue.takeIf { FrameFqNames.BlocksFrameWhenTrue in names },
    FramePropertyRule.StyleToken.takeIf { FrameFqNames.StyleToken in names },
    FramePropertyRule.NotData.takeIf { FrameFqNames.NotData in names },
  )
  require(rules.size <= 1) {
    "$where has ${rules.joinToString(" and ") { "@$it" }}: a property carries at most one of " +
      "@NeverCached, @BlocksFrameWhenTrue, @StyleToken and @NotData"
  }
  return rules.singleOrNull()
}

/**
 * Checks that [rule] fits the property's [type]: `@BlocksFrameWhenTrue` on a non-null Boolean,
 * `@StyleToken` only where the type carries a String, `@NotData` only where it does not. [typeName]
 * is the type as messages name it.
 */
internal fun checkFrameRule(
  rule: FramePropertyRule?,
  type: ConeKotlinType,
  typeName: String,
  containsString: Boolean,
  where: String,
) {
  when (rule) {
    FramePropertyRule.BlocksFrameWhenTrue -> require(
      (type as? ConeClassLikeType)?.lookupTag?.classId == BOOLEAN && !type.isMarkedNullable,
    ) {
      "@BlocksFrameWhenTrue $where must be a non-null Boolean. Found: $typeName"
    }
    FramePropertyRule.StyleToken -> require(containsString) {
      "@StyleToken $where: $typeName contains no String. " +
        "Use @NotData for a layout or style value without strings."
    }
    FramePropertyRule.NotData -> require(!containsString) {
      "@NotData $where: $typeName is or may contain a String, so it is text " +
        "(replaced in Shell) or, if its strings are style or asset codes, @StyleToken."
    }
    FramePropertyRule.NeverCached, null -> Unit
  }
}

/**
 * Whether a value of [type] may carry a String (text or a URL), as far as the compiler's view of the
 * type shows. It fails closed: what it cannot see into counts as carrying one.
 *
 * - `String`, `CharSequence` and `Char` do; numbers, booleans, `Unit` and primitive arrays do not;
 *   `kotlinx.serialization.json` values (any JSON) do.
 * - A type argument that does makes the type do (`List<String>`, `Map<String, Int>`); a star
 *   projection counts as one that does.
 * - An enum or an object does not: its values are a fixed set of names, not text.
 * - A final class does when any of its properties does (stored or computed), or a superclass's; a
 *   value class looks at its constructor's one property only (the others are computed from it). A
 *   sealed class or interface does when any of its subclasses does.
 * - Any other class or interface does: its subclasses cannot be known (an abstract `Painter` may be
 *   one that loads a URL).
 *
 * `@StyleToken` is checked against this, so on a type the parser cannot see into it always passes:
 * `Shell` keeps what that type's codec writes (the review of #331, L2).
 */
internal fun FirSession.containsString(type: ConeKotlinType): Boolean = containsString(type, mutableSetOf())

private fun FirSession.containsString(type: ConeKotlinType, seen: MutableSet<ClassId>): Boolean {
  val t = if (type is ConeFlexibleType) type.lowerBound else type
  // A type parameter inside a class's state: its argument was looked at where the class is used.
  if (t is ConeTypeParameterType) return false
  val classType = t as? ConeClassLikeType ?: return true
  val classId = classType.lookupTag.classId
  if (classId in STRING_TYPES) return true
  if (classId.packageFqName == JSON_PACKAGE) return true
  if (classId in NO_STRING_TYPES) return false
  for (argument in classType.typeArguments) {
    val argumentType = argument.type ?: return true // `*`
    if (containsString(argumentType, seen)) return true
  }
  if (classId in CONTAINER_TYPES) return false // only their arguments carry values
  return classContainsString(classId, seen)
}

private fun FirSession.classContainsString(classId: ClassId, seen: MutableSet<ClassId>): Boolean {
  if (!seen.add(classId)) return false // already being looked at, further up this walk
  val symbol = symbolProvider.getClassLikeSymbolByClassId(classId)
  if (symbol is FirTypeAliasSymbol) return containsString(symbol.resolvedExpandedTypeRef.coneType, seen)
  val klass = (symbol as? FirRegularClassSymbol)?.fir ?: return true
  return when {
    klass.classKind == ClassKind.ENUM_CLASS || klass.classKind == ClassKind.ENUM_ENTRY -> false
    klass.classKind == ClassKind.OBJECT -> false
    klass.status.modality == Modality.SEALED ->
      stateContainsString(klass, seen) ||
        klass.getSealedClassInheritors(this).any { classContainsString(it, seen) }
    klass.classKind == ClassKind.INTERFACE -> true
    klass.status.modality != Modality.FINAL -> true
    else -> stateContainsString(klass, seen)
  }
}

/** Whether [klass]'s own properties, or its superclasses', may carry a String. */
private fun FirSession.stateContainsString(klass: FirRegularClass, seen: MutableSet<ClassId>): Boolean {
  if (klass.status.isInline || klass.annotations.any { it.fqName(this) == JVM_INLINE }) {
    // A value class is its one constructor property; its other properties are computed from it.
    val underlying = klass.primaryConstructorIfAny(this)?.valueParameterSymbols?.singleOrNull() ?: return true
    return containsString(underlying.resolvedReturnType, seen)
  }
  val properties = klass.symbol.declaredProperties(this).filter { it.fir.receiverParameter == null }
  if (properties.any { containsString(it.resolvedReturnType, seen) }) return true
  for (superType in klass.superTypeRefs) {
    val superClass = (superType.coneType.toSymbol(this) as? FirRegularClassSymbol)?.fir ?: continue
    if (superClass.classKind != ClassKind.CLASS || superClass.symbol.classId == ANY) continue
    // `Derived : Base<String>()`: the superclass's own properties are of type `T`, its argument is here.
    for (argument in superType.coneType.typeArguments) {
      val argumentType = argument.type ?: return true // `*`
      if (containsString(argumentType, seen)) return true
    }
    if (stateContainsString(superClass, seen)) return true
  }
  return false
}

private fun classIdOf(fqName: String) = ClassId.topLevel(FqName(fqName))

private val BOOLEAN = classIdOf("kotlin.Boolean")
private val ANY = classIdOf("kotlin.Any")
private val JVM_INLINE = FqName("kotlin.jvm.JvmInline")
private val JSON_PACKAGE = FqName("kotlinx.serialization.json")

private val STRING_TYPES = listOf("kotlin.String", "kotlin.CharSequence", "kotlin.Char", "kotlin.CharArray")
  .map(::classIdOf).toSet()

private val NO_STRING_TYPES = listOf(
  "kotlin.Boolean", "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long", "kotlin.Float", "kotlin.Double",
  "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong", "kotlin.Unit", "kotlin.Nothing",
  "kotlin.BooleanArray", "kotlin.ByteArray", "kotlin.ShortArray", "kotlin.IntArray", "kotlin.LongArray",
  "kotlin.FloatArray", "kotlin.DoubleArray", "kotlin.UByteArray", "kotlin.UShortArray", "kotlin.UIntArray",
  "kotlin.ULongArray",
).map(::classIdOf).toSet()

private val CONTAINER_TYPES = listOf(
  "kotlin.Array", "kotlin.Pair", "kotlin.Triple",
  "kotlin.collections.Iterable", "kotlin.collections.MutableIterable",
  "kotlin.collections.Collection", "kotlin.collections.MutableCollection",
  "kotlin.collections.List", "kotlin.collections.MutableList",
  "kotlin.collections.Set", "kotlin.collections.MutableSet",
  "kotlin.collections.Map", "kotlin.collections.MutableMap",
).map(::classIdOf).toSet() + ClassId(FqName("kotlin.collections"), FqName("Map.Entry"), isLocal = false)
