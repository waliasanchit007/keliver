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
package dev.keliver.compose.generator

/**
 * A Kotlin type as Compose declares it. [fq] is the classifier with nested classes joined by '.'
 * (`androidx.compose.ui.Alignment.Vertical`); a function type is `kotlin.FunctionN` with its
 * parameter types then its return type in [args], [receiver] for an extension function type.
 */
data class TypeRef(
  val fq: String,
  val nullable: Boolean = false,
  val args: List<TypeRef> = emptyList(),
  val composable: Boolean = false,
  val receiver: TypeRef? = null,
  val suspend: Boolean = false,
) {
  val isFunction: Boolean get() = fq.startsWith("kotlin.Function") || fq.startsWith("kotlin.coroutines.SuspendFunction")
  val functionParams: List<TypeRef> get() = if (isFunction) args.dropLast(1) else emptyList()
  val functionReturn: TypeRef? get() = if (isFunction) args.last() else null
  val simpleName: String get() = fq.substringAfterLast('.')

  /** The canonical form used in lock keys: stable, readable, independent of imports. */
  fun canonical(): String = buildString {
    if (isFunction) {
      if (composable) append("@Composable ")
      if (suspend) append("suspend ")
      receiver?.let { append(it.canonical()).append('.') }
      append(functionParams.joinToString(",", "(", ")") { it.canonical() })
      append("->").append(functionReturn!!.canonical())
    } else {
      append(fq)
      if (args.isNotEmpty()) append(args.joinToString(",", "<", ">") { it.canonical() })
    }
  }.let { if (nullable) (if (isFunction) "($it)?" else "$it?") else it }
}

data class ParamInfo(
  val name: String,
  val type: TypeRef,
  val hasDefault: Boolean,
  /** The default expression's source text, from the sources jar. */
  var defaultText: String? = null,
)

enum class DeclKind { WIDGET, MODIFIER, SCOPED_MODIFIER }

data class DeclInfo(
  /** The spec from the config, as written. */
  val spec: String,
  val pkg: String,
  val name: String,
  /** A scope interface (`RowScope`) for a member modifier, else null. */
  val owner: String?,
  val receiver: TypeRef?,
  val params: List<ParamInfo>,
  val returns: TypeRef,
  val isInline: Boolean,
  var isComposable: Boolean = false,
  var kdoc: String? = null,
  /** Simple name → fully qualified name, from the source file's imports. */
  var imports: Map<String, String> = emptyMap(),
  var sourcePath: String? = null,
  /** Compose's `@Deprecated` level (`WARNING`, `ERROR`, `HIDDEN`) on this declaration, or null. */
  var deprecation: String? = null,
  var deprecationMessage: String? = null,
) {
  /** Kept in the jar for binary compatibility only: callers cannot see it (design §3). */
  val isHidden: Boolean get() = deprecation == "HIDDEN"

  val kind: DeclKind
    get() = when {
      returns.fq == MODIFIER && owner != null -> DeclKind.SCOPED_MODIFIER
      returns.fq == MODIFIER -> DeclKind.MODIFIER
      else -> DeclKind.WIDGET
    }

  /** The lock key: package, owner, name and the full parameter list with types. */
  val key: String
    get() = buildString {
      append(pkg).append('/')
      owner?.let { append(it).append('#') }
      append(name)
      append(params.joinToString(",", "(", ")") { "${it.name}:${it.type.canonical()}" })
    }

  /** The guest's package for this declaration: `dev.keliver.` + the package without `androidx.`. */
  val mirrorPkg: String get() = mirrorPackage(pkg)
}

const val MODIFIER = "androidx.compose.ui.Modifier"

fun mirrorPackage(pkg: String): String = "dev.keliver." + pkg.removePrefix("androidx.")

fun mirrorFq(fq: String): String = when {
  fq.startsWith("kotlin.") -> fq
  else -> {
    // Split the package from the (possibly nested) class name: package segments start lower case.
    val parts = fq.split('.')
    val firstClass = parts.indexOfFirst { it.first().isUpperCase() }.let { if (it < 0) parts.size - 1 else it }
    mirrorPackage(parts.take(firstClass).joinToString(".")) + "." + parts.drop(firstClass).joinToString(".")
  }
}

/** The package of a (possibly nested) class name. */
fun packageOf(fq: String): String {
  val parts = fq.split('.')
  val firstClass = parts.indexOfFirst { it.first().isUpperCase() }.let { if (it < 0) parts.size - 1 else it }
  return parts.take(firstClass).joinToString(".")
}
