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
