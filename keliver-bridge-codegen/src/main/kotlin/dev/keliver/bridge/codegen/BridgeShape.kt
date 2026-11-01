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

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import java.security.MessageDigest

/**
 * The contract's shape as one line per function and per type it reaches, and its hash
 * (`docs/BRIDGE_CODEGEN_DESIGN.md`, section 4, `SHAPE`). A change that keeps Zipline's call ids
 * but fails only when decoded changes it too: a @Serializable class's or field's serial name,
 * a field's type, an enum's constants (by serial name). Parameter names are left out: they do
 * not cross the wire. Fields are the properties with a backing field and no @Transient, which is
 * what kotlinx.serialization encodes for a plain @Serializable class.
 */
internal class BridgeShape private constructor(val lines: List<String>) {
  val hash: String = MessageDigest.getInstance("SHA-256")
    .digest(lines.joinToString("\n").toByteArray())
    .joinToString("") { "%02x".format(it) }
    .take(16)

  companion object {
    fun of(functions: List<KSFunctionDeclaration>): BridgeShape {
      val lines = sortedSetOf<String>()
      val seen = mutableSetOf<String>()
      fun type(type: KSType): String {
        val declaration = type.declaration as? KSClassDeclaration
        if (declaration != null) describe(declaration, lines, seen, ::type)
        val arguments = type.arguments.map { it.type?.resolve()?.let(::type) ?: "*" }
        return (type.qualifiedName() ?: "?") +
          (if (arguments.isEmpty()) "" else arguments.joinToString(",", "<", ">")) +
          (if (type.isMarkedNullable) "?" else "")
      }
      for (function in functions) {
        val parameters = function.parameters.joinToString(",") { type(it.type.resolve()) }
        val suspend = if (Modifier.SUSPEND in function.modifiers) "suspend " else ""
        lines += "${suspend}fun ${function.simpleName.asString()}($parameters): ${type(function.returnType!!.resolve())}"
      }
      return BridgeShape(lines.toList())
    }

    private fun describe(
      declaration: KSClassDeclaration,
      lines: MutableSet<String>,
      seen: MutableSet<String>,
      type: (KSType) -> String,
    ) {
      val qualifiedName = declaration.qualifiedName?.asString() ?: return
      if (!seen.add(qualifiedName)) return
      when {
        declaration.classKind == ClassKind.ENUM_CLASS -> {
          val constants = declaration.declarations
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .map { it.serialName(it.simpleName.asString()) }
          lines += "enum ${declaration.serialName(qualifiedName)} { ${constants.joinToString(", ")} }"
        }
        declaration.annotation(SERIALIZABLE) != null -> {
          val fields = declaration.getDeclaredProperties()
            .filter { it.hasBackingField && it.annotation(TRANSIENT) == null }
            .map { "${it.serialName(it.simpleName.asString())}: ${type(it.type.resolve())}" }
          lines += "class ${declaration.serialName(qualifiedName)} { ${fields.joinToString(", ")} }"
        }
      }
    }

    private fun com.google.devtools.ksp.symbol.KSAnnotated.serialName(default: String): String =
      annotation(SERIAL_NAME)?.argument("value") as String? ?: default
  }
}
