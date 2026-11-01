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

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName

/** One `@KeliverBridge` interface, checked. */
internal class Bridge(
  val declaration: KSClassDeclaration,
  val className: ClassName,
  val name: String,
  val version: Int,
  val functions: List<BridgeFunction>,
  val shape: BridgeShape,
)

internal class BridgeFunction(
  val name: String,
  val parameters: List<BridgeParameter>,
  /** Null for Unit. */
  val returnType: TypeName?,
  val suspending: Boolean,
  val timeoutSeconds: Int,
)

internal class BridgeParameter(val name: String, val type: TypeName)
