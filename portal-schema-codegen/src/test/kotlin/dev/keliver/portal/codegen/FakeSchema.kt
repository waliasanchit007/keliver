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
package dev.keliver.portal.codegen

import dev.keliver.tooling.schema.Deprecation
import dev.keliver.tooling.schema.FqType
import dev.keliver.tooling.schema.Modifier
import dev.keliver.tooling.schema.Widget

internal fun fq(vararg names: String, params: List<FqType> = emptyList(), nullable: Boolean = false) =
  FqType(names.toList(), parameterTypes = params, nullable = nullable)

internal data class FakeProperty(
  override val name: String,
  override val type: FqType,
  override val defaultExpression: String? = null,
  override val documentation: String? = null,
  override val deprecation: Deprecation? = null,
) : Widget.Property

internal data class FakeEvent(
  override val name: String,
  override val parameters: List<Widget.Event.Parameter> = emptyList(),
  override val isNullable: Boolean = true,
  override val defaultExpression: String? = "null",
  override val documentation: String? = null,
  override val deprecation: Deprecation? = null,
) : Widget.Event

internal data class FakeChildren(
  override val name: String,
  override val scope: FqType? = null,
  override val defaultExpression: String? = null,
  override val documentation: String? = null,
  override val deprecation: Deprecation? = null,
) : Widget.Children

internal data class FakeWidget(
  override val type: FqType,
  override val traits: List<Widget.Trait>,
  override val documentation: String? = null,
  override val deprecation: Deprecation? = null,
  override val internalComposable: Boolean = false,
) : Widget

internal data class FakeModifierProperty(
  override val name: String,
  override val type: FqType,
  override val isSerializable: Boolean = true,
  override val defaultExpression: String? = null,
  override val documentation: String? = null,
  override val deprecation: Deprecation? = null,
) : Modifier.Property

internal data class FakeModifier(
  override val type: FqType,
  override val properties: List<Modifier.Property> = emptyList(),
  override val scopes: List<FqType> = emptyList(),
  override val documentation: String? = null,
  override val deprecation: Deprecation? = null,
) : Modifier
