package dev.keliver.portal.codegen

import dev.keliver.tooling.schema.FqType
import dev.keliver.tooling.schema.Widget

/**
 * The portal's supported property kinds — the bridge between schema trait types
 * and what the WidgetNode tree wire format (i/d/b/s/li/lf tags) can carry.
 */
enum class MappedKind { TEXT, INT, BOOL, DOUBLE, FLOAT, INT_LIST, FLOAT_LIST, DP, CONSTRAINT, CROSS_AXIS, MAIN_AXIS, OVERFLOW }

data class MappedProp(
  val name: String,
  val kind: MappedKind,
  val required: Boolean,
  val defaultExpr: String?,
) {
  val isColor: Boolean get() = kind == MappedKind.INT && name.endsWith("Argb")
}

sealed interface WidgetPlan {
  data class Include(
    val name: String,               // simple name, == WidgetNode.type
    val composePackage: String,     // e.g. dev.keliver.material.compose
    val category: String,           // "Material" / "Layout"
    val props: List<MappedProp>,
    val skippedProps: List<String>, // unsupported-with-default, omitted from calls
    val events: List<String>,       // nullable events, omitted from calls (P1: no bindings)
    val hasChildren: Boolean,
  ) : WidgetPlan

  data class Exclude(val name: String, val reason: String) : WidgetPlan
}

private fun FqType.key(): String = names.joinToString(".")

internal fun mapType(t: FqType): MappedKind? = when {
  t.key() == "kotlin.String" -> MappedKind.TEXT
  t.key() == "kotlin.Int" -> MappedKind.INT
  t.key() == "kotlin.Boolean" -> MappedKind.BOOL
  t.key() == "kotlin.Double" -> MappedKind.DOUBLE
  t.key() == "kotlin.Float" -> MappedKind.FLOAT
  t.key() == "kotlin.collections.List" && t.parameterTypes.size == 1 &&
    t.parameterTypes[0].key() == "kotlin.Int" -> MappedKind.INT_LIST
  t.key() == "kotlin.collections.List" && t.parameterTypes.size == 1 &&
    t.parameterTypes[0].key() == "kotlin.Float" -> MappedKind.FLOAT_LIST
  t.key() == "dev.keliver.ui.Dp" -> MappedKind.DP
  t.key() == "dev.keliver.layout.api.Constraint" -> MappedKind.CONSTRAINT
  t.key() == "dev.keliver.layout.api.CrossAxisAlignment" -> MappedKind.CROSS_AXIS
  t.key() == "dev.keliver.layout.api.MainAxisAlignment" -> MappedKind.MAIN_AXIS
  t.key() == "dev.keliver.layout.api.Overflow" -> MappedKind.OVERFLOW
  else -> null
}

internal fun defaultInt(e: String?): Int = e?.trim()?.toIntOrNull() ?: 0
internal fun defaultBool(e: String?): Boolean = e?.trim() == "true"
internal fun defaultDouble(e: String?): Double = e?.trim()?.removeSuffix("f")?.toDoubleOrNull() ?: 0.0
internal fun defaultString(e: String?): String =
  e?.trim()?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }?.removeSurrounding("\"") ?: ""
internal fun constraintDefault(e: String?): Int = if (e?.contains("Fill") == true) 1 else 0
internal fun crossAxisDefault(e: String?): Int = when {
  e == null -> 0
  e.contains("Center") -> 1
  e.contains("End") -> 2
  e.contains("Stretch") -> 3
  else -> 0
}
internal fun overflowDefault(e: String?): Int = if (e?.contains("Scroll") == true) 1 else 0
internal fun mainAxisDefault(e: String?): Int = when {
  e == null -> 0
  e.contains("SpaceBetween") -> 3
  e.contains("SpaceAround") -> 4
  e.contains("SpaceEvenly") -> 5
  e.contains("Center") -> 1
  e.contains("End") -> 2
  else -> 0
}

fun planWidget(composePackage: String, category: String, widget: Widget): WidgetPlan {
  val name = widget.type.names.last()
  val props = mutableListOf<MappedProp>()
  val skipped = mutableListOf<String>()
  val events = mutableListOf<String>()
  var childrenCount = 0
  for (trait in widget.traits) {
    when (trait) {
      is Widget.Property -> {
        val kind = mapType(trait.type)
        val required = trait.defaultExpression == null
        when {
          kind != null -> props += MappedProp(trait.name, kind, required, trait.defaultExpression)
          required -> return WidgetPlan.Exclude(name, "required prop '${trait.name}' has unsupported type ${trait.type}")
          else -> skipped += trait.name
        }
      }
      is Widget.Event -> {
        if (!trait.isNullable && trait.defaultExpression == null) {
          return WidgetPlan.Exclude(name, "required non-nullable event '${trait.name}'")
        }
        events += trait.name
      }
      is Widget.Children -> childrenCount++
      // Protocol* trait interfaces extend Property/Event/Children, so the
      // branches above already match them — this only satisfies exhaustiveness.
      else -> {}
    }
  }
  if (childrenCount > 1) return WidgetPlan.Exclude(name, "multiple children slots ($childrenCount)")
  return WidgetPlan.Include(name, composePackage, category, props, skipped, events, childrenCount == 1)
}
