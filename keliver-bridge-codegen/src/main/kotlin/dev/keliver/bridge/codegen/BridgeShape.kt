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
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin
import java.security.MessageDigest

/**
 * The contract's shape as one line per function and per type it reaches, and its hash
 * (`docs/BRIDGE_CODEGEN_DESIGN.md`, section 4, `SHAPE`). A change that keeps Zipline's call ids
 * but fails only when decoded changes it too: a @Serializable class's or field's serial name,
 * a field's type, an enum's constants (by serial name), a sealed class's subclasses. Parameter
 * names are left out: they do not cross the wire. So is order where JSON does not keep it:
 * fields and enum constants are sorted. A field is a property with a backing field (inherited
 * ones included) and no @Transient; for a property of a class from another module, whose backing
 * fields KSP may not know, every property but a @Transient one.
 */
internal class BridgeShape private constructor(
  val lines: List<String>,
  /** Every file the shape read (classes, enums, typealiases): inputs of the generated file. */
  val files: List<KSFile>,
  /**
   * A sealed class was reached: a new subclass, in a new file, changes the shape without
   * touching any of [files], so the generated file depends on every source.
   */
  val reachesSealed: Boolean,
) {
  val hash: String = MessageDigest.getInstance("SHA-256")
    .digest(lines.joinToString("\n").toByteArray())
    .joinToString("") { "%02x".format(it) }
    .take(16)

  companion object {
    fun of(functions: List<KSFunctionDeclaration>): BridgeShape {
      val lines = sortedSetOf<String>()
      val seen = linkedMapOf<String, KSClassDeclaration>()
      val files = linkedSetOf<KSFile>()
      var reachesSealed = false
      lateinit var type: (KSType) -> String
      fun describe(declaration: KSClassDeclaration) {
        val qualifiedName = declaration.qualifiedName?.asString() ?: return
        if (seen.put(qualifiedName, declaration) != null) return
        declaration.containingFile?.let(files::add)
        // Inherited fields are read too: their classes' files are inputs as well.
        declaration.getAllSuperTypes().mapNotNull { it.declaration.containingFile }.forEach(files::add)
        when {
          declaration.classKind == ClassKind.ENUM_CLASS -> {
            val constants = declaration.declarations
              .filterIsInstance<KSClassDeclaration>()
              .filter { it.classKind == ClassKind.ENUM_ENTRY }
              .map { it.serialName(it.simpleName.asString()) }
              .sorted()
            lines += "enum ${declaration.serialName(qualifiedName)} { ${constants.joinToString(", ")} }"
          }
          declaration.annotation(SERIALIZABLE) != null -> {
            // Decided per property: an inherited one may come from another module's class.
            val fields = declaration.getAllProperties()
              .filter { (it.hasBackingField || !it.fromSource()) && it.annotation(TRANSIENT) == null }
              .map { "${it.serialName(it.simpleName.asString())}: ${type(it.type.resolve())}" }
              .sorted()
            val subclasses = declaration.getSealedSubclasses()
              .onEach { describe(it) }
              .map { it.serialName(it.qualifiedName?.asString() ?: "?") }
              .sorted()
              .toList()
            if (Modifier.SEALED in declaration.modifiers) reachesSealed = true
            val sealed = if (subclasses.isEmpty()) "" else " sealed ${subclasses.joinToString(" | ")}"
            lines += "class ${declaration.serialName(qualifiedName)}$sealed { ${fields.joinToString(", ")} }"
          }
        }
      }
      type = { unexpanded ->
        val t = unexpanded.expanded { alias -> alias.containingFile?.let(files::add) }
        (t.declaration as? KSClassDeclaration)?.let(::describe)
        val arguments = t.arguments.map { argument -> argument.type?.resolve()?.let(type) ?: "*" }
        (t.qualifiedName() ?: "?") +
          (if (arguments.isEmpty()) "" else arguments.joinToString(",", "<", ">")) +
          (if (t.isMarkedNullable) "?" else "")
      }
      for (function in functions) {
        val parameters = function.parameters.joinToString(",") { type(it.type.resolve()) }
        val suspend = if (Modifier.SUSPEND in function.modifiers) "suspend " else ""
        lines += "${suspend}fun ${function.simpleName.asString()}($parameters): ${type(function.returnType!!.resolve())}"
      }
      return BridgeShape(lines.toList(), files.toList(), reachesSealed)
    }

    private fun KSPropertyDeclaration.fromSource(): Boolean =
      (parentDeclaration ?: this).origin == Origin.KOTLIN

    private fun KSAnnotated.serialName(default: String): String =
      annotation(SERIAL_NAME)?.argument("value") as String? ?: default
  }
}
